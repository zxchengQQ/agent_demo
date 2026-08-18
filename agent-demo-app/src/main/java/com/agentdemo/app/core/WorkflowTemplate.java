package com.agentdemo.app.core;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 工作流模板
 * <p>
 * 业务含义：描述一个工作流的完整定义，包含编排模式、Agent 列表、参数定义和重试次数。
 * </p>
 */
@Data
@Builder
public class WorkflowTemplate {
    private String id;
    private String name;
    private String description;
    private OrchestrationMode mode;
    private List<AgentDefinition> agents;
    private List<ParameterDefinition> parameters;
    private int maxRetries;

    // ===== P2 新增（按 mode 选择使用，互斥）=====
    /** PARALLEL 模式：并行分组列表 */
    private List<ParallelGroup> parallelGroups;

    /** CONDITIONAL 模式：条件分支列表 */
    private List<BranchDefinition> branches;

    /** LOOP 模式：循环定义 */
    private LoopDefinition loop;

    // ===== P3 新增 =====
    /** SUPERVISOR 模式：层级编排定义（主控拆解 + Worker 池 + 主控汇总，与上述结构互斥） */
    private SupervisorDefinition supervisor;
}
