package com.agentdemo.app.execution;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.agent.core.HumanInteractionManager;
import com.agentdemo.agent.prompt.PromptTemplateLoader;
import com.agentdemo.agent.single.SessionToolResolver;
import com.agentdemo.app.adapter.AgenticAgentFactory;
import com.agentdemo.app.core.AgentDefinition;
import com.agentdemo.app.service.TestTokenStream;
import com.agentdemo.app.service.WorkflowHITLException;
import com.agentdemo.app.service.WorkflowHITLState;
import com.agentdemo.llm.registry.ModelFactory;
import com.agentdemo.llm.thinking.ThinkingStreamHandler;
import com.agentdemo.llm.thinking.ThinkingStreamingChatModel;
import com.agentdemo.llm.thinking.ToolCall;
import com.agentdemo.tools.permission.ToolPermissionLevel;
import com.agentdemo.tools.registry.ToolExecutor;
import com.agentdemo.tools.registry.ToolRegistry;
import com.agentdemo.tools.registry.ToolSchemaConverter;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.V;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentExecutor hitlEnabled 分流 + askUser HITL 拦截测试（Task-06）
 * <p>
 * 验证标准来源：Task-06 验证标准
 * 关联 AC：AC-N01（Agent 主动追问）、AC-T01（askUser 拦截机制）、AC-S02（追问次数上限）
 * </p>
 * <p>
 * 业务含义：hitlEnabled=true 时 AgentExecutor 使用 HITLReActStream（显式 ReAct）而非 TokenStream；
 * Agent 调用 askUser 工具时 HITLReActStream 拦截（不执行方法体），触发 onAskUser 回调——
 * 构造 askUser 模式 HITL 快照（消息列表 + 提问数据 + 暂停步骤），推送 ask_user + workflow_waiting
 * 事件，抛出 WorkflowHITLException（协调层捕获后进入 WAITING_USER）。hitlEnabled=false 走 TokenStream 零回归。
 * </p>
 */
class AgentExecutorHITLTest {

    /** HITL Agent 测试接口（hitlEnabled=true 时走 HITLReActStream，方法体不应被调用） */
    interface HitlAgent {
        @Agent
        TokenStream run(@V("topic") String topic);
    }

    private AgenticAgentFactory agentFactory;
    private ModelFactory modelFactory;
    private ToolRegistry toolRegistry;
    private ToolSchemaConverter toolSchemaConverter;
    private ToolExecutor toolExecutor;
    private PromptTemplateLoader promptTemplateLoader;
    private HumanInteractionManager humanInteractionManager;
    private AgentConfig agentConfig;
    private SessionToolResolver sessionToolResolver;
    private ThinkingStreamingChatModel thinkingModel;

    @BeforeEach
    void setUp() {
        agentFactory = mock(AgenticAgentFactory.class);
        modelFactory = mock(ModelFactory.class);
        toolRegistry = mock(ToolRegistry.class);
        toolSchemaConverter = mock(ToolSchemaConverter.class);
        toolExecutor = mock(ToolExecutor.class);
        promptTemplateLoader = mock(PromptTemplateLoader.class);
        humanInteractionManager = mock(HumanInteractionManager.class);
        agentConfig = mock(AgentConfig.class);
        sessionToolResolver = mock(SessionToolResolver.class);
        thinkingModel = mock(ThinkingStreamingChatModel.class);

        when(modelFactory.getDefaultThinkingStreamingChatModel()).thenReturn(thinkingModel);
        when(modelFactory.getThinkingStreamingChatModelByModelId(anyString())).thenReturn(thinkingModel);
        // Task-08：HITL 路径走 ForStreaming（deny 剔除、ask 保留）
        when(toolRegistry.resolveToolsForStreaming(any())).thenReturn(List.of());
        when(toolSchemaConverter.convertToJson(any())).thenReturn("[]");
        when(toolSchemaConverter.convertToDescriptionText(any())).thenReturn("可用工具描述");
        when(promptTemplateLoader.composeSystemPrompt(anyString(), anyString())).thenReturn("HITL 系统提示词");
        when(agentConfig.getThinkingMaxIterations()).thenReturn(8);
        when(sessionToolResolver.ensureAskUserTool(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    /** 全依赖构造器（HITL 路径需要，Spring 注入用） */
    private AgentExecutor hitlExecutor() {
        return new AgentExecutor(agentFactory, 5, modelFactory, toolRegistry, toolSchemaConverter,
                toolExecutor, promptTemplateLoader, humanInteractionManager, agentConfig, sessionToolResolver);
    }

    private AgentDefinition hitlAgentDef() {
        return AgentDefinition.builder()
                .name("HITL Agent")
                .description("d")
                .toolIds(List.of("builtin:askUser"))
                .roleName("general")
                .scenarioName("hitl")
                .interfaceClass(HitlAgent.class)
                .hitlEnabled(true)
                .build();
    }

    /**
     * 业务含义：mock ThinkingStreamingChatModel 触发一轮 askUser 工具调用——
     * handler.onToolCalls 推送 askUser，onComplete 携带 finishReason=tool_calls，
     * HITLReActStream 收到后拦截 askUser（不执行方法体）进入暂停流程。
     */
    private void stubAskUserCall() {
        doAnswer(invocation -> {
            ThinkingStreamHandler handler = invocation.getArgument(2);
            ToolCall tc = new ToolCall();
            tc.setId("call-1");
            tc.setFunctionName("askUser");
            tc.setArguments("{\"type\":\"text\",\"question\":\"请确认输入？\"}");
            handler.onToolCalls(List.of(tc));
            handler.onComplete("", "tool_calls", null);
            return null;
        }).when(thinkingModel).stream(any(), anyString(), any(ThinkingStreamHandler.class));
    }

    // ========== 验证标准：hitlEnabled=true 时使用 HITLReActStream，askUser 拦截抛 WorkflowHITLException ==========

    @Test
    void hitlEnabled_true_askUser调用_应抛WorkflowHITLException且模式为askUser() {
        stubAskUserCall();
        AgentExecutor executor = hitlExecutor();
        SseEmitter emitter = mock(SseEmitter.class);

        WorkflowHITLException ex = assertThrows(WorkflowHITLException.class,
                () -> executor.executeWithRetry(hitlAgentDef(), "输入", emitter, 1, 0, null, 0, "exec-1"));

        assertEquals(WorkflowHITLState.MODE_ASK_USER, ex.getHitlState().getHitlMode());
        assertEquals("HITL Agent", ex.getHitlState().getPendingStep().getAgentName());
        assertEquals(1, ex.getHitlState().getPendingStep().getAgentIndex());
        assertEquals("输入", ex.getHitlState().getPendingStep().getInput());
    }

    // ========== 验证标准：HITL 快照（askUser 模式）保存消息列表 + askUser 数据 ==========

    @Test
    void hitlEnabled_true_快照应携带提问数据与消息列表() {
        stubAskUserCall();
        AgentExecutor executor = hitlExecutor();
        SseEmitter emitter = mock(SseEmitter.class);

        WorkflowHITLException ex = assertThrows(WorkflowHITLException.class,
                () -> executor.executeWithRetry(hitlAgentDef(), "输入", emitter, 1, 0, null, 0, "exec-1"));

        // askUser 提问数据（type/question 来自 askUser 参数）
        WorkflowHITLState.AskUserData data = ex.getHitlState().getAskUserData();
        assertEquals("text", data.getType());
        assertEquals("请确认输入？", data.getQuestion());

        // 消息列表：SystemMessage（HITL 系统提示词）+ UserMessage（输入）+ 该轮 AiMessage
        assertNotNull(ex.getHitlState().getMessages());
        assertFalse(ex.getHitlState().getMessages().isEmpty());
        assertTrue(ex.getHitlState().getMessages().get(0) instanceof SystemMessage);
        assertTrue(ex.getHitlState().getMessages().get(1) instanceof UserMessage);
    }

    // ========== 验证标准：消息列表从 AgentDefinition 系统提示词 + 输入构建；工具 JSON 从 toolIds 构建 ==========

    @Test
    void hitlEnabled_true_系统提示词使用HITL场景且工具JSON从toolIds构建() {
        stubAskUserCall();
        AgentExecutor executor = hitlExecutor();
        SseEmitter emitter = mock(SseEmitter.class);

        assertThrows(WorkflowHITLException.class,
                () -> executor.executeWithRetry(hitlAgentDef(), "输入", emitter, 1, 0, null, 0, "exec-1"));

        // HITL 系统提示词：composeSystemPrompt 使用 SCENARIO_HITL 场景
        verify(promptTemplateLoader).composeSystemPrompt(anyString(), eq(PromptTemplateLoader.SCENARIO_HITL));
        // 工具从 AgentDefinition.toolIds 经 ForStreaming 解析 + 补入 askUser 工具（Task-08）
        verify(toolRegistry).resolveToolsForStreaming(any());
        verify(sessionToolResolver).ensureAskUserTool(any());
        // 工具 JSON 由 ToolSchemaConverter 生成
        verify(toolSchemaConverter).convertToJson(any());
    }

    // ========== 验证标准：复合 sessionId（executionId:agentIndex）隔离保存交互状态 ==========

    @Test
    void hitlEnabled_true_复合sessionId应隔离保存交互状态() {
        stubAskUserCall();
        AgentExecutor executor = hitlExecutor();
        SseEmitter emitter = mock(SseEmitter.class);

        assertThrows(WorkflowHITLException.class,
                () -> executor.executeWithRetry(hitlAgentDef(), "输入", emitter, 2, 0, null, 0, "exec-42"));

        // HITLReActStream 内部 saveInteraction 使用复合键 executionId:agentIndex
        verify(humanInteractionManager).saveInteraction(
                eq("exec-42:2"), any(), anyString(), anyString(), any(), anyInt(), any(), anyString());
    }

    // ========== 验证标准：事件推送归属协调层（Task-07 handleHITLPaused），AgentExecutor 仅抛异常避免重复 ==========

    @Test
    void hitlEnabled_true_应仅抛异常且不推送askUser事件() {
        stubAskUserCall();
        AgentExecutor executor = hitlExecutor();
        SseEmitter emitter = mock(SseEmitter.class);

        try (MockedStatic<WorkflowEventPublisher> publisherMock = mockStatic(WorkflowEventPublisher.class)) {
            assertThrows(WorkflowHITLException.class,
                    () -> executor.executeWithRetry(hitlAgentDef(), "输入", emitter, 1, 0, null, 0, "exec-1"));

            // 业务含义：ask_user + workflow_waiting 事件由协调层 handleHITLPaused 统一推送（Task-07），
            // AgentExecutor 仅抛 WorkflowHITLException，避免事件与状态流转重复
            publisherMock.verify(() -> WorkflowEventPublisher.send(eq(emitter), eq("ask_user"), any()), never());
            publisherMock.verify(() -> WorkflowEventPublisher.send(eq(emitter), eq("workflow_waiting"), any()), never());
        }
    }

    // ========== 验证标准：hitlEnabled=false 时行为零回归 ==========

    @Test
    void hitlEnabled_false_应走TokenStream零回归() {
        AgentDefinition plainDef = AgentDefinition.builder()
                .name("普通 Agent")
                .description("d")
                .toolIds(List.of())
                .interfaceClass(HitlAgent.class)
                .build();

        HitlAgent agent = mock(HitlAgent.class);
        TestTokenStream stream = new TestTokenStream();
        stream.completeWith("正常输出");
        when(agent.run(anyString())).thenReturn(stream);
        when(agentFactory.buildAgent(any())).thenReturn(agent);

        // 2 参构造器（HITL 依赖为 null），hitlEnabled=false 走 TokenStream 路径，不触碰 HITL 依赖
        AgentExecutor executor = new AgentExecutor(agentFactory);
        SseEmitter emitter = mock(SseEmitter.class);

        String output = executor.executeWithRetry(plainDef, "输入", emitter, 0, 0, null, 0, "exec-1");
        assertEquals("正常输出", output);
        verify(agent).run("输入");
    }

    // ========== Task-08 验证标准：hitlReply 恢复（askUser 注入回复续跑 / checkpoint 确认后执行方法） ==========

    /** askUser 模式 HITL 快照（消息列表含 askUser 工具调用的 AiMessage） */
    private WorkflowHITLState askUserHitlStateWithMessages() {
        List<ChatMessage> snapshotMessages = new ArrayList<>();
        snapshotMessages.add(SystemMessage.from("HITL 系统提示词"));
        snapshotMessages.add(UserMessage.from("输入"));
        snapshotMessages.add(AiMessage.aiMessage("", List.of(
                ToolExecutionRequest.builder().id("call-1").name("askUser").arguments("{}").build())));
        WorkflowHITLState.AskUserData askUserData =
                new WorkflowHITLState.AskUserData("text", "请确认输入？", List.of(), 1);
        WorkflowHITLState.PendingStep pendingStep =
                new WorkflowHITLState.PendingStep(1, "HITL Agent", "输入", 0);
        return new WorkflowHITLState(WorkflowHITLState.MODE_ASK_USER, askUserData, pendingStep, snapshotMessages, 1);
    }

    @Test
    void executeHitlResume_askUser_应追加用户回复为ToolExecutionResultMessage并续跑ReAct() throws Exception {
        // 业务含义：恢复时在快照消息列表（已含 askUser 工具调用）上追加用户回复
        // ToolExecutionResultMessage（id=call-1 匹配），新建 HITLReActStream(retryCount+1) 续跑（AC-N03/AC-M02）
        doAnswer(invocation -> {
            ThinkingStreamHandler handler = invocation.getArgument(2);
            // 业务含义：HITLReActStream 的最终回答从 result.content 累积（onPartialResponse 追加），
            // 需先推送 token 再以 stop 结束（与 stubAskUserCall 的 tool_calls 模式同构）
            handler.onPartialResponse("最终回答");
            handler.onComplete("最终回答", "stop", null);
            return null;
        }).when(thinkingModel).stream(any(), anyString(), any(ThinkingStreamHandler.class));

        AgentExecutor executor = hitlExecutor();
        SseEmitter emitter = mock(SseEmitter.class);

        String output = executor.executeHitlResume(hitlAgentDef(), askUserHitlStateWithMessages(),
                "用户回复", null, emitter, 1, "exec-1");

        assertEquals("最终回答", output);
        // 消息列表最后一条为用户回复（ToolExecutionResultMessage），携带原 askUser 工具调用语义
        verify(thinkingModel).stream(argThat(messages -> {
            if (messages.isEmpty()) {
                return false;
            }
            ChatMessage last = messages.get(messages.size() - 1);
            return last instanceof ToolExecutionResultMessage ter
                    && "askUser".equals(ter.toolName())
                    && "用户回复".equals(ter.text());
        }), anyString(), any());
    }

    @Test
    void executeHitlResume_askUser_恢复中再次askUser_应抛新快照且retryCount递增() {
        // 业务含义：恢复中 Agent 再次调用 askUser——构造新快照（消息列表已含本次回复）抛异常，
        // 协调层更新快照进入循环暂停-恢复（AC-N03/AC-S02）
        stubAskUserCall();
        AgentExecutor executor = hitlExecutor();
        SseEmitter emitter = mock(SseEmitter.class);

        WorkflowHITLException ex = assertThrows(WorkflowHITLException.class,
                () -> executor.executeHitlResume(hitlAgentDef(), askUserHitlStateWithMessages(),
                        "回复", null, emitter, 1, "exec-1"));

        assertEquals(WorkflowHITLState.MODE_ASK_USER, ex.getHitlState().getHitlMode());
        // 新快照 retryCount = 原 1 + 1 = 2（HITLReActStream 构造时 retryCount+1，回调携带该值）
        assertEquals(2, ex.getHitlState().getRetryCount());
    }

    @Test
    void executeHitlResume_checkpointApproved_应执行TokenStream方法不重复触发检查点() throws Exception {
        // 业务含义：checkpoint 确认（approved=true）后执行暂停的方法（TokenStream）——
        // 跳过 @HumanCheckpoint 检测（否则会再次抛检查点异常导致死循环，AC-N02/AC-S01）
        AgentDefinition checkpointDef = AgentDefinition.builder()
                .name("分析 Agent")
                .description("d")
                .toolIds(List.of())
                .interfaceClass(HitlAgent.class)
                .build();
        HitlAgent agent = mock(HitlAgent.class);
        TestTokenStream stream = new TestTokenStream();
        stream.completeWith("检查点执行输出");
        when(agent.run(anyString())).thenReturn(stream);
        when(agentFactory.buildAgent(any())).thenReturn(agent);

        WorkflowHITLState hitlState = new WorkflowHITLState(WorkflowHITLState.MODE_CHECKPOINT,
                new WorkflowHITLState.AskUserData("confirm", "确认执行？", List.of("确认", "取消"), 0),
                new WorkflowHITLState.PendingStep(0, "分析 Agent", "输入", 0), null, 0);

        // checkpoint 恢复走 TokenStream，HITL 依赖非必需（2 参构造器即可）
        AgentExecutor executor = new AgentExecutor(agentFactory);
        SseEmitter emitter = mock(SseEmitter.class);

        String output = executor.executeHitlResume(checkpointDef, hitlState, null, true, emitter, 0, "exec-1");
        assertEquals("检查点执行输出", output);
        // 方法体被调用（而非被注解检测拦截抛异常）
        verify(agent).run("输入");
    }

    // ========== Task-13 验证标准：toolConfirm 拦截与恢复（工作流 tool_confirm 状态机） ==========

    /** 模拟一轮 httpGet(ask 级) 工具调用——checkPermission 裁决 ASK，HITLReActStream 拦截走 tool_confirm */
    private void stubHttpToolCall() {
        when(toolExecutor.checkPermission("httpGet"))
                .thenReturn(new ToolExecutor.ToolPermissionCheck(
                        ToolPermissionLevel.ASK, "builtin:httpGet", "发起 HTTP GET 请求"));
        doAnswer(invocation -> {
            ThinkingStreamHandler handler = invocation.getArgument(2);
            ToolCall tc = new ToolCall();
            tc.setId("call-http");
            tc.setFunctionName("httpGet");
            tc.setArguments("{\"url\":\"https://example.com\"}");
            handler.onToolCalls(List.of(tc));
            handler.onComplete("", "tool_calls", null);
            return null;
        }).when(thinkingModel).stream(any(), anyString(), any(ThinkingStreamHandler.class));
    }

    @Test
    void hitlEnabled_true_ask级工具调用_应抛WorkflowHITLException且模式为toolConfirm() {
        stubHttpToolCall();
        AgentExecutor executor = hitlExecutor();
        SseEmitter emitter = mock(SseEmitter.class);

        WorkflowHITLException ex = assertThrows(WorkflowHITLException.class,
                () -> executor.executeWithRetry(hitlAgentDef(), "访问网页", emitter, 1, 0, null, 0, "exec-1"));

        assertEquals(WorkflowHITLState.MODE_TOOL_CONFIRM, ex.getHitlState().getHitlMode());
        assertEquals("HITL Agent", ex.getHitlState().getPendingStep().getAgentName());
        assertEquals(1, ex.getHitlState().getPendingStep().getAgentIndex());
        assertEquals("访问网页", ex.getHitlState().getPendingStep().getInput());
        // ToolConfirmData 四要素（toolCallId/工具名/描述/参数，AC-H01/AC-M02）
        WorkflowHITLState.ToolConfirmData data = ex.getHitlState().getToolConfirmData();
        assertEquals("call-http", data.getToolCallId());
        assertEquals("httpGet", data.getToolName());
        assertEquals("发起 HTTP GET 请求", data.getToolDescription());
        assertEquals("{\"url\":\"https://example.com\"}", data.getArguments());
        // 消息列表非空（ReAct 上下文保持，AC-M02）
        assertNotNull(ex.getHitlState().getMessages());
        assertFalse(ex.getHitlState().getMessages().isEmpty());
        // ask 级工具方法体未执行（拦截暂停）
        verify(toolExecutor, never()).execute("httpGet", "{\"url\":\"https://example.com\"}");
    }

    /** toolConfirm 模式 HITL 快照（消息列表含 httpGet 工具调用的 AiMessage，retryCount=2） */
    private WorkflowHITLState toolConfirmHitlStateWithMessages() {
        List<ChatMessage> snapshotMessages = new ArrayList<>();
        snapshotMessages.add(SystemMessage.from("HITL 系统提示词"));
        snapshotMessages.add(UserMessage.from("访问网页"));
        snapshotMessages.add(AiMessage.aiMessage("", List.of(
                ToolExecutionRequest.builder().id("call-http").name("httpGet")
                        .arguments("{\"url\":\"https://example.com\"}").build())));
        WorkflowHITLState.ToolConfirmData toolConfirmData =
                new WorkflowHITLState.ToolConfirmData("call-http", "httpGet", "发起 HTTP GET 请求", "{\"url\":\"https://example.com\"}");
        WorkflowHITLState.PendingStep pendingStep =
                new WorkflowHITLState.PendingStep(1, "HITL Agent", "访问网页", 0);
        return new WorkflowHITLState(WorkflowHITLState.MODE_TOOL_CONFIRM, toolConfirmData, pendingStep, snapshotMessages, 2);
    }

    @Test
    void executeHitlResume_toolConfirm批准_应执行工具并回填结果续跑() {
        // 业务含义：批准后直接执行待确认工具（参数原样），以暂停时 toolCallId 回填
        // ToolExecutionResultMessage，续跑 ReAct 循环（AC-N03/AC-M02）。
        when(toolExecutor.execute("httpGet", "{\"url\":\"https://example.com\"}")).thenReturn("HTTP 200 OK");
        doAnswer(invocation -> {
            ThinkingStreamHandler handler = invocation.getArgument(2);
            handler.onPartialResponse("最终回答");
            handler.onComplete("最终回答", "stop", null);
            return null;
        }).when(thinkingModel).stream(any(), anyString(), any(ThinkingStreamHandler.class));

        AgentExecutor executor = hitlExecutor();
        SseEmitter emitter = mock(SseEmitter.class);

        String output = executor.executeHitlResume(hitlAgentDef(), toolConfirmHitlStateWithMessages(),
                null, true, emitter, 1, "exec-1");

        assertEquals("最终回答", output);
        // 工具被执行一次，参数为快照原样
        verify(toolExecutor).execute("httpGet", "{\"url\":\"https://example.com\"}");
        // 结果消息以暂停时 toolCallId 回填（LLM 会话上下文一致）
        verify(thinkingModel).stream(argThat(messages -> {
            if (messages.isEmpty()) {
                return false;
            }
            ChatMessage last = messages.get(messages.size() - 1);
            return last instanceof ToolExecutionResultMessage ter
                    && "call-http".equals(ter.id())
                    && "httpGet".equals(ter.toolName())
                    && "HTTP 200 OK".equals(ter.text());
        }), anyString(), any());
    }

    @Test
    void executeHitlResume_toolConfirm拒绝_应回填脱敏拒绝文案续跑() {
        // 业务含义：拒绝后回填固定脱敏拒绝文案（不含权限配置细节，AC-S04），不执行工具，
        // LLM 据此调整方案，工作流不终止（区别于 checkpoint 拒绝终止，决策 7）。
        doAnswer(invocation -> {
            ThinkingStreamHandler handler = invocation.getArgument(2);
            handler.onPartialResponse("换用其他方式");
            handler.onComplete("换用其他方式", "stop", null);
            return null;
        }).when(thinkingModel).stream(any(), anyString(), any(ThinkingStreamHandler.class));

        AgentExecutor executor = hitlExecutor();
        SseEmitter emitter = mock(SseEmitter.class);

        String output = executor.executeHitlResume(hitlAgentDef(), toolConfirmHitlStateWithMessages(),
                null, false, emitter, 1, "exec-1");

        assertEquals("换用其他方式", output);
        // 工具零执行
        verify(toolExecutor, never()).execute(anyString(), anyString());
        // 拒绝文案回填：含"用户拒绝"，不含权限配置细节
        verify(thinkingModel).stream(argThat(messages -> {
            if (messages.isEmpty()) {
                return false;
            }
            ChatMessage last = messages.get(messages.size() - 1);
            if (!(last instanceof ToolExecutionResultMessage ter)) {
                return false;
            }
            return "call-http".equals(ter.id())
                    && "httpGet".equals(ter.toolName())
                    && ter.text().contains("用户拒绝")
                    && !ter.text().contains("deny");
        }), anyString(), any());
    }

    @Test
    void executeHitlResume_toolConfirm_续跑再拦截_retryCount保持原值() {
        // 业务含义：toolConfirm 恢复后再次遇到 ask 级工具，新快照 retryCount 保持原值
        // （确认不累计追问次数，区别于 askUser 的 retryCount+1，Task-13 验证标准）。
        when(toolExecutor.execute("httpGet", "{\"url\":\"https://example.com\"}")).thenReturn("HTTP 200 OK");
        stubHttpToolCall(); // 续跑轮次再次触发 httpGet 拦截
        AgentExecutor executor = hitlExecutor();
        SseEmitter emitter = mock(SseEmitter.class);

        WorkflowHITLException ex = assertThrows(WorkflowHITLException.class,
                () -> executor.executeHitlResume(hitlAgentDef(), toolConfirmHitlStateWithMessages(),
                        null, true, emitter, 1, "exec-1"));

        assertEquals(WorkflowHITLState.MODE_TOOL_CONFIRM, ex.getHitlState().getHitlMode());
        // retryCount 原值传递（快照 retryCount=2，不因确认 +1）
        assertEquals(2, ex.getHitlState().getRetryCount());
    }
}
