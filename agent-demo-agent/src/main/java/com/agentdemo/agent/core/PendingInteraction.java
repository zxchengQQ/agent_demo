package com.agentdemo.agent.core;

import dev.langchain4j.data.message.ChatMessage;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * HITL 暂停交互状态
 * <p>
 * 业务含义：Agent 调用 askUser 工具暂停执行时，将当前 ReAct 上下文保存为此对象。
 * 用户回复后，通过此对象恢复执行--加载消息列表、添加用户回复为 Observation、继续 ReAct 循环。
 * </p>
 * <p>
 * unified-chat-mode 扩展：新增 mode 与拆解上下文字段，使同一 pending 对象可承载
 * "直接对话暂停"与"拆解子任务暂停"两种形态（技术方案决策 4：pending 附加而非独立存储，
 * 复用单会话单条约束与超时清理机制）。
 * </p>
 * <p>
 * 调用方：HITLReActStream（保存）、TaskBreakdownStream（attachBreakdownContext）、
 * UnifiedChatStream/PlanAgent（按 mode 恢复路由）
 * </p>
 */
@Data
public class PendingInteraction {

    /** 暂停模式：直接对话路径（默认） */
    public static final String MODE_DIRECT = "direct";

    /** 暂停模式：任务拆解子任务执行路径 */
    public static final String MODE_BREAKDOWN = "breakdown";

    /** 暂停模式：权限确认路径（ask 级工具调用需用户批准/拒绝，技术方案 §4.2） */
    public static final String MODE_TOOL_CONFIRM = "tool_confirm";

    /** 暂停时的完整消息列表（含系统提示词、历史对话、ReAct 上下文） */
    private List<ChatMessage> messages;

    /** 提问类型：text（开放式追问）或 confirm（确认型交互） */
    private String askUserType;

    /** 问题文本 */
    private String question;

    /** 选项列表（confirm 类型必填，text 类型为 null） */
    private List<String> options;

    /** 当前连续追问次数（同一会话中连续 askUser 调用次数） */
    private int retryCount;

    /** 暂停时间戳（用于超时清理） */
    private long timestamp;

    /** 使用的模型 ID（恢复时需要） */
    private String modelId;

    /** 工具 JSON Schema（恢复时需要，避免重新计算） */
    private String toolsJson;

    /**
     * 暂停模式（direct/breakdown）
     * <p>
     * 业务含义：恢复路由依据--direct 恢复直答 ReAct 循环，breakdown 恢复子任务续跑。
     * 拆解子任务暂停时先由 HITLReActStream 保存 ReAct 上下文（此时 mode 未知），
     * 再由 TaskBreakdownStream 调用 attachBreakdownContext 补充拆解上下文与 mode。
     * </p>
     */
    private String mode = MODE_DIRECT;

    /** 拆解子任务列表（breakdown 模式：恢复时重放任务计划） */
    private List<SubTask> subTasks = new ArrayList<>();

    /** 暂停时正在执行的子任务 index（breakdown 模式：恢复从该子任务继续，0-based） */
    private int currentTaskIndex;

    /** 已完成子任务的结果列表（breakdown 模式：按序对应 subTasks 前缀，恢复时重放与上下文重建） */
    private List<String> subtaskResults = new ArrayList<>();

    /** 待确认工具调用的 toolCall id（tool_confirm 模式：恢复时 ToolExecutionResultMessage 需与 toolCall 的 id 匹配，AC-N03） */
    private String pendingToolCallId;

    /** 待确认工具名（tool_confirm 模式：@Tool 方法名，批准后经 ToolExecutor 执行） */
    private String pendingToolName;

    /** 待确认工具参数 JSON（tool_confirm 模式：原样保存 LLM 生成参数，批准后可直接执行，不重复解析） */
    private String pendingToolArguments;

    /**
     * 判断是否已过期
     *
     * @param timeoutMillis 超时时间（毫秒）
     * @return 是否过期
     */
    public boolean isExpired(long timeoutMillis) {
        return System.currentTimeMillis() - timestamp > timeoutMillis;
    }
}
