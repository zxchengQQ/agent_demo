package com.agentdemo.web.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作流执行历史摘要响应 DTO（P2 新增，AC-027）
 * <p>
 * 业务含义：执行历史列表的返回结构，含编排模式与迭代次数，供前端历史列表展示。
 * </p>
 */
@Data
@Builder
public class WorkflowExecutionSummaryResponse {

    private String executionId;
    private String templateId;
    private String templateName;
    private String mode;
    private String status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String finalResult;
    private int iterationCount;
}
