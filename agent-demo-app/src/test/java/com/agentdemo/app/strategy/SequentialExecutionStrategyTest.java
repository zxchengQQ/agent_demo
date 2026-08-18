package com.agentdemo.app.strategy;

import com.agentdemo.app.core.AgentDefinition;
import com.agentdemo.app.core.OrchestrationMode;
import com.agentdemo.app.core.WorkflowExecution;
import com.agentdemo.app.core.WorkflowTemplate;
import com.agentdemo.app.execution.AgentExecutor;
import com.agentdemo.app.service.WorkflowCancelledException;
import com.agentdemo.app.template.AnalysisAgent;
import com.agentdemo.app.template.ResearchAgent;
import com.agentdemo.app.template.SummaryAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 串行编排策略测试（P2 Task-06）
 * <p>
 * 业务含义：验证从 P1 executeSequential 迁移的串行策略：
 * Agent 按序执行、输出依次传递、事件推送、取消检查（AC-003/008/010/021）。
 * </p>
 */
class SequentialExecutionStrategyTest {

    private AgentExecutor agentExecutor;
    private SequentialExecutionStrategy strategy;

    @BeforeEach
    void setUp() {
        agentExecutor = mock(AgentExecutor.class);
        strategy = new SequentialExecutionStrategy(agentExecutor);
    }

    private WorkflowTemplate threeAgentTemplate() {
        return WorkflowTemplate.builder()
                .id("tpl-3")
                .name("三 Agent")
                .mode(OrchestrationMode.SEQUENTIAL)
                .maxRetries(0)
                .agents(List.of(
                        AgentDefinition.builder().name("研究 Agent").description("d1")
                                .toolIds(List.of()).interfaceClass(ResearchAgent.class).build(),
                        AgentDefinition.builder().name("分析 Agent").description("d2")
                                .toolIds(List.of()).interfaceClass(AnalysisAgent.class).build(),
                        AgentDefinition.builder().name("总结 Agent").description("d3")
                                .toolIds(List.of()).interfaceClass(SummaryAgent.class).build()))
                .parameters(List.of())
                .build();
    }

    private WorkflowExecution runningExecution(WorkflowTemplate template) {
        WorkflowExecution execution = new WorkflowExecution("exec-1", template.getId(), template.getName());
        execution.start();
        return execution;
    }

    @Test
    void supportedMode_shouldReturnSequential() {
        assertEquals(OrchestrationMode.SEQUENTIAL, strategy.supportedMode());
    }

    @Test
    void execute_shouldRunAgentsInOrderAndPassOutput() throws Exception {
        // Mock AgentExecutor：依次返回研究/分析/总结结果
        when(agentExecutor.executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any()))
                .thenReturn("研究结果", "分析结果", "总结结果");

        WorkflowTemplate template = threeAgentTemplate();
        WorkflowExecution execution = runningExecution(template);
        SseEmitter emitter = mock(SseEmitter.class);

        String result = strategy.execute(template, Map.of("topic", "AI"), emitter, execution, null, new AtomicBoolean(false));

        assertEquals("总结结果", result);
        // 3 个 Agent 依次执行
        verify(agentExecutor, times(3)).executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any());

        // 验证输入传递：第一个输入为 topic，后续为前一 Agent 输出
        ArgumentCaptor<String> inputCaptor = ArgumentCaptor.forClass(String.class);
        verify(agentExecutor, times(3)).executeWithRetry(any(), inputCaptor.capture(), any(), anyInt(), anyInt(), any());
        List<String> inputs = inputCaptor.getAllValues();
        assertEquals("AI", inputs.get(0));
        assertEquals("研究结果", inputs.get(1));
        assertEquals("分析结果", inputs.get(2));
    }

    @Test
    void execute_shouldPushWorkflowAndStepEvents() throws Exception {
        when(agentExecutor.executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any()))
                .thenReturn("R", "A", "S");

        WorkflowTemplate template = threeAgentTemplate();
        WorkflowExecution execution = runningExecution(template);
        SseEmitter emitter = mock(SseEmitter.class);

        strategy.execute(template, Map.of("topic", "X"), emitter, execution, null, new AtomicBoolean(false));

        // 事件数：workflow_start + 3×(step_start+step_complete) + workflow_complete = 8 次 send
        verify(emitter, times(8)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void execute_shouldRecordStepsInExecution() throws Exception {
        when(agentExecutor.executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any()))
                .thenReturn("R", "A", "S");

        WorkflowTemplate template = threeAgentTemplate();
        WorkflowExecution execution = runningExecution(template);
        SseEmitter emitter = mock(SseEmitter.class);

        strategy.execute(template, Map.of("topic", "X"), emitter, execution, null, new AtomicBoolean(false));

        assertEquals(3, execution.getSteps().size());
        assertEquals("研究 Agent", execution.getSteps().get(0).getAgentName());
        assertEquals("总结 Agent", execution.getSteps().get(2).getAgentName());
    }

    @Test
    void execute_cancelFlagTrue_shouldThrowCancelled() throws Exception {
        when(agentExecutor.executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any()))
                .thenReturn("R");

        WorkflowTemplate template = threeAgentTemplate();
        WorkflowExecution execution = runningExecution(template);
        SseEmitter emitter = mock(SseEmitter.class);

        AtomicBoolean cancelFlag = new AtomicBoolean(true);
        assertThrows(WorkflowCancelledException.class,
                () -> strategy.execute(template, Map.of("topic", "X"), emitter, execution, null, cancelFlag));
    }

    @Test
    void execute_shouldHandleEmptyOutput() throws Exception {
        // 第一个 Agent 输出为空，后续正常执行
        when(agentExecutor.executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any()))
                .thenReturn("", "分析结果");

        WorkflowTemplate template = threeAgentTemplate();
        // 只取前 2 个 Agent 简化
        WorkflowTemplate twoAgent = WorkflowTemplate.builder()
                .id("tpl-2").name("两 Agent").mode(OrchestrationMode.SEQUENTIAL).maxRetries(0)
                .agents(template.getAgents().subList(0, 2)).parameters(List.of())
                .build();
        WorkflowExecution execution = runningExecution(twoAgent);
        SseEmitter emitter = mock(SseEmitter.class);

        String result = strategy.execute(twoAgent, Map.of("topic", "X"), emitter, execution, null, new AtomicBoolean(false));

        assertEquals("分析结果", result);
        verify(agentExecutor, times(2)).executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any());
    }

    // ===== P3 新增：断点恢复跳过（Task-08，AC-017）=====

    @Test
    void execute_firstRun_shouldWriteResumeKeysToContext() throws Exception {
        // 首次执行：3 个 Agent 均真实执行，ctx 写入 done:0:{name} 恢复 keys（为恢复做准备）
        when(agentExecutor.executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any()))
                .thenReturn("研究结果", "分析结果", "总结结果");

        WorkflowTemplate template = threeAgentTemplate();
        WorkflowExecution execution = runningExecution(template);

        strategy.execute(template, Map.of("topic", "X"), mock(SseEmitter.class), execution, null, new AtomicBoolean(false));

        com.agentdemo.app.core.WorkflowContext ctx = execution.getContext();
        org.junit.jupiter.api.Assertions.assertNotNull(ctx, "首次执行后 ctx 应挂载到 execution");
        assertEquals("研究结果", ctx.read("done:0:研究 Agent"));
        assertEquals("分析结果", ctx.read("done:0:分析 Agent"));
        assertEquals("总结结果", ctx.read("done:0:总结 Agent"));
    }

    @Test
    void execute_resumeWithCompletedAgent_shouldSkipAndChainHistoryOutput() throws Exception {
        // 恢复场景：研究 Agent 已完成（ctx 预置恢复 key）
        // 预期：研究 Agent 不被调用（跳过）、分析 Agent 收到历史输出、断点后 Agent 正常执行
        when(agentExecutor.executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any()))
                .thenReturn("分析结果", "总结结果");

        WorkflowTemplate template = threeAgentTemplate();
        WorkflowExecution execution = runningExecution(template);
        com.agentdemo.app.core.WorkflowContext ctx = new com.agentdemo.app.core.WorkflowContext();
        ctx.write("done:0:研究 Agent", "历史输出1");
        execution.attachContext(ctx);

        String result = strategy.execute(template, Map.of("topic", "X"), mock(SseEmitter.class),
                execution, null, new AtomicBoolean(false));

        assertEquals("总结结果", result);
        // 仅 2 次真实执行（分析/总结），研究 Agent 跳过
        verify(agentExecutor, times(2)).executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any());
        verify(agentExecutor, never()).executeWithRetry(any(), eq("X"), any(), eq(0), anyInt(), any());

        // 跳过的历史输出作为下一 Agent 输入（分析 Agent 收到 "历史输出1" 而非 topic）
        ArgumentCaptor<String> inputCaptor = ArgumentCaptor.forClass(String.class);
        verify(agentExecutor, times(2)).executeWithRetry(any(), inputCaptor.capture(), any(), anyInt(), anyInt(), any());
        assertEquals("历史输出1", inputCaptor.getAllValues().get(0));
        assertEquals("分析结果", inputCaptor.getAllValues().get(1));
    }
}
