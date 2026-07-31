package com.agentdemo.agent.single;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.llm.factory.ModelFactory;
import com.agentdemo.memory.shortterm.ChatMemoryManager;
import com.agentdemo.tools.registry.ToolExecutor;
import com.agentdemo.tools.registry.ToolRegistry;
import com.agentdemo.tools.registry.ToolSchemaConverter;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SimpleAgent 核心行为测试
 * <p>
 * 业务含义：验证 CR-003 后 SimpleAgent 能感知 ToolRegistry 中动态 Tool 的增减，
 * 并在下次对话前重建 delegate 绑定最新工具列表，无需重启应用。
 * </p>
 */
class SimpleAgentTest {

    @Test
    @DisplayName("首次调用 chatStream 触发 delegate 初始化并绑定工具")
    void firstCallShouldInitializeDelegate() {
        // given
        TestableSimpleAgent agent = createAgentWithToolCount(0);

        // when
        agent.chatStream("session-1", "你好");

        // then
        verify(agent.getToolRegistry(), atLeastOnce()).listTools();
    }

    @Test
    @DisplayName("Tool 数量变化后再次调用 chatStream 会重建 delegate 绑定新工具")
    void shouldRebuildDelegateWhenToolCountChanges() {
        // given: 初始无动态 Tool
        TestableSimpleAgent agent = createAgentWithToolCount(0);

        // when: 首次调用，创建 delegate
        agent.chatStream("session-1", "你好");
        verify(agent.getToolRegistry(), times(1)).listTools();

        // given: 新增一个知识库 Tool（模拟注册后 ToolRegistry 数量变化）
        when(agent.getToolRegistry().getToolCount()).thenReturn(1);

        // when: 再次调用，应触发重建
        agent.chatStream("session-2", "产品文档相关");

        // then: listTools 被调用 2 次，证明 delegate 已重建
        verify(agent.getToolRegistry(), times(2)).listTools();
    }

    @Test
    @DisplayName("Tool 数量未变化时复用已有 delegate")
    void shouldReuseDelegateWhenToolCountUnchanged() {
        // given: 初始 1 个 Tool
        TestableSimpleAgent agent = createAgentWithToolCount(1);

        // when: 首次调用
        agent.chatStream("session-1", "你好");
        verify(agent.getToolRegistry(), times(1)).listTools();

        // given: Tool 数量不变
        when(agent.getToolRegistry().getToolCount()).thenReturn(1);

        // when: 再次调用
        agent.chatStream("session-2", "再次询问");

        // then: listTools 仍只被调用 1 次，delegate 未重建
        verify(agent.getToolRegistry(), times(1)).listTools();
    }

    /**
     * 创建 SimpleAgent 并暴露 ToolRegistry mock 以便测试验证
     */
    private TestableSimpleAgent createAgentWithToolCount(int toolCount) {
        ModelFactory modelFactory = mock(ModelFactory.class);
        when(modelFactory.getDefaultChatModel()).thenReturn(mock(ChatModel.class));
        when(modelFactory.getDefaultStreamingChatModel()).thenReturn(mock(StreamingChatModel.class));

        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.listTools()).thenReturn(Collections.emptyList());
        when(toolRegistry.getToolCount()).thenReturn(toolCount);

        ChatMemoryManager memoryManager = mock(ChatMemoryManager.class);
        when(memoryManager.getMemory(anyString())).thenReturn(MessageWindowChatMemory.withMaxMessages(20));

        AgentConfig agentConfig = new AgentConfig();
        agentConfig.setEnableLogging(false);

        return new TestableSimpleAgent(modelFactory, toolRegistry, memoryManager, agentConfig,
                mock(ToolSchemaConverter.class), mock(ToolExecutor.class));
    }

    /**
     * 测试用 SimpleAgent 子类，暴露 ToolRegistry mock 用于验证
     */
    private static class TestableSimpleAgent extends SimpleAgent {

        private final ToolRegistry toolRegistry;

        TestableSimpleAgent(ModelFactory modelFactory,
                            ToolRegistry toolRegistry,
                            ChatMemoryManager memoryManager,
                            AgentConfig agentConfig,
                            ToolSchemaConverter toolSchemaConverter,
                            ToolExecutor toolExecutor) {
            super(modelFactory, toolRegistry, memoryManager, agentConfig, toolSchemaConverter, toolExecutor);
            this.toolRegistry = toolRegistry;
        }

        ToolRegistry getToolRegistry() {
            return toolRegistry;
        }
    }
}
