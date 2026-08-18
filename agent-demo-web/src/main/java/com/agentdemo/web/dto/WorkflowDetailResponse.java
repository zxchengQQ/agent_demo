package com.agentdemo.web.dto;

import com.agentdemo.app.core.ParameterDefinition;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 工作流模板详情响应 DTO（详情页，含 Agent 配置 + P2 新模式结构）
 * <p>
 * P2 新增：parallelGroups/branches/loop 分别承载并行/条件/循环模板的编排结构（AC-002）。
 * 条件分支的 Predicate 为运行时对象不参与序列化，仅暴露 conditionDescription 描述文本（BR-APP-015）。
 * </p>
 */
@Data
@Builder
public class WorkflowDetailResponse {

    private String id;
    private String name;
    private String description;
    private String mode;
    private int maxRetries;
    private List<AgentItem> agents;
    private List<ParameterDefinition> parameters;

    // ===== P2 新增（按 mode 使用，其余为 null）=====
    /** PARALLEL 模式：并行分组列表 */
    private List<ParallelGroupItem> parallelGroups;

    /** CONDITIONAL 模式：条件分支列表 */
    private List<BranchItem> branches;

    /** LOOP 模式：循环定义 */
    private LoopItem loop;

    // ===== P3 新增 =====
    /** SUPERVISOR 模式：层级编排定义（主控拆解 + Worker 池 + 主控汇总，AC-002/AC-007） */
    private SupervisorItem supervisor;

    @Data
    @Builder
    public static class AgentItem {
        private String name;
        private String description;
        private String modelId;
        private List<String> tools;
    }

    /** 并行分组项（AC-004） */
    @Data
    @Builder
    public static class ParallelGroupItem {
        private String name;
        private List<AgentItem> agents;
    }

    /** 条件分支项（Predicate 不序列化，仅展示描述，AC-005/BR-APP-015） */
    @Data
    @Builder
    public static class BranchItem {
        private String name;
        private String conditionDescription;
        private List<AgentItem> agents;
    }

    /** 循环定义项（AC-006/AC-029） */
    @Data
    @Builder
    public static class LoopItem {
        private int maxIterations;
        private String exitConditionDescription;
        private List<AgentItem> agents;
    }

    /** Supervisor 层级编排项（P3 新增，AC-007/AC-031：主控 pro 强模型 + Worker lite 快模型） */
    @Data
    @Builder
    public static class SupervisorItem {
        private int maxSubtasks;
        private AgentItem planAgent;
        private List<AgentItem> workers;
        private AgentItem summarizeAgent;
    }
}
