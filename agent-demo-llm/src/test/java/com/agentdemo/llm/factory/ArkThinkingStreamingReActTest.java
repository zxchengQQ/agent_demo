package com.agentdemo.llm.factory;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * ArkThinkingStreamingChatModel 流式改造测试（Task-05 + Task-06 + BUG 修复）
 * <p>
 * 验证标准来源：Task-05 验证标准（逐行流式读取 + tool_calls 解析）、Task-06 验证标准（tools 参数）
 * 关联 AC：AC-002（内部推理逐 Token 推送）、AC-003（Thought 逐 Token 推送）、AC-004（工具调用触发）、AC-013（LLM 调用失败）
 * BUG 修复：tool_calls delta 分片累积拼接（避免碎片化执行）
 * </p>
 */
class ArkThinkingStreamingReActTest {

    private ArkThinkingStreamingChatModel model;

    @BeforeEach
    void setUp() {
        model = new ArkThinkingStreamingChatModel(
                "https://ark.cn-beijing.volces.com/api/coding/v3",
                "test-api-key",
                "doubao-seed-2.0-pro",
                Duration.ofSeconds(60));
    }

    // ========== Task-05: parseSseLine 逐行解析 ==========

    /**
     * 验证 parseSseLine 解析 reasoning_content 片段，触发 onPartialThinking
     */
    @Test
    void parseSseLineShouldExtractReasoningContent() {
        String line = "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"正在分析\"},\"finish_reason\":null}]}";
        ThinkingStreamHandler handler = mock(ThinkingStreamHandler.class);
        StringBuilder fullResponse = new StringBuilder();
        Map<Integer, ToolCall> accumulator = new LinkedHashMap<>();

        model.parseSseLine(line, handler, fullResponse, accumulator);

        verify(handler).onPartialThinking("正在分析");
    }

    /**
     * 验证 parseSseLine 解析 content 片段，触发 onPartialResponse 并累积 fullResponse
     */
    @Test
    void parseSseLineShouldExtractContentAndAccumulate() {
        String line = "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你好\"},\"finish_reason\":null}]}";
        ThinkingStreamHandler handler = mock(ThinkingStreamHandler.class);
        StringBuilder fullResponse = new StringBuilder();
        Map<Integer, ToolCall> accumulator = new LinkedHashMap<>();

        model.parseSseLine(line, handler, fullResponse, accumulator);

        verify(handler).onPartialResponse("你好");
        assertEquals("你好", fullResponse.toString());
    }

    /**
     * 验证 parseSseLine 解析 tool_calls 并在 finish_reason 时触发 onToolCalls 回调
     * 业务含义：LLM 决定调用工具时，方舟返回 delta.tool_calls，累积后在 finish_reason 时统一回调
     */
    @Test
    void parseSseLineShouldExtractToolCalls() {
        ThinkingStreamHandler handler = mock(ThinkingStreamHandler.class);
        StringBuilder fullResponse = new StringBuilder();
        Map<Integer, ToolCall> accumulator = new LinkedHashMap<>();

        // tool_calls delta 行（累积）
        model.parseSseLine("data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_001\",\"type\":\"function\",\"function\":{\"name\":\"calculate\",\"arguments\":\"{\\\"expression\\\":\\\"2+3\\\"}\"}}]},\"finish_reason\":null}]}", handler, fullResponse, accumulator);

        // finish_reason 行（触发 onToolCalls + onComplete）
        model.parseSseLine("data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}", handler, fullResponse, accumulator);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ToolCall>> captor = ArgumentCaptor.forClass(List.class);
        verify(handler).onToolCalls(captor.capture());

        List<ToolCall> toolCalls = captor.getValue();
        assertEquals(1, toolCalls.size(), "应解析出 1 个工具调用");
        ToolCall tc = toolCalls.get(0);
        assertEquals("call_001", tc.getId());
        assertEquals("calculate", tc.getFunctionName());
        assertEquals("{\"expression\":\"2+3\"}", tc.getArguments());
    }

    /**
     * 验证 parseSseLine 解析多个 tool_calls（串行执行场景）
     */
    @Test
    void parseSseLineShouldExtractMultipleToolCalls() {
        ThinkingStreamHandler handler = mock(ThinkingStreamHandler.class);
        StringBuilder fullResponse = new StringBuilder();
        Map<Integer, ToolCall> accumulator = new LinkedHashMap<>();

        // 两个 tool_calls（不同 index）
        model.parseSseLine("data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_001\",\"type\":\"function\",\"function\":{\"name\":\"calculate\",\"arguments\":\"{\\\"expression\\\":\\\"1+1\\\"}\"}},{\"index\":1,\"id\":\"call_002\",\"type\":\"function\",\"function\":{\"name\":\"getCurrentTime\",\"arguments\":\"{}\"}}]},\"finish_reason\":null}]}", handler, fullResponse, accumulator);

        // finish_reason 触发
        model.parseSseLine("data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}", handler, fullResponse, accumulator);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ToolCall>> captor = ArgumentCaptor.forClass(List.class);
        verify(handler).onToolCalls(captor.capture());

        List<ToolCall> toolCalls = captor.getValue();
        assertEquals(2, toolCalls.size(), "应解析出 2 个工具调用");
        assertEquals("calculate", toolCalls.get(0).getFunctionName());
        assertEquals("getCurrentTime", toolCalls.get(1).getFunctionName());
    }

    /**
     * 验证 tool_calls delta 分片累积拼接（BUG 修复核心测试）
     * 业务含义：OpenAI 协议中 tool_calls 流式分片返回，首个 chunk 含 id+name，
     * 后续 chunk 只有 arguments 片段，需按 index 累积拼接为完整 ToolCall
     */
    @Test
    void parseSseLineShouldAccumulateToolCallDeltas() {
        ThinkingStreamHandler handler = mock(ThinkingStreamHandler.class);
        StringBuilder fullResponse = new StringBuilder();
        Map<Integer, ToolCall> accumulator = new LinkedHashMap<>();

        // 第一个 chunk：含 id + name，arguments 为空
        model.parseSseLine("data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_001\",\"type\":\"function\",\"function\":{\"name\":\"httpGet\",\"arguments\":\"\"}}]},\"finish_reason\":null}]}", handler, fullResponse, accumulator);

        // 第二个 chunk：只有 arguments 片段
        model.parseSseLine("data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"url\\\"\"}}]},\"finish_reason\":null}]}", handler, fullResponse, accumulator);

        // 第三个 chunk：arguments 片段
        model.parseSseLine("data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\":\\\"http://example.com\\\"}\"}}]},\"finish_reason\":null}]}", handler, fullResponse, accumulator);

        // finish_reason 触发 onToolCalls
        model.parseSseLine("data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}", handler, fullResponse, accumulator);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ToolCall>> captor = ArgumentCaptor.forClass(List.class);
        verify(handler).onToolCalls(captor.capture());

        List<ToolCall> toolCalls = captor.getValue();
        assertEquals(1, toolCalls.size(), "应累积为 1 个完整工具调用（非 3 个碎片）");
        ToolCall tc = toolCalls.get(0);
        assertEquals("call_001", tc.getId());
        assertEquals("httpGet", tc.getFunctionName());
        assertEquals("{\"url\":\"http://example.com\"}", tc.getArguments(), "arguments 应正确拼接");
    }

    /**
     * 验证 parseSseLine 解析 finish_reason=stop，触发 onComplete
     */
    @Test
    void parseSseLineShouldTriggerOnCompleteOnStop() {
        String line = "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}";
        ThinkingStreamHandler handler = mock(ThinkingStreamHandler.class);
        StringBuilder fullResponse = new StringBuilder("最终回答");
        Map<Integer, ToolCall> accumulator = new LinkedHashMap<>();

        model.parseSseLine(line, handler, fullResponse, accumulator);

        verify(handler).onComplete("最终回答", "stop");
    }

    /**
     * 验证 parseSseLine 解析 finish_reason=tool_calls，触发 onToolCalls 和 onComplete
     */
    @Test
    void parseSseLineShouldTriggerOnCompleteOnToolCalls() {
        String line = "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_001\",\"type\":\"function\",\"function\":{\"name\":\"calculate\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}";
        ThinkingStreamHandler handler = mock(ThinkingStreamHandler.class);
        StringBuilder fullResponse = new StringBuilder("Thought文本");
        Map<Integer, ToolCall> accumulator = new LinkedHashMap<>();

        model.parseSseLine(line, handler, fullResponse, accumulator);

        // finish_reason 时先触发 onToolCalls（累积器非空），再触发 onComplete
        verify(handler).onToolCalls(org.mockito.ArgumentMatchers.anyList());
        verify(handler).onComplete("Thought文本", "tool_calls");
    }

    /**
     * 验证 parseSseLine 跳过 [DONE] 标记
     */
    @Test
    void parseSseLineShouldSkipDoneMarker() {
        String line = "data: [DONE]";
        ThinkingStreamHandler handler = mock(ThinkingStreamHandler.class);
        StringBuilder fullResponse = new StringBuilder();
        Map<Integer, ToolCall> accumulator = new LinkedHashMap<>();

        model.parseSseLine(line, handler, fullResponse, accumulator);

        verifyNoMoreInteractions(handler);
    }

    /**
     * 验证 parseSseLine 跳过非 data: 行
     */
    @Test
    void parseSseLineShouldSkipNonDataLine() {
        String line = ": comment line";
        ThinkingStreamHandler handler = mock(ThinkingStreamHandler.class);
        StringBuilder fullResponse = new StringBuilder();
        Map<Integer, ToolCall> accumulator = new LinkedHashMap<>();

        model.parseSseLine(line, handler, fullResponse, accumulator);

        verifyNoMoreInteractions(handler);
    }

    /**
     * 验证 parseSseLine 处理无效 JSON 不抛出异常
     */
    @Test
    void parseSseLineShouldHandleInvalidJsonGracefully() {
        String line = "data: {invalid json}";
        ThinkingStreamHandler handler = mock(ThinkingStreamHandler.class);
        StringBuilder fullResponse = new StringBuilder();
        Map<Integer, ToolCall> accumulator = new LinkedHashMap<>();

        // 不应抛出异常
        model.parseSseLine(line, handler, fullResponse, accumulator);

        verifyNoMoreInteractions(handler);
    }

    /**
     * 验证连续调用 parseSseLine 模拟流式读取（3个 reasoning 片段各触发1次回调）
     */
    @Test
    void parseSseLineShouldHandleMultipleReasoningChunks() {
        ThinkingStreamHandler handler = mock(ThinkingStreamHandler.class);
        StringBuilder fullResponse = new StringBuilder();
        Map<Integer, ToolCall> accumulator = new LinkedHashMap<>();

        model.parseSseLine("data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"片段1\"},\"finish_reason\":null}]}", handler, fullResponse, accumulator);
        model.parseSseLine("data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"片段2\"},\"finish_reason\":null}]}", handler, fullResponse, accumulator);
        model.parseSseLine("data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"片段3\"},\"finish_reason\":null}]}", handler, fullResponse, accumulator);

        verify(handler, times(3)).onPartialThinking(org.mockito.ArgumentMatchers.anyString());
        verify(handler).onPartialThinking("片段1");
        verify(handler).onPartialThinking("片段2");
        verify(handler).onPartialThinking("片段3");
    }

    // ========== Task-06: buildRequestBody 支持 tools 参数 ==========

    /**
     * 验证 buildRequestBody(messages, null) 不包含 tools 字段
     */
    @Test
    void buildRequestBodyWithNullToolsShouldNotContainTools() {
        List<ChatMessage> messages = Collections.singletonList(UserMessage.from("你好"));

        String requestBody = model.buildRequestBody(messages, null);

        assertNotNull(requestBody);
        assertTrue(!requestBody.contains("\"tools\""),
                "toolsJson 为 null 时不应包含 tools 字段");
    }

    /**
     * 验证 buildRequestBody(messages, "") 不包含 tools 字段
     */
    @Test
    void buildRequestBodyWithEmptyToolsShouldNotContainTools() {
        List<ChatMessage> messages = Collections.singletonList(UserMessage.from("你好"));

        String requestBody = model.buildRequestBody(messages, "");

        assertTrue(!requestBody.contains("\"tools\""),
                "toolsJson 为空字符串时不应包含 tools 字段");
    }

    /**
     * 验证 buildRequestBody(messages, toolsJson) 包含 tools 字段
     */
    @Test
    void buildRequestBodyWithToolsShouldContainTools() {
        List<ChatMessage> messages = Collections.singletonList(UserMessage.from("计算 2+3"));
        String toolsJson = "[{\"type\":\"function\",\"function\":{\"name\":\"calculate\",\"description\":\"计算数学表达式\",\"parameters\":{\"type\":\"object\",\"properties\":{\"expression\":{\"type\":\"string\"}},\"required\":[\"expression\"]}}}]";

        String requestBody = model.buildRequestBody(messages, toolsJson);

        assertTrue(requestBody.contains("\"tools\""),
                "toolsJson 非空时应包含 tools 字段");
        assertTrue(requestBody.contains("calculate"),
                "tools 字段应包含工具名称");
    }

    /**
     * 验证 buildRequestBody(messages, toolsJson) 仍包含 thinking.enabled
     */
    @Test
    void buildRequestBodyWithToolsShouldStillContainThinking() {
        List<ChatMessage> messages = Collections.singletonList(UserMessage.from("你好"));
        String toolsJson = "[{\"type\":\"function\",\"function\":{\"name\":\"test\",\"description\":\"test\",\"parameters\":{\"type\":\"object\",\"properties\":{},\"required\":[]}}}]";

        String requestBody = model.buildRequestBody(messages, toolsJson);

        assertTrue(requestBody.contains("\"thinking\""), "应包含 thinking 字段");
        assertTrue(requestBody.contains("\"type\":\"enabled\""), "thinking 应为 enabled");
        assertTrue(requestBody.contains("\"stream\":true"), "应包含 stream:true");
        assertTrue(requestBody.contains("\"model\""), "应包含 model 字段");
    }

    /**
     * 验证 buildRequestBody(messages) 向后兼容（委托给 buildRequestBody(messages, null)）
     */
    @Test
    void buildRequestBodySingleArgShouldBeBackwardCompatible() {
        List<ChatMessage> messages = Collections.singletonList(UserMessage.from("你好"));

        String requestBody = model.buildRequestBody(messages);

        assertNotNull(requestBody);
        assertTrue(requestBody.contains("\"model\""), "应包含 model 字段");
        assertTrue(!requestBody.contains("\"tools\""), "不应包含 tools 字段");
    }

    // ========== Task-05: stream(messages, toolsJson, handler) ==========

    /**
     * 验证 stream(messages, toolsJson, handler) 正常流程
     * 业务含义：使用 tools 参数发起 ReAct 流式调用，逐行解析 SSE 响应
     *
     * 测试策略：spy mock fetchAndParseSseStream，模拟逐行回调
     */
    @Test
    void streamWithToolsShouldCallFetchAndParseSseStream() {
        List<ChatMessage> messages = Collections.singletonList(UserMessage.from("计算 2+3"));
        String toolsJson = "[{\"type\":\"function\",\"function\":{\"name\":\"calculate\"}}]";
        ArkThinkingStreamingChatModel spyModel = org.mockito.Mockito.spy(model);
        ThinkingStreamHandler handler = mock(ThinkingStreamHandler.class);

        // mock fetchAndParseSseStream，模拟逐行回调
        org.mockito.Mockito.doAnswer(invocation -> {
            ThinkingStreamHandler h = invocation.getArgument(1);
            StringBuilder fullResp = new StringBuilder();
            Map<Integer, ToolCall> acc = new LinkedHashMap<>();
            spyModel.parseSseLine("data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"思考中\"},\"finish_reason\":null}]}", h, fullResp, acc);
            spyModel.parseSseLine("data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"回复\"},\"finish_reason\":null}]}", h, fullResp, acc);
            spyModel.parseSseLine("data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}", h, fullResp, acc);
            return null;
        }).when(spyModel).fetchAndParseSseStream(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(ThinkingStreamHandler.class));

        spyModel.stream(messages, toolsJson, handler);

        verify(handler).onPartialThinking("思考中");
        verify(handler).onPartialResponse("回复");
        verify(handler).onComplete("回复", "stop");
    }

    /**
     * 验证 stream(messages, toolsJson, handler) 异常时触发 onError
     */
    @Test
    void streamWithToolsShouldTriggerOnErrorOnException() {
        List<ChatMessage> messages = Collections.singletonList(UserMessage.from("你好"));
        ArkThinkingStreamingChatModel spyModel = org.mockito.Mockito.spy(model);
        ThinkingStreamHandler handler = mock(ThinkingStreamHandler.class);
        RuntimeException exception = new RuntimeException("连接失败");

        doThrow(exception).when(spyModel).fetchAndParseSseStream(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(ThinkingStreamHandler.class));

        spyModel.stream(messages, null, handler);

        verify(handler).onError(exception);
    }

    /**
     * 验证 stream(messages, handler) 仍走原有路径（向后兼容）
     */
    @Test
    void streamWithoutToolsShouldBeBackwardCompatible() {
        List<ChatMessage> messages = Collections.singletonList(UserMessage.from("你好"));
        ArkThinkingStreamingChatModel spyModel = org.mockito.Mockito.spy(model);
        String mockSseText = "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"思考\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"回复\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]";

        org.mockito.Mockito.doReturn(mockSseText).when(spyModel).fetchSseText(org.mockito.ArgumentMatchers.anyString());

        ThinkingStreamHandler handler = mock(ThinkingStreamHandler.class);
        spyModel.stream(messages, handler);

        verify(handler).onPartialThinking("思考");
        verify(handler).onPartialResponse("回复");
        verify(handler).onComplete("回复", "stop");
    }
}
