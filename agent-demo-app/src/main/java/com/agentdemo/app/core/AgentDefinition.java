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

    /**
     * 是否启用 HITL 显式 ReAct（工作流 HITL）
     * <p>
     * 业务含义：模板定义 true 时，AgentExecutor 对该 Agent 的 askUser 场景使用
     * HITLReActStream（显式 ReAct，支持暂停-恢复）；false（默认）走现有 TokenStream（零回归）。
     * @HumanCheckpoint 注解检测独立于本字段（AC-N02）。
     * </p>
     */
    @Builder.Default
    private boolean hitlEnabled = false;
}
