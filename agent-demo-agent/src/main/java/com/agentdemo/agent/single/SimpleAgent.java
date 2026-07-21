package com.agentdemo.agent.single;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.agent.core.BaseAgent;
import com.agentdemo.common.enums.AgentType;
import com.agentdemo.llm.factory.ModelFactory;
import com.agentdemo.memory.shortterm.ChatMemoryManager;
import com.agentdemo.tools.registry.ToolRegistry;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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

    /**
     * AiServices 创建的代理（懒加载，首次调用 chat 时创建）
     * volatile 保证多线程可见性
     */
    private volatile BaseAgent delegate;

    public SimpleAgent(ModelFactory modelFactory,
                       ToolRegistry toolRegistry,
                       ChatMemoryManager memoryManager,
                       AgentConfig agentConfig) {
        this.modelFactory = modelFactory;
        this.toolRegistry = toolRegistry;
        this.memoryManager = memoryManager;
        this.agentConfig = agentConfig;
        log.info("SimpleAgent 构造完成（delegate 懒加载）");
    }

    /**
     * 懒加载创建 AiServices 代理
     * 业务含义：首次调用时创建，此时所有 Spring Bean 已初始化完成，避免循环依赖
     * 双重检查锁保证线程安全
     */
    private BaseAgent getDelegate() {
        if (delegate == null) {
            synchronized (this) {
                if (delegate == null) {
                    // 业务含义：通过 AiServices 代理构建 Agent
                    // - chatModel：提供 LLM 推理能力（大脑）
                    // - chatMemoryProvider：按 sessionId 提供会话记忆（上下文保持）
                    // - tools：绑定工具集（手脚），Agent 自主决定何时调用
                    // - systemMessageProvider：动态提供系统提示词（角色设定）
                    log.info("初始化 Agent delegate，绑定工具数: {}", toolRegistry.size());
                    delegate = AiServices.builder(BaseAgent.class)
                            .chatModel(modelFactory.getDefaultChatModel())
                            // 业务含义：绑定流式模型，使 chatStream 方法能逐字输出（TC-LC4J-007）
                            .streamingChatModel(modelFactory.getDefaultStreamingChatModel())
                            .chatMemoryProvider(memoryId -> memoryManager.getMemory((String) memoryId))
                            .tools(toolRegistry.listTools().toArray())
                            .systemMessageProvider(memoryId -> agentConfig.getDefaultSystemPrompt())
                            .build();
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
