package com.agentdemo.llm.provider;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.llm.capability.*;
import com.agentdemo.llm.config.ArkProperties;
import com.agentdemo.llm.config.LlmProviderConfig;
import com.agentdemo.llm.thinking.ArkThinkingStreamingChatModel;
import com.agentdemo.llm.registry.ModelFactory;
import com.agentdemo.llm.thinking.ThinkingStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 火山引擎方舟 LLM 厂商策略实现（CR-002 Task-20 新增，Task-24 适配 ISP 修正）
 * <p>
 * 业务含义：迁移原 {@link ModelFactory} 中所有 {@code createArkXxx} 方法的逻辑到本 Provider，
 * 实现 {@link LlmServiceProvider} 聚合接口提供火山引擎方舟的核心能力：
 * <ul>
 *   <li>{@link ChatModelProvider}：同步对话（基于 {@link OpenAiChatModel}）</li>
 *   <li>{@link StreamingChatModelProvider}：流式对话（基于 {@link OpenAiStreamingChatModel}）</li>
 *   <li>{@link ThinkingStreamingChatModelProvider}：思考流式对话（基于 {@link ArkThinkingStreamingChatModel}）</li>
 *   <li>{@link EmbeddingModelProvider}：向量化（基于 {@link OpenAiEmbeddingModel}）</li>
 * </ul>
 * 另外显式实现 {@link VisionChatModelProvider}（CR-002 Task-24 ISP 修正后视觉能力为可选实现）：
 * <ul>
 *   <li>{@link VisionChatModelProvider}：视觉对话（基于 {@link OpenAiChatModel}）</li>
 * </ul>
 * </p>
 * <p>
 * 设计决策（CR-002）：
 * <ul>
 *   <li>标注 {@link Component}，由 Spring 自动注入到 {@link ModelFactory} 的 {@code List<LlmServiceProvider>} 中</li>
 *   <li>{@link #getProviderCode()} 返回 {@code "ark"}，与 {@link com.agentdemo.llm.config.LlmProvider#ARK} 的 code 字段匹配</li>
 *   <li>持有 {@link ArkProperties}（即 {@link LlmProviderConfig}）和内部缓存 Map，缓存按 modelName 复用（对应 AC-022）</li>
 *   <li>Provider 为 Spring 单例，缓存语义与原 ModelFactory 保持一致</li>
 *   <li>Task-24 修正：将 VisionChatModelProvider 从 LlmServiceProvider 聚合接口中拆出，本类显式 implements</li>
 * </ul>
 * </p>
 */
@Component
public class ArkLlmServiceProvider implements LlmServiceProvider, VisionChatModelProvider {

    /**
     * 火山引擎方舟配置
     */
    private final ArkProperties arkProperties;

    /**
     * 同步对话模型缓存（key: 模型名称）
     */
    private final ConcurrentHashMap<String, ChatModel> chatModelCache = new ConcurrentHashMap<>();

    /**
     * 流式对话模型缓存（key: 模型名称）
     */
    private final ConcurrentHashMap<String, StreamingChatModel> streamingModelCache = new ConcurrentHashMap<>();

    /**
     * 思考流式模型缓存（key: 模型名称）
     */
    private final ConcurrentHashMap<String, ThinkingStreamingChatModel> thinkingStreamingModelCache = new ConcurrentHashMap<>();

    /**
     * 视觉对话模型缓存（key: 模型名称）
     */
    private final ConcurrentHashMap<String, ChatModel> visionModelCache = new ConcurrentHashMap<>();

    /**
     * Embedding 模型缓存（单例，volatile 保证可见性）
     */
    private volatile EmbeddingModel embeddingModel;

    public ArkLlmServiceProvider(ArkProperties arkProperties) {
        this.arkProperties = arkProperties;
    }

    @Override
    public String getProviderCode() {
        return "ark";
    }

    @Override
    public ChatModel getChatModel(String scene) {
        String modelName = arkProperties.getModelName(scene);
        return chatModelCache.computeIfAbsent(modelName, this::createChatModel);
    }

    @Override
    public StreamingChatModel getStreamingChatModel(String scene) {
        String modelName = arkProperties.getModelName(scene);
        return streamingModelCache.computeIfAbsent(modelName, this::createStreamingChatModel);
    }

    @Override
    public EmbeddingModel getEmbeddingModel() {
        if (embeddingModel == null) {
            synchronized (this) {
                if (embeddingModel == null) {
                    embeddingModel = createEmbeddingModel();
                }
            }
        }
        return embeddingModel;
    }

    @Override
    public ChatModel getVisionChatModel() {
        String modelName = getVisionModelName();
        return visionModelCache.computeIfAbsent(modelName, this::createVisionChatModel);
    }

    /**
     * 获取思考流式模型实例（实现 ThinkingStreamingChatModelProvider 接口）
     * <p>
     * 业务含义：根据场景获取对应的 {@link ThinkingStreamingChatModel} 实例。
     * 通过缓存复用避免重复创建（对应 AC-022）。
     * </p>
     *
     * @param scene 场景标识（chat/code/lite 等），null 或空表示默认
     * @return {@link ArkThinkingStreamingChatModel} 实例
     */
    @Override
    public ThinkingStreamingChatModel getThinkingStreamingChatModel(String scene) {
        String modelName = arkProperties.getModelName(scene);
        return thinkingStreamingModelCache.computeIfAbsent(modelName, this::createThinkingStreamingChatModel);
    }

    // ==================== 私有创建方法 ====================

    /**
     * 创建火山方舟同步对话模型
     */
    private ChatModel createChatModel(String modelName) {
        validateApiKey();
        return OpenAiChatModel.builder()
                .baseUrl(arkProperties.getBaseUrl())
                .apiKey(arkProperties.getApiKey())
                .modelName(modelName)
                .temperature(arkProperties.getTemperature())
                .timeout(arkProperties.getTimeout())
                .maxRetries(arkProperties.getMaxRetries())
                .build();
    }

    /**
     * 创建火山方舟流式对话模型
     */
    private StreamingChatModel createStreamingChatModel(String modelName) {
        validateApiKey();
        return OpenAiStreamingChatModel.builder()
                .baseUrl(arkProperties.getBaseUrl())
                .apiKey(arkProperties.getApiKey())
                .modelName(modelName)
                .temperature(arkProperties.getTemperature())
                .timeout(arkProperties.getTimeout())
                .build();
    }

    /**
     * 创建火山方舟 Embedding 模型
     */
    private EmbeddingModel createEmbeddingModel() {
        validateApiKey();
        return OpenAiEmbeddingModel.builder()
                .baseUrl(arkProperties.getBaseUrl())
                .apiKey(arkProperties.getApiKey())
                .modelName(arkProperties.getEmbeddingModel())
                .timeout(arkProperties.getTimeout())
                .build();
    }

    /**
     * 创建火山方舟视觉对话模型
     */
    private ChatModel createVisionChatModel(String modelName) {
        validateApiKey();
        return OpenAiChatModel.builder()
                .baseUrl(arkProperties.getBaseUrl())
                .apiKey(arkProperties.getApiKey())
                .modelName(modelName)
                .temperature(arkProperties.getTemperature())
                .timeout(arkProperties.getTimeout())
                .maxRetries(arkProperties.getMaxRetries())
                .build();
    }

    /**
     * 创建火山方舟思考流式模型
     */
    private ThinkingStreamingChatModel createThinkingStreamingChatModel(String modelName) {
        validateApiKey();
        return new ArkThinkingStreamingChatModel(
                arkProperties.getBaseUrl(),
                arkProperties.getApiKey(),
                modelName,
                arkProperties.getTimeout());
    }

    /**
     * 获取火山方舟视觉模型名称
     * <p>
     * 业务含义：未配置（null 或空）时抛出 BusinessException，避免调用方误判空值
     * </p>
     */
    private String getVisionModelName() {
        String visionModel = arkProperties.getVisionModel();
        if (visionModel == null || visionModel.isEmpty()) {
            throw new BusinessException(ErrorCode.LLM_MODEL_NOT_CONFIGURED,
                    "ARK_VISION_MODEL 未配置，请通过 ark.coding-plan.vision-model 设置视觉模型名称");
        }
        return visionModel;
    }

    /**
     * 校验火山引擎 API Key 是否已配置
     * <p>
     * 业务含义：API Key 必须通过环境变量注入，禁止为空。
     * 切换到阿里百炼时不校验本 Key（CR-002 后由 Provider 自身负责校验）。
     * </p>
     */
    private void validateApiKey() {
        if (arkProperties.getApiKey() == null || arkProperties.getApiKey().isEmpty()) {
            throw new BusinessException(ErrorCode.LLM_API_KEY_INVALID,
                    "ARK_API_KEY 未配置，请通过环境变量注入");
        }
    }
}
