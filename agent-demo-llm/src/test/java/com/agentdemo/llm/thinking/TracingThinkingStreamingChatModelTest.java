package com.agentdemo.llm.thinking;

import com.agentdemo.observability.TraceCollector;
import com.agentdemo.observability.TraceCollector.LlmCallEvent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * TracingThinkingStreamingChatModel 测试（Task-09）
 * <p>
 * 业务含义：验证 Thinking 系装饰器（主链路追踪关键）——行为透传、onComplete 取真实
 * TokenUsage（技术难点 3）、onError 产失败事件（AC-T02）、模型名如实（AC-M02）。
 * </p>
 */
class TracingThinkingStreamingChatModelTest {

    private final ThinkingStreamingChatModel delegate = mock(ThinkingStreamingChatModel.class);
    private final TraceCollector collector = mock(TraceCollector.class);
    private final TracingThinkingStreamingChatModel decorated =
            new TracingThinkingStreamingChatModel(delegate, collector, "glm-5.2");

    private final List<ChatMessage> messages = List.of(UserMessage.from("你好"));

    @Test
    void stream_delegatesToRealModel_withSameArgs() {
        ThinkingStreamHandler handler = mock(ThinkingStreamHandler.class);
        decorated.stream(messages, "{\"tools\":[]}", handler);
        // 装饰器包装 handler（预期行为），断言委托收到相同参数 + 任意包装 handler
        verify(delegate).stream(org.mockito.ArgumentMatchers.eq(messages),
                org.mockito.ArgumentMatchers.eq("{\"tools\":[]}"),
                org.mockito.ArgumentMatchers.any(ThinkingStreamHandler.class));
    }

    @Test
    void stream_twoArg_delegatesToThreeArg_withNullTools() {
        ThinkingStreamHandler handler = mock(ThinkingStreamHandler.class);
        decorated.stream(messages, handler);
        // 两参方法委托三参，toolsJson 为 null
        verify(delegate).stream(org.mockito.ArgumentMatchers.eq(messages),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any(ThinkingStreamHandler.class));
    }

    @Test
    void onComplete_recordsEvent_withRealTokenUsage() {
        ThinkingStreamHandler handler = captureHandler();

        handler.onComplete("完整回复", "stop", new TokenUsage(30, 40));

        ArgumentCaptor<LlmCallEvent> captor = ArgumentCaptor.forClass(LlmCallEvent.class);
        verify(collector).recordLlm(captor.capture());
        LlmCallEvent event = captor.getValue();
        // AC-N03：真实 Token 值 + 模型名如实（AC-M02）
        assertThat(event.modelName()).isEqualTo("glm-5.2");
        assertThat(event.outputText()).isEqualTo("完整回复");
        assertThat(event.inputTokens()).isEqualTo(30);
        assertThat(event.outputTokens()).isEqualTo(40);
        assertThat(event.success()).isTrue();
        assertThat(event.finishReason()).isEqualTo("stop");
        assertThat(event.promptText()).contains("你好");
    }

    @Test
    void onError_recordsFailureEvent() {
        ThinkingStreamHandler handler = captureHandler();

        handler.onError(new RuntimeException("connect timeout"));

        ArgumentCaptor<LlmCallEvent> captor = ArgumentCaptor.forClass(LlmCallEvent.class);
        verify(collector).recordLlm(captor.capture());
        LlmCallEvent event = captor.getValue();
        // AC-T02：失败也产事件
        assertThat(event.success()).isFalse();
        assertThat(event.errorMessage()).isEqualTo("connect timeout");
        assertThat(event.modelName()).isEqualTo("glm-5.2");
    }

    private ThinkingStreamHandler captureHandler() {
        // 捕获传给 delegate 的 handler，后续模拟回调
        java.util.concurrent.atomic.AtomicReference<ThinkingStreamHandler> ref =
                new java.util.concurrent.atomic.AtomicReference<>();
        org.mockito.Mockito.doAnswer(inv -> {
            ref.set(inv.getArgument(2));
            return null;
        }).when(delegate).stream(org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(ThinkingStreamHandler.class));
        ThinkingStreamHandler outer = mock(ThinkingStreamHandler.class);
        decorated.stream(messages, "{\"tools\":[]}", outer);
        return ref.get();
    }
}
