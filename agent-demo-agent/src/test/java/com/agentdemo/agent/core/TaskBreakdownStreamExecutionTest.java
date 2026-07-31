package com.agentdemo.agent.core;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.llm.factory.ThinkingStreamingChatModel;
import com.agentdemo.llm.factory.ModelFactory;
import com.agentdemo.llm.factory.ThinkingStreamHandler;
import com.agentdemo.llm.factory.ToolCall;
import com.agentdemo.memory.shortterm.ChatMemoryManager;
import com.agentdemo.tools.registry.ToolExecutor;
import com.agentdemo.tools.registry.ToolSchemaConverter;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * TaskBreakdownStream 子任务执行阶段测试（Task-04）
 * <p>
 * 验证标准来源：Task-04 验证标准
 * 关联 AC：AC-001（子任务执行）、AC-003（状态流转）、AC-005（执行详情）、
 *         AC-006（子任务失败）、AC-011（拆解+思考共存）
 * </p>
 */
class TaskBreakdownStreamExecutionTest {

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

    /** 模拟单轮 LLM 调用（finishReason=stop，无工具调用） */
    private Object mockSingleRoundStop(InvocationOnMock invocation) {
        ThinkingStreamHandler handler = invocation.getArgument(2);
        handler.onPartialResponse("执行结果");
        handler.onComplete("执行结果", "stop", null);
        return null;
    }

    /** 模拟单轮 LLM 调用（finishReason=tool_calls，有工具调用） */
    private Object mockSingleRoundToolCalls(InvocationOnMock invocation, String toolName, String args) {
        ThinkingStreamHandler handler = invocation.getArgument(2);
        handler.onPartialResponse("需要调用工具");

        ToolCall tc = new ToolCall();
        tc.setId("call_001");
        tc.setFunctionName(toolName);
        tc.setArguments(args);
        handler.onToolCalls(Collections.singletonList(tc));
        handler.onComplete("需要调用工具", "tool_calls", null);
        return null;
    }

    // ========== 验证标准 1: 2个子任务 -> onTaskStart 被调用2次 ==========

    @Test
    void shouldTriggerOnTaskStartForEachSubTask() {
        mockPlanResponse("[{\"title\":\"分析\"},{\"title\":\"执行\"}]");
        doAnswer(this::mockSingleRoundStop).when(thinkingModel).stream(any(), any(), any());

        TaskBreakdownStream.TaskStartConsumer taskStartConsumer = mock(TaskBreakdownStream.TaskStartConsumer.class);

        createStream("复杂任务", false)
                .onTaskStart(taskStartConsumer)
                .start();

        verify(taskStartConsumer).accept(eq(1), eq("分析"));
        verify(taskStartConsumer).accept(eq(2), eq("执行"));
    }

    // ========== 验证标准 2: onTaskToken 回调被调用，index 正确 ==========

    @Test
    void shouldTriggerOnTaskTokenWithCorrectIndex() {
        mockPlanResponse("[{\"title\":\"分析\"}]");
        doAnswer(this::mockSingleRoundStop).when(thinkingModel).stream(any(), any(), any());

        TaskBreakdownStream.TaskTokenConsumer tokenConsumer = mock(TaskBreakdownStream.TaskTokenConsumer.class);

        createStream("任务", false)
                .onTaskToken(tokenConsumer)
                .start();

        verify(tokenConsumer).accept(eq(1), eq("执行结果"));
    }

    // ========== 验证标准 3: onTaskComplete 回调被调用 ==========

    @Test
    void shouldTriggerOnTaskComplete() {
        mockPlanResponse("[{\"title\":\"分析\"},{\"title\":\"执行\"}]");
        doAnswer(this::mockSingleRoundStop).when(thinkingModel).stream(any(), any(), any());

        TaskBreakdownStream.TaskCompleteConsumer completeConsumer = mock(TaskBreakdownStream.TaskCompleteConsumer.class);

        createStream("任务", false)
                .onTaskComplete(completeConsumer)
                .start();

        verify(completeConsumer).accept(1);
        verify(completeConsumer).accept(2);
    }

    // ========== 验证标准 4: 第1个子任务失败 -> onTaskFailed + onTaskCancelled ==========

    @Test
    void shouldTriggerOnTaskFailedAndCancelledWhenFirstTaskFails() {
        mockPlanResponse("[{\"title\":\"任务1\"},{\"title\":\"任务2\"}]");

        // 第一轮抛异常
        doAnswer(invocation -> {
            ThinkingStreamHandler handler = invocation.getArgument(2);
            handler.onError(new RuntimeException("LLM 超时"));
            return null;
        }).when(thinkingModel).stream(any(), any(), any());

        TaskBreakdownStream.TaskFailedConsumer failedConsumer = mock(TaskBreakdownStream.TaskFailedConsumer.class);
        TaskBreakdownStream.TaskCancelledConsumer cancelledConsumer = mock(TaskBreakdownStream.TaskCancelledConsumer.class);
        TaskBreakdownStream.TaskCompleteConsumer completeConsumer = mock(TaskBreakdownStream.TaskCompleteConsumer.class);

        createStream("任务", false)
                .onTaskFailed(failedConsumer)
                .onTaskCancelled(cancelledConsumer)
                .onTaskComplete(completeConsumer)
                .start();

        verify(failedConsumer).accept(eq(1), anyString());
        verify(cancelledConsumer).accept(2);
        verify(completeConsumer, never()).accept(anyInt());
    }

    // ========== 验证标准 5: finishReason="stop" -> 正常完成 ==========

    @Test
    void shouldCompleteWhenFinishReasonIsStop() {
        mockPlanResponse("[{\"title\":\"任务\"}]");
        doAnswer(this::mockSingleRoundStop).when(thinkingModel).stream(any(), any(), any());

        TaskBreakdownStream.TaskCompleteConsumer completeConsumer = mock(TaskBreakdownStream.TaskCompleteConsumer.class);

        createStream("任务", false)
                .onTaskComplete(completeConsumer)
                .start();

        verify(completeConsumer).accept(1);
    }

    // ========== 验证标准 6: finishReason="tool_calls" -> 工具调用 ==========

    @Test
    void shouldExecuteToolWhenFinishReasonIsToolCalls() {
        mockPlanResponse("[{\"title\":\"查时间\"}]");

        // 第一轮返回 tool_calls，第二轮返回 stop
        int[] callCount = {0};
        doAnswer(invocation -> {
            callCount[0]++;
            if (callCount[0] == 1) {
                mockSingleRoundToolCalls(invocation, "getCurrentTime", "{}");
            } else {
                mockSingleRoundStop(invocation);
            }
            return null;
        }).when(thinkingModel).stream(any(), any(), any());

        when(toolExecutor.execute("getCurrentTime", "{}")).thenReturn("2026-07-23 10:00:00");

        TaskBreakdownStream.TaskActionConsumer actionConsumer = mock(TaskBreakdownStream.TaskActionConsumer.class);
        TaskBreakdownStream.TaskObservationConsumer observationConsumer = mock(TaskBreakdownStream.TaskObservationConsumer.class);
        TaskBreakdownStream.TaskCompleteConsumer completeConsumer = mock(TaskBreakdownStream.TaskCompleteConsumer.class);

        createStream("几点了", false)
                .onTaskAction(actionConsumer)
                .onTaskObservation(observationConsumer)
                .onTaskComplete(completeConsumer)
                .start();

        verify(actionConsumer).accept(eq(1), eq("getCurrentTime"), eq("{}"), eq(1));
        verify(observationConsumer).accept(eq(1), eq("2026-07-23 10:00:00"), eq(1));
        verify(toolExecutor).execute("getCurrentTime", "{}");
        verify(completeConsumer).accept(1);
    }

    // ========== 验证标准 7: enableThinking=true -> onTaskReasoning 被调用 ==========

    @Test
    void shouldTriggerOnTaskReasoningWhenEnableThinking() {
        mockPlanResponse("[{\"title\":\"任务\"}]");

        doAnswer(invocation -> {
            ThinkingStreamHandler handler = invocation.getArgument(2);
            handler.onPartialThinking("正在思考");
            handler.onPartialResponse("结果");
            handler.onComplete("结果", "stop", null);
            return null;
        }).when(thinkingModel).stream(any(), any(), any());

        TaskBreakdownStream.TaskReasoningConsumer reasoningConsumer = mock(TaskBreakdownStream.TaskReasoningConsumer.class);

        createStream("任务", true)
                .onTaskReasoning(reasoningConsumer)
                .start();

        verify(reasoningConsumer).accept(eq(1), eq("正在思考"));
    }

    // ========== 验证标准 8: 达到 maxIterations -> 返回部分内容，onTaskComplete ==========

    @Test
    void shouldReturnPartialResultWhenMaxIterationsReached() {
        mockPlanResponse("[{\"title\":\"无限循环\"}]");
        agentConfig.setTaskExecutionMaxIterations(2);

        // 每轮都返回 tool_calls，永不 stop
        doAnswer(invocation -> {
            mockSingleRoundToolCalls(invocation, "calculate", "{}");
            return null;
        }).when(thinkingModel).stream(any(), any(), any());

        when(toolExecutor.execute("calculate", "{}")).thenReturn("结果");

        TaskBreakdownStream.TaskCompleteConsumer completeConsumer = mock(TaskBreakdownStream.TaskCompleteConsumer.class);

        createStream("任务", false)
                .onTaskComplete(completeConsumer)
                .start();

        // 达到 maxIterations 后仍应标记为完成
        verify(completeConsumer).accept(1);
    }

    // ========== 补充: 子任务结果写入记忆 ==========

    @Test
    void shouldWriteSubTaskResultToMemory() {
        mockPlanResponse("[{\"title\":\"任务\"}]");
        doAnswer(this::mockSingleRoundStop).when(thinkingModel).stream(any(), any(), any());

        createStream("任务", false).start();

        // 验证子任务结果写入记忆
        verify(memoryManager).addUserMessage(eq("test-session"), contains("任务"));
        verify(memoryManager).addAssistantMessage(eq("test-session"), anyString());
    }
}
