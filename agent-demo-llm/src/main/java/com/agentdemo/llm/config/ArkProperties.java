package com.agentdemo.llm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 火山引擎方舟 Coding Plan 配置
 * <p>
 * 业务含义：绑定 application.yml 中 ark.coding-plan.* 配置项，
 * 提供火山引擎 LLM 接入所需的 Base URL、API Key、模型列表等参数。
 * </p>
 * <p>
 * 配置示例（application.yml）：
 * <pre>
 * ark:
 *   coding-plan:
 *     base-url: https://ark.cn-beijing.volces.com/api/coding/v3
 *     api-key: ${ARK_API_KEY}
 *     default-model: doubao-seed-2.0-code
 *     models:
 *       chat: doubao-seed-2.0-pro
 *       code: doubao-seed-2.0-code
 *       lite: doubao-seed-2.0-lite
 *     timeout: 60s
 *     max-retries: 3
 *     temperature: 0.7
 * </pre>
 * </p>
 */
@Data
@ConfigurationProperties(prefix = "ark.coding-plan")
public class ArkProperties {

    /**
     * 火山引擎 Base URL
     * 业务含义：使用 Coding Plan 专用地址（/api/coding/v3）而非标准地址（/api/v3），
     * 前者按次计费消耗套餐额度，后者按 Token 计费产生额外费用
     */
    private String baseUrl = "https://ark.cn-beijing.volces.com/api/coding/v3";

    /**
     * API Key（从环境变量 ARK_API_KEY 注入，禁止硬编码）
     */
    private String apiKey;

    /**
     * 默认模型名称（当 scene 未命中 models 时回退使用）
     */
    private String defaultModel = "doubao-seed-2.0-code";

    /**
     * 按场景配置的模型映射
     * key: 场景标识（chat/code/lite 等）
     * value: 模型名称（如 doubao-seed-2.0-pro）
     */
    private Map<String, String> models = new HashMap<>();

    /**
     * 调用超时时间
     */
    private Duration timeout = Duration.ofSeconds(60);

    /**
     * 最大重试次数（网络异常时自动重试）
     */
    private int maxRetries = 3;

    /**
     * 温度参数（0.0-1.0，值越高回复越发散，值越低越确定）
     */
    private double temperature = 0.7;

    /**
     * 视觉模型名称（CR-002 新增）
     * <p>
     * 业务含义：用于 PDF 图片描述生成，配置后 ModelFactory.getVisionChatModel() 返回支持图片输入的 ChatModel。
     * 未配置（null）时图片描述功能不可用。示例：doubao-vision-pro
     * </p>
     */
    private String visionModel;

    /**
     * 根据场景获取模型名称
     * 业务含义：优先从 models Map 查找，未命中时回退到 defaultModel
     *
     * @param scene 场景标识（chat/code/lite 等）
     * @return 模型名称
     */
    public String getModelName(String scene) {
        if (scene == null || scene.isEmpty()) {
            return defaultModel;
        }
        return models.getOrDefault(scene, defaultModel);
    }
}
