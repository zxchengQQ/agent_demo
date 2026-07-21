package com.agentdemo.agent.core;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * BaseAgent 接口契约测试
 * <p>
 * 验证标准来源：T-01 验证标准
 * 关联 AC：AC-002（首次发送消息触发流式输出）
 * </p>
 */
class BaseAgentTest {

    /**
     * 验证 BaseAgent 接口包含流式对话方法 chatStream，返回 TokenStream
     * 业务含义：Agent 需具备"边想边说"的流式输出能力
     */
    @Test
    void shouldHaveChatStreamMethodReturningTokenStream() throws NoSuchMethodException {
        Method method = BaseAgent.class.getMethod("chatStream", String.class, String.class);
        assertEquals(TokenStream.class, method.getReturnType(),
                "chatStream 方法应返回 TokenStream 类型");
    }

    /**
     * 验证 chatStream 方法的参数注解正确（@MemoryId + @UserMessage）
     * 业务含义：LangChain4j 依靠这两个注解关联会话记忆和用户消息
     */
    @Test
    void chatStreamShouldHaveCorrectParamAnnotations() throws NoSuchMethodException {
        Method method = BaseAgent.class.getMethod("chatStream", String.class, String.class);
        Parameter[] params = method.getParameters();

        // 第一个参数 sessionId 应标注 @MemoryId（会话隔离）
        assertNotNull(params[0].getAnnotation(MemoryId.class),
                "第一个参数应标注 @MemoryId 以关联会话记忆");
        // 第二个参数 message 应标注 @UserMessage（用户输入）
        assertNotNull(params[1].getAnnotation(UserMessage.class),
                "第二个参数应标注 @UserMessage 以标识用户消息");
    }

    /**
     * 验证原有同步 chat 方法仍然存在（不破坏现有功能）
     */
    @Test
    void shouldKeepSyncChatMethod() throws NoSuchMethodException {
        Method method = BaseAgent.class.getMethod("chat", String.class, String.class);
        assertEquals(String.class, method.getReturnType(),
                "chat 方法应返回 String 类型");
    }
}
