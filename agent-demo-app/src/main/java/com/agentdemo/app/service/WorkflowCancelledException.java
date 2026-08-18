package com.agentdemo.app.service;

/**
 * 工作流执行取消异常
 * <p>
 * 业务含义：用户主动终止（AC-022）或执行被中断时抛出，
 * 由执行引擎捕获后推送 workflow_failed(status=TERMINATED) 事件。
 * </p>
 */
public class WorkflowCancelledException extends RuntimeException {

    public WorkflowCancelledException() {
        super("用户主动终止");
    }

    public WorkflowCancelledException(String message) {
        super(message);
    }

    public WorkflowCancelledException(String message, Throwable cause) {
        super(message, cause);
    }
}
