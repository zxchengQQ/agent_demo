package com.agentdemo.llm.factory;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * ArkThinkingStreamingChatModel 测试
 * <p>
 * 验证标准来源：T-17 验证标准
 * 关联 AC：AC-022（推理过程流式展示）
 * </p>
 */
class ArkThinkingStreamingChatModelTest {

    private ArkThinkingStreamingChatModel model;

    @BeforeEach
    void setUp() {
        // BR-LLM-002: 使用 Coding Plan 专用地址
        model = new ArkThinkingStreamingChatModel(
                "https://ark.cn-beijing.volces.com/api/coding/v3",
                "test-api-key",
                "doubao-seed-2.0-pro",
                Duration.ofSeconds(60));
    }

    // ========== 验证标准 5、6: 请求体构建 ==========

    /**
     * 验证请求体包含 thinking:{"type":"enabled"} 参数
     * 业务含义：开启深度思考，让方舟返回 reasoning_content
     */
    @Test
    void buildRequestBodyShouldContainThinkingEnabled() {
        List<ChatMessage> messages = Collections.singletonList(UserMessage.from("你好"));

        String requestBody = model.buildRequestBody(messages);

        assertNotNull(requestBody);
        assertTrue(requestBody.contains("\"thinking\""),
                "请求体应包含 thinking 字段");
        assertTrue(requestBody.contains("\"type\":\"enabled\""),
                "thinking 字段应为 enabled");
    }

    /**
     * 验证请求体包含 model 字段
     */
    @Test
    void buildRequestBodyShouldContainModelName() {
        List<ChatMessage> messages = Collections.singletonList(UserMessage.from("你好"));

        String requestBody = model.buildRequestBody(messages);

        assertTrue(requestBody.contains("\"model\":\"doubao-seed-2.0-pro\""),
                "请求体应包含 model 字段");
    }

    /**
     * 验证请求体包含 stream=true
     */
    @Test
    void buildRequestBodyShouldContainStreamTrue() {
        List<ChatMessage> messages = Collections.singletonList(UserMessage.from("你好"));

        String requestBody = model.buildRequestBody(messages);

        assertTrue(requestBody.contains("\"stream\":true"),
                "请求体应包含 stream:true");
    }

    /**
     * 验证 baseUrl 遵循 BR-LLM-002（Coding Plan 专用地址）
     */
    @Test
    void baseUrlShouldFollowCodingPlanConstraint() {
        String baseUrl = model.getBaseUrl();

        assertTrue(baseUrl.contains("/api/coding/v3"),
                "baseUrl 应使用 Coding Plan 专用地址 /api/coding/v3（BR-LLM-002）");
    }

    /**
     * 验证请求体包含 messages 数组
     */
    @Test
    void buildRequestBodyShouldContainMessages() {
        List<ChatMessage> messages = Collections.singletonList(UserMessage.from("测试消息"));

        String requestBody = model.buildRequestBody(messages);

        assertTrue(requestBody.contains("\"messages\""),
                "请求体应包含 messages 字段");
        assertTrue(requestBody.contains("测试消息"),
                "请求体应包含用户消息内容");
    }

    // ========== 验证标准 1、2、3: SSE 响应解析 ==========

    /**
     * 验证解析 reasoning_content 片段
     * 业务含义：方舟流式响应中 delta.reasoning_content 字段携带推理内容
     */
    @Test
    void parseSseResponseShouldExtractReasoningContent() {
        String sseText = "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"用户问的是\"},\"finish_reason\":null}]}\n\n"
                + "data: [DONE]";

        ThinkingStreamHandler handler = mock(ThinkingStreamHandler.class);

        model.parseSseResponse(sseText, handler);

        verify(handler).onPartialThinking("用户问的是");
    }

    /**
     * 验证解析 content 片段（正式回复）
     */
    @Test
    void parseSseResponseShouldExtractContent() {
        String sseText = "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你好\"},\"finish_reason\":null}]}\n\n"
                + "data: [DONE]";

        ThinkingStreamHandler handler = mock(ThinkingStreamHandler.class);

        model.parseSseResponse(sseText, handler);

        verify(handler).onPartialResponse("你好");
    }

    /**
     * 验证交替解析 reasoning_content 和 content（验证标准 1）
     * 业务含义：方舟先返回推理内容，再返回正式回复
     */
    @Test
    void parseSseResponseShouldHandleAlternatingReasoningAndContent() {
        String sseText = "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"用户\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"问的是\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你好\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"世界\"},\"finish_reason\":null}]}\n\n"
                + "data: [DONE]";

        ThinkingStreamHandler handler = mock(ThinkingStreamHandler.class);

        model.parseSseResponse(sseText, handler);

        // 验证推理片段按顺序回调
        verify(handler).onPartialThinking("用户");
        verify(handler).onPartialThinking("问的是");
        // 验证正式回复按顺序回调
        verify(handler).onPartialResponse("你好");
        verify(handler).onPartialResponse("世界");
    }

    /**
     * 验证跨 SSE 行的 reasoning_content 片段能正确拼接（验证标准 2）
     * 业务含义：多个 reasoning_content 片段分散在不同 SSE data 行，每个都应触发独立回调
     */
    @Test
    void parseSseResponseShouldHandleMultiChunkReasoningContent() {
        String sseText = "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"首先\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"，我需要\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"分析问题\"},\"finish_reason\":null}]}\n\n"
                + "data: [DONE]";

        ThinkingStreamHandler handler = mock(ThinkingStreamHandler.class);

        model.parseSseResponse(sseText, handler);

        // 验证三次独立回调（调用方负责拼接）
        verify(handler, times(3)).onPartialThinking(org.mockito.ArgumentMatchers.anyString());
        verify(handler).onPartialThinking("首先");
        verify(handler).onPartialThinking("，我需要");
        verify(handler).onPartialThinking("分析问题");
    }

    /**
     * 验证 finish_reason="stop" 触发 onComplete 回调，携带完整 content（验证标准 3）
     */
    @Test
    void parseSseResponseShouldTriggerOnCompleteOnFinishReasonStop() {
        String sseText = "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你好\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"世界\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]";

        ThinkingStreamHandler handler = mock(ThinkingStreamHandler.class);

        model.parseSseResponse(sseText, handler);

        // 验证 onComplete 被触发，携带完整正式回复（content 拼接）
        verify(handler).onComplete("你好世界");
    }

    /**
     * 验证 [DONE] 标记被正确处理（不触发回调）
     */
    @Test
    void parseSseResponseShouldHandleDoneMarker() {
        String sseText = "data: [DONE]";

        ThinkingStreamHandler handler = mock(ThinkingStreamHandler.class);

        model.parseSseResponse(sseText, handler);

        // [DONE] 不应触发任何回调（onComplete 由 finish_reason=stop 触发）
        verifyNoMoreInteractions(handler);
    }

    /**
     * 验证只有 reasoning_content 没有 content 时，onComplete 携带空字符串
     */
    @Test
    void parseSseResponseShouldTriggerOnCompleteWithEmptyContentWhenOnlyReasoning() {
        String sseText = "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"思考\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]";

        ThinkingStreamHandler handler = mock(ThinkingStreamHandler.class);

        model.parseSseResponse(sseText, handler);

        verify(handler).onComplete("");
    }

    /**
     * 验证空 SSE 响应不触发回调
     */
    @Test
    void parseSseResponseShouldHandleEmptyText() {
        ThinkingStreamHandler handler = mock(ThinkingStreamHandler.class);

        model.parseSseResponse("", handler);

        verifyNoMoreInteractions(handler);
    }

    // ========== 验证标准 4: 异常处理 ==========

    /**
     * 验证 stream 方法在 HTTP 异常时触发 onError（验证标准 4）
     * 业务含义：网络异常或非 200 状态码时，通过 handler.onError 通知调用方
     *
     * 测试策略：用 spy 覆盖 fetchSseText 方法抛出异常，验证 onError 被调用
     */
    @Test
    void streamShouldTriggerOnErrorOnHttpException() {
        List<ChatMessage> messages = Collections.singletonList(UserMessage.from("你好"));
        ArkThinkingStreamingChatModel spyModel = org.mockito.Mockito.spy(model);
        RuntimeException httpException = new RuntimeException("连接失败");
        doThrow(httpException).when(spyModel).fetchSseText(org.mockito.ArgumentMatchers.anyString());

        ThinkingStreamHandler handler = mock(ThinkingStreamHandler.class);

        spyModel.stream(messages, handler);

        verify(handler).onError(httpException);
    }

    /**
     * 验证 stream 方法正常流程：buildRequestBody -> fetchSseText -> parseSseResponse
     */
    @Test
    void streamShouldParseSseResponseFromFetchedText() {
        List<ChatMessage> messages = Collections.singletonList(UserMessage.from("你好"));
        String mockSseText = "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"思考\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"回复\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]";

        ArkThinkingStreamingChatModel spyModel = org.mockito.Mockito.spy(model);
        org.mockito.Mockito.doReturn(mockSseText).when(spyModel).fetchSseText(org.mockito.ArgumentMatchers.anyString());

        ThinkingStreamHandler handler = mock(ThinkingStreamHandler.class);

        spyModel.stream(messages, handler);

        verify(handler).onPartialThinking("思考");
        verify(handler).onPartialResponse("回复");
        verify(handler).onComplete("回复");
    }
}
