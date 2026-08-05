package com.agentdemo.llm.provider;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.llm.capability.*;
import com.agentdemo.llm.config.BailianProperties;
import com.agentdemo.llm.config.LlmProviderConfig;
import com.agentdemo.llm.thinking.BailianThinkingStreamingChatModel;
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
 * 阿里百炼 LLM 厂商策略实现（CR-002 Task-21 新增，Task-24 适配 ISP 修正）
 * <p>
 * 业务含义：迁移原 {@link ModelFactory} 中所有 {@code createBailianXxx} 方法的逻辑到本 Provider，
 * 实现 {@link LlmServiceProvider} 聚合接口提供阿里百炼的核心能力：
 * <ul>
 *   <li>{@link ChatModelProvider}：同步对话（基于 {@link OpenAiChatModel}，OpenAI 兼容协议）</li>
 *   <li>{@link StreamingChatModelProvider}：流式对话（基于 {@link OpenAiStreamingChatModel}）</li>
 *   <li>{@link ThinkingStreamingChatModelProvider}：思考流式对话（基于 {@link BailianThinkingStreamingChatModel}）</li>
 *   <li>{@link EmbeddingModelProvider}：向量化（基于 {@link OpenAiEmbeddingModel}，modelName 由 {@code bailian.embedding-model} 配置）</li>
 * </ul>
 * 另外显式实现 {@link VisionChatModelProvider}（CR-002 Task-24 ISP 修正后视觉能力为可选实现）：
 * <ul>
 *   <li>{@link VisionChatModelProvider}：视觉对话（基于 {@link OpenAiChatModel}）</li>
 * </ul>
 * </p>
 * <p>
 * 与火山引擎方舟的关键差异：
 * <ul>
 *   <li>Base URL 使用阿里百炼 OpenAI 兼容协议端点（/compatible-mode/v1）</li>
 *   <li>Embedding 模型由 {@code bailian.embedding-model} 独立配置（默认 text-embedding-v4），
 *       方舟则是硬编码常量（doubao-embedding-vision）</li>
 *   <li>思考流式模型通过模型名称自身触发思考能力，请求体不含 thinking.type=enabled 字段</li>
 * </ul>
 * </p>
 * <p>
 * 设计决策（CR-002）：
 * <ul>
 *   <li>标注 {@link Component}，由 Spring 自动注入到 {@link ModelFactory} 的 {@code List<LlmServiceProvider>} 中</li>
 *   <li>{@link #getProviderCode()} 返回 {@code "bailian"}，与 {@link com.agentdemo.llm.config.LlmProvider#BAILIAN} 的 code 字段匹配</li>
 *   <li>持有 {@link BailianProperties}（即 {@link LlmProviderConfig}）和内部缓存 Map，缓存按 modelName 复用（对应 AC-022）</li>
 *   <li>Provider 为 Spring 单例，缓存语义与原 ModelFactory 保持一致</li>
 *   <li>Task-24 修正：将 VisionChatModelProvider 从 LlmServiceProvider 聚合接口中拆出，本类显式 implements</li>
 * </ul>
 * </p>
 */
@Component
public class BailianLlmServiceProvider implements LlmServiceProvider, VisionChatModelProvider {

    /**
     * 阿里百炼配置
     */
    private final BailianProperties bailianProperties;

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

    public BailianLlmServiceProvider(BailianProperties bailianProperties) {
        this.bailianProperties = bailianProperties;
    }

    @Override
    public String getProviderCode() {
        return "bailian";
    }

    @Override
    public ChatModel getChatModel(String scene) {
        String modelName = bailianProperties.getModelName(scene);
        return chatModelCache.computeIfAbsent(modelName, this::createChatModel);
    }

    @Override
    public StreamingChatModel getStreamingChatModel(String scene) {
        String modelName = bailianProperties.getModelName(scene);
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
     * @return {@link BailianThinkingStreamingChatModel} 实例
     */
    @Override
    public ThinkingStreamingChatModel getThinkingStreamingChatModel(String scene) {
        String modelName = bailianProperties.getModelName(scene);
        return thinkingStreamingModelCache.computeIfAbsent(modelName, this::createThinkingStreamingChatModel);
    }

    // ==================== 私有创建方法 ====================

    /**
     * 创建阿里百炼同步对话模型
     * <p>
     * 业务含义：基于阿里百炼 OpenAI 兼容协议构建 ChatModel（遵循 BR-LLM-010）。
     * </p>
     */
    private ChatModel createChatModel(String modelName) {
        validateApiKey();
        return OpenAiChatModel.builder()
                .baseUrl(bailianProperties.getBaseUrl())
                .apiKey(bailianProperties.getApiKey())
                .modelName(modelName)
                .temperature(bailianProperties.getTemperature())
                .timeout(bailianProperties.getTimeout())
                .maxRetries(bailianProperties.getMaxRetries())
                .build();
    }

    /**
     * 创建阿里百炼流式对话模型
     */
    private StreamingChatModel createStreamingChatModel(String modelName) {
        validateApiKey();
        return OpenAiStreamingChatModel.builder()
                .baseUrl(bailianProperties.getBaseUrl())
                .apiKey(bailianProperties.getApiKey())
                .modelName(modelName)
                .temperature(bailianProperties.getTemperature())
                .timeout(bailianProperties.getTimeout())
                .build();
    }

    /**
     * 创建阿里百炼 Embedding 模型
     * <p>
     * 业务含义：使用 {@code bailian.embedding-model} 配置的模型（默认 text-embedding-v4）
     * 进行文本向量化，用于 RAG 文档检索。
     * </p>
     */
    private EmbeddingModel createEmbeddingModel() {
        validateApiKey();
        return OpenAiEmbeddingModel.builder()
                .baseUrl(bailianProperties.getBaseUrl())
                .apiKey(bailianProperties.getApiKey())
                .modelName(bailianProperties.getEmbeddingModel())
                .timeout(bailianProperties.getTimeout())
                .build();
    }

    /**
     * 创建阿里百炼视觉对话模型
     */
    private ChatModel createVisionChatModel(String modelName) {
        validateApiKey();
        return OpenAiChatModel.builder()
                .baseUrl(bailianProperties.getBaseUrl())
                .apiKey(bailianProperties.getApiKey())
                .modelName(modelName)
                .temperature(bailianProperties.getTemperature())
                .timeout(bailianProperties.getTimeout())
                .maxRetries(bailianProperties.getMaxRetries())
                .build();
    }

    /**
     * 创建阿里百炼思考流式模型
     * <p>
     * 业务含义：通过原生 HTTP 直连阿里百炼 OpenAI 兼容协议端点，解析 SSE 流中的
     * reasoning_content 字段，实现与火山方舟一致的深度思考能力（遵循 BR-LLM-014）。
     * </p>
     */
    private ThinkingStreamingChatModel createThinkingStreamingChatModel(String modelName) {
        validateApiKey();
        return new BailianThinkingStreamingChatModel(
                bailianProperties.getBaseUrl(),
                bailianProperties.getApiKey(),
                modelName,
                bailianProperties.getTimeout());
    }

    /**
     * 获取阿里百炼视觉模型名称
     * <p>
     * 业务含义：未配置（null 或空）时抛出 BusinessException，避免调用方误判空值
     * </p>
     */
    private String getVisionModelName() {
        String visionModel = bailianProperties.getVisionModel();
        if (visionModel == null || visionModel.isEmpty()) {
            throw new BusinessException(ErrorCode.LLM_MODEL_NOT_CONFIGURED,
                    "BAILIAN_VISION_MODEL 未配置，请通过 bailian.vision-model 设置视觉模型名称");
        }
        return visionModel;
    }

    /**
     * 校验阿里百炼 API Key 是否已配置
     * <p>
     * 业务含义：API Key 必须通过环境变量注入，禁止为空。
     * 切换到火山引擎方舟时不校验本 Key（CR-002 后由 Provider 自身负责校验）。
     * </p>
     */
    private void validateApiKey() {
        if (bailianProperties.getApiKey() == null || bailianProperties.getApiKey().isEmpty()) {
            throw new BusinessException(ErrorCode.LLM_API_KEY_INVALID,
                    "BAILIAN_API_KEY 未配置，请通过环境变量注入");
        }
    }
}
