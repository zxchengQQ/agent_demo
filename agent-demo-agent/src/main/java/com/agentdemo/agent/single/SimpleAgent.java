package com.agentdemo.agent.single;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.agent.core.BaseAgent;
import com.agentdemo.agent.core.HumanInteractionManager;
import com.agentdemo.agent.prompt.PromptTemplateLoader;
import com.agentdemo.common.enums.AgentType;
import com.agentdemo.llm.registry.ModelFactory;
import com.agentdemo.memory.shortterm.ChatMemoryManager;
import com.agentdemo.tools.registry.ToolExecutor;
import com.agentdemo.tools.registry.ToolRegistry;
import com.agentdemo.tools.registry.ToolSchemaConverter;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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
 * <p>
 * unified-chat-mode：会话工具解析逻辑抽取至 SessionToolResolver 共享组件；
 * 流式模式路径（chatThinking、chatThinkingReAct、chatHITLStream、resumeHITLStream）已删除，
 * 统一对话模式由 UnifiedChatStream 编排（技术方案 1.6.3 删除清单，用户确认彻底删除）。
 * SimpleAgent 保留 chat/chatStream（BaseAgent 接口合规 + 同步 /chat 端点使用）与 delegate 缓存体系。
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
    private final SessionToolResolver sessionToolResolver;

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
                       PromptTemplateLoader promptTemplateLoader,
                       HumanInteractionManager humanInteractionManager,
                       SessionToolResolver sessionToolResolver) {
        this.modelFactory = modelFactory;
        this.toolRegistry = toolRegistry;
        this.memoryManager = memoryManager;
        this.agentConfig = agentConfig;
        this.toolSchemaConverter = toolSchemaConverter;
        this.toolExecutor = toolExecutor;
        this.promptTemplateLoader = promptTemplateLoader;
        this.sessionToolResolver = sessionToolResolver;
        log.info("SimpleAgent 构造完成（delegate 懒加载，按 modelId 缓存，工具解析委托 SessionToolResolver）");
    }

    /**
     * 获取或创建指定 modelId + 工具列表的 AiServices 代理
     * <p>
     * 业务含义：按 modelId + toolsFingerprint 隔离 delegate 缓存。
     * 不同工具集需重建 delegate（LangChain4j 工具列表在 build 时固化）。
     * </p>
     *
     * @param modelId 模型 ID
     * @param toolObjects 工具对象列表
     * @return AiServices 代理
     */
    private BaseAgent getDelegate(String modelId, List<Object> toolObjects) {
        String toolsFingerprint = buildToolsFingerprint(toolObjects);
        String cacheKey = (modelId != null ? modelId : DEFAULT_CACHE_KEY) + ":" + toolsFingerprint;
        BaseAgent cached = delegateCache.get(cacheKey);

        if (cached == null) {
            synchronized (this) {
                cached = delegateCache.get(cacheKey);
                if (cached == null) {
                    log.info("初始化 Agent delegate，modelId={}, 工具数={}", modelId, toolObjects.size());
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
                            .tools(toolObjects.toArray())
                            .systemMessageProvider(memoryId -> promptTemplateLoader.composeSystemPrompt(PromptTemplateLoader.SCENARIO_CHAT))
                            .build();
                    delegateCache.put(cacheKey, cached);
                }
            }
        }
        return cached;
    }

    /**
     * 构建工具指纹（用于 delegate 缓存键）
     */
    private String buildToolsFingerprint(List<Object> toolObjects) {
        if (toolObjects == null || toolObjects.isEmpty()) {
            return "empty";
        }
        return toolObjects.stream()
                .flatMap(t -> sessionToolResolver.findToolMethodNames(t).stream())
                .sorted()
                .collect(Collectors.joining(","));
    }

    @Override
    public String chat(String sessionId, String message) {
        if (agentConfig.isEnableLogging()) {
            log.info("Agent 对话: sessionId={}, message={}", sessionId, message);
        }
        long start = System.currentTimeMillis();
        // 业务含义：同步路径无暂停确认能力，默认工具按 ForDirect 过滤（deny+ask 剔除，AC-T01 无 NONE 旁路）
        List<Object> tools = sessionToolResolver.resolveSessionTools(sessionId, null, false);
        String response = getDelegate(null, tools).chat(sessionId, message);
        long duration = System.currentTimeMillis() - start;
        if (agentConfig.isEnableLogging()) {
            log.info("Agent 回复: sessionId={}, 耗时={}ms, 回复长度={}", sessionId, duration, response.length());
        }
        return response;
    }

    @Override
    public TokenStream chatStream(String sessionId, String message) {
        if (agentConfig.isEnableLogging()) {
            log.info("Agent 流式对话: sessionId={}, message={}", sessionId, message);
        }
        // 业务含义：流式路径可暂停确认，默认工具按 ForStreaming 过滤（deny 剔除、ask 保留，AC-T01 无 NONE 旁路）
        List<Object> tools = sessionToolResolver.resolveSessionTools(sessionId, null);
        return getDelegate(null, tools).chatStream(sessionId, message);
    }

    /**
     * 同步对话（支持工具按需加载）
     *
     * @param sessionId 会话 ID
     * @param message   用户消息
     * @param modelId   模型 ID
     * @param toolIds   工具标识列表（null=沿用缓存，空=清除，非空=指定）
     * @return Agent 回复
     */
    public String chat(String sessionId, String message, String modelId, List<String> toolIds) {
        if (agentConfig.isEnableLogging()) {
            log.info("Agent 对话: sessionId={}, message={}, modelId={}, toolIds={}", sessionId, message, modelId, toolIds);
        }
        long start = System.currentTimeMillis();
        // 业务含义：同步路径无暂停确认能力，ask 级工具不注入（askSupported=false → SYNC 过滤模式）
        List<Object> tools = sessionToolResolver.resolveSessionTools(sessionId, toolIds, false);
        String response = getDelegate(modelId, tools).chat(sessionId, message);
        long duration = System.currentTimeMillis() - start;
        if (agentConfig.isEnableLogging()) {
            log.info("Agent 回复: sessionId={}, 耗时={}ms, 回复长度={}", sessionId, duration, response.length());
        }
        return response;
    }

    /**
     * 流式对话（支持工具按需加载）
     */
    public TokenStream chatStream(String sessionId, String message, String modelId, List<String> toolIds) {
        if (agentConfig.isEnableLogging()) {
            log.info("Agent 流式对话: sessionId={}, message={}, modelId={}, toolIds={}", sessionId, message, modelId, toolIds);
        }
        List<Object> tools = sessionToolResolver.resolveSessionTools(sessionId, toolIds);
        return getDelegate(modelId, tools).chatStream(sessionId, message);
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
