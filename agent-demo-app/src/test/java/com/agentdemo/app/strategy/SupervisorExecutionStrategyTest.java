package com.agentdemo.app.strategy;

import com.agentdemo.app.core.AgentDefinition;
import com.agentdemo.app.core.OrchestrationMode;
import com.agentdemo.app.core.Subtask;
import com.agentdemo.app.core.SupervisorDefinition;
import com.agentdemo.app.core.WorkflowContext;
import com.agentdemo.app.core.WorkflowExecution;
import com.agentdemo.app.core.WorkflowTemplate;
import com.agentdemo.app.execution.AgentExecutor;
import com.agentdemo.app.execution.WorkflowEventPublisher;
import com.agentdemo.app.service.WorkflowPausedException;
import com.agentdemo.common.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Supervisor 层级编排策略测试（P3 Task-14/15，AC-007）
 * <p>
 * 业务含义：验证 Worker 三级路由（精确/包含/兜底）与
 * "拆解 -> 依次调度 -> 汇总" 主流程编排（含解析重试与断点恢复）。
 * </p>
 */
class SupervisorExecutionStrategyTest {

    private static final String PLAN_JSON_2 =
            "[{\"id\":1,\"description\":\"调研资料\",\"agent\":\"研究\"}," +
            "{\"id\":2,\"description\":\"对比分析\",\"agent\":\"分析\"}]";

    private AgentExecutor agentExecutor;
    private SupervisorExecutionStrategy strategy;
    private MockedStatic<WorkflowEventPublisher> publisherMock;
    private SseEmitter emitter;

    @BeforeEach
    void setUp() {
        agentExecutor = mock(AgentExecutor.class);
        strategy = new SupervisorExecutionStrategy(agentExecutor);
        publisherMock = mockStatic(WorkflowEventPublisher.class);
        emitter = mock(SseEmitter.class);
    }

    @AfterEach
    void tearDown() {
        publisherMock.close();
    }

    private AgentDefinition agentDef(String name) {
        return AgentDefinition.builder().name(name).description(name + " 描述")
                .toolIds(List.of()).build();
    }

    private List<AgentDefinition> workerPool() {
        return List.of(agentDef("研究"), agentDef("分析"), agentDef("总结"));
    }

    private WorkflowTemplate supervisorTemplate() {
        return WorkflowTemplate.builder()
                .id("tpl-sup")
                .name("任务拆解")
                .mode(OrchestrationMode.SUPERVISOR)
                .maxRetries(1)
                .agents(List.of())
                .parameters(List.of())
                .supervisor(SupervisorDefinition.builder()
                        .planAgent(agentDef("主控拆解"))
                        .workers(workerPool())
                        .summarizeAgent(agentDef("主控汇总"))
                        .maxSubtasks(5)
                        .build())
                .build();
    }

    private WorkflowExecution runningExecution(WorkflowTemplate template) {
        WorkflowExecution execution = new WorkflowExecution("exec-1", template.getId(), template.getName());
        execution.start();
        return execution;
    }

    /** 按 Agent 名分发输出的通用 stub */
    private void stubByAgentName(String planOutput) {
        when(agentExecutor.executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any(), anyInt(), anyString()))
                .thenAnswer(inv -> {
                    AgentDefinition def = inv.getArgument(0);
                    return switch (def.getName()) {
                        case "主控拆解" -> planOutput;
                        case "主控汇总" -> "汇总报告";
                        default -> "输出:" + def.getName();
                    };
                });
    }

    // ===== Task-14: Worker 三级路由 =====

    @Test
    void routeWorker_exactMatch_shouldReturnWorkerWithRoutedTrue() {
        SupervisorExecutionStrategy.RoutingResult result =
                SupervisorExecutionStrategy.routeWorker(workerPool(), "研究");

        assertEquals("研究", result.worker().getName());
        assertTrue(result.routed(), "精确匹配 routed 应为 true");
    }

    @Test
    void routeWorker_containsMatch_shouldReturnWorkerWithRoutedTrue() {
        SupervisorExecutionStrategy.RoutingResult result =
                SupervisorExecutionStrategy.routeWorker(workerPool(), "研究 Agent");

        assertEquals("研究", result.worker().getName());
        assertTrue(result.routed(), "包含匹配 routed 应为 true");
    }

    @Test
    void routeWorker_unknownAgent_shouldFallbackToFirstWorkerWithRoutedFalse() {
        SupervisorExecutionStrategy.RoutingResult result =
                SupervisorExecutionStrategy.routeWorker(workerPool(), "不存在的角色");

        assertEquals("研究", result.worker().getName(), "兜底应返回第一个 Worker");
        assertFalse(result.routed(), "兜底 routed 应为 false");
    }

    @Test
    void routeWorker_nullAgent_shouldFallbackToFirstWorker() {
        SupervisorExecutionStrategy.RoutingResult result =
                SupervisorExecutionStrategy.routeWorker(workerPool(), null);

        assertEquals("研究", result.worker().getName());
        assertFalse(result.routed());
    }

    @Test
    void routeWorker_blankAgent_shouldFallbackToFirstWorker() {
        SupervisorExecutionStrategy.RoutingResult result =
                SupervisorExecutionStrategy.routeWorker(workerPool(), "  ");

        assertEquals("研究", result.worker().getName());
        assertFalse(result.routed());
    }

    // ===== Task-15: 主流程 =====

    @Test
    void supportedMode_shouldReturnSupervisor() {
        assertEquals(OrchestrationMode.SUPERVISOR, strategy.supportedMode());
    }

    @Test
    void execute_normalFlow_shouldPlanDispatchAndSummarize() throws Exception {
        stubByAgentName(PLAN_JSON_2);
        WorkflowTemplate template = supervisorTemplate();
        WorkflowExecution execution = runningExecution(template);

        String result = strategy.execute(template, Map.of("task", "写一篇 AI 综述"),
                emitter, execution, null, new AtomicBoolean(false));

        assertEquals("汇总报告", result, "应返回主控汇总输出");
        // 4 次调用：主控拆解 + 研究 + 分析 + 主控汇总
        verify(agentExecutor, times(4)).executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any(), anyInt(), anyString());
        // 研究 Worker 收到子任务 1 描述，分析 Worker 收到子任务 2 描述
        ArgumentCaptor<String> inputCaptor = ArgumentCaptor.forClass(String.class);
        verify(agentExecutor, times(4)).executeWithRetry(any(), inputCaptor.capture(), any(), anyInt(), anyInt(), any(), anyInt(), anyString());
        List<String> inputs = inputCaptor.getAllValues();
        assertTrue(inputs.get(0).contains("AI 综述"), "主控拆解输入应为原始任务");
        assertTrue(inputs.get(1).contains("调研资料"), "研究 Worker 输入应含子任务 1 描述");
        assertTrue(inputs.get(2).contains("对比分析"), "分析 Worker 输入应含子任务 2 描述");
        assertTrue(inputs.get(3).contains("输出:研究") && inputs.get(3).contains("输出:分析"),
                "汇总输入应含全部子任务结果，实际: " + inputs.get(3));
    }

    @Test
    void execute_workerInput_shouldContainTaskAndSubtaskDescription() throws Exception {
        stubByAgentName(PLAN_JSON_2);
        WorkflowTemplate template = supervisorTemplate();

        strategy.execute(template, Map.of("task", "调研 AI 现状"),
                emitter, runningExecution(template), null, new AtomicBoolean(false));

        ArgumentCaptor<String> inputCaptor = ArgumentCaptor.forClass(String.class);
        verify(agentExecutor, times(4)).executeWithRetry(any(), inputCaptor.capture(), any(), anyInt(), anyInt(), any(), anyInt(), anyString());
        String workerInput = inputCaptor.getAllValues().get(1);
        assertTrue(workerInput.contains("原始任务"), "子任务输入应含原始任务，实际: " + workerInput);
        assertTrue(workerInput.contains("你负责的子任务"), "子任务输入应含子任务描述前缀，实际: " + workerInput);
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_shouldEmitSupervisorPlanEventWithSubtasks() throws Exception {
        stubByAgentName(PLAN_JSON_2);
        WorkflowTemplate template = supervisorTemplate();

        strategy.execute(template, Map.of("task", "任务"),
                emitter, runningExecution(template), null, new AtomicBoolean(false));

        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        publisherMock.verify(() -> WorkflowEventPublisher.send(eq(emitter), eq("supervisor_plan"),
                dataCaptor.capture()));
        Map<String, Object> data = dataCaptor.getValue();
        assertEquals(2, data.get("totalSubtasks"));
        List<Map<String, Object>> subtasks = (List<Map<String, Object>>) data.get("subtasks");
        assertEquals(2, subtasks.size());
        assertEquals(1, subtasks.get(0).get("id"));
        assertEquals("调研资料", subtasks.get(0).get("description"));
        assertEquals("研究", subtasks.get(0).get("agent"));
        assertTrue((Boolean) subtasks.get(0).get("routed"), "研究 精确命中 routed=true");
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_shouldEmitDispatchEventBeforeEachSubtask() throws Exception {
        stubByAgentName(PLAN_JSON_2);
        WorkflowTemplate template = supervisorTemplate();

        strategy.execute(template, Map.of("task", "任务"),
                emitter, runningExecution(template), null, new AtomicBoolean(false));

        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        publisherMock.verify(() -> WorkflowEventPublisher.send(eq(emitter), eq("supervisor_dispatch"),
                dataCaptor.capture()), times(2));
        Map<String, Object> first = dataCaptor.getAllValues().get(0);
        assertEquals(1, first.get("subtaskIndex"));
        assertEquals(2, first.get("totalSubtasks"));
        assertEquals("调研资料", first.get("description"));
        assertEquals("研究", first.get("agentName"));
        assertTrue((Boolean) first.get("routed"));
        assertEquals(2, dataCaptor.getAllValues().get(1).get("subtaskIndex"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_shouldEmitSummaryEventBeforeSummarize() throws Exception {
        stubByAgentName(PLAN_JSON_2);
        WorkflowTemplate template = supervisorTemplate();

        strategy.execute(template, Map.of("task", "任务"),
                emitter, runningExecution(template), null, new AtomicBoolean(false));

        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        publisherMock.verify(() -> WorkflowEventPublisher.send(eq(emitter), eq("supervisor_summary"),
                dataCaptor.capture()));
        assertEquals(2, dataCaptor.getValue().get("subtaskCount"));
    }

    @Test
    void execute_planParseFail_thenRetryShouldSucceed() throws Exception {
        // 第 1 次拆解输出非 JSON，第 2 次合法（maxRetries=1 -> 2 次机会）
        when(agentExecutor.executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any(), anyInt(), anyString()))
                .thenReturn("抱歉，我无法输出 JSON", PLAN_JSON_2, "输出:研究", "输出:分析", "汇总报告");
        WorkflowTemplate template = supervisorTemplate();

        String result = strategy.execute(template, Map.of("task", "任务"),
                emitter, runningExecution(template), null, new AtomicBoolean(false));

        assertEquals("汇总报告", result);
        verify(agentExecutor, times(2)).executeWithRetry(eq(template.getSupervisor().getPlanAgent()),
                anyString(), any(), eq(0), anyInt(), any(), anyInt(), anyString());
    }

    @Test
    void execute_planParseAllFail_shouldThrowWorkflowPausedException() throws Exception {
        when(agentExecutor.executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any(), anyInt(), anyString()))
                .thenReturn("我不会输出 JSON", "依然不是 JSON");
        WorkflowTemplate template = supervisorTemplate();

        assertThrows(WorkflowPausedException.class, () ->
                strategy.execute(template, Map.of("task", "任务"),
                        emitter, runningExecution(template), null, new AtomicBoolean(false)));
    }

    @Test
    void execute_resume_shouldSkipPlanAndCompletedSubtasks() throws Exception {
        stubByAgentName(PLAN_JSON_2);
        WorkflowTemplate template = supervisorTemplate();
        WorkflowExecution execution = runningExecution(template);
        // 预置恢复状态：拆解已完成 + 子任务 1 已完成
        WorkflowContext ctx = new WorkflowContext();
        ctx.write("supervisor:plan", new ArrayList<>(List.of(
                Subtask.builder().id(1).description("调研资料").agent("研究").build(),
                Subtask.builder().id(2).description("对比分析").agent("分析").build())));
        ctx.write("subtask:1", "历史输出1");
        execution.attachContext(ctx);

        String result = strategy.execute(template, Map.of("task", "任务"),
                emitter, execution, null, new AtomicBoolean(false));

        assertEquals("汇总报告", result);
        // 主控拆解不重新调用；子任务 1（研究）跳过；仅子任务 2（分析）+ 汇总执行
        verify(agentExecutor, never()).executeWithRetry(eq(template.getSupervisor().getPlanAgent()),
                anyString(), any(), anyInt(), anyInt(), any(), anyInt(), anyString());
        verify(agentExecutor, times(2)).executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any(), anyInt(), anyString());
        ArgumentCaptor<String> inputCaptor = ArgumentCaptor.forClass(String.class);
        verify(agentExecutor, times(2)).executeWithRetry(any(), inputCaptor.capture(), any(), anyInt(), anyInt(), any(), anyInt(), anyString());
        assertTrue(inputCaptor.getAllValues().get(1).contains("历史输出1"),
                "汇总输入应含跳过子任务的历史输出");
        // plan 与子任务 1 均推 step_skipped
        publisherMock.verify(() -> WorkflowEventPublisher.send(eq(emitter), eq("step_skipped"), any()), times(2));
    }

    @Test
    void execute_emptyWorkers_shouldThrowBusinessException() {
        WorkflowTemplate template = WorkflowTemplate.builder()
                .id("tpl-bad").name("无 Worker").mode(OrchestrationMode.SUPERVISOR).maxRetries(1)
                .supervisor(SupervisorDefinition.builder()
                        .planAgent(agentDef("主控拆解"))
                        .workers(List.of())
                        .summarizeAgent(agentDef("主控汇总"))
                        .maxSubtasks(5)
                        .build())
                .build();

        BusinessException ex = assertThrows(BusinessException.class, () ->
                strategy.execute(template, Map.of("task", "任务"),
                        emitter, runningExecution(template), null, new AtomicBoolean(false)));
        assertTrue(ex.getMessage().contains("未配置 Worker 池"),
                "异常 message 应含\"未配置 Worker 池\"，实际: " + ex.getMessage());
    }

    @Test
    void execute_nullSupervisor_shouldThrowBusinessException() {
        WorkflowTemplate template = WorkflowTemplate.builder()
                .id("tpl-bad").name("无主控").mode(OrchestrationMode.SUPERVISOR).maxRetries(1)
                .build();

        assertThrows(BusinessException.class, () ->
                strategy.execute(template, Map.of("task", "任务"),
                        emitter, runningExecution(template), null, new AtomicBoolean(false)));
    }
}
