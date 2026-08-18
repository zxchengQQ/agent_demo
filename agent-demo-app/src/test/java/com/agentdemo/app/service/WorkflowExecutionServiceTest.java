package com.agentdemo.app.service;

import com.agentdemo.app.core.OrchestrationMode;
import com.agentdemo.app.core.WorkflowExecution;
import com.agentdemo.app.core.WorkflowExecutionStatus;
import com.agentdemo.app.core.WorkflowTemplate;
import com.agentdemo.app.execution.WorkflowEventPublisher;
import com.agentdemo.app.strategy.WorkflowExecutionStrategy;
import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工作流执行协调层测试（P2 Task-10 重构）
 * <p>
 * 业务含义：验证 WorkflowExecutionService 作为薄协调层的策略分发、
 * 执行实例生命周期管理、异常处理与并发隔离（AC-011/AC-020/AC-022/AC-023）。
 * </p>
 */
class WorkflowExecutionServiceTest {

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

    private WorkflowTemplate loopTemplate() {
        return WorkflowTemplate.builder()
                .id("tpl-loop")
                .name("循环模板")
                .mode(OrchestrationMode.LOOP)
                .maxRetries(0)
                .agents(List.of())
                .parameters(List.of())
                .build();
    }

    /** 等待异步执行完成（策略 mock 立即返回，轮询 status） */
    private void awaitTerminal(WorkflowExecution execution) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            WorkflowExecutionStatus status = service.getExecution(execution.getExecutionId()).getStatus();
            if (status != WorkflowExecutionStatus.RUNNING) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Test
    void execute_shouldCreateExecutionAndDelegateToStrategy() throws Exception {
        when(sequentialStrategy.execute(any(), any(), any(), any(), any(), any())).thenReturn("最终结果");

        SseEmitter emitter = mock(SseEmitter.class);
        String executionId = service.execute(sequentialTemplate(), Map.of("topic", "AI"), emitter, null);

        assertNotNull(executionId);
        WorkflowExecution execution = service.getExecution(executionId);
        assertEquals(WorkflowExecutionStatus.RUNNING, execution.getStatus());
        // mode 记录到执行实例
        assertEquals(OrchestrationMode.SEQUENTIAL, execution.getMode());

        awaitTerminal(execution);
        // 策略被分发调用，执行完成
        verify(sequentialStrategy).execute(any(), any(), any(), any(), any(), any());
        assertEquals(WorkflowExecutionStatus.COMPLETED, service.getExecution(executionId).getStatus());
    }

    @Test
    void execute_strategyNotFound_shouldThrowModeNotSupported() {
        // LOOP 模板但无对应策略
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.execute(loopTemplate(), Map.of(), mock(SseEmitter.class), null));
        assertEquals(ErrorCode.WORKFLOW_MODE_NOT_SUPPORTED, ex.getErrorCode());
    }

    @Test
    void execute_strategyThrowsCancelled_shouldSetTerminated() throws Exception {
        when(sequentialStrategy.execute(any(), any(), any(), any(), any(), any()))
                .thenThrow(new WorkflowCancelledException("用户主动终止"));

        SseEmitter emitter = mock(SseEmitter.class);
        String executionId = service.execute(sequentialTemplate(), Map.of("topic", "AI"), emitter, null);
        WorkflowExecution execution = service.getExecution(executionId);
        awaitTerminal(execution);
        assertEquals(WorkflowExecutionStatus.TERMINATED, service.getExecution(executionId).getStatus());
    }

    @Test
    void execute_strategyThrowsBusiness_shouldSetFailed() throws Exception {
        when(sequentialStrategy.execute(any(), any(), any(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.WORKFLOW_EXECUTION_FAILED, "Agent 执行失败"));

        SseEmitter emitter = mock(SseEmitter.class);
        String executionId = service.execute(sequentialTemplate(), Map.of("topic", "AI"), emitter, null);
        WorkflowExecution execution = service.getExecution(executionId);
        awaitTerminal(execution);
        assertEquals(WorkflowExecutionStatus.FAILED, service.getExecution(executionId).getStatus());
    }

    @Test
    void getExecution_shouldReturnNullWhenNotExist() {
        assertNull(service.getExecution("non-existent"));
    }

    @Test
    void terminate_shouldSetTerminatedStatus() {
        // 挂起策略执行（sleep 5s），保持 RUNNING 状态，验证执行中可终止（AC-022）
        when(sequentialStrategy.execute(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "结果";
        });

        SseEmitter emitter = mock(SseEmitter.class);
        String executionId = service.execute(sequentialTemplate(), Map.of("topic", "AI"), emitter, null);
        assertEquals(WorkflowExecutionStatus.RUNNING, service.getExecution(executionId).getStatus());
        service.terminate(executionId);
        assertEquals(WorkflowExecutionStatus.TERMINATED, service.getExecution(executionId).getStatus());
    }

    @Test
    void terminate_shouldThrowWhenNotExist() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.terminate("non-existent"));
        assertEquals(ErrorCode.WORKFLOW_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void terminate_shouldThrowAlreadyTerminatedWhenCompleted() throws Exception {
        when(sequentialStrategy.execute(any(), any(), any(), any(), any(), any())).thenReturn("完成");

        SseEmitter emitter = mock(SseEmitter.class);
        String executionId = service.execute(sequentialTemplate(), Map.of("topic", "AI"), emitter, null);
        WorkflowExecution execution = service.getExecution(executionId);
        awaitTerminal(execution);
        assertEquals(WorkflowExecutionStatus.COMPLETED, service.getExecution(executionId).getStatus());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.terminate(executionId));
        assertEquals(ErrorCode.WORKFLOW_ALREADY_TERMINATED, ex.getErrorCode());
    }

    @Test
    void execute_concurrent_shouldGenerateDistinctExecutionIds() {
        when(sequentialStrategy.execute(any(), any(), any(), any(), any(), any())).thenReturn("A", "B");

        String id1 = service.execute(sequentialTemplate(), Map.of("topic", "A"), mock(SseEmitter.class), null);
        String id2 = service.execute(sequentialTemplate(), Map.of("topic", "B"), mock(SseEmitter.class), null);
        assertNotEquals(id1, id2);
        assertNotNull(service.getExecution(id1));
        assertNotNull(service.getExecution(id2));
    }

    @Test
    void listExecutions_shouldReturnAllInReverseStartTime() throws Exception {
        when(sequentialStrategy.execute(any(), any(), any(), any(), any(), any())).thenReturn("A", "B");

        String id1 = service.execute(sequentialTemplate(), Map.of("topic", "A"), mock(SseEmitter.class), null);
        awaitTerminal(service.getExecution(id1));
        String id2 = service.execute(sequentialTemplate(), Map.of("topic", "B"), mock(SseEmitter.class), null);
        awaitTerminal(service.getExecution(id2));

        List<WorkflowExecution> list = service.listExecutions();
        assertEquals(2, list.size());
        // 按开始时间倒序：后执行的 id2 在前
        assertEquals(id2, list.get(0).getExecutionId());
        assertEquals(id1, list.get(1).getExecutionId());
        // 执行实例含 mode 字段（AC-027）
        assertEquals(OrchestrationMode.SEQUENTIAL, list.get(0).getMode());
    }

    @Test
    void listExecutions_empty_shouldReturnEmptyList() {
        assertTrue(service.listExecutions().isEmpty());
    }

    // ===== P3 新增：暂停处理（Task-06，AC-016）=====

    @Test
    void execute_strategyThrowsPaused_shouldSetPausedNotFailed() throws Exception {
        when(sequentialStrategy.execute(any(), any(), any(), any(), any(), any()))
                .thenThrow(new WorkflowPausedException("研究 Agent", 1, "Agent 执行失败: 研究 Agent: 超时", null));

        SseEmitter emitter = mock(SseEmitter.class);
        String executionId = service.execute(sequentialTemplate(), Map.of("topic", "AI"), emitter, null);
        WorkflowExecution execution = service.getExecution(executionId);
        awaitTerminal(execution);

        // 暂停而非失败（AC-016），endTime 为 null（非终态）
        assertEquals(WorkflowExecutionStatus.PAUSED, service.getExecution(executionId).getStatus());
        assertNull(service.getExecution(executionId).getEndTime());
        // 原 SSE 流已关闭
        verify(emitter).complete();
    }

    @Test
    void execute_strategyThrowsPaused_shouldPushWorkflowPausedEvent() {
        // 说明：handlePaused 实际由 ForkJoinPool 异步线程调用，而 MockedStatic 仅拦截
        // 创建线程的静态调用（thread-local），故此处在测试线程直接调用包可见的
        // handlePaused 验证事件契约；异步分流路径由上方
        // execute_strategyThrowsPaused_shouldSetPausedNotFailed 覆盖。
        try (MockedStatic<WorkflowEventPublisher> publisherMock = mockStatic(WorkflowEventPublisher.class)) {
            WorkflowExecution execution = new WorkflowExecution("exec-paused-1", "tpl-seq", "串行模板");
            execution.start();
            WorkflowPausedException cause =
                    new WorkflowPausedException("研究 Agent", 1, "Agent 执行失败: 研究 Agent: 超时", null);
            SseEmitter emitter = mock(SseEmitter.class);

            service.handlePaused(emitter, execution, cause, sequentialTemplate(), Map.of("topic", "AI"), null);

            // workflow_paused 事件 data 完整（AC-016）
            publisherMock.verify(() -> WorkflowEventPublisher.send(eq(emitter), eq("workflow_paused"),
                    argThat(data -> {
                        if (!(data instanceof Map<?, ?> map)) {
                            return false;
                        }
                        return "exec-paused-1".equals(map.get("executionId"))
                                && "研究 Agent".equals(map.get("failedAgent"))
                                && Integer.valueOf(1).equals(map.get("failedIndex"))
                                && Boolean.TRUE.equals(map.get("resumable"))
                                && map.get("error") != null;
                    })));
            // 原 SSE 流关闭，状态置 PAUSED
            verify(emitter).complete();
            assertEquals(WorkflowExecutionStatus.PAUSED, execution.getStatus());
        }
    }

    @Test
    void execute_nonPausedFailure_shouldStillFail() throws Exception {
        // 分流正确：普通 RuntimeException 仍走 handleFailure -> FAILED
        when(sequentialStrategy.execute(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("非暂停类失败"));

        SseEmitter emitter = mock(SseEmitter.class);
        String executionId = service.execute(sequentialTemplate(), Map.of("topic", "AI"), emitter, null);
        awaitTerminal(service.getExecution(executionId));
        assertEquals(WorkflowExecutionStatus.FAILED, service.getExecution(executionId).getStatus());
    }

    // ===== P3 新增：resume 恢复执行（Task-07，AC-017）=====

    @Test
    void resume_notExist_shouldThrowNotFound() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.resume("non-existent", mock(SseEmitter.class)));
        assertEquals(ErrorCode.WORKFLOW_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void resume_completedStatus_shouldThrowNotResumable() throws Exception {
        when(sequentialStrategy.execute(any(), any(), any(), any(), any(), any())).thenReturn("完成");

        String executionId = service.execute(sequentialTemplate(), Map.of("topic", "AI"), mock(SseEmitter.class), null);
        awaitTerminal(service.getExecution(executionId));
        assertEquals(WorkflowExecutionStatus.COMPLETED, service.getExecution(executionId).getStatus());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.resume(executionId, mock(SseEmitter.class)));
        assertEquals(ErrorCode.WORKFLOW_NOT_RESUMABLE, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("COMPLETED"), "message 应含当前状态，实际: " + ex.getMessage());
    }

    @Test
    void resume_pausedWithSnapshot_shouldReplayWithSnapshotArgs() throws Exception {
        // 首次执行暂停
        when(sequentialStrategy.execute(any(), any(), any(), any(), any(), any()))
                .thenThrow(new WorkflowPausedException("研究 Agent", 0, "失败", null));
        String executionId = service.execute(sequentialTemplate(), Map.of("topic", "原始任务"), mock(SseEmitter.class), "model-x");
        awaitTerminal(service.getExecution(executionId));
        assertEquals(WorkflowExecutionStatus.PAUSED, service.getExecution(executionId).getStatus());

        // 恢复：策略以快照参数重放，最终成功
        // 注意：已有 thenThrow stub 时必须用 doReturn 形式重新 stubbing（when(mock.exec()) 会先触发旧 throw）
        doReturn("恢复结果").when(sequentialStrategy).execute(any(), any(), any(), any(), any(), any());
        SseEmitter resumeEmitter = mock(SseEmitter.class);
        service.resume(executionId, resumeEmitter);
        awaitTerminal(service.getExecution(executionId));

        assertEquals(WorkflowExecutionStatus.COMPLETED, service.getExecution(executionId).getStatus());
        // 重放调用参数 = 快照中的 template/params/modelId
        verify(sequentialStrategy, times(2)).execute(any(), eq(Map.of("topic", "原始任务")), any(), any(), eq("model-x"), any());
    }

    @Test
    void resume_success_shouldCleanupSnapshot() throws Exception {
        when(sequentialStrategy.execute(any(), any(), any(), any(), any(), any()))
                .thenThrow(new WorkflowPausedException("研究 Agent", 0, "失败", null))
                .thenReturn("恢复结果");
        String executionId = service.execute(sequentialTemplate(), Map.of("topic", "AI"), mock(SseEmitter.class), null);
        awaitTerminal(service.getExecution(executionId));

        service.resume(executionId, mock(SseEmitter.class));
        awaitTerminal(service.getExecution(executionId));
        assertEquals(WorkflowExecutionStatus.COMPLETED, service.getExecution(executionId).getStatus());

        // 快照已清理：再次 resume 因状态 COMPLETED 抛 5507
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.resume(executionId, mock(SseEmitter.class)));
        assertEquals(ErrorCode.WORKFLOW_NOT_RESUMABLE, ex.getErrorCode());
    }

    @Test
    void resume_pauseAgain_shouldRePauseAndKeepSnapshot() throws Exception {
        // 循环暂停-恢复：恢复后再失败再暂停（AC 5.1.3）
        when(sequentialStrategy.execute(any(), any(), any(), any(), any(), any()))
                .thenThrow(new WorkflowPausedException("研究 Agent", 0, "第一次失败", null))
                .thenThrow(new WorkflowPausedException("分析 Agent", 1, "第二次失败", null))
                .thenReturn("最终成功");
        String executionId = service.execute(sequentialTemplate(), Map.of("topic", "AI"), mock(SseEmitter.class), null);
        awaitTerminal(service.getExecution(executionId));
        assertEquals(WorkflowExecutionStatus.PAUSED, service.getExecution(executionId).getStatus());

        // 第一次恢复 -> 再次暂停
        service.resume(executionId, mock(SseEmitter.class));
        awaitTerminal(service.getExecution(executionId));
        assertEquals(WorkflowExecutionStatus.PAUSED, service.getExecution(executionId).getStatus());

        // 快照仍在：第二次恢复成功
        service.resume(executionId, mock(SseEmitter.class));
        awaitTerminal(service.getExecution(executionId));
        assertEquals(WorkflowExecutionStatus.COMPLETED, service.getExecution(executionId).getStatus());
    }

    @Test
    void terminate_pausedExecution_shouldTerminateAndCleanupSnapshot() throws Exception {
        when(sequentialStrategy.execute(any(), any(), any(), any(), any(), any()))
                .thenThrow(new WorkflowPausedException("研究 Agent", 0, "失败", null));
        String executionId = service.execute(sequentialTemplate(), Map.of("topic", "AI"), mock(SseEmitter.class), null);
        awaitTerminal(service.getExecution(executionId));
        assertEquals(WorkflowExecutionStatus.PAUSED, service.getExecution(executionId).getStatus());

        service.terminate(executionId);
        assertEquals(WorkflowExecutionStatus.TERMINATED, service.getExecution(executionId).getStatus());

        // 快照已清理：终止后 resume 抛 5507（非 PAUSED 状态）
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.resume(executionId, mock(SseEmitter.class)));
        assertEquals(ErrorCode.WORKFLOW_NOT_RESUMABLE, ex.getErrorCode());
    }

    @Test
    void resume_executionContext_shouldBeAvailableToStrategy() throws Exception {
        // 恢复执行时策略可从 execution.getContext() 拿到暂停前状态（ctx 挂载不丢失）
        when(sequentialStrategy.execute(any(), any(), any(), any(), any(), any()))
                .thenThrow(new WorkflowPausedException("研究 Agent", 0, "失败", null));
        String executionId = service.execute(sequentialTemplate(), Map.of("topic", "AI"), mock(SseEmitter.class), null);
        awaitTerminal(service.getExecution(executionId));

        // 模拟策略首行挂载 ctx（真实策略在暂停前已挂载）
        service.getExecution(executionId).attachContext(new com.agentdemo.app.core.WorkflowContext());

        // doAnswer 形式重新 stubbing：安全替换旧 thenThrow，同时捕获策略收到的 execution
        AtomicReference<WorkflowExecution> captured = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(inv -> {
            captured.set(inv.getArgument(3));
            return "恢复结果";
        }).when(sequentialStrategy).execute(any(), any(), any(), any(), any(), any());

        service.resume(executionId, mock(SseEmitter.class));
        awaitTerminal(service.getExecution(executionId));
        assertNotNull(captured.get().getContext(), "恢复时策略收到的 execution 应带 ctx");
    }
}
