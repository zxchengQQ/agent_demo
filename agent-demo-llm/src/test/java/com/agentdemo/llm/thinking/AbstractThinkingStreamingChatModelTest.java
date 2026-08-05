package com.agentdemo.llm.thinking;

import com.agentdemo.llm.thinking.AbstractThinkingStreamingChatModel;
import com.agentdemo.llm.thinking.ThinkingStreamHandler;
import com.agentdemo.llm.thinking.ThinkingStreamingChatModel;
import com.agentdemo.llm.thinking.ToolCall;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task-18 测试：AbstractThinkingStreamingChatModel 抽象基类
 * <p>
 * 验证标准：
 * - 类声明为 abstract，实现 ThinkingStreamingChatModelProvider
 * - stream(messages, handler) 调用 buildRequestBody(messages, null) 后委托给 executeStream
 * - stream(messages, toolsJson, handler) 调用 buildRequestBody(messages, toolsJson) 后委托给 executeStream
 * - parseSseLine 正确处理 data: 前缀、[DONE]、reasoning_content、content、tool_calls、finish_reason、usage
 * - 子类仅需实现 buildRequestBody
 * </p>
 */
class AbstractThinkingStreamingChatModelTest {

    /** 测试用具体子类：实现 customizeRequestBody 钩子，记录调用，覆盖 fetchSseText/executeStream 避免真实 HTTP 调用 */
    static class TestableThinkingModel extends AbstractThinkingStreamingChatModel {
        final List<String> executeStreamCalls = new ArrayList<>();
        final List<String> fetchSseTextCalls = new ArrayList<>();
        final List<Integer> customizeCalls = new ArrayList<>();
        String nextSseText = "";

        TestableThinkingModel(String baseUrl, String apiKey, String modelName, Duration timeout) {
            super(baseUrl, apiKey, modelName, timeout);
        }

        @Override
        protected void customizeRequestBody(ObjectNode root) {
            customizeCalls.add(1);
        }

        @Override
        protected void executeStream(String requestBody, ThinkingStreamHandler handler) {
            executeStreamCalls.add(requestBody);
        }

        @Override
        protected String fetchSseText(String requestBody) {
            fetchSseTextCalls.add(requestBody);
            return nextSseText;
        }
    }

    private TestableThinkingModel model;
    private ThinkingStreamHandler handler;
    private final StringBuilder receivedResponse = new StringBuilder();
    private final StringBuilder receivedThinking = new StringBuilder();
    private final AtomicReference<String> finishReasonRef = new AtomicReference<>();
    private final AtomicReference<TokenUsage> usageRef = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        model = new TestableThinkingModel("http://test", "key", "test-model", Duration.ofSeconds(5));
        handler = new ThinkingStreamHandler() {
            @Override public void onPartialThinking(String thinking) { receivedThinking.append(thinking); }
            @Override public void onPartialResponse(String token) { receivedResponse.append(token); }
            @Override public void onComplete(String fullResponse, String finishReason, TokenUsage tokenUsage) {
                finishReasonRef.set(finishReason);
                usageRef.set(tokenUsage);
            }
            @Override public void onToolCalls(List<ToolCall> toolCalls) {}
            @Override public void onError(Throwable error) {}
        };
    }

    @Test
    @DisplayName("类应声明为 abstract")
    void shouldBeAbstract() {
        int modifiers = AbstractThinkingStreamingChatModel.class.getModifiers();
        assertTrue(Modifier.isAbstract(modifiers), "AbstractThinkingStreamingChatModel 必须是 abstract 类");
    }

    @Test
    @DisplayName("应实现 ThinkingStreamingChatModel 接口（保持调用方零改动）")
    void shouldImplementProviderInterface() {
        assertTrue(ThinkingStreamingChatModel.class.isAssignableFrom(AbstractThinkingStreamingChatModel.class));
    }

    @Test
    @DisplayName("stream(messages, handler) 应委托给 buildRequestBody + fetchSseText + parseSseResponse")
    void streamWithoutToolsShouldDelegate() {
        List<ChatMessage> messages = List.of(UserMessage.from("hello"));
        model.nextSseText = "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}";
        model.stream(messages, handler);

        assertEquals(1, model.customizeCalls.size(), "应调用 customizeRequestBody 钩子");
        assertEquals(1, model.fetchSseTextCalls.size(), "应调用 fetchSseText 获取 SSE 文本");
        assertEquals(0, model.executeStreamCalls.size(), "单轮模式不应调用 executeStream");
        assertEquals("hi", receivedResponse.toString(), "应通过 parseSseResponse 解析 SSE 内容");
    }

    @Test
    @DisplayName("stream(messages, toolsJson, handler) 应将 toolsJson 透传给 buildRequestBody 并委托给 executeStream")
    void streamWithToolsShouldPassToolsJson() {
        List<ChatMessage> messages = List.of(UserMessage.from("hi"));
        model.stream(messages, "[{\"type\":\"function\"}]", handler);

        assertEquals(1, model.customizeCalls.size(), "应调用 customizeRequestBody 钩子");
        assertEquals(1, model.executeStreamCalls.size(), "应委托给 executeStream");
    }

    @Test
    @DisplayName("fetchSseText 异常时应触发 handler.onError")
    void streamShouldTriggerOnErrorOnException() {
        TestableThinkingModel errModel = new TestableThinkingModel("http://x", "k", "m", Duration.ofSeconds(1)) {
            @Override
            protected String fetchSseText(String requestBody) {
                throw new RuntimeException("network down");
            }
        };
        List<ChatMessage> messages = List.of(UserMessage.from("hi"));
        java.util.concurrent.atomic.AtomicReference<Throwable> errRef = new java.util.concurrent.atomic.AtomicReference<>();
        ThinkingStreamHandler errHandler = new ThinkingStreamHandler() {
            @Override public void onPartialThinking(String thinking) {}
            @Override public void onPartialResponse(String token) {}
            @Override public void onComplete(String fullResponse, String finishReason, TokenUsage tokenUsage) {}
            @Override public void onToolCalls(List<ToolCall> toolCalls) {}
            @Override public void onError(Throwable error) { errRef.set(error); }
        };
        errModel.stream(messages, errHandler);
        assertNotNull(errRef.get(), "fetchSseText 异常时应触发 onError");
        assertTrue(errRef.get().getMessage().contains("network down"));
    }

    @Test
    @DisplayName("parseSseLine 应正确解析 reasoning_content 片段")
    void parseSseLineShouldExtractReasoningContent() {
        String sseLine = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"思考中\"}}]}";
        StringBuilder fullResponse = new StringBuilder();
        Map<Integer, ToolCall> acc = new LinkedHashMap<>();
        model.parseSseLine(sseLine, handler, fullResponse, acc);

        assertEquals("思考中", receivedThinking.toString());
        assertEquals(0, fullResponse.length(), "reasoning_content 不应累积到 fullResponse");
    }

    @Test
    @DisplayName("parseSseLine 应正确解析 content 片段并累积到 fullResponse")
    void parseSseLineShouldExtractContent() {
        String sseLine = "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}";
        StringBuilder fullResponse = new StringBuilder();
        Map<Integer, ToolCall> acc = new LinkedHashMap<>();
        model.parseSseLine(sseLine, handler, fullResponse, acc);

        assertEquals("你好", receivedResponse.toString());
        assertEquals("你好", fullResponse.toString());
    }

    @Test
    @DisplayName("parseSseLine 应忽略非 data: 前缀行和 [DONE] 标记")
    void parseSseLineShouldIgnoreNonDataAndDone() {
        StringBuilder fullResponse = new StringBuilder();
        Map<Integer, ToolCall> acc = new LinkedHashMap<>();
        model.parseSseLine(": comment line", handler, fullResponse, acc);
        model.parseSseLine("data: [DONE]", handler, fullResponse, acc);
        model.parseSseLine("", handler, fullResponse, acc);

        assertEquals(0, receivedResponse.length());
        assertEquals(0, receivedThinking.length());
    }

    @Test
    @DisplayName("parseSseLine 应解析 usage 字段并在 onComplete 时透传")
    void parseSseLineShouldExtractUsageAndPassToOnComplete() {
        StringBuilder fullResponse = new StringBuilder();
        Map<Integer, ToolCall> acc = new LinkedHashMap<>();
        // 先解析 usage chunk
        model.parseSseLine("data: {\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30}}",
            handler, fullResponse, acc);
        // 再解析 finish_reason chunk 触发 onComplete
        model.parseSseLine("data: {\"choices\":[{\"finish_reason\":\"stop\"}]}",
            handler, fullResponse, acc);

        assertEquals("stop", finishReasonRef.get());
        assertNotNull(usageRef.get());
        assertEquals(10, usageRef.get().inputTokenCount());
        assertEquals(20, usageRef.get().outputTokenCount());
        assertEquals(30, usageRef.get().totalTokenCount());
    }

    @Test
    @DisplayName("parseSseLine 应解析 tool_calls 分片并按 index 累积拼接")
    void parseSseLineShouldAccumulateToolCallsByIndex() {
        StringBuilder fullResponse = new StringBuilder();
        Map<Integer, ToolCall> acc = new LinkedHashMap<>();
        // 首个 chunk：含 id + name
        model.parseSseLine("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"arguments\":\"\"}}]}}]}",
            handler, fullResponse, acc);
        // 第二个 chunk：仅含 arguments 片段
        model.parseSseLine("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"city\\\":\\\"BJ\\\"}\"}}]}}]}",
            handler, fullResponse, acc);

        assertEquals(1, acc.size());
        ToolCall tc = acc.get(0);
        assertEquals("call_1", tc.getId());
        assertEquals("get_weather", tc.getFunctionName());
        assertEquals("{\"city\":\"BJ\"}", tc.getArguments());
    }

    @Test
    @DisplayName("finish_reason 出现时应触发 onToolCalls（如有累积）+ onComplete")
    void parseSseLineShouldTriggerOnToolCallsAndOnComplete() {
        java.util.concurrent.atomic.AtomicReference<List<ToolCall>> toolCallsRef = new java.util.concurrent.atomic.AtomicReference<>();
        ThinkingStreamHandler h = new ThinkingStreamHandler() {
            @Override public void onPartialThinking(String thinking) {}
            @Override public void onPartialResponse(String token) {}
            @Override public void onComplete(String fullResponse, String finishReason, TokenUsage tokenUsage) {
                finishReasonRef.set(finishReason);
            }
            @Override public void onToolCalls(List<ToolCall> toolCalls) { toolCallsRef.set(toolCalls); }
            @Override public void onError(Throwable error) {}
        };
        StringBuilder fullResponse = new StringBuilder();
        Map<Integer, ToolCall> acc = new LinkedHashMap<>();
        model.parseSseLine("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"f\",\"arguments\":\"{}\"}}]}}]}",
            h, fullResponse, acc);
        model.parseSseLine("data: {\"choices\":[{\"finish_reason\":\"tool_calls\"}]}",
            h, fullResponse, acc);

        assertNotNull(toolCallsRef.get(), "应触发 onToolCalls");
        assertEquals(1, toolCallsRef.get().size());
        assertEquals("tool_calls", finishReasonRef.get());
    }

    @Test
    @DisplayName("parseSseLine 解析异常时不应抛出，仅记录日志")
    void parseSseLineShouldSwallowParseException() {
        StringBuilder fullResponse = new StringBuilder();
        Map<Integer, ToolCall> acc = new LinkedHashMap<>();
        // 非法 JSON 不应抛出异常
        assertDoesNotThrow(() ->
            model.parseSseLine("data: {invalid json}", handler, fullResponse, acc));
    }

    @Test
    @DisplayName("parseSseResponse 应按行委托给 parseSseLine")
    void parseSseResponseShouldDelegateLineByLine() {
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"A\"}}]}\ndata: {\"choices\":[{\"delta\":{\"content\":\"B\"}}]}\n";
        model.parseSseResponse(sse, handler);
        assertEquals("AB", receivedResponse.toString());
    }

    @Test
    @DisplayName("parseSseResponse 空输入应安全返回")
    void parseSseResponseShouldHandleNullAndEmpty() {
        assertDoesNotThrow(() -> model.parseSseResponse(null, handler));
        assertDoesNotThrow(() -> model.parseSseResponse("", handler));
    }

    @Test
    @DisplayName("子类仅需实现 customizeRequestBody 钩子（抽象方法存在）")
    void customizeRequestBodyShouldBeAbstract() throws NoSuchMethodException {
        Method m = AbstractThinkingStreamingChatModel.class.getDeclaredMethod(
            "customizeRequestBody", ObjectNode.class);
        assertTrue(Modifier.isAbstract(m.getModifiers()) && Modifier.isProtected(m.getModifiers()),
            "customizeRequestBody 应为 protected abstract");
    }

    @Test
    @DisplayName("buildRequestBody 应为 final 模板方法（子类不应覆盖）")
    void buildRequestBodyShouldBeFinal() throws NoSuchMethodException {
        Method m = AbstractThinkingStreamingChatModel.class.getDeclaredMethod(
            "buildRequestBody", List.class, String.class);
        assertTrue(Modifier.isProtected(m.getModifiers()),
            "buildRequestBody 应为 protected");
        assertTrue(Modifier.isFinal(m.getModifiers()),
            "buildRequestBody 应为 final（模板方法，子类通过 customizeRequestBody 钩子差异化）");
    }
}
