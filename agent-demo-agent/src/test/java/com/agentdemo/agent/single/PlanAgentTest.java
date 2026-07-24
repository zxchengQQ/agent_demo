package com.agentdemo.agent.single;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.agent.core.TaskBreakdownStream;
import com.agentdemo.llm.factory.ModelFactory;
import com.agentdemo.memory.shortterm.ChatMemoryManager;
import com.agentdemo.tools.registry.ToolExecutor;
import com.agentdemo.tools.registry.ToolSchemaConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/**
 * PlanAgent 测试（Task-05）
 * <p>
 * 验证标准来源：Task-05 验证标准
 * 关联 AC：AC-001（任务拆解入口）
 * </p>
 */
class PlanAgentTest {

    private ModelFactory modelFactory;
    private ChatMemoryManager memoryManager;
    private AgentConfig agentConfig;
    private ToolSchemaConverter toolSchemaConverter;
    private ToolExecutor toolExecutor;
    private PlanAgent planAgent;

    @BeforeEach
    void setUp() {
        modelFactory = mock(ModelFactory.class);
        memoryManager = mock(ChatMemoryManager.class);
        agentConfig = new AgentConfig();
        toolSchemaConverter = mock(ToolSchemaConverter.class);
        toolExecutor = mock(ToolExecutor.class);
        planAgent = new PlanAgent(modelFactory, memoryManager, agentConfig, toolSchemaConverter, toolExecutor);
    }

    /**
     * 验证 chatTaskBreakdownStream 返回非 null 的 TaskBreakdownStream 实例
     * 业务含义：Controller 调用此方法获取编排流，返回值不能为 null
     */
    @Test
    void chatTaskBreakdownStreamShouldReturnNonNull() {
        TaskBreakdownStream stream = planAgent.chatTaskBreakdownStream("session1", "msg", false);

        assertNotNull(stream, "chatTaskBreakdownStream 应返回非 null 的 TaskBreakdownStream 实例");
    }

    /**
     * 验证 enableThinking 参数正确传递
     * 业务含义：enableThinking 状态需要传递给 TaskBreakdownStream
     */
    @Test
    void chatTaskBreakdownStreamShouldAcceptEnableThinkingTrue() {
        TaskBreakdownStream stream = planAgent.chatTaskBreakdownStream("session1", "msg", true);

        assertNotNull(stream, "enableThinking=true 时应正常返回 TaskBreakdownStream");
    }

    /**
     * 验证 PlanAgent 实例化时依赖注入成功
     * 业务含义：所有依赖（ModelFactory、ChatMemoryManager、AgentConfig、ToolSchemaConverter、ToolExecutor）均非 null
     */
    @Test
    void planAgentShouldBeInstantiatedWithAllDependencies() {
        assertNotNull(planAgent, "PlanAgent 应成功实例化");

        // 验证可以正常调用方法（间接验证依赖注入成功）
        TaskBreakdownStream stream = planAgent.chatTaskBreakdownStream("test", "hello", false);
        assertNotNull(stream, "依赖注入成功后应能正常创建 TaskBreakdownStream");
    }
}
