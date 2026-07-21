package com.agentdemo.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Agent 类型枚举
 * <p>
 * 业务含义：标识 Agent 的协作模式，用于路由到不同的 Agent 实现。
 * </p>
 */
@Getter
@AllArgsConstructor
public enum AgentType {

    /**
     * 单 Agent：一个 Agent 独立完成任务，具备 ReAct 循环与工具调用能力
     */
    SINGLE("SINGLE", "单 Agent"),

    /**
     * 多 Agent：多个角色 Agent 协作完成任务（如研究员+作者+审核员）
     */
    MULTI("MULTI", "多 Agent 协作"),

    /**
     * 工作流 Agent：基于状态机编排，含分支、重试、Human-in-the-loop
     */
    WORKFLOW("WORKFLOW", "工作流 Agent");

    private final String code;
    private final String desc;
}
