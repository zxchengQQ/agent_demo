package com.agentdemo.app.core;

import com.agentdemo.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * WorkflowExecution 暂停/恢复状态模型测试（P3 Task-01）
 * <p>
 * 业务含义：验证 P3 新增的 PAUSED 状态与 context 挂载机制——
 * 重试耗尽后进入"中场休息"状态（非终态），随时可从断点恢复（AC-016/AC-017 前置）。
 * </p>
 */
class WorkflowExecutionTest {

    private WorkflowExecution runningExecution() {
        WorkflowExecution execution = new WorkflowExecution("exec-1", "tpl-1", "模板");
        execution.start();
        return execution;
    }

    @Test
    void pausedStatus_shouldExistWithNamePaused() {
        assertEquals("PAUSED", WorkflowExecutionStatus.PAUSED.name());
        assertNotNull(WorkflowExecutionStatus.PAUSED.getDescription());
    }

    @Test
    void pause_fromRunning_shouldSetPausedAndKeepEndTimeNull() {
        WorkflowExecution execution = runningExecution();
        execution.pause("研究 Agent", "超时");
        assertEquals(WorkflowExecutionStatus.PAUSED, execution.getStatus());
        // 业务含义：暂停非终态，endTime 必须为 null（恢复后重跑再定）
        assertNull(execution.getEndTime());
    }

    @Test
    void resumeFromPause_shouldBackToRunningAndKeepStartTime() {
        WorkflowExecution execution = runningExecution();
        LocalDateTime startTimeBefore = execution.getStartTime();
        execution.pause("研究 Agent", "超时");
        execution.resumeFromPause();
        assertEquals(WorkflowExecutionStatus.RUNNING, execution.getStatus());
        // 业务含义：startTime 保留原值（历史总耗时语义），不因恢复而重置
        assertEquals(startTimeBefore, execution.getStartTime());
    }

    @Test
    void attachContext_shouldReturnSameInstanceAndOverwrite() {
        WorkflowExecution execution = runningExecution();
        WorkflowContext ctx1 = new WorkflowContext();
        execution.attachContext(ctx1);
        assertSame(ctx1, execution.getContext());

        // 再次 attach 不同 ctx 覆盖
        WorkflowContext ctx2 = new WorkflowContext();
        execution.attachContext(ctx2);
        assertSame(ctx2, execution.getContext());
    }

    @Test
    void errorCodeWorkflowNotResumable_shouldBe5507() {
        assertEquals(5507, ErrorCode.WORKFLOW_NOT_RESUMABLE.getCode());
    }
}
