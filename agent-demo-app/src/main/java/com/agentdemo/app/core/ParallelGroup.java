package com.agentdemo.app.core;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 并行分组定义
 * <p>
 * 业务含义：并行编排（PARALLEL）中一个分组的定义，组内多个 Agent 串行执行，组间并行执行（AC-004）。
 * </p>
 */
@Data
@Builder
public class ParallelGroup {
    private String name;
    private List<AgentDefinition> agents;
}
