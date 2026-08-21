package com.agentdemo.agent.single;

import com.agentdemo.agent.core.HitlTokenStream;
import com.agentdemo.agent.core.HumanInteractionManager;
import com.agentdemo.llm.thinking.ThinkingStreamHandler;
import com.agentdemo.llm.thinking.ThinkingStreamingChatModel;
import com.agentdemo.llm.thinking.ToolCall;
import com.agentdemo.tools.registry.ToolExecutor;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * HITLReActStream 测试
 * <p>
 * 验证标准来源：Task-02 验证标准
 * 关联 AC：AC-N01（歧义追问）、AC-N02（关键操作确认）、AC-N03（恢复执行）、
 *         AC-S01（追问上限）、AC-M01（上下文保持）
 * </p>
 */
class HITLReActStreamTest {

    private ThinkingStreamingChatModel model;
    private ToolExecutor toolExecutor;
    private HumanInteractionManager humanInteractionManager;

    @BeforeEach
    void setUp() {
        model = mock(ThinkingStreamingChatModel.class);
        toolExecutor = mock(ToolExecutor.class);
        humanInteractionManager = mock(HumanInteractionManager.class);
    }

    /**
     * 模拟单轮 LLM 调用返回 stop（无工具调用）
     */
    private Object mockSingleRoundStop(org.mockito.invocation.InvocationOnMock invocation) {
        ThinkingStreamHandler handler = invocation.getArgument(2);
        handler.onPartialResponse("最终回答");
        handler.onComplete("最终回答", "stop", null);
        return null;
    }

    /**
     * 模拟单轮 LLM 调用返回 tool_calls（指定工具名和参数）
     */
    private Object mockSingleRoundToolCalls(org.mockito.invocation.InvocationOnMock invocation,
                                            String toolName, String args) {
        ThinkingStreamHandler handler = invocation.getArgument(2);
        handler.onPartialResponse("需要调用工具");

        ToolCall tc = new ToolCall();
        tc.setId("call_001");
        tc.setFunctionName(toolName);
        tc.setArguments(args);

        handler.onToolCalls(Collections.singletonList(tc));
        handler.onComplete("需要调用工具", "tool_calls", null);
        return null;
    }

    // ========== 验证标准：askUser 工具拦截 ==========

    @Test
    void askUser调用应拦截并触发onAskUser回调() {
        String askUserArgs = "{\"type\":\"text\",\"question\":\"请提供订单号\",\"options\":[]}";
        doAnswer(inv -> mockSingleRoundToolCalls(inv, "askUser", askUserArgs))
                .when(model).stream(any(), any(), any());

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("系统提示"));
        messages.add(UserMessage.from("帮我查订单"));

        HITLReActStream stream = new HITLReActStream(
                model, messages, "[tools]", toolExecutor,
                humanInteractionManager, "sess-001", "model-001", 0, 8);

        HitlTokenStream.AskUserConsumer askUserConsumer = mock(HitlTokenStream.AskUserConsumer.class);
        HitlTokenStream.CompleteConsumer completeConsumer = mock(HitlTokenStream.CompleteConsumer.class);

        stream.onAskUser(askUserConsumer);
        stream.onComplete(completeConsumer);
        stream.start();

        // 验证 onAskUser 被触发，携带正确参数
        verify(askUserConsumer).accept("text", "请提供订单号", Collections.emptyList(), 0);
        // 验证 onComplete 不被调用（暂停状态）
        verify(completeConsumer, never()).accept(anyString());
    }

    @Test
    void askUser拦截后应保存状态到HumanInteractionManager() {
        String askUserArgs = "{\"type\":\"confirm\",\"question\":\"确认删除？\",\"options\":[\"确认\",\"取消\"]}";
        doAnswer(inv -> mockSingleRoundToolCalls(inv, "askUser", askUserArgs))
                .when(model).stream(any(), any(), any());

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("删除文件"));

        HITLReActStream stream = new HITLReActStream(
                model, messages, "[tools]", toolExecutor,
                humanInteractionManager, "sess-002", "model-002", 0, 8);

        stream.onAskUser(mock(HitlTokenStream.AskUserConsumer.class));
        stream.start();

        // 验证状态已保存
        verify(humanInteractionManager).saveInteraction(
                eq("sess-002"), any(), eq("confirm"), eq("确认删除？"),
                eq(List.of("确认", "取消")), eq(0), eq("model-002"), eq("[tools]"));
    }

    @Test
    void askUser拦截后onAction应被触发() {
        String askUserArgs = "{\"type\":\"text\",\"question\":\"问题\",\"options\":[]}";
        doAnswer(inv -> mockSingleRoundToolCalls(inv, "askUser", askUserArgs))
                .when(model).stream(any(), any(), any());

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("test"));

        HITLReActStream stream = new HITLReActStream(
                model, messages, "[tools]", toolExecutor,
                humanInteractionManager, "sess-003", "model-001", 0, 8);

        HitlTokenStream.ActionConsumer actionConsumer = mock(HitlTokenStream.ActionConsumer.class);
        stream.onAction(actionConsumer);
        stream.onAskUser(mock(HitlTokenStream.AskUserConsumer.class));
        stream.start();

        // 验证 onAction 被触发（前端显示 Agent 正在调用工具）
        verify(actionConsumer).accept("askUser", askUserArgs, 1);
    }

    // ========== 验证标准：非 askUser 工具正常执行 ==========

    @Test
    void 非askUser工具应正常执行() {
        int[] callCount = {0};
        doAnswer(inv -> {
            callCount[0]++;
            if (callCount[0] == 1) {
                mockSingleRoundToolCalls(inv, "calculate", "{\"expression\":\"1+1\"}");
            } else {
                mockSingleRoundStop(inv);
            }
            return null;
        }).when(model).stream(any(), any(), any());

        when(toolExecutor.execute("calculate", "{\"expression\":\"1+1\"}"))
                .thenReturn("1+1 = 2");

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("计算1+1"));

        HITLReActStream stream = new HITLReActStream(
                model, messages, "[tools]", toolExecutor,
                humanInteractionManager, "sess-004", "model-001", 0, 8);

        HitlTokenStream.ActionConsumer actionConsumer = mock(HitlTokenStream.ActionConsumer.class);
        HitlTokenStream.ObservationConsumer observationConsumer = mock(HitlTokenStream.ObservationConsumer.class);
        HitlTokenStream.CompleteConsumer completeConsumer = mock(HitlTokenStream.CompleteConsumer.class);

        stream.onAction(actionConsumer);
        stream.onObservation(observationConsumer);
        stream.onComplete(completeConsumer);
        stream.start();

        verify(actionConsumer).accept("calculate", "{\"expression\":\"1+1\"}", 1);
        verify(observationConsumer).accept("1+1 = 2", 1);
        verify(completeConsumer).accept("最终回答");
        // 验证未保存 HITL 状态
        verify(humanInteractionManager, never()).saveInteraction(
                anyString(), any(), anyString(), anyString(), any(), anyInt(), anyString(), anyString());
    }

    // ========== 验证标准：追问次数上限 ==========

    @Test
    void retryCount超过上限应返回错误Observation() {
        String askUserArgs = "{\"type\":\"text\",\"question\":\"再次追问\",\"options\":[]}";
        doAnswer(inv -> mockSingleRoundToolCalls(inv, "askUser", askUserArgs))
                .when(model).stream(any(), any(), any());

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("test"));

        // retryCount = 3，已达上限
        HITLReActStream stream = new HITLReActStream(
                model, messages, "[tools]", toolExecutor,
                humanInteractionManager, "sess-005", "model-001", 3, 8);

        HitlTokenStream.ObservationConsumer observationConsumer = mock(HitlTokenStream.ObservationConsumer.class);
        HitlTokenStream.AskUserConsumer askUserConsumer = mock(HitlTokenStream.AskUserConsumer.class);

        stream.onObservation(observationConsumer);
        stream.onAskUser(askUserConsumer);
        stream.start();

        // 验证返回了错误 Observation
        verify(observationConsumer).accept(contains("已达最大追问次数"), eq(1));
        // 验证 onAskUser 未被触发
        verify(askUserConsumer, never()).accept(anyString(), anyString(), any(), anyInt());
        // 验证未保存状态
        verify(humanInteractionManager, never()).saveInteraction(
                anyString(), any(), anyString(), anyString(), any(), anyInt(), anyString(), anyString());
    }

    // ========== 验证标准：无工具调用时正常完成 ==========

    @Test
    void 无工具调用应正常完成() {
        doAnswer(this::mockSingleRoundStop).when(model).stream(any(), any(), any());

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("你好"));

        HITLReActStream stream = new HITLReActStream(
                model, messages, "[tools]", toolExecutor,
                humanInteractionManager, "sess-006", "model-001", 0, 8);

        HitlTokenStream.FinalAnswerConsumer finalAnswerConsumer = mock(HitlTokenStream.FinalAnswerConsumer.class);
        HitlTokenStream.CompleteConsumer completeConsumer = mock(HitlTokenStream.CompleteConsumer.class);

        stream.onFinalAnswer(finalAnswerConsumer);
        stream.onComplete(completeConsumer);
        stream.start();

        verify(finalAnswerConsumer).accept(1);
        verify(completeConsumer).accept("最终回答");
    }

    // ========== 验证标准：LLM 调用异常 ==========

    @Test
    void LLM异常应触发onError() {
        RuntimeException exception = new RuntimeException("LLM 连接失败");
        doAnswer(inv -> {
            ThinkingStreamHandler handler = inv.getArgument(2);
            handler.onError(exception);
            return null;
        }).when(model).stream(any(), any(), any());

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("触发错误"));

        HITLReActStream stream = new HITLReActStream(
                model, messages, "[tools]", toolExecutor,
                humanInteractionManager, "sess-007", "model-001", 0, 8);

        HitlTokenStream.ErrorConsumer errorConsumer = mock(HitlTokenStream.ErrorConsumer.class);
        stream.onError(errorConsumer);
        stream.start();

        verify(errorConsumer).accept(exception);
    }
}
