package com.agentdemo.app.strategy;

import com.agentdemo.app.core.AgentDefinition;
import com.agentdemo.app.core.OrchestrationMode;
import com.agentdemo.app.core.WorkflowExecution;
import com.agentdemo.app.core.WorkflowTemplate;
import com.agentdemo.app.execution.AgentExecutor;
import com.agentdemo.app.template.ResearchAgent;
import com.agentdemo.observability.TraceCollector;
import com.agentdemo.observability.TraceContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工作流步骤 span 埋点测试（CR-001 Task-20，AC-N07/E05）
 * <p>
 * 业务含义：验证 SequentialExecutionStrategy 执行完成后经 executeOrSkip 统一收口上报
 * WorkflowStepEvent（含 executionId/agentName/status/duration/output，AC-N07）。
 * </p>
 */
class WorkflowStepTraceTest {

    @AfterEach
    void tearDown() {
        TraceContextHolder.clear();
    }

    @Test
    void sequentialExecution_reportsWorkflowStepEvent() throws Exception {
        AgentExecutor agentExecutor = mock(AgentExecutor.class);
        when(agentExecutor.executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any(), anyInt(), anyString()))
                .thenReturn("审查输出");
        TraceCollector collector = mock(TraceCollector.class);
        TraceContextHolder.set(new TraceContextHolder.TraceContext("wf-trace-1", "exec-1"));
        SequentialExecutionStrategy strategy = new SequentialExecutionStrategy(agentExecutor, collector);

        WorkflowTemplate template = WorkflowTemplate.builder()
                .id("tpl-seq")
                .name("内容审查")
                .mode(OrchestrationMode.SEQUENTIAL)
                .maxRetries(0)
                .parameters(List.of())
                .agents(List.of(AgentDefinition.builder().name("审查 Agent")
                        .toolIds(List.of()).interfaceClass(ResearchAgent.class).build()))
                .build();
        WorkflowExecution execution = new WorkflowExecution("exec-1", template.getId(), template.getName());
        execution.start();

        strategy.execute(template, Map.of("topic", "内容"), mock(SseEmitter.class),
                execution, null, new AtomicBoolean(false));

        ArgumentCaptor<TraceCollector.WorkflowStepEvent> captor =
                ArgumentCaptor.forClass(TraceCollector.WorkflowStepEvent.class);
        verify(collector).recordWorkflowStep(captor.capture());
        TraceCollector.WorkflowStepEvent e = captor.getValue();
        assertThat(e.executionId()).isEqualTo("exec-1");
        assertThat(e.agentName()).isEqualTo("审查 Agent");
        assertThat(e.index()).isZero();
        assertThat(e.status()).isEqualTo("COMPLETED");
        assertThat(e.output()).isEqualTo("审查输出");
        assertThat(e.durationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void failedStep_reportsFailedStepSpan_andStillThrows() throws Exception {
        // Task-20 验证标准：步骤失败/重试路径不丢事件（AC-N07）——失败步骤记录 FAILED 步骤 span，异常仍上抛
        AgentExecutor agentExecutor = mock(AgentExecutor.class);
        when(agentExecutor.executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any(), anyInt(), anyString()))
                .thenThrow(new RuntimeException("Agent 执行失败"));
        TraceCollector collector = mock(TraceCollector.class);
        TraceContextHolder.set(new TraceContextHolder.TraceContext("wf-trace-1", "exec-1"));
        SequentialExecutionStrategy strategy = new SequentialExecutionStrategy(agentExecutor, collector);

        WorkflowTemplate template = WorkflowTemplate.builder()
                .id("tpl-seq")
                .name("内容审查")
                .mode(OrchestrationMode.SEQUENTIAL)
                .maxRetries(0)
                .parameters(List.of())
                .agents(List.of(AgentDefinition.builder().name("审查 Agent")
                        .toolIds(List.of()).interfaceClass(ResearchAgent.class).build()))
                .build();
        WorkflowExecution execution = new WorkflowExecution("exec-1", template.getId(), template.getName());
        execution.start();

        // 异常仍上抛给调用方（AC-E05 不吞异常）
        assertThatThrownBy(() -> strategy.execute(template, Map.of("topic", "内容"),
                mock(SseEmitter.class), execution, null, new AtomicBoolean(false)))
                .isInstanceOf(Exception.class);

        ArgumentCaptor<TraceCollector.WorkflowStepEvent> captor =
                ArgumentCaptor.forClass(TraceCollector.WorkflowStepEvent.class);
        verify(collector).recordWorkflowStep(captor.capture());
        TraceCollector.WorkflowStepEvent e = captor.getValue();
        assertThat(e.executionId()).isEqualTo("exec-1");
        assertThat(e.agentName()).isEqualTo("审查 Agent");
        assertThat(e.status()).isEqualTo("FAILED");
        assertThat(e.durationMs()).isGreaterThanOrEqualTo(0);
        // 步骤状态未被篡改为 FAILED（前端执行历史零回归）
        assertThat(execution.getSteps().get(0).getStatus().name()).isEqualTo("RUNNING");
    }
}
