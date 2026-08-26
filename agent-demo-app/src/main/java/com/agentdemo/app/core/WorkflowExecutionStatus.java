package com.agentdemo.app.core;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 工作流执行状态
 */
@Getter
@AllArgsConstructor
public enum WorkflowExecutionStatus {
    PENDING("等待执行"),
    RUNNING("执行中"),
    COMPLETED("已完成"),
    FAILED("执行失败"),
    TERMINATED("已终止"),
    TIMEOUT("执行超时"),
    /** P3 新增：Agent 重试耗尽后暂停（非终态，可从断点恢复，AC-016） */
    PAUSED("已暂停（可恢复）"),
    /** 工作流 HITL 新增：等待用户输入（非终态，用户回复恢复或主动终止，AC-N01/N02/N03） */
    WAITING_USER("等待用户输入");

    private final String description;
}
