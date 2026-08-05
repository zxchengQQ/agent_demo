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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import org.mockito.ArgumentCaptor;

/**
 * TaskBreakdownStream 规划阶段测试（Task-03）
 * <p>
 * 验证标准来源：Task-03 验证标准
 * 关联 AC：AC-001（拆解复杂任务）、AC-002（无需拆解）、AC-008（子任务超限）、
 *         AC-009（LLM判断无需拆解）、AC-013（子任务上限规则）
 * </p>
 */
class TaskBreakdownStreamPlanningTest {

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

        // Mock 降级路径的流式输出（onNoBreakdown 时调用 streamDirectAnswer）
        doAnswer(invocation -> {
            ThinkingStreamHandler handler = invocation.getArgument(2);
            handler.onPartialResponse("直接回答");
            handler.onComplete("直接回答", "stop", null);
            return null;
        }).when(thinkingModel).stream(any(), any(), any());
    }

    /**
     * 构建测试用 TaskBreakdownStream
     */
    private TaskBreakdownStream createStream(String message) {
        return new TaskBreakdownStream(
                "test-session", message, false,
                modelFactory, memoryManager, agentConfig,
                toolSchemaConverter, toolExecutor);
    }

    /**
     * 模拟 ChatModel.chat 返回指定文本
     */
    private void mockChatModelResponse(String text) {
        AiMessage aiMessage = AiMessage.from(text);
        ChatResponse response = mock(ChatResponse.class);
        when(response.aiMessage()).thenReturn(aiMessage);
        when(chatModel.chat(anyList())).thenReturn(response);
    }

    // ========== 验证标准 1: Mock 返回 JSON 子任务列表 -> onPlan 被调用 ==========

    @Test
    void shouldTriggerOnPlanWhenLlmReturnsTaskList() {
        mockChatModelResponse("[{\"title\":\"分析需求\"},{\"title\":\"调研方案\"}]");

        TaskBreakdownStream.PlanConsumer planConsumer = mock(TaskBreakdownStream.PlanConsumer.class);
        Runnable completeCallback = mock(Runnable.class);

        createStream("帮我调研Vue3和React的区别")
                .onPlan(planConsumer)
                .onComplete(completeCallback)
                .start();

        ArgumentCaptor<List<SubTask>> captor = ArgumentCaptor.forClass(List.class);
        verify(planConsumer).accept(captor.capture());

        List<SubTask> tasks = captor.getValue();
        assertEquals(2, tasks.size(), "应有 2 个子任务");
        assertEquals(1, tasks.get(0).index(), "第1个子任务 index 应为 1");
        assertEquals("分析需求", tasks.get(0).title(), "第1个子任务 title 应为 '分析需求'");
        assertEquals(2, tasks.get(1).index(), "第2个子任务 index 应为 2");
        assertEquals("调研方案", tasks.get(1).title(), "第2个子任务 title 应为 '调研方案'");

        verify(completeCallback).run();
    }

    // ========== 验证标准 2: Mock 返回空列表 -> onNoBreakdown 被调用 ==========

    @Test
    void shouldTriggerOnNoBreakdownWhenLlmReturnsEmptyList() {
        mockChatModelResponse("[]");

        TaskBreakdownStream.PlanConsumer planConsumer = mock(TaskBreakdownStream.PlanConsumer.class);
        Runnable noBreakdownCallback = mock(Runnable.class);
        Runnable completeCallback = mock(Runnable.class);

        createStream("现在几点")
                .onPlan(planConsumer)
                .onNoBreakdown(noBreakdownCallback)
                .onComplete(completeCallback)
                .start();

        verify(noBreakdownCallback).run();
        verify(planConsumer, never()).accept(anyList());
        verify(completeCallback).run();
    }

    // ========== 验证标准 3: Mock 返回非 JSON -> onNoBreakdown 被调用（降级） ==========

    @Test
    void shouldTriggerOnNoBreakdownWhenLlmReturnsNonJson() {
        mockChatModelResponse("这是一个简单任务，不需要拆解。直接回答即可。");

        TaskBreakdownStream.PlanConsumer planConsumer = mock(TaskBreakdownStream.PlanConsumer.class);
        Runnable noBreakdownCallback = mock(Runnable.class);

        createStream("你好")
                .onPlan(planConsumer)
                .onNoBreakdown(noBreakdownCallback)
                .start();

        verify(noBreakdownCallback).run();
        verify(planConsumer, never()).accept(anyList());
    }

    // ========== 验证标准 4: Mock 返回 15 个子任务 -> 截断为 10 ==========

    @Test
    void shouldTruncateTo10WhenLlmReturns15Tasks() {
        // 生成 15 个子任务的 JSON
        StringBuilder json = new StringBuilder("[");
        for (int i = 1; i <= 15; i++) {
            if (i > 1) json.append(",");
            json.append("{\"title\":\"任务").append(i).append("\"}");
        }
        json.append("]");

        mockChatModelResponse(json.toString());

        TaskBreakdownStream.PlanConsumer planConsumer = mock(TaskBreakdownStream.PlanConsumer.class);

        createStream("复杂任务")
                .onPlan(planConsumer)
                .start();

        ArgumentCaptor<List<SubTask>> captor = ArgumentCaptor.forClass(List.class);
        verify(planConsumer).accept(captor.capture());

        List<SubTask> tasks = captor.getValue();
        assertEquals(10, tasks.size(), "15 个子任务应截断为 10 个");
        assertEquals(1, tasks.get(0).index(), "第1个 index 应为 1");
        assertEquals(10, tasks.get(9).index(), "第10个 index 应为 10");
        assertEquals("任务10", tasks.get(9).title(), "第10个 title 应为 '任务10'");
    }

    // ========== 验证标准 5: Mock 抛异常 -> onError 被调用 ==========

    @Test
    void shouldTriggerOnErrorWhenChatModelThrows() {
        RuntimeException exception = new RuntimeException("LLM 连接失败");
        when(chatModel.chat(anyList())).thenThrow(exception);

        TaskBreakdownStream.ErrorConsumer errorConsumer = mock(TaskBreakdownStream.ErrorConsumer.class);
        Runnable completeCallback = mock(Runnable.class);

        createStream("触发错误")
                .onError(errorConsumer)
                .onComplete(completeCallback)
                .start();

        verify(errorConsumer).accept(exception);
        verify(completeCallback, never()).run();
    }

    // ========== 验证标准 6: 链式调用返回 stream 实例本身 ==========

    @Test
    void chainedSettersShouldReturnSameStreamInstance() {
        TaskBreakdownStream stream = createStream("test");

        TaskBreakdownStream result1 = stream.onPlan(tasks -> {});
        TaskBreakdownStream result2 = stream.onNoBreakdown(() -> {});
        TaskBreakdownStream result3 = stream.onTaskStart((i, t) -> {});
        TaskBreakdownStream result4 = stream.onTaskToken((i, c) -> {});
        TaskBreakdownStream result5 = stream.onTaskReasoning((i, c) -> {});
        TaskBreakdownStream result6 = stream.onTaskThought((i, c, it) -> {});
        TaskBreakdownStream result7 = stream.onTaskAction((i, n, a, it) -> {});
        TaskBreakdownStream result8 = stream.onTaskObservation((i, r, it) -> {});
        TaskBreakdownStream result9 = stream.onTaskComplete(i -> {});
        TaskBreakdownStream result10 = stream.onTaskFailed((i, e) -> {});
        TaskBreakdownStream result11 = stream.onTaskCancelled(i -> {});
        TaskBreakdownStream result12 = stream.onSummaryToken(t -> {});
        TaskBreakdownStream result13 = stream.onSummaryReasoning(r -> {});
        TaskBreakdownStream result14 = stream.onComplete(() -> {});
        TaskBreakdownStream result15 = stream.onError(e -> {});

        assertSame(stream, result1, "onPlan 应返回 this");
        assertSame(stream, result2, "onNoBreakdown 应返回 this");
        assertSame(stream, result3, "onTaskStart 应返回 this");
        assertSame(stream, result4, "onTaskToken 应返回 this");
        assertSame(stream, result5, "onTaskReasoning 应返回 this");
        assertSame(stream, result6, "onTaskThought 应返回 this");
        assertSame(stream, result7, "onTaskAction 应返回 this");
        assertSame(stream, result8, "onTaskObservation 应返回 this");
        assertSame(stream, result9, "onTaskComplete 应返回 this");
        assertSame(stream, result10, "onTaskFailed 应返回 this");
        assertSame(stream, result11, "onTaskCancelled 应返回 this");
        assertSame(stream, result12, "onSummaryToken 应返回 this");
        assertSame(stream, result13, "onSummaryReasoning 应返回 this");
        assertSame(stream, result14, "onComplete 应返回 this");
        assertSame(stream, result15, "onError 应返回 this");
    }

    // ========== 补充：JSON 包含 markdown 标记时仍能解析 ==========

    @Test
    void shouldParseJsonWithMarkdownFence() {
        mockChatModelResponse("```json\n[{\"title\":\"分析\"},{\"title\":\"执行\"}]\n```");

        TaskBreakdownStream.PlanConsumer planConsumer = mock(TaskBreakdownStream.PlanConsumer.class);

        createStream("复杂任务")
                .onPlan(planConsumer)
                .start();

        ArgumentCaptor<List<SubTask>> captor = ArgumentCaptor.forClass(List.class);
        verify(planConsumer).accept(captor.capture());

        List<SubTask> tasks = captor.getValue();
        assertEquals(2, tasks.size(), "应解析出 2 个子任务（忽略 markdown 标记）");
    }
}
