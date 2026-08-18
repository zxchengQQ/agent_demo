package com.agentdemo.app.core;

import com.agentdemo.app.template.AnalysisAgent;
import com.agentdemo.app.template.ResearchAgent;
import com.agentdemo.app.template.SummaryAgent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Supervisor 数据类测试（P3 Task-02）
 * <p>
 * 业务含义：验证 Supervisor 层级编排的"组织架构图"数据模型——
 * 主控（拆解+汇总）+ Worker 池 + 子任务上限（AC-007 前置，BR-APP-012）。
 * </p>
 */
class SupervisorDefinitionTest {

    private AgentDefinition agent(String name, Class<?> iface) {
        return AgentDefinition.builder()
                .name(name).description("d").toolIds(List.of()).interfaceClass(iface)
                .build();
    }

    @Test
    void supervisorDefinition_builder_shouldFillAllFields() {
        AgentDefinition plan = agent("任务拆解(主控)", ResearchAgent.class);
        AgentDefinition w1 = agent("研究", ResearchAgent.class);
        AgentDefinition w2 = agent("分析", AnalysisAgent.class);
        AgentDefinition summarize = agent("综合汇总(主控)", SummaryAgent.class);

        SupervisorDefinition sup = SupervisorDefinition.builder()
                .planAgent(plan)
                .workers(List.of(w1, w2))
                .summarizeAgent(summarize)
                .maxSubtasks(5)
                .build();

        assertSame(plan, sup.getPlanAgent());
        assertEquals(2, sup.getWorkers().size());
        assertSame(w1, sup.getWorkers().get(0));
        assertSame(summarize, sup.getSummarizeAgent());
        assertEquals(5, sup.getMaxSubtasks());
    }

    @Test
    void subtask_builder_shouldFillAllFields() {
        Subtask subtask = Subtask.builder()
                .id(1).description("调研分类算法").agent("研究")
                .build();
        assertEquals(1, subtask.getId());
        assertEquals("调研分类算法", subtask.getDescription());
        assertEquals("研究", subtask.getAgent());
    }

    @Test
    void workflowTemplate_supervisorField_shouldBeNullable() {
        SupervisorDefinition sup = SupervisorDefinition.builder()
                .planAgent(agent("plan", ResearchAgent.class))
                .workers(List.of(agent("w", ResearchAgent.class)))
                .summarizeAgent(agent("sum", SummaryAgent.class))
                .maxSubtasks(5)
                .build();

        WorkflowTemplate template = WorkflowTemplate.builder()
                .id("tpl-sup").name("n").description("d")
                .mode(OrchestrationMode.SUPERVISOR).maxRetries(0)
                .agents(List.of()).parameters(List.of())
                .supervisor(sup)
                .build();
        assertSame(sup, template.getSupervisor());

        // 不设置时为 null（P1/P2 既有模板兼容）
        WorkflowTemplate legacy = WorkflowTemplate.builder()
                .id("tpl-seq").name("n").description("d")
                .mode(OrchestrationMode.SEQUENTIAL).maxRetries(0)
                .agents(List.of()).parameters(List.of())
                .build();
        assertNull(legacy.getSupervisor());
    }
}
