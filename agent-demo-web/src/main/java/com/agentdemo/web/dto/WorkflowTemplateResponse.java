package com.agentdemo.web.dto;

import com.agentdemo.app.core.ParameterDefinition;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 工作流模板摘要响应 DTO（列表页）
 */
@Data
@Builder
public class WorkflowTemplateResponse {

    private String id;
    private String name;
    private String description;
    private String mode;
    private int agentCount;
    private List<ParameterDefinition> parameters;
}
