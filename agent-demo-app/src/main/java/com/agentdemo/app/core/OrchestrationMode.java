package com.agentdemo.app.core;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 工作流编排模式
 */
@Getter
@AllArgsConstructor
public enum OrchestrationMode {
    SEQUENTIAL("串行编排"),
    PARALLEL("并行编排"),
    CONDITIONAL("条件分支"),
    LOOP("循环编排"),
    SUPERVISOR("层级编排");

    private final String description;
}
