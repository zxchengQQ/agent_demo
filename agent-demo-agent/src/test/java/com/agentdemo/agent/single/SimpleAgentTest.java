package com.agentdemo.agent.single;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.agent.prompt.PromptTemplateLoader;
import com.agentdemo.llm.registry.ModelFactory;
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
import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SimpleAgent 工具绑定行为测试
 * <p>
 * 业务含义：验证工具按需加载后，Agent 未显式指定工具时仅绑定默认工具
 * （而非全量工具），避免 MCP/知识库等可选工具默认暴露给 Agent（BUG 修复）。
 * </p>
 */
class SimpleAgentTest {

    @Test
    @DisplayName("未指定工具时仅绑定默认工具，不加载全量工具（BUG 修复核心）")
    void firstCallShouldBindDefaultToolsInsteadOfAll() {
        // given: 默认工具列表配置 + 全量工具（含 MCP）mock
        TestableSimpleAgent agent = createAgentWithDefaultTools();

        // when: 无 tools 参数的流式对话（触发 getDelegate(modelId)）
        agent.chatStream("session-1", "你好");

        // then: 仅通过 getDefaultTools 获取默认工具，不再调用 listTools 全量扫描
        verify(agent.getToolRegistry(), atLeastOnce()).getDefaultTools(anyList());
        verify(agent.getToolRegistry(), never()).listTools();
    }

    @Test
    @DisplayName("指定工具时通过 resolveTools 解析并保留默认工具")
    void specifiedToolsShouldResolveAndKeepDefaults() {
        // given
        TestableSimpleAgent agent = createAgentWithDefaultTools();
        List<String> toolIds = List.of("builtin:httpGet");

        // when: 传入 tools 参数的流式对话（resolveSessionTools 解析指定 + 默认）
        agent.chatStream("session-1", "抓取网页", null, toolIds);

        // then: resolveTools 解析指定工具 + getDefaultTools 提供默认工具
        verify(agent.getToolRegistry(), atLeastOnce()).resolveTools(toolIds);
        verify(agent.getToolRegistry(), atLeastOnce()).getDefaultTools(anyList());
    }

    /**
     * 创建 SimpleAgent 并暴露 ToolRegistry mock 以便测试验证
     * 默认工具列表配置为 getCurrentTime，工具注册表含默认工具 + 一个 MCP 工具（模拟全量）
     */
    private TestableSimpleAgent createAgentWithDefaultTools() {
        ModelFactory modelFactory = mock(ModelFactory.class);
        when(modelFactory.getDefaultChatModel()).thenReturn(mock(ChatModel.class));
        when(modelFactory.getDefaultStreamingChatModel()).thenReturn(mock(StreamingChatModel.class));

        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.listTools()).thenReturn(Collections.emptyList());
        when(toolRegistry.getDefaultTools(anyList())).thenReturn(Collections.emptyList());
        when(toolRegistry.resolveTools(anyList())).thenReturn(Collections.emptyList());
        when(toolRegistry.getToolCount()).thenReturn(0);

        ChatMemoryManager memoryManager = mock(ChatMemoryManager.class);
        when(memoryManager.getMemory(anyString())).thenReturn(MessageWindowChatMemory.withMaxMessages(20));

        AgentConfig agentConfig = new AgentConfig();
        agentConfig.setEnableLogging(false);
        agentConfig.getTools().setDefaultTools(List.of("builtin:getCurrentTime"));

        return new TestableSimpleAgent(modelFactory, toolRegistry, memoryManager, agentConfig,
                mock(ToolSchemaConverter.class), mock(ToolExecutor.class),
                new PromptTemplateLoader(agentConfig));
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
                            ToolExecutor toolExecutor,
                            PromptTemplateLoader promptTemplateLoader) {
            super(modelFactory, toolRegistry, memoryManager, agentConfig, toolSchemaConverter, toolExecutor, promptTemplateLoader);
            this.toolRegistry = toolRegistry;
        }

        ToolRegistry getToolRegistry() {
            return toolRegistry;
        }
    }
}
