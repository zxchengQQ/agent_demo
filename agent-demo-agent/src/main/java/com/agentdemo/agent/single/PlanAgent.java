package com.agentdemo.agent.single;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.agent.core.TaskBreakdownStream;
import com.agentdemo.llm.factory.ModelFactory;
import com.agentdemo.memory.shortterm.ChatMemoryManager;
import com.agentdemo.tools.registry.ToolExecutor;
import com.agentdemo.tools.registry.ToolSchemaConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 任务拆解 Agent（CR-002 新增）
 * <p>
 * 业务含义：接收用户消息，创建 TaskBreakdownStream 进行三阶段编排（规划 -> 执行 -> 总结）。
 * 不实现 BaseAgent 接口，因为其方法签名不同（返回 TaskBreakdownStream 而非 TokenStream）。
 * </p>
 * <p>
 * 调用方：web 层 AgentController.chatStream（当 enableTaskBreakdown=true 时使用）
 * </p>
 * <p>
 * 依赖注入：ModelFactory（LLM 模型）、ChatMemoryManager（会话记忆）、
 * AgentConfig（配置）、ToolSchemaConverter（工具 Schema）、ToolExecutor（工具执行）
 * </p>
 */
@Service
public class PlanAgent {

    private static final Logger log = LoggerFactory.getLogger(PlanAgent.class);

    private final ModelFactory modelFactory;
    private final ChatMemoryManager memoryManager;
    private final AgentConfig agentConfig;
    private final ToolSchemaConverter toolSchemaConverter;
    private final ToolExecutor toolExecutor;

    /**
     * 构造器注入（禁止 @Autowired 字段注入）
     *
     * @param modelFactory        模型工厂（提供 ChatModel 和 ArkThinkingStreamingChatModel）
     * @param memoryManager       记忆管理器（会话级短期记忆）
     * @param agentConfig         Agent 配置（提示词、迭代次数等）
     * @param toolSchemaConverter 工具 Schema 转换器（工具描述和 JSON Schema）
     * @param toolExecutor        工具执行器（ReAct 循环中执行工具调用）
     */
    public PlanAgent(ModelFactory modelFactory,
                     ChatMemoryManager memoryManager,
                     AgentConfig agentConfig,
                     ToolSchemaConverter toolSchemaConverter,
                     ToolExecutor toolExecutor) {
        this.modelFactory = modelFactory;
        this.memoryManager = memoryManager;
        this.agentConfig = agentConfig;
        this.toolSchemaConverter = toolSchemaConverter;
        this.toolExecutor = toolExecutor;
        log.info("PlanAgent 构造完成");
    }

    /**
     * 任务拆解流式对话
     * <p>
     * 业务含义：创建三阶段编排流（规划 -> 执行 -> 总结），由 Controller 注册回调后 start()。
     * Controller 通过链式调用注册回调，将 TaskBreakdownStream 的事件映射为 SSE 事件推送给前端。
     * </p>
     *
     * @param sessionId      会话 ID
     * @param message        用户消息
     * @param enableThinking 是否开启深度思考（与任务拆解独立共存，AC-011）
     * @return TaskBreakdownStream 实例（需调用 start() 启动）
     */
    public TaskBreakdownStream chatTaskBreakdownStream(String sessionId, String message, boolean enableThinking) {
        log.info("Agent 任务拆解流式对话: sessionId={}, message={}, enableThinking={}",
                sessionId, message, enableThinking);

        return new TaskBreakdownStream(
                sessionId, message, enableThinking,
                modelFactory, memoryManager, agentConfig, toolSchemaConverter, toolExecutor);
    }
}
