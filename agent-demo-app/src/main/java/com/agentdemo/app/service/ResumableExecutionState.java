package com.agentdemo.app.service;

import com.agentdemo.app.core.WorkflowTemplate;

import java.util.Map;

/**
 * 可恢复执行快照（P3 新增，AC-017）
 * <p>
 * 业务含义：工作流暂停时保存的"小本本"——记录原始模板、执行参数与模型 ID，
 * resume 时据此重放策略（已完成步骤由策略按 ctx 恢复 key 跳过，仅重执行失败步骤）。
 * 快照生命周期：暂停时写入 -> 恢复成功/终止/失败时清理。
 * </p>
 */
public class ResumableExecutionState {

    /** 原始工作流模板 */
    private final WorkflowTemplate template;

    /** 原始执行参数（如 topic） */
    private final Map<String, Object> parameters;

    /** 原始模型 ID（null 表示默认模型） */
    private final String modelId;

    public ResumableExecutionState(WorkflowTemplate template, Map<String, Object> parameters, String modelId) {
        this.template = template;
        this.parameters = parameters;
        this.modelId = modelId;
    }

    public WorkflowTemplate getTemplate() {
        return template;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public String getModelId() {
        return modelId;
    }
}
