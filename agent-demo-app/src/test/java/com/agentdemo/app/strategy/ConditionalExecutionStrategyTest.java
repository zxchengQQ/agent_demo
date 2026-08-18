package com.agentdemo.app.strategy;

import com.agentdemo.app.core.AgentDefinition;
import com.agentdemo.app.core.BranchDefinition;
import com.agentdemo.app.core.OrchestrationMode;
import com.agentdemo.app.core.ParameterDefinition;
import com.agentdemo.app.core.WorkflowContext;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 条件分支编排策略测试（P2 Task-08）
 * <p>
 * 业务含义：验证根据条件谓词选择分支、仅执行匹配分支的 Agent（AC-005）。
 * </p>
 */
class ConditionalExecutionStrategyTest {

    private AgentExecutor agentExecutor;
    private ConditionalExecutionStrategy strategy;

    @BeforeEach
    void setUp() {
        agentExecutor = mock(AgentExecutor.class);
        strategy = new ConditionalExecutionStrategy(agentExecutor);
    }

    private AgentDefinition agentDef(String name) {
        return AgentDefinition.builder().name(name).toolIds(List.of()).interfaceClass(ResearchAgent.class).build();
    }

    private WorkflowTemplate routingTemplate() {
        return WorkflowTemplate.builder()
                .id("tpl-route")
                .name("智能路由")
                .mode(OrchestrationMode.CONDITIONAL)
                .maxRetries(0)
                .parameters(List.of())
                .branches(List.of(
                        BranchDefinition.builder().name("简单回答").conditionDescription("问题简单")
                                .condition(ctx -> ctx.readAsString("question").length() <= 10)
                                .agents(List.of(agentDef("简单 Agent"))).build(),
                        BranchDefinition.builder().name("复杂拆解").conditionDescription("问题复杂")
                                .condition(ctx -> ctx.readAsString("question").length() > 10)
                                .agents(List.of(agentDef("研究 Agent"), agentDef("总结 Agent"))).build()))
                .build();
    }

    private WorkflowExecution runningExecution(WorkflowTemplate template) {
        WorkflowExecution execution = new WorkflowExecution("exec-c", template.getId(), template.getName());
        execution.start();
        return execution;
    }

    @Test
    void supportedMode_shouldReturnConditional() {
        assertEquals(OrchestrationMode.CONDITIONAL, strategy.supportedMode());
    }

    @Test
    void execute_shouldRunOnlyFirstMatchingBranch() throws Exception {
        // 问题短 → 命中"简单回答"分支（第一个条件满足）
        when(agentExecutor.executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any()))
                .thenReturn("简单回答内容");

        WorkflowTemplate template = routingTemplate();
        WorkflowExecution execution = runningExecution(template);
        SseEmitter emitter = mock(SseEmitter.class);

        String result = strategy.execute(template, Map.of("question", "现在几点"), emitter, execution, null, new AtomicBoolean(false));

        assertEquals("简单回答内容", result);
        // 仅执行 1 个 Agent（简单分支），不执行复杂分支的 2 个 Agent
        verify(agentExecutor, times(1)).executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any());
    }

    @Test
    void execute_shouldRunSecondBranchWhenFirstNotMatch() throws Exception {
        // 问题长 → 分支1 不满足，命中分支2（复杂拆解，2 个 Agent）
        when(agentExecutor.executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any()))
                .thenReturn("研究结果", "总结结果");

        WorkflowTemplate template = routingTemplate();
        WorkflowExecution execution = runningExecution(template);
        SseEmitter emitter = mock(SseEmitter.class);

        String result = strategy.execute(template, Map.of("question", "如何设计一个高性能的多 Agent 编排系统"),
                emitter, execution, null, new AtomicBoolean(false));

        // 执行 2 个 Agent（研究 + 总结）
        verify(agentExecutor, times(2)).executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any());
        assertEquals("总结结果", result);
    }

    @Test
    void execute_noBranchMatch_shouldReturnEmpty() throws Exception {
        // 分支条件都不满足（question 长度恰好 10 但... 用空字符串）
        WorkflowTemplate template = routingTemplate();
        WorkflowExecution execution = runningExecution(template);
        SseEmitter emitter = mock(SseEmitter.class);

        // 构造都不匹配的条件：空字符串长度 0 <= 10 会命中分支1... 改为所有分支 false
        WorkflowTemplate noMatchTemplate = WorkflowTemplate.builder()
                .id("tpl-route-none")
                .name("无匹配")
                .mode(OrchestrationMode.CONDITIONAL)
                .maxRetries(0)
                .parameters(List.of())
                .branches(List.of(
                        BranchDefinition.builder().name("分支A").condition(ctx -> false).agents(List.of(agentDef("A"))).build(),
                        BranchDefinition.builder().name("分支B").condition(ctx -> false).agents(List.of(agentDef("B"))).build()))
                .build();

        String result = strategy.execute(noMatchTemplate, Map.of(), emitter, execution, null, new AtomicBoolean(false));

        // 不执行任何 Agent，返回空字符串
        verify(agentExecutor, never()).executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any());
        assertEquals("", result);
    }

    @Test
    void execute_cancelFlagTrue_shouldThrowCancelled() {
        WorkflowTemplate template = routingTemplate();
        WorkflowExecution execution = runningExecution(template);
        SseEmitter emitter = mock(SseEmitter.class);

        assertThrows(WorkflowCancelledException.class,
                () -> strategy.execute(template, Map.of("question", "X"), emitter, execution, null, new AtomicBoolean(true)));
    }

    @Test
    void execute_shouldPassUserInputToFirstAgent_notEmpty() throws Exception {
        // BUG 复现：用户输入的 question 参数应传递给第一个 Agent，而非空字符串
        when(agentExecutor.executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any()))
                .thenReturn("回答内容");

        WorkflowTemplate template = WorkflowTemplate.builder()
                .id("tpl-route-input")
                .name("智能路由")
                .mode(OrchestrationMode.CONDITIONAL)
                .maxRetries(0)
                .parameters(List.of(
                        ParameterDefinition.builder().name("question").type("string").required(true).description("用户问题").build()
                ))
                .branches(List.of(
                        BranchDefinition.builder().name("简单回答").conditionDescription("问题简单")
                                .condition(ctx -> ctx.readAsString("question").length() <= 100)
                                .agents(List.of(agentDef("简单 Agent"))).build()))
                .build();

        WorkflowExecution execution = runningExecution(template);
        SseEmitter emitter = mock(SseEmitter.class);

        strategy.execute(template, Map.of("question", "什么是 Spring Boot"), emitter, execution, null, new AtomicBoolean(false));

        ArgumentCaptor<String> inputCaptor = ArgumentCaptor.forClass(String.class);
        verify(agentExecutor).executeWithRetry(any(), inputCaptor.capture(), any(), anyInt(), anyInt(), any());
        assertNotEquals("", inputCaptor.getValue(), "Agent 输入不应为空字符串");
        assertEquals("什么是 Spring Boot", inputCaptor.getValue(),
                "Agent 应收到用户输入的 question 参数值");
    }

    // ===== P3 新增：断点恢复跳过（Task-09，AC-017）=====

    @Test
    void execute_firstRun_shouldWriteResumeKeys() throws Exception {
        // 首次执行：命中分支的 Agent 写入 done keys（为下次恢复做准备）
        when(agentExecutor.executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any()))
                .thenReturn("简单回答内容");

        WorkflowTemplate template = routingTemplate();
        WorkflowExecution execution = runningExecution(template);

        strategy.execute(template, Map.of("question", "现在几点"), mock(SseEmitter.class),
                execution, null, new AtomicBoolean(false));

        WorkflowContext ctx = execution.getContext();
        org.junit.jupiter.api.Assertions.assertNotNull(ctx, "首次执行后 ctx 应挂载到 execution");
        assertEquals("简单回答内容", ctx.read("done:0:简单 Agent"));
    }

    @Test
    void execute_resumeWithBranchAgentDone_shouldSkipAndReturnHistory() throws Exception {
        // 恢复场景：ctx 预置 done:0:简单 Agent="快答历史"（简单分支唯一 Agent 已完成），
        // question 满足简单分支条件（确定性谓词重放，与首次执行走同一分支）
        WorkflowTemplate template = routingTemplate();
        WorkflowExecution execution = runningExecution(template);
        WorkflowContext ctx = new WorkflowContext();
        ctx.write("done:0:简单 Agent", "快答历史");
        execution.attachContext(ctx);

        String result = strategy.execute(template, Map.of("question", "现在几点"),
                mock(SseEmitter.class), execution, null, new AtomicBoolean(false));

        // 简单 Agent 不被调用，直接返回历史输出
        verify(agentExecutor, never()).executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any());
        assertEquals("快答历史", result);
    }

    @Test
    void execute_resumeBranchReplay_shouldFollowSameBranchAsFirstRun() throws Exception {
        // 恢复场景：复杂分支第 1 个 Agent（研究）已完成，question 为长文本
        // 分支条件重放一致（仍走复杂分支）：研究跳过、总结收到历史输出并执行
        when(agentExecutor.executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any()))
                .thenReturn("总结结果");

        WorkflowTemplate template = routingTemplate();
        WorkflowExecution execution = runningExecution(template);
        WorkflowContext ctx = new WorkflowContext();
        ctx.write("done:0:研究 Agent", "研究历史");
        execution.attachContext(ctx);

        String result = strategy.execute(template, Map.of("question", "如何设计一个高性能的多 Agent 编排系统"),
                mock(SseEmitter.class), execution, null, new AtomicBoolean(false));

        // 仅总结 Agent 执行（1 次），研究 Agent 跳过
        verify(agentExecutor, times(1)).executeWithRetry(any(), anyString(), any(), anyInt(), anyInt(), any());
        assertEquals("总结结果", result);

        // 跳过的研究历史输出作为总结 Agent 输入
        ArgumentCaptor<String> inputCaptor = ArgumentCaptor.forClass(String.class);
        verify(agentExecutor).executeWithRetry(any(), inputCaptor.capture(), any(), anyInt(), anyInt(), any());
        assertEquals("研究历史", inputCaptor.getValue());
    }
}
