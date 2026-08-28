package com.agentdemo.agent.core;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.agent.prompt.PromptTemplateLoader;
import com.agentdemo.agent.single.SessionToolResolver;
import com.agentdemo.agent.single.SkillToolInterceptor;
import com.agentdemo.llm.registry.ModelFactory;
import com.agentdemo.llm.thinking.ThinkingStreamingChatModel;
import com.agentdemo.memory.shortterm.ChatMemoryManager;
import com.agentdemo.skill.prompt.SkillPromptComposer;
import com.agentdemo.tools.registry.ToolExecutor;
import com.agentdemo.tools.registry.ToolRegistry;
import com.agentdemo.tools.registry.ToolSchemaConverter;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 规划历史行为测试（agent-context-engineering Task-15，AC-N03）
 * <p>
 * 端到端断言：UnifiedChatStream 启动普通消息时，TaskPlanJudge 收到最近会话历史
 * （记忆中的指代对象消息注入于当前消息之前），指代类消息可据此解析。
 * </p>
 */
class PlanHistoryBehaviorTest {

    private ModelFactory modelFactory;
    private ThinkingStreamingChatModel thinkingModel;
    private ChatMemoryManager memoryManager;
    private ChatMemory chatMemory;
    private TaskPlanJudge taskPlanJudge;
    private ToolSchemaConverter toolSchemaConverter;
    private AgentConfig agentConfig;
    private HumanInteractionManager humanInteractionManager;
    private SessionToolResolver sessionToolResolver;

    @BeforeEach
    void setUp() {
        modelFactory = mock(ModelFactory.class);
        thinkingModel = mock(ThinkingStreamingChatModel.class);
        memoryManager = mock(ChatMemoryManager.class);
        chatMemory = mock(ChatMemory.class);
        taskPlanJudge = mock(TaskPlanJudge.class);
        toolSchemaConverter = mock(ToolSchemaConverter.class);
        agentConfig = new AgentConfig();
        humanInteractionManager = new HumanInteractionManager();
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        sessionToolResolver = new SessionToolResolver(toolRegistry, agentConfig);

        when(modelFactory.getDefaultThinkingStreamingChatModel()).thenReturn(thinkingModel);
        when(toolSchemaConverter.convertToJson(anyList())).thenReturn("[]");
        when(toolSchemaConverter.convertToDescriptionText(anyList())).thenReturn("工具描述");
        when(memoryManager.getMemory(anyString())).thenReturn(chatMemory);
        when(taskPlanJudge.judge(anyString(), anyString(), isNull(), anyList())).thenReturn(List.of());
    }

    @Test
    @DisplayName("启动普通消息：规划判断携带会话历史（指代解析，AC-N03）")
    void 规划判断_携带会话历史() {
        // 记忆中已有前轮"订单 ORD-12345"上下文
        List<dev.langchain4j.data.message.ChatMessage> history = new ArrayList<>();
        history.add(UserMessage.from("查一下订单 ORD-12345 的状态"));
        history.add(AiMessage.from("您的订单已发货"));
        when(chatMemory.messages()).thenReturn(history);

        // 直答停止（触发 onComplete 让流完成）
        org.mockito.Mockito.doAnswer(inv -> {
            com.agentdemo.llm.thinking.ThinkingStreamHandler handler = inv.getArgument(2);
            handler.onPartialResponse("回答");
            handler.onComplete("回答", "stop", null);
            return null;
        }).when(thinkingModel).stream(any(), any(), any());
        when(taskPlanJudge.judge(anyString(), anyString(), isNull(), anyList())).thenReturn(List.of());

        UnifiedChatStream stream = new UnifiedChatStream(
                "sess-hist", "帮我把它做个深度分析", null, false, false, null, null,
                modelFactory, memoryManager, agentConfig, toolSchemaConverter, null,
                new PromptTemplateLoader(agentConfig), humanInteractionManager,
                sessionToolResolver, taskPlanJudge);
        stream.onComplete(org.mockito.Mockito.mock(ThinkingTokenStream.CompleteConsumer.class));
        stream.start();
        // 断言 judge 收到历史（最近 6 条，含指代对象实体）
        ArgumentCaptor<List<dev.langchain4j.data.message.ChatMessage>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(taskPlanJudge).judge(anyString(), anyString(), isNull(), historyCaptor.capture());
        List<dev.langchain4j.data.message.ChatMessage> sent = historyCaptor.getValue();
        assertTrue(sent.stream().anyMatch(m -> m instanceof UserMessage um
                && um.hasSingleText() && um.singleText().contains("ORD-12345")),
                "规划判断应携带含指代对象实体的会话历史（AC-N03）");
    }
}
