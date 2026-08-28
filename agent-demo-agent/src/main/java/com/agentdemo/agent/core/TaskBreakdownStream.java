package com.agentdemo.agent.core;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.agent.prompt.PromptTemplateLoader;
import com.agentdemo.agent.single.HITLReActStream;
import com.agentdemo.agent.single.SessionToolResolver;
import com.agentdemo.agent.single.SkillToolInterceptor;
import com.agentdemo.llm.thinking.ThinkingStreamHandler;
import com.agentdemo.llm.thinking.ThinkingStreamingChatModel;
import com.agentdemo.llm.registry.ModelFactory;
import com.agentdemo.llm.thinking.ToolCall;
import com.agentdemo.memory.shortterm.ChatMemoryManager;
import com.agentdemo.skill.prompt.SkillPromptComposer;
import com.agentdemo.tools.registry.ToolExecutor;
import com.agentdemo.tools.registry.ToolSchemaConverter;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务拆解三阶段编排流（CR-002 新增，unified-chat-mode 改造）
 * <p>
 * 业务含义：在单次 SSE 连接中完成拆解执行编排（规划已上移至 TaskPlanJudge）：
 * 1. 启动：注入的 tasks 推送 onPlan（外部注入，不再内部规划）
 * 2. 执行：逐个子任务委托 {@link HITLReActStream} 执行 ReAct 循环（含 askUser 拦截能力）
 * 3. 总结：LLM 流式调用生成最终总结
 * </p>
 * <p>
 * unified-chat-mode 改造要点（技术方案 1.6.2）：
 * ① 构造改为外部注入 tasks / 已解析工具列表 / toolsJson / HumanInteractionManager；
 * ② 删除内部规划（planTasks/parseTaskPlan/extractJsonArray）与降级直答（streamDirectAnswer，
 *    统一模式直答由 UnifiedChatStream 承担）；
 * ③ 子任务执行委托 HITLReActStream（复用 askUser 拦截逻辑，DRY）；
 * ④ 新增暂停信号 {@link BreakdownPausedException}：子任务 askUser 拦截后 attachBreakdownContext
 *    并中止编排，触发 onAskUser，不触发 onComplete；
 * ⑤ 新增恢复入口 {@link #resumeFromPending(String)}：加载 pending 拆解上下文，重放 onPlan +
 *    已完成子任务 onTaskComplete，续跑当前子任务与剩余子任务；
 * ⑥ enableThinking 字段删除（统一模式恒开启，task_reasoning/summary reasoning 无条件推送）；
 * ⑦ 新增 onAskUser 回调。
 * </p>
 * <p>
 * 关联 AC：AC-001~AC-016、AC-T05（子任务暂停-恢复）、AC-M02（上下文连续性）
 * </p>
 */
public class TaskBreakdownStream {

    private static final Logger log = LoggerFactory.getLogger(TaskBreakdownStream.class);

    // ==================== 依赖 ====================
    private final String sessionId;
    private final String message;
    /** 模型 ID（null 表示使用默认模型） */
    private final String modelId;
    private final ModelFactory modelFactory;
    private final ChatMemoryManager memoryManager;
    private final AgentConfig agentConfig;
    private final ToolSchemaConverter toolSchemaConverter;
    private final ToolExecutor toolExecutor;
    private final PromptTemplateLoader promptTemplateLoader;
    private final HumanInteractionManager humanInteractionManager;

    /** 拆解子任务列表（外部注入，规划上移至 TaskPlanJudge） */
    private List<SubTask> tasks;

    /** 已解析工具列表（含 askUser，直答/拆解共用同一份） */
    private final List<Object> tools;

    /** 工具 JSON Schema（单次请求内一次生成复用） */
    private final String toolsJson;

    /** 技能提示词组装器（null 则技能段跳过，agent-skill） */
    private final SkillPromptComposer skillPromptComposer;

    /** 技能工具拦截器（null 则 loadSkill 不拦截，agent-skill） */
    private final SkillToolInterceptor skillToolInterceptor;

    /** 会话工具解析器（agent-context-engineering Task-10：{{tools}} 文本来源，null 退化 tools 全量） */
    private final SessionToolResolver sessionToolResolver;

    // ==================== 回调消费者 ====================
    // 规划阶段
    private PlanConsumer onPlan;

    // 子任务执行阶段
    private TaskStartConsumer onTaskStart;
    private TaskTokenConsumer onTaskToken;
    private TaskReasoningConsumer onTaskReasoning;
    private TaskThoughtConsumer onTaskThought;
    private TaskActionConsumer onTaskAction;
    private TaskObservationConsumer onTaskObservation;
    private TaskCompleteConsumer onTaskComplete;
    private TaskFailedConsumer onTaskFailed;
    private TaskCancelledConsumer onTaskCancelled;

    // 总结阶段
    private TokenConsumer onSummaryToken;
    private ReasoningConsumer onSummaryReasoning;

    // HITL 暂停（子任务执行中 askUser 拦截）
    private AskUserConsumer onAskUser;

    // 技能激活（子任务执行中 loadSkill 拦截，agent-skill）
    private com.agentdemo.agent.core.HitlTokenStream.SkillActivatedConsumer onSkillActivated;

    // 生命周期
    private Runnable onComplete;
    private ErrorConsumer onError;

    // BUG 修复：cancel 标志位，emitter 超时/断开时通知异步线程停止后续执行
    private volatile boolean cancelled = false;

    // ==================== 构造器 ====================

    /**
     * 统一构造器（unified-chat-mode：外部注入 tasks/tools/toolsJson/HumanInteractionManager）
     *
     * @param sessionId              会话 ID
     * @param message                用户消息（已剥离 /plan 前缀）
     * @param modelId                模型 ID（null 使用默认模型）
     * @param modelFactory           模型工厂
     * @param memoryManager          记忆管理器
     * @param agentConfig            Agent 配置
     * @param toolSchemaConverter    工具 Schema 转换器
     * @param toolExecutor           工具执行器
     * @param promptTemplateLoader   提示词模板加载器
     * @param humanInteractionManager 人机交互管理器（子任务暂停-恢复）
     * @param tools                  已解析工具列表（含 askUser）
     * @param toolsJson              工具 JSON Schema
     * @param tasks                  拆解子任务列表（可为空集合，由 startWithTasks 设置）
     */
    public TaskBreakdownStream(String sessionId, String message, String modelId,
                               ModelFactory modelFactory, ChatMemoryManager memoryManager,
                               AgentConfig agentConfig, ToolSchemaConverter toolSchemaConverter,
                               ToolExecutor toolExecutor, PromptTemplateLoader promptTemplateLoader,
                               HumanInteractionManager humanInteractionManager,
                               List<Object> tools, String toolsJson, List<SubTask> tasks) {
        this(sessionId, message, modelId, modelFactory, memoryManager, agentConfig,
                toolSchemaConverter, toolExecutor, promptTemplateLoader, humanInteractionManager,
                tools, toolsJson, tasks, null, null, null);
    }

    /**
     * 构造器（技能能力版本，agent-skill）
     *
     * @param skillPromptComposer 技能提示词组装器（null 则技能段跳过）
     * @param skillToolInterceptor 技能工具拦截器（null 则 loadSkill 不拦截）
     */
    public TaskBreakdownStream(String sessionId, String message, String modelId,
                               ModelFactory modelFactory, ChatMemoryManager memoryManager,
                               AgentConfig agentConfig, ToolSchemaConverter toolSchemaConverter,
                               ToolExecutor toolExecutor, PromptTemplateLoader promptTemplateLoader,
                               HumanInteractionManager humanInteractionManager,
                               List<Object> tools, String toolsJson, List<SubTask> tasks,
                               SkillPromptComposer skillPromptComposer,
                               SkillToolInterceptor skillToolInterceptor) {
        this(sessionId, message, modelId, modelFactory, memoryManager, agentConfig,
                toolSchemaConverter, toolExecutor, promptTemplateLoader, humanInteractionManager,
                tools, toolsJson, tasks, skillPromptComposer, skillToolInterceptor, null);
    }

    /**
     * 构造器（技能能力版本 + 会话工具解析器，agent-context-engineering Task-10）
     * <p>
     * sessionToolResolver 用于子任务系统提示词 {{tools}} 文本的来源（会话基础工具集，
     * 冻结契约 AC-T02）；null 时退化为 tools 全量（兼容既有测试与退化路径）。
     * </p>
     *
     * @param sessionToolResolver 会话工具解析器（null 则 {{tools}} 文本用 tools 全量）
     */
    public TaskBreakdownStream(String sessionId, String message, String modelId,
                               ModelFactory modelFactory, ChatMemoryManager memoryManager,
                               AgentConfig agentConfig, ToolSchemaConverter toolSchemaConverter,
                               ToolExecutor toolExecutor, PromptTemplateLoader promptTemplateLoader,
                               HumanInteractionManager humanInteractionManager,
                               List<Object> tools, String toolsJson, List<SubTask> tasks,
                               SkillPromptComposer skillPromptComposer,
                               SkillToolInterceptor skillToolInterceptor,
                               SessionToolResolver sessionToolResolver) {
        this.sessionId = sessionId;
        this.message = message;
        this.modelId = modelId;
        this.modelFactory = modelFactory;
        this.memoryManager = memoryManager;
        this.agentConfig = agentConfig;
        this.toolSchemaConverter = toolSchemaConverter;
        this.toolExecutor = toolExecutor;
        this.promptTemplateLoader = promptTemplateLoader;
        this.humanInteractionManager = humanInteractionManager;
        this.tools = tools;
        this.toolsJson = toolsJson;
        this.tasks = tasks;
        this.skillPromptComposer = skillPromptComposer;
        this.skillToolInterceptor = skillToolInterceptor;
        this.sessionToolResolver = sessionToolResolver;
    }

    // ==================== 链式 Setter ====================

    public TaskBreakdownStream onPlan(PlanConsumer consumer) {
        this.onPlan = consumer;
        return this;
    }

    public TaskBreakdownStream onTaskStart(TaskStartConsumer consumer) {
        this.onTaskStart = consumer;
        return this;
    }

    public TaskBreakdownStream onTaskToken(TaskTokenConsumer consumer) {
        this.onTaskToken = consumer;
        return this;
    }

    public TaskBreakdownStream onTaskReasoning(TaskReasoningConsumer consumer) {
        this.onTaskReasoning = consumer;
        return this;
    }

    public TaskBreakdownStream onTaskThought(TaskThoughtConsumer consumer) {
        this.onTaskThought = consumer;
        return this;
    }

    public TaskBreakdownStream onTaskAction(TaskActionConsumer consumer) {
        this.onTaskAction = consumer;
        return this;
    }

    public TaskBreakdownStream onTaskObservation(TaskObservationConsumer consumer) {
        this.onTaskObservation = consumer;
        return this;
    }

    public TaskBreakdownStream onTaskComplete(TaskCompleteConsumer consumer) {
        this.onTaskComplete = consumer;
        return this;
    }

    public TaskBreakdownStream onTaskFailed(TaskFailedConsumer consumer) {
        this.onTaskFailed = consumer;
        return this;
    }

    public TaskBreakdownStream onTaskCancelled(TaskCancelledConsumer consumer) {
        this.onTaskCancelled = consumer;
        return this;
    }

    public TaskBreakdownStream onSummaryToken(TokenConsumer consumer) {
        this.onSummaryToken = consumer;
        return this;
    }

    public TaskBreakdownStream onSummaryReasoning(ReasoningConsumer consumer) {
        this.onSummaryReasoning = consumer;
        return this;
    }

    /** HITL 暂停回调（子任务执行中 askUser 拦截） */
    public TaskBreakdownStream onAskUser(AskUserConsumer consumer) {
        this.onAskUser = consumer;
        return this;
    }

    /** 技能激活回调（子任务执行中 loadSkill 拦截，agent-skill） */
    public TaskBreakdownStream onSkillActivated(com.agentdemo.agent.core.HitlTokenStream.SkillActivatedConsumer consumer) {
        this.onSkillActivated = consumer;
        return this;
    }

    public TaskBreakdownStream onComplete(Runnable runnable) {
        this.onComplete = runnable;
        return this;
    }

    public TaskBreakdownStream onError(ErrorConsumer consumer) {
        this.onError = consumer;
        return this;
    }

    // ==================== 取消与启动 ====================

    /**
     * 取消编排（BUG 修复）
     * <p>
     * 业务含义：emitter 超时或客户端断开时，由 AgentController 调用此方法，
     * 通知异步线程停止后续子任务执行和总结阶段。正在进行的 LLM 调用无法中途打断，
     * 但会在当前 LLM 调用返回后立即退出循环。
     * </p>
     */
    public void cancel() {
        cancelled = true;
    }

    /**
     * 以外部注入的 tasks 启动拆解执行
     * <p>
     * 业务含义：统一模式拆解路径入口（自动拆解/强制拆解共用）。tasks 已在构造时注入，
     * 直接推送 onPlan 并进入执行。
     * </p>
     */
    public void start() {
        startWithTasks(this.tasks);
    }

    /**
     * 设置子任务列表并启动拆解执行
     * <p>
     * 业务含义：供 UnifiedChatStream 在规划判断后调用（规划已上移至 TaskPlanJudge）。
     * </p>
     *
     * @param tasks 子任务列表（非空，空列表应走直答路径由 UnifiedChatStream 处理）
     */
    public void startWithTasks(List<SubTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            log.warn("startWithTasks 收到空任务列表，跳过执行: sessionId={}", sessionId);
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }
        this.tasks = tasks;
        try {
            // 推送任务计划（AC-001）
            if (onPlan != null) {
                onPlan.accept(tasks);
            }

            // BUG 修复：emitter 超时/断开时取消后续执行
            if (cancelled) {
                log.info("任务拆解已取消，跳过执行: sessionId={}", sessionId);
                return;
            }

            // ===== 逐个子任务执行 =====
            boolean allSuccess = executeAllSubTasks(tasks, new ArrayList<>());

            if (!allSuccess) {
                // 子任务执行失败，不生成总结（AC-006）
                if (onComplete != null) {
                    onComplete.run();
                }
                return;
            }

            // BUG 修复：emitter 超时/断开时取消后续执行
            if (cancelled) {
                log.info("任务拆解已取消，跳过总结: sessionId={}", sessionId);
                return;
            }

            // ===== 总结 =====
            streamSummary();

            if (onComplete != null) {
                onComplete.run();
            }
        } catch (BreakdownPausedException e) {
            // 业务含义：子任务 askUser 拦截导致的暂停信号，编排在此中止
            // onAskUser 已在 executeAllSubTasks 内触发，onComplete 不触发（SSE 流由
            // ask_user + done 结束，等待用户回复后经 resumeFromPending 续跑）
            log.info("任务拆解暂停于子任务: sessionId={}, index={}", sessionId, e.getTaskIndex());
        } catch (Exception e) {
            log.error("任务拆解编排异常: sessionId={}", sessionId, e);
            if (onError != null) {
                onError.accept(e);
            }
        }
    }

    // ==================== 恢复入口 ====================

    /**
     * 从暂停点恢复拆解执行（用户回复后）
     * <p>
     * 业务含义：加载 pending（messages + 拆解上下文），追加用户回复 Observation，
     * 重放 onPlan + 已完成子任务 onTaskComplete（新 SSE 流重建进度视图，决策 5），
     * 续跑当前子任务与剩余子任务，最后总结。pending 缺失/损坏时降级为普通统一流程
     * （不抛异常，技术方案 3.3）。
     * </p>
     *
     * @param userReply 用户回复文本
     */
    public void resumeFromPending(String userReply) {
        PendingInteraction pending = humanInteractionManager.loadInteraction(sessionId);
        if (pending == null) {
            // 降级：pending 缺失，无法恢复，交由 UnifiedChatStream 走普通流程
            log.warn("resumeFromPending 失败：sessionId={} 无 pending 状态，降级普通流程", sessionId);
            throw new IllegalStateException("无 pending 拆解状态，需降级普通流程");
        }
        if (!PendingInteraction.MODE_BREAKDOWN.equals(pending.getMode())) {
            log.warn("resumeFromPending 失败：sessionId={} pending.mode={}，非 breakdown 模式", sessionId, pending.getMode());
            throw new IllegalStateException("pending 非 breakdown 模式，需降级普通流程");
        }

        List<SubTask> savedTasks = pending.getSubTasks();
        int currentTaskIndex = pending.getCurrentTaskIndex();
        List<String> subtaskResults = new ArrayList<>(pending.getSubtaskResults());

        // 业务含义：查找 askUser 工具调用 ID，将用户回复作为 Observation 追加到暂停时 ReAct 上下文
        List<ChatMessage> resumeMessages = new ArrayList<>(pending.getMessages());
        String toolCallId = findAskUserToolCallId(resumeMessages);
        resumeMessages.add(ToolExecutionResultMessage.from(toolCallId, "askUser", userReply));

        // 清除 pending（已恢复，防重复恢复）
        humanInteractionManager.clearInteraction(sessionId);

        log.info("恢复拆解执行: sessionId={}, currentTaskIndex={}, 已完成子任务数={}",
                sessionId, currentTaskIndex, subtaskResults.size());

        try {
            // 决策 5：重放 onPlan + 已完成子任务 onTaskComplete（新 SSE 流重建进度视图）
            if (onPlan != null) {
                onPlan.accept(savedTasks);
            }
            for (int i = 0; i < currentTaskIndex && i < savedTasks.size(); i++) {
                if (onTaskComplete != null) {
                    onTaskComplete.accept(savedTasks.get(i).index());
                }
            }

            // 续跑：从当前子任务（含用户回复 Observation）到最后一个子任务
            boolean allSuccess = executeAllSubTasks(savedTasks, currentTaskIndex,
                    subtaskResults, resumeMessages, pending.getRetryCount() + 1);

            if (!allSuccess) {
                if (onComplete != null) {
                    onComplete.run();
                }
                return;
            }

            if (cancelled) {
                log.info("拆解恢复后已取消，跳过总结: sessionId={}", sessionId);
                return;
            }

            streamSummary();

            if (onComplete != null) {
                onComplete.run();
            }
        } catch (BreakdownPausedException e) {
            // 恢复后再次暂停（子任务再次 askUser）
            log.info("拆解恢复后再次暂停: sessionId={}, index={}", sessionId, e.getTaskIndex());
        } catch (Exception e) {
            log.error("拆解恢复异常: sessionId={}", sessionId, e);
            if (onError != null) {
                onError.accept(e);
            }
        }
    }

    // ==================== Phase 2: 子任务执行 ====================

    /**
     * 执行所有子任务（首次启动）
     * <p>
     * 业务含义：遍历子任务列表，逐个子任务创建 HITLReActStream 执行 ReAct 循环
     * （委托而非复制拦截逻辑，决策 3）。askUser 拦截时 attachBreakdownContext 并抛出
     * BreakdownPausedException 中止编排。子任务失败时取消剩余子任务（AC-006）。
     * </p>
     *
     * @param tasks           子任务列表
     * @param subtaskResults  已完成子任务结果收集器（按序）
     * @return true=全部成功，false=有子任务失败
     */
    private boolean executeAllSubTasks(List<SubTask> tasks, List<String> subtaskResults) {
        return executeAllSubTasks(tasks, 0, subtaskResults, null, 0);
    }

    /**
     * 执行所有子任务（支持恢复起点与初始消息上下文）
     *
     * @param tasks            子任务列表
     * @param startIndex       开始执行的子任务 index（0-based，恢复时从 currentTaskIndex 起）
     * @param subtaskResults   已完成子任务结果收集器（按序，恢复时含前序结果）
     * @param initialMessages  初始消息上下文（首次=null 走记忆构建；恢复=pending.messages + 用户回复 Observation）
     * @param initialRetryCount 初始追问计数（恢复时 = pending.retryCount + 1）
     * @return true=全部成功，false=有子任务失败
     */
    private boolean executeAllSubTasks(List<SubTask> tasks, int startIndex,
                                       List<String> subtaskResults, List<ChatMessage> initialMessages,
                                       int initialRetryCount) {
        for (int i = startIndex; i < tasks.size(); i++) {
            SubTask task = tasks.get(i);

            // BUG 修复：emitter 超时/断开时取消剩余子任务
            if (cancelled) {
                log.info("任务拆解已取消，跳过剩余子任务: sessionId={}, currentIndex={}", sessionId, i);
                for (int j = i; j < tasks.size(); j++) {
                    if (onTaskCancelled != null) {
                        onTaskCancelled.accept(tasks.get(j).index());
                    }
                }
                return false;
            }

            // 推送子任务开始事件（AC-003: 状态 pending -> in-progress）
            if (onTaskStart != null) {
                onTaskStart.accept(task.index(), task.title());
            }

            try {
                // 业务含义：子任务委托 HITLReActStream 执行（含 askUser 拦截能力）。
                // 恢复时仅当前暂停子任务（i == startIndex）续用暂停时上下文（initialMessages），
                // 后续子任务走记忆构建（前序结果已写入会话记忆 + previousResults 拼接）。
                String result = executeSubTask(task, subtaskResults, i,
                        (i == startIndex) ? initialMessages : null, initialRetryCount);
                subtaskResults.add(result);

                // 将子任务结果写入会话记忆，供后续子任务和总结阶段获取上下文
                memoryManager.addUserMessage(sessionId, "子任务：" + task.title());
                memoryManager.addAssistantMessage(sessionId, result);

                // 推送子任务完成事件（AC-003: 状态 in-progress -> completed）
                if (onTaskComplete != null) {
                    onTaskComplete.accept(task.index());
                }
            } catch (BreakdownPausedException e) {
                // 业务含义：子任务 askUser 拦截暂停。已由 executeSubTask 内部完成
                // attachBreakdownContext（含 tasks/currentTaskIndex/已完成结果），
                // 此处向上抛出中止编排，onAskUser 已触发。
                throw e;
            } catch (Exception e) {
                log.error("子任务执行失败: index={}, title={}", task.index(), task.title(), e);
                if (onTaskFailed != null) {
                    onTaskFailed.accept(task.index(), e.getMessage());
                }

                // 取消剩余子任务（AC-006: 失败即停，后续标记已取消）
                for (int j = i + 1; j < tasks.size(); j++) {
                    if (onTaskCancelled != null) {
                        onTaskCancelled.accept(tasks.get(j).index());
                    }
                }
                return false;
            }
        }

        return true;
    }

    /**
     * 执行单个子任务（委托 HITLReActStream）
     * <p>
     * 业务含义：构造子任务消息（task-execute 场景系统提示词 + 历史记忆 + 子任务描述/恢复上下文），
     * 创建 HITLReActStream 并适配回调：onPartialThinking -> onTaskReasoning、
     * onPartialResponse -> onTaskToken、onPartialThought -> onTaskThought、
     * onAction/onObservation -> onTaskAction/onTaskObservation、onComplete -> 返回结果。
     * askUser 拦截时 HITLReActStream 保存 ReAct 上下文（mode 未知），本方法在 onAskUser 回调中
     * attachBreakdownContext 补充拆解上下文并抛出 BreakdownPausedException 中止编排。
     * </p>
     *
     * @param task             子任务
     * @param previousResults  之前子任务结果列表
     * @param taskIndex        当前子任务在任务列表中的 index（0-based，用于暂停上下文记录）
     * @param initialMessages  初始消息上下文（首次=null 走记忆构建；恢复=pending.messages + 用户回复 Observation）
     * @param retryCount       初始追问计数
     * @return 子任务执行结果文本
     */
    private String executeSubTask(SubTask task, List<String> previousResults, int taskIndex,
                                  List<ChatMessage> initialMessages, int retryCount) {
        // 业务含义：按 modelId 选择思考流式模型，null 时使用默认模型
        ThinkingStreamingChatModel thinkingModel = (modelId != null)
                ? modelFactory.getThinkingStreamingChatModelByModelId(modelId)
                : modelFactory.getDefaultThinkingStreamingChatModel();

        // 构造系统提示词：task-execute 场景 + 工具描述（{{tools}} 占位符运行时替换，BR-AGT-009）
        // agent-context-engineering Task-10（AC-N01/T02）：{{tools}} 文本来自会话基础工具集
        // （冻结契约），技能目录/激活段已移出系统提示词（改为记忆流附件，emit-once）
        String toolsText = (sessionToolResolver != null)
                ? toolSchemaConverter.convertToDescriptionText(
                        sessionToolResolver.resolveSessionBaseTools(sessionId, null))
                : toolSchemaConverter.convertToDescriptionText(tools);
        String systemPrompt = promptTemplateLoader.composeSystemPrompt(PromptTemplateLoader.SCENARIO_TASK_EXECUTE)
                .replace("{{tools}}", toolsText);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        // 注入历史记忆（多轮上下文）
        messages.addAll(memoryManager.getMemory(sessionId).messages());

        // 构造子任务描述（包含之前子任务结果，提供上下文连贯性）
        StringBuilder userMessage = new StringBuilder();
        userMessage.append("子任务：").append(task.title());
        if (!previousResults.isEmpty()) {
            userMessage.append("\n之前子任务结果：\n");
            for (int i = 0; i < previousResults.size(); i++) {
                userMessage.append(i + 1).append(". ").append(previousResults.get(i)).append("\n");
            }
        }
        messages.add(UserMessage.from(userMessage.toString()));

        // 业务含义：恢复执行时（当前子任务）直接续用暂停时上下文（含用户回复 Observation），
        // 不重建消息列表，保证上下文无损（AC-M01/M02）
        if (initialMessages != null) {
            messages = initialMessages;
        }

        int maxIterations = agentConfig.getTaskExecutionMaxIterations();

        final String[] resultHolder = {""};
        final boolean[] paused = {false};

        HITLReActStream hitlStream = new HITLReActStream(
                thinkingModel,
                messages,
                toolsJson,
                toolExecutor,
                humanInteractionManager,
                sessionId,
                modelId,
                retryCount,
                maxIterations,
                skillToolInterceptor);

        // 回调适配：HITL 事件 -> task_* 事件（技术方案 1.6.4）
        hitlStream.onPartialThinking(thinking -> {
            // 统一模式恒开启思考，task_reasoning 无条件推送（原 enableThinking 删除）
            if (onTaskReasoning != null) {
                onTaskReasoning.accept(task.index(), thinking);
            }
        });
        hitlStream.onPartialResponse(token -> {
            if (onTaskToken != null) {
                onTaskToken.accept(task.index(), token);
            }
            resultHolder[0] = resultHolder[0] + token;
        });
        hitlStream.onPartialThought((content, iteration) -> {
            // 工具轮 content 归类为 task_thought（技术方案 11.1 风险 5 知悉的行为差异）
            if (onTaskThought != null) {
                onTaskThought.accept(task.index(), content, iteration);
            }
        });
        hitlStream.onAction((toolName, args, iteration) -> {
            if (onTaskAction != null) {
                onTaskAction.accept(task.index(), toolName, args, iteration);
            }
        });
        hitlStream.onObservation((result, iteration) -> {
            if (onTaskObservation != null) {
                onTaskObservation.accept(task.index(), result, iteration);
            }
        });
        hitlStream.onAskUser((type, question, options, askRetryCount) -> {
            // 业务含义：子任务 askUser 拦截暂停。HITLReActStream 已 saveInteraction（ReAct 上下文），
            // 此处 attachBreakdownContext 补充拆解上下文（决策 4：pending 附加），并触发 onAskUser
            humanInteractionManager.attachBreakdownContext(sessionId, tasks, taskIndex, previousResults);
            if (onAskUser != null) {
                onAskUser.accept(type, question, options, askRetryCount);
            }
            // 业务含义：标记暂停。不能在回调内抛异常（HITLReActStream.start() 会捕获并触发
            // 其 onError，导致暂停信号被吞没/包装），改在 start() 返回后检测 paused 标志再抛
            paused[0] = true;
        });
        hitlStream.onSkillActivated((skillId, skillName, source, boundToolIds) -> {
            // 业务含义：子任务 loadSkill 激活事件透传（agent-skill，AC-S04）
            if (onSkillActivated != null) {
                onSkillActivated.accept(skillId, skillName, source, boundToolIds);
            }
        });
        hitlStream.onComplete(response -> {
            resultHolder[0] = response;
        });
        hitlStream.onError(error -> {
            throw new RuntimeException("子任务 ReAct 执行失败: " + error.getMessage(), error);
        });

        hitlStream.start();

        // 业务含义：HITLReActStream.start() 内部 askUser 拦截后暂停循环并 return（不触发 onComplete）。
        // 通过 paused 标志（onAskUser 回调中置位）判定暂停，抛 BreakdownPausedException 中止拆解编排。
        if (paused[0]) {
            throw new BreakdownPausedException(taskIndex);
        }
        return resultHolder[0];
    }

    /**
     * 查找最后一个 askUser 工具调用的 ID
     * <p>
     * 业务含义：ToolExecutionResultMessage 需匹配原始 ToolExecutionRequest 的 ID，
     * 否则 LLM 无法正确关联工具结果。
     * </p>
     *
     * @param messages 消息列表
     * @return 工具调用 ID（未找到时返回 "askUser"）
     */
    private String findAskUserToolCallId(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage msg = messages.get(i);
            if (msg instanceof AiMessage aiMsg && aiMsg.hasToolExecutionRequests()) {
                for (dev.langchain4j.agent.tool.ToolExecutionRequest req : aiMsg.toolExecutionRequests()) {
                    if ("askUser".equals(req.name())) {
                        return req.id();
                    }
                }
            }
        }
        return "askUser";
    }

    // ==================== Phase 3: 总结 ====================

    /**
     * 总结阶段：流式输出最终总结
     * <p>
     * 业务含义：所有子任务完成后，调用 LLM 流式生成最终总结（AC-004）。
     * 总结消息包含总结提示词 + 会话记忆（含子任务执行结果）。
     * </p>
     */
    private void streamSummary() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(promptTemplateLoader.composeSystemPrompt(PromptTemplateLoader.SCENARIO_TASK_SUMMARY)));
        // 历史记忆中已包含用户消息和各子任务执行结果
        messages.addAll(memoryManager.getMemory(sessionId).messages());

        // 业务含义：按 modelId 选择思考流式模型，null 时使用默认模型
        ThinkingStreamingChatModel thinkingModel = (modelId != null)
                ? modelFactory.getThinkingStreamingChatModelByModelId(modelId)
                : modelFactory.getDefaultThinkingStreamingChatModel();
        final Throwable[] errorHolder = {null};

        ThinkingStreamHandler handler = new ThinkingStreamHandler() {
            @Override
            public void onPartialThinking(String thinking) {
                // 统一模式恒开启思考，summary reasoning 无条件推送
                if (onSummaryReasoning != null) {
                    onSummaryReasoning.accept(thinking);
                }
            }

            @Override
            public void onPartialResponse(String token) {
                if (onSummaryToken != null) {
                    onSummaryToken.accept(token);
                }
            }

            @Override
            public void onToolCalls(List<ToolCall> toolCalls) {
                // 总结阶段不需要工具调用
            }

            @Override
            public void onComplete(String fullResponse, String finishReason, TokenUsage tokenUsage) {
                log.info("总结输出完成: sessionId={}, finishReason={}", sessionId, finishReason);
            }

            @Override
            public void onError(Throwable error) {
                log.error("总结输出异常: sessionId={}", sessionId, error);
                errorHolder[0] = error;
            }
        };

        // 不带 tools 参数调用 LLM
        thinkingModel.stream(messages, null, handler);

        if (errorHolder[0] != null) {
            throw new RuntimeException("总结输出失败: " + errorHolder[0].getMessage(), errorHolder[0]);
        }
    }

    /**
     * 暂停信号（内部异常）
     * <p>
     * 业务含义：子任务 askUser 拦截后中止拆解编排的信号。attachBreakdownContext 已在
     * executeSubTask 的 askUser 回调内完成，onAskUser 已触发，编排在此终止等待用户回复。
     * </p>
     */
    static class BreakdownPausedException extends RuntimeException {
        private final int taskIndex;

        BreakdownPausedException(int taskIndex) {
            super("任务拆解暂停于子任务 index=" + taskIndex);
            this.taskIndex = taskIndex;
        }

        int getTaskIndex() {
            return taskIndex;
        }
    }

    // ==================== 回调接口定义 ====================

    /** 规划完成回调（携带子任务列表） */
    @FunctionalInterface
    public interface PlanConsumer {
        void accept(List<SubTask> tasks);
    }

    /** 子任务开始回调 */
    @FunctionalInterface
    public interface TaskStartConsumer {
        void accept(int index, String title);
    }

    /** 子任务内容片段回调 */
    @FunctionalInterface
    public interface TaskTokenConsumer {
        void accept(int index, String content);
    }

    /** 子任务推理片段回调 */
    @FunctionalInterface
    public interface TaskReasoningConsumer {
        void accept(int index, String content);
    }

    /** 子任务 ReAct 思考回调 */
    @FunctionalInterface
    public interface TaskThoughtConsumer {
        void accept(int index, String content, int iteration);
    }

    /** 子任务工具调用回调 */
    @FunctionalInterface
    public interface TaskActionConsumer {
        void accept(int index, String toolName, String args, int iteration);
    }

    /** 子任务工具结果回调 */
    @FunctionalInterface
    public interface TaskObservationConsumer {
        void accept(int index, String result, int iteration);
    }

    /** 子任务完成回调 */
    @FunctionalInterface
    public interface TaskCompleteConsumer {
        void accept(int index);
    }

    /** 子任务失败回调 */
    @FunctionalInterface
    public interface TaskFailedConsumer {
        void accept(int index, String error);
    }

    /** 子任务取消回调 */
    @FunctionalInterface
    public interface TaskCancelledConsumer {
        void accept(int index);
    }

    /** 总结文本片段回调 */
    @FunctionalInterface
    public interface TokenConsumer {
        void accept(String token);
    }

    /** 总结推理片段回调 */
    @FunctionalInterface
    public interface ReasoningConsumer {
        void accept(String reasoning);
    }

    /** HITL 暂停回调 */
    @FunctionalInterface
    public interface AskUserConsumer {
        void accept(String type, String question, List<String> options, int retryCount);
    }

    /** 异常回调 */
    @FunctionalInterface
    public interface ErrorConsumer {
        void accept(Throwable error);
    }
}
