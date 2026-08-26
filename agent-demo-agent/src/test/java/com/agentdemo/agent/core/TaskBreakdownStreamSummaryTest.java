package com.agentdemo.agent.core;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.agent.prompt.PromptTemplateLoader;
import com.agentdemo.llm.thinking.ThinkingStreamingChatModel;
import com.agentdemo.llm.registry.ModelFactory;
import com.agentdemo.llm.thinking.ThinkingStreamHandler;
import com.agentdemo.memory.shortterm.ChatMemoryManager;
import com.agentdemo.tools.registry.ToolExecutor;
import com.agentdemo.tools.registry.ToolSchemaConverter;
import dev.langchain4j.memory.ChatMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * TaskBreakdownStream 总结阶段测试（unified-chat-mode Task-07）
 * <p>
 * 验证标准来源：unified-chat-mode 任务规划 Task-07 验证标准
 * 关联 AC：AC-N05（拆解过程可视化-总结）、AC-004（总结生成）
 * 业务含义：验证统一模式下拆解完成后总结阶段——onSummaryToken/onSummaryReasoning
 * 无条件推送（enableThinking 删除，统一模式恒开启）、总结完成后 onComplete。
 * </p>
 */
class TaskBreakdownStreamSummaryTest {

    private ModelFactory modelFactory;
    private ThinkingStreamingChatModel thinkingModel;
    private ChatMemoryManager memoryManager;
    private AgentConfig agentConfig;
    private ToolSchemaConverter toolSchemaConverter;
    private ToolExecutor toolExecutor;
    private ChatMemory chatMemory;
    private HumanInteractionManager humanInteractionManager;

    @BeforeEach
    void setUp() {
        modelFactory = mock(ModelFactory.class);
        thinkingModel = mock(ThinkingStreamingChatModel.class);
        memoryManager = mock(ChatMemoryManager.class);
        toolSchemaConverter = mock(ToolSchemaConverter.class);
        toolExecutor = mock(ToolExecutor.class);
        agentConfig = new AgentConfig();
        chatMemory = mock(ChatMemory.class);
        humanInteractionManager = new HumanInteractionManager();

        when(modelFactory.getDefaultThinkingStreamingChatModel()).thenReturn(thinkingModel);
        when(toolSchemaConverter.convertToDescriptionText(anyList())).thenReturn("工具描述");
        when(memoryManager.getMemory(anyString())).thenReturn(chatMemory);
        when(chatMemory.messages()).thenReturn(new ArrayList<>());
    }

    private TaskBreakdownStream createStream(List<SubTask> tasks) {
        return new TaskBreakdownStream(
                "test-session", "复杂任务", null,
                modelFactory, memoryManager, agentConfig, toolSchemaConverter, toolExecutor,
                new PromptTemplateLoader(agentConfig), humanInteractionManager,
                List.of(new Object()), "[]", tasks);
    }

    // ========== 验证标准 1: 所有子任务完成后 onSummaryToken 被调用 ==========

    @Test
    @DisplayName("所有子任务完成后总结阶段推送 onSummaryToken")
    void shouldTriggerOnSummaryTokenAfterAllTasksComplete() {
        List<SubTask> tasks = List.of(new SubTask(1, "任务1"));
        // 第1次调用=子任务执行，第2次调用=总结
        int[] callCount = {0};
        doAnswer(invocation -> {
            callCount[0]++;
            ThinkingStreamHandler handler = invocation.getArgument(2);
            if (callCount[0] == 1) {
                handler.onPartialResponse("子任务结果");
                handler.onComplete("子任务结果", "stop", null);
            } else {
                handler.onPartialResponse("总结内容");
                handler.onComplete("总结内容", "stop", null);
            }
            return null;
        }).when(thinkingModel).stream(any(), any(), any());

        TaskBreakdownStream.TokenConsumer summaryTokenConsumer = mock(TaskBreakdownStream.TokenConsumer.class);

        createStream(tasks)
                .onSummaryToken(summaryTokenConsumer)
                .start();

        verify(summaryTokenConsumer).accept("总结内容");
    }

    // ========== 验证标准 2: 总结完成后 onComplete 被调用 ==========

    @Test
    @DisplayName("总结完成后 onComplete 被调用")
    void shouldTriggerOnCompleteAfterSummary() {
        List<SubTask> tasks = List.of(new SubTask(1, "任务1"));

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

        createStream(tasks)
                .onComplete(completeCallback)
                .start();

        verify(completeCallback).run();
    }

    // ========== 验证标准 3: 总结阶段 onSummaryReasoning 无条件推送（enableThinking 删除） ==========

    @Test
    @DisplayName("总结阶段 onSummaryReasoning 无条件推送（统一模式恒开启）")
    void shouldTriggerOnSummaryReasoningUnconditionally() {
        List<SubTask> tasks = List.of(new SubTask(1, "任务1"));

        int[] callCount = {0};
        doAnswer(invocation -> {
            callCount[0]++;
            ThinkingStreamHandler handler = invocation.getArgument(2);
            if (callCount[0] == 1) {
                handler.onPartialResponse("结果");
                handler.onComplete("结果", "stop", null);
            } else {
                handler.onPartialThinking("总结推理");
                handler.onPartialResponse("总结");
                handler.onComplete("总结", "stop", null);
            }
            return null;
        }).when(thinkingModel).stream(any(), any(), any());

        TaskBreakdownStream.ReasoningConsumer summaryReasoningConsumer = mock(TaskBreakdownStream.ReasoningConsumer.class);

        createStream(tasks)
                .onSummaryReasoning(summaryReasoningConsumer)
                .start();

        // 统一模式恒开启思考，无需 enableThinking 参数即推送
        verify(summaryReasoningConsumer).accept("总结推理");
    }
}
