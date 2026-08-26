package com.agentdemo.app.core;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核心枚举类测试（Task-03）
 */
class EnumsTest {

    @Test
    void orchestrationMode_shouldContainAllFiveValues() {
        assertEquals(5, OrchestrationMode.values().length);
        assertTrue(Arrays.stream(OrchestrationMode.values())
                .anyMatch(m -> m == OrchestrationMode.SEQUENTIAL));
        assertTrue(Arrays.stream(OrchestrationMode.values())
                .anyMatch(m -> m == OrchestrationMode.PARALLEL));
        assertTrue(Arrays.stream(OrchestrationMode.values())
                .anyMatch(m -> m == OrchestrationMode.CONDITIONAL));
        assertTrue(Arrays.stream(OrchestrationMode.values())
                .anyMatch(m -> m == OrchestrationMode.LOOP));
        assertTrue(Arrays.stream(OrchestrationMode.values())
                .anyMatch(m -> m == OrchestrationMode.SUPERVISOR));
    }

    @Test
    void workflowExecutionStatus_shouldContainAllEightValues() {
        // P3 新增 PAUSED（可恢复态）：6 -> 7；工作流 HITL 新增 WAITING_USER：7 -> 8
        assertEquals(8, WorkflowExecutionStatus.values().length);
        assertTrue(Arrays.stream(WorkflowExecutionStatus.values())
                .anyMatch(s -> s == WorkflowExecutionStatus.PENDING));
        assertTrue(Arrays.stream(WorkflowExecutionStatus.values())
                .anyMatch(s -> s == WorkflowExecutionStatus.RUNNING));
        assertTrue(Arrays.stream(WorkflowExecutionStatus.values())
                .anyMatch(s -> s == WorkflowExecutionStatus.COMPLETED));
        assertTrue(Arrays.stream(WorkflowExecutionStatus.values())
                .anyMatch(s -> s == WorkflowExecutionStatus.FAILED));
        assertTrue(Arrays.stream(WorkflowExecutionStatus.values())
                .anyMatch(s -> s == WorkflowExecutionStatus.TERMINATED));
        assertTrue(Arrays.stream(WorkflowExecutionStatus.values())
                .anyMatch(s -> s == WorkflowExecutionStatus.TIMEOUT));
        assertTrue(Arrays.stream(WorkflowExecutionStatus.values())
                .anyMatch(s -> s == WorkflowExecutionStatus.PAUSED));
        assertTrue(Arrays.stream(WorkflowExecutionStatus.values())
                .anyMatch(s -> s == WorkflowExecutionStatus.WAITING_USER));
    }

    @Test
    void stepStatus_shouldContainAllFourValues() {
        assertEquals(4, StepStatus.values().length);
        assertTrue(Arrays.stream(StepStatus.values())
                .anyMatch(s -> s == StepStatus.PENDING));
        assertTrue(Arrays.stream(StepStatus.values())
                .anyMatch(s -> s == StepStatus.RUNNING));
        assertTrue(Arrays.stream(StepStatus.values())
                .anyMatch(s -> s == StepStatus.COMPLETED));
        assertTrue(Arrays.stream(StepStatus.values())
                .anyMatch(s -> s == StepStatus.FAILED));
    }

    @Test
    void eachEnumValue_shouldHaveNonEmptyChineseDescription() {
        for (OrchestrationMode mode : OrchestrationMode.values()) {
            assertNotNull(mode.getDescription());
            assertFalse(mode.getDescription().isBlank());
        }
        for (WorkflowExecutionStatus status : WorkflowExecutionStatus.values()) {
            assertNotNull(status.getDescription());
            assertFalse(status.getDescription().isBlank());
        }
        for (StepStatus status : StepStatus.values()) {
            assertNotNull(status.getDescription());
            assertFalse(status.getDescription().isBlank());
        }
    }

    @Test
    void sequential_shouldHaveChineseDescription() {
        assertEquals("串行编排", OrchestrationMode.SEQUENTIAL.getDescription());
    }
}
