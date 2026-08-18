package com.agentdemo.app.core;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Agent 定义
 * <p>
 * 业务含义：描述工作流中单个 Agent 的配置，包括模型、工具、提示词场景和接口类型。
 * 工具在模板中预定义不可修改（AC-025）。
 * </p>
 */
@Data
@Builder
public class AgentDefinition {
    private String name;
    private String description;
    private String modelId;
    private List<String> toolIds;
    private String roleName;
    private String scenarioName;
    private Class<?> interfaceClass;
}
