package com.agentdemo.common.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 工作流错误码测试（Task-02）
 */
class ErrorCodeTest {

    @Test
    void workflowErrorCodes_shouldReturnExpectedCodes() {
        assertEquals(5500, ErrorCode.WORKFLOW_NOT_FOUND.getCode());
        assertEquals(5501, ErrorCode.WORKFLOW_PARAM_MISSING.getCode());
        assertEquals(5502, ErrorCode.WORKFLOW_MODEL_NOT_FOUND.getCode());
        assertEquals(5503, ErrorCode.WORKFLOW_EXECUTION_FAILED.getCode());
        assertEquals(5504, ErrorCode.WORKFLOW_TIMEOUT.getCode());
        assertEquals(5505, ErrorCode.WORKFLOW_ALREADY_TERMINATED.getCode());
        // P2 新增：不支持的编排模式
        assertEquals(5506, ErrorCode.WORKFLOW_MODE_NOT_SUPPORTED.getCode());
    }

    @Test
    void workflowErrorCodes_shouldHaveNonEmptyChineseMessage() {
        assertNotNull(ErrorCode.WORKFLOW_NOT_FOUND.getMessage());
        assertFalse(ErrorCode.WORKFLOW_NOT_FOUND.getMessage().isBlank());
        assertFalse(ErrorCode.WORKFLOW_PARAM_MISSING.getMessage().isBlank());
        assertFalse(ErrorCode.WORKFLOW_MODEL_NOT_FOUND.getMessage().isBlank());
        assertFalse(ErrorCode.WORKFLOW_EXECUTION_FAILED.getMessage().isBlank());
        assertFalse(ErrorCode.WORKFLOW_TIMEOUT.getMessage().isBlank());
        assertFalse(ErrorCode.WORKFLOW_ALREADY_TERMINATED.getMessage().isBlank());
        assertFalse(ErrorCode.WORKFLOW_MODE_NOT_SUPPORTED.getMessage().isBlank());
    }
}
