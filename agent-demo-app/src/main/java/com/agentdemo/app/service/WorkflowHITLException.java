package com.agentdemo.app.service;

/**
 * 工作流 HITL 暂停异常（工作流 HITL）
 * <p>
 * 业务含义：AgentExecutor 拦截到 HITL 暂停信号（askUser 工具调用或 @HumanCheckpoint 检查点）
 * 后抛出的"等待用户输入"信号——协调层捕获后进入 WAITING_USER 状态并保存 HITL 快照，
 * 而非宣告 FAILED 终态。携带完整的 HITL 状态快照供恢复使用。
 * </p>
 * <p>
 * 兼容性：继承 {@link WorkflowPausedException}，复用 failedAgentName/failedIndex 语义
 * （暂停步骤的 Agent 名与索引），协调层 catch (WorkflowPausedException) 分支在捕获顺序上
 * 必须位于本异常之后（子类优先），保证 P3 的 PAUSED 流程零回归。
 * </p>
 */
public class WorkflowHITLException extends WorkflowPausedException {

    /** HITL 暂停状态快照（模式/提问数据/暂停步骤/消息列表/追问计数） */
    private final WorkflowHITLState hitlState;

    public WorkflowHITLException(WorkflowHITLState hitlState, String message, Throwable cause) {
        super(hitlState.getPendingStep().getAgentName(),
                hitlState.getPendingStep().getAgentIndex(), message, cause);
        this.hitlState = hitlState;
    }

    public WorkflowHITLState getHitlState() {
        return hitlState;
    }
}
