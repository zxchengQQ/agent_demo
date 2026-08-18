package com.agentdemo.app.core;

import lombok.Builder;
import lombok.Data;

/**
 * 工作流参数定义
 */
@Data
@Builder
public class ParameterDefinition {
    private String name;
    private String type;
    private boolean required;
    private String description;
}
