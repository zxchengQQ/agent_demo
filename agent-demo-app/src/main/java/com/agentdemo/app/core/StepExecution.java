package com.agentdemo.app.core;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 步骤执行记录
 * <p>
 * 业务含义：记录工作流中单个 Agent 步骤的执行状态、输出、错误和耗时。
 * </p>
 */
@Data
public class StepExecution {
    private String agentName;
    private int index;
    private StepStatus status;
    private String output;
    private String error;
    private int retryCount;
    private long startTime;
    private LocalDateTime startTimeStamp;
    private LocalDateTime endTime;
    private long durationMs;

    public StepExecution(String agentName, int index, StepStatus status) {
        this.agentName = agentName;
        this.index = index;
        this.status = status;
        this.startTimeStamp = LocalDateTime.now();
    }

    /**
     * 标记步骤完成
     *
     * @param output 步骤输出文本
     */
    public void complete(String output) {
        this.output = output;
        this.status = StepStatus.COMPLETED;
        this.endTime = LocalDateTime.now();
        this.durationMs = System.currentTimeMillis() - startTime;
    }

    /**
     * 标记步骤失败
     *
     * @param error 错误信息
     */
    public void fail(String error) {
        this.error = error;
        this.status = StepStatus.FAILED;
        this.endTime = LocalDateTime.now();
        this.durationMs = System.currentTimeMillis() - startTime;
    }

    public long getDurationMs() {
        return durationMs;
    }
}
