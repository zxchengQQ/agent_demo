package com.agentdemo.app.core;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Supervisor 层级编排定义（P3 新增，AC-007）
 * <p>
 * 业务含义：工作流模板"设计图"上的组织架构——主控 Agent（拆解+汇总）+
 * Worker 池（主控指定的子任务执行者）+ 子任务上限。
 * BR-APP-012：主控用强模型（拆解/汇总质量），Worker 用快模型（执行成本）。
 * </p>
 */
@Data
@Builder
public class SupervisorDefinition {

    /** 主控-拆解 Agent（输入原始任务，输出子任务 JSON 数组） */
    private AgentDefinition planAgent;

    /** Worker 池：子任务路由目标（按 name 精确/包含匹配） */
    private List<AgentDefinition> workers;

    /** 主控-汇总 Agent（输入全部子任务结果，输出综合报告） */
    private AgentDefinition summarizeAgent;

    /** 子任务数量上限（防 LLM 拆解失控，默认 5，超出截断） */
    private int maxSubtasks;
}
