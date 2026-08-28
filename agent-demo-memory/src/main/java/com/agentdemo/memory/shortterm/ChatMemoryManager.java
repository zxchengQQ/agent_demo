package com.agentdemo.memory.shortterm;

import com.agentdemo.llm.registry.ModelFactory;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 短期记忆管理器
 * <p>
 * 业务含义：基于 LangChain4j 封装会话级短期记忆，保留最近 N 条消息作为上下文，支持多会话隔离。
 * agent-context-engineering 改造（Task-03）：默认装配 {@link CompressingChatMemory}（滚动摘要压缩，
 * AC-E01）；compression enabled=false 或未装配 LLM 时退化为 MessageWindowChatMemory FIFO（现状行为）。
 * 新增附件 API（addAttachment/hasAttachment）供技能目录/指令/状态写入会话记忆流（emit-once，AC-N01/T01）。
 * </p>
 * <p>
 * 设计原则：
 * 1. 会话隔离：每个 sessionId 对应独立的 ChatMemory，互不干扰
 * 2. 窗口限制：默认保留 20 条消息，超出后旧消息经滚动摘要归纳（压缩模式）或 FIFO 淘汰
 * 3. 线程安全：使用 ConcurrentHashMap 存储会话记忆
 * </p>
 */
@Service
public class ChatMemoryManager {

    private static final Logger log = LoggerFactory.getLogger(ChatMemoryManager.class);

    /** 摘要系统提示词（框架内部生成器，指导模型保留关键实体/结论/工具结果要点） */
    private static final String SUMMARY_SYSTEM_PROMPT =
            "你是会话记忆摘要助手。将给定的历史对话归纳为简洁摘要。"
            + "必须保留：关键实体（人名/订单号/文件名等）、已得出的结论、关键工具调用结果要点。"
            + "不要保留寒暄与重复内容。用中文输出，控制在 200 字以内。";

    /**
     * 默认记忆窗口大小（保留最近 20 条消息）
     * 业务含义：窗口过大消耗 Token 多，过小丢失上下文，20 条是经验平衡值
     */
    private static final int DEFAULT_WINDOW_SIZE = 20;

    /**
     * 会话记忆存储（key: sessionId, value: ChatMemory）
     */
    private final ConcurrentHashMap<String, ChatMemory> memoryMap = new ConcurrentHashMap<>();

    private final ModelFactory modelFactory;
    private final MemoryCompressionProperties compressionProperties;

    /**
     * Spring 装配构造（LLM 摘要 + 压缩开关）
     * <p>
     * 业务含义：多构造器场景下必须标注 @Autowired，否则 Spring 选用默认（无参）构造器，
     * 导致压缩静默退化为 FIFO（压缩不生效）。
     * </p>
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ChatMemoryManager(ModelFactory modelFactory, MemoryCompressionProperties compressionProperties) {
        this.modelFactory = modelFactory;
        this.compressionProperties = compressionProperties;
    }

    /**
     * 兼容构造（未装配 LLM/测试场景：压缩关闭，退化为 FIFO 现状）
     */
    public ChatMemoryManager() {
        this.modelFactory = null;
        this.compressionProperties = null;
    }

    /**
     * 获取指定会话的记忆（不存在则创建默认窗口的记忆）
     * 业务含义：computeIfAbsent 回调中禁止修改同一 map，否则触发 Recursive update 异常
     */
    public ChatMemory getMemory(String sessionId) {
        return memoryMap.computeIfAbsent(sessionId, id -> {
            ChatMemory memory = createMemory();
            log.info("创建会话记忆: sessionId={}, maxMessages={}, compressionEnabled={}",
                    id, DEFAULT_WINDOW_SIZE, compressionEnabled());
            return memory;
        });
    }

    /**
     * 创建新会话记忆
     */
    public ChatMemory createMemory(String sessionId) {
        ChatMemory memory = createMemory();
        memoryMap.put(sessionId, memory);
        log.info("创建会话记忆: sessionId={}, maxMessages={}, compressionEnabled={}",
                sessionId, DEFAULT_WINDOW_SIZE, compressionEnabled());
        return memory;
    }

    /**
     * 创建带窗口大小的会话记忆
     *
     * @param sessionId  会话 ID
     * @param maxMessages 最大消息数
     */
    public ChatMemory createMemory(String sessionId, int maxMessages) {
        ChatMemory memory = compressionEnabled()
                ? new CompressingChatMemory(maxMessages, this::summarize)
                : MessageWindowChatMemory.withMaxMessages(maxMessages);
        memoryMap.put(sessionId, memory);
        log.info("创建会话记忆: sessionId={}, maxMessages={}, compressionEnabled={}",
                sessionId, maxMessages, compressionEnabled());
        return memory;
    }

    /**
     * 添加用户消息
     */
    public void addUserMessage(String sessionId, String message) {
        ChatMemory memory = getMemory(sessionId);
        memory.add(UserMessage.from(message));
    }

    /**
     * 添加助手消息
     */
    public void addAssistantMessage(String sessionId, String message) {
        ChatMemory memory = getMemory(sessionId);
        memory.add(AiMessage.from(message));
    }

    /**
     * 清空会话记忆
     */
    public void clearMemory(String sessionId) {
        memoryMap.remove(sessionId);
        log.info("清空会话记忆: sessionId={}", sessionId);
    }

    /**
     * 判断会话记忆是否存在
     */
    public boolean exists(String sessionId) {
        return memoryMap.containsKey(sessionId);
    }

    /**
     * 写入框架附件消息（技能目录/指令/状态，emit-once）
     * <p>
     * 业务含义（agent-context-engineering）：附件以框架标记帧进入会话记忆流，
     * 压缩模式下永不压缩（AC-N01）；后续轮次随记忆自动带入上下文。
     * </p>
     *
     * @param sessionId 会话 ID
     * @param type      附件类型
     * @param text      附件正文
     */
    public void addAttachment(String sessionId, CompressingChatMemory.AttachmentType type, String text) {
        ChatMemory memory = getMemory(sessionId);
        if (memory instanceof CompressingChatMemory cc) {
            memory.add(cc.newAttachmentMessage(type, text));
        } else {
            memory.add(UserMessage.from(CompressingChatMemory.ATTACHMENT_PREFIX + type.name()
                    + CompressingChatMemory.ATTACHMENT_SUFFIX + "\n" + text));
        }
    }

    /**
     * 判断会话记忆是否已存在指定类型附件
     */
    public boolean hasAttachment(String sessionId, CompressingChatMemory.AttachmentType type) {
        ChatMemory memory = memoryMap.get(sessionId);
        if (memory == null) {
            return false;
        }
        String prefix = CompressingChatMemory.ATTACHMENT_PREFIX + type.name()
                + CompressingChatMemory.ATTACHMENT_SUFFIX;
        return memory.messages().stream()
                .filter(m -> m instanceof UserMessage um && um.hasSingleText())
                .anyMatch(m -> ((UserMessage) m).singleText().startsWith(prefix));
    }

    private boolean compressionEnabled() {
        return modelFactory != null && compressionProperties != null && compressionProperties.isEnabled();
    }

    private ChatMemory createMemory() {
        if (compressionEnabled()) {
            return new CompressingChatMemory(DEFAULT_WINDOW_SIZE, this::summarize);
        }
        return MessageWindowChatMemory.withMaxMessages(DEFAULT_WINDOW_SIZE);
    }

    /**
     * 摘要生成（滚动演进）：旧摘要 + 待压缩消息段 -> 新摘要
     * <p>
     * 调用默认 ChatModel 归纳；模型返回空/异常时抛错，由 CompressingChatMemory 降级 FIFO（AC-E01）。
     * </p>
     */
    private String summarize(String previousSummary, List<ChatMessage> messagesToCompress) {
        List<ChatMessage> request = new ArrayList<>();
        request.add(SystemMessage.from(SUMMARY_SYSTEM_PROMPT));
        StringBuilder content = new StringBuilder();
        if (previousSummary != null && !previousSummary.isBlank()) {
            content.append("既有摘要：").append(previousSummary).append('\n');
        }
        content.append("待归纳对话：\n");
        for (ChatMessage m : messagesToCompress) {
            content.append("- ").append(format(m)).append('\n');
        }
        request.add(UserMessage.from(content.toString()));

        ChatResponse response = modelFactory.getDefaultChatModel().chat(request);
        String text = response.aiMessage() != null ? response.aiMessage().text() : null;
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("摘要模型返回空");
        }
        return text.trim();
    }

    private String format(ChatMessage m) {
        if (m instanceof UserMessage um) {
            return "用户: " + um.singleText();
        }
        if (m instanceof AiMessage am) {
            return "助手: " + (am.text() != null ? am.text() : "");
        }
        if (m instanceof ToolExecutionResultMessage tr) {
            return "工具[" + tr.toolName() + "]: " + tr.text();
        }
        return String.valueOf(m);
    }
}
