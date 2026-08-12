package com.agentdemo.agent.single;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.agent.core.BaseAgent;
import com.agentdemo.agent.core.ThinkingTokenStream;
import com.agentdemo.agent.prompt.PromptTemplateLoader;
import com.agentdemo.common.enums.AgentType;
import com.agentdemo.llm.registry.ModelFactory;
import com.agentdemo.llm.thinking.ThinkingStreamingChatModel;
import com.agentdemo.memory.shortterm.ChatMemoryManager;
import com.agentdemo.tools.registry.ToolExecutor;
import com.agentdemo.tools.registry.ToolRegistry;
import com.agentdemo.tools.registry.ToolSchemaConverter;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单 Agent 实现
 * <p>
 * 业务含义：基于 LangChain4j AiServices 实现的单 Agent，具备 ReAct 循环、工具调用、记忆能力。
 * 采用委托模式：SimpleAgent 实现 BaseAgent 接口，内部委托给 AiServices 创建的代理。
 * </p>
 * <p>
 * 核心逻辑说明：
 * 1. AiServices 通过动态代理实现 BaseAgent 接口，自动处理 ReAct 循环（思考-行动-观察）
 * 2. 模型作为"大脑"负责决策，工具作为"手脚"负责执行，记忆保持上下文
 * 3. @MemoryId 让 LangChain4j 自动按 sessionId 隔离会话记忆
 * 4. systemMessageProvider 动态提供系统提示词，支持不同场景定制
 * </p>
 * <p>
 * Phase 2 重构：delegate 缓存改为按 modelId 隔离的 ConcurrentHashMap，
 * 支持前端按 modelId 选择不同模型进行对话。
 * </p>
 */
@Service
public class SimpleAgent implements BaseAgent {

    private static final Logger log = LoggerFactory.getLogger(SimpleAgent.class);

    /** 默认 cacheKey（modelId 为 null 时使用） */
    private static final String DEFAULT_CACHE_KEY = "default";

    private final ModelFactory modelFactory;
    private final ToolRegistry toolRegistry;
    private final ChatMemoryManager memoryManager;
    private final AgentConfig agentConfig;
    private final ToolSchemaConverter toolSchemaConverter;
    private final ToolExecutor toolExecutor;
    private final PromptTemplateLoader promptTemplateLoader;

    /**
     * AiServices 代理缓存（按 modelId 隔离，懒加载）
     * 业务含义：不同 modelId 对应不同的 ChatModel/StreamingChatModel 实例，
     * 因此 delegate 也需按 modelId 分别缓存。modelId 为 null 时使用 "default" 作为 key。
     */
    private final ConcurrentHashMap<String, BaseAgent> delegateCache = new ConcurrentHashMap<>();

    /**
     * 各 modelId 对应的 delegate 创建时的工具数量
     * 业务含义：CR-003 动态知识库 Tool 注册后，下次对话前若工具数量变化则重建对应 modelId 的 delegate，
     * 使新注册/注销的知识库 Tool 对 Agent 生效，无需重启应用。
     */
    private final ConcurrentHashMap<String, Integer> delegateToolCounts = new ConcurrentHashMap<>();

    public SimpleAgent(ModelFactory modelFactory,
                       ToolRegistry toolRegistry,
                       ChatMemoryManager memoryManager,
                       AgentConfig agentConfig,
                       ToolSchemaConverter toolSchemaConverter,
                       ToolExecutor toolExecutor,
                       PromptTemplateLoader promptTemplateLoader) {
        this.modelFactory = modelFactory;
        this.toolRegistry = toolRegistry;
        this.memoryManager = memoryManager;
        this.agentConfig = agentConfig;
        this.toolSchemaConverter = toolSchemaConverter;
        this.toolExecutor = toolExecutor;
        this.promptTemplateLoader = promptTemplateLoader;
        log.info("SimpleAgent 构造完成（delegate 懒加载，按 modelId 缓存）");
    }

    /**
     * 获取或创建指定 modelId 的 AiServices 代理
     * 业务含义：按 modelId 隔离 delegate 缓存，支持前端选择不同模型。
     * modelId 为 null 时使用默认模型（第一个 chat 模型）。
     * 双重检查锁保证线程安全，工具数量变化时重建 delegate。
     *
     * @param modelId 模型 ID（null 表示使用默认模型）
     * @return AiServices 创建的 BaseAgent 代理
     */
    private BaseAgent getDelegate(String modelId) {
        String cacheKey = modelId != null ? modelId : DEFAULT_CACHE_KEY;
        int currentToolCount = toolRegistry.getToolCount();
        Integer lastCount = delegateToolCounts.get(cacheKey);
        BaseAgent cached = delegateCache.get(cacheKey);

        // CR-003: 若 delegate 不存在或 Tool 数量变化，需要重建
        if (cached == null || lastCount == null || currentToolCount != lastCount) {
            synchronized (this) {
                currentToolCount = toolRegistry.getToolCount();
                lastCount = delegateToolCounts.get(cacheKey);
                cached = delegateCache.get(cacheKey);
                if (cached == null || lastCount == null || currentToolCount != lastCount) {
                    log.info("初始化 Agent delegate，modelId={}, 绑定工具数: {}", modelId, currentToolCount);
                    // 按 modelId 选择 ChatModel 和 StreamingChatModel
                    dev.langchain4j.model.chat.ChatModel chatModel = (modelId != null)
                            ? modelFactory.getChatModelByModelId(modelId)
                            : modelFactory.getDefaultChatModel();
                    dev.langchain4j.model.chat.StreamingChatModel streamingChatModel = (modelId != null)
                            ? modelFactory.getStreamingChatModelByModelId(modelId)
                            : modelFactory.getDefaultStreamingChatModel();

                    cached = AiServices.builder(BaseAgent.class)
                            .chatModel(chatModel)
                            .streamingChatModel(streamingChatModel)
                            .chatMemoryProvider(memoryId -> memoryManager.getMemory((String) memoryId))
                            .tools(toolRegistry.listTools().toArray())
                            .systemMessageProvider(memoryId -> promptTemplateLoader.composeSystemPrompt(PromptTemplateLoader.SCENARIO_CHAT))
                            .build();
                    delegateCache.put(cacheKey, cached);
                    delegateToolCounts.put(cacheKey, currentToolCount);
                    log.info("Agent delegate 初始化完成, modelId={}", modelId);
                }
            }
        }
        return cached;
    }

    @Override
    public String chat(String sessionId, String message) {
        return chat(sessionId, message, null);
    }

    /**
     * 同步对话（支持指定模型）
     *
     * @param sessionId 会话 ID
     * @param message   用户消息
     * @param modelId   模型 ID（null 使用默认模型）
     * @return Agent 回复
     */
    public String chat(String sessionId, String message, String modelId) {
        if (agentConfig.isEnableLogging()) {
            log.info("Agent 对话: sessionId={}, message={}, modelId={}", sessionId, message, modelId);
        }
        long start = System.currentTimeMillis();
        String response = getDelegate(modelId).chat(sessionId, message);
        long duration = System.currentTimeMillis() - start;
        if (agentConfig.isEnableLogging()) {
            log.info("Agent 回复: sessionId={}, 耗时={}ms, 回复长度={}", sessionId, duration, response.length());
        }
        return response;
    }

    @Override
    public TokenStream chatStream(String sessionId, String message) {
        return chatStream(sessionId, message, null);
    }

    /**
     * 流式对话（支持指定模型）
     * 业务含义：委托给 AiServices 代理的流式方法，由 StreamingChatModel 逐字生成。
     *
     * @param sessionId 会话 ID
     * @param message   用户消息
     * @param modelId   模型 ID（null 使用默认模型）
     * @return TokenStream 流式令牌
     */
    public TokenStream chatStream(String sessionId, String message, String modelId) {
        if (agentConfig.isEnableLogging()) {
            log.info("Agent 流式对话: sessionId={}, message={}, modelId={}", sessionId, message, modelId);
        }
        return getDelegate(modelId).chatStream(sessionId, message);
    }

    /**
     * 思考流式对话（CR-001 新增）
     *
     * @param sessionId 会话 ID
     * @param message   用户消息
     * @return ThinkingTokenStream 思考流式令牌（需调用 start() 启动）
     */
    public ThinkingTokenStream chatThinkingStream(String sessionId, String message) {
        return chatThinkingStream(sessionId, message, null);
    }

    /**
     * 思考流式对话（支持指定模型）
     * <p>
     * 业务含义：手动组装 ChatMessage（系统提示词 + 历史消息 + 当前用户消息），
     * 委托给 ThinkingStreamingChatModel 直连 API，解析 reasoning_content 与 content 分别回调。
     * </p>
     *
     * @param sessionId 会话 ID
     * @param message   用户消息
     * @param modelId   模型 ID（null 使用默认模型）
     * @return ThinkingTokenStream 思考流式令牌
     */
    public ThinkingTokenStream chatThinkingStream(String sessionId, String message, String modelId) {
        if (agentConfig.isEnableLogging()) {
            log.info("Agent 思考流式对话: sessionId={}, message={}, modelId={}", sessionId, message, modelId);
        }

        ThinkingStreamingChatModel thinkingModel = (modelId != null)
                ? modelFactory.getThinkingStreamingChatModelByModelId(modelId)
                : modelFactory.getDefaultThinkingStreamingChatModel();
        List<ChatMessage> messages = buildMessagesWithMemory(sessionId, message);

        return new ArkThinkingTokenStream(thinkingModel, messages);
    }

    /**
     * ReAct 思考流式对话（Task-08 新增）
     *
     * @param sessionId 会话 ID
     * @param message   用户消息
     * @return ThinkingTokenStream 思考流式令牌（需调用 start() 启动）
     */
    public ThinkingTokenStream chatThinkingReActStream(String sessionId, String message) {
        return chatThinkingReActStream(sessionId, message, null);
    }

    /**
     * ReAct 思考流式对话（支持指定模型）
     * <p>
     * 业务含义：启动显式 ReAct 循环（推理 -> 工具调用 -> 观察 -> 继续推理 -> 最终回答），
     * 通过 LLM 原生驱动 ReAct，支持工具调用和双重推理层。
     * </p>
     *
     * @param sessionId 会话 ID
     * @param message   用户消息
     * @param modelId   模型 ID（null 使用默认模型）
     * @return ThinkingTokenStream 思考流式令牌
     */
    public ThinkingTokenStream chatThinkingReActStream(String sessionId, String message, String modelId) {
        if (agentConfig.isEnableLogging()) {
            log.info("Agent ReAct 思考流式对话: sessionId={}, message={}, modelId={}", sessionId, message, modelId);
        }

        // 业务含义：按 modelId 选择思考流式模型，null 时使用默认模型
        ThinkingStreamingChatModel thinkingModel = (modelId != null)
                ? modelFactory.getThinkingStreamingChatModelByModelId(modelId)
                : modelFactory.getDefaultThinkingStreamingChatModel();
        List<ChatMessage> messages = buildReActMessagesWithMemory(sessionId, message);

        String toolsJson = toolSchemaConverter.convertToJson();

        return new ReActThinkingStream(
                thinkingModel,
                messages,
                toolsJson,
                toolExecutor,
                agentConfig.getThinkingMaxIterations());
    }

    /**
     * 组装带记忆的 ReAct 消息列表
     */
    private List<ChatMessage> buildReActMessagesWithMemory(String sessionId, String message) {
        List<ChatMessage> messages = new ArrayList<>();
        String systemPrompt = promptTemplateLoader.composeSystemPrompt(PromptTemplateLoader.SCENARIO_REACT)
                .replace("{{tools}}", toolSchemaConverter.convertToDescriptionText());
        messages.add(SystemMessage.from(systemPrompt));
        messages.addAll(memoryManager.getMemory(sessionId).messages());
        messages.add(UserMessage.from(message));
        return messages;
    }

    /**
     * 组装带记忆的消息列表
     */
    private List<ChatMessage> buildMessagesWithMemory(String sessionId, String message) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(promptTemplateLoader.composeSystemPrompt(PromptTemplateLoader.SCENARIO_THINKING)));
        messages.addAll(memoryManager.getMemory(sessionId).messages());
        messages.add(UserMessage.from(message));
        return messages;
    }

    /**
     * 获取 Agent 类型
     */
    public AgentType getType() {
        return AgentType.SINGLE;
    }

    /**
     * 获取 Agent 名称
     */
    public String getName() {
        return "SimpleAgent";
    }
}
