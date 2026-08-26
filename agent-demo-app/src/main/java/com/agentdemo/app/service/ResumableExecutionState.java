package com.agentdemo.app.service;

import com.agentdemo.app.core.WorkflowTemplate;

import java.util.Map;

/**
 * 可恢复执行快照（P3 新增，AC-017；工作流 HITL 扩展 hitlState）
 * <p>
 * 业务含义：工作流暂停时保存的"小本本"——记录原始模板、执行参数与模型 ID，
 * resume 时据此重放策略（已完成步骤由策略按 ctx 恢复 key 跳过，仅重执行失败步骤）。
 * 快照生命周期：暂停时写入 -> 恢复成功/终止/失败时清理。
 * </p>
 * <p>
 * 工作流 HITL 扩展：hitlState 仅在"等待用户输入"（WAITING_USER）暂停时填充，
 * 失败暂停（PAUSED）为 null——两种暂停语义通过该字段区分，恢复时按类型分流。
 * </p>
 */
public class ResumableExecutionState {

    /** 原始工作流模板 */
    private final WorkflowTemplate template;

    /** 原始执行参数（如 topic） */
    private final Map<String, Object> parameters;

    /** 原始模型 ID（null 表示默认模型） */
    private final String modelId;

    /** HITL 暂停状态快照（WAITING_USER 时非 null，PAUSED 时为 null） */
    private final WorkflowHITLState hitlState;

    /** 失败暂停（PAUSED）构造器：无 HITL 状态，保持 P3 调用方零适配 */
    public ResumableExecutionState(WorkflowTemplate template, Map<String, Object> parameters, String modelId) {
        this(template, parameters, modelId, null);
    }

    /** HITL 暂停（WAITING_USER）构造器：携带 HITL 快照供恢复续跑 */
    public ResumableExecutionState(WorkflowTemplate template, Map<String, Object> parameters,
                                   String modelId, WorkflowHITLState hitlState) {
        this.template = template;
        this.parameters = parameters;
        this.modelId = modelId;
        this.hitlState = hitlState;
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

    public WorkflowHITLState getHitlState() {
        return hitlState;
    }
}
