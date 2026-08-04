package com.agentdemo.llm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 阿里百炼 OpenAI 兼容协议配置
 * <p>
 * 业务含义：绑定 application.yml 中 bailian.* 配置项，
 * 提供阿里百炼 LLM 接入所需的 Base URL、API Key、模型列表等参数。
 * 阿里百炼通过 OpenAI 兼容协议（/compatible-mode/v1）提供服务。
 * </p>
 * <p>
 * 配置示例（application.yml）：
 * <pre>
 * bailian:
 *   base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
 *   api-key: ${BAILIAN_API_KEY}
 *   default-model: deepseek-v4-flash
 *   models:
 *     chat: deepseek-v4-flash
 *     code: deepseek-v4-flash
 *     lite: deepseek-v4-flash
 *   timeout: 60s
 *   max-retries: 3
 *   temperature: 0.7
 *   embedding-model: text-embedding-v4
 * </pre>
 * </p>
 */
@Data
@ConfigurationProperties(prefix = "bailian")
public class BailianProperties {

    /**
     * 阿里百炼 OpenAI 兼容协议 Base URL
     * 业务含义：使用 /compatible-mode/v1 路径，兼容 OpenAI 协议格式，
     * 可通过 LangChain4j openai4j 适配器直接接入
     */
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    /**
     * API Key（从环境变量 BAILIAN_API_KEY 注入，禁止硬编码）
     */
    private String apiKey;

    /**
     * 默认模型名称（当 scene 未命中 models 时回退使用）
     */
    private String defaultModel = "deepseek-v4-flash";

    /**
     * 按场景配置的模型映射
     * key: 场景标识（chat/code/lite 等）
     * value: 模型名称（如 deepseek-v4-flash）
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
     * Embedding 模型名称
     * 业务含义：RAG 文档向量化使用的阿里百炼 Embedding 模型，
     * 独立于对话模型配置，便于单独指定
     */
    private String embeddingModel = "text-embedding-v4";

    /**
     * 视觉模型名称（CR-002 新增）
     * <p>
     * 业务含义：用于 PDF 图片描述生成，配置后 ModelFactory.getVisionChatModel() 返回支持图片输入的 ChatModel。
     * 未配置（null）时图片描述功能不可用。示例：qwen-vl-plus
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