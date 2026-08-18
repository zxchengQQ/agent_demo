package com.agentdemo.web.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Workflow DTO 测试（Task-13）
 */
class WorkflowDtoTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    void executeRequest_shouldHaveParametersAndModelId() {
        WorkflowExecuteRequest request = new WorkflowExecuteRequest();
        request.setParameters(Map.of("topic", "AI"));
        request.setModelId("vendor1:model1");
        assertNotNull(request.getParameters());
        assertEquals("AI", request.getParameters().get("topic"));
        assertEquals("vendor1:model1", request.getModelId());
    }

    @Test
    void executeRequest_shouldRequireParameters() {
        WorkflowExecuteRequest request = new WorkflowExecuteRequest();
        request.setParameters(null);
        assertFalse(validator.validate(request).isEmpty(), "parameters 为 null 时应校验失败");
    }

    @Test
    void templateResponse_shouldHaveAllFields() {
        WorkflowTemplateResponse response = WorkflowTemplateResponse.builder()
                .id("tpl-1")
                .name("模板")
                .description("描述")
                .mode("SEQUENTIAL")
                .agentCount(3)
                .parameters(List.of())
                .build();
        assertEquals("tpl-1", response.getId());
        assertEquals("SEQUENTIAL", response.getMode());
        assertEquals(3, response.getAgentCount());
    }

    @Test
    void detailResponse_shouldHaveAgentsWithTools() {
        WorkflowDetailResponse.AgentItem agent = WorkflowDetailResponse.AgentItem.builder()
                .name("研究 Agent")
                .description("d")
                .modelId(null)
                .tools(List.of("builtin:httpGet"))
                .build();
        WorkflowDetailResponse response = WorkflowDetailResponse.builder()
                .id("tpl-1")
                .maxRetries(3)
                .agents(List.of(agent))
                .build();
        assertEquals(3, response.getMaxRetries());
        assertEquals(1, response.getAgents().size());
        assertEquals(List.of("builtin:httpGet"), response.getAgents().get(0).getTools());
    }

    @Test
    void executionResponse_shouldHaveAllFields() {
        WorkflowExecutionResponse.StepItem step = WorkflowExecutionResponse.StepItem.builder()
                .agentName("研究 Agent")
                .status("COMPLETED")
                .durationMs(5200)
                .build();
        WorkflowExecutionResponse response = WorkflowExecutionResponse.builder()
                .executionId("exec-1")
                .templateId("tpl-1")
                .templateName("模板")
                .status("COMPLETED")
                .steps(List.of(step))
                .finalResult("结果")
                .build();
        assertEquals("exec-1", response.getExecutionId());
        assertEquals("COMPLETED", response.getStatus());
        assertEquals(5200, response.getSteps().get(0).getDurationMs());
        assertEquals("结果", response.getFinalResult());
        assertTrue(response.getStartTime() == null); // 初始未设置
    }

    @Test
    void summaryResponse_shouldHaveAllFields() {
        WorkflowExecutionSummaryResponse response = WorkflowExecutionSummaryResponse.builder()
                .executionId("exec-1")
                .templateId("tpl-1")
                .templateName("模板")
                .mode("LOOP")
                .status("COMPLETED")
                .finalResult("结果")
                .iterationCount(3)
                .build();
        assertEquals("exec-1", response.getExecutionId());
        assertEquals("LOOP", response.getMode());
        assertEquals("COMPLETED", response.getStatus());
        assertEquals(3, response.getIterationCount());
        assertEquals("结果", response.getFinalResult());
    }

    @Test
    void executionResponse_shouldHaveModeAndIterationCount() {
        WorkflowExecutionResponse response = WorkflowExecutionResponse.builder()
                .executionId("exec-1")
                .templateId("tpl-1")
                .templateName("模板")
                .status("COMPLETED")
                .mode("LOOP")
                .iterationCount(5)
                .steps(List.of())
                .finalResult("结果")
                .build();
        assertEquals("LOOP", response.getMode());
        assertEquals(5, response.getIterationCount());
    }
}
