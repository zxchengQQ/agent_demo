package com.agentdemo.agent.single;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.llm.factory.ModelFactory;
import com.agentdemo.memory.shortterm.ChatMemoryManager;
import com.agentdemo.tools.registry.ToolRegistry;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.TokenStream;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SimpleAgent 流式能力测试
 * <p>
 * 验证标准来源：T-02 验证标准
 * 关联 AC：AC-002（首次发送消息触发流式输出）
 * </p>
 */
class SimpleAgentStreamingTest {

    /**
     * 验证 SimpleAgent 初始化 delegate 时绑定了 streamingChatModel
     * 业务含义：Agent 需绑定流式模型才能逐字输出回复
     */
    @Test
    void shouldBindStreamingChatModelWhenDelegateInitialized() {
        // given: mock 依赖
        ModelFactory modelFactory = mock(ModelFactory.class);
        when(modelFactory.getDefaultChatModel()).thenReturn(mock(ChatModel.class));
        when(modelFactory.getDefaultStreamingChatModel()).thenReturn(mock(StreamingChatModel.class));

        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.listTools()).thenReturn(Collections.emptyList());
        when(toolRegistry.size()).thenReturn(0);

        ChatMemoryManager memoryManager = mock(ChatMemoryManager.class);
        when(memoryManager.getMemory(anyString())).thenReturn(MessageWindowChatMemory.withMaxMessages(20));

        AgentConfig agentConfig = new AgentConfig();
        agentConfig.setEnableLogging(false);

        SimpleAgent agent = new SimpleAgent(modelFactory, toolRegistry, memoryManager, agentConfig);

        // when: 触发 delegate 初始化（调用 chatStream）
        try {
            agent.chatStream("test-session", "你好");
        } catch (Exception e) {
            // mock 模型可能导致流式调用异常，但本测试只验证 streamingChatModel 是否被绑定
        }

        // then: streamingChatModel 被请求（证明 getDelegate 绑定了流式模型）
        verify(modelFactory, atLeastOnce()).getDefaultStreamingChatModel();
    }

    /**
     * 验证 chatStream 返回非 null 的 TokenStream
     * 业务含义：调用流式对话应返回可用的流式令牌对象
     */
    @Test
    void chatStreamShouldReturnNonNullTokenStream() {
        // given: mock 依赖
        ModelFactory modelFactory = mock(ModelFactory.class);
        when(modelFactory.getDefaultChatModel()).thenReturn(mock(ChatModel.class));
        when(modelFactory.getDefaultStreamingChatModel()).thenReturn(mock(StreamingChatModel.class));

        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.listTools()).thenReturn(Collections.emptyList());
        when(toolRegistry.size()).thenReturn(0);

        ChatMemoryManager memoryManager = mock(ChatMemoryManager.class);
        when(memoryManager.getMemory(anyString())).thenReturn(MessageWindowChatMemory.withMaxMessages(20));

        AgentConfig agentConfig = new AgentConfig();
        agentConfig.setEnableLogging(false);

        SimpleAgent agent = new SimpleAgent(modelFactory, toolRegistry, memoryManager, agentConfig);

        // when: 调用 chatStream
        TokenStream tokenStream = agent.chatStream("test-session", "你好");

        // then: 返回非 null（不调用 start() 不会触发真实 LLM 调用）
        assertNotNull(tokenStream, "chatStream 应返回非 null 的 TokenStream");
    }
}
