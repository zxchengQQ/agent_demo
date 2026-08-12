package com.agentdemo.llm.config;

import lombok.Data;

/**
 * 模型配置实体
 * <p>
 * 业务含义：描述一个 LLM 模型的配置信息，包括 API 模型名称、显示名称、类型和能力标记。
 * 每个 LlmModelConfig 属于一个 LlmVendorConfig（通过 vendorId 关联）。
 * </p>
 */
@Data
public class LlmModelConfig {
    /** 模型唯一标识（UUID 去横线） */
    private String id;
    /** 所属厂商 ID */
    private String vendorId;
    /** API 模型名称（如 doubao-seed-2.0-pro，用于调用 LLM API） */
    private String modelName;
    /** 显示名称（如"豆包Seed 2.0 Pro"，用于前端展示） */
    private String displayName;
    /** 模型类型：chat（对话）/ embedding（向量化）/ rerank（重排）/ multimodal（多模态） */
    private String type;
    /** 是否支持视图理解（仅 chat 类型有意义，标记模型是否能处理图片输入） */
    private boolean supportsVision;
}
