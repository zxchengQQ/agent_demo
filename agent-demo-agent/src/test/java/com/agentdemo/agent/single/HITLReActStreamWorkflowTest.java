package com.agentdemo.agent.single;

import com.agentdemo.agent.core.HitlTokenStream;
import com.agentdemo.agent.core.HumanInteractionManager;
import com.agentdemo.llm.thinking.ThinkingStreamHandler;
import com.agentdemo.llm.thinking.ThinkingStreamingChatModel;
import com.agentdemo.llm.thinking.ToolCall;
import com.agentdemo.tools.registry.ToolExecutor;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * HITLReActStream 工作流上下文适配测试
 * <p>
 * 验证标准来源：Task-04 验证标准
 * 关联 AC：AC-T01（askUser 拦截机制）、AC-N01（Agent 主动追问）、AC-M02（Agent 消息列表保持）
 * </p>
 * <p>
 * 业务含义：工作流编排场景下，HITLReActStream 的消息列表由外部（AgentDefinition 系统提示词 + 输入）
 * 构建后直接传入，不依赖 ChatMemory；sessionId 使用复合键（executionId:agentIndex）在
 * HumanInteractionManager 中隔离不同 Agent 的暂停状态。现有构造器天然支持该场景，
 * 本测试验证工作流上下文的兼容性（YAGNI：无需新增冗余构造器）。
 * </p>
 */
class HITLReActStreamWorkflowTest {

    private ThinkingStreamingChatModel model;
    private ToolExecutor toolExecutor;
    private HumanInteractionManager humanInteractionManager;

    @BeforeEach
    void setUp() {
        model = mock(ThinkingStreamingChatModel.class);
        toolExecutor = mock(ToolExecutor.class);
        humanInteractionManager = mock(HumanInteractionManager.class);
    }

    /** 模拟单轮 LLM 调用返回 stop（无工具调用） */
    private Object mockSingleRoundStop(org.mockito.invocation.InvocationOnMock invocation) {
        ThinkingStreamHandler handler = invocation.getArgument(2);
        handler.onPartialResponse("工作流 Agent 最终回答");
        handler.onComplete("工作流 Agent 最终回答", "stop", null);
        return null;
    }

    /** 模拟单轮 LLM 调用返回指定问题的 askUser 工具调用 */
    private Object mockSingleRoundAskUser(org.mockito.invocation.InvocationOnMock invocation, String question) {
        ThinkingStreamHandler handler = invocation.getArgument(2);
        handler.onPartialResponse("需要追问用户");
        ToolCall tc = new ToolCall();
        tc.setId("call_w001");
        tc.setFunctionName("askUser");
        tc.setArguments("{\"type\":\"text\",\"question\":\"" + question + "\",\"options\":[]}");
        handler.onToolCalls(Collections.singletonList(tc));
        handler.onComplete("需要追问用户", "tool_calls", null);
        return null;
    }

    // ========== 验证标准：外部 messages 列表，不依赖 ChatMemory ==========

    @Test
    void 外部构建消息列表应被原样用于ReAct循环() {
        doAnswer(this::mockSingleRoundStop).when(model).stream(any(), any(), any());

        // 外部构建消息列表（模拟 AgentDefinition 系统提示词 + 输入）
        List<ChatMessage> externalMessages = new ArrayList<>();
        externalMessages.add(SystemMessage.from("你是订单处理助手"));
        externalMessages.add(UserMessage.from("请帮我查询订单"));

        HITLReActStream stream = new HITLReActStream(
                model, externalMessages, "[workflow-tools]", toolExecutor,
                humanInteractionManager, "exec-101:1", "model-001", 0, 8);

        HitlTokenStream.CompleteConsumer completeConsumer = mock(HitlTokenStream.CompleteConsumer.class);
        stream.onComplete(completeConsumer);
        stream.start();

        // 外部消息列表被原样用于 ReAct 循环，未被替换
        verify(completeConsumer).accept("工作流 Agent 最终回答");
        assertEquals(2, externalMessages.size(), "外部消息列表应保持原样");
    }

    // ========== 验证标准：askUser 拦截逻辑在新构造器下正常工作 ==========

    @Test
    void 复合sessionId下askUser应拦截并保存状态() {
        doAnswer(inv -> mockSingleRoundAskUser(inv, "请确认收货地址"))
                .when(model).stream(any(), any(), any());

        List<ChatMessage> externalMessages = new ArrayList<>();
        externalMessages.add(SystemMessage.from("你是订单处理助手"));
        externalMessages.add(UserMessage.from("帮我处理订单"));

        HITLReActStream stream = new HITLReActStream(
                model, externalMessages, "[workflow-tools]", toolExecutor,
                humanInteractionManager, "exec-101:1", "model-001", 0, 8);

        HitlTokenStream.AskUserConsumer askUserConsumer = mock(HitlTokenStream.AskUserConsumer.class);
        HitlTokenStream.CompleteConsumer completeConsumer = mock(HitlTokenStream.CompleteConsumer.class);

        stream.onAskUser(askUserConsumer);
        stream.onComplete(completeConsumer);
        stream.start();

        // askUser 被拦截：onAskUser 触发，onComplete 不触发
        verify(askUserConsumer).accept("text", "请确认收货地址", Collections.emptyList(), 0);
        verify(completeConsumer, never()).accept(anyString());
        // 复合 sessionId 透传给 HumanInteractionManager
        verify(humanInteractionManager).saveInteraction(
                eq("exec-101:1"), any(), eq("text"), eq("请确认收货地址"),
                eq(Collections.emptyList()), eq(0), eq("model-001"), eq("[workflow-tools]"));
    }

    @Test
    void askUser拦截时保存的消息列表应保留外部构建内容() {
        doAnswer(inv -> mockSingleRoundAskUser(inv, "请确认收货地址"))
                .when(model).stream(any(), any(), any());

        List<ChatMessage> externalMessages = new ArrayList<>();
        externalMessages.add(SystemMessage.from("你是订单处理助手"));
        externalMessages.add(UserMessage.from("帮我处理订单"));

        HITLReActStream stream = new HITLReActStream(
                model, externalMessages, "[workflow-tools]", toolExecutor,
                humanInteractionManager, "exec-101:1", "model-001", 0, 8);
        stream.onAskUser(mock(HitlTokenStream.AskUserConsumer.class));
        stream.start();

        // 捕获 saveInteraction 保存的消息列表，验证保留外部构建的系统提示词 + 输入
        ArgumentCaptor<List<ChatMessage>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(humanInteractionManager).saveInteraction(
                eq("exec-101:1"), messagesCaptor.capture(), anyString(), anyString(), any(), anyInt(), anyString(), anyString());

        List<ChatMessage> saved = messagesCaptor.getValue();
        assertTrue(saved.stream().anyMatch(m -> m instanceof SystemMessage), "保存的消息列表应包含系统提示词");
        assertTrue(saved.stream().anyMatch(m -> m instanceof UserMessage), "保存的消息列表应包含用户输入");
    }

    // ========== 验证标准：复合 sessionId（executionId:agentIndex）在 HumanInteractionManager 中隔离 ==========

    @Test
    void 复合sessionId应在HumanInteractionManager中正确隔离() {
        // 真实实现验证复合键隔离
        HumanInteractionManager realManager = new HumanInteractionManager();

        int[] callCount = {0};
        doAnswer(inv -> {
            callCount[0]++;
            String question = callCount[0] == 1 ? "Agent1的问题" : "Agent2的问题";
            return mockSingleRoundAskUser(inv, question);
        }).when(model).stream(any(), any(), any());

        // 同一工作流 exec-101 中两个不同 Agent（index=1 / index=2）
        HITLReActStream stream1 = new HITLReActStream(
                model, new ArrayList<>(List.of(UserMessage.from("任务A"))), "[t]", toolExecutor,
                realManager, "exec-101:1", "model-001", 0, 8);
        stream1.onAskUser(mock(HitlTokenStream.AskUserConsumer.class));
        stream1.start();

        HITLReActStream stream2 = new HITLReActStream(
                model, new ArrayList<>(List.of(UserMessage.from("任务B"))), "[t]", toolExecutor,
                realManager, "exec-101:2", "model-001", 0, 8);
        stream2.onAskUser(mock(HitlTokenStream.AskUserConsumer.class));
        stream2.start();

        // 两个复合键的暂停状态独立保存，互不覆盖
        assertTrue(realManager.hasPending("exec-101:1"), "exec-101:1 应有 pending 状态");
        assertTrue(realManager.hasPending("exec-101:2"), "exec-101:2 应有 pending 状态");
        assertEquals("Agent1的问题", realManager.loadInteraction("exec-101:1").getQuestion());
        assertEquals("Agent2的问题", realManager.loadInteraction("exec-101:2").getQuestion());
    }
}
