package com.agentdemo.app.service;

import com.agentdemo.app.core.OrchestrationMode;
import com.agentdemo.app.core.WorkflowExecution;
import com.agentdemo.app.core.WorkflowExecutionStatus;
import com.agentdemo.app.core.WorkflowTemplate;
import com.agentdemo.app.execution.WorkflowEventPublisher;
import com.agentdemo.app.strategy.WorkflowExecutionStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WorkflowExecutionService HITL 暂停处理测试（Task-07）
 * <p>
 * 验证标准来源：Task-07 验证标准
 * 关联 AC：AC-N01（Agent 主动追问）、AC-N02（模板预设检查点）、AC-S03（WAITING_USER 操作保护）
 * </p>
 * <p>
 * 业务含义：协调层捕获 WorkflowHITLException（在 WorkflowPausedException 之前，子类优先）后，
 * handleHITLPaused 保存 HITL 快照（hitlState）到 resumableStates，推送 ask_user + workflow_waiting
 * 事件，关闭当前 SSE 流并将状态置 WAITING_USER（区别于失败暂停 PAUSED）。与 handlePaused 完全分离，
 * 互不干扰（PAUSED 流程零回归）。
 * </p>
 */
class WorkflowExecutionServiceHITLTest {

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

    /** askUser 模式 HITL 快照（消息列表含 ReAct 上下文） */
    private WorkflowHITLState askUserHitlState() {
        WorkflowHITLState.AskUserData askUserData =
                new WorkflowHITLState.AskUserData("text", "请确认输入？", List.of(), 0);
        WorkflowHITLState.PendingStep pendingStep =
                new WorkflowHITLState.PendingStep(1, "研究 Agent", "输入", 0);
        return new WorkflowHITLState(WorkflowHITLState.MODE_ASK_USER, askUserData, pendingStep, List.of(), 0);
    }

    /** checkpoint 模式 HITL 快照（方法执行前暂停，无消息列表） */
    private WorkflowHITLState checkpointHitlState() {
        WorkflowHITLState.AskUserData askUserData =
                new WorkflowHITLState.AskUserData("confirm", "确认执行？", List.of("确认", "取消"), 0);
        WorkflowHITLState.PendingStep pendingStep =
                new WorkflowHITLState.PendingStep(0, "分析 Agent", "输入", 0);
        return new WorkflowHITLState(WorkflowHITLState.MODE_CHECKPOINT, askUserData, pendingStep, null, 0);
    }

    /** toolConfirm 模式 HITL 快照（工具四要素，Task-15） */
    private WorkflowHITLState toolConfirmHitlState() {
        WorkflowHITLState.ToolConfirmData toolConfirmData =
                new WorkflowHITLState.ToolConfirmData("call-http", "httpGet", "发起 HTTP GET 请求", "{\"url\":\"https://example.com\"}");
        WorkflowHITLState.PendingStep pendingStep =
                new WorkflowHITLState.PendingStep(1, "研究 Agent", "输入", 0);
        return new WorkflowHITLState(WorkflowHITLState.MODE_TOOL_CONFIRM, toolConfirmData, pendingStep, List.of(), 0);
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

    // ========== 验证标准：handleHITLPaused 推送 ask_user + workflow_waiting，状态置 WAITING_USER ==========

    @Test
    void handleHITLPaused_askUser模式_应推送事件并置WaitingUser() {
        try (MockedStatic<WorkflowEventPublisher> publisherMock = mockStatic(WorkflowEventPublisher.class)) {
            WorkflowExecution execution = new WorkflowExecution("exec-hitl-1", "tpl-seq", "串行模板");
            execution.start();
            WorkflowHITLException e = new WorkflowHITLException(askUserHitlState(), "Agent 等待用户输入", null);
            SseEmitter emitter = mock(SseEmitter.class);

            service.handleHITLPaused(emitter, execution, e, sequentialTemplate(), Map.of("topic", "AI"), null);

            publisherMock.verify(() -> WorkflowEventPublisher.send(eq(emitter), eq("ask_user"), any()));
            publisherMock.verify(() -> WorkflowEventPublisher.send(eq(emitter), eq("workflow_waiting"), any()));
            verify(emitter).complete();
            assertEquals(WorkflowExecutionStatus.WAITING_USER, execution.getStatus());
        }
    }

    // ========== 验证标准：ask_user 事件携带完整提问数据（type/question/options） ==========

    @Test
    void handleHITLPaused_askUser事件_应携带提问数据() {
        try (MockedStatic<WorkflowEventPublisher> publisherMock = mockStatic(WorkflowEventPublisher.class)) {
            WorkflowExecution execution = new WorkflowExecution("exec-hitl-2", "tpl-seq", "串行模板");
            execution.start();
            WorkflowHITLException e = new WorkflowHITLException(askUserHitlState(), "Agent 等待用户输入", null);
            SseEmitter emitter = mock(SseEmitter.class);

            service.handleHITLPaused(emitter, execution, e, sequentialTemplate(), Map.of("topic", "AI"), null);

            publisherMock.verify(() -> WorkflowEventPublisher.send(eq(emitter), eq("ask_user"),
                    argThat(data -> {
                        if (!(data instanceof Map<?, ?> map)) {
                            return false;
                        }
                        return "text".equals(map.get("type"))
                                && "请确认输入？".equals(map.get("question"))
                                && "研究 Agent".equals(map.get("agentName"))
                                && Integer.valueOf(1).equals(map.get("agentIndex"));
                    })));
        }
    }

    // ========== 验证标准：checkpoint 模式同样进入 WAITING_USER（无消息列表场景） ==========

    @Test
    void handleHITLPaused_checkpoint模式_应推送事件并置WaitingUser() {
        try (MockedStatic<WorkflowEventPublisher> publisherMock = mockStatic(WorkflowEventPublisher.class)) {
            WorkflowExecution execution = new WorkflowExecution("exec-hitl-3", "tpl-seq", "串行模板");
            execution.start();
            WorkflowHITLException e = new WorkflowHITLException(checkpointHitlState(), "检查点等待确认", null);
            SseEmitter emitter = mock(SseEmitter.class);

            service.handleHITLPaused(emitter, execution, e, sequentialTemplate(), Map.of("topic", "AI"), null);

            // checkpoint 模式事件带 hitlMode=checkpoint，confirm 类型提问
            publisherMock.verify(() -> WorkflowEventPublisher.send(eq(emitter), eq("workflow_waiting"),
                    argThat(data -> data instanceof Map<?, ?> map
                            && "checkpoint".equals(map.get("hitlMode")))));
            publisherMock.verify(() -> WorkflowEventPublisher.send(eq(emitter), eq("ask_user"),
                    argThat(data -> data instanceof Map<?, ?> map
                            && "confirm".equals(map.get("type")))));
            assertEquals(WorkflowExecutionStatus.WAITING_USER, execution.getStatus());
        }
    }

    // ========== 验证标准：execute 异常链捕获 WorkflowHITLException -> WAITING_USER（非 PAUSED/FAILED） ==========

    @Test
    void execute_hitlException_应置WaitingUser而非Paused() throws Exception {
        WorkflowHITLException hitl = new WorkflowHITLException(askUserHitlState(), "Agent 等待用户输入", null);
        when(sequentialStrategy.execute(any(), any(), any(), any(), any(), any())).thenThrow(hitl);

        SseEmitter emitter = mock(SseEmitter.class);
        String executionId = service.execute(sequentialTemplate(), Map.of("topic", "AI"), emitter, null);

        waitForStatus(executionId, WorkflowExecutionStatus.WAITING_USER);
        assertEquals(WorkflowExecutionStatus.WAITING_USER, service.getExecution(executionId).getStatus());
    }

    // ========== 验证标准：handleHITLPaused 与 handlePaused 互不干扰（PAUSED 流程零回归） ==========

    @Test
    void execute_pausedException_应仍置Paused() throws Exception {
        WorkflowPausedException paused = new WorkflowPausedException("研究 Agent", 1, "Agent 执行失败", null);
        when(sequentialStrategy.execute(any(), any(), any(), any(), any(), any())).thenThrow(paused);

        SseEmitter emitter = mock(SseEmitter.class);
        String executionId = service.execute(sequentialTemplate(), Map.of("topic", "AI"), emitter, null);

        // 失败暂停仍走 PAUSED，不被 HITL 分支劫持
        waitForStatus(executionId, WorkflowExecutionStatus.PAUSED);
        assertEquals(WorkflowExecutionStatus.PAUSED, service.getExecution(executionId).getStatus());
    }

    // ========== Task-15 验证标准：toolConfirm 暂停推送 tool_confirm + workflow_waiting 双事件 ==========

    @Test
    void handleHITLPaused_toolConfirm模式_应推送toolConfirm与waiting双事件() {
        try (MockedStatic<WorkflowEventPublisher> publisherMock = mockStatic(WorkflowEventPublisher.class)) {
            WorkflowExecution execution = new WorkflowExecution("exec-tc-1", "tpl-seq", "串行模板");
            execution.start();
            WorkflowHITLException e = new WorkflowHITLException(toolConfirmHitlState(), "Agent 等待工具确认", null);
            SseEmitter emitter = mock(SseEmitter.class);

            service.handleHITLPaused(emitter, execution, e, sequentialTemplate(), Map.of("topic", "AI"), null);

            publisherMock.verify(() -> WorkflowEventPublisher.send(eq(emitter), eq("tool_confirm"), any()));
            publisherMock.verify(() -> WorkflowEventPublisher.send(eq(emitter), eq("workflow_waiting"), any()));
            verify(emitter).complete();
            assertEquals(WorkflowExecutionStatus.WAITING_USER, execution.getStatus());
        }
    }

    // ========== Task-15 验证标准：tool_confirm 事件携带工具四要素（AC-H01/AC-H02） ==========

    @Test
    void handleHITLPaused_toolConfirm事件_应携带工具四要素() {
        try (MockedStatic<WorkflowEventPublisher> publisherMock = mockStatic(WorkflowEventPublisher.class)) {
            WorkflowExecution execution = new WorkflowExecution("exec-tc-2", "tpl-seq", "串行模板");
            execution.start();
            WorkflowHITLException e = new WorkflowHITLException(toolConfirmHitlState(), "Agent 等待工具确认", null);
            SseEmitter emitter = mock(SseEmitter.class);

            service.handleHITLPaused(emitter, execution, e, sequentialTemplate(), Map.of("topic", "AI"), null);

            publisherMock.verify(() -> WorkflowEventPublisher.send(eq(emitter), eq("tool_confirm"),
                    argThat(data -> {
                        if (!(data instanceof Map<?, ?> map)) {
                            return false;
                        }
                        return "httpGet".equals(map.get("toolName"))
                                && "发起 HTTP GET 请求".equals(map.get("toolDescription"))
                                && "{\"url\":\"https://example.com\"}".equals(map.get("arguments"))
                                && "研究 Agent".equals(map.get("agentName"))
                                && Integer.valueOf(1).equals(map.get("agentIndex"));
                    })));
            publisherMock.verify(() -> WorkflowEventPublisher.send(eq(emitter), eq("workflow_waiting"),
                    argThat(data -> data instanceof Map<?, ?> map
                            && "toolConfirm".equals(map.get("hitlMode")))));
        }
    }

    // ========== Task-15 验证标准：hitlReply toolConfirm 批准/拒绝均恢复重放（拒绝不 terminate，决策 7） ==========

    @Test
    void hitlReply_toolConfirm批准_应恢复重放不终止() throws Exception {
        // 业务含义：execute 首次执行抛 toolConfirm 暂停（进 WAITING_USER + 快照就绪），
        // hitlReply 重放时成功返回——批准不 terminate（区别于 checkpoint 拒绝，决策 7）
        AtomicInteger call = new AtomicInteger();
        when(sequentialStrategy.execute(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            if (call.incrementAndGet() == 1) {
                throw new WorkflowHITLException(toolConfirmHitlState(), "Agent 等待工具确认", null);
            }
            return "HTTP 200 OK";
        });

        SseEmitter emitter = mock(SseEmitter.class);
        String executionId = service.execute(sequentialTemplate(), Map.of("topic", "AI"), emitter, null);
        waitForStatus(executionId, WorkflowExecutionStatus.WAITING_USER);

        SseEmitter resumeEmitter = mock(SseEmitter.class);
        service.hitlReply(executionId, null, true, resumeEmitter);
        waitForStatus(executionId, WorkflowExecutionStatus.COMPLETED);
        assertEquals("HTTP 200 OK", service.getExecution(executionId).getFinalResult());
    }

    @Test
    void hitlReply_toolConfirm拒绝_应恢复重放不终止() throws Exception {
        // 业务含义：toolConfirm 拒绝 ≠ 终止（区别于 checkpoint 拒绝终止，决策 7）——
        // 拒绝语义传达到 executeHitlResume（approved=false），工作流续跑完成
        AtomicInteger call = new AtomicInteger();
        when(sequentialStrategy.execute(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            if (call.incrementAndGet() == 1) {
                throw new WorkflowHITLException(toolConfirmHitlState(), "Agent 等待工具确认", null);
            }
            return "换用其他方式";
        });

        SseEmitter emitter = mock(SseEmitter.class);
        String executionId = service.execute(sequentialTemplate(), Map.of("topic", "AI"), emitter, null);
        waitForStatus(executionId, WorkflowExecutionStatus.WAITING_USER);

        SseEmitter resumeEmitter = mock(SseEmitter.class);
        service.hitlReply(executionId, null, false, resumeEmitter);
        waitForStatus(executionId, WorkflowExecutionStatus.COMPLETED);
        assertEquals("换用其他方式", service.getExecution(executionId).getFinalResult());
    }
}
