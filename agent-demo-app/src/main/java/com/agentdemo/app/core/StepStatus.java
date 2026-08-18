package com.agentdemo.app.core;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 步骤执行状态
 */
@Getter
@AllArgsConstructor
public enum StepStatus {
    PENDING("等待执行"),
    RUNNING("执行中"),
    COMPLETED("已完成"),
    FAILED("执行失败");

    private final String description;
}
