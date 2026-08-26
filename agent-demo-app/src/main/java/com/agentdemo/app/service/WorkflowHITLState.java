package com.agentdemo.app.service;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * 工作流 HITL 暂停状态快照（工作流 HITL）
 * <p>
 * 业务含义：工作流执行中 Agent 暂停等待用户回复时保存的"小本本"——
 * 记录暂停模式（askUser/checkpoint/toolConfirm）、提问数据或工具确认数据、暂停步骤位置、
 * Agent 消息列表与追问计数。用户回复后据此恢复执行：askUser 模式注入回复为 Observation 续跑 ReAct，
 * checkpoint 模式确认后执行暂停的方法，toolConfirm 模式批准后执行待确认工具并回填结果（AC-N03/AC-M01/AC-M02）。
 * </p>
 * <p>
 * 存储位置：挂载于 {@link ResumableExecutionState#hitlState}，随恢复成功/终止/超时清理。
 * messages 仅 askUser/toolConfirm 模式填充（checkpoint 模式在方法执行前暂停，无 ReAct 上下文）。
 * </p>
 */
public class WorkflowHITLState {

    /** HITL 暂停模式：Agent 主动调用 askUser 工具追问 */
    public static final String MODE_ASK_USER = "askUser";

    /** HITL 暂停模式：模板预设检查点（@HumanCheckpoint 注解方法执行前） */
    public static final String MODE_CHECKPOINT = "checkpoint";

    /** HITL 暂停模式：ask 级工具调用被权限拦截，等待用户批准/拒绝（与前端 waitingHitlMode='toolConfirm' 契约一致） */
    public static final String MODE_TOOL_CONFIRM = "toolConfirm";

    /** HITL 暂停模式 */
    private final String hitlMode;

    /** 提问数据（type/question/options/retryCount；toolConfirm/checkpoint 模式为 null） */
    private final AskUserData askUserData;

    /** 工具确认数据（toolCallId/toolName/toolDescription/arguments；askUser/checkpoint 模式为 null） */
    private final ToolConfirmData toolConfirmData;

    /** 暂停步骤位置（agentIndex/agentName/input/iteration） */
    private final PendingStep pendingStep;

    /** Agent 消息列表（仅 askUser/toolConfirm 模式：ReAct 上下文，恢复时续跑；checkpoint 模式为空） */
    private final List<ChatMessage> messages;

    /** 当前连续追问次数（同一次 HITL 暂停-恢复周期的累计计数） */
    private final int retryCount;

    public WorkflowHITLState(String hitlMode, AskUserData askUserData, PendingStep pendingStep,
                             List<ChatMessage> messages, int retryCount) {
        this(hitlMode, askUserData, null, pendingStep, messages, retryCount);
    }

    /**
     * toolConfirm 模式构造器（决策 2 方案 A：工作流宿主在 onToolConfirm 回调内构建快照）
     */
    public WorkflowHITLState(String hitlMode, ToolConfirmData toolConfirmData, PendingStep pendingStep,
                             List<ChatMessage> messages, int retryCount) {
        this(hitlMode, null, toolConfirmData, pendingStep, messages, retryCount);
    }

    private WorkflowHITLState(String hitlMode, AskUserData askUserData, ToolConfirmData toolConfirmData,
                              PendingStep pendingStep, List<ChatMessage> messages, int retryCount) {
        this.hitlMode = hitlMode;
        this.askUserData = askUserData;
        this.toolConfirmData = toolConfirmData;
        this.pendingStep = pendingStep;
        this.messages = messages;
        this.retryCount = retryCount;
    }

    public String getHitlMode() {
        return hitlMode;
    }

    public AskUserData getAskUserData() {
        return askUserData;
    }

    public ToolConfirmData getToolConfirmData() {
        return toolConfirmData;
    }

    public PendingStep getPendingStep() {
        return pendingStep;
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    public int getRetryCount() {
        return retryCount;
    }

    /**
     * askUser 提问数据
     * <p>
     * 业务含义：Agent 暂停时向用户展示的问题——type 区分开放式追问（text）与确认型交互（confirm），
     * options 为 confirm 类型的选项列表（2-4 个），text 类型为空列表。
     * </p>
     */
    public static class AskUserData {

        /** 提问类型：text（开放式追问）或 confirm（确认型交互） */
        private final String type;

        /** 问题文本 */
        private final String question;

        /** 选项列表（confirm 类型 2-4 个，text 类型为空列表） */
        private final List<String> options;

        /** 当前追问计数 */
        private final int retryCount;

        public AskUserData(String type, String question, List<String> options, int retryCount) {
            this.type = type;
            this.question = question;
            this.options = options;
            this.retryCount = retryCount;
        }

        public String getType() {
            return type;
        }

        public String getQuestion() {
            return question;
        }

        public List<String> getOptions() {
            return options;
        }

        public int getRetryCount() {
            return retryCount;
        }
    }

    /**
     * 工具确认数据（Task-12 新增）
     * <p>
     * 业务含义：ask 级工具被权限拦截暂停时保存的四要素——toolCallId 为恢复回填
     * ToolExecutionResultMessage 的 id 匹配必需（AC-M02），toolName/toolArguments 供批准后
     * 直接执行（不重复解析），toolDescription 供前端确认卡片展示（AC-H01/AC-H02）。
     * </p>
     */
    public static class ToolConfirmData {

        /** 被拦截工具调用的 toolCall id（恢复时 ToolExecutionResultMessage 需与 id 匹配） */
        private final String toolCallId;

        /** 待确认工具方法名（@Tool 方法名，批准后经 ToolExecutor 执行） */
        private final String toolName;

        /** 工具描述（确认卡片展示） */
        private final String toolDescription;

        /** 待确认工具参数 JSON（原样保存，批准后直接执行） */
        private final String arguments;

        public ToolConfirmData(String toolCallId, String toolName, String toolDescription, String arguments) {
            this.toolCallId = toolCallId;
            this.toolName = toolName;
            this.toolDescription = toolDescription;
            this.arguments = arguments;
        }

        public String getToolCallId() {
            return toolCallId;
        }

        public String getToolName() {
            return toolName;
        }

        public String getToolDescription() {
            return toolDescription;
        }

        public String getArguments() {
            return arguments;
        }
    }

    /**
     * HITL 恢复上下文（Task-08）
     * <p>
     * 业务含义：用户回复后由协调层（hitlReply）写入 ctx 的恢复 key（hitl:{iteration}:{agentName}），
     * 携带暂停快照 + 用户回复 + 确认结果。策略重放时 executeOrSkip/Supervisor 检测到该 key，
     * 据此以恢复方式执行暂停步（askUser 注入回复续跑 ReAct / checkpoint 确认后执行方法），
     * 避免"重放时重新触发 HITL"的死循环（AC-N03/AC-S01）。
     * </p>
     */
    public static class HitlResume {

        /** 暂停时的 HITL 快照（含消息列表/提问数据/暂停步骤） */
        private final WorkflowHITLState hitlState;

        /** 用户回复文本（askUser 模式：注入 ReAct 循环作为 Observation；checkpoint 模式为 null） */
        private final String message;

        /** 检查点确认结果（checkpoint 模式：true=执行方法；false=拒绝终止；askUser 模式为 null） */
        private final Boolean approved;

        public HitlResume(WorkflowHITLState hitlState, String message, Boolean approved) {
            this.hitlState = hitlState;
            this.message = message;
            this.approved = approved;
        }

        public WorkflowHITLState getHitlState() {
            return hitlState;
        }

        public String getMessage() {
            return message;
        }

        public Boolean getApproved() {
            return approved;
        }
    }

    /**
     * 暂停步骤位置
     * <p>
     * 业务含义：定位 HITL 暂停发生在工作流的哪个 Agent 步骤——agentIndex 对应步骤记录序号，
     * iteration 为循环模式轮次（非循环为 0），input 为该步骤的输入（checkpoint 恢复时重放需要）。
     * </p>
     */
    public static class PendingStep {

        /** 步骤索引（对应 StepExecution.index） */
        private final int agentIndex;

        /** Agent 名称（对应 AgentDefinition.name） */
        private final String agentName;

        /** 该步骤的输入（checkpoint 恢复执行时作为用户消息） */
        private final String input;

        /** 循环模式轮次（非循环为 0） */
        private final int iteration;

        public PendingStep(int agentIndex, String agentName, String input, int iteration) {
            this.agentIndex = agentIndex;
            this.agentName = agentName;
            this.input = input;
            this.iteration = iteration;
        }

        public int getAgentIndex() {
            return agentIndex;
        }

        public String getAgentName() {
            return agentName;
        }

        public String getInput() {
            return input;
        }

        public int getIteration() {
            return iteration;
        }
    }
}
