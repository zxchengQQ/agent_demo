package com.agentdemo.app.service;

/**
 * 工作流执行超时异常
 * <p>
 * 业务含义：单个 Agent 执行超过超时阈值（默认 5 分钟）时抛出，
 * 由执行引擎捕获后推送 workflow_failed(status=TIMEOUT) 事件（AC-023）。
 * </p>
 */
public class WorkflowTimeoutException extends RuntimeException {

    public WorkflowTimeoutException(String message) {
        super(message);
    }

    public WorkflowTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
