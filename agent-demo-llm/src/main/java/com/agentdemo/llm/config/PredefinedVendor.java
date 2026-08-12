package com.agentdemo.llm.config;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 预定义厂商配置
 * <p>
 * 业务含义：描述一个预定义 LLM 厂商的配置模板，包含厂商代码、显示名称、
 * API Base URL、思考模式触发方式和可选模型列表。
 * 前端"添加厂商"页面展示这些预定义选项，用户选择后填充为 LlmVendorConfig。
 * </p>
 */
@Data
public class PredefinedVendor {
    /** 厂商代码（如 ark、bailian、openai，全局唯一） */
    private String code;
    /** 厂商显示名称（如"火山引擎方舟"） */
    private String name;
    /** API Base URL（OpenAI 兼容协议端点） */
    private String baseUrl;
    /** 思考模式触发方式：enabled（请求体含 thinking.type=enabled）/ none（模型名自身触发） */
    private String thinkingTrigger;
    /** 该厂商下的预定义模型列表 */
    private List<PredefinedModel> models = new ArrayList<>();

    /**
     * 便捷构造方法
     *
     * @param code            厂商代码
     * @param name            厂商显示名称
     * @param baseUrl         API Base URL
     * @param thinkingTrigger 思考模式触发方式
     * @param models          预定义模型列表
     */
    public PredefinedVendor(String code, String name, String baseUrl, String thinkingTrigger, List<PredefinedModel> models) {
        this.code = code;
        this.name = name;
        this.baseUrl = baseUrl;
        this.thinkingTrigger = thinkingTrigger;
        this.models = models != null ? models : new ArrayList<>();
    }

    public PredefinedVendor() {
    }
}
