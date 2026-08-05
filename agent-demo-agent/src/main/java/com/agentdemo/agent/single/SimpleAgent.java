package com.agentdemo.agent.single;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.agent.core.BaseAgent;
import com.agentdemo.agent.core.ThinkingTokenStream;
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
 * 懒加载设计：delegate 在首次调用 chat() 时创建，避免构造时调用 listTools() 触发循环依赖
 * </p>
 */
@Service
public class SimpleAgent implements BaseAgent {

    private static final Logger log = LoggerFactory.getLogger(SimpleAgent.class);

    private final ModelFactory modelFactory;
    private final ToolRegistry toolRegistry;
    private final ChatMemoryManager memoryManager;
    private final AgentConfig agentConfig;
    private final ToolSchemaConverter toolSchemaConverter;
    private final ToolExecutor toolExecutor;

    /**
     * AiServices 创建的代理（懒加载，首次调用 chat 时创建）
     * volatile 保证多线程可见性
     */
    private volatile BaseAgent delegate;

    /**
     * 上次创建 delegate 时 ToolRegistry 中的工具数量
     * 业务含义：CR-003 动态知识库 Tool 注册后，下次对话前若工具数量变化则重建 delegate，
     * 使新注册/注销的知识库 Tool 对 Agent 生效，无需重启应用。
     */
    private volatile int lastToolCount = -1;

    public SimpleAgent(ModelFactory modelFactory,
                       ToolRegistry toolRegistry,
                       ChatMemoryManager memoryManager,
                       AgentConfig agentConfig,
                       ToolSchemaConverter toolSchemaConverter,
                       ToolExecutor toolExecutor) {
        this.modelFactory = modelFactory;
        this.toolRegistry = toolRegistry;
        this.memoryManager = memoryManager;
        this.agentConfig = agentConfig;
        this.toolSchemaConverter = toolSchemaConverter;
        this.toolExecutor = toolExecutor;
        log.info("SimpleAgent 构造完成（delegate 懒加载）");
    }

    /**
     * 懒加载创建 AiServices 代理
     * 业务含义：首次调用时创建，此时所有 Spring Bean 已初始化完成，避免循环依赖
     * 双重检查锁保证线程安全
     */
    private BaseAgent getDelegate() {
        // CR-003: 若 Tool 数量变化，需要重建 delegate 以绑定新注册/注销的知识库 Tool
        int currentToolCount = toolRegistry.getToolCount();
        if (delegate == null || currentToolCount != lastToolCount) {
            synchronized (this) {
                currentToolCount = toolRegistry.getToolCount();
                if (delegate == null || currentToolCount != lastToolCount) {
                    // 业务含义：通过 AiServices 代理构建 Agent
                    // - chatModel：提供 LLM 推理能力（大脑）
                    // - chatMemoryProvider：按 sessionId 提供会话记忆（上下文保持）
                    // - tools：绑定工具集（手脚），Agent 自主决定何时调用
                    // - systemMessageProvider：动态提供系统提示词（角色设定）
                    log.info("初始化 Agent delegate，绑定工具数: {}", currentToolCount);
                    delegate = AiServices.builder(BaseAgent.class)
                            .chatModel(modelFactory.getDefaultChatModel())
                            // 业务含义：绑定流式模型，使 chatStream 方法能逐字输出（TC-LC4J-007）
                            .streamingChatModel(modelFactory.getDefaultStreamingChatModel())
                            .chatMemoryProvider(memoryId -> memoryManager.getMemory((String) memoryId))
                            .tools(toolRegistry.listTools().toArray())
                            .systemMessageProvider(memoryId -> agentConfig.getDefaultSystemPrompt())
                            .build();
                    lastToolCount = currentToolCount;
                    log.info("Agent delegate 初始化完成");
                }
            }
        }
        return delegate;
    }

    @Override
    public String chat(String sessionId, String message) {
        if (agentConfig.isEnableLogging()) {
            log.info("Agent 对话: sessionId={}, message={}", sessionId, message);
        }
        long start = System.currentTimeMillis();
        String response = getDelegate().chat(sessionId, message);
        long duration = System.currentTimeMillis() - start;
        if (agentConfig.isEnableLogging()) {
            log.info("Agent 回复: sessionId={}, 耗时={}ms, 回复长度={}", sessionId, duration, response.length());
        }
        return response;
    }

    /**
     * 流式对话
     * 业务含义：委托给 AiServices 代理的流式方法，由 StreamingChatModel 逐字生成。
     * 注意：需在 getDelegate() 中绑定 streamingChatModel（见 T-02），否则调用会失败。
     *
     * @param sessionId 会话 ID
     * @param message   用户消息
     * @return TokenStream 流式令牌
     */
    @Override
    public TokenStream chatStream(String sessionId, String message) {
        if (agentConfig.isEnableLogging()) {
            log.info("Agent 流式对话: sessionId={}, message={}", sessionId, message);
        }
        return getDelegate().chatStream(sessionId, message);
    }

    /**
     * 思考流式对话（CR-001 新增）
     * <p>
     * 业务含义：区别于 chatStream（基于 LangChain4j AiServices 自动处理记忆与工具），
     * 本方法手动组装 ChatMessage（系统提示词 + 历史消息 + 当前用户消息），
     * 委托给 ArkThinkingStreamingChatModel 直连方舟 API，解析 reasoning_content 与 content 分别回调。
     * </p>
     * <p>
     * 调用方：web 层 AgentController.chatStream（当 enableThinking=true 时使用）
     * </p>
     *
     * @param sessionId 会话 ID
     * @param message   用户消息
     * @return ThinkingTokenStream 思考流式令牌（需调用 start() 启动）
     */
    public ThinkingTokenStream chatThinkingStream(String sessionId, String message) {
        if (agentConfig.isEnableLogging()) {
            log.info("Agent 思考流式对话: sessionId={}, message={}", sessionId, message);
        }

        // 业务含义：手动组装消息列表（系统提示词 + 历史消息 + 当前用户消息）
        // 区别于 AiServices 自动注入记忆，此处需显式拼接以保证多轮上下文完整传递给方舟 API
        ThinkingStreamingChatModel thinkingModel = modelFactory.getThinkingStreamingChatModel();
        List<ChatMessage> messages = buildMessagesWithMemory(sessionId, message);

        return new ArkThinkingTokenStream(thinkingModel, messages);
    }

    /**
     * ReAct 思考流式对话（Task-08 新增）
     * <p>
     * 业务含义：区别于 chatThinkingStream（单轮直连方舟 API，不调用工具），
     * 本方法启动显式 ReAct 循环（推理 -> 工具调用 -> 观察 -> 继续推理 -> 最终回答），
     * 通过方舟 LLM 原生驱动 ReAct，支持工具调用和双重推理层。
     * </p>
     * <p>
     * 核心差异：
     * - 使用 thinkingReactSystemPrompt（含 ReAct 引导 + 工具描述）替代 thinkingSystemPrompt
     * - 通过 ToolSchemaConverter 生成工具 JSON Schema 传给方舟 API
     * - 返回 ReActThinkingStream（实现 ReAct 循环）而非 ArkThinkingTokenStream（单轮）
     * </p>
     *
     * @param sessionId 会话 ID
     * @param message   用户消息
     * @return ThinkingTokenStream 思考流式令牌（需调用 start() 启动）
     */
    public ThinkingTokenStream chatThinkingReActStream(String sessionId, String message) {
        if (agentConfig.isEnableLogging()) {
            log.info("Agent ReAct 思考流式对话: sessionId={}, message={}", sessionId, message);
        }

        // 业务含义：使用 ReAct 专用系统提示词（含 Thought/Action/Observation 引导 + 工具描述）
        ThinkingStreamingChatModel thinkingModel = modelFactory.getThinkingStreamingChatModel();
        List<ChatMessage> messages = buildReActMessagesWithMemory(sessionId, message);

        // 业务含义：将 @Tool 注解方法转换为 OpenAI 兼容的 tools JSON Schema，传给方舟 API
        String toolsJson = toolSchemaConverter.convertToJson();

        return new ReActThinkingStream(
                thinkingModel,
                messages,
                toolsJson,
                toolExecutor,
                agentConfig.getThinkingMaxIterations());
    }

    /**
     * 组装带记忆的 ReAct 消息列表（Task-08 新增，CR-001 修改）
     * <p>
     * 业务含义：与 buildMessagesWithMemory 类似，但使用 thinkingReactSystemPrompt（含 ReAct 引导）。
     * CR-001 变更：工具描述不再硬编码在 thinkingReactSystemPrompt 中，改为运行时通过
     * ToolSchemaConverter.convertToDescriptionText() 动态生成并拼接到系统提示词末尾。
     * </p>
     *
     * @param sessionId 会话 ID
     * @param message   当前用户消息
     * @return 组装后的消息列表
     */
    private List<ChatMessage> buildReActMessagesWithMemory(String sessionId, String message) {
        List<ChatMessage> messages = new ArrayList<>();
        // ReAct 专用系统提示词（含 ReAct 引导）+ 动态工具描述（CR-001：工具描述不再硬编码在提示词中）
        String systemPrompt = agentConfig.getThinkingReactSystemPrompt()
                + "\n" + toolSchemaConverter.convertToDescriptionText();
        messages.add(SystemMessage.from(systemPrompt));
        // 历史消息（多轮上下文）
        messages.addAll(memoryManager.getMemory(sessionId).messages());
        // 当前用户消息
        messages.add(UserMessage.from(message));
        return messages;
    }

    /**
     * 组装带记忆的消息列表（CR-001 新增）
     * 业务含义：系统提示词 + 会话历史消息 + 当前用户消息，供思考流式路径使用。
     * 消息顺序与方舟 API 多轮对话约定一致：system -> 历史 user/assistant 交替 -> 当前 user。
     *
     * @param sessionId 会话 ID
     * @param message   当前用户消息
     * @return 组装后的消息列表
     */
    private List<ChatMessage> buildMessagesWithMemory(String sessionId, String message) {
        List<ChatMessage> messages = new ArrayList<>();
        // 系统提示词（思考模式专用，不提及工具调用，避免模型尝试调用不存在的工具）
        messages.add(SystemMessage.from(agentConfig.getThinkingSystemPrompt()));
        // 历史消息（多轮上下文，由 ChatMemoryManager 维护窗口）
        messages.addAll(memoryManager.getMemory(sessionId).messages());
        // 当前用户消息
        messages.add(UserMessage.from(message));
        return messages;
    }

    /**
     * 获取 Agent 类型
     *
     * @return Agent 类型
     */
    public AgentType getType() {
        return AgentType.SINGLE;
    }

    /**
     * 获取 Agent 名称
     *
     * @return Agent 名称
     */
    public String getName() {
        return "SimpleAgent";
    }
}
