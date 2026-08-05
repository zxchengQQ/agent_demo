package com.agentdemo.llm.thinking;

import com.agentdemo.llm.thinking.ThinkingStreamHandler;
import com.agentdemo.llm.thinking.ToolCall;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * ThinkingStreamHandler 接口扩展测试（Task-02）
 * <p>
 * 验证内容：
 * 1. ToolCall 数据类字段完整性（id、functionName、arguments）
 * 2. ThinkingStreamHandler 接口方法签名正确性（onToolCalls、onComplete 新签名）
 * 3. 匿名实现类可正常实现所有接口方法
 * </p>
 */
class ThinkingStreamHandlerTest {

    // ========== ToolCall 数据类测试 ==========

    /**
     * 验证 ToolCall 的 id 字段 getter/setter 正常工作
     */
    @Test
    void toolCallShouldHaveIdFieldWithGetterAndSetter() {
        ToolCall toolCall = new ToolCall();
        toolCall.setId("call_001");
        assertEquals("call_001", toolCall.getId());
    }

    /**
     * 验证 ToolCall 的 functionName 字段 getter/setter 正常工作
     */
    @Test
    void toolCallShouldHaveFunctionNameFieldWithGetterAndSetter() {
        ToolCall toolCall = new ToolCall();
        toolCall.setFunctionName("get_weather");
        assertEquals("get_weather", toolCall.getFunctionName());
    }

    /**
     * 验证 ToolCall 的 arguments 字段 getter/setter 正常工作
     */
    @Test
    void toolCallShouldHaveArgumentsFieldWithGetterAndSetter() {
        ToolCall toolCall = new ToolCall();
        toolCall.setArguments("{\"city\":\"北京\"}");
        assertEquals("{\"city\":\"北京\"}", toolCall.getArguments());
    }

    // ========== ThinkingStreamHandler 接口方法签名测试 ==========

    /**
     * 验证接口包含 onToolCalls(List<ToolCall>) 方法声明
     */
    @Test
    void interfaceShouldDeclareOnToolCallsMethod() throws NoSuchMethodException {
        Method method = ThinkingStreamHandler.class.getDeclaredMethod(
                "onToolCalls", List.class);

        assertNotNull(method, "ThinkingStreamHandler 应声明 onToolCalls 方法");
        assertEquals(void.class, method.getReturnType(),
                "onToolCalls 返回类型应为 void");
    }

    /**
     * 验证 onComplete 方法签名包含 finishReason 和 tokenUsage 参数（Task-15 扩展）
     */
    @Test
    void onCompleteShouldHaveFinishReasonAndTokenUsageParameter() throws NoSuchMethodException {
        Method method = ThinkingStreamHandler.class.getDeclaredMethod(
                "onComplete", String.class, String.class, TokenUsage.class);

        assertNotNull(method, "onComplete 应有三个参数：fullResponse、finishReason 和 tokenUsage");
        assertEquals(void.class, method.getReturnType(),
                "onComplete 返回类型应为 void");
    }

    // ========== 匿名实现类测试 ==========

    /**
     * 验证可以创建匿名实现类实现所有接口方法（包括新增的 onToolCalls）
     */
    @Test
    void anonymousImplementationShouldWorkForAllMethods() {
        ToolCall toolCall = new ToolCall();
        toolCall.setId("call_001");
        toolCall.setFunctionName("get_weather");
        toolCall.setArguments("{\"city\":\"北京\"}");

        final StringBuilder responseBuffer = new StringBuilder();
        final StringBuilder finishReasonBuffer = new StringBuilder();
        final List<ToolCall> toolCallsBuffer = new ArrayList<>();

        ThinkingStreamHandler handler = new ThinkingStreamHandler() {
            @Override
            public void onPartialThinking(String thinking) {
                // 推理内容片段回调
            }

            @Override
            public void onPartialResponse(String token) {
                responseBuffer.append(token);
            }

            @Override
            public void onComplete(String fullResponse, String finishReason, TokenUsage tokenUsage) {
                responseBuffer.append(fullResponse);
                finishReasonBuffer.append(finishReason);
            }

            @Override
            public void onToolCalls(List<ToolCall> toolCalls) {
                toolCallsBuffer.addAll(toolCalls);
            }

            @Override
            public void onError(Throwable error) {
                // 异常回调
            }
        };

        // 调用各方法验证不报错
        handler.onPartialThinking("思考中");
        handler.onPartialResponse("你好");
        handler.onComplete("完整回复", "stop", null);
        handler.onToolCalls(Collections.singletonList(toolCall));
        handler.onError(new RuntimeException("测试异常"));

        // 验证回调数据正确传递
        assertEquals("你好完整回复", responseBuffer.toString());
        assertEquals("stop", finishReasonBuffer.toString());
        assertEquals(1, toolCallsBuffer.size());
        assertEquals("call_001", toolCallsBuffer.get(0).getId());
        assertEquals("get_weather", toolCallsBuffer.get(0).getFunctionName());
        assertEquals("{\"city\":\"北京\"}", toolCallsBuffer.get(0).getArguments());
    }
}
