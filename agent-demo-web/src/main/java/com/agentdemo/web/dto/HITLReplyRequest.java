package com.agentdemo.web.dto;

import lombok.Data;

/**
 * HITL 回复请求 DTO（工作流 HITL，Task-10）
 * <p>
 * 业务含义：用户回复工作流中 Agent 的提问（askUser 模式）或确认检查点（checkpoint 模式）的请求体。
 * message 与 approved 至少一个非 null——askUser 模式携带 message（用户回复文本），
 * checkpoint 模式携带 approved（确认结果 true=执行 / false=拒绝）。空请求（两者均 null）
 * 无意义，由 Controller 校验抛 WORKFLOW_PARAM_MISSING（AC-N03/AC-S01）。
 * </p>
 */
@Data
public class HITLReplyRequest {

    /** 用户回复文本（askUser 模式使用；checkpoint 模式为 null） */
    private String message;

    /** 检查点确认结果（checkpoint 模式：true=执行 / false=拒绝；askUser 模式为 null） */
    private Boolean approved;
}
