package com.agentdemo.app.execution;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.agent.core.HumanInteractionManager;
import com.agentdemo.agent.prompt.PromptTemplateLoader;
import com.agentdemo.agent.single.SessionToolResolver;
import com.agentdemo.app.adapter.AgenticAgentFactory;
import com.agentdemo.app.core.AgentDefinition;
import com.agentdemo.app.service.TestTokenStream;
import com.agentdemo.app.service.WorkflowCancelledException;
import com.agentdemo.app.service.WorkflowHITLException;
import com.agentdemo.app.service.WorkflowPausedException;
import com.agentdemo.app.service.WorkflowTimeoutException;
import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.llm.registry.ModelFactory;
import com.agentdemo.llm.thinking.ThinkingStreamHandler;
import com.agentdemo.llm.thinking.ThinkingStreamingChatModel;
import com.agentdemo.llm.thinking.ToolCall;
import com.agentdemo.tools.registry.ToolExecutor;
import com.agentdemo.tools.registry.ToolRegistry;
import com.agentdemo.tools.registry.ToolSchemaConverter;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.service.TokenStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentExecutor 测试（P2 Task-04）
 * <p>
 * 业务含义：验证从 P1 WorkflowExecutionService 抽取的 Agent 执行逻辑
 * （构建 + 流式 + 重试 + 超时）与取消检查，行为与 P1 保持一致。
 * </p>
 */
class AgentExecutorTest {

    /**
     * 无 @HumanCheckpoint 的测试接口（Task-13 起模板 ResearchAgent 已标注检查点，
     * 本测试验证的是重试/超时/事件推送逻辑，须用普通接口隔离检查点拦截）
     */
    interface PlainResearchAgent {
        @Agent
        TokenStream execute(String input);
    }

    private AgenticAgentFactory agentFactory;
    private AgentExecutor executor;

    @BeforeEach
    void setUp() {
        agentFactory = mock(AgenticAgentFactory.class);
        executor = new AgentExecutor(agentFactory);
    }

    private AgentDefinition agentDef() {
        return AgentDefinition.builder()
                .name("研究 Agent")
                .description("d")
                .toolIds(List.of())
                .interfaceClass(PlainResearchAgent.class)
                .build();
    }

    /** 输出固定文本的 TokenStream */
    private TestTokenStream fixedStream(String fullText, String... tokens) {
        TestTokenStream stream = new TestTokenStream();
        for (String token : tokens) {
            stream.emitToken(token);
        }
        stream.completeWith(fullText);
        return stream;
    }

    @Test
    void executeWithRetry_shouldReturnFullOutput() throws Exception {
        PlainResearchAgent agent = mock(PlainResearchAgent.class);
        when(agent.execute(anyString())).thenReturn(fixedStream("完整结果"));
        when(agentFactory.buildAgent(any())).thenReturn(agent);

        SseEmitter emitter = mock(SseEmitter.class);
        String output = executor.executeWithRetry(agentDef(), "输入", emitter, 0, 0, null, 0, "exec-1");

        assertEquals("完整结果", output);
        // 无 token 片段时（仅 completeWith），不推送 token 事件
        verify(agent, times(1)).execute(anyString());
    }

    @Test
    void executeWithRetry_shouldPushTokenEventsDuringStreaming() throws Exception {
        PlainResearchAgent agent = mock(PlainResearchAgent.class);
        when(agent.execute(anyString())).thenReturn(fixedStream("完整文本", "片", "段", "一"));
        when(agentFactory.buildAgent(any())).thenReturn(agent);

        SseEmitter emitter = mock(SseEmitter.class);
        executor.executeWithRetry(agentDef(), "输入", emitter, 0, 0, null, 0, "exec-1");

        // 3 个 token 片段 → 推送 3 次 SSE 事件（每个 token 一次 send）
        verify(emitter, times(3)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void executeWithRetry_shouldPushStepRetryOnFirstFailureThenSucceed() throws Exception {
        PlainResearchAgent agent = mock(PlainResearchAgent.class);
        when(agent.execute(anyString()))
                .thenThrow(new RuntimeException("LLM 调用超时"))
                .thenReturn(fixedStream("成功结果"));
        when(agentFactory.buildAgent(any())).thenReturn(agent);

        SseEmitter emitter = mock(SseEmitter.class);
        String output = executor.executeWithRetry(agentDef(), "输入", emitter, 1, 3, null, 0, "exec-1");

        assertEquals("成功结果", output);
        // 首次失败 + 1 次重试 = execute 调用 2 次
        verify(agent, times(2)).execute(anyString());
        // 首次失败推送 1 次 step_retry 事件（无 token），成功后不再推送事件
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void executeWithRetry_shouldPushStepErrorWhenRetriesExhausted() throws Exception {
        PlainResearchAgent agent = mock(PlainResearchAgent.class);
        when(agent.execute(anyString())).thenThrow(new RuntimeException("持续失败"));
        when(agentFactory.buildAgent(any())).thenReturn(agent);

        SseEmitter emitter = mock(SseEmitter.class);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> executor.executeWithRetry(agentDef(), "输入", emitter, 1, 3, null, 0, "exec-1"));
        assertEquals(ErrorCode.WORKFLOW_EXECUTION_FAILED, ex.getErrorCode());

        // 首次 + 3 次重试 = execute 调用 4 次
        verify(agent, times(4)).execute(anyString());
        // 每次失败推送 1 次事件：3 次 step_retry + 最后 1 次 step_error = 4 次 send
        verify(emitter, times(4)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void executeWithRetry_shouldUseConfiguredMaxRetries() throws Exception {
        PlainResearchAgent agent = mock(PlainResearchAgent.class);
        when(agent.execute(anyString())).thenThrow(new RuntimeException("持续失败"));
        when(agentFactory.buildAgent(any())).thenReturn(agent);

        SseEmitter emitter = mock(SseEmitter.class);
        assertThrows(BusinessException.class,
                () -> executor.executeWithRetry(agentDef(), "输入", emitter, 0, 5, null, 0, "exec-1"));
        // maxRetries=5 时：首次 + 5 次重试 = 6 次
        verify(agent, times(6)).execute(anyString());
    }

    @Test
    void executeWithRetry_errorMessage_shouldIncludeRootCause() throws Exception {
        // 业务含义：底层失败原因（如"未配置 chat 模型"/"LLM 连接超时"）必须在最终错误中可见，便于定位
        PlainResearchAgent agent = mock(PlainResearchAgent.class);
        when(agent.execute(anyString())).thenThrow(new RuntimeException("LLM 连接超时"));
        when(agentFactory.buildAgent(any())).thenReturn(agent);

        SseEmitter emitter = mock(SseEmitter.class);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> executor.executeWithRetry(agentDef(), "输入", emitter, 0, 0, null, 0, "exec-1"));
        assertTrue(ex.getMessage().contains("LLM 连接超时"),
                "错误信息应包含底层根因，实际: " + ex.getMessage());
    }

    @Test
    void checkCancelled_trueFlag_shouldThrow() {
        AtomicBoolean flag = new AtomicBoolean(true);
        assertThrows(WorkflowCancelledException.class, () -> AgentExecutor.checkCancelled(flag));
    }

    @Test
    void checkCancelled_falseOrNullFlag_shouldNotThrow() {
        assertDoesNotThrow(() -> AgentExecutor.checkCancelled(new AtomicBoolean(false)));
        assertDoesNotThrow(() -> AgentExecutor.checkCancelled(null));
    }

    @Test
    void executeWithRetry_timeout_shouldThrowWorkflowTimeout() {
        // 注入超时 0 分钟，Agent 永不完成 → future.get(0) 立即超时
        AgentExecutor zeroTimeoutExecutor = new AgentExecutor(agentFactory, 0);
        PlainResearchAgent agent = mock(PlainResearchAgent.class);
        when(agent.execute(anyString())).thenReturn(new TestTokenStream()); // 永不 complete
        when(agentFactory.buildAgent(any())).thenReturn(agent);

        SseEmitter emitter = mock(SseEmitter.class);
        assertThrows(WorkflowTimeoutException.class,
                () -> zeroTimeoutExecutor.executeWithRetry(agentDef(), "输入", emitter, 0, 0, null, 0, "exec-1"));
    }

    // ===== P3 新增：重试耗尽抛 WorkflowPausedException（AC-016）=====

    @Test
    void executeWithRetry_exhausted_shouldThrowWorkflowPausedException() throws Exception {
        // 业务含义：重试耗尽的信号从"全盘失败"变为"暂停待恢复"（AC-016）
        PlainResearchAgent agent = mock(PlainResearchAgent.class);
        when(agent.execute(anyString())).thenThrow(new RuntimeException("持续失败"));
        when(agentFactory.buildAgent(any())).thenReturn(agent);

        SseEmitter emitter = mock(SseEmitter.class);
        WorkflowPausedException ex = assertThrows(WorkflowPausedException.class,
                () -> executor.executeWithRetry(agentDef(), "输入", emitter, 1, 1, null, 0, "exec-1"));

        assertEquals("研究 Agent", ex.getFailedAgentName());
        assertEquals(1, ex.getFailedIndex());
        // maxRetries=1：首次 + 1 次重试 = 2 次
        verify(agent, times(2)).execute(anyString());
    }

    @Test
    void executeWithRetry_pausedException_messageShouldContainRootCause() throws Exception {
        PlainResearchAgent agent = mock(PlainResearchAgent.class);
        when(agent.execute(anyString())).thenThrow(new RuntimeException("LLM 连接超时"));
        when(agentFactory.buildAgent(any())).thenReturn(agent);

        SseEmitter emitter = mock(SseEmitter.class);
        WorkflowPausedException ex = assertThrows(WorkflowPausedException.class,
                () -> executor.executeWithRetry(agentDef(), "输入", emitter, 2, 0, null, 0, "exec-1"));
        // 延续上轮 BUG 修复：错误信息携带根因便于定位
        assertTrue(ex.getMessage().contains("研究 Agent"), "应含 Agent 名，实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("LLM 连接超时"), "应含根因，实际: " + ex.getMessage());
    }

    @Test
    void executeWithRetry_exhausted_retryAndErrorEventsUnchanged() throws Exception {
        // 行为不变：重试期间推 step_retry，耗尽时推 step_error（AC-015 兼容）
        PlainResearchAgent agent = mock(PlainResearchAgent.class);
        when(agent.execute(anyString())).thenThrow(new RuntimeException("持续失败"));
        when(agentFactory.buildAgent(any())).thenReturn(agent);

        SseEmitter emitter = mock(SseEmitter.class);
        assertThrows(WorkflowPausedException.class,
                () -> executor.executeWithRetry(agentDef(), "输入", emitter, 1, 2, null, 0, "exec-1"));

        // maxRetries=2：2 次 step_retry + 1 次 step_error = 3 次 send（与 P2 行为一致）
        verify(emitter, times(3)).send(any(SseEmitter.SseEventBuilder.class));
    }

    // ===== Task-14 验证标准：工作流 HITL 路径三段提示词组合（role + app-xxx 场景 + hitl-guidance）=====

    @Test
    void hitlPath_systemPrompt_应三段组合且tools占位符无残留() {
        // 业务含义：工作流 HITL 路径恢复 app-xxx 任务场景段（AC-N03 任务场景不丢失），
        // 以 hitl-guidance 引导段承载工具描述与 askUser 使用规则——三段组合各司其职，避免统一
        // hitl.txt 丢失 Agent 专属任务描述（技术方案决策 5 方案 A）。
        AgenticAgentFactory agentFactory = mock(AgenticAgentFactory.class);
        ModelFactory modelFactory = mock(ModelFactory.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ToolSchemaConverter toolSchemaConverter = mock(ToolSchemaConverter.class);
        ToolExecutor toolExecutor = mock(ToolExecutor.class);
        PromptTemplateLoader promptTemplateLoader = mock(PromptTemplateLoader.class);
        HumanInteractionManager humanInteractionManager = mock(HumanInteractionManager.class);
        AgentConfig agentConfig = mock(AgentConfig.class);
        SessionToolResolver sessionToolResolver = mock(SessionToolResolver.class);
        ThinkingStreamingChatModel thinkingModel = mock(ThinkingStreamingChatModel.class);

        when(modelFactory.getDefaultThinkingStreamingChatModel()).thenReturn(thinkingModel);
        when(toolRegistry.resolveToolsForStreaming(any())).thenReturn(List.of());
        when(sessionToolResolver.ensureAskUserTool(any())).thenAnswer(inv -> inv.getArgument(0));
        when(toolSchemaConverter.convertToJson(any())).thenReturn("[]");
        when(promptTemplateLoader.composeSystemPrompt(eq("general"), eq("app-research")))
                .thenReturn("【角色段】你是一名专业的研究人员\n\n【任务场景段】你的任务是对给定主题进行深入研究");
        when(promptTemplateLoader.loadScenarioTemplate(eq(PromptTemplateLoader.SCENARIO_HITL_GUIDANCE)))
                .thenReturn("## 工具使用与人工交互引导\n### 可用工具\n{{tools}}");
        when(toolSchemaConverter.convertToDescriptionText(any()))
                .thenReturn("【工具描述】httpGet：发起 HTTP GET 请求");
        when(agentConfig.getThinkingMaxIterations()).thenReturn(8);
        // 触发 askUser 拦截（捕获 SystemMessage 断言组合结果）
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

        AgentExecutor executor = new AgentExecutor(agentFactory, 5, modelFactory, toolRegistry,
                toolSchemaConverter, toolExecutor, promptTemplateLoader, humanInteractionManager,
                agentConfig, sessionToolResolver);
        SseEmitter emitter = mock(SseEmitter.class);
        AgentDefinition def = AgentDefinition.builder()
                .name("研究 Agent")
                .description("d")
                .toolIds(List.of())
                .roleName("general")
                .scenarioName("app-research")
                .interfaceClass(PlainResearchAgent.class)
                .hitlEnabled(true)
                .build();

        assertThrows(WorkflowHITLException.class,
                () -> executor.executeWithRetry(def, "输入", emitter, 1, 0, null, 0, "exec-1"));

        // 捕获传给模型的首条 SystemMessage（即组合后的系统提示词）
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(thinkingModel).stream(captor.capture(), anyString(), any());
        String systemPrompt = ((SystemMessage) captor.getValue().get(0)).text();

        // 三段顺序正确：角色段 -> 任务场景段 -> 工具引导段（AC-N03 任务场景不丢失）
        int roleIdx = systemPrompt.indexOf("【角色段】");
        int sceneIdx = systemPrompt.indexOf("【任务场景段】");
        int guidIdx = systemPrompt.indexOf("## 工具使用与人工交互引导");
        assertTrue(roleIdx >= 0, "应包含角色段，实际: " + systemPrompt);
        assertTrue(sceneIdx > roleIdx, "任务场景段应在角色段之后");
        assertTrue(guidIdx > sceneIdx, "工具引导段应在任务场景段之后");
        // {{tools}} 替换为工具描述文本，无残留占位符
        assertTrue(systemPrompt.contains("【工具描述】httpGet：发起 HTTP GET 请求"), "工具描述应注入");
        assertFalse(systemPrompt.contains("{{tools}}"), "不应残留 {{tools}} 占位符");
        // composeSystemPrompt 使用 app-xxx 场景（任务场景段恢复，决策 5）
        verify(promptTemplateLoader).composeSystemPrompt("general", "app-research");
    }
}
