package com.agentdemo.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 添加/编辑厂商请求 DTO
 * <p>
 * 业务含义：前端调用 /api/llm/config/vendors 接口的请求参数，
 * 包含厂商基本信息和模型列表。添加时 apiKey 必填，编辑时可为空（保留原值）。
 * </p>
 */
@Data
public class VendorRequest {

    /** 厂商显示名称（必填，全局唯一） */
    @NotBlank(message = "厂商名称不能为空")
    private String name;

    /** 厂商类型：predefined（预定义）/ custom（自定义） */
    private String type;

    /** API Base URL（必填，OpenAI 兼容协议端点） */
    @NotBlank(message = "Base URL 不能为空")
    private String baseUrl;

    /** API Key（添加时必填，编辑时可选，为空保留原值） */
    private String apiKey;

    /** 思考模式触发方式：enabled / none */
    private String thinkingTrigger;

    /** 请求超时时间（秒，默认 60） */
    private long timeout = 60;

    /** 最大重试次数（默认 3） */
    private int maxRetries = 3;

    /** 温度参数（默认 0.7） */
    private double temperature = 0.7;

    /** 模型列表 */
    private List<ModelItem> models;

    /**
     * 模型项
     */
    @Data
    public static class ModelItem {
        /** API 模型名称 */
        private String modelName;
        /** 显示名称 */
        private String displayName;
        /** 模型类型：chat / embedding / rerank / multimodal */
        private String type;
        /** 是否支持视图理解 */
        private boolean supportsVision;
    }
}
