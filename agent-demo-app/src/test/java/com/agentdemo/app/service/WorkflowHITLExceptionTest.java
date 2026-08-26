package com.agentdemo.app.service;

import com.agentdemo.app.service.WorkflowHITLState.AskUserData;
import com.agentdemo.app.service.WorkflowHITLState.PendingStep;
import com.agentdemo.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * WorkflowHITLException 测试（工作流 HITL Task-01）
 * <p>
 * 业务含义：验证"等待用户输入"信号异常——携带完整的 HITL 暂停状态（模式/提问数据/暂停步骤/追问计数），
 * 且是 WorkflowPausedException 子类（复用失败 Agent 名与步骤索引语义，兼容现有异常处理链，AC-N02 前置）。
 * </p>
 */
class WorkflowHITLExceptionTest {

    @Test
    void constructor_shouldCarryHITLState() {
        PendingStep pendingStep = new PendingStep(2, "研究 Agent", "请分析 AI Agent", 0);
        AskUserData askUserData = new AskUserData("confirm", "确认执行此步骤？", List.of("确认", "取消"), 0);
        WorkflowHITLState state = new WorkflowHITLState(
                WorkflowHITLState.MODE_CHECKPOINT, askUserData, pendingStep, List.of(), 0);

        WorkflowHITLException ex = new WorkflowHITLException(state, "等待用户确认", null);

        assertSame(state, ex.getHitlState(), "异常应携带完整的 HITL 状态快照");
    }

    @Test
    void hitlMode_shouldSupportAskUserAndCheckpoint() {
        PendingStep pendingStep = new PendingStep(0, "研究 Agent", "输入", 0);
        WorkflowHITLState askUserState = new WorkflowHITLState(
                WorkflowHITLState.MODE_ASK_USER,
                new AskUserData("text", "请提供主题", List.of(), 1),
                pendingStep, List.of(), 1);
        WorkflowHITLState checkpointState = new WorkflowHITLState(
                WorkflowHITLState.MODE_CHECKPOINT,
                new AskUserData("confirm", "确认？", List.of("确认", "取消"), 0),
                pendingStep, List.of(), 0);

        assertEquals("askUser", askUserState.getHitlMode());
        assertEquals("checkpoint", checkpointState.getHitlMode());
    }

    @Test
    void askUserData_shouldCarryQuestionAndOptionsAndRetryCount() {
        AskUserData askUserData = new AskUserData("confirm", "是否继续修订？", List.of("继续", "停止"), 2);
        assertEquals("confirm", askUserData.getType());
        assertEquals("是否继续修订？", askUserData.getQuestion());
        assertEquals(List.of("继续", "停止"), askUserData.getOptions());
        assertEquals(2, askUserData.getRetryCount());
    }

    @Test
    void pendingStep_shouldCarryExecutionPosition() {
        PendingStep pendingStep = new PendingStep(3, "修订 Agent", "当前稿件", 2);
        assertEquals(3, pendingStep.getAgentIndex());
        assertEquals("修订 Agent", pendingStep.getAgentName());
        assertEquals("当前稿件", pendingStep.getInput());
        assertEquals(2, pendingStep.getIteration());
    }

    @Test
    void exception_shouldBeCatchableAsWorkflowPausedAndBusiness() {
        PendingStep pendingStep = new PendingStep(1, "研究 Agent", "输入", 0);
        WorkflowHITLState state = new WorkflowHITLState(
                WorkflowHITLState.MODE_CHECKPOINT,
                new AskUserData("confirm", "确认？", List.of("确认", "取消"), 0),
                pendingStep, List.of(), 0);
        WorkflowHITLException ex = new WorkflowHITLException(state, "等待用户确认", null);

        // 子类兼容：协调层 catch (WorkflowPausedException) 与既有 assertThrows(BusinessException) 零适配
        assertThrows(WorkflowPausedException.class, () -> {
            throw ex;
        });
        assertThrows(BusinessException.class, () -> {
            throw ex;
        });
    }

    @Test
    void exception_shouldReuseFailedAgentInfoForPauseSemantics() {
        PendingStep pendingStep = new PendingStep(1, "研究 Agent", "输入", 0);
        WorkflowHITLState state = new WorkflowHITLState(
                WorkflowHITLState.MODE_CHECKPOINT,
                new AskUserData("confirm", "确认？", List.of("确认", "取消"), 0),
                pendingStep, List.of(), 0);
        WorkflowHITLException ex = new WorkflowHITLException(state, "等待用户确认", null);

        // 复用父类 failedAgentName/failedIndex：暂停步骤的 Agent 名与索引
        assertEquals("研究 Agent", ex.getFailedAgentName());
        assertEquals(1, ex.getFailedIndex());
    }
}
