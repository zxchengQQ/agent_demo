package com.agentdemo.app.execution;

import com.agentdemo.app.adapter.AgenticAgentFactory;
import com.agentdemo.app.core.AgentDefinition;
import com.agentdemo.app.service.TestTokenStream;
import com.agentdemo.app.service.WorkflowCancelledException;
import com.agentdemo.app.service.WorkflowPausedException;
import com.agentdemo.app.service.WorkflowTimeoutException;
import com.agentdemo.app.template.ResearchAgent;
import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentExecutor 测试（P2 Task-04）
 * <p>
 * 业务含义：验证从 P1 WorkflowExecutionService 抽取的 Agent 执行逻辑
 * （构建 + 流式 + 重试 + 超时）与取消检查，行为与 P1 保持一致。
 * </p>
 */
class AgentExecutorTest {

    private AgenticAgentFactory agentFactory;
    private AgentExecutor executor;

    @BeforeEach
    void setUp() {
        agentFactory = mock(AgenticAgentFactory.class);
        executor = new AgentExecutor(agentFactory);
    }

    private AgentDefinition agentDef() {
        return AgentDefinition.builder()
                .name("研究 Agent")
                .description("d")
                .toolIds(List.of())
                .interfaceClass(ResearchAgent.class)
                .build();
    }

    /** 输出固定文本的 TokenStream */
    private TestTokenStream fixedStream(String fullText, String... tokens) {
        TestTokenStream stream = new TestTokenStream();
        for (String token : tokens) {
            stream.emitToken(token);
        }
        stream.completeWith(fullText);
        return stream;
    }

    @Test
    void executeWithRetry_shouldReturnFullOutput() throws Exception {
        ResearchAgent agent = mock(ResearchAgent.class);
        when(agent.execute(anyString())).thenReturn(fixedStream("完整结果"));
        when(agentFactory.buildAgent(any())).thenReturn(agent);

        SseEmitter emitter = mock(SseEmitter.class);
        String output = executor.executeWithRetry(agentDef(), "输入", emitter, 0, 0, null);

        assertEquals("完整结果", output);
        // 无 token 片段时（仅 completeWith），不推送 token 事件
        verify(agent, times(1)).execute(anyString());
    }

    @Test
    void executeWithRetry_shouldPushTokenEventsDuringStreaming() throws Exception {
        ResearchAgent agent = mock(ResearchAgent.class);
        when(agent.execute(anyString())).thenReturn(fixedStream("完整文本", "片", "段", "一"));
        when(agentFactory.buildAgent(any())).thenReturn(agent);

        SseEmitter emitter = mock(SseEmitter.class);
        executor.executeWithRetry(agentDef(), "输入", emitter, 0, 0, null);

        // 3 个 token 片段 → 推送 3 次 SSE 事件（每个 token 一次 send）
        verify(emitter, times(3)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void executeWithRetry_shouldPushStepRetryOnFirstFailureThenSucceed() throws Exception {
        ResearchAgent agent = mock(ResearchAgent.class);
        when(agent.execute(anyString()))
                .thenThrow(new RuntimeException("LLM 调用超时"))
                .thenReturn(fixedStream("成功结果"));
        when(agentFactory.buildAgent(any())).thenReturn(agent);

        SseEmitter emitter = mock(SseEmitter.class);
        String output = executor.executeWithRetry(agentDef(), "输入", emitter, 1, 3, null);

        assertEquals("成功结果", output);
        // 首次失败 + 1 次重试 = execute 调用 2 次
        verify(agent, times(2)).execute(anyString());
        // 首次失败推送 1 次 step_retry 事件（无 token），成功后不再推送事件
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void executeWithRetry_shouldPushStepErrorWhenRetriesExhausted() throws Exception {
        ResearchAgent agent = mock(ResearchAgent.class);
        when(agent.execute(anyString())).thenThrow(new RuntimeException("持续失败"));
        when(agentFactory.buildAgent(any())).thenReturn(agent);

        SseEmitter emitter = mock(SseEmitter.class);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> executor.executeWithRetry(agentDef(), "输入", emitter, 1, 3, null));
        assertEquals(ErrorCode.WORKFLOW_EXECUTION_FAILED, ex.getErrorCode());

        // 首次 + 3 次重试 = execute 调用 4 次
        verify(agent, times(4)).execute(anyString());
        // 每次失败推送 1 次事件：3 次 step_retry + 最后 1 次 step_error = 4 次 send
        verify(emitter, times(4)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void executeWithRetry_shouldUseConfiguredMaxRetries() throws Exception {
        ResearchAgent agent = mock(ResearchAgent.class);
        when(agent.execute(anyString())).thenThrow(new RuntimeException("持续失败"));
        when(agentFactory.buildAgent(any())).thenReturn(agent);

        SseEmitter emitter = mock(SseEmitter.class);
        assertThrows(BusinessException.class,
                () -> executor.executeWithRetry(agentDef(), "输入", emitter, 0, 5, null));
        // maxRetries=5 时：首次 + 5 次重试 = 6 次
        verify(agent, times(6)).execute(anyString());
    }

    @Test
    void executeWithRetry_errorMessage_shouldIncludeRootCause() throws Exception {
        // 业务含义：底层失败原因（如"未配置 chat 模型"/"LLM 连接超时"）必须在最终错误中可见，便于定位
        ResearchAgent agent = mock(ResearchAgent.class);
        when(agent.execute(anyString())).thenThrow(new RuntimeException("LLM 连接超时"));
        when(agentFactory.buildAgent(any())).thenReturn(agent);

        SseEmitter emitter = mock(SseEmitter.class);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> executor.executeWithRetry(agentDef(), "输入", emitter, 0, 0, null));
        assertTrue(ex.getMessage().contains("LLM 连接超时"),
                "错误信息应包含底层根因，实际: " + ex.getMessage());
    }

    @Test
    void checkCancelled_trueFlag_shouldThrow() {
        AtomicBoolean flag = new AtomicBoolean(true);
        assertThrows(WorkflowCancelledException.class, () -> AgentExecutor.checkCancelled(flag));
    }

    @Test
    void checkCancelled_falseOrNullFlag_shouldNotThrow() {
        assertDoesNotThrow(() -> AgentExecutor.checkCancelled(new AtomicBoolean(false)));
        assertDoesNotThrow(() -> AgentExecutor.checkCancelled(null));
    }

    @Test
    void executeWithRetry_timeout_shouldThrowWorkflowTimeout() {
        // 注入超时 0 分钟，Agent 永不完成 → future.get(0) 立即超时
        AgentExecutor zeroTimeoutExecutor = new AgentExecutor(agentFactory, 0);
        ResearchAgent agent = mock(ResearchAgent.class);
        when(agent.execute(anyString())).thenReturn(new TestTokenStream()); // 永不 complete
        when(agentFactory.buildAgent(any())).thenReturn(agent);

        SseEmitter emitter = mock(SseEmitter.class);
        assertThrows(WorkflowTimeoutException.class,
                () -> zeroTimeoutExecutor.executeWithRetry(agentDef(), "输入", emitter, 0, 0, null));
    }

    // ===== P3 新增：重试耗尽抛 WorkflowPausedException（AC-016）=====

    @Test
    void executeWithRetry_exhausted_shouldThrowWorkflowPausedException() throws Exception {
        // 业务含义：重试耗尽的信号从"全盘失败"变为"暂停待恢复"（AC-016）
        ResearchAgent agent = mock(ResearchAgent.class);
        when(agent.execute(anyString())).thenThrow(new RuntimeException("持续失败"));
        when(agentFactory.buildAgent(any())).thenReturn(agent);

        SseEmitter emitter = mock(SseEmitter.class);
        WorkflowPausedException ex = assertThrows(WorkflowPausedException.class,
                () -> executor.executeWithRetry(agentDef(), "输入", emitter, 1, 1, null));

        assertEquals("研究 Agent", ex.getFailedAgentName());
        assertEquals(1, ex.getFailedIndex());
        // maxRetries=1：首次 + 1 次重试 = 2 次
        verify(agent, times(2)).execute(anyString());
    }

    @Test
    void executeWithRetry_pausedException_messageShouldContainRootCause() throws Exception {
        ResearchAgent agent = mock(ResearchAgent.class);
        when(agent.execute(anyString())).thenThrow(new RuntimeException("LLM 连接超时"));
        when(agentFactory.buildAgent(any())).thenReturn(agent);

        SseEmitter emitter = mock(SseEmitter.class);
        WorkflowPausedException ex = assertThrows(WorkflowPausedException.class,
                () -> executor.executeWithRetry(agentDef(), "输入", emitter, 2, 0, null));
        // 延续上轮 BUG 修复：错误信息携带根因便于定位
        assertTrue(ex.getMessage().contains("研究 Agent"), "应含 Agent 名，实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("LLM 连接超时"), "应含根因，实际: " + ex.getMessage());
    }

    @Test
    void executeWithRetry_exhausted_retryAndErrorEventsUnchanged() throws Exception {
        // 行为不变：重试期间推 step_retry，耗尽时推 step_error（AC-015 兼容）
        ResearchAgent agent = mock(ResearchAgent.class);
        when(agent.execute(anyString())).thenThrow(new RuntimeException("持续失败"));
        when(agentFactory.buildAgent(any())).thenReturn(agent);

        SseEmitter emitter = mock(SseEmitter.class);
        assertThrows(WorkflowPausedException.class,
                () -> executor.executeWithRetry(agentDef(), "输入", emitter, 1, 2, null));

        // maxRetries=2：2 次 step_retry + 1 次 step_error = 3 次 send（与 P2 行为一致）
        verify(emitter, times(3)).send(any(SseEmitter.SseEventBuilder.class));
    }
}
