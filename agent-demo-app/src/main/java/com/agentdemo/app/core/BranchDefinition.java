package com.agentdemo.app.core;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.function.Predicate;

/**
 * 条件分支定义
 * <p>
 * 业务含义：条件分支编排（CONDITIONAL）中一个分支的定义，包含运行时评估的 condition 谓词
 * 与前端展示用的 conditionDescription 描述文本（AC-005）。
 * </p>
 * <p>
 * condition 为 Java 运行时对象无法 JSON 序列化，统一标注 @JsonIgnore；
 * 前端仅展示 conditionDescription 文本（BR-APP-015）。
 * </p>
 */
@Data
@Builder
public class BranchDefinition {
    private String name;

    /** 前端展示用条件描述（如"问题复杂，需多 Agent 拆解"） */
    private String conditionDescription;

    /** 运行时评估谓词，不参与 JSON 序列化 */
    @JsonIgnore
    private Predicate<WorkflowContext> condition;

    /** 命中该分支后执行的 Agent 序列 */
    private List<AgentDefinition> agents;
}
