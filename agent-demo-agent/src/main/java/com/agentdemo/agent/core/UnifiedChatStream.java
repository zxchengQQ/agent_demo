package com.agentdemo.agent.core;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.agent.prompt.PromptTemplateLoader;
import com.agentdemo.agent.single.HITLReActStream;
import com.agentdemo.agent.single.SessionToolResolver;
import com.agentdemo.llm.registry.ModelFactory;
import com.agentdemo.llm.thinking.ThinkingStreamingChatModel;
import com.agentdemo.memory.shortterm.ChatMemoryManager;
import com.agentdemo.tools.registry.ToolExecutor;
import com.agentdemo.tools.registry.ToolSchemaConverter;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一对话模式编排核心（unified-chat-mode 新增）
 * <p>
 * 业务含义：以深度思考 ReAct 为基础的统一对话模式——前置规划判断动态路由
 * "拆解执行/直接回答"两条路径，全路径具备 askUser 人机交互能力（含子任务级暂停-恢复）。
 * 四条入口：直接回答（judge 空列表）、自动拆解（judge 非空列表）、强制拆解（/plan）、
 * 暂停恢复（按 pending.mode 分流 direct/breakdown/tool_confirm）。
 * </p>
 * <p>
 * 编排职责（技术方案 1.2 框架选择策略）：
 * 1. forced 且内容为空 -> 友好提示（AC-E02，不写记忆不拆解）
 * 2. forced -> 跳过"是否拆解"判断，强制进入拆解（规划失败/空则单一子任务）
 * 3. 普通 -> TaskPlanJudge 判断：空列表直答、非空拆解
 * 4. resume -> 按 pending.mode 分流（direct 恢复直答、breakdown 恢复子任务续跑、
 *    tool_confirm 按 approved 批准/拒绝恢复权限确认，AC-N03/AC-S02）
 * </p>
 * <p>
 * 事件契约（技术方案 1.6.4）：SSE 协议零变更，回调签名复用现有函数式接口。
 * </p>
 * <p>
 * 关联 AC：AC-N01~N05、AC-T01~T05、AC-E01/E02、AC-M01/M02
 * </p>
 */
public class UnifiedChatStream {

    private static final Logger log = LoggerFactory.getLogger(UnifiedChatStream.class);

    // ==================== 依赖与参数 ====================
    private final String sessionId;
    private final String message;
    private final String modelId;
    /** /plan 强制拆解标记（true 跳过"是否拆解"判断） */
    private final boolean forcedBreakdown;
    /** 恢复模式（true 时按 pending.mode 分流恢复，不再解析 /plan） */
    private final boolean resumeMode;
    /** 工具标识列表（null=沿用缓存，空=清除，非空=指定） */
    private final List<String> toolIds;
    /**
     * 权限确认结果（仅 tool_confirm 恢复模式生效）
     * <p>
     * 业务含义：true=用户批准（执行待确认工具并回填结果，AC-N03），
     * false/null=用户拒绝（回填拒绝文案不执行，AC-S02）。null 防御性按拒绝处理。
     * </p>
     */
    private final Boolean approved;

    private final ModelFactory modelFactory;
    private final ChatMemoryManager memoryManager;
    private final AgentConfig agentConfig;
    private final ToolSchemaConverter toolSchemaConverter;
    private final ToolExecutor toolExecutor;
    private final PromptTemplateLoader promptTemplateLoader;
    private final HumanInteractionManager humanInteractionManager;
    private final SessionToolResolver sessionToolResolver;
    private final TaskPlanJudge taskPlanJudge;

    // ==================== 回调消费者（直答路径 + 拆解路径 + HITL + 生命周期） ====================
    // 直答路径（SSE: reasoning/thought/token/action/observation/final-answer）
    private ThinkingTokenStream.ThinkingConsumer onPartialThinking;
    private ThinkingTokenStream.ThoughtConsumer onPartialThought;
    private ThinkingTokenStream.ResponseConsumer onPartialResponse;
    private ThinkingTokenStream.ActionConsumer onAction;
    private ThinkingTokenStream.ObservationConsumer onObservation;
    private ThinkingTokenStream.FinalAnswerConsumer onFinalAnswer;

    // 拆解路径（SSE: task_* 10 事件）
    private TaskBreakdownStream.PlanConsumer onPlan;
    private TaskBreakdownStream.TaskStartConsumer onTaskStart;
    private TaskBreakdownStream.TaskTokenConsumer onTaskToken;
    private TaskBreakdownStream.TaskReasoningConsumer onTaskReasoning;
    private TaskBreakdownStream.TaskThoughtConsumer onTaskThought;
    private TaskBreakdownStream.TaskActionConsumer onTaskAction;
    private TaskBreakdownStream.TaskObservationConsumer onTaskObservation;
    private TaskBreakdownStream.TaskCompleteConsumer onTaskComplete;
    private TaskBreakdownStream.TaskFailedConsumer onTaskFailed;
    private TaskBreakdownStream.TaskCancelledConsumer onTaskCancelled;

    // 拆解总结（SSE: token/reasoning）
    private TaskBreakdownStream.TokenConsumer onSummaryToken;
    private TaskBreakdownStream.ReasoningConsumer onSummaryReasoning;

    // HITL 暂停（SSE: ask_user + done）
    private HitlTokenStream.AskUserConsumer onAskUser;

    // 工具权限确认（SSE: tool_confirm；事件后流保持打开，等待用户操作回传 toolApproved）
    private HitlTokenStream.ToolConfirmConsumer onToolConfirm;

    // 生命周期（SSE: usage + done；onComplete 携带完整响应）
    private ThinkingTokenStream.CompleteConsumer onComplete;
    private ThinkingTokenStream.ErrorConsumer onError;

    private volatile boolean cancelled = false;

    // ==================== 构造器 ====================

    public UnifiedChatStream(String sessionId, String message, String modelId,
                             boolean forcedBreakdown, boolean resumeMode, List<String> toolIds,
                             Boolean approved,
                             ModelFactory modelFactory, ChatMemoryManager memoryManager,
                             AgentConfig agentConfig, ToolSchemaConverter toolSchemaConverter,
                             ToolExecutor toolExecutor, PromptTemplateLoader promptTemplateLoader,
                             HumanInteractionManager humanInteractionManager,
                             SessionToolResolver sessionToolResolver, TaskPlanJudge taskPlanJudge) {
        this.sessionId = sessionId;
        this.message = message;
        this.modelId = modelId;
        this.forcedBreakdown = forcedBreakdown;
        this.resumeMode = resumeMode;
        this.toolIds = toolIds;
        this.approved = approved;
        this.modelFactory = modelFactory;
        this.memoryManager = memoryManager;
        this.agentConfig = agentConfig;
        this.toolSchemaConverter = toolSchemaConverter;
        this.toolExecutor = toolExecutor;
        this.promptTemplateLoader = promptTemplateLoader;
        this.humanInteractionManager = humanInteractionManager;
        this.sessionToolResolver = sessionToolResolver;
        this.taskPlanJudge = taskPlanJudge;
    }

    // ==================== 链式回调注册 ====================

    public UnifiedChatStream onPartialThinking(ThinkingTokenStream.ThinkingConsumer consumer) {
        this.onPartialThinking = consumer;
        return this;
    }

    public UnifiedChatStream onPartialThought(ThinkingTokenStream.ThoughtConsumer consumer) {
        this.onPartialThought = consumer;
        return this;
    }

    public UnifiedChatStream onPartialResponse(ThinkingTokenStream.ResponseConsumer consumer) {
        this.onPartialResponse = consumer;
        return this;
    }

    public UnifiedChatStream onAction(ThinkingTokenStream.ActionConsumer consumer) {
        this.onAction = consumer;
        return this;
    }

    public UnifiedChatStream onObservation(ThinkingTokenStream.ObservationConsumer consumer) {
        this.onObservation = consumer;
        return this;
    }

    public UnifiedChatStream onFinalAnswer(ThinkingTokenStream.FinalAnswerConsumer consumer) {
        this.onFinalAnswer = consumer;
        return this;
    }

    public UnifiedChatStream onPlan(TaskBreakdownStream.PlanConsumer consumer) {
        this.onPlan = consumer;
        return this;
    }

    public UnifiedChatStream onTaskStart(TaskBreakdownStream.TaskStartConsumer consumer) {
        this.onTaskStart = consumer;
        return this;
    }

    public UnifiedChatStream onTaskToken(TaskBreakdownStream.TaskTokenConsumer consumer) {
        this.onTaskToken = consumer;
        return this;
    }

    public UnifiedChatStream onTaskReasoning(TaskBreakdownStream.TaskReasoningConsumer consumer) {
        this.onTaskReasoning = consumer;
        return this;
    }

    public UnifiedChatStream onTaskThought(TaskBreakdownStream.TaskThoughtConsumer consumer) {
        this.onTaskThought = consumer;
        return this;
    }

    public UnifiedChatStream onTaskAction(TaskBreakdownStream.TaskActionConsumer consumer) {
        this.onTaskAction = consumer;
        return this;
    }

    public UnifiedChatStream onTaskObservation(TaskBreakdownStream.TaskObservationConsumer consumer) {
        this.onTaskObservation = consumer;
        return this;
    }

    public UnifiedChatStream onTaskComplete(TaskBreakdownStream.TaskCompleteConsumer consumer) {
        this.onTaskComplete = consumer;
        return this;
    }

    public UnifiedChatStream onTaskFailed(TaskBreakdownStream.TaskFailedConsumer consumer) {
        this.onTaskFailed = consumer;
        return this;
    }

    public UnifiedChatStream onTaskCancelled(TaskBreakdownStream.TaskCancelledConsumer consumer) {
        this.onTaskCancelled = consumer;
        return this;
    }

    public UnifiedChatStream onSummaryToken(TaskBreakdownStream.TokenConsumer consumer) {
        this.onSummaryToken = consumer;
        return this;
    }

    public UnifiedChatStream onSummaryReasoning(TaskBreakdownStream.ReasoningConsumer consumer) {
        this.onSummaryReasoning = consumer;
        return this;
    }

    public UnifiedChatStream onAskUser(HitlTokenStream.AskUserConsumer consumer) {
        this.onAskUser = consumer;
        return this;
    }

    public UnifiedChatStream onToolConfirm(HitlTokenStream.ToolConfirmConsumer consumer) {
        this.onToolConfirm = consumer;
        return this;
    }

    public UnifiedChatStream onComplete(ThinkingTokenStream.CompleteConsumer consumer) {
        this.onComplete = consumer;
        return this;
    }

    public UnifiedChatStream onError(ThinkingTokenStream.ErrorConsumer consumer) {
        this.onError = consumer;
        return this;
    }

    // ==================== 取消与启动 ====================

    public void cancel() {
        cancelled = true;
    }

    /**
     * 启动统一编排
     * <p>
     * 业务含义：按路由顺序执行——恢复模式优先（回复不解析 /plan，决策 6）-> /plan 空内容
     * 友好提示 -> 强制拆解（跳过判断）-> 普通消息规划判断路由（拆解/直答）。
     * </p>
     */
    public void start() {
        try {
            if (resumeMode) {
                handleResume();
                return;
            }
            if (forcedBreakdown && (message == null || message.trim().isEmpty())) {
                handleEmptyPlanPrompt();
                return;
            }
            if (forcedBreakdown) {
                startForcedBreakdown();
                return;
            }

            // 业务含义：普通消息 -> 前置规划判断（TaskPlanJudge），空列表直答、非空拆解
            List<SubTask> tasks = taskPlanJudge.judge(sessionId, message, modelId);
            if (tasks == null || tasks.isEmpty()) {
                log.info("统一模式路由: sessionId={}, 路径=direct（判断为空）", sessionId);
                startDirectAnswer();
            } else {
                log.info("统一模式路由: sessionId={}, 路径=breakdown（子任务数={}）", sessionId, tasks.size());
                startBreakdown(tasks);
            }
        } catch (Exception e) {
            log.error("统一编排异常: sessionId={}", sessionId, e);
            if (onError != null) {
                onError.accept(e);
            }
        }
    }

    // ==================== 恢复路由 ====================

    /**
     * 恢复模式：按 pending.mode 分流
     * <p>
     * 业务含义：hasPending 优先恢复（决策 6：回复中的 /plan 视为普通文本）。
     * mode=direct -> 直答恢复；mode=breakdown -> 子任务续跑；pending 缺失 -> 降级普通流程。
     * </p>
     */
    private void handleResume() {
        PendingInteraction pending = humanInteractionManager.loadInteraction(sessionId);
        if (pending == null) {
            // 降级：pending 缺失，走普通统一流程重新处理该消息（技术方案 3.3）
            log.warn("统一模式恢复失败：sessionId={} 无 pending，降级普通流程", sessionId);
            List<SubTask> tasks = taskPlanJudge.judge(sessionId, message, modelId);
            if (tasks == null || tasks.isEmpty()) {
                startDirectAnswer();
            } else {
                startBreakdown(tasks);
            }
            return;
        }

        if (PendingInteraction.MODE_TOOL_CONFIRM.equals(pending.getMode())) {
            log.info("统一模式恢复路由: sessionId={}, mode=tool_confirm（权限确认恢复）", sessionId);
            resumeToolConfirm(pending);
        } else if (PendingInteraction.MODE_BREAKDOWN.equals(pending.getMode())) {
            log.info("统一模式恢复路由: sessionId={}, mode=breakdown（子任务续跑）", sessionId);
            resumeBreakdown();
        } else {
            log.info("统一模式恢复路由: sessionId={}, mode=direct（直答恢复）", sessionId);
            resumeDirectAnswer(pending);
        }
    }

    /**
     * 权限确认恢复：批准执行工具 / 拒绝回填拒绝文案，续跑 ReAct 循环
     * <p>
     * 业务含义：mode=tool_confirm 暂停后按用户批准/拒绝分流（AC-N03/AC-S02）：
     * 1. 批准（approved=true）-> toolExecutor.execute(pendingToolName, pendingToolArguments) 执行工具，
     *    结果以 id=pendingToolCallId 的 ToolExecutionResultMessage 回填（LLM 要求结果消息与 toolCall id 匹配，风险 §5）；
     * 2. 拒绝（approved=false/null）-> 工具零执行，固定拒绝文案回填（含"用户拒绝"语义，不含权限配置细节）；
     * 3. 两分支均 clearInteraction 清除暂停态，复用直答恢复的模型加载与循环重启逻辑
     *    （pending.getMessages() 已含该轮 AiMessage 与已执行 allow 工具结果，游标完整性由 Task-10 保证）。
     * 用户回复的 UI 文本 message 不进入推理上下文——权限决策由 toolApproved 表达，结果消息即 Observation。
     * </p>
     */
    private void resumeToolConfirm(PendingInteraction pending) {
        boolean approved = Boolean.TRUE.equals(this.approved);
        String toolCallId = pending.getPendingToolCallId();
        String toolName = pending.getPendingToolName();
        String toolArguments = pending.getPendingToolArguments();

        String result;
        if (approved) {
            result = toolExecutor.execute(toolName, toolArguments);
        } else {
            result = "用户拒绝了本次工具调用（" + toolName + "），请换用其他方式完成该任务。";
        }

        // 业务含义：以暂停时的 toolCall id 回填结果消息（而非重新生成），保证 LLM 会话上下文一致
        pending.getMessages().add(ToolExecutionResultMessage.from(toolCallId, toolName, result));

        humanInteractionManager.clearInteraction(sessionId);

        // 复用直答恢复逻辑：加载模型 + 重建 HITLReActStream + 注册回调 + 启动循环
        ThinkingStreamingChatModel thinkingModel = (pending.getModelId() != null)
                ? modelFactory.getThinkingStreamingChatModelByModelId(pending.getModelId())
                : modelFactory.getDefaultThinkingStreamingChatModel();

        HITLReActStream hitl = new HITLReActStream(
                thinkingModel,
                pending.getMessages(),
                pending.getToolsJson(),
                toolExecutor,
                humanInteractionManager,
                sessionId,
                pending.getModelId(),
                pending.getRetryCount() + 1,
                agentConfig.getThinkingMaxIterations());
        registerHitlCallbacks(hitl, pending.getMessages(), pending.getToolsJson());
        hitl.start();
    }

    /**
     * 直答恢复：续用暂停时上下文（pending.messages + 用户回复 Observation）
     */
    private void resumeDirectAnswer(PendingInteraction pending) {
        // 业务含义：查找 askUser 工具调用 ID，将用户回复作为 Observation 追加
        String toolCallId = findAskUserToolCallId(pending.getMessages());
        pending.getMessages().add(ToolExecutionResultMessage.from(toolCallId, "askUser", message));

        humanInteractionManager.clearInteraction(sessionId);

        ThinkingStreamingChatModel thinkingModel = (pending.getModelId() != null)
                ? modelFactory.getThinkingStreamingChatModelByModelId(pending.getModelId())
                : modelFactory.getDefaultThinkingStreamingChatModel();

        HITLReActStream hitl = new HITLReActStream(
                thinkingModel,
                pending.getMessages(),
                pending.getToolsJson(),
                toolExecutor,
                humanInteractionManager,
                sessionId,
                pending.getModelId(),
                pending.getRetryCount() + 1,
                agentConfig.getThinkingMaxIterations());
        registerHitlCallbacks(hitl, pending.getMessages(), pending.getToolsJson());
        hitl.start();
    }

    /**
     * 拆解恢复：构造 TaskBreakdownStream 并 resumeFromPending
     */
    private void resumeBreakdown() {
        TaskBreakdownStream breakdownStream = createBreakdownStream(List.of());
        registerBreakdownCallbacks(breakdownStream);
        breakdownStream.resumeFromPending(message);
    }

    // ==================== 路径实现 ====================

    /**
     * AC-E02：/plan 空内容友好提示（token 事件 + done，不写记忆不拆解）
     */
    private void handleEmptyPlanPrompt() {
        String hint = "您使用了 /plan 指令，但未提供任务描述。请在 /plan 后输入需要拆解的任务，例如：/plan 调研竞品并输出报告";
        log.info("/plan 空内容提示: sessionId={}", sessionId);
        if (onPartialResponse != null) {
            onPartialResponse.accept(hint);
        }
        if (onComplete != null) {
            onComplete.accept(hint);
        }
    }

    /**
     * 强制拆解（/plan）：跳过"是否拆解"判断
     * <p>
     * 业务含义：仍调用 TaskPlanJudge 生成子任务列表（规划），但"空列表降级直答"被跳过——
     * 用户强制拆解时若规划失败/为空，强制以单一子任务执行整个任务主题（AC-N04）。
     * </p>
     */
    private void startForcedBreakdown() {
        List<SubTask> tasks = taskPlanJudge.judge(sessionId, message, modelId);
        if (tasks == null || tasks.isEmpty()) {
            // 业务含义：强制拆解不允许降级直答，以单一子任务执行整个任务主题
            log.warn("强制拆解规划为空，降级单一子任务: sessionId={}", sessionId);
            tasks = List.of(new SubTask(1, message));
        }
        startBreakdown(tasks);
    }

    /**
     * 拆解路径：TaskBreakdownStream 执行（自动拆解/强制拆解共用）
     */
    private void startBreakdown(List<SubTask> tasks) {
        TaskBreakdownStream breakdownStream = createBreakdownStream(tasks);
        registerBreakdownCallbacks(breakdownStream);
        breakdownStream.startWithTasks(tasks);
    }

    /**
     * 直答路径：hitl 场景 + HITLReActStream（深度思考 ReAct + askUser）
     */
    private void startDirectAnswer() {
        // 业务含义：统一工具解析管道（默认 ∪ 指定 + askUser 补入 + 去重），直答/拆解共用
        List<Object> tools = sessionToolResolver.resolveSessionTools(sessionId, toolIds);
        tools = sessionToolResolver.ensureAskUserTool(tools);
        String toolsJson = toolSchemaConverter.convertToJson(tools);

        List<ChatMessage> messages = buildHitlMessages(tools);

        ThinkingStreamingChatModel thinkingModel = (modelId != null)
                ? modelFactory.getThinkingStreamingChatModelByModelId(modelId)
                : modelFactory.getDefaultThinkingStreamingChatModel();

        HITLReActStream hitl = new HITLReActStream(
                thinkingModel,
                messages,
                toolsJson,
                toolExecutor,
                humanInteractionManager,
                sessionId,
                modelId,
                0,
                agentConfig.getThinkingMaxIterations());
        registerHitlCallbacks(hitl, messages, toolsJson);
        hitl.start();
    }

    /**
     * 构造拆解编排流（统一工具解析 + 外部注入 tasks）
     */
    private TaskBreakdownStream createBreakdownStream(List<SubTask> tasks) {
        // 业务含义：直答/拆解共用同一份工具解析结果（单次请求内一致，技术方案 3.1）
        List<Object> tools = sessionToolResolver.resolveSessionTools(sessionId, toolIds);
        tools = sessionToolResolver.ensureAskUserTool(tools);
        String toolsJson = toolSchemaConverter.convertToJson(tools);

        return new TaskBreakdownStream(
                sessionId, message, modelId,
                modelFactory, memoryManager, agentConfig, toolSchemaConverter, toolExecutor,
                promptTemplateLoader, humanInteractionManager,
                tools, toolsJson, tasks);
    }

    /**
     * 组装直答路径消息（hitl 场景系统提示词 + 历史记忆 + 当前用户消息）
     */
    private List<ChatMessage> buildHitlMessages(List<Object> tools) {
        List<ChatMessage> messages = new ArrayList<>();
        String systemPrompt = promptTemplateLoader.composeSystemPrompt(PromptTemplateLoader.SCENARIO_HITL)
                .replace("{{tools}}", toolSchemaConverter.convertToDescriptionText(tools));
        messages.add(SystemMessage.from(systemPrompt));
        messages.addAll(memoryManager.getMemory(sessionId).messages());
        messages.add(UserMessage.from(message));
        return messages;
    }

    // ==================== 回调注册 ====================

    /**
     * 注册直答路径回调（HITLReActStream 事件 -> 1.6.4 直答事件契约）
     *
     * @param hitl      HITL ReAct 流实例
     * @param messages  本次 ReAct 循环使用的消息列表（快照外移后由宿主补保存）
     * @param toolsJson 本次 ReAct 循环使用的工具 JSON Schema（快照外移后由宿主补保存）
     */
    private void registerHitlCallbacks(HITLReActStream hitl, List<ChatMessage> messages, String toolsJson) {
        final String[] fullResponse = {""};
        hitl.onPartialThinking(thinking -> {
            if (onPartialThinking != null) {
                onPartialThinking.accept(thinking);
            }
        });
        hitl.onPartialThought((thought, iteration) -> {
            if (onPartialThought != null) {
                onPartialThought.accept(thought, iteration);
            }
        });
        hitl.onPartialResponse(token -> {
            if (onPartialResponse != null) {
                onPartialResponse.accept(token);
            }
            fullResponse[0] = fullResponse[0] + token;
        });
        hitl.onAction((toolName, args, iteration) -> {
            if (onAction != null) {
                onAction.accept(toolName, args, iteration);
            }
        });
        hitl.onObservation((result, iteration) -> {
            if (onObservation != null) {
                onObservation.accept(result, iteration);
            }
        });
        hitl.onFinalAnswer(iteration -> {
            if (onFinalAnswer != null) {
                onFinalAnswer.accept(iteration);
            }
        });
        hitl.onAskUser((type, question, options, retryCount) -> {
            // 业务含义：askUser 拦截暂停，触发 ask_user 事件，不触发 onComplete（流由 ask_user + done 结束）
            if (onAskUser != null) {
                onAskUser.accept(type, question, options, retryCount);
            }
        });
        hitl.onToolConfirm((toolCallId, toolName, toolDescription, arguments) -> {
            // 业务含义（Task-10/11 决策 2 方案 A：快照职责外移）：
            // HITLReActStream 仅触发 4 参回调（含 toolCallId），本宿主在此补保存
            // tool_confirm 快照（mode=tool_confirm + pendingToolCallId + 三字段），
            // 恢复路由 resumeToolConfirm 按 pendingToolCallId 回填结果消息（AC-M02）。
            humanInteractionManager.saveToolConfirmInteraction(
                    sessionId, messages, modelId, toolsJson,
                    toolCallId, toolName, arguments);
            // 触发上层 onToolConfirm 回调（Controller 转 SSE tool_confirm 事件，等待用户批准/拒绝）
            if (onToolConfirm != null) {
                onToolConfirm.accept(toolCallId, toolName, toolDescription, arguments);
            }
        });
        hitl.onComplete(response -> {
            if (onComplete != null) {
                onComplete.accept(response);
            }
        });
        hitl.onError(error -> {
            if (onError != null) {
                onError.accept(error);
            }
        });
    }

    /**
     * 注册拆解路径回调（TaskBreakdownStream 事件 -> 1.6.4 task_* 事件契约）
     */
    private void registerBreakdownCallbacks(TaskBreakdownStream breakdownStream) {
        // 业务含义：拆解总结文本通过 onSummaryToken 累积，onComplete 时回传（Controller 写记忆）
        final StringBuilder summary = new StringBuilder();

        breakdownStream.onPlan(tasks -> {
            if (onPlan != null) {
                onPlan.accept(tasks);
            }
        });
        breakdownStream.onTaskStart((index, title) -> {
            if (onTaskStart != null) {
                onTaskStart.accept(index, title);
            }
        });
        breakdownStream.onTaskToken((index, content) -> {
            if (onTaskToken != null) {
                onTaskToken.accept(index, content);
            }
        });
        breakdownStream.onTaskReasoning((index, content) -> {
            if (onTaskReasoning != null) {
                onTaskReasoning.accept(index, content);
            }
        });
        breakdownStream.onTaskThought((index, content, iteration) -> {
            if (onTaskThought != null) {
                onTaskThought.accept(index, content, iteration);
            }
        });
        breakdownStream.onTaskAction((index, toolName, args, iteration) -> {
            if (onTaskAction != null) {
                onTaskAction.accept(index, toolName, args, iteration);
            }
        });
        breakdownStream.onTaskObservation((index, result, iteration) -> {
            if (onTaskObservation != null) {
                onTaskObservation.accept(index, result, iteration);
            }
        });
        breakdownStream.onTaskComplete(index -> {
            if (onTaskComplete != null) {
                onTaskComplete.accept(index);
            }
        });
        breakdownStream.onTaskFailed((index, error) -> {
            if (onTaskFailed != null) {
                onTaskFailed.accept(index, error);
            }
        });
        breakdownStream.onTaskCancelled(index -> {
            if (onTaskCancelled != null) {
                onTaskCancelled.accept(index);
            }
        });
        breakdownStream.onSummaryToken(token -> {
            summary.append(token);
            if (onSummaryToken != null) {
                onSummaryToken.accept(token);
            }
        });
        breakdownStream.onSummaryReasoning(reasoning -> {
            if (onSummaryReasoning != null) {
                onSummaryReasoning.accept(reasoning);
            }
        });
        breakdownStream.onAskUser((type, question, options, retryCount) -> {
            if (onAskUser != null) {
                onAskUser.accept(type, question, options, retryCount);
            }
        });
        breakdownStream.onComplete(() -> {
            if (onComplete != null) {
                onComplete.accept(summary.toString());
            }
        });
        breakdownStream.onError(error -> {
            if (onError != null) {
                onError.accept(error);
            }
        });
    }

    /**
     * 从消息列表中查找最后一个 askUser 工具调用的 ID
     */
    private String findAskUserToolCallId(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage msg = messages.get(i);
            if (msg instanceof AiMessage aiMsg && aiMsg.hasToolExecutionRequests()) {
                for (ToolExecutionRequest req : aiMsg.toolExecutionRequests()) {
                    if ("askUser".equals(req.name())) {
                        return req.id();
                    }
                }
            }
        }
        return "askUser";
    }
}
