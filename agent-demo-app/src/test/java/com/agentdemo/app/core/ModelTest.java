package com.agentdemo.app.core;

import com.agentdemo.app.template.ResearchAgent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核心数据模型类测试（Task-04）
 */
class ModelTest {

    @Test
    void parameterDefinition_shouldBuildWithAllFields() {
        ParameterDefinition param = ParameterDefinition.builder()
                .name("topic")
                .type("string")
                .required(true)
                .description("研究主题")
                .build();
        assertEquals("topic", param.getName());
        assertEquals("string", param.getType());
        assertTrue(param.isRequired());
        assertEquals("研究主题", param.getDescription());
    }

    @Test
    void agentDefinition_shouldBuildWithAllFields() {
        AgentDefinition agent = AgentDefinition.builder()
                .name("研究 Agent")
                .description("收集信息")
                .modelId(null)
                .toolIds(List.of("builtin:httpGet"))
                .roleName("general")
                .scenarioName("app-research")
                .interfaceClass(ResearchAgent.class)
                .build();
        assertEquals("研究 Agent", agent.getName());
        assertEquals(null, agent.getModelId());
        assertEquals(List.of("builtin:httpGet"), agent.getToolIds());
        assertEquals(ResearchAgent.class, agent.getInterfaceClass());
    }

    @Test
    void workflowTemplate_shouldBuildWithAllFields() {
        WorkflowTemplate template = WorkflowTemplate.builder()
                .id("test")
                .name("测试")
                .description("测试模板")
                .mode(OrchestrationMode.SEQUENTIAL)
                .maxRetries(3)
                .agents(List.of())
                .parameters(List.of())
                .build();
        assertEquals("test", template.getId());
        assertEquals("测试", template.getName());
        assertEquals(OrchestrationMode.SEQUENTIAL, template.getMode());
        assertEquals(3, template.getMaxRetries());
        assertNotNull(template.getAgents());
        assertNotNull(template.getParameters());
    }

    @Test
    void stepExecution_shouldContainAllFields() {
        StepExecution step = new StepExecution("研究 Agent", 0, StepStatus.RUNNING);
        assertEquals("研究 Agent", step.getAgentName());
        assertEquals(0, step.getIndex());
        assertEquals(StepStatus.RUNNING, step.getStatus());
        assertFalse(step.getError() != null); // 初始 error 为 null
    }

    @Test
    void stepExecution_complete_shouldSetOutputAndStatus() {
        StepExecution step = new StepExecution("研究 Agent", 0, StepStatus.RUNNING);
        step.complete("输出内容");
        assertEquals(StepStatus.COMPLETED, step.getStatus());
        assertEquals("输出内容", step.getOutput());
        assertTrue(step.getDurationMs() >= 0);
        assertNotNull(step.getEndTime());
    }

    @Test
    void workflowExecution_shouldContainAllFields() {
        WorkflowExecution execution = new WorkflowExecution("exec-1", "tpl-1", "测试模板");
        assertEquals("exec-1", execution.getExecutionId());
        assertEquals("tpl-1", execution.getTemplateId());
        assertEquals("测试模板", execution.getTemplateName());
        assertEquals(WorkflowExecutionStatus.PENDING, execution.getStatus());
        assertNotNull(execution.getSteps());
        // start() 后 startTime 才被设置（PENDING 状态未开始为 null）
        execution.start();
        assertNotNull(execution.getStartTime());
    }

    @Test
    void workflowExecution_complete_shouldSetFinalResult() {
        WorkflowExecution execution = new WorkflowExecution("exec-1", "tpl-1", "测试模板");
        execution.start();
        execution.complete("最终结果");
        assertEquals(WorkflowExecutionStatus.COMPLETED, execution.getStatus());
        assertEquals("最终结果", execution.getFinalResult());
        assertNotNull(execution.getEndTime());
    }

    @Test
    void workflowExecution_setMode_shouldReturnValue() {
        WorkflowExecution execution = new WorkflowExecution("exec-1", "tpl-1", "测试模板");
        execution.setMode(OrchestrationMode.LOOP);
        assertEquals(OrchestrationMode.LOOP, execution.getMode());
    }

    @Test
    void workflowExecution_setIterationCount_shouldReturnValue() {
        WorkflowExecution execution = new WorkflowExecution("exec-1", "tpl-1", "测试模板");
        // 默认迭代次数为 0（未设置时）
        assertEquals(0, execution.getIterationCount());
        execution.setIterationCount(3);
        assertEquals(3, execution.getIterationCount());
    }
}
