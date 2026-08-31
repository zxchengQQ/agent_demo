package com.agentdemo.llm.registry;

import com.agentdemo.observability.TraceCollector;
import com.agentdemo.observability.TraceCollector.LlmCallEvent;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * TraceChatModelListener 测试（Task-08）
 * <p>
 * 业务含义：验证 OpenAI 系 listener 三路径（请求/响应/错误）正确生成 LLM 事件
 * （模型名/消息/TokenUsage/耗时/成败），AC-N03/T02。
 * </p>
 */
class TraceChatModelListenerTest {

    private final TraceCollector collector = mock(TraceCollector.class);
    private final TraceChatModelListener listener = new TraceChatModelListener(collector);

    private ChatRequest request(String model) {
        return ChatRequest.builder()
                .messages(List.of(UserMessage.from("你好")))
                .parameters(ChatRequestParameters.builder().modelName(model).build())
                .build();
    }

    @Test
    void onResponse_recordsSuccessEvent() {
        ChatModelRequestContext reqCtx = new ChatModelRequestContext(
                request("glm-5.2"), ModelProvider.OPEN_AI, new HashMap<>());
        listener.onRequest(reqCtx);

        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("回复内容"))
                .metadata(ChatResponseMetadata.builder()
                        .modelName("glm-5.2")
                        .tokenUsage(new TokenUsage(10, 20))
                        .finishReason(FinishReason.STOP)
                        .build())
                .build();
        ChatModelResponseContext respCtx = new ChatModelResponseContext(
                response, request("glm-5.2"), ModelProvider.OPEN_AI, reqCtx.attributes());
        listener.onResponse(respCtx);

        ArgumentCaptor<LlmCallEvent> captor = ArgumentCaptor.forClass(LlmCallEvent.class);
        verify(collector).recordLlm(captor.capture());
        LlmCallEvent event = captor.getValue();
        // AC-N03：模型名/消息/Token 必备字段
        assertThat(event.modelName()).isEqualTo("glm-5.2");
        assertThat(event.promptText()).contains("你好");
        assertThat(event.outputText()).isEqualTo("回复内容");
        assertThat(event.inputTokens()).isEqualTo(10);
        assertThat(event.outputTokens()).isEqualTo(20);
        assertThat(event.success()).isTrue();
        assertThat(event.finishReason()).isEqualTo("STOP");
        assertThat(event.durationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void onError_recordsFailureEvent() {
        ChatModelRequestContext reqCtx = new ChatModelRequestContext(
                request("glm-5.2"), ModelProvider.OPEN_AI, new HashMap<>());
        listener.onRequest(reqCtx);

        ChatModelErrorContext errCtx = new ChatModelErrorContext(
                new RuntimeException("connection timeout"), request("glm-5.2"),
                ModelProvider.OPEN_AI, reqCtx.attributes());
        listener.onError(errCtx);

        ArgumentCaptor<LlmCallEvent> captor = ArgumentCaptor.forClass(LlmCallEvent.class);
        verify(collector).recordLlm(captor.capture());
        LlmCallEvent event = captor.getValue();
        // AC-T02：失败也产事件，含错误信息
        assertThat(event.success()).isFalse();
        assertThat(event.errorMessage()).isEqualTo("connection timeout");
        assertThat(event.modelName()).isEqualTo("glm-5.2");
    }

    @Test
    void onRequest_withoutResponse_doesNotRecord() {
        // 仅 onRequest 不产生事件（事件在 onResponse/onError 收口）
        ChatModelRequestContext reqCtx = new ChatModelRequestContext(
                request("glm-5.2"), ModelProvider.OPEN_AI, new HashMap<>());
        listener.onRequest(reqCtx);
        Map<Object, Object> attrs = reqCtx.attributes();
        assertThat(attrs).isNotEmpty();
    }
}
