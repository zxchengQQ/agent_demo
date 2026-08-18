package com.agentdemo.app.service;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;

/**
 * 工作流暂停异常（P3 新增，AC-016）
 * <p>
 * 业务含义：Agent 重试耗尽后抛出的"中场休息信号"——协调层捕获后进入 PAUSED 状态
 * 并保存恢复快照，而非宣告 FAILED 终态。携带失败 Agent 名与步骤索引供前端高亮定位。
 * </p>
 * <p>
 * 兼容性：继承 BusinessException，P1/P2 中 catch (BusinessException) 的调用方与
 * assertThrows(BusinessException.class, ...) 测试零适配（instanceof 语义不变）。
 * </p>
 */
public class WorkflowPausedException extends BusinessException {

    /** 失败的 Agent 名（前端失败步骤高亮定位用） */
    private final String failedAgentName;

    /** 失败的步骤索引（前端失败步骤高亮定位用） */
    private final int failedIndex;

    public WorkflowPausedException(String failedAgentName, int failedIndex, String message, Throwable cause) {
        super(ErrorCode.WORKFLOW_EXECUTION_FAILED, message, cause);
        this.failedAgentName = failedAgentName;
        this.failedIndex = failedIndex;
    }

    public String getFailedAgentName() {
        return failedAgentName;
    }

    public int getFailedIndex() {
        return failedIndex;
    }
}
