package com.agentdemo.web.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工作流执行状态响应 DTO
 */
@Data
@Builder
public class WorkflowExecutionResponse {

    private String executionId;
    private String templateId;
    private String templateName;
    private String status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private List<StepItem> steps;
    private String finalResult;

    // ===== P2 新增 =====
    /** 编排模式（AC-027 历史展示） */
    private String mode;

    /** 迭代次数（循环模式，AC-029） */
    private int iterationCount;

    @Data
    @Builder
    public static class StepItem {
        private String agentName;
        private String status;
        private long durationMs;
    }
}
