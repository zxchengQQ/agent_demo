package com.agentdemo.app.integration;

import com.agentdemo.app.adapter.AgenticAgentFactory;
import com.agentdemo.app.core.AgentDefinition;
import com.agentdemo.app.core.WorkflowExecution;
import com.agentdemo.app.core.WorkflowExecutionStatus;
import com.agentdemo.app.core.WorkflowTemplate;
import com.agentdemo.app.execution.AgentExecutor;
import com.agentdemo.app.execution.WorkflowEventPublisher;
import com.agentdemo.app.registry.WorkflowTemplateRegistry;
import com.agentdemo.app.service.TestTokenStream;
import com.agentdemo.app.service.WorkflowExecutionService;
import com.agentdemo.app.strategy.SequentialExecutionStrategy;
import com.agentdemo.app.strategy.SupervisorExecutionStrategy;
import com.agentdemo.app.template.AnalysisAgent;
import com.agentdemo.app.template.ResearchAgent;
import com.agentdemo.app.template.ResearchAnalyzeSummarizeTemplate;
import com.agentdemo.app.template.SummaryAgent;
import com.agentdemo.app.template.SupervisorPlanAgent;
import com.agentdemo.app.template.SupervisorSummarizeAgent;
import com.agentdemo.app.template.TaskBreakdownSupervisorTemplate;
import com.agentdemo.tools.registry.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P3 端到端集成测试（Task-23）
 * <p>
 * 业务含义：在真实策略 + 真实模板注册 + mock LLM（AgenticAgentFactory）的完整链路上验证：
 * 1. Supervisor 层级编排端到端：拆解 -> 路由调度 -> 汇总，SSE 事件序列完整（AC-007）
 * 2. 暂停恢复端到端：重试耗尽 -> PAUSED + workflow_paused 事件 -> resume 后
 *    step_skipped（已完成步骤跳过）+ 失败步骤重执行 -> COMPLETED（AC-016/AC-017）
 * </p>
 * <p>
 * 技术说明：事件断言通过 RecordingEmitter 捕获（执行在 ForkJoinPool 异步线程，
 * MockedStatic 无法拦截跨线程静态调用，故重写 SseEmitter.send 记录事件名）。
 * </p>
 */
class WorkflowP3IntegrationTest {

    /** 子任务清单 JSON（主控拆解输出，2 个子任务路由到研究/分析 Worker） */
    private static final String PLAN_JSON =
            "[{\"id\":1,\"description\":\"调研分类算法\",\"agent\":\"研究\"},"
                    + "{\"id\":2,\"description\":\"对比分析主流方案\",\"agent\":\"分析\"}]";

    private WorkflowTemplateRegistry registry;
    private AgenticAgentFactory agentFactory;
    private WorkflowExecutionService service;

    @BeforeEach
    void setUp() {
        registry = new WorkflowTemplateRegistry();
        agentFactory = mock(AgenticAgentFactory.class);
        // P3 架构：真实策略（串行 + Supervisor）+ mock AgentFactory 隔离 LLM
        AgentExecutor agentExecutor = new AgentExecutor(agentFactory, 1);
        service = new WorkflowExecutionService(List.of(
                new SequentialExecutionStrategy(agentExecutor),
                new SupervisorExecutionStrategy(agentExecutor)));

        // 注册 P1 串行模板 + P3 Supervisor 模板
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        new ResearchAnalyzeSummarizeTemplate(toolRegistry).researchAnalyzeSummarizeWorkflow(registry);
        new TaskBreakdownSupervisorTemplate(toolRegistry).taskBreakdownSupervisorWorkflow(registry);
    }

    /**
     * 记录事件名的 SseEmitter（重写 send 拦截 SseEventBuilder，线程安全）
     */
    static class RecordingEmitter extends SseEmitter {
        final List<String> eventNames = new CopyOnWriteArrayList<>();

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            // SseEventBuilder.build() 返回的文本 entry 形如 "event:NAME\n"（部分场景含后续 "data:" 标记），
            // 用正则提取首个 event:NAME 片段（\S+ 遇换行即停，天然过滤杂质）
            for (ResponseBodyEmitter.DataWithMediaType entry : builder.build()) {
                if (entry.getData() instanceof String text) {
                    java.util.regex.Matcher matcher = EVENT_NAME_PATTERN.matcher(text);
                    if (matcher.find()) {
                        eventNames.add(matcher.group(1));
                    }
                }
            }
        }

        int count(String eventName) {
            return (int) eventNames.stream().filter(e -> e.equals(eventName)).count();
        }
    }

    /** 断言辅助：事件 a 的序号严格小于事件 b（首个出现位置） */
    private static void assertOrder(RecordingEmitter emitter, String before, String after) {
        int idxBefore = emitter.eventNames.indexOf(before);
        int idxAfter = emitter.eventNames.indexOf(after);
        assertTrue(idxBefore >= 0, "缺少事件: " + before + "，实际序列: " + emitter.eventNames);
        assertTrue(idxAfter >= 0, "缺少事件: " + after + "，实际序列: " + emitter.eventNames);
        assertTrue(idxBefore < idxAfter, "事件顺序错误: " + before + " 应在 " + after + " 之前，实际序列: " + emitter.eventNames);
    }

    /** 从 SSE 文本 entry 提取事件名的正则（event:NAME，NAME 为非空白连续段） */
    private static final java.util.regex.Pattern EVENT_NAME_PATTERN =
            java.util.regex.Pattern.compile("event:(\\S+)");

    /** 构造立即完成的 TokenStream */
    private static TestTokenStream streamOf(String text) {
        TestTokenStream stream = new TestTokenStream();
        stream.completeWith(text);
        return stream;
    }

    /** 按 Agent 名分发 mock（buildAgent 在每次执行时都会被调用，须按定义路由） */
    private void stubBuildAgentByAgentName(Object... nameAgentPairs) {
        when(agentFactory.buildAgent(any())).thenAnswer(inv -> {
            AgentDefinition def = inv.getArgument(0);
            for (int i = 0; i < nameAgentPairs.length; i += 2) {
                if (nameAgentPairs[i].equals(def.getName())) {
                    return nameAgentPairs[i + 1];
                }
            }
            throw new IllegalStateException("未 mock 的 Agent: " + def.getName());
        });
    }

    /** 等待异步执行到达非 RUNNING 状态（PAUSED/COMPLETED 均视为到达） */
    private WorkflowExecution awaitNotRunning(String executionId) {
        long deadline = System.currentTimeMillis() + 10000;
        WorkflowExecution execution = null;
        while (System.currentTimeMillis() < deadline) {
            execution = service.getExecution(executionId);
            if (execution != null && execution.getStatus() != WorkflowExecutionStatus.RUNNING
                    && execution.getStatus() != WorkflowExecutionStatus.PENDING) {
                return execution;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertNotNull(execution, "执行应在超时内到达稳定状态");
        assertNotEquals(WorkflowExecutionStatus.RUNNING, execution.getStatus(), "执行不应仍处于 RUNNING");
        return execution;
    }

    // ===== 集成测试 1：Supervisor 端到端（AC-007，Task-23 验证标准③）=====

    @Test
    void supervisorExecution_endToEnd_shouldEmitPlanDispatchSummaryThenComplete() {
        // mock 主控拆解（输出子任务 JSON）、两个 Worker、主控汇总
        SupervisorPlanAgent planAgent = mock(SupervisorPlanAgent.class);
        when(planAgent.execute(any())).thenAnswer(inv -> streamOf(PLAN_JSON));
        ResearchAgent research = mock(ResearchAgent.class);
        when(research.execute(any())).thenAnswer(inv -> streamOf("调研输出"));
        AnalysisAgent analysis = mock(AnalysisAgent.class);
        when(analysis.execute(any())).thenAnswer(inv -> streamOf("分析输出"));
        SummaryAgent summary = mock(SummaryAgent.class);
        SupervisorSummarizeAgent summarize = mock(SupervisorSummarizeAgent.class);
        when(summarize.execute(any())).thenAnswer(inv -> streamOf("综合报告"));
        stubBuildAgentByAgentName(
                "任务拆解(主控)", planAgent,
                "研究", research,
                "分析", analysis,
                "总结", summary,
                "综合汇总(主控)", summarize);

        WorkflowTemplate template = registry.getTemplate(TaskBreakdownSupervisorTemplate.TEMPLATE_ID);
        RecordingEmitter emitter = new RecordingEmitter();
        String executionId = service.execute(template, Map.of("task", "写一份分类算法调研报告"), emitter, null);

        WorkflowExecution execution = awaitNotRunning(executionId);
        assertEquals(WorkflowExecutionStatus.COMPLETED, execution.getStatus(), "Supervisor 工作流应执行完成");
        assertEquals("综合报告", execution.getFinalResult());

        // 事件序列：step_start(拆解) -> supervisor_plan -> supervisor_dispatch*2 -> step_start/step_complete(Worker)
        //           -> supervisor_summary -> step_start/step_complete(汇总) -> workflow_complete
        assertTrue(emitter.eventNames.contains("step_start"), "缺少拆解步骤 step_start，实际: " + emitter.eventNames);
        assertEquals(2, emitter.count("supervisor_dispatch"), "应调度 2 个子任务，实际: " + emitter.eventNames);
        assertOrder(emitter, "step_start", "supervisor_plan");
        assertOrder(emitter, "supervisor_plan", "supervisor_dispatch");
        // 全部调度完成后才进入汇总：最后一次调度在 supervisor_summary 之前，且之后仍有 Worker/汇总的完成事件
        assertOrder(emitter, "supervisor_dispatch", "supervisor_summary");
        assertTrue(emitter.eventNames.lastIndexOf("supervisor_dispatch")
                        < emitter.eventNames.lastIndexOf("step_complete"),
                "最后一次调度后应有 Worker 执行完成事件，实际: " + emitter.eventNames);
        // 汇总 Agent 自身的完成事件在 supervisor_summary 之后
        assertTrue(emitter.eventNames.indexOf("supervisor_summary")
                        < emitter.eventNames.lastIndexOf("step_complete"),
                "supervisor_summary 后应有汇总步骤完成事件，实际: " + emitter.eventNames);
        assertOrder(emitter, "supervisor_summary", "workflow_complete");
        assertEquals("workflow_complete", emitter.eventNames.get(emitter.eventNames.size() - 1),
                "最后一个事件应为 workflow_complete");

        // Agent 执行次数：拆解 1 次、两个 Worker 各 1 次、汇总 1 次；未被路由的 Worker 不执行
        verify(planAgent, times(1)).execute(any());
        verify(research, times(1)).execute(any());
        verify(analysis, times(1)).execute(any());
        verify(summary, never()).execute(any());
        verify(summarize, times(1)).execute(any());
    }

    // ===== 集成测试 2：暂停恢复端到端（AC-016/AC-017，Task-23 验证标准③）=====

    @Test
    void sequentialExecution_pauseThenResume_shouldSkipCompletedAndRerunFailed() {
        ResearchAgent research = mock(ResearchAgent.class);
        when(research.execute(any())).thenAnswer(inv -> streamOf("研究资料"));

        // 分析 Agent：前 4 次（首次 + maxRetries=3 次重试）失败 -> 暂停；恢复后第 5 次成功
        AnalysisAgent analysis = mock(AnalysisAgent.class);
        final int[] analysisCalls = {0};
        when(analysis.execute(any())).thenAnswer(inv -> {
            analysisCalls[0]++;
            if (analysisCalls[0] <= 4) {
                throw new RuntimeException("LLM 连接超时");
            }
            return streamOf("分析洞察");
        });

        SummaryAgent summary = mock(SummaryAgent.class);
        when(summary.execute(any())).thenAnswer(inv -> streamOf("最终报告"));
        stubBuildAgentByAgentName(
                "研究 Agent", research,
                "分析 Agent", analysis,
                "总结 Agent", summary);

        WorkflowTemplate template = registry.getTemplate(ResearchAnalyzeSummarizeTemplate.TEMPLATE_ID);
        RecordingEmitter firstEmitter = new RecordingEmitter();
        String executionId = service.execute(template, Map.of("topic", "AI"), firstEmitter, null);

        // 第一阶段：分析 Agent 重试耗尽 -> PAUSED（非 FAILED）+ workflow_paused 事件
        WorkflowExecution paused = awaitNotRunning(executionId);
        assertEquals(WorkflowExecutionStatus.PAUSED, paused.getStatus(), "重试耗尽后应暂停而非失败（AC-016）");
        assertTrue(firstEmitter.eventNames.contains("workflow_paused"), "原流应推送 workflow_paused 事件");
        assertTrue(firstEmitter.eventNames.contains("step_error"), "重试耗尽应推送 step_error 事件");
        verify(analysis, times(4)).execute(any());

        // 第二阶段：resume 后跳过已完成的研究步骤，重执行分析步骤 -> COMPLETED
        RecordingEmitter resumeEmitter = new RecordingEmitter();
        service.resume(executionId, resumeEmitter);

        WorkflowExecution completed = awaitNotRunning(executionId);
        assertEquals(WorkflowExecutionStatus.COMPLETED, completed.getStatus(), "恢复后应执行完成（AC-017）");
        assertEquals("最终报告", completed.getFinalResult());

        // 恢复流事件：step_skipped（研究步骤跳过）+ workflow_complete
        assertTrue(resumeEmitter.eventNames.contains("step_skipped"), "恢复流应推送 step_skipped 跳过已完成步骤");
        assertOrder(resumeEmitter, "step_skipped", "workflow_complete");
        assertEquals("workflow_complete", resumeEmitter.eventNames.get(resumeEmitter.eventNames.size() - 1));

        // 断点续执行核心断言：研究 Agent 未重复执行（仍 1 次），分析 Agent 共 5 次（4 失败 + 1 成功）
        verify(research, times(1)).execute(any());
        verify(analysis, times(5)).execute(any());
        verify(summary, times(1)).execute(any());
    }
}
