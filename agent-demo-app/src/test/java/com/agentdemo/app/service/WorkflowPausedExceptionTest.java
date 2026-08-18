package com.agentdemo.app.service;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * WorkflowPausedException 测试（P3 Task-03）
 * <p>
 * 业务含义：验证"中场休息信号"异常——携带失败 Agent 名与步骤索引，
 * 且子类兼容 BusinessException（P1/P2 既有 catch 分支与 assertThrows 测试零适配，AC-016 前置）。
 * </p>
 */
class WorkflowPausedExceptionTest {

    @Test
    void constructor_shouldCarryFailedAgentInfo() {
        WorkflowPausedException ex = new WorkflowPausedException("研究 Agent", 1, "Agent 执行失败", null);
        assertEquals("研究 Agent", ex.getFailedAgentName());
        assertEquals(1, ex.getFailedIndex());
    }

    @Test
    void pausedException_shouldBeCatchableAsBusinessException() {
        // 子类兼容：P1/P2 的 catch (BusinessException) 与 assertThrows(BusinessException.class) 不受影响
        assertDoesNotThrow(() -> {
            try {
                throw new WorkflowPausedException("研究 Agent", 1, "超时", null);
            } catch (BusinessException e) {
                // 预期被捕获
            }
        });
        assertThrows(BusinessException.class, () -> {
            throw new WorkflowPausedException("研究 Agent", 1, "超时", null);
        });
    }

    @Test
    void errorCode_shouldReuseWorkflowExecutionFailed() {
        WorkflowPausedException ex = new WorkflowPausedException("研究 Agent", 1, "超时", null);
        assertEquals(ErrorCode.WORKFLOW_EXECUTION_FAILED, ex.getErrorCode());
        assertEquals(5503, ex.getErrorCode().getCode());
    }
}
