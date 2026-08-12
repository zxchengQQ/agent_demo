package com.agentdemo.web.dto;

import lombok.Data;

/**
 * 模型响应 DTO
 * <p>
 * 业务含义：返回给前端的模型信息，包含所属厂商信息，便于前端展示模型归属。
 * </p>
 */
@Data
public class ModelResponse {

    /** 模型 ID */
    private String id;

    /** 所属厂商 ID */
    private String vendorId;

    /** 所属厂商名称 */
    private String vendorName;

    /** API 模型名称 */
    private String modelName;

    /** 显示名称 */
    private String displayName;

    /** 模型类型：chat / embedding / rerank / multimodal */
    private String type;

    /** 是否支持视图理解 */
    private boolean supportsVision;
}
