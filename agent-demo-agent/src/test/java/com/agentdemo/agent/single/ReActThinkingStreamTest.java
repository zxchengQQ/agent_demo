package com.agentdemo.agent.single;

import com.agentdemo.agent.core.ThinkingTokenStream;
import com.agentdemo.llm.thinking.ThinkingStreamHandler;
import com.agentdemo.llm.thinking.ThinkingStreamingChatModel;
import com.agentdemo.llm.thinking.ToolCall;
import com.agentdemo.tools.registry.ToolExecutor;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReActThinkingStream 测试（Task-07）
 * <p>
 * 验证标准来源：Task-07 验证标准
 * 关联 AC：AC-001（ReAct 循环启动）、AC-002（内部推理推送）、AC-003（Thought 推送）、
 *         AC-004（工具调用 action）、AC-005（工具结果 observation）、AC-006（循环终止）、
 *         AC-008（无需工具调用）、AC-011（强制总结）、AC-012（工具失败回填）、AC-022（串行工具调用）
 * </p>
 */
class ReActThinkingStreamTest {

    private ThinkingStreamingChatModel model;
    private ToolExecutor toolExecutor;

    @BeforeEach
    void setUp() {
        model = mock(ThinkingStreamingChatModel.class);
        toolExecutor = mock(ToolExecutor.class);
    }

    /**
     * 模拟单轮 LLM 调用（finish_reason=stop，无工具调用）
     * 业务含义：LLM 直接给出最终回答，不调用工具
     */
    private Object mockSingleRoundStop(InvocationOnMock invocation) {
        ThinkingStreamHandler handler = invocation.getArgument(2);
        handler.onPartialThinking("正在分析");
        handler.onPartialResponse("你好");
        handler.onComplete("你好", "stop", null);
        return null;
    }

    /**
     * 模拟单轮 LLM 调用（finish_reason=tool_calls，有工具调用）
     */
    private Object mockSingleRoundToolCalls(InvocationOnMock invocation, String toolName, String args) {
        ThinkingStreamHandler handler = invocation.getArgument(2);
        handler.onPartialThinking("需要查时间");
        handler.onPartialResponse("Thought: 用户问时间");

        ToolCall tc = new ToolCall();
        tc.setId("call_001");
        tc.setFunctionName(toolName);
        tc.setArguments(args);

        handler.onToolCalls(Collections.singletonList(tc));
        handler.onComplete("Thought: 用户问时间", "tool_calls", null);
        return null;
    }

    // ========== 验证标准：单轮无工具调用 ==========

    /**
     * 验证单轮无工具调用：onFinalAnswer 触发，onComplete 触发，无 onAction/onObservation
     */
    @Test
    void singleRoundNoToolShouldTriggerFinalAnswerAndComplete() {
        doAnswer(this::mockSingleRoundStop).when(model).stream(any(), any(), any());

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("系统提示"));
        messages.add(UserMessage.from("你好"));

        ReActThinkingStream stream = new ReActThinkingStream(model, messages, "[]", toolExecutor, 8);

        ThinkingTokenStream.ThinkingConsumer thinkingConsumer = mock(ThinkingTokenStream.ThinkingConsumer.class);
        ThinkingTokenStream.ThoughtConsumer thoughtConsumer = mock(ThinkingTokenStream.ThoughtConsumer.class);
        ThinkingTokenStream.FinalAnswerConsumer finalAnswerConsumer = mock(ThinkingTokenStream.FinalAnswerConsumer.class);
        ThinkingTokenStream.CompleteConsumer completeConsumer = mock(ThinkingTokenStream.CompleteConsumer.class);

        stream.onPartialThinking(thinkingConsumer);
        stream.onPartialThought(thoughtConsumer);
        stream.onFinalAnswer(finalAnswerConsumer);
        stream.onComplete(completeConsumer);
        stream.start();

        verify(thinkingConsumer).accept("正在分析");
        verify(thoughtConsumer).accept("你好", 1);
        verify(finalAnswerConsumer).accept(1);
        verify(completeConsumer).accept("你好");
    }

    // ========== 验证标准：单轮有工具调用 ==========

    /**
     * 验证单轮有工具调用：onAction 触发，onObservation 触发，第二轮 onFinalAnswer
     */
    @Test
    void toolCallShouldTriggerActionAndObservation() {
        // 第一轮返回 tool_calls，第二轮返回 stop（用计数器区分）
        int[] callCount = {0};
        doAnswer(invocation -> {
            callCount[0]++;
            if (callCount[0] == 1) {
                mockSingleRoundToolCalls(invocation, "getCurrentTime", "{}");
            } else {
                mockSingleRoundStop(invocation);
            }
            return null;
        }).when(model).stream(any(), any(), any());

        when(toolExecutor.execute("getCurrentTime", "{}")).thenReturn("2026-07-22 16:00:00");

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("系统提示"));
        messages.add(UserMessage.from("几点了"));

        ReActThinkingStream stream = new ReActThinkingStream(model, messages, "[tools]", toolExecutor, 8);

        ThinkingTokenStream.ActionConsumer actionConsumer = mock(ThinkingTokenStream.ActionConsumer.class);
        ThinkingTokenStream.ObservationConsumer observationConsumer = mock(ThinkingTokenStream.ObservationConsumer.class);
        ThinkingTokenStream.FinalAnswerConsumer finalAnswerConsumer = mock(ThinkingTokenStream.FinalAnswerConsumer.class);
        ThinkingTokenStream.CompleteConsumer completeConsumer = mock(ThinkingTokenStream.CompleteConsumer.class);

        stream.onAction(actionConsumer);
        stream.onObservation(observationConsumer);
        stream.onFinalAnswer(finalAnswerConsumer);
        stream.onComplete(completeConsumer);
        stream.start();

        // 验证 action 事件（第1轮）
        verify(actionConsumer).accept("getCurrentTime", "{}", 1);
        // 验证 observation 事件（第1轮）
        verify(observationConsumer).accept("2026-07-22 16:00:00", 1);
        // 验证 final-answer 在第2轮触发
        verify(finalAnswerConsumer).accept(2);
        verify(completeConsumer).accept("你好");
    }

    // ========== 验证标准：多个 tool_calls 串行执行 ==========

    /**
     * 验证多个 tool_calls 串行执行：onAction/onObservation 按顺序各回调 2 次
     */
    @Test
    void multipleToolCallsShouldExecuteSequentially() {
        doAnswer(invocation -> {
            ThinkingStreamHandler handler = invocation.getArgument(2);
            handler.onPartialResponse("Thought: 需要两个工具");

            ToolCall tc1 = new ToolCall();
            tc1.setId("call_001");
            tc1.setFunctionName("calculate");
            tc1.setArguments("{\"expression\":\"1+1\"}");

            ToolCall tc2 = new ToolCall();
            tc2.setId("call_002");
            tc2.setFunctionName("getCurrentTime");
            tc2.setArguments("{}");

            handler.onToolCalls(List.of(tc1, tc2));
            handler.onComplete("Thought: 需要两个工具", "tool_calls", null);
            return null;
        }).when(model).stream(any(), anyString(), any());

        doAnswer(this::mockSingleRoundStop).when(model).stream(any(), eq(null), any());

        when(toolExecutor.execute("calculate", "{\"expression\":\"1+1\"}")).thenReturn("1+1 = 2");
        when(toolExecutor.execute("getCurrentTime", "{}")).thenReturn("2026-07-22 16:00:00");

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("计算并查时间"));

        ReActThinkingStream stream = new ReActThinkingStream(model, messages, "[tools]", toolExecutor, 8);

        ThinkingTokenStream.ActionConsumer actionConsumer = mock(ThinkingTokenStream.ActionConsumer.class);
        ThinkingTokenStream.ObservationConsumer observationConsumer = mock(ThinkingTokenStream.ObservationConsumer.class);

        stream.onAction(actionConsumer);
        stream.onObservation(observationConsumer);
        stream.onFinalAnswer(mock(ThinkingTokenStream.FinalAnswerConsumer.class));
        stream.onComplete(mock(ThinkingTokenStream.CompleteConsumer.class));
        stream.start();

        // 验证串行执行顺序
        verify(actionConsumer).accept("calculate", "{\"expression\":\"1+1\"}", 1);
        verify(actionConsumer).accept("getCurrentTime", "{}", 1);
        verify(observationConsumer).accept("1+1 = 2", 1);
        verify(observationConsumer).accept("2026-07-22 16:00:00", 1);
    }

    // ========== 验证标准：工具执行失败 ==========

    /**
     * 验证工具执行失败：observation 收到错误信息，循环不中断
     */
    @Test
    void toolFailureShouldReturnErrorAsObservation() {
        int[] callCount = {0};
        doAnswer(invocation -> {
            callCount[0]++;
            if (callCount[0] == 1) {
                mockSingleRoundToolCalls(invocation, "failingTool", "{}");
            } else {
                mockSingleRoundStop(invocation);
            }
            return null;
        }).when(model).stream(any(), any(), any());

        when(toolExecutor.execute("failingTool", "{}")).thenReturn("工具执行失败: 模拟错误");

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("调用失败工具"));

        ReActThinkingStream stream = new ReActThinkingStream(model, messages, "[tools]", toolExecutor, 8);

        ThinkingTokenStream.ObservationConsumer observationConsumer = mock(ThinkingTokenStream.ObservationConsumer.class);
        ThinkingTokenStream.CompleteConsumer completeConsumer = mock(ThinkingTokenStream.CompleteConsumer.class);

        stream.onObservation(observationConsumer);
        stream.onFinalAnswer(mock(ThinkingTokenStream.FinalAnswerConsumer.class));
        stream.onComplete(completeConsumer);
        stream.start();

        // 验证 observation 收到错误信息
        verify(observationConsumer).accept("工具执行失败: 模拟错误", 1);
        // 验证循环继续，最终完成
        verify(completeConsumer).accept("你好");
    }

    // ========== 验证标准：达到 maxIterations 强制总结 ==========

    /**
     * 验证达到 maxIterations 时：调用 model.stream 不带 tools，推送 final-answer
     */
    @Test
    void maxIterationsShouldForceSummarize() {
        // 每轮都返回 tool_calls，直到不带 tools 时返回 stop
        doAnswer(invocation -> {
            String toolsJson = invocation.getArgument(1);
            if (toolsJson != null) {
                mockSingleRoundToolCalls(invocation, "calculate", "{}");
            } else {
                mockSingleRoundStop(invocation);
            }
            return null;
        }).when(model).stream(any(), any(), any());

        when(toolExecutor.execute("calculate", "{}")).thenReturn("结果");

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("无限循环"));

        // maxIterations=2，第3轮强制总结（不带 tools）
        ReActThinkingStream stream = new ReActThinkingStream(model, messages, "[tools]", toolExecutor, 2);

        ThinkingTokenStream.FinalAnswerConsumer finalAnswerConsumer = mock(ThinkingTokenStream.FinalAnswerConsumer.class);
        ThinkingTokenStream.CompleteConsumer completeConsumer = mock(ThinkingTokenStream.CompleteConsumer.class);

        stream.onAction(mock(ThinkingTokenStream.ActionConsumer.class));
        stream.onObservation(mock(ThinkingTokenStream.ObservationConsumer.class));
        stream.onFinalAnswer(finalAnswerConsumer);
        stream.onComplete(completeConsumer);
        stream.start();

        // 验证 final-answer 在第3轮触发（iteration 3 = maxIterations + 1）
        verify(finalAnswerConsumer).accept(3);
        verify(completeConsumer).accept("你好");
    }

    // ========== 验证标准：LLM 调用异常 ==========

    /**
     * 验证 LLM 调用异常时：onError 被回调，循环终止
     */
    @Test
    void llmErrorShouldTriggerOnError() {
        RuntimeException exception = new RuntimeException("LLM 连接失败");
        doAnswer(invocation -> {
            ThinkingStreamHandler handler = invocation.getArgument(2);
            handler.onError(exception);
            return null;
        }).when(model).stream(any(), any(), any());

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("触发错误"));

        ReActThinkingStream stream = new ReActThinkingStream(model, messages, "[tools]", toolExecutor, 8);

        ThinkingTokenStream.ErrorConsumer errorConsumer = mock(ThinkingTokenStream.ErrorConsumer.class);
        stream.onError(errorConsumer);
        stream.start();

        verify(errorConsumer).accept(exception);
    }

    // ========== 验证标准：iteration 正确递增 ==========

    /**
     * 验证所有 thought/action/observation 回调携带正确的 iteration 值（从 1 开始递增）
     */
    @Test
    void iterationShouldIncrementCorrectly() {
        // 前2轮返回 tool_calls，第3轮返回 stop
        int[] callCount = {0};
        doAnswer(invocation -> {
            callCount[0]++;
            ThinkingStreamHandler handler = invocation.getArgument(2);
            String toolsJson = invocation.getArgument(1);
            if (toolsJson != null && callCount[0] <= 2) {
                handler.onPartialResponse("第" + callCount[0] + "轮思考");

                ToolCall tc = new ToolCall();
                tc.setId("call_" + callCount[0]);
                tc.setFunctionName("calculate");
                tc.setArguments("{}");
                handler.onToolCalls(Collections.singletonList(tc));
                handler.onComplete("第" + callCount[0] + "轮思考", "tool_calls", null);
            } else {
                handler.onPartialResponse("最终回答");
                handler.onComplete("最终回答", "stop", null);
            }
            return null;
        }).when(model).stream(any(), any(), any());

        when(toolExecutor.execute("calculate", "{}")).thenReturn("结果");

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("多轮循环"));

        ReActThinkingStream stream = new ReActThinkingStream(model, messages, "[tools]", toolExecutor, 8);

        ThinkingTokenStream.ThoughtConsumer thoughtConsumer = mock(ThinkingTokenStream.ThoughtConsumer.class);
        ThinkingTokenStream.ActionConsumer actionConsumer = mock(ThinkingTokenStream.ActionConsumer.class);
        ThinkingTokenStream.FinalAnswerConsumer finalAnswerConsumer = mock(ThinkingTokenStream.FinalAnswerConsumer.class);

        stream.onPartialThought(thoughtConsumer);
        stream.onAction(actionConsumer);
        stream.onObservation(mock(ThinkingTokenStream.ObservationConsumer.class));
        stream.onFinalAnswer(finalAnswerConsumer);
        stream.onComplete(mock(ThinkingTokenStream.CompleteConsumer.class));
        stream.start();

        // 验证 iteration 递增
        verify(thoughtConsumer).accept("第1轮思考", 1);
        verify(thoughtConsumer).accept("第2轮思考", 2);
        verify(thoughtConsumer).accept("最终回答", 3);
        verify(actionConsumer).accept("calculate", "{}", 1);
        verify(actionConsumer).accept("calculate", "{}", 2);
        verify(finalAnswerConsumer).accept(3);
    }
}
