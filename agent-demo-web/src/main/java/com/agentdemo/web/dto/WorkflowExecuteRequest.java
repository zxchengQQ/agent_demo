package com.agentdemo.web.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

/**
 * 工作流执行请求 DTO
 */
@Data
public class WorkflowExecuteRequest {

    /** 执行参数（如 topic） */
    @NotNull(message = "执行参数不能为空")
    private Map<String, Object> parameters;

    /** 模型 ID（null 使用默认模型） */
    private String modelId;
}
