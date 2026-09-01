package com.agentdemo.app.service;

import com.agentdemo.app.core.AgentDefinition;
import com.agentdemo.app.core.OrchestrationMode;
import com.agentdemo.app.core.WorkflowExecution;
import com.agentdemo.app.core.WorkflowTemplate;
import com.agentdemo.app.execution.AgentExecutor;
import com.agentdemo.app.strategy.ParallelExecutionStrategy;
import com.agentdemo.app.strategy.WorkflowExecutionStrategy;
import com.agentdemo.app.template.ResearchAgent;
import com.agentdemo.observability.TraceCollector;
import com.agentdemo.observability.TraceContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工作流追踪上下文传播测试（CR-001 Task-19，AC-M03/E05）
 * <p>
 * 业务含义：验证 WorkflowExecutionService 三入口在异步线程设置 TraceContextHolder（executionId
 * 作聚合键）并开启根 span（AC-M03），执行结束 finally 清理（AC-E05）；ParallelExecutionStrategy
 * 并行线程池 Runnable 包装传播上下文至工作线程。
 * </p>
 */
class WorkflowExecutionTraceTest {

    @AfterEach
    void tearDown() {
        TraceContextHolder.clear();
    }

    private WorkflowTemplate seqTemplate() {
        return WorkflowTemplate.builder()
                .id("tpl-seq")
                .name("内容审查")
                .mode(OrchestrationMode.SEQUENTIAL)
                .maxRetries(0)
                .parameters(List.of())
                .agents(List.of(AgentDefinition.builder().name("审查 Agent")
                        .toolIds(List.of()).interfaceClass(ResearchAgent.class).build()))
                .build();
    }

    @Test
    void execute_setsTraceContextInAsyncThread_withExecutionIdAndStartsRootSpan() throws Exception {
        WorkflowExecutionStrategy strategy = mock(WorkflowExecutionStrategy.class);
        when(strategy.supportedMode()).thenReturn(OrchestrationMode.SEQUENTIAL);
        TraceCollector collector = mock(TraceCollector.class);
        WorkflowExecutionService service = new WorkflowExecutionService(List.of(strategy), collector);

        AtomicReference<TraceContextHolder.TraceContext> ctxInAsync = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        when(strategy.execute(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            ctxInAsync.set(TraceContextHolder.get());
            latch.countDown();
            return "完成";
        });

        String executionId = service.execute(seqTemplate(), Map.of(), new SseEmitter(), null);

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        // AC-M03：异步线程内 holder 有值，sessionId=executionId（thread 聚合键）
        assertThat(ctxInAsync.get()).isNotNull();
        assertThat(ctxInAsync.get().sessionId()).isEqualTo(executionId);
        // BUG 修复：根 span 经 resumeRequest(executionId) 启动（新执行无续接记录回退独立新 trace，
        // WAITING_USER 暂停时经 markHITLPause 记录续接点供 hitlReply 恢复轮共享同一 trace）
        verify(collector).resumeRequest(executionId);
        verify(collector).endRequest();
    }

    @Test
    void handleHITLPaused_marksTraceResumePoint_forHitlReplyContinuation() {
        // BUG 修复验证：工作流 HITL 暂停（WAITING_USER）标记 trace 续接点，
        // hitlReply 恢复轮经 resumeRequest(executionId) 以远程父上下文续接同一 trace
        TraceCollector collector = mock(TraceCollector.class);
        WorkflowExecutionStrategy strategy = mock(WorkflowExecutionStrategy.class);
        WorkflowExecutionService hitlService =
                new WorkflowExecutionService(List.of(strategy), collector);
        WorkflowExecution execution = new WorkflowExecution("exec-hitl-trace", "tpl-seq", "串行模板");
        execution.start();
        WorkflowHITLState hitlState = new WorkflowHITLState(WorkflowHITLState.MODE_ASK_USER,
                new WorkflowHITLState.AskUserData("text", "请确认输入？", List.of(), 0),
                new WorkflowHITLState.PendingStep(1, "研究 Agent", "输入", 0), List.of(), 0);
        WorkflowHITLException e = new WorkflowHITLException(hitlState, "Agent 等待用户输入", null);

        try (org.mockito.MockedStatic<com.agentdemo.app.execution.WorkflowEventPublisher> publisherMock =
                     org.mockito.Mockito.mockStatic(com.agentdemo.app.execution.WorkflowEventPublisher.class)) {
            hitlService.handleHITLPaused(new SseEmitter(), execution, e, seqTemplate(), Map.of(), null);

            assertThat(execution.getStatus()).isEqualTo(
                    com.agentdemo.app.core.WorkflowExecutionStatus.WAITING_USER);
            // WAITING_USER 暂停标记 trace 续接点（键=executionId，与 runAsyncWithTrace 的 resumeRequest 同键）
            verify(collector).markHITLPause("exec-hitl-trace");
        }
    }

    @Test
    void parallelStrategy_propagatesContextToWorkerThreads() throws Exception {
        // 主线程设置上下文（模拟 execute 包装后的线程），并行工作线程应能读取到
        TraceContextHolder.set(new TraceContextHolder.TraceContext("wf-trace-1", "exec-par"));
        AgentExecutor agentExecutor = mock(AgentExecutor.class);
        ParallelExecutionStrategy strategy = new ParallelExecutionStrategy(agentExecutor);

        AtomicReference<String> sessionInWorker = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        when(agentExecutor.executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any(), anyInt(), anyString()))
                .thenAnswer(inv -> {
                    sessionInWorker.set(TraceContextHolder.currentSessionId());
                    latch.countDown();
                    return "输出";
                });

        WorkflowTemplate template = WorkflowTemplate.builder()
                .id("tpl-par")
                .name("并行审查")
                .mode(OrchestrationMode.PARALLEL)
                .maxRetries(0)
                .parameters(List.of())
                .parallelGroups(List.of(
                        com.agentdemo.app.core.ParallelGroup.builder().name("组1")
                                .agents(List.of(AgentDefinition.builder().name("Agent1")
                                        .toolIds(List.of()).interfaceClass(ResearchAgent.class).build())).build()))
                .build();
        WorkflowExecution execution = new WorkflowExecution("exec-par", template.getId(), template.getName());
        execution.start();

        strategy.execute(template, Map.of(), mock(SseEmitter.class), execution, null, new AtomicBoolean(false));

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        // AC-M03：并行工作线程内上下文已传播（executionId 可见）
        assertThat(sessionInWorker.get()).isEqualTo("exec-par");
    }
}
