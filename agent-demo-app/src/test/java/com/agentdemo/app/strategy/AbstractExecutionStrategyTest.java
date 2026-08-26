package com.agentdemo.app.strategy;

import com.agentdemo.app.core.AgentDefinition;
import com.agentdemo.app.core.OrchestrationMode;
import com.agentdemo.app.core.WorkflowContext;
import com.agentdemo.app.core.WorkflowExecution;
import com.agentdemo.app.core.WorkflowTemplate;
import com.agentdemo.app.execution.AgentExecutor;
import com.agentdemo.app.execution.WorkflowEventPublisher;
import com.agentdemo.app.service.WorkflowCancelledException;
import com.agentdemo.app.service.WorkflowHITLState;
import com.agentdemo.app.template.ResearchAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AbstractExecutionStrategy 恢复基础设施测试（P3 Task-04）
 * <p>
 * 业务含义：验证断点续执行的公共底座——attachOrNewContext（取或建 ctx）、
 * resumeKey（统一恢复 key 规则）、executeOrSkip（跳过已完成步骤，AC-017 前置）。
 * </p>
 */
class AbstractExecutionStrategyTest {

    private AgentExecutor agentExecutor;
    private TestableStrategy strategy;
    private MockedStatic<WorkflowEventPublisher> publisherMock;

    /** 测试用具体子类（protected 方法可测） */
    static class TestableStrategy extends AbstractExecutionStrategy {
        TestableStrategy(AgentExecutor agentExecutor) {
            super(agentExecutor);
        }

        @Override
        public OrchestrationMode supportedMode() {
            return OrchestrationMode.SEQUENTIAL;
        }

        @Override
        public String execute(WorkflowTemplate template, Map<String, Object> params,
                              SseEmitter emitter, WorkflowExecution execution,
                              String modelId, AtomicBoolean cancelFlag) {
            return "";
        }

        // 暴露 protected 方法供测试
        String callExecuteOrSkip(AgentDefinition agentDef, String input, int iteration,
                                 WorkflowContext ctx, SseEmitter emitter, WorkflowExecution execution,
                                 int maxRetries, String modelId, AtomicBoolean cancelFlag, int agentIndex) {
            return executeOrSkip(agentDef, input, iteration, ctx, emitter, execution,
                    maxRetries, modelId, cancelFlag, agentIndex);
        }
    }

    @BeforeEach
    void setUp() {
        agentExecutor = mock(AgentExecutor.class);
        strategy = new TestableStrategy(agentExecutor);
        publisherMock = mockStatic(WorkflowEventPublisher.class);
    }

    @AfterEach
    void tearDown() {
        publisherMock.close();
    }

    private AgentDefinition agentDef() {
        return AgentDefinition.builder()
                .name("研究 Agent").description("d")
                .toolIds(List.of()).interfaceClass(ResearchAgent.class)
                .build();
    }

    private WorkflowExecution runningExecution() {
        WorkflowExecution execution = new WorkflowExecution("exec-1", "tpl-1", "模板");
        execution.start();
        return execution;
    }

    // ===== attachOrNewContext =====

    @Test
    void attachOrNewContext_noContext_shouldCreateAndAttach() {
        WorkflowExecution execution = runningExecution();
        WorkflowContext ctx1 = strategy.attachOrNewContext(execution);
        WorkflowContext ctx2 = strategy.attachOrNewContext(execution);
        // 两次调用返回同一实例（已挂载后不再新建）
        assertSame(ctx1, ctx2);
        assertSame(ctx1, execution.getContext());
    }

    @Test
    void attachOrNewContext_existingContext_shouldReturnWithoutOverwrite() {
        WorkflowExecution execution = runningExecution();
        WorkflowContext existing = new WorkflowContext();
        execution.attachContext(existing);
        WorkflowContext ctx = strategy.attachOrNewContext(execution);
        assertSame(existing, ctx);
    }

    // ===== resumeKey =====

    @Test
    void resumeKey_shouldFollowUnifiedRule() {
        assertEquals("done:0:研究 Agent", AbstractExecutionStrategy.resumeKey(0, "研究 Agent"));
        assertEquals("done:2:评分 Agent", AbstractExecutionStrategy.resumeKey(2, "评分 Agent"));
    }

    // ===== executeOrSkip =====

    @Test
    void executeOrSkip_keyHit_shouldSkipAndEmitStepSkipped() throws Exception {
        WorkflowExecution execution = runningExecution();
        WorkflowContext ctx = strategy.attachOrNewContext(execution);
        ctx.write("done:0:研究 Agent", "历史输出1");
        SseEmitter emitter = mock(SseEmitter.class);

        String result = strategy.callExecuteOrSkip(agentDef(), "输入", 0, ctx, emitter,
                execution, 0, null, new AtomicBoolean(false), 0);

        assertEquals("历史输出1", result);
        // Agent 未真实执行
        verify(agentExecutor, never()).executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any(), anyInt(), anyString());
        // 推送 step_skipped 事件，data 含 agentIndex/agentName/reason
        publisherMock.verify(() -> WorkflowEventPublisher.send(eq(emitter), eq("step_skipped"), any()));
    }

    @Test
    void executeOrSkip_keyMiss_shouldExecuteAndWriteResumeKey() throws Exception {
        when(agentExecutor.executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any(), anyInt(), anyString()))
                .thenReturn("新输出");
        WorkflowExecution execution = runningExecution();
        WorkflowContext ctx = strategy.attachOrNewContext(execution);
        SseEmitter emitter = mock(SseEmitter.class);

        String result = strategy.callExecuteOrSkip(agentDef(), "输入", 0, ctx, emitter,
                execution, 1, "model-1", new AtomicBoolean(false), 0);

        assertEquals("新输出", result);
        verify(agentExecutor, times(1)).executeWithRetry(any(), eq("输入"), any(), eq(0), eq(1), eq("model-1"), anyInt(), anyString());
        // 恢复 key 写入 + lastOutput 更新 + agentOutputs 记录
        assertEquals("新输出", ctx.read("done:0:研究 Agent"));
        assertEquals("新输出", ctx.readAsString("lastOutput"));
        assertEquals("新输出", ctx.getOutput("研究 Agent"));
        // 步骤记录到 execution
        assertEquals(1, execution.getSteps().size());
    }

    @Test
    void executeOrSkip_emptyStringOutput_shouldAlsoSkip() throws Exception {
        // 业务含义：空输出也是"已完成"（AC-021 空输出兼容），恢复时跳过不重跑
        WorkflowExecution execution = runningExecution();
        WorkflowContext ctx = strategy.attachOrNewContext(execution);
        ctx.write("done:0:研究 Agent", "");
        SseEmitter emitter = mock(SseEmitter.class);

        String result = strategy.callExecuteOrSkip(agentDef(), "输入", 0, ctx, emitter,
                execution, 0, null, new AtomicBoolean(false), 0);

        assertEquals("", result);
        verify(agentExecutor, never()).executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any(), anyInt(), anyString());
    }

    @Test
    void executeOrSkip_cancelFlagTrue_shouldThrowBeforeSkipCheck() {
        // 业务含义：用户终止优先于跳过判定——恢复流中用户点终止也应立即停止
        WorkflowExecution execution = runningExecution();
        WorkflowContext ctx = strategy.attachOrNewContext(execution);
        ctx.write("done:0:研究 Agent", "历史输出");
        SseEmitter emitter = mock(SseEmitter.class);

        assertThrows(WorkflowCancelledException.class,
                () -> strategy.callExecuteOrSkip(agentDef(), "输入", 0, ctx, emitter,
                        execution, 0, null, new AtomicBoolean(true), 0));
    }

    @Test
    void executeOrSkip_keyHit_shouldUpdateLastOutput() throws Exception {
        // 业务含义：跳过分支也须同步 lastOutput——循环模式的退出条件谓词在轮末读
        // lastOutput 判定，若暂停轮最后 Agent 被跳过而不更新，谓词会误读参数值导致提前退出
        WorkflowExecution execution = runningExecution();
        WorkflowContext ctx = strategy.attachOrNewContext(execution);
        ctx.write("lastOutput", "初始参数值");
        ctx.write("done:0:研究 Agent", "历史输出1");
        SseEmitter emitter = mock(SseEmitter.class);

        strategy.callExecuteOrSkip(agentDef(), "输入", 0, ctx, emitter,
                execution, 0, null, new AtomicBoolean(false), 0);

        assertEquals("历史输出1", ctx.readAsString("lastOutput"));
    }

    @Test
    void executeOrSkip_keyHit_stepSkippedDataShouldContainFields() throws Exception {
        WorkflowExecution execution = runningExecution();
        WorkflowContext ctx = strategy.attachOrNewContext(execution);
        ctx.write("done:0:研究 Agent", "历史输出1");
        SseEmitter emitter = mock(SseEmitter.class);

        strategy.callExecuteOrSkip(agentDef(), "输入", 0, ctx, emitter,
                execution, 0, null, new AtomicBoolean(false), 0);

        // 验证 step_skipped 的 data 字段：agentIndex/agentName/reason=断点恢复
        publisherMock.verify(() -> WorkflowEventPublisher.send(eq(emitter), eq("step_skipped"),
                org.mockito.ArgumentMatchers.argThat(data -> {
                    if (!(data instanceof Map<?, ?> map)) {
                        return false;
                    }
                    return Integer.valueOf(0).equals(map.get("agentIndex"))
                            && "研究 Agent".equals(map.get("agentName"))
                            && "断点恢复".equals(map.get("reason"));
                })));
    }

    @Test
    void executeOrSkip_executePath_shouldPushStepStartAndComplete() throws Exception {
        when(agentExecutor.executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any(), anyInt(), anyString()))
                .thenReturn("输出");
        WorkflowExecution execution = runningExecution();
        WorkflowContext ctx = strategy.attachOrNewContext(execution);
        SseEmitter emitter = mock(SseEmitter.class);

        strategy.callExecuteOrSkip(agentDef(), "输入", 0, ctx, emitter,
                execution, 0, null, new AtomicBoolean(false), 0);

        publisherMock.verify(() -> WorkflowEventPublisher.send(eq(emitter), eq("step_start"), any()));
        publisherMock.verify(() -> WorkflowEventPublisher.send(eq(emitter), eq("step_complete"), any()));
        assertTrue(execution.getSteps().get(0).getDurationMs() >= 0);
    }

    // ===== HITL 恢复 key（Task-08）=====

    @Test
    void hitlResumeKey_shouldFollowUnifiedRule() {
        assertEquals("hitl:0:研究 Agent", AbstractExecutionStrategy.hitlResumeKey(0, "研究 Agent"));
        assertEquals("hitl:2:评分 Agent", AbstractExecutionStrategy.hitlResumeKey(2, "评分 Agent"));
    }

    @Test
    void executeOrSkip_hitlResumeKeyHit_应恢复执行暂停步并写完成key() throws Exception {
        // 业务含义：done key 未命中但 hitl key 命中（用户已回复的 HITL 暂停步）——
        // 以恢复方式执行（executeHitlResume）而非正常 executeWithRetry，避免重放死循环（AC-N03）
        WorkflowHITLState hitlState = new WorkflowHITLState(WorkflowHITLState.MODE_ASK_USER,
                new WorkflowHITLState.AskUserData("text", "请确认输入？", List.of(), 0),
                new WorkflowHITLState.PendingStep(0, "研究 Agent", "输入", 0), List.of(), 0);
        WorkflowHITLState.HitlResume resume = new WorkflowHITLState.HitlResume(hitlState, "用户回复", null);
        when(agentExecutor.executeHitlResume(any(), any(), anyString(), any(), any(), anyInt(), anyString()))
                .thenReturn("恢复输出");

        WorkflowExecution execution = runningExecution();
        WorkflowContext ctx = strategy.attachOrNewContext(execution);
        ctx.write("hitl:0:研究 Agent", resume);
        SseEmitter emitter = mock(SseEmitter.class);

        String result = strategy.callExecuteOrSkip(agentDef(), "输入", 0, ctx, emitter,
                execution, 0, null, new AtomicBoolean(false), 0);

        assertEquals("恢复输出", result);
        // 以恢复方式执行（携带用户回复 + askUser 快照），不走正常重试路径
        verify(agentExecutor).executeHitlResume(any(), any(), eq("用户回复"), isNull(), any(), anyInt(), anyString());
        verify(agentExecutor, never()).executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any(), anyInt(), anyString());
        // 恢复完成写 done key + 清除 hitl key + lastOutput 同步
        assertEquals("恢复输出", ctx.read("done:0:研究 Agent"));
        assertNull(ctx.read("hitl:0:研究 Agent"));
        assertEquals("恢复输出", ctx.readAsString("lastOutput"));
        // 步骤记录 + 事件协议与正常执行一致
        assertEquals(1, execution.getSteps().size());
        publisherMock.verify(() -> WorkflowEventPublisher.send(eq(emitter), eq("step_start"), any()));
        publisherMock.verify(() -> WorkflowEventPublisher.send(eq(emitter), eq("step_complete"), any()));
    }
}
