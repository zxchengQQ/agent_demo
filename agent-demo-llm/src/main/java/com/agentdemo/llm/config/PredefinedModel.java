package com.agentdemo.llm.config;

import lombok.Data;

/**
 * 预定义模型配置
 * <p>
 * 业务含义：描述预定义厂商目录中一个模型的配置模板，包含 API 模型名称、显示名称、类型和能力标记。
 * 用户选择预定义厂商后，前端根据此模板展示可选模型列表。
 * </p>
 */
@Data
public class PredefinedModel {
    /** API 模型名称（如 doubao-seed-2.0-pro，用于调用 LLM API） */
    private String modelName;
    /** 显示名称（如"豆包Seed 2.0 Pro"，用于前端展示） */
    private String displayName;
    /** 模型类型：chat（对话）/ embedding（向量化）/ rerank（重排）/ multimodal（多模态） */
    private String type;
    /** 是否支持视图理解（仅 chat 类型有意义，标记模型是否能处理图片输入） */
    private boolean supportsVision;

    /**
     * 便捷构造方法
     *
     * @param modelName      API 模型名称
     * @param displayName    显示名称
     * @param type           模型类型
     * @param supportsVision 是否支持视图理解
     */
    public PredefinedModel(String modelName, String displayName, String type, boolean supportsVision) {
        this.modelName = modelName;
        this.displayName = displayName;
        this.type = type;
        this.supportsVision = supportsVision;
    }

    public PredefinedModel() {
    }
}
