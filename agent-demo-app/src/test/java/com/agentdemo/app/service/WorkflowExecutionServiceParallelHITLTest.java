package com.agentdemo.app.service;

import com.agentdemo.app.core.OrchestrationMode;
import com.agentdemo.app.core.WorkflowContext;
import com.agentdemo.app.core.WorkflowExecution;
import com.agentdemo.app.core.WorkflowExecutionStatus;
import com.agentdemo.app.core.WorkflowTemplate;
import com.agentdemo.app.strategy.AbstractExecutionStrategy;
import com.agentdemo.app.strategy.WorkflowExecutionStrategy;
import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WorkflowExecutionService 并行 HITL 排队 / 超时清理 / WAITING_USER 操作保护测试（Task-09）
 * <p>
 * 验证标准来源：Task-09 验证标准
 * 关联 AC：AC-E02（并行多 HITL 排队）、AC-E01（会话超时清理）、AC-S03（WAITING_USER 操作保护）、
 * AC-H01（用户终止等待中工作流）
 * </p>
 * <p>
 * 业务含义：
 * 1. 并行 HITL 排队——并行分组多个 Agent 同时 askUser 时，第一个生效进入 WAITING_USER，
 *    其余由并行策略写入 ctx 排队列表（PENDING_HITL_KEY）。用户每次 hitlReply 恢复时协调层
 *    先消费队列：仍有排队则更新快照并再次进入 WAITING_USER（依次问完），队列清空才真正
 *    重放策略执行。保证同一 executionId 同时只有一个 WAITING_USER（AC-E02）。
 * 2. 会话超时清理——扫描 WAITING_USER 超过 30 分钟未回复的执行置 TIMEOUT（终态）并清理
 *    HITL 快照（AC-E01）。
 * 3. WAITING_USER 操作保护——该状态下仅允许 hitlReply 和 terminate，resume（失败暂停恢复）
 *    被拒绝（AC-S03/AC-H01）。
 * </p>
 */
class WorkflowExecutionServiceParallelHITLTest {

    private WorkflowExecutionStrategy sequentialStrategy;
    private WorkflowExecutionService service;

    @BeforeEach
    void setUp() {
        sequentialStrategy = mock(WorkflowExecutionStrategy.class);
        when(sequentialStrategy.supportedMode()).thenReturn(OrchestrationMode.SEQUENTIAL);
        service = new WorkflowExecutionService(List.of(sequentialStrategy));
    }

    private WorkflowTemplate sequentialTemplate() {
        return WorkflowTemplate.builder()
                .id("tpl-seq")
                .name("串行模板")
                .mode(OrchestrationMode.SEQUENTIAL)
                .maxRetries(0)
                .agents(List.of())
                .parameters(List.of())
                .build();
    }

    /** askUser 模式 HITL 快照（暂停步 agentIndex=0，指定 Agent 名与问题文本） */
    private WorkflowHITLState askUserHitlState(String agentName, String question) {
        WorkflowHITLState.AskUserData askUserData =
                new WorkflowHITLState.AskUserData("text", question, List.of(), 0);
        WorkflowHITLState.PendingStep pendingStep =
                new WorkflowHITLState.PendingStep(0, agentName, "输入", 0);
        return new WorkflowHITLState(WorkflowHITLState.MODE_ASK_USER, askUserData, pendingStep, List.of(), 0);
    }

    /** 等待异步执行状态到达目标状态 */
    private void waitForStatus(String executionId, WorkflowExecutionStatus target) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            WorkflowExecution execution = service.getExecution(executionId);
            if (execution != null && execution.getStatus() == target) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        fail("状态未变为 " + target + ": 实际="
                + (service.getExecution(executionId) != null ? service.getExecution(executionId).getStatus() : "null"));
    }

    // ========== 验证标准：并行 HITL 排队——第一个生效其余排队，用户回复后按序处理（AC-E02）==========

    @Test
    void hitlReply_有排队HITL_应消费队列再次进入WaitingUser并写恢复key() throws Exception {
        // 第一个 HITL 生效进入 WAITING_USER
        when(sequentialStrategy.execute(any(), any(), any(), any(), any(), any()))
                .thenThrow(new WorkflowHITLException(askUserHitlState("研究 Agent", "问题1"), "等待", null));
        String executionId = service.execute(sequentialTemplate(), Map.of("topic", "AI"), mock(SseEmitter.class), null);
        waitForStatus(executionId, WorkflowExecutionStatus.WAITING_USER);

        // 模拟并行场景：ctx 中已有另一个并行分组的排队 HITL（问题2，由并行策略写入 PENDING_HITL_KEY）
        WorkflowHITLException queued =
                new WorkflowHITLException(askUserHitlState("分析 Agent", "问题2"), "排队", null);
        WorkflowExecution execution = service.getExecution(executionId);
        WorkflowContext ctx = new WorkflowContext();
        ctx.write(WorkflowContext.PENDING_HITL_KEY, List.of(queued));
        execution.attachContext(ctx);

        // 用户回复问题1 -> 协调层先消费排队队列 -> 再次进入 WAITING_USER（问题2），而非重放策略
        service.hitlReply(executionId, "回复A", null, mock(SseEmitter.class));
        assertEquals(WorkflowExecutionStatus.WAITING_USER, service.getExecution(executionId).getStatus());

        // 排队列表已清空（队列消费完，后续 hitlReply 将正常恢复）
        Object rest = execution.getContext().read(WorkflowContext.PENDING_HITL_KEY);
        assertNotNull(rest, "排队列表 key 应保留（空列表表示清空）");
        assertTrue(((List<?>) rest).isEmpty(), "消费后排队列表应清空");

        // 当前回复（问题1）已写入恢复 key（重放时据此恢复执行"研究 Agent"暂停步，避免死循环）
        Object resume = execution.getContext().read(AbstractExecutionStrategy.hitlResumeKey(0, "研究 Agent"));
        assertNotNull(resume, "已回复的问题1 应写入 hitl 恢复 key");
        assertEquals("回复A", ((WorkflowHITLState.HitlResume) resume).getMessage());
    }

    @Test
    void hitlReply_排队清空后_应正常恢复并完成() throws Exception {
        // 第一次执行抛 HITL#1 进入 WAITING_USER；恢复重放时返回最终结果
        when(sequentialStrategy.execute(any(), any(), any(), any(), any(), any()))
                .thenThrow(new WorkflowHITLException(askUserHitlState("研究 Agent", "问题1"), "等待", null))
                .thenReturn("最终结果");
        String executionId = service.execute(sequentialTemplate(), Map.of("topic", "AI"), mock(SseEmitter.class), null);
        waitForStatus(executionId, WorkflowExecutionStatus.WAITING_USER);

        // 模拟并行场景：ctx 中已有另一个并行分组的排队 HITL（问题2）
        WorkflowHITLException queued =
                new WorkflowHITLException(askUserHitlState("分析 Agent", "问题2"), "排队", null);
        WorkflowExecution execution = service.getExecution(executionId);
        WorkflowContext ctx = new WorkflowContext();
        ctx.write(WorkflowContext.PENDING_HITL_KEY, List.of(queued));
        execution.attachContext(ctx);

        // 第一次回复 -> 消费排队 -> 再次 WAITING_USER（按序处理问题2）
        service.hitlReply(executionId, "回复A", null, mock(SseEmitter.class));
        assertEquals(WorkflowExecutionStatus.WAITING_USER, service.getExecution(executionId).getStatus());

        // 第二次回复 -> 队列已空 -> 正常恢复重放 -> COMPLETED
        service.hitlReply(executionId, "回复B", null, mock(SseEmitter.class));
        waitForStatus(executionId, WorkflowExecutionStatus.COMPLETED);
        assertEquals("最终结果", service.getExecution(executionId).getFinalResult());
    }

    // ========== 验证标准：会话超时清理（30 分钟）正确清理 WAITING_USER 状态，状态置 TIMEOUT（AC-E01）==========

    @Test
    void cleanupExpiredWaitingUsers_超时_应置Timeout并清理快照() throws Exception {
        when(sequentialStrategy.execute(any(), any(), any(), any(), any(), any()))
                .thenThrow(new WorkflowHITLException(askUserHitlState("研究 Agent", "问题"), "等待", null));
        String executionId = service.execute(sequentialTemplate(), Map.of("topic", "AI"), mock(SseEmitter.class), null);
        waitForStatus(executionId, WorkflowExecutionStatus.WAITING_USER);

        // 0ms 超时：立即视为超时（测试直调重载，生产为 @Scheduled 30 分钟）
        service.cleanupExpiredWaitingUsers(0);
        assertEquals(WorkflowExecutionStatus.TIMEOUT, service.getExecution(executionId).getStatus());

        // 快照已清理：超时（终态）后 hitlReply 抛 5507
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.hitlReply(executionId, "回复", null, mock(SseEmitter.class)));
        assertEquals(ErrorCode.WORKFLOW_NOT_RESUMABLE, ex.getErrorCode());
    }

    @Test
    void cleanupExpiredWaitingUsers_未超时_应保留WaitingUser() throws Exception {
        when(sequentialStrategy.execute(any(), any(), any(), any(), any(), any()))
                .thenThrow(new WorkflowHITLException(askUserHitlState("研究 Agent", "问题"), "等待", null));
        String executionId = service.execute(sequentialTemplate(), Map.of("topic", "AI"), mock(SseEmitter.class), null);
        waitForStatus(executionId, WorkflowExecutionStatus.WAITING_USER);

        // 1 小时超时阈值：未超时，保留 WAITING_USER（可继续 hitlReply）
        service.cleanupExpiredWaitingUsers(60 * 60 * 1000L);
        assertEquals(WorkflowExecutionStatus.WAITING_USER, service.getExecution(executionId).getStatus());
    }

    // ========== 验证标准：WAITING_USER 状态下 execute()/resume() 被拒绝，仅 hitlReply 和 terminate 允许（AC-S03/AC-H01）==========

    @Test
    void waitingUser状态_resume应被拒绝() throws Exception {
        when(sequentialStrategy.execute(any(), any(), any(), any(), any(), any()))
                .thenThrow(new WorkflowHITLException(askUserHitlState("研究 Agent", "问题"), "等待", null));
        String executionId = service.execute(sequentialTemplate(), Map.of("topic", "AI"), mock(SseEmitter.class), null);
        waitForStatus(executionId, WorkflowExecutionStatus.WAITING_USER);

        // resume（失败暂停恢复）在 WAITING_USER 状态被拒绝——应走 hitlReply（AC-S03）
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.resume(executionId, mock(SseEmitter.class)));
        assertEquals(ErrorCode.WORKFLOW_NOT_RESUMABLE, ex.getErrorCode());
    }

    @Test
    void waitingUser状态_terminate应允许并清理快照() throws Exception {
        when(sequentialStrategy.execute(any(), any(), any(), any(), any(), any()))
                .thenThrow(new WorkflowHITLException(askUserHitlState("研究 Agent", "问题"), "等待", null));
        String executionId = service.execute(sequentialTemplate(), Map.of("topic", "AI"), mock(SseEmitter.class), null);
        waitForStatus(executionId, WorkflowExecutionStatus.WAITING_USER);

        // WAITING_USER 允许终止（AC-H01）——终止为终态，HITL 快照随之清理
        service.terminate(executionId);
        assertEquals(WorkflowExecutionStatus.TERMINATED, service.getExecution(executionId).getStatus());

        // 终止后 hitlReply 抛 5507（终态不可再 HITL 回复）
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.hitlReply(executionId, "回复", null, mock(SseEmitter.class)));
        assertEquals(ErrorCode.WORKFLOW_NOT_RESUMABLE, ex.getErrorCode());
    }
}
