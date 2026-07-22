package com.agentdemo.llm.factory;

import com.agentdemo.common.constant.ModelConstants;
import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.llm.config.ArkProperties;
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
 * 业务含义：统一管理火山引擎 LLM 模型实例的创建与获取，支持按场景路由到不同模型。
 * 设计原则：
 * 1. 模型实例线程安全且创建成本较高，通过缓存复用避免重复创建
 * 2. 按场景路由（编程任务->doubao-code，推理任务->deepseek-pro）
 * 3. 屏蔽火山引擎与 OpenAI 协议的差异，对上层提供统一抽象
 * </p>
 * <p>
 * 调用方：agent 层（构建 AiServices 时调用）、memory 层（Embedding）、rag 层（Embedding）
 * </p>
 */
@Component
public class ModelFactory {

    private final ArkProperties properties;

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
     * 业务含义：ArkThinkingStreamingChatModel 创建成本较高（含 HTTP 连接配置），按 modelName 缓存复用（BR-LLM-004）
     */
    private final ConcurrentHashMap<String, ArkThinkingStreamingChatModel> thinkingStreamingModelCache = new ConcurrentHashMap<>();

    /**
     * Embedding 模型缓存（单例，volatile 保证可见性）
     */
    private volatile EmbeddingModel embeddingModel;

    public ModelFactory(ArkProperties properties) {
        this.properties = properties;
    }

    /**
     * 按场景获取对话模型
     * 业务含义：根据场景标识从配置的 models Map 中查找对应模型，未命中时回退到默认模型
     *
     * @param scene 场景标识（chat/code/lite 等）
     * @return ChatModel 实例
     * @throws BusinessException API Key 未配置时抛出
     */
    public ChatModel getChatModel(String scene) {
        String modelName = properties.getModelName(scene);
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
        String modelName = properties.getModelName(scene);
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
     * 获取默认思考流式对话模型（CR-001 新增）
     * <p>
     * 业务含义：返回 ArkThinkingStreamingChatModel 实例，用于深度思考模式下的流式输出。
     * 该模型直连方舟 Chat Completions API，解析 SSE 流中的 reasoning_content 与 content，
     * 分别通过 ThinkingStreamHandler 回调暴露推理内容与正式回复。
     * </p>
     * <p>
     * 遵循 BR-LLM-004：按 modelName 缓存复用，避免重复创建实例。
     * 遵循 BR-LLM-001：API Key 从 ArkProperties 注入，禁止硬编码。
     * </p>
     *
     * @return ArkThinkingStreamingChatModel 实例
     * @throws BusinessException API Key 未配置时抛出（LLM_API_KEY_INVALID）
     */
    public ArkThinkingStreamingChatModel getThinkingStreamingChatModel() {
        // 业务含义：用默认模型名作为缓存键（scene=null 时 ArkProperties 回退到 defaultModel）
        String modelName = properties.getModelName(null);
        return thinkingStreamingModelCache.computeIfAbsent(modelName, this::createThinkingStreamingChatModel);
    }

    /**
     * 创建思考流式模型实例（CR-001 新增）
     * 业务含义：基于 ArkProperties 配置构造 ArkThinkingStreamingChatModel，baseUrl 指向 Coding Plan 专用地址（BR-LLM-002）
     *
     * @param modelName 模型名称
     * @return ArkThinkingStreamingChatModel 实例
     */
    private ArkThinkingStreamingChatModel createThinkingStreamingChatModel(String modelName) {
        validateApiKey();
        return new ArkThinkingStreamingChatModel(
                properties.getBaseUrl(),
                properties.getApiKey(),
                modelName,
                properties.getTimeout());
    }

    /**
     * 获取 Embedding 模型
     * 业务含义：用于 RAG 文档向量化与长期记忆向量化，使用豆包 Embedding 模型
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
     * 业务含义：基于 OpenAI 适配器构建火山引擎 ChatModel，baseUrl 指向 Coding Plan 专用地址
     */
    private ChatModel createChatModel(String modelName) {
        validateApiKey();
        return OpenAiChatModel.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .modelName(modelName)
                .temperature(properties.getTemperature())
                .timeout(properties.getTimeout())
                .maxRetries(properties.getMaxRetries())
                .build();
    }

    /**
     * 创建流式对话模型
     */
    private StreamingChatModel createStreamingChatModel(String modelName) {
        validateApiKey();
        return OpenAiStreamingChatModel.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .modelName(modelName)
                .temperature(properties.getTemperature())
                .timeout(properties.getTimeout())
                .build();
    }

    /**
     * 创建 Embedding 模型
     * 业务含义：使用豆包 Embedding 模型进行文本向量化，用于 RAG 检索
     */
    private EmbeddingModel createEmbeddingModel() {
        validateApiKey();
        return OpenAiEmbeddingModel.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .modelName(ModelConstants.MODEL_DOUBAO_EMBEDDING)
                .timeout(properties.getTimeout())
                .build();
    }

    /**
     * 校验 API Key 是否已配置
     * 业务含义：API Key 必须通过环境变量注入，禁止为空
     *
     * @throws BusinessException API Key 为空时抛出
     */
    private void validateApiKey() {
        if (properties.getApiKey() == null || properties.getApiKey().isEmpty()) {
            throw new BusinessException(ErrorCode.LLM_API_KEY_INVALID,
                    "ARK_API_KEY 未配置，请通过环境变量注入");
        }
    }
}
