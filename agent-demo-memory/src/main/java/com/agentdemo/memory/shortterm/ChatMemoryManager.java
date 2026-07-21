package com.agentdemo.memory.shortterm;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 短期记忆管理器
 * <p>
 * 业务含义：基于 LangChain4j MessageWindowChatMemory 封装会话级短期记忆，
 * 保留最近 N 条消息作为上下文，支持多会话隔离。
 * </p>
 * <p>
 * 设计原则：
 * 1. 会话隔离：每个 sessionId 对应独立的 ChatMemory，互不干扰
 * 2. 窗口限制：默认保留 20 条消息，超出后旧消息自动淘汰（平衡上下文长度与 Token 消耗）
 * 3. 线程安全：使用 ConcurrentHashMap 存储会话记忆
 * </p>
 * <p>
 * 调用方：agent 层（构建 AiServices 时传入 memory）、web 层（请求前后更新记忆）
 * </p>
 */
@Service
public class ChatMemoryManager {

    private static final Logger log = LoggerFactory.getLogger(ChatMemoryManager.class);

    /**
     * 默认记忆窗口大小（保留最近 20 条消息）
     * 业务含义：窗口过大消耗 Token 多，过小丢失上下文，20 条是经验平衡值
     */
    private static final int DEFAULT_WINDOW_SIZE = 20;

    /**
     * 会话记忆存储（key: sessionId, value: ChatMemory）
     */
    private final ConcurrentHashMap<String, ChatMemory> memoryMap = new ConcurrentHashMap<>();

    /**
     * 获取指定会话的记忆（不存在则创建默认窗口的记忆）
     * 业务含义：computeIfAbsent 回调中禁止修改同一 map，否则触发 Recursive update 异常
     *
     * @param sessionId 会话 ID
     * @return ChatMemory 实例
     */
    public ChatMemory getMemory(String sessionId) {
        return memoryMap.computeIfAbsent(sessionId, id -> {
            ChatMemory memory = MessageWindowChatMemory.withMaxMessages(DEFAULT_WINDOW_SIZE);
            log.info("创建会话记忆: sessionId={}, maxMessages={}", id, DEFAULT_WINDOW_SIZE);
            return memory;
        });
    }

    /**
     * 创建新会话记忆
     *
     * @param sessionId 会话 ID
     * @return 新建的 ChatMemory
     */
    public ChatMemory createMemory(String sessionId) {
        return createMemory(sessionId, DEFAULT_WINDOW_SIZE);
    }

    /**
     * 创建带窗口大小的会话记忆
     *
     * @param sessionId    会话 ID
     * @param maxMessages 最大消息数
     * @return 新建的 ChatMemory
     */
    public ChatMemory createMemory(String sessionId, int maxMessages) {
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(maxMessages);
        memoryMap.put(sessionId, memory);
        log.info("创建会话记忆: sessionId={}, maxMessages={}", sessionId, maxMessages);
        return memory;
    }

    /**
     * 添加用户消息
     *
     * @param sessionId 会话 ID
     * @param message   用户消息内容
     */
    public void addUserMessage(String sessionId, String message) {
        ChatMemory memory = getMemory(sessionId);
        memory.add(dev.langchain4j.data.message.UserMessage.from(message));
    }

    /**
     * 添加助手消息
     *
     * @param sessionId 会话 ID
     * @param message   助手回复内容
     */
    public void addAssistantMessage(String sessionId, String message) {
        ChatMemory memory = getMemory(sessionId);
        memory.add(dev.langchain4j.data.message.AiMessage.from(message));
    }

    /**
     * 清空会话记忆
     *
     * @param sessionId 会话 ID
     */
    public void clearMemory(String sessionId) {
        memoryMap.remove(sessionId);
        log.info("清空会话记忆: sessionId={}", sessionId);
    }

    /**
     * 判断会话记忆是否存在
     *
     * @param sessionId 会话 ID
     * @return 是否存在
     */
    public boolean exists(String sessionId) {
        return memoryMap.containsKey(sessionId);
    }
}
