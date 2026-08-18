package com.agentdemo.app.core;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.function.Predicate;

/**
 * 循环定义
 * <p>
 * 业务含义：循环编排（LOOP）的定义，包含循环体 Agent、最大迭代次数与退出条件（AC-006/AC-029）。
 * maxIterations 必须配置，防止无限循环（BR-APP-014）。
 * </p>
 * <p>
 * exitCondition 为 Java 运行时对象无法 JSON 序列化，统一标注 @JsonIgnore；
 * 前端仅展示 exitConditionDescription 描述文本。
 * </p>
 */
@Data
@Builder
public class LoopDefinition {
    /** 最大迭代次数，必须配置（BR-APP-014，AC-029） */
    private int maxIterations;

    /** 前端展示用退出条件描述（如"评分 ≥ 90 时退出"） */
    private String exitConditionDescription;

    /** 运行时退出条件谓词，不参与 JSON 序列化 */
    @JsonIgnore
    private Predicate<WorkflowContext> exitCondition;

    /** 循环体 Agent 列表 */
    private List<AgentDefinition> agents;
}
