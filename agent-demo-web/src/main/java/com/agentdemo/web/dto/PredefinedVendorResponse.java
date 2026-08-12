package com.agentdemo.web.dto;

import lombok.Data;

import java.util.List;

/**
 * 预定义厂商响应 DTO
 * <p>
 * 业务含义：返回系统内置的厂商配置模板，前端"添加厂商"页面据此展示选项。
 * 用户选择预定义厂商后，系统根据模板创建 LlmVendorConfig，用户只需填入 API Key。
 * </p>
 */
@Data
public class PredefinedVendorResponse {

    /** 厂商代码（如 ark、bailian、openai） */
    private String code;

    /** 厂商显示名称 */
    private String name;

    /** API Base URL */
    private String baseUrl;

    /** 思考模式触发方式 */
    private String thinkingTrigger;

    /** 预定义模型列表 */
    private List<PredefinedModelItem> models;

    /**
     * 预定义模型项
     */
    @Data
    public static class PredefinedModelItem {
        /** API 模型名称 */
        private String modelName;
        /** 显示名称 */
        private String displayName;
        /** 模型类型 */
        private String type;
        /** 是否支持视图理解 */
        private boolean supportsVision;
    }
}
