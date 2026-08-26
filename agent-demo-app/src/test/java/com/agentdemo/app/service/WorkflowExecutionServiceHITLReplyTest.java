package com.agentdemo.app.service;

import com.agentdemo.app.core.OrchestrationMode;
import com.agentdemo.app.core.WorkflowContext;
import com.agentdemo.app.core.WorkflowExecution;
import com.agentdemo.app.core.WorkflowExecutionStatus;
import com.agentdemo.app.core.WorkflowTemplate;
import com.agentdemo.app.execution.WorkflowEventPublisher;
import com.agentdemo.app.strategy.AbstractExecutionStrategy;
import com.agentdemo.app.strategy.WorkflowExecutionStrategy;
import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WorkflowExecutionService hitlReply 恢复流程测试（Task-08）
 * <p>
 * 验证标准来源：Task-08 验证标准
 * 关联 AC：AC-N03（用户回复后恢复执行）、AC-S01（检查点拒绝后终止）、AC-M01/AC-M02（上下文保持）、
 * AC-H02（恢复后 Agent 失败转 PAUSED 而非 WAITING_USER）
 * </p>
 * <p>
 * 业务含义：hitlReply 校验 WAITING_USER 状态 + HITL 快照存在后恢复执行——checkpoint 拒绝直接终止；
 * askUser/checkpoint 确认则状态回 RUNNING + 推送 workflow_resumed，将 HITL 恢复上下文
 * （HitlResume：快照 + 用户回复 + approved）写入 ctx 的恢复 key（hitl:{iteration}:{agentName}），
 * 重放策略时策略层据此以恢复方式执行暂停步（避免再次触发 HITL 死循环），后续步骤正常继续。
 * 恢复成功/终态化清理快照；恢复中再次 HITL 暂停则保留/更新快照（循环暂停-恢复）。
 * </p>
 */
class WorkflowExecutionServiceHITLReplyTest {

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

    /** askUser 模式 HITL 快照（消息列表含 ReAct 上下文，暂停步 agentIndex=1） */
    private WorkflowHITLState askUserHitlState() {
        WorkflowHITLState.AskUserData askUserData =
                new WorkflowHITLState.AskUserData("text", "请确认输入？", List.of(), 0);
        WorkflowHITLState.PendingStep pendingStep =
                new WorkflowHITLState.PendingStep(1, "研究 Agent", "输入", 0);
        return new WorkflowHITLState(WorkflowHITLState.MODE_ASK_USER, askUserData, pendingStep, List.of(), 0);
    }

    /** checkpoint 模式 HITL 快照（方法执行前暂停，无消息列表，暂停步 agentIndex=0） */
    private WorkflowHITLState checkpointHitlState() {
        WorkflowHITLState.AskUserData askUserData =
                new WorkflowHITLState.AskUserData("confirm", "确认执行？", List.of("确认", "取消"), 0);
        WorkflowHITLState.PendingStep pendingStep =
                new WorkflowHITLState.PendingStep(0, "分析 Agent", "输入", 0);
        return new WorkflowHITLState(WorkflowHITLState.MODE_CHECKPOINT, askUserData, pendingStep, null, 0);
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

    // ========== 验证标准：hitlReply 校验 WAITING_USER 状态 + HITL 快照存在，否则抛 WORKFLOW_NOT_RESUMABLE ==========

    @Test
    void hitlReply_notExist_shouldThrowNotFound() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.hitlReply("non-existent", "回复", null, mock(SseEmitter.class)));
        assertEquals(ErrorCode.WORKFLOW_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void hitlReply_notWaitingUser_shouldThrowNotResumable() throws Exception {
        // PAUSED（失败暂停）状态不允许 HITL 回复（与 resume 语义区分）
        when(sequentialStrategy.execute(any(), any(), any(), any(), any(), any()))
                .thenThrow(new WorkflowPausedException("研究 Agent", 0, "失败", null));
        String executionId = service.execute(sequentialTemplate(), Map.of("topic", "AI"), mock(SseEmitter.class), null);
        waitForStatus(executionId, WorkflowExecutionStatus.PAUSED);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.hitlReply(executionId, "回复", null, mock(SseEmitter.class)));
        assertEquals(ErrorCode.WORKFLOW_NOT_RESUMABLE, ex.getErrorCode());
    }

    // ========== 验证标准：askUser 模式恢复——重放策略 + workflow_resumed 事件 + 快照清理 ==========

    @Test
    void hitlReply_askUser_应恢复Running推送Resumed并清理快照() throws Exception {
        when(sequentialStrategy.execute(any(), any(), any(), any(), any(), any()))
                .thenThrow(new WorkflowHITLException(askUserHitlState(), "Agent 等待用户输入", null))
                .thenReturn("最终结果");
        String executionId = service.execute(sequentialTemplate(), Map.of("topic", "AI"), mock(SseEmitter.class), null);
        waitForStatus(executionId, WorkflowExecutionStatus.WAITING_USER);

        // workflow_resumed 在同步段推送（可被 MockedStatic 拦截），重放为异步
        try (MockedStatic<WorkflowEventPublisher> publisherMock = mockStatic(WorkflowEventPublisher.class)) {
            SseEmitter resumeEmitter = mock(SseEmitter.class);
            service.hitlReply(executionId, "用户回复", null, resumeEmitter);
            publisherMock.verify(() -> WorkflowEventPublisher.send(eq(resumeEmitter), eq("workflow_resumed"), any()));
        }

        waitForStatus(executionId, WorkflowExecutionStatus.COMPLETED);
        // 恢复成功清理快照 + 状态终态：再次 hitlReply 抛 5507（与 resume 清理语义一致）
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.hitlReply(executionId, "回复", null, mock(SseEmitter.class)));
        assertEquals(ErrorCode.WORKFLOW_NOT_RESUMABLE, ex.getErrorCode());
    }

    // ========== 验证标准：checkpoint 模式恢复——approved=true 执行 + 重放；approved=false 状态 TERMINATED ==========

    @Test
    void hitlReply_checkpointApproved_应重放策略并完成() throws Exception {
        when(sequentialStrategy.execute(any(), any(), any(), any(), any(), any()))
                .thenThrow(new WorkflowHITLException(checkpointHitlState(), "检查点等待确认", null))
                .thenReturn("确认后结果");
        String executionId = service.execute(sequentialTemplate(), Map.of("topic", "AI"), mock(SseEmitter.class), null);
        waitForStatus(executionId, WorkflowExecutionStatus.WAITING_USER);

        service.hitlReply(executionId, null, true, mock(SseEmitter.class));
        waitForStatus(executionId, WorkflowExecutionStatus.COMPLETED);
        assertEquals("确认后结果", service.getExecution(executionId).getFinalResult());
    }

    @Test
    void hitlReply_checkpointDenied_应终止并清理快照() throws Exception {
        when(sequentialStrategy.execute(any(), any(), any(), any(), any(), any()))
                .thenThrow(new WorkflowHITLException(checkpointHitlState(), "检查点等待确认", null));
        String executionId = service.execute(sequentialTemplate(), Map.of("topic", "AI"), mock(SseEmitter.class), null);
        waitForStatus(executionId, WorkflowExecutionStatus.WAITING_USER);

        // checkpoint 拒绝为同步路径：推送 workflow_failed(TERMINATED) + 状态置 TERMINATED（AC-S01）
        try (MockedStatic<WorkflowEventPublisher> publisherMock = mockStatic(WorkflowEventPublisher.class)) {
            SseEmitter emitter = mock(SseEmitter.class);
            service.hitlReply(executionId, null, false, emitter);
            publisherMock.verify(() -> WorkflowEventPublisher.send(eq(emitter), eq("workflow_failed"),
                    argThat(data -> data instanceof Map<?, ?> map
                            && WorkflowExecutionStatus.TERMINATED.name().equals(map.get("status")))));
            verify(emitter).complete();
        }
        assertEquals(WorkflowExecutionStatus.TERMINATED, service.getExecution(executionId).getStatus());
    }

    // ========== 验证标准：恢复中再次 HITL 暂停则保留/更新快照（循环暂停-恢复） ==========

    @Test
    void hitlReply_resumeAgainPause_应保留快照循环暂停恢复() throws Exception {
        when(sequentialStrategy.execute(any(), any(), any(), any(), any(), any()))
                .thenThrow(new WorkflowHITLException(askUserHitlState(), "第一次等待", null))
                .thenThrow(new WorkflowHITLException(askUserHitlState(), "第二次等待", null))
                .thenReturn("最终成功");
        String executionId = service.execute(sequentialTemplate(), Map.of("topic", "AI"), mock(SseEmitter.class), null);
        waitForStatus(executionId, WorkflowExecutionStatus.WAITING_USER);

        // 第一次恢复 -> 重放再次触发 HITL -> 再次 WAITING_USER（快照保留/更新）
        service.hitlReply(executionId, "回复1", null, mock(SseEmitter.class));
        waitForStatus(executionId, WorkflowExecutionStatus.WAITING_USER);

        // 第二次恢复 -> 重放成功 -> COMPLETED
        service.hitlReply(executionId, "回复2", null, mock(SseEmitter.class));
        waitForStatus(executionId, WorkflowExecutionStatus.COMPLETED);
    }

    // ========== 验证标准：HITL 恢复上下文（HitlResume）写入 ctx 恢复 key，供策略重放时识别暂停步 ==========

    @Test
    void hitlReply_应写HitlResume到ctx恢复key() throws Exception {
        when(sequentialStrategy.execute(any(), any(), any(), any(), any(), any()))
                .thenThrow(new WorkflowHITLException(askUserHitlState(), "Agent 等待用户输入", null))
                .thenReturn("最终结果");
        String executionId = service.execute(sequentialTemplate(), Map.of("topic", "AI"), mock(SseEmitter.class), null);
        waitForStatus(executionId, WorkflowExecutionStatus.WAITING_USER);

        service.hitlReply(executionId, "用户回复", null, mock(SseEmitter.class));

        // 同步段：HITL 恢复上下文写入 ctx 的 hitl:{iteration}:{agentName} 恢复 key（AC-M01/AC-M02）
        WorkflowContext ctx = service.getExecution(executionId).getContext();
        assertNotNull(ctx, "恢复时 ctx 应已挂载（HITL 暂停时策略已创建）");
        Object resume = ctx.read(AbstractExecutionStrategy.hitlResumeKey(0, "研究 Agent"));
        assertNotNull(resume, "ctx 应写入 hitl 恢复 key，供策略重放识别暂停步");
        assertEquals("用户回复", ((WorkflowHITLState.HitlResume) resume).getMessage());
        assertNotNull(((WorkflowHITLState.HitlResume) resume).getHitlState());
    }

    // ========== 验证标准：AC-M01 上下文保持——恢复后共享变量（WorkflowContext）完整保留 ==========

    @Test
    void hitlReply_恢复后_应保留共享上下文变量() throws Exception {
        // 业务含义：HITL 暂停-恢复复用同一 WorkflowContext（Agent 间共享容器），
        // 暂停前其他 Agent 写入的共享变量（如 lastOutput）在恢复重放后仍可读（AC-M01）。
        // mock 环境下 strategy 不挂载 ctx，此处模拟真实策略暂停时已挂载 ctx 并写入共享变量。
        when(sequentialStrategy.execute(any(), any(), any(), any(), any(), any()))
                .thenThrow(new WorkflowHITLException(askUserHitlState(), "Agent 等待用户输入", null))
                .thenReturn("最终结果");
        String executionId = service.execute(sequentialTemplate(), Map.of("topic", "AI"), mock(SseEmitter.class), null);
        waitForStatus(executionId, WorkflowExecutionStatus.WAITING_USER);

        // 模拟暂停前已写入的共享变量（前一 Agent 输出）
        WorkflowContext ctx = new WorkflowContext();
        ctx.write("lastOutput", "研究资料");
        service.getExecution(executionId).attachContext(ctx);

        service.hitlReply(executionId, "用户回复", null, mock(SseEmitter.class));
        waitForStatus(executionId, WorkflowExecutionStatus.COMPLETED);

        // 恢复后同一 ctx 实例，共享变量完整保留（AC-M01）
        WorkflowContext after = service.getExecution(executionId).getContext();
        assertEquals(ctx, after, "恢复应复用同一 WorkflowContext 实例");
        assertEquals("研究资料", after.read("lastOutput"), "共享变量应在暂停-恢复后保留");
    }

    // ========== 验证标准：AC-H02 恢复后 Agent 失败——转为 PAUSED 而非 WAITING_USER ==========

    @Test
    void hitlReply_恢复后Agent重试耗尽_应转为Paused而非WaitingUser() throws Exception {
        // 业务含义：用户回复后恢复执行，重放过程中 Agent 重试耗尽失败——语义为"失败暂停"（PAUSED，
        // 走 resume 恢复），而非再次进入"等待用户"（WAITING_USER，走 hitlReply 恢复）（AC-H02）。
        when(sequentialStrategy.execute(any(), any(), any(), any(), any(), any()))
                .thenThrow(new WorkflowHITLException(askUserHitlState(), "Agent 等待用户输入", null))
                .thenThrow(new WorkflowPausedException("研究 Agent", 1, "重试耗尽", null));
        String executionId = service.execute(sequentialTemplate(), Map.of("topic", "AI"), mock(SseEmitter.class), null);
        waitForStatus(executionId, WorkflowExecutionStatus.WAITING_USER);

        // 恢复重放抛 WorkflowPausedException -> handlePaused -> 状态 PAUSED（非 WAITING_USER）
        service.hitlReply(executionId, "用户回复", null, mock(SseEmitter.class));
        waitForStatus(executionId, WorkflowExecutionStatus.PAUSED);
        assertEquals(WorkflowExecutionStatus.PAUSED, service.getExecution(executionId).getStatus());

        // PAUSED 状态下 hitlReply 被拒绝（应走 resume 恢复失败暂停，AC-S03）
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.hitlReply(executionId, "回复", null, mock(SseEmitter.class)));
        assertEquals(ErrorCode.WORKFLOW_NOT_RESUMABLE, ex.getErrorCode());
    }
}
