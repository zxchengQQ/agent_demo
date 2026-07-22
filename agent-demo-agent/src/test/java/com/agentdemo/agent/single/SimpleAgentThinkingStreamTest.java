package com.agentdemo.agent.single;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.agent.core.ThinkingTokenStream;
import com.agentdemo.llm.factory.ArkThinkingStreamingChatModel;
import com.agentdemo.llm.factory.ModelFactory;
import com.agentdemo.memory.shortterm.ChatMemoryManager;
import com.agentdemo.tools.registry.ToolRegistry;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.TokenStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SimpleAgent 思考流式能力测试（CR-001 新增）
 * <p>
 * 验证标准来源：T-19 验证标准
 * 关联 AC：AC-021（深度思考开关）、AC-022（推理过程流式展示）
 * </p>
 */
class SimpleAgentThinkingStreamTest {

    /**
     * 验证标准 1：chatThinkingStream 返回非 null 的 ThinkingTokenStream
     * 业务含义：开启深度思考时，Agent 应返回可用的思考流式令牌对象
     */
    @Test
    void chatThinkingStreamShouldReturnNonNullThinkingTokenStream() {
        // given: mock 依赖
        ModelFactory modelFactory = mock(ModelFactory.class);
        ArkThinkingStreamingChatModel thinkingModel = mock(ArkThinkingStreamingChatModel.class);
        when(modelFactory.getThinkingStreamingChatModel()).thenReturn(thinkingModel);

        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.listTools()).thenReturn(Collections.emptyList());
        when(toolRegistry.size()).thenReturn(0);

        ChatMemoryManager memoryManager = mock(ChatMemoryManager.class);
        when(memoryManager.getMemory(anyString())).thenReturn(MessageWindowChatMemory.withMaxMessages(20));

        AgentConfig agentConfig = new AgentConfig();
        agentConfig.setEnableLogging(false);

        SimpleAgent agent = new SimpleAgent(modelFactory, toolRegistry, memoryManager, agentConfig);

        // when: 调用 chatThinkingStream
        ThinkingTokenStream stream = agent.chatThinkingStream("test-session", "你好");

        // then: 返回非 null
        assertNotNull(stream, "chatThinkingStream 应返回非 null 的 ThinkingTokenStream");
    }

    /**
     * 验证标准 3：组装的消息包含系统提示词 + 历史消息 + 当前用户消息
     * 业务含义：思考流式路径需手动组装 ChatMessage（区别于 AiServices 自动注入），
     * 确保多轮对话上下文完整传递给方舟 API
     */
    @Test
    @SuppressWarnings("unchecked")
    void chatThinkingStreamShouldAssembleMessagesWithSystemPromptHistoryAndUserMessage() {
        // given: mock 依赖
        ModelFactory modelFactory = mock(ModelFactory.class);
        ArkThinkingStreamingChatModel thinkingModel = mock(ArkThinkingStreamingChatModel.class);
        when(modelFactory.getThinkingStreamingChatModel()).thenReturn(thinkingModel);

        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.listTools()).thenReturn(Collections.emptyList());
        when(toolRegistry.size()).thenReturn(0);

        // 构造带历史消息的 memory
        ChatMemoryManager memoryManager = mock(ChatMemoryManager.class);
        MessageWindowChatMemory memory = MessageWindowChatMemory.withMaxMessages(20);
        memory.add(UserMessage.from("历史用户消息"));
        memory.add(AiMessage.from("历史助手回复"));
        when(memoryManager.getMemory(anyString())).thenReturn(memory);

        AgentConfig agentConfig = new AgentConfig();
        agentConfig.setEnableLogging(false);
        String systemPrompt = "你是测试助手";
        agentConfig.setDefaultSystemPrompt(systemPrompt);

        SimpleAgent agent = new SimpleAgent(modelFactory, toolRegistry, memoryManager, agentConfig);

        // when: 调用 chatThinkingStream 并 start 触发 model.stream
        ThinkingTokenStream stream = agent.chatThinkingStream("test-session", "你好");
        stream.start();

        // then: 捕获传给 model.stream 的 messages，验证组装内容
        ArgumentCaptor<List<ChatMessage>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(thinkingModel).stream(messagesCaptor.capture(), any());

        List<ChatMessage> messages = messagesCaptor.getValue();

        // 验证包含系统提示词（来自 agentConfig.getDefaultSystemPrompt()）
        assertTrue(messages.stream().anyMatch(m -> m instanceof SystemMessage
                        && ((SystemMessage) m).text().equals(systemPrompt)),
                "消息列表应包含系统提示词: " + systemPrompt);

        // 验证包含历史用户消息
        assertTrue(messages.stream().anyMatch(m -> m instanceof UserMessage
                        && extractText((UserMessage) m).contains("历史用户消息")),
                "消息列表应包含历史用户消息");

        // 验证包含历史助手回复
        assertTrue(messages.stream().anyMatch(m -> m instanceof AiMessage
                        && ((AiMessage) m).text().contains("历史助手回复")),
                "消息列表应包含历史助手回复");

        // 验证包含当前用户消息
        assertTrue(messages.stream().anyMatch(m -> m instanceof UserMessage
                        && extractText((UserMessage) m).contains("你好")),
                "消息列表应包含当前用户消息");
    }

    /**
     * 验证标准 2：不破坏现有 chatStream() 方法
     * 业务含义：新增 chatThinkingStream 不应影响原有流式对话能力
     */
    @Test
    void chatThinkingStreamShouldNotBreakExistingChatStream() {
        // given: mock 依赖（同时 mock 同步与流式模型，模拟完整 SimpleAgent）
        ModelFactory modelFactory = mock(ModelFactory.class);
        when(modelFactory.getDefaultChatModel()).thenReturn(mock(dev.langchain4j.model.chat.ChatModel.class));
        when(modelFactory.getDefaultStreamingChatModel()).thenReturn(mock(dev.langchain4j.model.chat.StreamingChatModel.class));
        ArkThinkingStreamingChatModel thinkingModel = mock(ArkThinkingStreamingChatModel.class);
        when(modelFactory.getThinkingStreamingChatModel()).thenReturn(thinkingModel);

        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.listTools()).thenReturn(Collections.emptyList());
        when(toolRegistry.size()).thenReturn(0);

        ChatMemoryManager memoryManager = mock(ChatMemoryManager.class);
        when(memoryManager.getMemory(anyString())).thenReturn(MessageWindowChatMemory.withMaxMessages(20));

        AgentConfig agentConfig = new AgentConfig();
        agentConfig.setEnableLogging(false);

        SimpleAgent agent = new SimpleAgent(modelFactory, toolRegistry, memoryManager, agentConfig);

        // when: 调用原有 chatStream（不调用 start 避免触发真实 LLM）
        TokenStream tokenStream = agent.chatStream("test-session", "你好");

        // then: 仍能返回非 null 的 TokenStream（原有能力未破坏）
        assertNotNull(tokenStream, "原有 chatStream 应仍能返回非 null 的 TokenStream");
    }

    /**
     * 辅助方法：从 UserMessage 提取文本内容
     * 业务含义：UserMessage 由 List<Content> 组成，需过滤 TextContent 获取文本
     */
    private String extractText(UserMessage um) {
        return um.contents().stream()
                .filter(c -> c instanceof TextContent)
                .map(c -> ((TextContent) c).text())
                .findFirst()
                .orElse("");
    }
}
