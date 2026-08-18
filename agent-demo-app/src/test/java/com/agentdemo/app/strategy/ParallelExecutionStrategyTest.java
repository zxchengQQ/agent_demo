package com.agentdemo.app.strategy;

import com.agentdemo.app.core.AgentDefinition;
import com.agentdemo.app.core.OrchestrationMode;
import com.agentdemo.app.core.ParallelGroup;
import com.agentdemo.app.core.ParameterDefinition;
import com.agentdemo.app.core.WorkflowExecution;
import com.agentdemo.app.core.WorkflowTemplate;
import com.agentdemo.app.execution.AgentExecutor;
import com.agentdemo.app.service.WorkflowCancelledException;
import com.agentdemo.app.template.ResearchAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 并行编排策略测试（P2 Task-07）
 * <p>
 * 业务含义：验证多个并行分组同时执行、组内 Agent 串行、各组结果汇总为综合报告（AC-004）。
 * </p>
 */
class ParallelExecutionStrategyTest {

    private AgentExecutor agentExecutor;
    private ParallelExecutionStrategy strategy;

    @BeforeEach
    void setUp() {
        agentExecutor = mock(AgentExecutor.class);
        strategy = new ParallelExecutionStrategy(agentExecutor);
    }

    private AgentDefinition agentDef(String name) {
        return AgentDefinition.builder().name(name).toolIds(List.of()).interfaceClass(ResearchAgent.class).build();
    }

    private WorkflowTemplate threeGroupTemplate() {
        return WorkflowTemplate.builder()
                .id("tpl-parallel")
                .name("多角度审查")
                .mode(OrchestrationMode.PARALLEL)
                .maxRetries(0)
                .parameters(List.of())
                .parallelGroups(List.of(
                        ParallelGroup.builder().name("安全审查").agents(List.of(agentDef("安全 Agent"))).build(),
                        ParallelGroup.builder().name("性能审查").agents(List.of(agentDef("性能 Agent"))).build(),
                        ParallelGroup.builder().name("风格审查").agents(List.of(agentDef("风格 Agent"))).build()))
                .build();
    }

    private WorkflowExecution runningExecution(WorkflowTemplate template) {
        WorkflowExecution execution = new WorkflowExecution("exec-p", template.getId(), template.getName());
        execution.start();
        return execution;
    }

    @Test
    void supportedMode_shouldReturnParallel() {
        assertEquals(OrchestrationMode.PARALLEL, strategy.supportedMode());
    }

    @Test
    void execute_shouldRunAllGroupsAndSummarize() throws Exception {
        // 并发安全：按 Agent 名返回输出（避免 mockito 多值 thenReturn 并发错乱）
        when(agentExecutor.executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any()))
                .thenAnswer(inv -> inv.getArgument(0, AgentDefinition.class).getName() + "-输出");

        WorkflowTemplate template = threeGroupTemplate();
        WorkflowExecution execution = runningExecution(template);
        SseEmitter emitter = mock(SseEmitter.class);

        String result = strategy.execute(template, Map.of(), emitter, execution, null, new AtomicBoolean(false));

        // 综合报告包含 3 个分组名称与各自输出
        assertTrue(result.contains("安全审查"));
        assertTrue(result.contains("安全 Agent-输出"));
        assertTrue(result.contains("性能审查"));
        assertTrue(result.contains("性能 Agent-输出"));
        assertTrue(result.contains("风格审查"));
        assertTrue(result.contains("风格 Agent-输出"));
        // 3 个 Agent 各执行 1 次
        verify(agentExecutor, times(3)).executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any());
    }

    @Test
    void execute_shouldPushGroupEvents() throws Exception {
        when(agentExecutor.executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any()))
                .thenReturn("A", "B", "C");

        WorkflowTemplate template = threeGroupTemplate();
        WorkflowExecution execution = runningExecution(template);
        SseEmitter emitter = mock(SseEmitter.class);

        strategy.execute(template, Map.of(), emitter, execution, null, new AtomicBoolean(false));

        // 事件数：workflow_start(1) + 3×(group_start+step_start+step_complete+group_complete)=12 + workflow_complete(1) = 14
        verify(emitter, times(14)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void execute_groupWithMultipleAgents_shouldRunSequentiallyWithinGroup() throws Exception {
        // 分组1 含 2 个 Agent，分组2 含 1 个 Agent
        WorkflowTemplate template = WorkflowTemplate.builder()
                .id("tpl-parallel-2")
                .name("混合并行")
                .mode(OrchestrationMode.PARALLEL)
                .maxRetries(0)
                .parameters(List.of())
                .parallelGroups(List.of(
                        ParallelGroup.builder().name("组1").agents(List.of(agentDef("A1"), agentDef("A2"))).build(),
                        ParallelGroup.builder().name("组2").agents(List.of(agentDef("B1"))).build()))
                .build();

        when(agentExecutor.executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any()))
                .thenAnswer(inv -> inv.getArgument(0, AgentDefinition.class).getName());

        WorkflowExecution execution = runningExecution(template);
        SseEmitter emitter = mock(SseEmitter.class);

        String result = strategy.execute(template, Map.of(), emitter, execution, null, new AtomicBoolean(false));

        // 3 个 Agent 全部执行
        verify(agentExecutor, times(3)).executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any());
        // 组1 保留最后一个 Agent（A2）输出，组2 保留 B1 输出
        assertTrue(result.contains("A2"));
        assertTrue(result.contains("B1"));
        assertFalse(result.contains("A1"), "组内串行只保留最后一个 Agent 输出");
    }

    @Test
    void execute_groupAgentFailure_shouldThrow() throws Exception {
        when(agentExecutor.executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any()))
                .thenThrow(new RuntimeException("安全审查失败"));

        WorkflowTemplate template = threeGroupTemplate();
        WorkflowExecution execution = runningExecution(template);
        SseEmitter emitter = mock(SseEmitter.class);

        assertThrows(Exception.class,
                () -> strategy.execute(template, Map.of(), emitter, execution, null, new AtomicBoolean(false)));
    }

    @Test
    void execute_cancelFlagTrue_shouldThrowCancelled() {
        WorkflowTemplate template = threeGroupTemplate();
        WorkflowExecution execution = runningExecution(template);
        SseEmitter emitter = mock(SseEmitter.class);

        assertThrows(WorkflowCancelledException.class,
                () -> strategy.execute(template, Map.of(), emitter, execution, null, new AtomicBoolean(true)));
    }

    @Test
    void execute_shouldPassUserInputToFirstAgent_notEmpty() throws Exception {
        // BUG 复现：用户输入的 content 参数应传递给每个分组的第一个 Agent，而非空字符串
        when(agentExecutor.executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any()))
                .thenAnswer(inv -> inv.getArgument(0, AgentDefinition.class).getName() + "-输出");

        WorkflowTemplate template = WorkflowTemplate.builder()
                .id("tpl-parallel-input")
                .name("多角度审查")
                .mode(OrchestrationMode.PARALLEL)
                .maxRetries(0)
                .parameters(List.of(
                        ParameterDefinition.builder().name("content").type("string").required(true).description("待审查内容").build()
                ))
                .parallelGroups(List.of(
                        ParallelGroup.builder().name("安全审查").agents(List.of(agentDef("安全 Agent"))).build(),
                        ParallelGroup.builder().name("性能审查").agents(List.of(agentDef("性能 Agent"))).build()))
                .build();

        WorkflowExecution execution = runningExecution(template);
        SseEmitter emitter = mock(SseEmitter.class);

        strategy.execute(template, Map.of("content", "public void process(String data) { ... }"),
                emitter, execution, null, new AtomicBoolean(false));

        // 捕获所有 executeWithRetry 调用的 input 参数
        ArgumentCaptor<String> inputCaptor = ArgumentCaptor.forClass(String.class);
        verify(agentExecutor, times(2)).executeWithRetry(any(), inputCaptor.capture(), any(), anyInt(), anyInt(), any());

        // 每个分组的第一个 Agent 都应收到用户输入，而非空字符串
        for (String capturedInput : inputCaptor.getAllValues()) {
            assertNotEquals("", capturedInput, "Agent 输入不应为空字符串");
            assertEquals("public void process(String data) { ... }", capturedInput,
                    "每个分组的第一个 Agent 应收到用户输入的 content 参数值");
        }
    }

    // ===== P3 新增：分组恢复跳过（Task-11，AC-017）=====

    /** 恢复场景模板：安全组 1 Agent（已完成）、性能组 2 Agent（第 1 个已完成）、风格组 1 Agent（未开始） */
    private WorkflowTemplate resumeTemplate() {
        return WorkflowTemplate.builder()
                .id("tpl-parallel-resume")
                .name("混合审查")
                .mode(OrchestrationMode.PARALLEL)
                .maxRetries(0)
                .parameters(List.of())
                .parallelGroups(List.of(
                        ParallelGroup.builder().name("安全审查").agents(List.of(agentDef("安全 Agent"))).build(),
                        ParallelGroup.builder().name("性能审查")
                                .agents(List.of(agentDef("性能 Agent1"), agentDef("性能 Agent2"))).build(),
                        ParallelGroup.builder().name("风格审查").agents(List.of(agentDef("风格 Agent"))).build()))
                .build();
    }

    @Test
    void execute_resume_shouldSkipCompletedAgentsAndKeepGroupEvents() throws Exception {
        // 恢复场景：安全组完整（跳过）、性能组半途（Agent1 跳过 / Agent2 执行）、风格组正常执行
        when(agentExecutor.executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any()))
                .thenAnswer(inv -> inv.getArgument(0, AgentDefinition.class).getName() + "-新输出");

        WorkflowTemplate template = resumeTemplate();
        WorkflowExecution execution = runningExecution(template);
        com.agentdemo.app.core.WorkflowContext ctx = new com.agentdemo.app.core.WorkflowContext();
        ctx.write("done:0:安全 Agent", "安全历史");
        ctx.write("done:0:性能 Agent1", "性能1历史");
        execution.attachContext(ctx);

        SseEmitter emitter = mock(SseEmitter.class);
        String result = strategy.execute(template, Map.of("content", "代码"), emitter, execution, null, new AtomicBoolean(false));

        // 仅 2 次真实执行：性能 Agent2 + 风格 Agent（安全 Agent 与性能 Agent1 跳过）
        ArgumentCaptor<AgentDefinition> agentCaptor = ArgumentCaptor.forClass(AgentDefinition.class);
        verify(agentExecutor, times(2)).executeWithRetry(agentCaptor.capture(), anyString(), any(), anyInt(), anyInt(), any());
        List<String> executedNames = agentCaptor.getAllValues().stream().map(AgentDefinition::getName).toList();
        assertFalse(executedNames.contains("安全 Agent"), "安全 Agent 应整组跳过，实际执行: " + executedNames);
        assertFalse(executedNames.contains("性能 Agent1"), "性能 Agent1 应跳过，实际执行: " + executedNames);

        // 跳过分组的历史输出参与汇总（AC-017：已完成步骤不重复执行但结果保留）
        assertTrue(result.contains("安全历史"), "汇总应包含跳过分组的历史输出");
        assertTrue(result.contains("性能 Agent2-新输出"));

        // 全跳过分组（安全）仍推 group_start/group_complete（前端进度完整）
        // 事件数：workflow_start(1) + 安全组(group_start+step_skipped+group_complete=3)
        //        + 性能组(group_start+step_skipped+step_start+step_complete+group_complete=5)
        //        + 风格组(group_start+step_start+step_complete+group_complete=4) + workflow_complete(1) = 14
        verify(emitter, times(14)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void execute_resume_partialGroupChain_shouldReceiveHistoryInput() throws Exception {
        // 性能组内串联：跳过的性能 Agent1 历史输出应作为性能 Agent2 的输入
        when(agentExecutor.executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any()))
                .thenAnswer(inv -> "执行输出:" + inv.getArgument(1, String.class));

        WorkflowTemplate template = resumeTemplate();
        WorkflowExecution execution = runningExecution(template);
        com.agentdemo.app.core.WorkflowContext ctx = new com.agentdemo.app.core.WorkflowContext();
        ctx.write("done:0:安全 Agent", "安全历史");
        ctx.write("done:0:性能 Agent1", "性能1历史");
        execution.attachContext(ctx);

        strategy.execute(template, Map.of("content", "代码"), mock(SseEmitter.class), execution, null, new AtomicBoolean(false));

        // 性能 Agent2 收到"性能1历史"（跳过 Agent 的历史输出衔接组内串联）
        ArgumentCaptor<String> inputCaptor = ArgumentCaptor.forClass(String.class);
        verify(agentExecutor, times(2)).executeWithRetry(any(), inputCaptor.capture(), any(), anyInt(), anyInt(), any());
        assertTrue(inputCaptor.getAllValues().contains("性能1历史"),
                "性能 Agent2 应收到跳过 Agent1 的历史输出，实际: " + inputCaptor.getAllValues());
    }

    @Test
    void execute_firstRun_shouldWriteResumeKeys() throws Exception {
        // 首次执行：各 Agent 写入 done keys（为恢复做准备）
        when(agentExecutor.executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any()))
                .thenAnswer(inv -> inv.getArgument(0, AgentDefinition.class).getName() + "-输出");

        WorkflowTemplate template = threeGroupTemplate();
        WorkflowExecution execution = runningExecution(template);

        strategy.execute(template, Map.of(), mock(SseEmitter.class), execution, null, new AtomicBoolean(false));

        com.agentdemo.app.core.WorkflowContext ctx = execution.getContext();
        org.junit.jupiter.api.Assertions.assertNotNull(ctx, "首次执行后 ctx 应挂载到 execution");
        assertEquals("安全 Agent-输出", ctx.read("done:0:安全 Agent"));
        assertEquals("风格 Agent-输出", ctx.read("done:0:风格 Agent"));
    }
}
