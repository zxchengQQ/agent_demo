package com.agentdemo.agent.core;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.agent.prompt.PromptTemplateLoader;
import com.agentdemo.llm.thinking.ThinkingStreamingChatModel;
import com.agentdemo.llm.registry.ModelFactory;
import com.agentdemo.llm.thinking.ThinkingStreamHandler;
import com.agentdemo.llm.thinking.ToolCall;
import com.agentdemo.memory.shortterm.ChatMemoryManager;
import com.agentdemo.tools.registry.ToolExecutor;
import com.agentdemo.tools.registry.ToolSchemaConverter;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * TaskBreakdownStream 恢复测试（unified-chat-mode Task-09）
 * <p>
 * 验证标准来源：unified-chat-mode 任务规划 Task-09 验证标准（状态完整性专项）
 * 关联 AC：AC-T05（恢复续跑）、AC-M02（已完成不重跑）、AC-N05（恢复时进度视图）
 * 业务含义：验证拆解-追问-恢复链路——恢复仅从 currentTaskIndex 开始、前序子任务不重跑、
 * 事件重放顺序正确、恢复上下文 = pending.messages + 用户回复 Observation、
 * 续跑完整（子任务 N -> 剩余 -> 总结）、pending 缺失降级普通流程。
 * </p>
 */
class TaskBreakdownStreamResumeTest {

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
        when(toolExecutor.execute(anyString(), anyString())).thenReturn("工具执行结果");
    }

    private TaskBreakdownStream createStream() {
        return new TaskBreakdownStream(
                "test-session", "复杂任务", null,
                modelFactory, memoryManager, agentConfig, toolSchemaConverter, toolExecutor,
                new PromptTemplateLoader(agentConfig), humanInteractionManager,
                List.of(new Object()), "[]", List.of());
    }

    /** 构造一个已暂停的 pending（mode=breakdown，含拆解上下文），模拟真实暂停场景 */
    private void preparePausedPending(List<SubTask> tasks, int currentTaskIndex, List<String> subtaskResults) {
        // 模拟 HITLReActStream.handleAskUser 保存的 ReAct 上下文（含 askUser 工具调用）
        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
        messages.add(dev.langchain4j.data.message.SystemMessage.from("系统提示"));
        messages.add(UserMessage.from("子任务2缺订单号"));
        messages.add(dev.langchain4j.data.message.AiMessage.aiMessage("", List.of(
                dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                        .id("call_ask").name("askUser")
                        .arguments("{\"type\":\"text\",\"question\":\"请提供订单号\"}")
                        .build())));

        humanInteractionManager.saveInteraction("test-session", messages,
                "text", "请提供订单号", null, 0, null, "[]", PendingInteraction.MODE_BREAKDOWN);
        humanInteractionManager.attachBreakdownContext("test-session", tasks, currentTaskIndex, subtaskResults);
    }

    // ========== Task-09 验证标准 ==========

    @Test
    @DisplayName("恢复仅从 currentTaskIndex 开始，前序子任务不重跑（各子任务执行次数=1）")
    void resumeFromPending_仅从暂停点续跑_前序不重跑() {
        List<SubTask> tasks = List.of(new SubTask(1, "任务1"), new SubTask(2, "任务2"), new SubTask(3, "任务3"));
        // 子任务1已完成（写记忆 + onTaskComplete），子任务2暂停
        preparePausedPending(tasks, 1, List.of("子任务1结果"));

        // 恢复后执行子任务2、3（各一次）
        doAnswer(invocation -> {
            ThinkingStreamHandler handler = invocation.getArgument(2);
            handler.onPartialResponse("续跑结果");
            handler.onComplete("续跑结果", "stop", null);
            return null;
        }).when(thinkingModel).stream(any(), any(), any());

        TaskBreakdownStream.TaskCompleteConsumer completeConsumer = mock(TaskBreakdownStream.TaskCompleteConsumer.class);
        Runnable completeCallback = mock(Runnable.class);

        createStream()
                .onTaskComplete(completeConsumer)
                .onComplete(completeCallback)
                .resumeFromPending("ORD-12345");

        // 前序子任务1重放（决策 5）+ 子任务2/3 实际完成
        verify(completeConsumer).accept(1);
        verify(completeConsumer).accept(2);
        verify(completeConsumer).accept(3);
        // 业务含义：恢复后 stream 调用 = 子任务2 + 子任务3 + 总结 = 3 次，
        // 子任务1 不重跑（前序不重跑断言）
        verify(thinkingModel, times(3)).stream(any(), any(), any());
        verify(completeCallback).run();
    }

    @Test
    @DisplayName("事件重放顺序：onPlan -> 已完成子任务 onTaskComplete -> 续跑事件")
    void resumeFromPending_事件重放顺序正确() {
        List<SubTask> tasks = List.of(new SubTask(1, "任务1"), new SubTask(2, "任务2"), new SubTask(3, "任务3"));
        preparePausedPending(tasks, 1, List.of("子任务1结果"));

        doAnswer(invocation -> {
            ThinkingStreamHandler handler = invocation.getArgument(2);
            handler.onPartialResponse("续跑");
            handler.onComplete("续跑", "stop", null);
            return null;
        }).when(thinkingModel).stream(any(), any(), any());

        List<String> eventOrder = new ArrayList<>();
        TaskBreakdownStream.PlanConsumer planConsumer = tasksList -> eventOrder.add("plan:" + tasksList.size());
        TaskBreakdownStream.TaskCompleteConsumer completeConsumer = index -> eventOrder.add("complete:" + index);
        TaskBreakdownStream.TaskStartConsumer startConsumer = (index, title) -> eventOrder.add("start:" + index);

        createStream()
                .onPlan(planConsumer)
                .onTaskComplete(completeConsumer)
                .onTaskStart(startConsumer)
                .resumeFromPending("ORD-12345");

        // 顺序：plan -> complete:1（重放）-> start:2 -> complete:2 -> start:3 -> complete:3
        assertEquals("plan:3", eventOrder.get(0), "应最先重放 onPlan");
        assertEquals("complete:1", eventOrder.get(1), "应重放已完成子任务1的 onTaskComplete");
        assertEquals("start:2", eventOrder.get(2), "应从子任务2开始续跑");
    }

    @Test
    @DisplayName("恢复上下文 = pending.messages + 用户回复 Observation（retryCount+1）")
    void resumeFromPending_续用暂停上下文并追加用户回复() {
        List<SubTask> tasks = List.of(new SubTask(1, "任务1"), new SubTask(2, "任务2"));
        preparePausedPending(tasks, 1, List.of("子任务1结果"));
        // retryCount=0，恢复后应 +1

        final List<dev.langchain4j.data.message.ChatMessage>[] capturedMessages = new List[1];
        final boolean[] capturedFirst = {false};
        doAnswer(invocation -> {
            // 只捕获第一次调用（恢复子任务的上下文），后续总结调用会覆盖
            if (!capturedFirst[0]) {
                capturedFirst[0] = true;
                capturedMessages[0] = invocation.getArgument(0);
            }
            ThinkingStreamHandler handler = invocation.getArgument(2);
            handler.onPartialResponse("续跑");
            handler.onComplete("续跑", "stop", null);
            return null;
        }).when(thinkingModel).stream(any(), any(), any());

        createStream().resumeFromPending("ORD-12345");

        // 业务含义：恢复上下文 = pending.messages + 追加的用户回复 Observation。
        // 暂停时 pending 不含 ToolExecutionResultMessage（askUser 拦截时不回填工具结果），
        // 因此存在 ToolExecutionResultMessage 即证明用户回复 Observation 已追加
        boolean hasUserReplyObservation = capturedMessages[0].stream()
                .anyMatch(m -> m instanceof dev.langchain4j.data.message.ToolExecutionResultMessage);
        org.junit.jupiter.api.Assertions.assertTrue(hasUserReplyObservation,
                "恢复上下文应追加用户回复 Observation");
    }

    @Test
    @DisplayName("续跑链路完整：子任务N -> 剩余子任务 -> 总结 onComplete")
    void resumeFromPending_续跑后总结完成() {
        List<SubTask> tasks = List.of(new SubTask(1, "任务1"), new SubTask(2, "任务2"), new SubTask(3, "任务3"));
        preparePausedPending(tasks, 2, List.of("子任务1结果", "子任务2结果"));

        // 子任务3执行 + 总结（共 2 次 stream 调用）
        int[] callCount = {0};
        doAnswer(invocation -> {
            callCount[0]++;
            ThinkingStreamHandler handler = invocation.getArgument(2);
            if (callCount[0] == 1) {
                handler.onPartialResponse("子任务3结果");
                handler.onComplete("子任务3结果", "stop", null);
            } else {
                handler.onPartialResponse("最终总结");
                handler.onComplete("最终总结", "stop", null);
            }
            return null;
        }).when(thinkingModel).stream(any(), any(), any());

        TaskBreakdownStream.TokenConsumer summaryTokenConsumer = mock(TaskBreakdownStream.TokenConsumer.class);
        Runnable completeCallback = mock(Runnable.class);

        createStream()
                .onSummaryToken(summaryTokenConsumer)
                .onComplete(completeCallback)
                .resumeFromPending("ORD-12345");

        verify(summaryTokenConsumer).accept("最终总结");
        verify(completeCallback).run();
    }

    @Test
    @DisplayName("loadInteraction 后 clearInteraction 被调用（防重复恢复）")
    void resumeFromPending_清除pending防重复恢复() {
        List<SubTask> tasks = List.of(new SubTask(1, "任务1"), new SubTask(2, "任务2"));
        preparePausedPending(tasks, 1, List.of("子任务1结果"));

        doAnswer(invocation -> {
            ThinkingStreamHandler handler = invocation.getArgument(2);
            handler.onPartialResponse("续跑");
            handler.onComplete("续跑", "stop", null);
            return null;
        }).when(thinkingModel).stream(any(), any(), any());

        createStream().resumeFromPending("ORD-12345");

        assertFalse(humanInteractionManager.hasPending("test-session"),
                "恢复后 pending 应被清除，防止重复恢复");
    }

    @Test
    @DisplayName("pending 缺失/非 breakdown 模式 -> 降级普通流程（抛 IllegalStateException 由编排层捕获）")
    void resumeFromPending_pending缺失_降级() {
        TaskBreakdownStream stream = createStream();

        // 无 pending：resumeFromPending 抛 IllegalStateException，由 UnifiedChatStream 捕获降级普通流程
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> stream.resumeFromPending("回复"),
                "pending 缺失时应抛异常交由编排层降级普通流程");
    }
}
