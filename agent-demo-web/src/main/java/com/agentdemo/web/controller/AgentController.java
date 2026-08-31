package com.agentdemo.web.controller;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.agent.core.HumanInteractionManager;
import com.agentdemo.agent.core.PlanCommandParser;
import com.agentdemo.agent.core.SubTask;
import com.agentdemo.agent.core.UnifiedChatStream;
import com.agentdemo.agent.single.PlanAgent;
import com.agentdemo.agent.single.SimpleAgent;
import com.agentdemo.common.dto.ToolInfo;
import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.common.result.Result;
import com.agentdemo.common.utils.SimpleTokenEstimator;
import com.agentdemo.memory.session.SessionManager;
import com.agentdemo.memory.shortterm.ChatMemoryManager;
import com.agentdemo.observability.TraceCollector;
import com.agentdemo.observability.TraceContextHolder;
import com.agentdemo.skill.session.SkillSessionManager;
import com.agentdemo.tools.permission.ToolPermissionLevel;
import com.agentdemo.tools.permission.ToolPermissionService;
import com.agentdemo.tools.registry.ToolRegistry;
import com.agentdemo.web.dto.ChatRequest;
import com.agentdemo.web.dto.ChatResponse;
import com.agentdemo.web.dto.UpdateToolPermissionRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Agent 对话接口
 * <p>
 * 业务含义：提供 Agent 对话的 REST API，支持同步对话、流式对话、会话管理。
 * 接口路径规范：统一 /api/agent/* 前缀
 * </p>
 * <p>
 * unified-chat-mode：chatStream 重构为统一路由（四模式融合）——恢复优先 -> /plan 解析 ->
 * 规划判断路由，所有路径收敛为 UnifiedChatStream 编排 + 单次统一回调注册（技术方案 1.6.2）。
 * </p>
 */
@Tag(name = "Agent 对话", description = "Agent 对话与会话管理接口")
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    /**
     * Agent 实例（CR-001 调整：由 BaseAgent 接口改为 SimpleAgent 具体类型）
     * 业务含义：SimpleAgent 实现 BaseAgent 接口，同时提供 chat/chatStream（原路径）和
     * chatThinkingStream（CR-001 思考路径）。注入具体类型避免 BaseAgent 类型多候选注入歧义。
     */
    private final SimpleAgent simpleAgent;
    private final PlanAgent planAgent;
    private final SessionManager sessionManager;
    private final ChatMemoryManager memoryManager;
    private final ToolRegistry toolRegistry;
    private final AgentConfig agentConfig;
    private final HumanInteractionManager humanInteractionManager;
    private final ToolPermissionService toolPermissionService;
    private final SkillSessionManager skillSessionManager;
    private final TraceCollector traceCollector;
    /** 技能附件文本源（agent-context-engineering：排除状态附件；null 兼容场景跳过） */
    private final com.agentdemo.skill.prompt.SkillPromptComposer skillPromptComposer;

    @org.springframework.beans.factory.annotation.Autowired
    public AgentController(SimpleAgent simpleAgent, PlanAgent planAgent,
                           SessionManager sessionManager, ChatMemoryManager memoryManager,
                           ToolRegistry toolRegistry, AgentConfig agentConfig,
                           HumanInteractionManager humanInteractionManager,
                           ToolPermissionService toolPermissionService,
                           SkillSessionManager skillSessionManager,
                           TraceCollector traceCollector,
                           com.agentdemo.skill.prompt.SkillPromptComposer skillPromptComposer) {
        this.simpleAgent = simpleAgent;
        this.planAgent = planAgent;
        this.sessionManager = sessionManager;
        this.memoryManager = memoryManager;
        this.toolRegistry = toolRegistry;
        this.agentConfig = agentConfig;
        this.humanInteractionManager = humanInteractionManager;
        this.toolPermissionService = toolPermissionService;
        this.skillSessionManager = skillSessionManager;
        this.traceCollector = traceCollector;
        this.skillPromptComposer = skillPromptComposer;
    }

    /**
     * 兼容构造（未装配技能附件文本源：状态附件跳过，其余行为不变）
     */
    public AgentController(SimpleAgent simpleAgent, PlanAgent planAgent,
                           SessionManager sessionManager, ChatMemoryManager memoryManager,
                           ToolRegistry toolRegistry, AgentConfig agentConfig,
                           HumanInteractionManager humanInteractionManager,
                           ToolPermissionService toolPermissionService,
                           SkillSessionManager skillSessionManager,
                           TraceCollector traceCollector) {
        this(simpleAgent, planAgent, sessionManager, memoryManager, toolRegistry, agentConfig,
                humanInteractionManager, toolPermissionService, skillSessionManager, traceCollector, null);
    }

    /**
     * 同步对话
     * 业务含义：接收用户消息，调用 Agent 获取回复，返回会话 ID 供多轮对话使用
     *
     * @param request 对话请求
     * @return 对话响应
     */
    @Operation(summary = "同步对话", description = "发送消息给 Agent，获取同步回复")
    @PostMapping("/chat")
    public Result<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        // 会话管理：sessionId 为空则新建
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = sessionManager.createSession();
        } else if (!sessionManager.exists(sessionId)) {
            // 业务含义：传入的 sessionId 不存在时新建，避免前端传入无效 ID 导致错误
            sessionId = sessionManager.createSession();
        }

        // 记录用户消息到记忆
        memoryManager.addUserMessage(sessionId, request.getMessage());

        // 业务含义：读取前端指定的 modelId，null 时使用默认模型
        String modelId = request.getModel();

        // 业务含义：解析工具列表（默认 ∪ 指定），工具按需加载
        List<String> toolIds = request.getTools();
        if (toolIds != null && !toolIds.isEmpty()) {
            // 校验工具标识，格式错误/不存在抛 BusinessException（由 GlobalExceptionHandler 统一处理）
            // 同步路径无暂停能力，按 ForDirect 能力声明校验（AC-T01 唯一解析入口）
            toolRegistry.resolveToolsForDirect(toolIds);
        }

        // 调用 Agent（ReAct 循环由 LangChain4j 自动处理）
        long start = System.currentTimeMillis();
        String response = simpleAgent.chat(sessionId, request.getMessage(), modelId, toolIds);
        long duration = System.currentTimeMillis() - start;

        // 记录助手回复到记忆
        memoryManager.addAssistantMessage(sessionId, response);

        ChatResponse chatResponse = new ChatResponse(
                sessionId, response, null, duration, null, null);
        return Result.success(chatResponse);
    }

    /**
     * 获取可用工具列表
     * <p>
     * 业务含义：返回所有已注册工具的信息，供前端工具选择器和设置页面展示。
     * 每项包含 id（category:name）、category、name、description、isDefault、permission。
     * </p>
     *
     * @return 工具信息列表 + 默认工具 ID 列表
     */
    @Operation(summary = "获取可用工具列表", description = "返回所有已注册工具的信息，按类别分组")
    @GetMapping("/tools")
    public Result<Map<String, Object>> getAvailableTools() {
        List<String> defaultIds = agentConfig.getTools().getDefaultTools();
        List<ToolInfo> tools = toolRegistry.getAvailableTools(defaultIds);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tools", tools);
        result.put("defaults", defaultIds);
        return Result.success(result);
    }

    /**
     * 更新工具权限等级
     * <p>
     * 业务含义：管理页调整工具权限（allow/ask/deny）的落点（AC-N01）。校验通过后
     * 写入 ToolPermissionService 显式配置并持久化 JSON 文件（重启不丢），立即生效。
     * </p>
     * <p>
     * 校验规则：
     * 1. askUser 工具豁免（AC-S03 防确认死锁）——权限固定放行，禁止修改，返回 400；
     * 2. 工具不存在/标识非法——抛 TOOL_NOT_FOUND（由全局异常处理器统一返回）；
     * 3. 权限值非法——解析失败转 PARAM_INVALID（400），避免落入 500 兜底。
     * </p>
     *
     * @param toolId  工具标识（category:name 格式）
     * @param request 权限更新请求（permission=allow/ask/deny）
     * @return 操作结果
     */
    @Operation(summary = "更新工具权限", description = "设置指定工具的权限等级（allow/ask/deny）")
    @PutMapping("/tools/{toolId}/permission")
    public Result<Void> updateToolPermission(@PathVariable String toolId,
                                             @Valid @RequestBody UpdateToolPermissionRequest request) {
        // 业务含义：askUser 工具权限固定为放行（AC-S03 防确认死锁），任何改权限请求一律拒绝
        if (ToolPermissionService.ASK_USER_TOOL_ID.equals(toolId)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "askUser 工具权限固定为放行（allow），不可修改");
        }
        // 业务含义：loadSkill 工具权限固定为放行（决策 6：技能激活只读可回滚，自主激活不被确认打断）
        if (ToolPermissionService.LOAD_SKILL_TOOL_ID.equals(toolId)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "loadSkill 工具权限固定为放行（allow），不可修改");
        }
        // 业务含义：校验工具存在性（格式错误/不存在抛 TOOL_NOT_FOUND），ForDirect 能力声明校验（AC-T01）
        toolRegistry.resolveToolsForDirect(List.of(toolId));
        // 业务含义：解析权限等级，非法值抛 IllegalArgumentException -> 转参数校验错误（400）
        ToolPermissionLevel level;
        try {
            level = ToolPermissionLevel.parse(request.getPermission());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, e.getMessage());
        }
        // 业务含义：写入显式配置并持久化（AC-N01：重启后仍生效）
        toolPermissionService.setExplicit(toolId, level);
        log.info("更新工具权限: toolId={}, permission={}", toolId, level.getCode());
        return Result.success();
    }

    /**
     * 流式对话（unified-chat-mode 统一路由）
     * <p>
     * 业务含义：接收用户消息，流式返回大模型生成内容（SSE 逐字推送）。
     * 透明续聊：sessionId 无效时自动新建会话，通过 session 事件通知前端更新关联。
     * </p>
     * <p>
     * 统一路由顺序（技术方案 1.2/1.6.2）：
     * 1. 校验消息非空
     * 2. 会话管理（无效 sessionId 新建并发 session 事件）
     * 3. hasPending 优先恢复（回复不解析 /plan，决策 6）
     * 4. PlanCommandParser 解析 /plan（forced 且空内容 -> 友好提示，不写记忆）
     * 5. 记录剥离后内容到记忆 + 知识库注入
     * 6. planAgent.chatUnifiedStream/resumeUnifiedStream + 统一回调注册 + runAsync
     * </p>
     * <p>
     * SSE 事件协议（零变更）：
     * session/token/reasoning/thought/action/observation/final-answer/task_系列/usage/ask_user/done/error
     * </p>
     *
     * @param request 对话请求
     * @return SseEmitter 流式响应
     */
    @Operation(summary = "流式对话", description = "发送消息给 Agent，流式返回生成内容（SSE）")
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody ChatRequest request) {
        // 业务含义：深度思考等长回复可能超过 5 分钟，固定超时会掐断流式输出。
        // 0 = 永不超时（Servlet 规范），客户端真实断开由容器回调兜底（与 WorkflowController 一致）
        SseEmitter emitter = new SseEmitter(0L);

        // 业务含义：空消息校验（AC-015），避免无效请求消耗会话与 LLM 资源
        if (request.getMessage() == null || request.getMessage().trim().isEmpty()) {
            sendEvent(emitter, "error", "消息不能为空");
            emitter.complete();
            return emitter;
        }

        // 会话管理：sessionId 为空或不存在则新建（BR-MEM-005、BR-WEB-008）
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isEmpty() || !sessionManager.exists(sessionId)) {
            sessionId = sessionManager.createSession();
            // 通知前端会话 ID（支持透明续聊：前端据此更新本地会话关联）
            sendEvent(emitter, "session", sessionId);
        }

        // sessionId 在 if 中可能被重新赋值，lambda 要求 effectively final，故用 final 变量
        final String effectiveSessionId = sessionId;

        // 业务含义：技能选择处理（agent-skill，AC-N03/S04）——在 HITL 恢复优先判定之前应用，
        // 手动指定/排除状态立即写入会话，随后下发 manual 激活事件。技能不存在时返回 400。
        applySkillSelection(effectiveSessionId, request, emitter);

        long start = System.currentTimeMillis();

        // 业务含义：读取前端指定的 modelId，null 时使用默认模型
        String modelId = request.getModel();

        // 业务含义：解析工具列表，供 Agent 按需加载
        // 工具解析错误（如格式错误、工具不存在）通过 SSE error 事件通知前端
        List<String> toolIds = request.getTools();
        try {
            if (toolIds != null && !toolIds.isEmpty()) {
                // 流式路径可暂停确认，按 ForStreaming 能力声明校验（AC-T01 唯一解析入口）
                toolRegistry.resolveToolsForStreaming(toolIds); // 仅校验，不在此处使用
            }
        } catch (BusinessException e) {
            sendEvent(emitter, "error", e.getMessage());
            emitter.complete();
            return emitter;
        }

        // ==================== unified-chat-mode 统一路由 ====================

        // 业务含义：hasPending 优先恢复（回复不解析 /plan，决策 6）。
        // 恢复时用户回复作为 Observation 进入 pending 上下文，不重复写记忆（与恢复语义一致）。
        // 工具权限确认恢复（AC-N03/AC-S02）：toolApproved != null 表示用户操作了 tool_confirm 卡片，
        // 走 resumeUnifiedStream 三参重载透传批准/拒绝结果；toolApproved=null 走现有两参恢复（askUser 等）。
        if (humanInteractionManager.hasPending(effectiveSessionId)) {
            UnifiedChatStream unifiedStream;
            if (request.getToolApproved() != null) {
                unifiedStream = planAgent.resumeUnifiedStream(
                        effectiveSessionId, request.getMessage(), request.getToolApproved());
            } else {
                unifiedStream = planAgent.resumeUnifiedStream(effectiveSessionId, request.getMessage());
            }
            registerUnifiedCallbacks(unifiedStream, emitter, effectiveSessionId, request.getMessage(), start);
            runTracedAsync(unifiedStream, effectiveSessionId);
            return emitter;
        }

        // 业务含义：/plan 前缀解析（输入层一次性处理，剥离后进入记忆与推理，需求 6.6）
        PlanCommandParser.PlanCommand planCommand = PlanCommandParser.parse(request.getMessage());

        // 业务含义：/plan 空内容友好提示（AC-E02）——不写记忆、不拆解、不报错
        if (planCommand.forced() && (planCommand.content() == null || planCommand.content().trim().isEmpty())) {
            String hint = "您使用了 /plan 指令，但未提供任务描述。请在 /plan 后输入需要拆解的任务，例如：/plan 调研竞品并输出报告";
            sendEvent(emitter, "token", hint);
            int inputTokens = SimpleTokenEstimator.estimate(request.getMessage());
            int outputTokens = SimpleTokenEstimator.estimate(hint);
            sendEvent(emitter, "usage", buildUsageJson(inputTokens, outputTokens, inputTokens + outputTokens, true));
            sendEvent(emitter, "done", System.currentTimeMillis() - start);
            emitter.complete();
            log.info("/plan 空内容提示: sessionId={}", effectiveSessionId);
            return emitter;
        }

        // 业务含义：剥离 /plan 前缀（控制指令不进入推理上下文，需求 6.6）+ 转义用户输入中的框架标记
        // （agent-context-engineering AC-S03：防止用户伪造框架附件/状态消息）
        String baseMessage = planCommand.content() != null ? planCommand.content() : request.getMessage();
        baseMessage = stripFrameworkMarkers(baseMessage);

        // 业务含义：用户指定知识库时，将知识库名称注入用户消息末尾，
        // 引导 LLM 在 ReAct 循环中调用 searchKnowledge 工具时使用指定知识库。
        final String effectiveMessage;
        List<String> knowledgeBases = request.getKnowledgeBases();
        if (knowledgeBases != null && !knowledgeBases.isEmpty()) {
            effectiveMessage = baseMessage
                + "\n\n[系统提示：用户指定了以下知识库，请调用对应的知识库检索工具获取相关信息后再回答："
                + String.join("、", knowledgeBases) + "]";
        } else {
            effectiveMessage = baseMessage;
        }

        // 业务含义（agent-context-engineering AC-M02 组装唯一化）：记忆只写入 effectiveMessage
        // （模型实际所见，含知识库提示），组装层不再重复追加当前用户消息，消除重复注入。
        memoryManager.addUserMessage(effectiveSessionId, effectiveMessage);

        // 业务含义：统一模式编排（首次/强制拆解）。强制拆解经 /plan 前缀表达（AC-N04）。
        UnifiedChatStream unifiedStream = planAgent.chatUnifiedStream(
                effectiveSessionId, effectiveMessage, modelId, toolIds, planCommand.forced());
        registerUnifiedCallbacks(unifiedStream, emitter, effectiveSessionId, effectiveMessage, start);
        runTracedAsync(unifiedStream, effectiveSessionId);

        return emitter;
    }

    /**
     * 在异步线程执行统一编排，并包裹可观测上下文（langsmith-observability，Task-11）
     * <p>
     * 业务含义：SSE 编排运行在 {@code CompletableFuture.runAsync} 异步线程，MDC traceId
     * 是 ThreadLocal 不跨线程传播（技术难点 2）。故在 Controller 线程（TraceIdInterceptor
     * 已写入 MDC）捕获 traceId/sessionId，经 {@link TraceContextHolder} 显式注入异步线程；
     * 同时包裹请求级根 span 生命周期（startRequest/endRequest，AC-T01 共享 trace）。
     * </p>
     */
    private void runTracedAsync(UnifiedChatStream unifiedStream, String sessionId) {
        // 业务含义：Controller 线程捕获 MDC traceId（同步路径回退逻辑见 TraceContextHolder）
        String traceId = TraceContextHolder.currentTraceId();
        TraceContextHolder.TraceContext ctx = new TraceContextHolder.TraceContext(traceId, sessionId);
        CompletableFuture.runAsync(() -> {
            try {
                TraceContextHolder.set(ctx);
                traceCollector.startRequest();
                unifiedStream.start();
            } finally {
                // 业务含义：根 span 与 ThreadLocal 必须清理，防线程池复用泄漏
                traceCollector.endRequest();
                TraceContextHolder.clear();
            }
        });
    }

    /**
     * 注册统一编排回调（技术方案 1.6.4 事件契约，SSE 协议零变更）
     * <p>
     * 业务含义：直答路径 6 事件 + 拆解路径 task_* 10 事件 + ask_user + usage/done/error
     * 全部在此单次注册，Controller 收敛为统一回调注册块。
     * </p>
     */
    private void registerUnifiedCallbacks(UnifiedChatStream unifiedStream, SseEmitter emitter,
                                          String sessionId, String inputMessage, long start) {
        // 累积完整回复（总结 token 走 onSummaryToken，直答走 onPartialResponse）
        StringBuilder fullResponse = new StringBuilder();

        unifiedStream
                // 直答路径：reasoning/thought/token/action/observation/final-answer
                .onPartialThinking(thinking -> sendEvent(emitter, "reasoning", thinking))
                .onPartialThought((thought, iteration) -> sendEvent(emitter, "thought",
                        Map.of("content", thought, "iteration", iteration)))
                .onPartialResponse(token -> {
                    sendEvent(emitter, "token", token);
                    fullResponse.append(token);
                })
                .onAction((toolName, arguments, iteration) -> sendEvent(emitter, "action",
                        Map.of("toolName", toolName, "arguments", arguments, "iteration", iteration)))
                .onObservation((result, iteration) -> sendEvent(emitter, "observation",
                        Map.of("result", result, "iteration", iteration)))
                .onFinalAnswer(iteration -> sendEvent(emitter, "final-answer", Map.of("iteration", iteration)))
                // 拆解路径：task_plan + task_start/token/reasoning/thought/action/observation/complete/failed/cancelled
                .onPlan(tasks -> {
                    List<Map<String, Object>> taskList = new ArrayList<>();
                    for (SubTask task : tasks) {
                        Map<String, Object> map = new LinkedHashMap<>();
                        map.put("index", task.index());
                        map.put("title", task.title());
                        taskList.add(map);
                    }
                    sendEvent(emitter, "task_plan", Map.of("tasks", taskList));
                })
                .onTaskStart((index, title) -> sendEvent(emitter, "task_start",
                        Map.of("index", index, "title", title)))
                .onTaskToken((index, content) -> sendEvent(emitter, "task_token",
                        Map.of("index", index, "content", content)))
                .onTaskReasoning((index, content) -> sendEvent(emitter, "task_reasoning",
                        Map.of("index", index, "content", content)))
                .onTaskThought((index, content, iter) -> sendEvent(emitter, "task_thought",
                        Map.of("index", index, "content", content, "iteration", iter)))
                .onTaskAction((index, name, args, iter) -> sendEvent(emitter, "task_action",
                        Map.of("index", index, "toolName", name, "args", args, "iteration", iter)))
                .onTaskObservation((index, result, iter) -> sendEvent(emitter, "task_observation",
                        Map.of("index", index, "result", result, "iteration", iter)))
                .onTaskComplete(index -> sendEvent(emitter, "task_complete", Map.of("index", index)))
                .onTaskFailed((index, error) -> sendEvent(emitter, "task_failed",
                        Map.of("index", index, "error", error)))
                .onTaskCancelled(index -> sendEvent(emitter, "task_cancelled", Map.of("index", index)))
                // 拆解总结：token/reasoning（token 累积供 usage 估算）
                .onSummaryToken(token -> {
                    sendEvent(emitter, "token", token);
                    fullResponse.append(token);
                })
                .onSummaryReasoning(reasoning -> sendEvent(emitter, "reasoning", reasoning))
                // HITL 暂停：ask_user + done（流结束）
                .onAskUser((type, question, options, retryCount) -> {
                    sendEvent(emitter, "ask_user", Map.of("type", type, "question", question,
                            "options", options != null ? options : List.of(), "retryCount", retryCount));
                    sendEvent(emitter, "done", System.currentTimeMillis() - start);
                    emitter.complete();
                })
                // 工具权限确认：tool_confirm（AC-H01）
                // 业务含义：ask 级工具被拦截时推送确认卡片数据。与 ask_user 不同——事件后不 complete，
                // emitter 保持打开（pending 挂起），前端渲染卡片等用户操作，随后经 resumeUnifiedStream 回传 toolApproved。
                .onToolConfirm((toolCallId, toolName, toolDescription, arguments) -> sendEvent(emitter, "tool_confirm",
                        Map.of("toolName", toolName, "toolDescription",
                                toolDescription != null ? toolDescription : "", "arguments",
                                arguments != null ? arguments : "")))
                // 技能激活：skill_activated（agent-skill，AC-S04）
                // 业务含义：loadSkill 拦截激活成功时推送激活事件（技能 id/名称/来源/绑定工具），
                // 前端据此渲染激活徽标；手动指定激活由 chatStream 前置下发（source=manual）。
                .onSkillActivated((skillId, skillName, source, boundToolIds) -> sendEvent(emitter, "skill_activated",
                        Map.of("skillId", skillId != null ? skillId : "",
                                "skillName", skillName != null ? skillName : "",
                                "source", source != null ? source : "auto",
                                "boundToolIds", boundToolIds != null ? boundToolIds : List.of())))
                // 完成：写记忆 + usage + done（直答=最终回答，拆解=总结文本）
                .onComplete(fullResponseStr -> {
                    memoryManager.addAssistantMessage(sessionId, fullResponseStr);
                    long duration = System.currentTimeMillis() - start;
                    int inputTokens = SimpleTokenEstimator.estimate(inputMessage);
                    int outputTokens = SimpleTokenEstimator.estimate(fullResponseStr);
                    int totalTokens = inputTokens + outputTokens;
                    sendEvent(emitter, "usage", buildUsageJson(inputTokens, outputTokens, totalTokens, true));
                    sendEvent(emitter, "done", duration);
                    emitter.complete();
                })
                .onError(error -> {
                    log.error("统一模式对话异常: sessionId={}", sessionId, error);
                    sendEvent(emitter, "error", "生成回复时发生错误，请重试");
                    emitter.complete();
                });

        // BUG 修复：注册 emitter 生命周期回调，超时/断开时取消异步编排，
        // 避免异步线程继续向已 complete 的 emitter 发送事件导致 IllegalStateException
        emitter.onTimeout(() -> {
            log.warn("SSE 超时，取消统一编排: sessionId={}", sessionId);
            unifiedStream.cancel();
        });
        emitter.onError(e -> {
            log.warn("SSE 异常，取消统一编排: sessionId={}", sessionId);
            unifiedStream.cancel();
        });
    }

    /**
     * 应用会话级技能选择（手动指定/排除），并下发 manual 激活事件
     * <p>
     * 业务含义（agent-skill，AC-N03/S04）：ChatRequest.skills/excludedSkills 落点。
     * 手动指定（skills 非空）立即激活并下发 skill_activated（source=manual）；
     * 指定不存在的技能返回 400。
     * </p>
     */
    private void applySkillSelection(String sessionId, ChatRequest request, SseEmitter emitter) {
        List<String> skills = request.getSkills();
        List<String> excludedSkills = request.getExcludedSkills();
        if (skills == null && excludedSkills == null) {
            return;
        }
        // 手动指定存在性校验（仅非空时）
        if (skills != null && !skills.isEmpty()) {
            for (String skillId : skills) {
                if (skillSessionManager.getSkillDefinition(skillId).isEmpty()) {
                    sendEvent(emitter, "error", "技能不存在: " + skillId);
                    emitter.complete();
                    throw new BusinessException(ErrorCode.PARAM_INVALID, "技能不存在: " + skillId);
                }
            }
        }
        skillSessionManager.applyManualSelection(sessionId, skills, excludedSkills);
        // 手动指定激活事件下发（source=manual）
        if (skills != null && !skills.isEmpty()) {
            for (String skillId : skills) {
                skillSessionManager.getSkillDefinition(skillId).ifPresent(skill -> {
                    List<String> boundToolIds = scriptToolNames(skillId, skill.getScripts());
                    sendEvent(emitter, "skill_activated",
                            Map.of("skillId", skill.getId(), "skillName", skill.getName(),
                                    "source", "manual", "boundToolIds", boundToolIds));
                });
            }
        }
        // agent-context-engineering（AC-S01 状态持久化）：排除技能时写 STATUS 附件进会话记忆，
        // 模型后续轮次据此不再尝试激活已排除技能
        if (excludedSkills != null && !excludedSkills.isEmpty() && skillPromptComposer != null) {
            for (String skillId : excludedSkills) {
                try {
                    String statusText = skillPromptComposer.composeStatusAttachment(
                            "技能 " + skillId + " 已被用户排除，请勿再次尝试激活。");
                    if (statusText != null && !statusText.isBlank()) {
                        memoryManager.addAttachment(sessionId,
                                com.agentdemo.memory.shortterm.CompressingChatMemory.AttachmentType.STATUS,
                                statusText);
                    }
                } catch (Exception e) {
                    log.warn("技能排除状态附件写入失败（降级跳过）: sessionId={}, skillId={}, error={}",
                            sessionId, skillId, e.getMessage());
                }
            }
        }
        log.info("技能选择应用: sessionId={}, skills={}, excludedSkills={}", sessionId, skills, excludedSkills);
    }

    /**
     * 转义用户输入开头的框架标记（agent-context-engineering，AC-S03 防伪造路径）
     * <p>
     * 业务含义：框架附件（【框架附件·）、摘要（【历史对话摘要】）、状态（<agent_status>）均为
     * 平台专属通道，仅框架代码可写入。用户输入若以这些标记开头，视为伪造尝试，替换为转义文本，
     * 使其不再匹配框架标记前缀（不会被当作附件/摘要/状态进入记忆流）。
     * </p>
     */
    private String stripFrameworkMarkers(String text) {
        if (text == null) {
            return null;
        }
        String stripped = text;
        for (String marker : new String[]{
                com.agentdemo.memory.shortterm.CompressingChatMemory.ATTACHMENT_PREFIX,
                com.agentdemo.memory.shortterm.CompressingChatMemory.SUMMARY_PREFIX,
                "<agent_status>"}) {
            if (stripped.startsWith(marker)) {
                stripped = "用户消息（已转义系统标记）" + stripped.substring(marker.length());
                break;
            }
        }
        return stripped;
    }

    /**
     * 自带脚本工具名列表（skill_{skillId}_{scriptName}，手动指定事件载荷，CR-001）
     */
    private List<String> scriptToolNames(String skillId, List<com.agentdemo.skill.entity.SkillScript> scripts) {
        if (scripts == null || scripts.isEmpty()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (var script : scripts) {
            if (script != null && script.getName() != null && !script.getName().isBlank()) {
                names.add(com.agentdemo.skill.script.SkillScriptToolRegistrar.buildToolName(skillId, script.getName()));
            }
        }
        return names;
    }

    /**
     * 发送 SSE 事件（统一异常处理，避免异常中断后续 SSE 事件推送）
     *
     * @param emitter  SSE 发射器
     * @param eventName 事件名
     * @param data      事件数据
     */
    private void sendEvent(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (Exception e) {
            // 业务含义：客户端断开（IOException）或 emitter 已超时/完成（IllegalStateException），
            // 降级为 WARN 日志，不抛异常，避免中断后续 SSE 事件推送
            log.warn("SSE 发送失败: event={}, reason={}", eventName, e.getMessage());
        }
    }

    /**
     * 构建 usage 事件的 JSON 数据（Task-16 新增）
     * <p>
     * 业务含义：将 Token 用量构造为前端可解析的 JSON 格式，
     * estimated 标识是否为估算值（true=SimpleTokenEstimator 估算，false=API 返回的真实值）。
     * </p>
     *
     * @param inputTokens  输入 Token 数
     * @param outputTokens 输出 Token 数
     * @param totalTokens  总 Token 数
     * @param estimated    是否为估算值
     * @return JSON 字符串，如 {"inputTokens":150,"outputTokens":320,"totalTokens":470,"estimated":false}
     */
    private String buildUsageJson(int inputTokens, int outputTokens, int totalTokens, boolean estimated) {
        return String.format("{\"inputTokens\":%d,\"outputTokens\":%d,\"totalTokens\":%d,\"estimated\":%s}",
                inputTokens, outputTokens, totalTokens, estimated);
    }

    /**
     * 创建新会话
     *
     * @return 会话 ID
     */
    @Operation(summary = "创建会话", description = "创建新的对话会话")
    @PostMapping("/session")
    public Result<String> createSession() {
        String sessionId = sessionManager.createSession();
        return Result.success(sessionId);
    }

    /**
     * 查询会话是否存在
     *
     * @param sessionId 会话 ID
     * @return 是否存在
     */
    @Operation(summary = "查询会话", description = "查询指定会话是否存在")
    @GetMapping("/session/{sessionId}")
    public Result<Boolean> existsSession(@PathVariable String sessionId) {
        return Result.success(sessionManager.exists(sessionId));
    }

    /**
     * 清空会话记忆
     * 业务含义：删除指定会话的对话历史，重新开始对话
     *
     * @param sessionId 会话 ID
     * @return 操作结果
     */
    @Operation(summary = "清空记忆", description = "清空指定会话的对话记忆")
    @DeleteMapping("/session/{sessionId}/memory")
    public Result<Void> clearMemory(@PathVariable String sessionId) {
        memoryManager.clearMemory(sessionId);
        return Result.success();
    }
}
