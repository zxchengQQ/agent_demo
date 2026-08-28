package com.agentdemo.agent.core;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.agent.prompt.PromptTemplateLoader;
import com.agentdemo.llm.thinking.ThinkingStreamingChatModel;
import com.agentdemo.llm.registry.ModelFactory;
import com.agentdemo.llm.thinking.ThinkingStreamHandler;
import com.agentdemo.llm.thinking.ToolCall;
import com.agentdemo.memory.shortterm.ChatMemoryManager;
import com.agentdemo.tools.permission.ToolPermissionLevel;
import com.agentdemo.tools.registry.ToolExecutor;
import com.agentdemo.tools.registry.ToolSchemaConverter;
import dev.langchain4j.memory.ChatMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;

/**
 * TaskBreakdownStream 子任务执行测试（unified-chat-mode Task-07/08）
 * <p>
 * 验证标准来源：unified-chat-mode 任务规划 Task-07/08 验证标准
 * 关联 AC：AC-N05（拆解执行）、AC-T05（子任务暂停）、AC-S01（副作用确认）、AC-S02（追问上限）
 * 业务含义：验证外部注入改造后的拆解执行引擎——按序执行子任务、askUser 拦截暂停
 * （attachBreakdownContext + onAskUser + onComplete 不触发）、失败即停、子任务结果写记忆。
 * 子任务执行委托 HITLReActStream，通过 mock thinkingModel.stream 模拟 HITL 行为。
 * </p>
 */
class TaskBreakdownStreamExecutionTest {

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
        when(toolSchemaConverter.convertToJson(anyList())).thenReturn("[]");
        when(toolSchemaConverter.convertToDescriptionText(anyList())).thenReturn("工具描述");
        when(memoryManager.getMemory(anyString())).thenReturn(chatMemory);
        when(chatMemory.messages()).thenReturn(new ArrayList<>());
        // 业务含义：业务工具执行返回非空字符串，避免 ToolExecutionResultMessage 构造 NPE
        when(toolExecutor.execute(anyString(), anyString())).thenReturn("工具执行结果");
        // 业务含义：httpGet 等业务工具默认 ALLOW 权限（AC-N02），子任务循环可连续执行直至迭代上限
        when(toolExecutor.checkPermission(anyString())).thenReturn(
                new ToolExecutor.ToolPermissionCheck(ToolPermissionLevel.ALLOW, "builtin:httpGet", "desc"));
    }

    private TaskBreakdownStream createStream(List<SubTask> tasks) {
        return new TaskBreakdownStream(
                "test-session", "复杂任务", null,
                modelFactory, memoryManager, agentConfig, toolSchemaConverter, toolExecutor,
                new PromptTemplateLoader(agentConfig), humanInteractionManager,
                List.of(new Object()), "[]", tasks);
    }

    // ==================== Task-10: 子任务系统提示词冻结（agent-context-engineering，AC-N01/T02） ====================

    @Test
    void 子任务系统提示词_不含技能段_且tools文本来自基础工具集() {
        com.agentdemo.agent.single.SessionToolResolver sessionToolResolver =
                mock(com.agentdemo.agent.single.SessionToolResolver.class);
        com.agentdemo.skill.prompt.SkillPromptComposer skillPromptComposer =
                mock(com.agentdemo.skill.prompt.SkillPromptComposer.class);
        when(skillPromptComposer.composeCatalogSegment("test-session")).thenReturn("## 可用技能\n- s1：技能一");
        when(skillPromptComposer.composeActivatedSegment("test-session")).thenReturn("## 已激活技能\n指令全文");
        Object baseTool = new Object();
        when(sessionToolResolver.resolveSessionBaseTools("test-session", null)).thenReturn(List.of(baseTool));
        when(toolSchemaConverter.convertToDescriptionText(List.of(baseTool))).thenReturn("【基础工具清单】");

        TaskBreakdownStream stream = new TaskBreakdownStream(
                "test-session", "复杂任务", null,
                modelFactory, memoryManager, agentConfig, toolSchemaConverter, toolExecutor,
                new PromptTemplateLoader(agentConfig), humanInteractionManager,
                List.of(new Object()), "[]", List.of(new SubTask(1, "子任务A")),
                skillPromptComposer, null, sessionToolResolver);
        stream.onTaskComplete(t -> {});
        stream.onComplete(() -> {});
        doAnswer(inv -> mockSubTaskStop(inv)).when(thinkingModel).stream(any(), any(), any());
        stream.startWithTasks(List.of(new SubTask(1, "子任务A")));

        // 捕获子任务执行的首次 model.stream，断言系统提示词冻结契约
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(thinkingModel, atLeastOnce()).stream(captor.capture(), any(), any());
        List<ChatMessage> messages = captor.getAllValues().get(0);
        SystemMessage sys = (SystemMessage) messages.get(0);
        assertThat(sys.text())
                .contains("【基础工具清单】")            // {{tools}} 来自基础工具集（冻结）
                .doesNotContain("可用技能")            // 技能目录段移出系统提示词
                .doesNotContain("已激活技能")          // 技能激活段移出系统提示词
                .doesNotContain("技能一").doesNotContain("指令全文");
    }

    /** 模拟单轮 LLM 调用（finishReason=stop，无工具调用）——子任务正常完成 */
    private Object mockSubTaskStop(org.mockito.invocation.InvocationOnMock invocation) {
        ThinkingStreamHandler handler = invocation.getArgument(2);
        handler.onPartialResponse("执行结果");
        handler.onComplete("执行结果", "stop", null);
        return null;
    }

    /** 模拟单轮 LLM 调用（finishReason=tool_calls，调用 askUser）——子任务触发追问 */
    private Object mockSubTaskAskUser(org.mockito.invocation.InvocationOnMock invocation) {
        ThinkingStreamHandler handler = invocation.getArgument(2);
        handler.onPartialResponse("需要追问");
        ToolCall tc = new ToolCall();
        tc.setId("call_ask");
        tc.setFunctionName("askUser");
        tc.setArguments("{\"type\":\"text\",\"question\":\"请提供订单号\"}");
        handler.onToolCalls(Collections.singletonList(tc));
        handler.onComplete("需要追问", "tool_calls", null);
        return null;
    }

    // ========== Task-07 验证标准：构造注入 + 按序执行 ==========

    @Test
    @DisplayName("构造注入 tasks 后 start 按序执行所有子任务（onTaskStart/onTaskComplete 按序触发）")
    void startWithInjectedTasks_按序执行所有子任务() {
        List<SubTask> tasks = List.of(new SubTask(1, "分析"), new SubTask(2, "执行"));
        doAnswer(this::mockSubTaskStop).when(thinkingModel).stream(any(), any(), any());

        TaskBreakdownStream.TaskStartConsumer startConsumer = mock(TaskBreakdownStream.TaskStartConsumer.class);
        TaskBreakdownStream.TaskCompleteConsumer completeConsumer = mock(TaskBreakdownStream.TaskCompleteConsumer.class);
        Runnable completeCallback = mock(Runnable.class);

        createStream(tasks)
                .onTaskStart(startConsumer)
                .onTaskComplete(completeConsumer)
                .onComplete(completeCallback)
                .start();

        verify(startConsumer).accept(eq(1), eq("分析"));
        verify(startConsumer).accept(eq(2), eq("执行"));
        verify(completeConsumer).accept(1);
        verify(completeConsumer).accept(2);
        verify(completeCallback).run();
    }

    @Test
    @DisplayName("onPlan 推送外部注入的任务列表")
    void start_推送onPlan任务列表() {
        List<SubTask> tasks = List.of(new SubTask(1, "任务A"));
        doAnswer(this::mockSubTaskStop).when(thinkingModel).stream(any(), any(), any());

        TaskBreakdownStream.PlanConsumer planConsumer = mock(TaskBreakdownStream.PlanConsumer.class);
        Runnable completeCallback = mock(Runnable.class);

        createStream(tasks)
                .onPlan(planConsumer)
                .onComplete(completeCallback)
                .start();

        verify(planConsumer).accept(tasks);
        verify(completeCallback).run();
    }

    @Test
    @DisplayName("子任务结果写记忆格式不变（子任务：{title} + 结果）")
    void start_子任务结果写记忆() {
        List<SubTask> tasks = List.of(new SubTask(1, "分析"));
        doAnswer(this::mockSubTaskStop).when(thinkingModel).stream(any(), any(), any());

        createStream(tasks).start();

        verify(memoryManager).addUserMessage("test-session", "子任务：分析");
        verify(memoryManager).addAssistantMessage("test-session", "执行结果");
    }

    @Test
    @DisplayName("空任务列表 start 直接完成，不执行")
    void start_空任务列表_直接完成() {
        Runnable completeCallback = mock(Runnable.class);

        createStream(Collections.emptyList())
                .onComplete(completeCallback)
                .start();

        verify(completeCallback).run();
        verify(thinkingModel, never()).stream(any(), any(), any());
    }

    // ========== Task-08 验证标准：askUser 拦截暂停 ==========

    @Test
    @DisplayName("子任务 askUser 拦截 -> attachBreakdownContext + onAskUser 触发 + onComplete 不触发")
    void subTaskAskUser_暂停并附加拆解上下文() {
        List<SubTask> tasks = List.of(new SubTask(1, "任务1"), new SubTask(2, "任务2"));
        // 子任务1正常完成，子任务2触发 askUser
        int[] callCount = {0};
        doAnswer(invocation -> {
            callCount[0]++;
            if (callCount[0] == 1) {
                return mockSubTaskStop(invocation);
            }
            return mockSubTaskAskUser(invocation);
        }).when(thinkingModel).stream(any(), any(), any());

        TaskBreakdownStream.TaskStartConsumer startConsumer = mock(TaskBreakdownStream.TaskStartConsumer.class);
        TaskBreakdownStream.TaskCompleteConsumer completeConsumer = mock(TaskBreakdownStream.TaskCompleteConsumer.class);
        TaskBreakdownStream.AskUserConsumer askUserConsumer = mock(TaskBreakdownStream.AskUserConsumer.class);
        Runnable completeCallback = mock(Runnable.class);

        createStream(tasks)
                .onTaskStart(startConsumer)
                .onTaskComplete(completeConsumer)
                .onAskUser(askUserConsumer)
                .onComplete(completeCallback)
                .start();

        // 子任务1完成，子任务2暂停（不触发 onTaskComplete(2)）
        verify(completeConsumer).accept(1);
        verify(completeConsumer, never()).accept(2);

        // onAskUser 触发，onComplete 不触发
        verify(askUserConsumer).accept(eq("text"), eq("请提供订单号"), isNull(), anyInt());
        verify(completeCallback, never()).run();

        // attachBreakdownContext 生效：pending 已保存拆解上下文（mode=breakdown, currentTaskIndex=1）
        PendingInteraction pending = humanInteractionManager.loadInteraction("test-session");
        assertNotNull(pending, "暂停后应存在 pending 状态");
        assertEquals(PendingInteraction.MODE_BREAKDOWN, pending.getMode(), "mode 应为 breakdown");
        assertEquals(1, pending.getCurrentTaskIndex(), "currentTaskIndex 应为子任务2（index 1，0-based）");
        assertEquals(tasks, pending.getSubTasks(), "subTasks 应为完整任务列表");
        assertEquals(List.of("执行结果"), pending.getSubtaskResults(), "已完成子任务结果应保留");
    }

    @Test
    @DisplayName("子任务执行失败 -> onTaskFailed + 剩余子任务取消 + onComplete 触发（不总结）")
    void subTaskFailed_失败即停并取消剩余() {
        List<SubTask> tasks = List.of(new SubTask(1, "任务1"), new SubTask(2, "任务2"), new SubTask(3, "任务3"));
        // 子任务1失败（model.stream 抛异常）
        doAnswer(invocation -> {
            ThinkingStreamHandler handler = invocation.getArgument(2);
            handler.onError(new RuntimeException("LLM 连接失败"));
            return null;
        }).when(thinkingModel).stream(any(), any(), any());

        TaskBreakdownStream.TaskFailedConsumer failedConsumer = mock(TaskBreakdownStream.TaskFailedConsumer.class);
        TaskBreakdownStream.TaskCancelledConsumer cancelledConsumer = mock(TaskBreakdownStream.TaskCancelledConsumer.class);
        Runnable completeCallback = mock(Runnable.class);

        createStream(tasks)
                .onTaskFailed(failedConsumer)
                .onTaskCancelled(cancelledConsumer)
                .onComplete(completeCallback)
                .start();

        verify(failedConsumer).accept(eq(1), anyString());
        verify(cancelledConsumer).accept(2);
        verify(cancelledConsumer).accept(3);
        verify(completeCallback).run();
    }

    @Test
    @DisplayName("子任务迭代上限 taskExecutionMaxIterations 生效（超限后返回累积内容）")
    void subTaskExceedsMaxIterations_返回累积内容() {
        List<SubTask> tasks = List.of(new SubTask(1, "任务1"));
        // 每轮都 tool_calls 但非 askUser（业务工具），永不 stop -> 达到 maxIterations
        doAnswer(invocation -> {
            ThinkingStreamHandler handler = invocation.getArgument(2);
            handler.onPartialResponse("部分内容");
            ToolCall tc = new ToolCall();
            tc.setId("call_1");
            tc.setFunctionName("httpGet");
            tc.setArguments("{\"url\":\"http://x\"}");
            handler.onToolCalls(Collections.singletonList(tc));
            handler.onComplete("部分内容", "tool_calls", null);
            return null;
        }).when(thinkingModel).stream(any(), any(), any());
        agentConfig.setTaskExecutionMaxIterations(3);

        TaskBreakdownStream.TaskCompleteConsumer completeConsumer = mock(TaskBreakdownStream.TaskCompleteConsumer.class);
        Runnable completeCallback = mock(Runnable.class);

        createStream(tasks)
                .onTaskComplete(completeConsumer)
                .onComplete(completeCallback)
                .start();

        verify(completeConsumer).accept(1);
        verify(completeCallback).run();
    }
}
