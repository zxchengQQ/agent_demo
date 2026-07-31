package com.agentdemo.llm.factory;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ThinkingStreamingChatModel 抽象接口测试（CR-001 Task-09）
 * <p>
 * 验证标准来源：Task-09 验证标准
 * 关联 AC：AC-015, AC-016, AC-017
 * </p>
 * <p>
 * 测试目标：
 * 1. 接口定义两个 stream 方法
 * 2. 接口可被 ArkThinkingStreamingChatModel 和 BailianThinkingStreamingChatModel 实现
 * 3. 接口的抽象方法签名与现有调用方期望一致
 * </p>
 */
class ThinkingStreamingChatModelTest {

    /**
     * 验证标准 1：接口定义包含 stream(messages, handler) 方法
     * 业务含义：单轮思考流式调用（不带工具调用）
     */
    @Test
    void shouldDefineStreamMethodWithoutTools() throws NoSuchMethodException {
        // Given: ThinkingStreamingChatModel 接口
        Class<?> iface = ThinkingStreamingChatModel.class;

        // When: 查找 stream(List, ThinkingStreamHandler) 方法
        var method = iface.getMethod("stream", List.class, ThinkingStreamHandler.class);

        // Then: 方法存在且为 void
        assertNotNull(method);
        assertEquals(void.class, method.getReturnType());
    }

    /**
     * 验证标准 2：接口定义包含 stream(messages, toolsJson, handler) 方法
     * 业务含义：ReAct 思考流式调用（带工具调用）
     */
    @Test
    void shouldDefineStreamMethodWithTools() throws NoSuchMethodException {
        // Given: ThinkingStreamingChatModel 接口
        Class<?> iface = ThinkingStreamingChatModel.class;

        // When: 查找 stream(List, String, ThinkingStreamHandler) 方法
        var method = iface.getMethod("stream", List.class, String.class, ThinkingStreamHandler.class);

        // Then: 方法存在且为 void
        assertNotNull(method);
        assertEquals(void.class, method.getReturnType());
    }

    /**
     * 验证标准 3：接口可被 ArkThinkingStreamingChatModel 实现
     * 业务含义：确保 Ark 实现类遵循接口契约
     */
    @Test
    void shouldBeImplementableByArkModel() {
        // Given: ArkThinkingStreamingChatModel 实例
        ArkThinkingStreamingChatModel arkModel = new ArkThinkingStreamingChatModel(
                "https://ark.cn-beijing.volces.com/api/coding/v3",
                "test-key",
                "doubao-seed-2.0-pro",
                java.time.Duration.ofSeconds(60));

        // Then: ArkThinkingStreamingChatModel 必须是 ThinkingStreamingChatModel 的实现
        assertTrue(ThinkingStreamingChatModel.class.isAssignableFrom(arkModel.getClass()),
                "ArkThinkingStreamingChatModel 应实现 ThinkingStreamingChatModel 接口");
    }

    /**
     * 验证标准 4：接口是 public 抽象接口
     * 业务含义：确保接口可被外部模块访问和实现
     */
    @Test
    void shouldBePublicInterface() {
        // Then: ThinkingStreamingChatModel 必须是接口
        assertTrue(ThinkingStreamingChatModel.class.isInterface());
        // Then: 必须是 public
        assertTrue(java.lang.reflect.Modifier.isPublic(ThinkingStreamingChatModel.class.getModifiers()));
    }
}
