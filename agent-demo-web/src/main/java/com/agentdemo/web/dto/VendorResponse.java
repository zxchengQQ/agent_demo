package com.agentdemo.web.dto;

import lombok.Data;

import java.util.List;

/**
 * 厂商响应 DTO
 * <p>
 * 业务含义：返回给前端的厂商信息，API Key 脱敏处理，避免明文泄露。
 * apiKeyConfigured 标记是否已配置 API Key，前端据此判断是否需要提示用户填写。
 * </p>
 */
@Data
public class VendorResponse {

    /** 厂商 ID */
    private String id;

    /** 厂商显示名称 */
    private String name;

    /** 厂商类型：predefined / custom */
    private String type;

    /** API Base URL */
    private String baseUrl;

    /** 脱敏后的 API Key（如 "sk-****7890"） */
    private String apiKeyMasked;

    /** 是否已配置 API Key */
    private boolean apiKeyConfigured;

    /** 思考模式触发方式 */
    private String thinkingTrigger;

    /** 请求超时时间（秒） */
    private long timeout;

    /** 最大重试次数 */
    private int maxRetries;

    /** 温度参数 */
    private double temperature;

    /** 模型列表 */
    private List<ModelResponse> models;
}
