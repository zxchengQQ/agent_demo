package com.agentdemo.llm.config;

import lombok.Data;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 厂商配置实体
 * <p>
 * 业务含义：描述一个 LLM 厂商的完整配置，包括连接信息（baseUrl, apiKey）、
 * 请求参数（timeout, maxRetries, temperature）、思考模式触发方式（thinkingTrigger）
 * 和该厂商下的模型列表。
 * </p>
 */
@Data
public class LlmVendorConfig {
    /** 厂商唯一标识（UUID 去横线） */
    private String id;
    /** 厂商显示名称（如"火山引擎方舟"，全局唯一） */
    private String name;
    /** 厂商类型：predefined（预定义）/ custom（自定义） */
    private String type;
    /** API Base URL（OpenAI 兼容协议端点） */
    private String baseUrl;
    /** API Key（明文存储在内存中，API 响应时脱敏） */
    private String apiKey;
    /** 思考模式触发方式：enabled（请求体含 thinking.type=enabled）/ none（模型名自身触发） */
    private String thinkingTrigger;
    /** 请求超时时间 */
    private Duration timeout = Duration.ofSeconds(60);
    /** 最大重试次数 */
    private int maxRetries = 3;
    /** 温度参数 */
    private double temperature = 0.7;
    /** 该厂商下的模型列表 */
    private List<LlmModelConfig> models = new ArrayList<>();
}
