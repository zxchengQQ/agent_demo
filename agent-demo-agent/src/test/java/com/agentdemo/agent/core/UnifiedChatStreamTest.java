package com.agentdemo.agent.core;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.agent.prompt.PromptTemplateLoader;
import com.agentdemo.agent.single.SessionToolResolver;
import com.agentdemo.llm.thinking.ThinkingStreamingChatModel;
import com.agentdemo.llm.registry.ModelFactory;
import com.agentdemo.llm.thinking.ThinkingStreamHandler;
import com.agentdemo.llm.thinking.ToolCall;
import com.agentdemo.memory.shortterm.ChatMemoryManager;
import com.agentdemo.tools.permission.ToolPermissionLevel;
import com.agentdemo.tools.registry.ToolExecutor;
import com.agentdemo.tools.registry.ToolRegistry;
import com.agentdemo.tools.registry.ToolSchemaConverter;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * UnifiedChatStream 单元测试（unified-chat-mode Task-10/11）
 * <p>
 * 验证标准来源：unified-chat-mode 任务规划 Task-10/11 验证标准
 * 关联 AC：AC-N01/N02/N03/N04、AC-T01/T05、AC-E01/E02、AC-M01/M02
 * 业务含义：验证统一编排核心的四个路径——直答（judge 空）、拆解（judge 非空）、
 * 强制拆解（/plan 跳过判断）、恢复（按 pending.mode 分流）。
 * </p>
 */
class UnifiedChatStreamTest {

    private ModelFactory modelFactory;
    private ThinkingStreamingChatModel thinkingModel;
    private ChatMemoryManager memoryManager;
    private AgentConfig agentConfig;
    private ToolSchemaConverter toolSchemaConverter;
    private ToolExecutor toolExecutor;
    private ChatMemory chatMemory;
    private HumanInteractionManager humanInteractionManager;
    private SessionToolResolver sessionToolResolver;
    private TaskPlanJudge taskPlanJudge;
    private ToolRegistry toolRegistry;

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
        toolRegistry = mock(ToolRegistry.class);
        sessionToolResolver = new SessionToolResolver(toolRegistry, agentConfig);
        taskPlanJudge = mock(TaskPlanJudge.class);

        when(modelFactory.getDefaultThinkingStreamingChatModel()).thenReturn(thinkingModel);
        when(toolSchemaConverter.convertToJson(anyList())).thenReturn("[]");
        when(toolSchemaConverter.convertToDescriptionText(anyList())).thenReturn("工具描述");
        when(memoryManager.getMemory(anyString())).thenReturn(chatMemory);
        when(chatMemory.messages()).thenReturn(new ArrayList<>());
        when(toolExecutor.execute(anyString(), anyString())).thenReturn("工具结果");
        when(toolRegistry.resolveToolsForStreaming(anyList())).thenReturn(List.of());
        when(toolRegistry.resolveToolsForDirect(anyList())).thenReturn(List.of());
        when(toolRegistry.getDefaultToolsForStreaming(anyList())).thenReturn(List.of());
        when(toolRegistry.getDefaultToolsForDirect(anyList())).thenReturn(List.of());
    }

    private UnifiedChatStream createStream(String message, boolean forcedBreakdown, boolean resumeMode, Boolean approved) {
        return new UnifiedChatStream(
                "test-session", message, null, forcedBreakdown, resumeMode, null, approved,
                modelFactory, memoryManager, agentConfig, toolSchemaConverter, toolExecutor,
                new PromptTemplateLoader(agentConfig), humanInteractionManager,
                sessionToolResolver, taskPlanJudge);
    }

    /** 兼容旧签名（approved=null，非 tool_confirm 恢复路径） */
    private UnifiedChatStream createStream(String message, boolean forcedBreakdown, boolean resumeMode) {
        return createStream(message, forcedBreakdown, resumeMode, null);
    }

    /** 模拟单轮 LLM 调用（finishReason=stop）——直答路径 */
    private Object mockDirectStop(org.mockito.invocation.InvocationOnMock invocation) {
        ThinkingStreamHandler handler = invocation.getArgument(2);
        handler.onPartialThinking("推理");
        handler.onPartialResponse("直接回答");
        handler.onComplete("直接回答", "stop", null);
        return null;
    }

    // ========== Task-10：直答路径 ==========

    @Test
    @DisplayName("judge 返回空列表 -> 直答路径（hitl 场景 + 思考折叠块事件，无 task_* 事件）")
    void judgeEmpty_走直答路径() {
        when(taskPlanJudge.judge(anyString(), anyString(), isNull())).thenReturn(List.of());
        doAnswer(this::mockDirectStop).when(thinkingModel).stream(any(), any(), any());

        ThinkingTokenStream.ThinkingConsumer thinkingConsumer = mock(ThinkingTokenStream.ThinkingConsumer.class);
        ThinkingTokenStream.ResponseConsumer responseConsumer = mock(ThinkingTokenStream.ResponseConsumer.class);
        ThinkingTokenStream.CompleteConsumer completeConsumer = mock(ThinkingTokenStream.CompleteConsumer.class);
        TaskBreakdownStream.PlanConsumer planConsumer = mock(TaskBreakdownStream.PlanConsumer.class);

        createStream("你好", false, false)
                .onPartialThinking(thinkingConsumer)
                .onPartialResponse(responseConsumer)
                .onComplete(completeConsumer)
                .onPlan(planConsumer)
                .start();

        verify(thinkingConsumer).accept("推理");
        verify(responseConsumer).accept("直接回答");
        verify(completeConsumer).accept("直接回答");
        verify(planConsumer, never()).accept(anyList());
    }

    @Test
    @DisplayName("直答路径 askUser 拦截 -> onAskUser 触发 + onComplete 不触发（saveInteraction mode=direct）")
    void directPathAskUser_暂停() {
        when(taskPlanJudge.judge(anyString(), anyString(), isNull())).thenReturn(List.of());
        doAnswer(invocation -> {
            ThinkingStreamHandler handler = invocation.getArgument(2);
            handler.onPartialResponse("需要追问");
            ToolCall tc = new ToolCall();
            tc.setId("call_ask");
            tc.setFunctionName("askUser");
            tc.setArguments("{\"type\":\"confirm\",\"question\":\"确认删除？\",\"options\":[\"确认\",\"取消\"]}");
            handler.onToolCalls(List.of(tc));
            handler.onComplete("需要追问", "tool_calls", null);
            return null;
        }).when(thinkingModel).stream(any(), any(), any());

        HitlTokenStream.AskUserConsumer askUserConsumer = mock(HitlTokenStream.AskUserConsumer.class);
        ThinkingTokenStream.CompleteConsumer completeConsumer = mock(ThinkingTokenStream.CompleteConsumer.class);

        createStream("删除文件", false, false)
                .onAskUser(askUserConsumer)
                .onComplete(completeConsumer)
                .start();

        verify(askUserConsumer).accept(eq("confirm"), eq("确认删除？"), anyList(), anyInt());
        verify(completeConsumer, never()).accept(anyString());

        // pending 已保存（mode=direct）
        PendingInteraction pending = humanInteractionManager.loadInteraction("test-session");
        assertNotNull(pending);
        assertEquals(PendingInteraction.MODE_DIRECT, pending.getMode());
    }

    @Test
    @DisplayName("forced 且空内容 -> 友好提示 + onComplete，不调用 judge、不写记忆、不拆解（AC-E02）")
    void forcedEmptyContent_友好提示() {
        when(taskPlanJudge.judge(anyString(), anyString(), isNull())).thenReturn(List.of());

        ThinkingTokenStream.ResponseConsumer responseConsumer = mock(ThinkingTokenStream.ResponseConsumer.class);
        ThinkingTokenStream.CompleteConsumer completeConsumer = mock(ThinkingTokenStream.CompleteConsumer.class);
        TaskBreakdownStream.PlanConsumer planConsumer = mock(TaskBreakdownStream.PlanConsumer.class);

        createStream("   ", true, false)
                .onPartialResponse(responseConsumer)
                .onComplete(completeConsumer)
                .onPlan(planConsumer)
                .start();

        verify(responseConsumer).accept(org.mockito.ArgumentMatchers.contains("/plan"));
        verify(completeConsumer).accept(anyString());
        verify(taskPlanJudge, never()).judge(anyString(), anyString(), isNull());
        verify(planConsumer, never()).accept(anyList());
        verify(memoryManager, never()).addUserMessage(anyString(), anyString());
    }

    // ========== Task-11：拆解路由与恢复路由 ==========

    @Test
    @DisplayName("judge 返回非空列表 -> 拆解路径（onPlan + task_* 事件）")
    void judgeNonEmpty_走拆解路径() {
        List<SubTask> tasks = List.of(new SubTask(1, "调研"), new SubTask(2, "报告"));
        when(taskPlanJudge.judge(anyString(), anyString(), isNull())).thenReturn(tasks);
        doAnswer(invocation -> {
            ThinkingStreamHandler handler = invocation.getArgument(2);
            handler.onPartialResponse("子任务结果");
            handler.onComplete("子任务结果", "stop", null);
            return null;
        }).when(thinkingModel).stream(any(), any(), any());

        TaskBreakdownStream.PlanConsumer planConsumer = mock(TaskBreakdownStream.PlanConsumer.class);
        TaskBreakdownStream.TaskCompleteConsumer completeConsumer = mock(TaskBreakdownStream.TaskCompleteConsumer.class);
        ThinkingTokenStream.CompleteConsumer onComplete = mock(ThinkingTokenStream.CompleteConsumer.class);

        createStream("调研竞品并写报告", false, false)
                .onPlan(planConsumer)
                .onTaskComplete(completeConsumer)
                .onComplete(onComplete)
                .start();

        verify(planConsumer).accept(tasks);
        verify(completeConsumer).accept(1);
        verify(completeConsumer).accept(2);
        verify(onComplete).accept(anyString());
    }

    @Test
    @DisplayName("forced=true -> 跳过判断（judge 空也不降级直答，强制单一子任务拆解）AC-N04")
    void forcedBreakdown_强制拆解不降级直答() {
        // judge 返回空（LLM 认为简单），但 forced 强制拆解为单一子任务
        when(taskPlanJudge.judge(anyString(), anyString(), isNull())).thenReturn(List.of());
        doAnswer(invocation -> {
            ThinkingStreamHandler handler = invocation.getArgument(2);
            handler.onPartialResponse("子任务结果");
            handler.onComplete("子任务结果", "stop", null);
            return null;
        }).when(thinkingModel).stream(any(), any(), any());

        TaskBreakdownStream.PlanConsumer planConsumer = mock(TaskBreakdownStream.PlanConsumer.class);
        TaskBreakdownStream.TaskStartConsumer startConsumer = mock(TaskBreakdownStream.TaskStartConsumer.class);

        createStream("调研竞品", true, false)
                .onPlan(planConsumer)
                .onTaskStart(startConsumer)
                .start();

        // judge 被调用（生成子任务），空结果强制单一子任务（不降级直答）
        verify(taskPlanJudge).judge(anyString(), anyString(), isNull());
        verify(startConsumer).accept(anyInt(), anyString());
    }

    @Test
    @DisplayName("resume 且 pending.mode=breakdown -> resumeFromPending 续跑")
    void resumeModeBreakdown_子任务续跑() {
        // 预置 breakdown pending（子任务1已完成，子任务2暂停）
        List<SubTask> tasks = List.of(new SubTask(1, "任务1"), new SubTask(2, "任务2"));
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("系统提示"));
        messages.add(UserMessage.from("子任务2缺订单号"));
        messages.add(dev.langchain4j.data.message.AiMessage.aiMessage("", List.of(
                dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                        .id("call_ask").name("askUser")
                        .arguments("{\"type\":\"text\",\"question\":\"请提供订单号\"}")
                        .build())));
        humanInteractionManager.saveInteraction("test-session", messages,
                "text", "请提供订单号", null, 0, null, "[]", PendingInteraction.MODE_BREAKDOWN);
        humanInteractionManager.attachBreakdownContext("test-session", tasks, 1, List.of("子任务1结果"));

        doAnswer(invocation -> {
            ThinkingStreamHandler handler = invocation.getArgument(2);
            handler.onPartialResponse("续跑结果");
            handler.onComplete("续跑结果", "stop", null);
            return null;
        }).when(thinkingModel).stream(any(), any(), any());

        TaskBreakdownStream.PlanConsumer planConsumer = mock(TaskBreakdownStream.PlanConsumer.class);
        TaskBreakdownStream.TaskCompleteConsumer completeConsumer = mock(TaskBreakdownStream.TaskCompleteConsumer.class);

        // resume 模式：message = 用户回复
        createStream("ORD-12345", false, true)
                .onPlan(planConsumer)
                .onTaskComplete(completeConsumer)
                .start();

        // 恢复：重放 onPlan + 已完成子任务1 onTaskComplete + 子任务2/3 实际执行
        verify(planConsumer).accept(tasks);
        verify(completeConsumer).accept(1); // 重放
        verify(completeConsumer).accept(2); // 续跑完成
    }

    @Test
    @DisplayName("resume 且 pending.mode=direct -> 直答恢复（续用上下文 + retryCount+1）")
    void resumeModeDirect_直答恢复() {
        // 预置 direct pending（含 askUser 工具调用）
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("系统提示"));
        messages.add(UserMessage.from("需要订单号"));
        messages.add(dev.langchain4j.data.message.AiMessage.aiMessage("", List.of(
                dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                        .id("call_ask").name("askUser")
                        .arguments("{\"type\":\"text\",\"question\":\"请提供订单号\"}")
                        .build())));
        humanInteractionManager.saveInteraction("test-session", messages,
                "text", "请提供订单号", null, 0, null, "[]", PendingInteraction.MODE_DIRECT);

        doAnswer(this::mockDirectStop).when(thinkingModel).stream(any(), any(), any());

        ThinkingTokenStream.ResponseConsumer responseConsumer = mock(ThinkingTokenStream.ResponseConsumer.class);
        ThinkingTokenStream.CompleteConsumer completeConsumer = mock(ThinkingTokenStream.CompleteConsumer.class);

        createStream("ORD-12345", false, true)
                .onPartialResponse(responseConsumer)
                .onComplete(completeConsumer)
                .start();

        verify(responseConsumer).accept("直接回答");
        verify(completeConsumer).accept("直接回答");
        // pending 已清除（恢复完成）
        org.junit.jupiter.api.Assertions.assertFalse(humanInteractionManager.hasPending("test-session"));
    }

    @Test
    @DisplayName("resume 且回复含 /plan 前缀 -> 不触发强制拆解（视为普通文本，决策 6）")
    void resumeModePlanPrefix_不触发强制拆解() {
        // 预置 direct pending
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("系统提示"));
        messages.add(UserMessage.from("需要确认"));
        messages.add(dev.langchain4j.data.message.AiMessage.aiMessage("", List.of(
                dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                        .id("call_ask").name("askUser")
                        .arguments("{\"type\":\"confirm\",\"question\":\"确认？\",\"options\":[\"确认\",\"取消\"]}")
                        .build())));
        humanInteractionManager.saveInteraction("test-session", messages,
                "confirm", "确认？", List.of("确认", "取消"), 0, null, "[]", PendingInteraction.MODE_DIRECT);

        doAnswer(this::mockDirectStop).when(thinkingModel).stream(any(), any(), any());

        ThinkingTokenStream.CompleteConsumer completeConsumer = mock(ThinkingTokenStream.CompleteConsumer.class);

        // 用户回复 "/plan 删除"——恢复模式下应作为普通文本，不触发强制拆解
        createStream("/plan 删除", false, true)
                .onComplete(completeConsumer)
                .start();

        verify(completeConsumer).accept("直接回答");
        // 未进入拆解路径（无 onPlan）
        verify(taskPlanJudge, never()).judge(anyString(), anyString(), isNull());
    }

    // ========== Task-11：tool_confirm 恢复 ==========

    /** 预置 tool_confirm pending（该轮 AiMessage 含 toolCall id=call_http，待确认工具 httpGet） */
    private List<ChatMessage> presetToolConfirmPending() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("系统提示"));
        messages.add(UserMessage.from("需要 http 请求"));
        messages.add(dev.langchain4j.data.message.AiMessage.aiMessage("", List.of(
                dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                        .id("call_http").name("httpGet")
                        .arguments("{\"url\":\"https://example.com\"}")
                        .build())));
        humanInteractionManager.saveToolConfirmInteraction("test-session", messages,
                null, "[]", "call_http", "httpGet", "{\"url\":\"https://example.com\"}");
        return messages;
    }

    @Test
    @DisplayName("resume 且 mode=tool_confirm 且 approved=true -> 批准执行工具，回填 id 匹配结果，pending 清除，循环续跑（AC-N03）")
    void resumeToolConfirm_批准执行工具() {
        List<ChatMessage> messages = presetToolConfirmPending();
        when(toolExecutor.execute(eq("httpGet"), eq("{\"url\":\"https://example.com\"}"))).thenReturn("HTTP 200 OK");
        doAnswer(this::mockDirectStop).when(thinkingModel).stream(any(), any(), any());

        ThinkingTokenStream.CompleteConsumer completeConsumer = mock(ThinkingTokenStream.CompleteConsumer.class);

        createStream("", false, true, Boolean.TRUE)
                .onComplete(completeConsumer)
                .start();

        // 工具被执行一次，参数为 pending 原样参数（不重复解析）
        verify(toolExecutor).execute(eq("httpGet"), eq("{\"url\":\"https://example.com\"}"));
        // 结果消息 id 与 pendingToolCallId 匹配（LLM 会话一致性，风险 §5）
        ChatMessage last = messages.get(messages.size() - 1);
        assertTrue(last instanceof ToolExecutionResultMessage);
        ToolExecutionResultMessage resultMsg = (ToolExecutionResultMessage) last;
        assertEquals("call_http", resultMsg.id());
        assertEquals("httpGet", resultMsg.toolName());
        assertEquals("HTTP 200 OK", resultMsg.text());
        // pending 已清除 + 循环续跑（onComplete 触发）
        assertFalse(humanInteractionManager.hasPending("test-session"));
        verify(completeConsumer).accept("直接回答");
    }

    @Test
    @DisplayName("resume 且 mode=tool_confirm 且 approved=false -> 工具零执行，拒绝文案回填，pending 清除（AC-S02）")
    void resumeToolConfirm_拒绝不执行() {
        List<ChatMessage> messages = presetToolConfirmPending();
        doAnswer(this::mockDirectStop).when(thinkingModel).stream(any(), any(), any());

        ThinkingTokenStream.CompleteConsumer completeConsumer = mock(ThinkingTokenStream.CompleteConsumer.class);

        createStream("", false, true, Boolean.FALSE)
                .onComplete(completeConsumer)
                .start();

        // 工具零执行（approve=false 不触发 ToolExecutor）
        verify(toolExecutor, never()).execute(anyString(), anyString());
        // 拒绝文案回填：含"用户拒绝"语义，id 匹配 pendingToolCallId
        ChatMessage last = messages.get(messages.size() - 1);
        assertTrue(last instanceof ToolExecutionResultMessage);
        ToolExecutionResultMessage resultMsg = (ToolExecutionResultMessage) last;
        assertEquals("call_http", resultMsg.id());
        assertEquals("httpGet", resultMsg.toolName());
        assertTrue(resultMsg.text().contains("用户拒绝"), "拒绝文案应包含'用户拒绝'语义");
        assertFalse(resultMsg.text().contains("deny"), "拒绝文案不得包含权限配置细节");
        // pending 已清除 + 循环续跑
        assertFalse(humanInteractionManager.hasPending("test-session"));
        verify(completeConsumer).accept("直接回答");
    }

    @Test
    @DisplayName("resume 且无 pending -> 降级普通流程（不因 tool_confirm 分支引入 NPE）")
    void resumeNoPending_降级普通流程() {
        when(taskPlanJudge.judge(anyString(), anyString(), isNull())).thenReturn(List.of());
        doAnswer(this::mockDirectStop).when(thinkingModel).stream(any(), any(), any());

        ThinkingTokenStream.CompleteConsumer completeConsumer = mock(ThinkingTokenStream.CompleteConsumer.class);

        createStream("你好", false, true)
                .onComplete(completeConsumer)
                .start();

        // 无 pending 时按现有逻辑降级 judge 判断走直答
        verify(taskPlanJudge).judge(anyString(), anyString(), isNull());
        verify(completeConsumer).accept("直接回答");
    }

    @Test
    @DisplayName("直答路径 ask 级工具暂停 -> 宿主补保存 tool_confirm 快照（mode + toolCallId + 三字段 + messages，AC-M02）")
    void directPathAskTool_宿主补保存快照() {
        // 业务含义（Task-11 决策 2 方案 A）：HITLReActStream 快照职责外移后，
        // 单 Agent 宿主 registerHitlCallbacks 在 onToolConfirm 回调内补保存快照，
        // 恢复路由 resumeToolConfirm 按 pendingToolCallId 回填结果消息。
        when(taskPlanJudge.judge(anyString(), anyString(), isNull())).thenReturn(List.of());
        when(toolExecutor.checkPermission("httpGet"))
                .thenReturn(new ToolExecutor.ToolPermissionCheck(
                        ToolPermissionLevel.ASK, "builtin:httpGet", "发起 HTTP GET 请求"));
        doAnswer(invocation -> {
            ThinkingStreamHandler handler = invocation.getArgument(2);
            handler.onPartialResponse("需要确认");
            ToolCall tc = new ToolCall();
            tc.setId("call_http");
            tc.setFunctionName("httpGet");
            tc.setArguments("{\"url\":\"https://example.com\"}");
            handler.onToolCalls(List.of(tc));
            handler.onComplete("需要确认", "tool_calls", null);
            return null;
        }).when(thinkingModel).stream(any(), any(), any());

        HitlTokenStream.ToolConfirmConsumer confirmConsumer = mock(HitlTokenStream.ToolConfirmConsumer.class);
        ThinkingTokenStream.CompleteConsumer completeConsumer = mock(ThinkingTokenStream.CompleteConsumer.class);

        createStream("访问网页", false, false)
                .onToolConfirm(confirmConsumer)
                .onComplete(completeConsumer)
                .start();

        // 4 参回调触发（含 toolCallId）
        verify(confirmConsumer).accept("call_http", "httpGet", "发起 HTTP GET 请求", "{\"url\":\"https://example.com\"}");
        // 快照已保存（真实 HumanInteractionManager）：mode=tool_confirm + toolCallId + 三字段
        PendingInteraction pending = humanInteractionManager.loadInteraction("test-session");
        assertNotNull(pending, "ask 级工具暂停后应保存 tool_confirm 快照");
        assertEquals(PendingInteraction.MODE_TOOL_CONFIRM, pending.getMode());
        assertEquals("call_http", pending.getPendingToolCallId());
        assertEquals("httpGet", pending.getPendingToolName());
        assertEquals("{\"url\":\"https://example.com\"}", pending.getPendingToolArguments());
        // 暂停：onComplete 不触发
        verify(completeConsumer, never()).accept(anyString());
    }
}
