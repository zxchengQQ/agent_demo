package com.agentdemo.app.integration;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.agent.core.HumanInteractionManager;
import com.agentdemo.agent.prompt.PromptTemplateLoader;
import com.agentdemo.agent.single.SessionToolResolver;
import com.agentdemo.app.adapter.AgenticAgentFactory;
import com.agentdemo.app.core.AgentDefinition;
import com.agentdemo.app.core.WorkflowExecution;
import com.agentdemo.app.core.WorkflowExecutionStatus;
import com.agentdemo.app.core.WorkflowTemplate;
import com.agentdemo.app.execution.AgentExecutor;
import com.agentdemo.app.registry.WorkflowTemplateRegistry;
import com.agentdemo.app.service.TestTokenStream;
import com.agentdemo.app.service.WorkflowExecutionService;
import com.agentdemo.app.strategy.ConditionalExecutionStrategy;
import com.agentdemo.app.strategy.ParallelExecutionStrategy;
import com.agentdemo.app.strategy.SequentialExecutionStrategy;
import com.agentdemo.app.strategy.SupervisorExecutionStrategy;
import com.agentdemo.app.template.AnalysisAgent;
import com.agentdemo.app.template.MultiPerspectiveReviewTemplate;
import com.agentdemo.app.template.PerformanceReviewAgent;
import com.agentdemo.app.template.ResearchAgent;
import com.agentdemo.app.template.ResearchAnalyzeSummarizeTemplate;
import com.agentdemo.app.template.SecurityReviewAgent;
import com.agentdemo.app.template.SmartRoutingTemplate;
import com.agentdemo.app.template.StyleReviewAgent;
import com.agentdemo.app.template.SummaryAgent;
import com.agentdemo.app.template.SupervisorPlanAgent;
import com.agentdemo.app.template.SupervisorSummarizeAgent;
import com.agentdemo.app.template.TaskBreakdownSupervisorTemplate;
import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.llm.registry.ModelFactory;
import com.agentdemo.llm.thinking.ThinkingStreamHandler;
import com.agentdemo.llm.thinking.ThinkingStreamingChatModel;
import com.agentdemo.llm.thinking.ToolCall;
import com.agentdemo.tools.builtin.AskUserTool;
import com.agentdemo.tools.builtin.CalculatorTool;
import com.agentdemo.tools.builtin.HttpTool;
import com.agentdemo.tools.permission.ToolPermissionGuard;
import com.agentdemo.tools.permission.ToolPermissionLevel;
import com.agentdemo.tools.permission.ToolPermissionProperties;
import com.agentdemo.tools.permission.ToolPermissionService;
import com.agentdemo.tools.registry.ToolExecutor;
import com.agentdemo.tools.registry.ToolIdResolver;
import com.agentdemo.tools.registry.ToolRegistry;
import com.agentdemo.tools.registry.ToolSchemaConverter;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工作流 HITL（Human-in-the-Loop）端到端集成测试（Task-15 + Task-20 扩展）
 * <p>
 * 业务含义：在"真实模板注册 + 真实策略 + 真实工具权限装配 + mock AgentFactory/LLM 隔离"的
 * 完整链路上，验证 HITL 人机交互全流程：
 * 1. Task-15：@HumanCheckpoint 检查点确认/拒绝、Supervisor 模式 Worker 检查点、
 *    并行模式多 HITL 排队、WAITING_USER 用户主动终止（AC-N02/N03/N04、AC-S01、AC-E02、AC-H01）；
 * 2. Task-20：ask 级工具 tool_confirm 确认/拒绝闭环（AC-N03/AC-M02/AC-S04/AC-E03）——
 *    3 模板（ResearchAnalyzeSummarize / TaskBreakdownSupervisor / SmartRouting）执行至
 *    httpGet（默认 ASK）触发暂停 -> WAITING_USER + tool_confirm/workflow_waiting 双事件 ->
 *    hitlReply 批准/拒绝 -> 续跑断言（上下文完整、拒绝换方案、无超时卡死）。
 * </p>
 * <p>
 * 装配说明（Task-20 改造）：模板自 Task-16 起 hitlEnabled=true，hitlEnabled Agent 走
 * HITLReActStream（ThinkingStreamingChatModel），故改用全参数 AgentExecutor + 真实工具装配
 * （ToolPermissionService + ToolPermissionGuard + ToolRegistry，httpGet=ASK）+
 * mock ThinkingStreamingChatModel 按输入路由各 Agent 推理行为。
 * 技术说明：事件断言通过 RecordingEmitter 捕获（执行在 ForkJoinPool 异步线程，
 * MockedStatic 无法拦截跨线程静态调用，故重写 SseEmitter.send 记录事件名）。
 * </p>
 */
class WorkflowHITLIntegrationTest {

    /** 子任务清单 JSON（Supervisor 主控拆解输出） */
    private static final String PLAN_JSON =
            "[{\"id\":1,\"description\":\"调研分类算法\",\"agent\":\"研究\"},"
                    + "{\"id\":2,\"description\":\"对比分析主流方案\",\"agent\":\"分析\"}]";

    /** hitl-guidance 引导段（mock PromptTemplateLoader 返回，含 {{tools}} 占位，Task-14 三段组合） */
    private static final String HITL_GUIDANCE_TEXT =
            "## 工具使用与人工交互引导\n"
                    + "- 只能使用下方\"可用工具\"列表中的工具，不要编造或猜测工具名\n"
                    + "- 当用户指令缺少必要参数时，调用 askUser 工具进行追问\n"
                    + "- 当即将执行有副作用的操作前，必须调用 askUser(type=confirm) 确认\n"
                    + "### 可用工具\n"
                    + "{{tools}}";

    @TempDir
    Path tempDir;

    private WorkflowTemplateRegistry registry;
    private AgenticAgentFactory agentFactory;
    private WorkflowExecutionService service;

    /** HITL 依赖（全参数 AgentExecutor 注入，Task-20） */
    private ThinkingStreamingChatModel thinkingModel;
    private ToolExecutor toolExecutor;

    /** HITL 回复内容工厂：按等待次数生成用户回复（区分 checkpoint 批准/拒绝、tool_confirm 批准/拒绝） */
    interface HITLReplyFactory {
        HitlReply create(String executionId, int waitingCount);
    }

    /** HITL 回复（checkpoint/toolConfirm 模式传 approved） */
    record HitlReply(Boolean approved) {
    }

    @BeforeEach
    void setUp() {
        registry = new WorkflowTemplateRegistry();
        agentFactory = mock(AgenticAgentFactory.class);

        // ===== 真实工具装配（Task-19 模式）：ToolPermissionService + Guard + ToolRegistry，httpGet=ASK =====
        ToolPermissionProperties props = new ToolPermissionProperties();
        props.setFilePath(tempDir.resolve("perm.json").toString());
        ToolPermissionService permissionService = new ToolPermissionService(props);
        // mock ApplicationContext 避免 Spring 扫描，工具经 register 动态注册（隔离测试环境）
        ApplicationContext appCtx = mock(ApplicationContext.class);
        when(appCtx.getBeansWithAnnotation(org.springframework.stereotype.Component.class))
                .thenReturn(Collections.emptyMap());
        ToolIdResolver idResolver = new ToolIdResolver();
        ToolPermissionGuard guard = new ToolPermissionGuard(permissionService, idResolver);
        ToolRegistry toolRegistry = new ToolRegistry(appCtx, permissionService, idResolver, guard);
        // HttpTool @DefaultToolPermission(ASK)：httpGet/httpPost 默认 ask 级（工作流 tool_confirm 触发前提）
        toolRegistry.register(new HttpTool());
        toolRegistry.register(new CalculatorTool());
        toolRegistry.register(new AskUserTool());

        // 默认工具置空，避免干扰 HITL 工具集（SessionToolResolver 会合并默认工具）
        // AgentConfig 为真实对象（thinkingMaxIterations 默认 8，HITLReActStream 循环上限充足）
        AgentConfig agentConfig = new AgentConfig();
        agentConfig.getTools().setDefaultTools(Collections.emptyList());
        SessionToolResolver sessionToolResolver = new SessionToolResolver(toolRegistry, agentConfig);

        // ===== mock HITL 依赖（ThinkingStreamingChatModel 按输入路由各 Agent 行为） =====
        ModelFactory modelFactory = mock(ModelFactory.class);
        thinkingModel = mock(ThinkingStreamingChatModel.class);
        when(modelFactory.getDefaultThinkingStreamingChatModel()).thenReturn(thinkingModel);
        when(modelFactory.getThinkingStreamingChatModelByModelId(anyString())).thenReturn(thinkingModel);
        ToolSchemaConverter toolSchemaConverter = mock(ToolSchemaConverter.class);
        when(toolSchemaConverter.convertToJson(any())).thenReturn("[]");
        when(toolSchemaConverter.convertToDescriptionText(any())).thenReturn("可用工具描述");
        toolExecutor = mock(ToolExecutor.class);
        // 业务含义：httpGet 为 ask 级（默认 ASK）——HITLReActStream 执行工具前 checkPermission 裁决，
        // ASK 触发 tool_confirm 拦截暂停（决策 7/AC-N03）
        when(toolExecutor.checkPermission("httpGet"))
                .thenReturn(new ToolExecutor.ToolPermissionCheck(
                        ToolPermissionLevel.ASK, "builtin:httpGet", "发起 HTTP GET 请求"));
        PromptTemplateLoader promptTemplateLoader = mock(PromptTemplateLoader.class);
        when(promptTemplateLoader.composeSystemPrompt(anyString(), anyString())).thenReturn("HITL 系统提示词");
        // Task-14 三段组合：hitl-guidance 返回引导段（{{tools}} 运行时替换），验证三段顺序与占位替换
        when(promptTemplateLoader.loadScenarioTemplate(PromptTemplateLoader.SCENARIO_HITL_GUIDANCE))
                .thenReturn(HITL_GUIDANCE_TEXT);
        HumanInteractionManager humanInteractionManager = mock(HumanInteractionManager.class);

        // ===== 全参数 AgentExecutor（HITL 依赖齐全，Task-20） + 真实策略 + 4 模板 =====
        AgentExecutor agentExecutor = new AgentExecutor(agentFactory, 1, modelFactory, toolRegistry,
                toolSchemaConverter, toolExecutor, promptTemplateLoader, humanInteractionManager,
                agentConfig, sessionToolResolver);
        service = new WorkflowExecutionService(List.of(
                new SequentialExecutionStrategy(agentExecutor),
                new ParallelExecutionStrategy(agentExecutor),
                new SupervisorExecutionStrategy(agentExecutor),
                new ConditionalExecutionStrategy(agentExecutor)));

        // 注册预置模板（含 SmartRouting，Task-20 tool_confirm 三模板之一）
        new ResearchAnalyzeSummarizeTemplate(toolRegistry).researchAnalyzeSummarizeWorkflow(registry);
        new TaskBreakdownSupervisorTemplate(toolRegistry).taskBreakdownSupervisorWorkflow(registry);
        new MultiPerspectiveReviewTemplate(toolRegistry).multiPerspectiveReviewWorkflow(registry);
        new SmartRoutingTemplate(toolRegistry).smartRoutingWorkflow(registry);
    }

    // ==================== 辅助方法 ====================

    /** 记录事件名的 SseEmitter（重写 send 拦截 SseEventBuilder，线程安全） */
    static class RecordingEmitter extends SseEmitter {
        final List<String> eventNames = new CopyOnWriteArrayList<>();

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            for (ResponseBodyEmitter.DataWithMediaType entry : builder.build()) {
                if (entry.getData() instanceof String text) {
                    java.util.regex.Matcher matcher = EVENT_NAME_PATTERN.matcher(text);
                    if (matcher.find()) {
                        eventNames.add(matcher.group(1));
                    }
                }
            }
        }

        int count(String eventName) {
            return (int) eventNames.stream().filter(e -> e.equals(eventName)).count();
        }
    }

    /** 从 SSE 文本 entry 提取事件名的正则（event:NAME，NAME 为非空白连续段） */
    private static final java.util.regex.Pattern EVENT_NAME_PATTERN =
            java.util.regex.Pattern.compile("event:(\\S+)");

    /** 断言辅助：事件 a 的序号严格小于事件 b（首个出现位置） */
    private static void assertOrder(RecordingEmitter emitter, String before, String after) {
        int idxBefore = emitter.eventNames.indexOf(before);
        int idxAfter = emitter.eventNames.indexOf(after);
        assertTrue(idxBefore >= 0, "缺少事件: " + before + "，实际序列: " + emitter.eventNames);
        assertTrue(idxAfter >= 0, "缺少事件: " + after + "，实际序列: " + emitter.eventNames);
        assertTrue(idxBefore < idxAfter, "事件顺序错误: " + before + " 应在 " + after + " 之前，实际序列: " + emitter.eventNames);
    }

    /** 构造立即完成的 TokenStream（非 HITL Agent 用，hitlEnabled=false 路径） */
    private static TestTokenStream streamOf(String text) {
        TestTokenStream stream = new TestTokenStream();
        stream.completeWith(text);
        return stream;
    }

    /** 按 Agent 名分发 mock（仅非 HITL Agent 的 TokenStream 路径需要，如 MultiPerspectiveReview） */
    private void stubBuildAgentByAgentName(Object... nameAgentPairs) {
        when(agentFactory.buildAgent(any())).thenAnswer(inv -> {
            AgentDefinition def = inv.getArgument(0);
            for (int i = 0; i < nameAgentPairs.length; i += 2) {
                if (nameAgentPairs[i].equals(def.getName())) {
                    return nameAgentPairs[i + 1];
                }
            }
            throw new IllegalStateException("未 mock 的 Agent: " + def.getName());
        });
    }

    /**
     * 按 HITL Agent 输入路由思考流模型（Task-20）。
     * <p>
     * 业务含义：hitlEnabled Agent 均走 HITLReActStream（ThinkingStreamingChatModel），
     * 本方法按消息列表第 2 条 UserMessage（Agent 输入）路由响应：
     * 1. toolTriggerContains 命中的输入且本轮尚未含 httpGet 工具结果（首轮）→ 触发 httpGet
     *    工具调用（tool_confirm 暂停，ask 级拦截语义）；
     * 2. 已含 httpGet 工具结果（批准/拒绝后续跑轮）→ 返回 finalAfterTool（LLM 收尾回答）；
     * 3. 其余输入按 contains 匹配 textContainsByInput 返回文本（研究/分析/总结各步骤正常输出）。
     * </p>
     */
    private void stubThinkingModelByInput(Map<String, String> textContainsByInput,
                                          String toolTriggerContains, String finalAfterTool) {
        doAnswer(invocation -> {
            List<ChatMessage> messages = invocation.getArgument(0);
            String input = ((UserMessage) messages.get(1)).singleText();
            ThinkingStreamHandler handler = invocation.getArgument(2);
            boolean hasToolResult = messages.stream()
                    .anyMatch(m -> m instanceof ToolExecutionResultMessage ter
                            && "httpGet".equals(ter.toolName()));
            if (toolTriggerContains != null && input.contains(toolTriggerContains) && !hasToolResult) {
                // 首轮：LLM 决定调用 httpGet（ask 级）→ HITLReActStream 拦截暂停（tool_confirm）
                ToolCall tc = new ToolCall();
                tc.setId("call-http");
                tc.setFunctionName("httpGet");
                tc.setArguments("{\"url\":\"https://example.com\"}");
                handler.onToolCalls(List.of(tc));
                handler.onComplete("", "tool_calls", null);
                return null;
            }
            if (hasToolResult) {
                // 续跑轮：工具结果/拒绝文案已回填，LLM 收尾给出最终回答
                handler.onPartialResponse(finalAfterTool);
                handler.onComplete(finalAfterTool, "stop", null);
                return null;
            }
            String text = null;
            for (Map.Entry<String, String> entry : textContainsByInput.entrySet()) {
                if (input.contains(entry.getKey())) {
                    text = entry.getValue();
                    break;
                }
            }
            if (text == null) {
                text = "回答";
            }
            handler.onPartialResponse(text);
            handler.onComplete(text, "stop", null);
            return null;
        }).when(thinkingModel).stream(any(), anyString(), any(ThinkingStreamHandler.class));
    }

    /**
     * 等待异步执行到达终态；每次 WAITING_USER 时按等待次数调用回复工厂（HITL 排队按序处理）。
     *
     * @param executionId  执行 ID
     * @param emitter      HITL 回复复用的事件发射器（记录事件；null 用 mock）
     * @param replyFactory 回复工厂（每次 WAITING_USER 调用一次，返回确认结果）
     * @return 最终执行实例
     */
    private WorkflowExecution awaitHITL(String executionId, SseEmitter emitter, HITLReplyFactory replyFactory) {
        long deadline = System.currentTimeMillis() + 10000;
        WorkflowExecution execution = null;
        int waitingCount = 0;
        while (System.currentTimeMillis() < deadline) {
            execution = service.getExecution(executionId);
            if (execution.getStatus() == WorkflowExecutionStatus.WAITING_USER) {
                HitlReply reply = replyFactory.create(executionId, waitingCount++);
                service.hitlReply(executionId, null, reply.approved(),
                        emitter != null ? emitter : mock(SseEmitter.class));
                continue;
            }
            if (execution.getStatus() != WorkflowExecutionStatus.RUNNING
                    && execution.getStatus() != WorkflowExecutionStatus.PENDING) {
                return execution;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertNotNull(execution, "执行应在超时内到达稳定状态");
        assertNotEquals(WorkflowExecutionStatus.RUNNING, execution.getStatus(), "执行不应仍处于 RUNNING");
        return execution;
    }

    /** 等待异步执行状态到达目标状态 */
    private void waitForStatus(String executionId, WorkflowExecutionStatus target) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            WorkflowExecution execution = service.getExecution(executionId);
            if (execution != null && execution.getStatus() == target) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        fail("状态未变为 " + target + ": 实际="
                + (service.getExecution(executionId) != null ? service.getExecution(executionId).getStatus() : "null"));
    }

    // ==================== 测试用例 ====================

    // ===== 测试 1：AC-N03 -- 检查点批准后恢复完成，上下文保持（Task-20 适配 HITL 路径） =====

    @Test
    void checkpointApproved_应恢复完成且上下文保持() {
        // hitlEnabled Agent 走 HITLReActStream：研究（checkpoint 批准后 HITL）→ 分析 → 总结
        stubThinkingModelByInput(
                Map.of("AI", "研究资料", "研究资料", "分析洞察", "分析洞察", "最终报告"),
                null, null);

        WorkflowTemplate template = registry.getTemplate(ResearchAnalyzeSummarizeTemplate.TEMPLATE_ID);
        RecordingEmitter emitter = new RecordingEmitter();
        String executionId = service.execute(template, Map.of("topic", "AI"), emitter, null);

        // 研究 Agent 检查点暂停 -> 用户批准继续（AC-N03）
        WorkflowExecution execution = awaitHITL(executionId, emitter,
                (id, count) -> new HitlReply(true));

        assertEquals(WorkflowExecutionStatus.COMPLETED, execution.getStatus());
        assertEquals("最终报告", execution.getFinalResult());
        // AC-N03：恢复重放后共享上下文（lastOutput 链式传递）保持完整
        assertEquals("最终报告", execution.getContext().readAsString("lastOutput"),
                "恢复后共享上下文 lastOutput 应保留完整");

        // 事件序列：workflow_waiting（检查点） -> workflow_resumed -> workflow_complete
        assertTrue(emitter.eventNames.contains("workflow_waiting"), "应推送 workflow_waiting 事件");
        assertTrue(emitter.eventNames.contains("workflow_resumed"), "应推送 workflow_resumed 事件");
        assertOrder(emitter, "workflow_waiting", "workflow_resumed");
        assertOrder(emitter, "workflow_resumed", "workflow_complete");

        // HITL 路径：研究（checkpoint 批准后）/分析/总结各 1 次模型调用
        verify(thinkingModel, times(3)).stream(any(), anyString(), any());
    }

    // ===== 测试 2：AC-S01 -- 检查点拒绝后工作流 TERMINATED，不执行后续步骤 =====

    @Test
    void checkpointDenied_应终止且不执行后续步骤() {
        // 研究 Agent checkpoint 拒绝：不进入 HITLReActStream，thinkingModel 零调用
        WorkflowTemplate template = registry.getTemplate(ResearchAnalyzeSummarizeTemplate.TEMPLATE_ID);
        RecordingEmitter emitter = new RecordingEmitter();
        String executionId = service.execute(template, Map.of("topic", "AI"), emitter, null);

        // 研究 Agent 检查点第一次暂停即拒绝（AC-S01）
        WorkflowExecution execution = awaitHITL(executionId, emitter,
                (id, count) -> new HitlReply(false));

        assertEquals(WorkflowExecutionStatus.TERMINATED, execution.getStatus());
        // 拒绝后不执行任何后续步骤（研究检查点未进入方法体，分析/总结不执行）
        verify(thinkingModel, never()).stream(any(), anyString(), any());
        // 推送 workflow_failed(TERMINATED)
        assertTrue(emitter.eventNames.contains("workflow_failed"), "应推送 workflow_failed，实际: " + emitter.eventNames);
    }

    // ===== 测试 3：AC-N04 -- Supervisor 模式 Worker Agent 检查点拒绝后终止（Task-20 适配 HITL 路径） =====

    @Test
    void supervisorWorkerCheckpointDenied_应终止且后续Worker不执行() {
        // 拆解主控（hitlEnabled=true）走 HITLReActStream 返回 PLAN_JSON；研究 Worker checkpoint 拒绝终止
        stubThinkingModelByInput(
                Map.of("写一份分类算法调研报告", PLAN_JSON),
                null, null);

        WorkflowTemplate template = registry.getTemplate(TaskBreakdownSupervisorTemplate.TEMPLATE_ID);
        RecordingEmitter emitter = new RecordingEmitter();
        String executionId = service.execute(template, Map.of("task", "写一份分类算法调研报告"), emitter, null);

        // 研究 Worker 检查点暂停 -> 用户拒绝（AC-N04）
        WorkflowExecution execution = awaitHITL(executionId, emitter,
                (id, count) -> new HitlReply(false));

        assertEquals(WorkflowExecutionStatus.TERMINATED, execution.getStatus());
        // 拆解主控已执行（HITL 1 次返回 PLAN_JSON），研究 Worker 未进入方法体（checkpoint 拦截），分析/汇总不执行
        verify(thinkingModel, times(1)).stream(any(), anyString(), any());
        assertTrue(emitter.eventNames.contains("workflow_failed"), "应推送 workflow_failed，实际: " + emitter.eventNames);
    }

    // ===== 测试 4：AC-E02 -- 并行模式多 Agent 同时 HITL：第一个生效其余排队，回复后按序处理 =====

    @Test
    void parallelMultipleHitl_应排队按序处理并完成() {
        // 安全/性能/风格审查 Agent 未启用 hitl（hitlEnabled=false），走 TokenStream（mock execute 返回流）
        SecurityReviewAgent security = mock(SecurityReviewAgent.class);
        when(security.execute(any())).thenAnswer(inv -> streamOf("安全报告"));
        PerformanceReviewAgent performance = mock(PerformanceReviewAgent.class);
        when(performance.execute(any())).thenAnswer(inv -> streamOf("性能报告"));
        StyleReviewAgent style = mock(StyleReviewAgent.class);
        when(style.execute(any())).thenAnswer(inv -> streamOf("风格报告"));
        stubBuildAgentByAgentName(
                "安全审查 Agent", security,
                "性能审查 Agent", performance,
                "风格审查 Agent", style);

        WorkflowTemplate template = registry.getTemplate(MultiPerspectiveReviewTemplate.TEMPLATE_ID);
        RecordingEmitter emitter = new RecordingEmitter();
        String executionId = service.execute(template, Map.of("content", "方案"), emitter, null);

        // 两次 WAITING_USER：第一个检查点生效（安全），回复后消费排队（性能）再次等待，再回复后恢复执行
        WorkflowExecution execution = awaitHITL(executionId, emitter,
                (id, count) -> new HitlReply(true));

        assertEquals(WorkflowExecutionStatus.COMPLETED, execution.getStatus());
        // 排队语义（AC-E02）：两次 workflow_waiting（第一次生效 + 排队按序消费），最终 workflow_resumed
        assertEquals(2, emitter.count("workflow_waiting"), "并行多 HITL 应产生两次等待，实际: " + emitter.eventNames);
        assertTrue(emitter.eventNames.contains("workflow_resumed"), "应推送 workflow_resumed 事件");
        // 汇总含三组输出
        assertTrue(execution.getFinalResult().contains("安全报告"), "汇总应含安全报告");
        assertTrue(execution.getFinalResult().contains("性能报告"), "汇总应含性能报告");
        assertTrue(execution.getFinalResult().contains("风格报告"), "汇总应含风格报告");
        // 安全/性能各 1 次（检查点批准后执行）；风格 1 次（首轮完成）
        verify(security, times(1)).execute(any());
        verify(performance, times(1)).execute(any());
        verify(style, times(1)).execute(any());
    }

    // ===== 测试 5：AC-H01 -- WAITING_USER 状态用户主动终止：状态 TERMINATED + 快照清理 =====

    @Test
    void waitingUser_terminate_应终止并清理快照() {
        WorkflowTemplate template = registry.getTemplate(ResearchAnalyzeSummarizeTemplate.TEMPLATE_ID);
        String executionId = service.execute(template, Map.of("topic", "AI"), mock(SseEmitter.class), null);

        // 研究 Agent 检查点暂停进入 WAITING_USER -> 用户点击终止（AC-H01）
        waitForStatus(executionId, WorkflowExecutionStatus.WAITING_USER);
        service.terminate(executionId);

        WorkflowExecution execution = service.getExecution(executionId);
        assertEquals(WorkflowExecutionStatus.TERMINATED, execution.getStatus());
        // 快照清理：终止（终态）后 hitlReply 抛 5507（不可再回复）
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.hitlReply(executionId, null, true, mock(SseEmitter.class)));
        assertEquals(ErrorCode.WORKFLOW_NOT_RESUMABLE, ex.getErrorCode());
        // 终止发生在检查点执行前，研究 Agent 未进入方法体
        verify(thinkingModel, never()).stream(any(), anyString(), any());
    }

    // ===== 测试 6：AC-H01/H02/AC-M02 -- tool_confirm 批准闭环：批准后执行工具、结果回填、续跑完成 =====

    @Test
    void toolConfirmApproved_应执行工具并续跑完成且上下文保持() {
        // 业务含义：研究 Agent checkpoint 批准后走 HITLReActStream，首轮触发 httpGet（ask 级）
        // -> tool_confirm 暂停（第二次 WAITING_USER）-> 批准 -> 工具结果回填续跑 -> 分析/总结完成（AC-N03）
        when(toolExecutor.execute("httpGet", "{\"url\":\"https://example.com\"}")).thenReturn("HTTP 200 OK");
        stubThinkingModelByInput(
                Map.of("AI", "研究资料", "研究资料", "分析洞察", "分析洞察", "最终报告"),
                "AI", "研究资料");

        WorkflowTemplate template = registry.getTemplate(ResearchAnalyzeSummarizeTemplate.TEMPLATE_ID);
        RecordingEmitter emitter = new RecordingEmitter();
        String executionId = service.execute(template, Map.of("topic", "AI"), emitter, null);

        // 两次 WAITING_USER：第一次 checkpoint 批准 -> 研究 Agent 进入 HITL 触发 httpGet；
        // 第二次 tool_confirm 批准 -> 执行工具续跑
        WorkflowExecution execution = awaitHITL(executionId, emitter,
                (id, count) -> new HitlReply(true));

        assertEquals(WorkflowExecutionStatus.COMPLETED, execution.getStatus());
        assertEquals("最终报告", execution.getFinalResult());
        // AC-N03：tool_confirm 恢复后共享上下文 lastOutput 链式保持完整
        assertEquals("最终报告", execution.getContext().readAsString("lastOutput"),
                "恢复后共享上下文 lastOutput 应保留完整");

        // 双暂停：checkpoint + tool_confirm 各一次 workflow_waiting；tool_confirm 事件存在
        assertEquals(2, emitter.count("workflow_waiting"), "应产生 checkpoint + tool_confirm 两次等待，实际: " + emitter.eventNames);
        assertEquals(1, emitter.count("tool_confirm"), "应推送一次 tool_confirm，实际: " + emitter.eventNames);
        // 事件顺序：checkpoint 暂停卡（ask_user -> workflow_waiting）先于 tool_confirm 暂停卡
        // （tool_confirm -> 第二次 workflow_waiting -> 第二次 workflow_resumed -> workflow_complete）
        // 注：workflow_resumed/workflow_waiting 各出现两次（checkpoint + tool_confirm），
        // 用 lastIndexOf 定位 tool_confirm 暂停卡的后续事件
        assertOrder(emitter, "ask_user", "workflow_waiting");
        int idxToolConfirm = emitter.eventNames.indexOf("tool_confirm");
        int idxWaiting2 = emitter.eventNames.lastIndexOf("workflow_waiting");
        int idxResumed2 = emitter.eventNames.lastIndexOf("workflow_resumed");
        int idxComplete = emitter.eventNames.indexOf("workflow_complete");
        assertTrue(idxToolConfirm >= 0 && idxToolConfirm < idxWaiting2
                        && idxWaiting2 < idxResumed2 && idxResumed2 < idxComplete,
                "tool_confirm 暂停卡事件顺序错误，实际序列: " + emitter.eventNames);

        // 批准后工具被执行一次，参数为暂停时原样（AC-H01）
        verify(toolExecutor).execute("httpGet", "{\"url\":\"https://example.com\"}");
        // 模型调用：研究 2 次（触发工具 + 续跑收尾）+ 分析 1 + 总结 1 = 4 次
        verify(thinkingModel, times(4)).stream(any(), anyString(), any());
    }

    // ===== 测试 7：AC-S04/决策 7 -- tool_confirm 拒绝闭环：拒绝文案回填、LLM 换方案、不终止工作流 =====

    @Test
    void toolConfirmDenied_应回填拒绝文案换方案且不终止() {
        // 业务含义：研究 Worker checkpoint 批准后 HITL 触发 httpGet -> tool_confirm 暂停 ->
        // 拒绝 -> 回填脱敏拒绝文案（不含权限配置细节）续跑 -> LLM 换方案 -> 后续子任务与汇总完成，
        // 工作流不终止（区别于 checkpoint 拒绝终止，决策 7/AC-S04）。
        // 业务含义：文本路由 Map 用 LinkedHashMap 保证"## 子任务"（综合汇总输入特征）优先于
        // "对比分析主流方案"（该串同时出现在综合汇总的"## 子任务 2（对比分析主流方案）"中，
        // Map.of 迭代顺序未定义会导致误匹配为"分析输出"）
        Map<String, String> textByInput = new LinkedHashMap<>();
        textByInput.put("## 子任务", "综合报告");
        textByInput.put("对比分析主流方案", "分析输出");
        textByInput.put("写一份分类算法调研报告", PLAN_JSON);
        stubThinkingModelByInput(textByInput,
                // 业务含义：仅研究 Worker 的 HITL 输入含"你负责的子任务：\n调研分类算法"
                // （检查点批准后的 ReAct 输入）；分析 Worker 为"对比分析主流方案"、综合汇总
                // 为子任务结果拼接（"## 子任务 1（调研分类算法）"）均不含该完整特征串，
                // 据此精确定位研究 Worker 触发 httpGet，避免误伤其他 HITL Agent
                "你负责的子任务：\n调研分类算法", "换用其他方式（不调用HTTP）");

        WorkflowTemplate template = registry.getTemplate(TaskBreakdownSupervisorTemplate.TEMPLATE_ID);
        RecordingEmitter emitter = new RecordingEmitter();
        String executionId = service.execute(template, Map.of("task", "写一份分类算法调研报告"), emitter, null);

        // 第一次 WAITING_USER（研究 Worker checkpoint）批准 -> 进入 HITL 触发 httpGet；
        // 第二次 WAITING_USER（tool_confirm）拒绝 -> 换方案续跑
        WorkflowExecution execution = awaitHITL(executionId, emitter,
                (id, count) -> new HitlReply(count == 0));

        // 拒绝不终止：工作流 COMPLETED（决策 7，区别于 checkpoint 拒绝终止）
        assertEquals(WorkflowExecutionStatus.COMPLETED, execution.getStatus());
        assertEquals("综合报告", execution.getFinalResult());

        // tool_confirm 双事件 + 拒绝后恢复事件
        assertEquals(1, emitter.count("tool_confirm"), "应推送一次 tool_confirm，实际: " + emitter.eventNames);
        assertTrue(emitter.eventNames.contains("workflow_resumed"), "拒绝后应推送 workflow_resumed（不终止）");
        assertTrue(emitter.eventNames.contains("workflow_complete"), "应推送 workflow_complete，实际: " + emitter.eventNames);

        // 拒绝后工具零执行（AC-H01）
        verify(toolExecutor, never()).execute(anyString(), anyString());
        // 拒绝文案回填到续跑消息：含"用户拒绝"语义、不含权限配置细节（AC-S04）
        verify(thinkingModel).stream(argThat(messages -> messages.stream()
                .anyMatch(m -> m instanceof ToolExecutionResultMessage ter
                        && "httpGet".equals(ter.toolName())
                        && ter.text().contains("用户拒绝")
                        && !ter.text().contains("deny"))), anyString(), any());
    }

    // ===== 测试 8：AC-N02/AC-M02 -- SmartRouting 复杂分支 tool_confirm 批准 + hitl-guidance 三段组合观察 =====

    @Test
    void smartRoutingToolConfirmApproved_应完成且系统提示词含三段组合() {
        // 业务含义：SmartRouting（条件分支）复杂问题走研究-分析-总结分支，研究 Agent checkpoint
        // 批准后 HITL 触发 httpGet -> tool_confirm 批准续跑；同时观察 hitl-guidance 三段组合
        // （role 段 + app 场景段 + hitl-guidance 工具引导段，{{tools}} 运行时替换，Task-14）。
        String question = "请帮我调研一下AI分类算法的现状和主流方案";
        when(toolExecutor.execute("httpGet", "{\"url\":\"https://example.com\"}")).thenReturn("HTTP 200 OK");
        stubThinkingModelByInput(
                Map.of(question, "研究资料", "研究资料", "分析洞察", "分析洞察", "最终报告"),
                question, "研究资料");

        WorkflowTemplate template = registry.getTemplate(SmartRoutingTemplate.TEMPLATE_ID);
        RecordingEmitter emitter = new RecordingEmitter();
        String executionId = service.execute(template, Map.of("question", question), emitter, null);

        WorkflowExecution execution = awaitHITL(executionId, emitter,
                (id, count) -> new HitlReply(true));

        assertEquals(WorkflowExecutionStatus.COMPLETED, execution.getStatus());
        assertEquals("最终报告", execution.getFinalResult());
        assertEquals(1, emitter.count("tool_confirm"), "应推送一次 tool_confirm，实际: " + emitter.eventNames);

        // 三段组合生效：系统提示词含 role 段（mock）+ hitl-guidance 引导段，且 {{tools}} 已替换无残留
        // （研究/分析/总结各 HITL 轮均命中，至少一次即证明三段组合在 HITL 路径生效）
        verify(thinkingModel, atLeastOnce()).stream(argThat(messages -> {
            if (messages.isEmpty()) {
                return false;
            }
            ChatMessage first = messages.get(0);
            return first instanceof SystemMessage sm
                    && sm.text().contains("HITL 系统提示词")
                    && sm.text().contains("工具使用与人工交互引导")
                    && !sm.text().contains("{{tools}}");
        }), anyString(), any());
    }
}
