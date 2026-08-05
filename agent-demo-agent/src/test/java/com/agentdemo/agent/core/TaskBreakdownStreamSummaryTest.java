package com.agentdemo.agent.core;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.llm.thinking.ThinkingStreamingChatModel;
import com.agentdemo.llm.registry.ModelFactory;
import com.agentdemo.llm.thinking.ThinkingStreamHandler;
import com.agentdemo.memory.shortterm.ChatMemoryManager;
import com.agentdemo.tools.registry.ToolExecutor;
import com.agentdemo.tools.registry.ToolSchemaConverter;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * TaskBreakdownStream 总结阶段和降级路径测试（Task-05）
 * <p>
 * 验证标准来源：Task-05 验证标准
 * 关联 AC：AC-002（无需拆解降级）、AC-004（总结生成）、AC-009（LLM判断无需拆解）
 * </p>
 */
class TaskBreakdownStreamSummaryTest {

    private ModelFactory modelFactory;
    private ChatModel chatModel;
    private ThinkingStreamingChatModel thinkingModel;
    private ChatMemoryManager memoryManager;
    private AgentConfig agentConfig;
    private ToolSchemaConverter toolSchemaConverter;
    private ToolExecutor toolExecutor;
    private ChatMemory chatMemory;

    @BeforeEach
    void setUp() {
        modelFactory = mock(ModelFactory.class);
        chatModel = mock(ChatModel.class);
        thinkingModel = mock(ThinkingStreamingChatModel.class);
        memoryManager = mock(ChatMemoryManager.class);
        toolSchemaConverter = mock(ToolSchemaConverter.class);
        toolExecutor = mock(ToolExecutor.class);
        agentConfig = new AgentConfig();
        chatMemory = mock(ChatMemory.class);

        when(modelFactory.getDefaultChatModel()).thenReturn(chatModel);
        when(modelFactory.getThinkingStreamingChatModel()).thenReturn(thinkingModel);
        when(toolSchemaConverter.convertToJson()).thenReturn("[]");
        when(toolSchemaConverter.convertToDescriptionText()).thenReturn("工具描述");
        when(memoryManager.getMemory(anyString())).thenReturn(chatMemory);
        when(chatMemory.messages()).thenReturn(new ArrayList<>());
    }

    private TaskBreakdownStream createStream(String message, boolean enableThinking) {
        return new TaskBreakdownStream(
                "test-session", message, enableThinking,
                modelFactory, memoryManager, agentConfig,
                toolSchemaConverter, toolExecutor);
    }

    private void mockPlanResponse(String json) {
        AiMessage aiMessage = AiMessage.from(json);
        ChatResponse response = mock(ChatResponse.class);
        when(response.aiMessage()).thenReturn(aiMessage);
        when(chatModel.chat(anyList())).thenReturn(response);
    }

    // ========== 验证标准 1: 所有子任务完成后 onSummaryToken 被调用 ==========

    @Test
    void shouldTriggerOnSummaryTokenAfterAllTasksComplete() {
        mockPlanResponse("[{\"title\":\"任务1\"}]");

        // 第1次调用=Phase 2子任务执行，第2次调用=Phase 3总结
        int[] callCount = {0};
        doAnswer(invocation -> {
            callCount[0]++;
            ThinkingStreamHandler handler = invocation.getArgument(2);
            if (callCount[0] == 1) {
                // Phase 2: 子任务执行
                handler.onPartialResponse("子任务结果");
                handler.onComplete("子任务结果", "stop", null);
            } else {
                // Phase 3: 总结
                handler.onPartialResponse("总结内容");
                handler.onComplete("总结内容", "stop", null);
            }
            return null;
        }).when(thinkingModel).stream(any(), any(), any());

        TaskBreakdownStream.TokenConsumer summaryTokenConsumer = mock(TaskBreakdownStream.TokenConsumer.class);

        createStream("任务", false)
                .onSummaryToken(summaryTokenConsumer)
                .start();

        verify(summaryTokenConsumer).accept("总结内容");
    }

    // ========== 验证标准 2: 总结完成后 onComplete 被调用 ==========

    @Test
    void shouldTriggerOnCompleteAfterSummary() {
        mockPlanResponse("[{\"title\":\"任务1\"}]");

        int[] callCount = {0};
        doAnswer(invocation -> {
            callCount[0]++;
            ThinkingStreamHandler handler = invocation.getArgument(2);
            if (callCount[0] == 1) {
                handler.onPartialResponse("结果");
                handler.onComplete("结果", "stop", null);
            } else {
                handler.onPartialResponse("总结");
                handler.onComplete("总结", "stop", null);
            }
            return null;
        }).when(thinkingModel).stream(any(), any(), any());

        Runnable completeCallback = mock(Runnable.class);

        createStream("任务", false)
                .onComplete(completeCallback)
                .start();

        verify(completeCallback).run();
    }

    // ========== 验证标准 3: onNoBreakdown 路径 -> onSummaryToken 被调用（降级） ==========

    @Test
    void shouldTriggerOnSummaryTokenOnNoBreakdownPath() {
        mockPlanResponse("[]");

        doAnswer(invocation -> {
            ThinkingStreamHandler handler = invocation.getArgument(2);
            handler.onPartialResponse("直接回答");
            handler.onComplete("直接回答", "stop", null);
            return null;
        }).when(thinkingModel).stream(any(), any(), any());

        TaskBreakdownStream.PlanConsumer planConsumer = mock(TaskBreakdownStream.PlanConsumer.class);
        TaskBreakdownStream.TokenConsumer summaryTokenConsumer = mock(TaskBreakdownStream.TokenConsumer.class);

        createStream("你好", false)
                .onPlan(planConsumer)
                .onSummaryToken(summaryTokenConsumer)
                .start();

        verify(planConsumer, never()).accept(anyList());
        verify(summaryTokenConsumer).accept("直接回答");
    }

    // ========== 验证标准 4: enableThinking=true 且 onNoBreakdown -> onSummaryReasoning 被调用 ==========

    @Test
    void shouldTriggerOnSummaryReasoningWhenEnableThinkingOnNoBreakdown() {
        mockPlanResponse("[]");

        doAnswer(invocation -> {
            ThinkingStreamHandler handler = invocation.getArgument(2);
            handler.onPartialThinking("推理内容");
            handler.onPartialResponse("回答");
            handler.onComplete("回答", "stop", null);
            return null;
        }).when(thinkingModel).stream(any(), any(), any());

        TaskBreakdownStream.ReasoningConsumer summaryReasoningConsumer = mock(TaskBreakdownStream.ReasoningConsumer.class);

        createStream("你好", true)
                .onSummaryReasoning(summaryReasoningConsumer)
                .start();

        verify(summaryReasoningConsumer).accept("推理内容");
    }

    // ========== 补充: enableThinking=false 时不推送 onSummaryReasoning ==========

    @Test
    void shouldNotTriggerOnSummaryReasoningWhenEnableThinkingFalse() {
        mockPlanResponse("[]");

        doAnswer(invocation -> {
            ThinkingStreamHandler handler = invocation.getArgument(2);
            handler.onPartialThinking("推理内容");
            handler.onPartialResponse("回答");
            handler.onComplete("回答", "stop", null);
            return null;
        }).when(thinkingModel).stream(any(), any(), any());

        TaskBreakdownStream.ReasoningConsumer summaryReasoningConsumer = mock(TaskBreakdownStream.ReasoningConsumer.class);

        createStream("你好", false)
                .onSummaryReasoning(summaryReasoningConsumer)
                .start();

        verify(summaryReasoningConsumer, never()).accept(anyString());
    }
}
