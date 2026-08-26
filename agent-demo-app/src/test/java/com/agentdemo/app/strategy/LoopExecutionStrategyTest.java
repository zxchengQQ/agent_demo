package com.agentdemo.app.strategy;

import com.agentdemo.app.core.AgentDefinition;
import com.agentdemo.app.core.LoopDefinition;
import com.agentdemo.app.core.OrchestrationMode;
import com.agentdemo.app.core.ParameterDefinition;
import com.agentdemo.app.core.WorkflowContext;
import com.agentdemo.app.core.WorkflowExecution;
import com.agentdemo.app.core.WorkflowTemplate;
import com.agentdemo.app.execution.AgentExecutor;
import com.agentdemo.app.service.WorkflowCancelledException;
import com.agentdemo.app.template.ResearchAgent;
import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 循环编排策略测试（P2 Task-09）
 * <p>
 * 业务含义：验证循环体执行、退出条件评估、最大迭代限制（AC-006/AC-029/BR-APP-014）。
 * </p>
 */
class LoopExecutionStrategyTest {

    private AgentExecutor agentExecutor;
    private LoopExecutionStrategy strategy;

    @BeforeEach
    void setUp() {
        agentExecutor = mock(AgentExecutor.class);
        strategy = new LoopExecutionStrategy(agentExecutor);
    }

    private AgentDefinition agentDef(String name) {
        return AgentDefinition.builder().name(name).toolIds(List.of()).interfaceClass(ResearchAgent.class).build();
    }

    private WorkflowTemplate loopTemplate(int maxIterations, java.util.function.Predicate<WorkflowContext> exitCondition) {
        return WorkflowTemplate.builder()
                .id("tpl-loop")
                .name("质量评分-修订")
                .mode(OrchestrationMode.LOOP)
                .maxRetries(0)
                .parameters(List.of())
                .loop(LoopDefinition.builder()
                        .maxIterations(maxIterations)
                        .exitConditionDescription("评分 ≥ 90 时退出")
                        .exitCondition(exitCondition)
                        .agents(List.of(agentDef("评分 Agent"), agentDef("修订 Agent")))
                        .build())
                .build();
    }

    private WorkflowExecution runningExecution(WorkflowTemplate template) {
        WorkflowExecution execution = new WorkflowExecution("exec-l", template.getId(), template.getName());
        execution.start();
        return execution;
    }

    @Test
    void supportedMode_shouldReturnLoop() {
        assertEquals(OrchestrationMode.LOOP, strategy.supportedMode());
    }

    @Test
    void execute_maxIterationsReached_shouldExitWithMaxReason() throws Exception {
        // 退出条件始终不满足（评分恒 80）→ 循环 5 次后达上限退出
        when(agentExecutor.executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any(), anyInt(), anyString()))
                .thenReturn("结果");

        WorkflowTemplate template = loopTemplate(5, ctx -> ctx.readAsString("score").equals("90"));
        WorkflowExecution execution = runningExecution(template);
        SseEmitter emitter = mock(SseEmitter.class);

        String result = strategy.execute(template, Map.of("draft", "初稿"), emitter, execution, null, new AtomicBoolean(false));

        // 循环体 2 个 Agent × 5 轮 = 10 次 execute
        verify(agentExecutor, times(10)).executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any(), anyInt(), anyString());
        assertEquals(5, execution.getIterationCount());
        assertEquals("结果", result);
    }

    @Test
    void execute_exitConditionMet_shouldExitEarly() throws Exception {
        // 退出条件第 2 轮满足（前 2 轮后评分达 90）→ 仅循环 2 轮
        when(agentExecutor.executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any(), anyInt(), anyString()))
                .thenReturn("结果");

        // 用一个 AtomicInteger 控制：前 1 轮 score=80，第 2 轮 score=95
        WorkflowTemplate template = loopTemplate(5, ctx -> ctx.readAsString("score").equals("95"));
        WorkflowExecution execution = runningExecution(template);
        SseEmitter emitter = mock(SseEmitter.class);

        // Mock AgentExecutor 在第 2 轮把 score 写入 ctx 需要依赖 executeAgentList 实现。
        // 简化：退出条件由 ctx 中"lastOutput"判断，让第 2 轮后满足
        // 这里直接测：退出条件读取 ctx.state，第 2 轮 write score=95
        // 由于 executeAgentList 每次迭代将输出写入 ctx，我们让输出在第 2 轮变为 "95"
        when(agentExecutor.executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any(), anyInt(), anyString()))
                .thenReturn("80")   // 第1轮第1个Agent
                .thenReturn("80")   // 第1轮第2个Agent
                .thenReturn("95")   // 第2轮第1个Agent
                .thenReturn("95");  // 第2轮第2个Agent

        // exitCondition 检查 ctx 的 lastOutput（executeAgentList 每次结束写入 lastOutput）
        WorkflowTemplate exitOnOutputTemplate = loopTemplate(5, ctx -> ctx.readAsString("lastOutput").equals("95"));
        WorkflowExecution exec2 = runningExecution(exitOnOutputTemplate);
        SseEmitter emitter2 = mock(SseEmitter.class);

        strategy.execute(exitOnOutputTemplate, Map.of("draft", "初稿"), emitter2, exec2, null, new AtomicBoolean(false));

        // 循环 2 轮 × 2 Agent = 4 次
        verify(agentExecutor, times(4)).executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any(), anyInt(), anyString());
        assertEquals(2, exec2.getIterationCount());
    }

    @Test
    void execute_maxIterationsZero_shouldThrowParamMissing() {
        WorkflowTemplate template = loopTemplate(0, ctx -> false);
        WorkflowExecution execution = runningExecution(template);
        SseEmitter emitter = mock(SseEmitter.class);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> strategy.execute(template, Map.of(), emitter, execution, null, new AtomicBoolean(false)));
        assertEquals(ErrorCode.WORKFLOW_PARAM_MISSING, ex.getErrorCode());
    }

    @Test
    void execute_cancelFlagTrue_shouldThrowCancelled() {
        WorkflowTemplate template = loopTemplate(5, ctx -> false);
        WorkflowExecution execution = runningExecution(template);
        SseEmitter emitter = mock(SseEmitter.class);

        assertThrows(WorkflowCancelledException.class,
                () -> strategy.execute(template, Map.of(), emitter, execution, null, new AtomicBoolean(true)));
    }

    @Test
    void execute_shouldPassUserInputToFirstAgent_notEmpty() throws Exception {
        // BUG 复现：用户输入的 content 参数应传递给第一个 Agent，而非空字符串
        when(agentExecutor.executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any(), anyInt(), anyString()))
                .thenReturn("评分结果");

        WorkflowTemplate template = WorkflowTemplate.builder()
                .id("tpl-loop-input")
                .name("质量评分-修订")
                .mode(OrchestrationMode.LOOP)
                .maxRetries(0)
                .parameters(List.of(
                        ParameterDefinition.builder().name("content").type("string").required(true).description("待打磨内容").build()
                ))
                .loop(LoopDefinition.builder()
                        .maxIterations(1)
                        .exitConditionDescription("评分 ≥ 90 时退出")
                        .exitCondition(ctx -> true)
                        .agents(List.of(agentDef("评分 Agent")))
                        .build())
                .build();

        WorkflowExecution execution = runningExecution(template);
        SseEmitter emitter = mock(SseEmitter.class);

        strategy.execute(template, Map.of("content", "这是一段需要评分的初稿内容"), emitter, execution, null, new AtomicBoolean(false));

        ArgumentCaptor<String> inputCaptor = ArgumentCaptor.forClass(String.class);
        verify(agentExecutor).executeWithRetry(any(), inputCaptor.capture(), any(), anyInt(), anyInt(), any(), anyInt(), anyString());
        assertNotEquals("", inputCaptor.getValue(), "Agent 输入不应为空字符串");
        assertEquals("这是一段需要评分的初稿内容", inputCaptor.getValue(),
                "Agent 应收到用户输入的 content 参数值");
    }

    // ===== P3 新增：轮次恢复（Task-10，AC-017）=====

    /** 预置恢复 ctx：iterationCount=2（第 1 轮完整 + 第 2 轮暂停），第 2 轮评分已完成、修订未完成 */
    private WorkflowContext pausedAtRound2RevisionCtx(String round1Score, String round1Revision,
                                                      String round2Score) {
        WorkflowContext ctx = new WorkflowContext();
        ctx.incrementIteration();  // 第 1 轮
        ctx.incrementIteration();  // 第 2 轮（暂停轮）
        ctx.write("done:1:评分 Agent", round1Score);
        ctx.write("done:1:修订 Agent", round1Revision);
        ctx.write("done:2:评分 Agent", round2Score);
        return ctx;
    }

    @Test
    void execute_resume_atPausedRoundAgent_shouldSkipHistoricalRoundsAndPausedDoneAgents() throws Exception {
        // 恢复场景：第 2 轮修订 Agent 处暂停——第 1 轮整轮跳过、第 2 轮评分跳过，仅修订真实执行
        when(agentExecutor.executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any(), anyInt(), anyString()))
                .thenAnswer(inv -> "真实输出");

        WorkflowTemplate template = loopTemplate(5, ctx -> false);  // 退出条件不满足，聚焦跳过逻辑
        WorkflowExecution execution = runningExecution(template);
        execution.attachContext(pausedAtRound2RevisionCtx("第1轮评分", "第1轮修订", "第2轮评分"));

        strategy.execute(template, Map.of("draft", "初稿"), mock(SseEmitter.class),
                execution, null, new AtomicBoolean(false));

        // 真实执行：第 2 轮修订(1) + 第 3~5 轮全量(2×3=6) = 7 次；第 1 轮 2 步与第 2 轮评分跳过
        verify(agentExecutor, times(7)).executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any(), anyInt(), anyString());
        // iterationCount 恢复正确：暂停轮不重复递增（2 轮历史 + 3 轮新 = 5）
        assertEquals(5, execution.getIterationCount());
    }

    @Test
    void execute_resume_historicalRounds_shouldNotEvaluateExitCondition() throws Exception {
        // 恢复场景：ctx 状态已满足退出条件（score=90），历史完整轮不评估——暂停轮仍须执行
        when(agentExecutor.executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any(), anyInt(), anyString()))
                .thenAnswer(inv -> "真实输出");

        WorkflowTemplate template = loopTemplate(5, ctx -> ctx.readAsString("score").equals("90"));
        WorkflowExecution execution = runningExecution(template);
        WorkflowContext ctx = pausedAtRound2RevisionCtx("第1轮评分", "第1轮修订", "第2轮评分");
        ctx.write("score", "90");  // 恢复前状态已满足退出条件
        execution.attachContext(ctx);

        String result = strategy.execute(template, Map.of("draft", "初稿"), mock(SseEmitter.class),
                execution, null, new AtomicBoolean(false));

        // 若历史轮误评估 exitCondition 会提前退出（0 次调用）；正确行为：暂停轮修订执行 1 次后评估退出
        verify(agentExecutor, times(1)).executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any(), anyInt(), anyString());
        assertEquals("真实输出", result);
        assertEquals(2, execution.getIterationCount());
    }

    @Test
    void execute_resume_shouldPushLoopIterationPerRound() throws Exception {
        // loop_iteration 事件在恢复重放时按轮次正常推送；历史轮/暂停轮已完成 Agent 跳过推 step_skipped（前端进度完整）
        when(agentExecutor.executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any(), anyInt(), anyString()))
                .thenAnswer(inv -> "真实输出");

        WorkflowTemplate template = loopTemplate(2, ctx -> true);  // 暂停轮执行完即满足退出
        WorkflowExecution execution = runningExecution(template);
        execution.attachContext(pausedAtRound2RevisionCtx("第1轮评分", "第1轮修订", "第2轮评分"));

        SseEmitter emitter = mock(SseEmitter.class);
        strategy.execute(template, Map.of("draft", "初稿"), emitter, execution, null, new AtomicBoolean(false));

        // 事件数：workflow_start(1) + 轮1历史重放(loop_iteration + 2×step_skipped = 3)
        //        + 轮2暂停轮(loop_iteration + 评分 step_skipped + 修订 step_start/step_complete = 4)
        //        + workflow_complete(1) = 9；仅修订 Agent 真实执行
        verify(emitter, times(9)).send(any(SseEmitter.SseEventBuilder.class));
        verify(agentExecutor, times(1)).executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any(), anyInt(), anyString());
        assertEquals(2, execution.getIterationCount());
    }
}
