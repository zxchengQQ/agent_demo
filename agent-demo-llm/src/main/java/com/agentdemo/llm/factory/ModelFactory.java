package com.agentdemo.llm.factory;

import com.agentdemo.common.constant.ModelConstants;
import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.llm.config.ArkProperties;
import com.agentdemo.llm.config.BailianProperties;
import com.agentdemo.llm.config.LlmProperties;
import com.agentdemo.llm.config.LlmProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型工厂
 * <p>
 * 业务含义：统一管理 LLM 模型实例的创建与获取，支持按场景路由到不同模型。
 * 根据 llm.provider 配置自动选择当前使用的 LLM 提供商（火山引擎方舟或阿里百炼）。
 * 设计原则：
 * 1. 模型实例线程安全且创建成本较高，通过缓存复用避免重复创建
 * 2. 按场景路由（编程任务->code 模型，推理任务->chat 模型）
 * 3. 屏蔽不同 LLM 提供商与 OpenAI 协议的差异，对上层提供统一抽象
 * </p>
 * <p>
 * 调用方：agent 层（构建 AiServices 时调用）、memory 层（Embedding）、rag 层（Embedding）
 * </p>
 */
@Component
public class ModelFactory {

    private final ArkProperties arkProperties;
    private final LlmProperties llmProperties;
    private final BailianProperties bailianProperties;

    /**
     * 对话模型缓存（key: 模型名称）
     * 业务含义：ChatModel 线程安全可复用，缓存避免重复构建
     */
    private final ConcurrentHashMap<String, ChatModel> chatModelCache = new ConcurrentHashMap<>();

    /**
     * 流式对话模型缓存（key: 模型名称）
     */
    private final ConcurrentHashMap<String, StreamingChatModel> streamingModelCache = new ConcurrentHashMap<>();

    /**
     * 思考流式模型缓存（key: 模型名称）
     * 业务含义：CR-001 改造——泛型类型改为 ThinkingStreamingChatModel 接口，
     * 支持缓存不同提供商的思考流式模型实现（Ark / Bailian），按 modelName 缓存复用（BR-LLM-004）。
     * </p>
     */
    private final ConcurrentHashMap<String, ThinkingStreamingChatModel> thinkingStreamingModelCache = new ConcurrentHashMap<>();

    /**
     * Embedding 模型缓存（单例，volatile 保证可见性）
     */
    private volatile EmbeddingModel embeddingModel;

    public ModelFactory(ArkProperties arkProperties, LlmProperties llmProperties,
                        BailianProperties bailianProperties) {
        this.arkProperties = arkProperties;
        this.llmProperties = llmProperties;
        this.bailianProperties = bailianProperties;
    }

    /**
     * 根据当前提供商和场景获取模型名称
     * 业务含义：优先从当前提供商配置的 models Map 查找，未命中时回退到 defaultModel
     *
     * @param scene 场景标识（chat/code/lite 等）
     * @return 模型名称
     */
    private String getModelName(String scene) {
        if (llmProperties.getProvider() == LlmProvider.BAILIAN) {
            return bailianProperties.getModelName(scene);
        }
        return arkProperties.getModelName(scene);
    }

    /**
     * 按场景获取对话模型
     * 业务含义：根据当前提供商和场景标识获取对应模型，通过缓存复用避免重复创建
     *
     * @param scene 场景标识（chat/code/lite 等）
     * @return ChatModel 实例
     * @throws BusinessException API Key 未配置时抛出
     */
    public ChatModel getChatModel(String scene) {
        String modelName = getModelName(scene);
        return chatModelCache.computeIfAbsent(modelName, this::createChatModel);
    }

    /**
     * 获取默认对话模型
     *
     * @return ChatModel 实例
     */
    public ChatModel getDefaultChatModel() {
        return getChatModel(null);
    }

    /**
     * 按场景获取流式对话模型
     * 业务含义：流式模型用于 SSE 逐字输出场景，与同步模型分别构建
     *
     * @param scene 场景标识
     * @return StreamingChatModel 实例
     */
    public StreamingChatModel getStreamingChatModel(String scene) {
        String modelName = getModelName(scene);
        return streamingModelCache.computeIfAbsent(modelName, this::createStreamingChatModel);
    }

    /**
     * 获取默认流式对话模型
     *
     * @return StreamingChatModel 实例
     */
    public StreamingChatModel getDefaultStreamingChatModel() {
        return getStreamingChatModel(null);
    }

    /**
     * 获取默认思考流式对话模型
     * <p>
     * 业务含义：CR-001 改造——返回 ThinkingStreamingChatModel 接口实例，根据 llm.provider
     * 自动路由到对应提供商的思考流式模型实现（ArkThinkingStreamingChatModel 或 BailianThinkingStreamingChatModel）。
     * 解除 BAILIAN 模式下的 UnsupportedOperationException 限制，遵循 BR-LLM-014。
     * </p>
     *
     * @return ThinkingStreamingChatModel 接口实例（火山引擎或阿里百炼实现）
     * @throws BusinessException 当前提供商的 API Key 未配置时抛出
     */
    public ThinkingStreamingChatModel getThinkingStreamingChatModel() {
        // CR-001: 移除 BAILIAN 模式异常拦截，按 provider 路由到对应实现
        String modelName = getModelName(null);
        return thinkingStreamingModelCache.computeIfAbsent(modelName, this::createThinkingStreamingChatModel);
    }

    /**
     * 创建思考流式模型实例（CR-001 改造：内部根据 provider 路由）
     * 业务含义：根据 llm.provider 创建对应提供商的思考流式模型：
     * - ARK：基于 ArkProperties 创建 ArkThinkingStreamingChatModel，baseUrl 指向 Coding Plan 专用地址（BR-LLM-002）
     * - BAILIAN：基于 BailianProperties 创建 BailianThinkingStreamingChatModel，baseUrl 指向 OpenAI 兼容协议端点（BR-LLM-010）
     *
     * @param modelName 模型名称
     * @return ThinkingStreamingChatModel 实例
     */
    private ThinkingStreamingChatModel createThinkingStreamingChatModel(String modelName) {
        if (llmProperties.getProvider() == LlmProvider.BAILIAN) {
            return createBailianThinkingStreamingChatModel(modelName);
        }
        return createArkThinkingStreamingChatModel(modelName);
    }

    /**
     * 创建火山方舟思考流式模型
     */
    private ThinkingStreamingChatModel createArkThinkingStreamingChatModel(String modelName) {
        validateArkApiKey();
        return new ArkThinkingStreamingChatModel(
                arkProperties.getBaseUrl(),
                arkProperties.getApiKey(),
                modelName,
                arkProperties.getTimeout());
    }

    /**
     * 创建阿里百炼思考流式模型（CR-001 新增）
     */
    private ThinkingStreamingChatModel createBailianThinkingStreamingChatModel(String modelName) {
        validateBailianApiKey();
        return new BailianThinkingStreamingChatModel(
                bailianProperties.getBaseUrl(),
                bailianProperties.getApiKey(),
                modelName,
                bailianProperties.getTimeout());
    }

    /**
     * 获取 Embedding 模型
     * 业务含义：根据当前提供商选择对应的 Embedding 模型，用于 RAG 文档向量化与长期记忆向量化
     *
     * @return EmbeddingModel 实例
     */
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

    /**
     * 创建对话模型
     * 业务含义：根据当前提供商选择合适的配置和适配器创建 ChatModel 实例
     */
    private ChatModel createChatModel(String modelName) {
        if (llmProperties.getProvider() == LlmProvider.BAILIAN) {
            return createBailianChatModel(modelName);
        }
        validateArkApiKey();
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
     * 创建阿里百炼对话模型
     * 业务含义：基于阿里百炼 OpenAI 兼容协议构建 ChatModel，使用指定的 Embedding 模型
     */
    private ChatModel createBailianChatModel(String modelName) {
        validateBailianApiKey();
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
     * 创建流式对话模型
     * 业务含义：根据当前提供商选择合适的配置和适配器创建 StreamingChatModel 实例
     */
    private StreamingChatModel createStreamingChatModel(String modelName) {
        if (llmProperties.getProvider() == LlmProvider.BAILIAN) {
            return createBailianStreamingChatModel(modelName);
        }
        validateArkApiKey();
        return OpenAiStreamingChatModel.builder()
                .baseUrl(arkProperties.getBaseUrl())
                .apiKey(arkProperties.getApiKey())
                .modelName(modelName)
                .temperature(arkProperties.getTemperature())
                .timeout(arkProperties.getTimeout())
                .build();
    }

    /**
     * 创建阿里百炼流式对话模型
     * 业务含义：基于阿里百炼 OpenAI 兼容协议构建 StreamingChatModel
     */
    private StreamingChatModel createBailianStreamingChatModel(String modelName) {
        validateBailianApiKey();
        return OpenAiStreamingChatModel.builder()
                .baseUrl(bailianProperties.getBaseUrl())
                .apiKey(bailianProperties.getApiKey())
                .modelName(modelName)
                .temperature(bailianProperties.getTemperature())
                .timeout(bailianProperties.getTimeout())
                .build();
    }

    /**
     * 创建 Embedding 模型
     * 业务含义：根据当前提供商选择对应的 Embedding 模型
     */
    private EmbeddingModel createEmbeddingModel() {
        if (llmProperties.getProvider() == LlmProvider.BAILIAN) {
            return createBailianEmbeddingModel();
        }
        validateArkApiKey();
        return OpenAiEmbeddingModel.builder()
                .baseUrl(arkProperties.getBaseUrl())
                .apiKey(arkProperties.getApiKey())
                .modelName(ModelConstants.MODEL_DOUBAO_EMBEDDING)
                .timeout(arkProperties.getTimeout())
                .build();
    }

    /**
     * 创建阿里百炼 Embedding 模型
     * 业务含义：使用阿里百炼指定的 Embedding 模型进行文本向量化，用于 RAG 检索
     */
    private EmbeddingModel createBailianEmbeddingModel() {
        validateBailianApiKey();
        return OpenAiEmbeddingModel.builder()
                .baseUrl(bailianProperties.getBaseUrl())
                .apiKey(bailianProperties.getApiKey())
                .modelName(bailianProperties.getEmbeddingModel())
                .timeout(bailianProperties.getTimeout())
                .build();
    }

    /**
     * 校验火山引擎 API Key 是否已配置
     * 业务含义：API Key 必须通过环境变量注入，禁止为空
     *
     * @throws BusinessException API Key 为空时抛出
     */
    private void validateArkApiKey() {
        if (arkProperties.getApiKey() == null || arkProperties.getApiKey().isEmpty()) {
            throw new BusinessException(ErrorCode.LLM_API_KEY_INVALID,
                    "ARK_API_KEY 未配置，请通过环境变量注入");
        }
    }

    /**
     * 校验阿里百炼 API Key 是否已配置
     * 业务含义：API Key 必须通过环境变量注入，禁止为空
     *
     * @throws BusinessException API Key 为空时抛出
     */
    private void validateBailianApiKey() {
        if (bailianProperties.getApiKey() == null || bailianProperties.getApiKey().isEmpty()) {
            throw new BusinessException(ErrorCode.LLM_API_KEY_INVALID,
                    "BAILIAN_API_KEY 未配置，请通过环境变量注入");
        }
    }
}