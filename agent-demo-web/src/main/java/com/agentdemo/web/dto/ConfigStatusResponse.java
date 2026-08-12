package com.agentdemo.web.dto;

import lombok.Data;

/**
 * 配置状态响应 DTO
 * <p>
 * 业务含义：返回当前 LLM 配置的整体状态，前端据此判断系统是否可用
 * （是否有配置、是否有 chat 模型、是否有 embedding 模型等）。
 * </p>
 */
@Data
public class ConfigStatusResponse {

    /** 是否已配置任何厂商 */
    private boolean hasConfig;

    /** 是否有可用的 chat 模型 */
    private boolean hasChatModel;

    /** 是否有可用的 embedding 模型 */
    private boolean hasEmbeddingModel;

    /** 已配置厂商数量 */
    private int vendorCount;

    /** 已配置 chat 模型数量 */
    private int chatModelCount;
}
