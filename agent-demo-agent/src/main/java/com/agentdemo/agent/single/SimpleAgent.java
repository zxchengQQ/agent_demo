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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;
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

    /**
     * 会话级工具缓存（按 sessionId 隔离）
     * 业务含义：用户首次指定 tools 后缓存到会话，后续轮次无需重复指定。
     * 空数组时清除缓存，恢复仅默认工具。
     */
    private final ConcurrentHashMap<String, List<String>> sessionToolIds = new ConcurrentHashMap<>();

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
     * 获取或创建指定 modelId 的 AiServices 代理（默认工具，向后兼容）
     * <p>
     * 业务含义：未显式指定工具时，仅绑定默认工具（工具按需加载），
     * 避免把 MCP/知识库等可选工具默认暴露给 Agent。
     * </p>
     *
     * @param modelId 模型 ID
     * @return AiServices 代理
     */
    private BaseAgent getDelegate(String modelId) {
        return getDelegate(modelId, toolRegistry.getDefaultTools(agentConfig.getTools().getDefaultTools()));
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
                .flatMap(t -> findToolMethodNames(t).stream())
                .sorted()
                .collect(Collectors.joining(","));
    }

    /** 获取工具对象的所有 @Tool 方法名 */
    private List<String> findToolMethodNames(Object tool) {
        List<String> names = new ArrayList<>();
        for (Method method : tool.getClass().getDeclaredMethods()) {
            if (method.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class)) {
                names.add(method.getName());
            }
        }
        return names;
    }

    /**
     * 解析会话工具列表
     * <p>
     * 业务含义：根据传入的 toolIds 决定本次对话的工具列表。
     * null → 从会话缓存读取（无缓存时用默认）；非空列表 → 解析并缓存；空列表 → 清除缓存，仅默认。
     * </p>
     */
    private List<Object> resolveSessionTools(String sessionId, List<String> toolIds) {
        List<String> effectiveIds;
        if (toolIds != null) {
            if (toolIds.isEmpty()) {
                // 空数组 → 清除会话绑定，仅默认工具
                sessionToolIds.remove(sessionId);
                effectiveIds = null;
            } else {
                // 指定工具 → 解析 + 缓存
                sessionToolIds.put(sessionId, toolIds);
                effectiveIds = toolIds;
            }
        } else {
            // 未指定 → 从会话缓存读取
            effectiveIds = sessionToolIds.get(sessionId);
        }

        List<Object> tools;
        if (effectiveIds != null && !effectiveIds.isEmpty()) {
            tools = toolRegistry.resolveTools(effectiveIds);
        } else {
            tools = toolRegistry.getDefaultTools(agentConfig.getTools().getDefaultTools());
        }
        // 合并默认工具（默认工具不可排除）
        return mergeDefaults(tools);
    }

    /**
     * 合并默认工具，保证默认工具始终在列表中
     */
    private List<Object> mergeDefaults(List<Object> specifiedTools) {
        List<String> defaultIds = agentConfig.getTools().getDefaultTools();
        if (defaultIds == null || defaultIds.isEmpty()) {
            return specifiedTools;
        }
        Set<Object> merged = new LinkedHashSet<>(specifiedTools);
        List<Object> defaultTools = toolRegistry.getDefaultTools(defaultIds);
        // 默认工具排在前面，去重
        List<Object> result = new ArrayList<>(defaultTools);
        for (Object t : specifiedTools) {
            if (!result.contains(t)) {
                result.add(t);
            }
        }
        return result;
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
        return chatThinkingReActStream(sessionId, message, modelId, null);
    }

    /**
     * ReAct 思考流式对话（支持工具按需加载）
     */
    public ThinkingTokenStream chatThinkingReActStream(String sessionId, String message, String modelId, List<String> toolIds) {
        if (agentConfig.isEnableLogging()) {
            log.info("Agent ReAct 思考流式对话: sessionId={}, message={}, modelId={}, toolIds={}", sessionId, message, modelId, toolIds);
        }

        ThinkingStreamingChatModel thinkingModel = (modelId != null)
                ? modelFactory.getThinkingStreamingChatModelByModelId(modelId)
                : modelFactory.getDefaultThinkingStreamingChatModel();
        // 业务含义：解析本次会话应绑定的工具列表（默认 ∪ 指定），
        // ReAct 循环的工具 Schema 与描述文本仅基于该列表生成，避免暴露未指定的可选工具
        List<Object> tools = resolveSessionTools(sessionId, toolIds);
        List<ChatMessage> messages = buildReActMessagesWithMemory(sessionId, message, tools);

        String toolsJson = toolSchemaConverter.convertToJson(tools);

        return new ReActThinkingStream(
                thinkingModel,
                messages,
                toolsJson,
                toolExecutor,
                agentConfig.getThinkingMaxIterations());
    }

    // ==================== 工具按需加载方法（带 toolIds 参数） ====================

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
        List<Object> tools = resolveSessionTools(sessionId, toolIds);
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
        List<Object> tools = resolveSessionTools(sessionId, toolIds);
        return getDelegate(modelId, tools).chatStream(sessionId, message);
    }

    /**
     * 思考流式对话（支持工具按需加载）
     */
    public ThinkingTokenStream chatThinkingStream(String sessionId, String message, String modelId, List<String> toolIds) {
        if (agentConfig.isEnableLogging()) {
            log.info("Agent 思考流式对话: sessionId={}, message={}, modelId={}, toolIds={}", sessionId, message, modelId, toolIds);
        }

        ThinkingStreamingChatModel thinkingModel = (modelId != null)
                ? modelFactory.getThinkingStreamingChatModelByModelId(modelId)
                : modelFactory.getDefaultThinkingStreamingChatModel();
        List<ChatMessage> messages = buildMessagesWithMemory(sessionId, message);

        return new ArkThinkingTokenStream(thinkingModel, messages);
    }

    /**
     * 组装带记忆的 ReAct 消息列表
     *
     * @param tools 本次会话绑定的工具对象列表（默认 ∪ 指定），描述文本仅基于此列表生成
     */
    private List<ChatMessage> buildReActMessagesWithMemory(String sessionId, String message, List<Object> tools) {
        List<ChatMessage> messages = new ArrayList<>();
        String systemPrompt = promptTemplateLoader.composeSystemPrompt(PromptTemplateLoader.SCENARIO_REACT)
                .replace("{{tools}}", toolSchemaConverter.convertToDescriptionText(tools));
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
