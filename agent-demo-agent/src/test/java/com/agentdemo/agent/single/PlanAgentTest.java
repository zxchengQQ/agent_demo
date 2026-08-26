package com.agentdemo.agent.single;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.agent.core.HumanInteractionManager;
import com.agentdemo.agent.core.TaskPlanJudge;
import com.agentdemo.agent.core.UnifiedChatStream;
import com.agentdemo.agent.prompt.PromptTemplateLoader;
import com.agentdemo.llm.registry.ModelFactory;
import com.agentdemo.memory.shortterm.ChatMemoryManager;
import com.agentdemo.tools.registry.ToolExecutor;
import com.agentdemo.tools.registry.ToolRegistry;
import com.agentdemo.tools.registry.ToolSchemaConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/**
 * PlanAgent 测试（unified-chat-mode Task-12）
 * <p>
 * 验证标准来源：unified-chat-mode 任务规划 Task-12 验证标准
 * 关联 AC：AC-N01（工厂统一）
 * 业务含义：验证统一模式工厂——chatUnifiedStream/resumeUnifiedStream 正确构造
 * UnifiedChatStream 实例，依赖注入完整。
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
        planAgent = new PlanAgent(modelFactory, memoryManager, agentConfig, toolSchemaConverter, toolExecutor,
                new PromptTemplateLoader(agentConfig), mock(HumanInteractionManager.class),
                new SessionToolResolver(mock(ToolRegistry.class), agentConfig), mock(TaskPlanJudge.class));
    }

    /**
     * 验证 chatUnifiedStream 返回非 null 的 UnifiedChatStream 实例（首次/强制拆解）
     */
    @Test
    @DisplayName("chatUnifiedStream 返回非 null 实例")
    void chatUnifiedStreamShouldReturnNonNull() {
        UnifiedChatStream stream = planAgent.chatUnifiedStream("session1", "msg", null, null, false);

        assertNotNull(stream, "chatUnifiedStream 应返回非 null 的 UnifiedChatStream 实例");
    }

    /**
     * 验证 forcedBreakdown=true 时构造正常
     */
    @Test
    @DisplayName("chatUnifiedStream 支持 forcedBreakdown 参数")
    void chatUnifiedStreamShouldAcceptForcedBreakdown() {
        UnifiedChatStream stream = planAgent.chatUnifiedStream("session1", "调研竞品", null, null, true);

        assertNotNull(stream, "forcedBreakdown=true 时应正常返回 UnifiedChatStream");
    }

    /**
     * 验证 resumeUnifiedStream 返回非 null 的恢复实例
     */
    @Test
    @DisplayName("resumeUnifiedStream 返回非 null 恢复实例")
    void resumeUnifiedStreamShouldReturnNonNull() {
        UnifiedChatStream stream = planAgent.resumeUnifiedStream("session1", "回复");

        assertNotNull(stream, "resumeUnifiedStream 应返回非 null 的 UnifiedChatStream 实例");
    }

    /**
     * 验证三参重载将 approved 透传至 UnifiedChatStream（tool_confirm 恢复通道，AC-S02）
     */
    @Test
    @DisplayName("resumeUnifiedStream 三参重载透传 approved")
    void resumeUnifiedStreamThreeArgs_透传approved() {
        UnifiedChatStream approvedStream = planAgent.resumeUnifiedStream("session1", "回复", Boolean.TRUE);
        UnifiedChatStream deniedStream = planAgent.resumeUnifiedStream("session1", "回复", Boolean.FALSE);

        assertNotNull(approvedStream, "三参重载应返回非 null 实例");
        assertNotNull(deniedStream, "三参重载应返回非 null 实例");
        assertEquals(Boolean.TRUE, readApproved(approvedStream), "approved=true 应透传到 UnifiedChatStream");
        assertEquals(Boolean.FALSE, readApproved(deniedStream), "approved=false 应透传到 UnifiedChatStream");
    }

    /**
     * 验证旧签名委托三参重载且 approved=null（普通 HITL askUser 恢复零变更，向后兼容）
     */
    @Test
    @DisplayName("resumeUnifiedStream 旧签名 approved=null（向后兼容）")
    void resumeUnifiedStreamTwoArgs_approved默认null() {
        UnifiedChatStream stream = planAgent.resumeUnifiedStream("session1", "回复");

        assertNotNull(stream, "旧签名应返回非 null 实例");
        assertEquals(null, readApproved(stream), "旧签名 approved 应为 null");
    }

    /** 反射读取 UnifiedChatStream.approved 字段（验证透传，不暴露生产 API） */
    private Object readApproved(UnifiedChatStream stream) {
        try {
            java.lang.reflect.Field field = UnifiedChatStream.class.getDeclaredField("approved");
            field.setAccessible(true);
            return field.get(stream);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("反射读取 approved 失败", e);
        }
    }

    /**
     * 验证 PlanAgent 实例化时依赖注入成功
     */
    @Test
    @DisplayName("PlanAgent 依赖注入成功后能创建统一编排流")
    void planAgentShouldBeInstantiatedWithAllDependencies() {
        assertNotNull(planAgent, "PlanAgent 应成功实例化");

        UnifiedChatStream stream = planAgent.chatUnifiedStream("test", "hello", null, null, false);
        assertNotNull(stream, "依赖注入成功后应能正常创建 UnifiedChatStream");
    }
}
