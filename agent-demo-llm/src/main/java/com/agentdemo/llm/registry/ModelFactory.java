package com.agentdemo.llm.registry;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.llm.config.LlmConfigStore;
import com.agentdemo.llm.config.LlmModelConfig;
import com.agentdemo.llm.config.LlmVendorConfig;
import com.agentdemo.llm.thinking.ArkThinkingStreamingChatModel;
import com.agentdemo.llm.thinking.BailianThinkingStreamingChatModel;
import com.agentdemo.llm.thinking.ThinkingStreamingChatModel;
import com.agentdemo.llm.thinking.TracingThinkingStreamingChatModel;
import com.agentdemo.observability.TraceCollector;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM 模型工厂（动态配置版）
 * <p>
 * 业务含义：从 LlmConfigStore 读取用户配置的厂商和模型信息，
 * 创建 OpenAI 兼容的模型实例并按 vendorId:modelName 缓存复用。
 * 替代原 CR-002 的 Provider 路由模式，支持多厂商同时配置和按 modelId 选择模型。
 * </p>
 */
@Component
public class ModelFactory {
    private final LlmConfigStore configStore;
    private final TraceCollector traceCollector;
    private final ConcurrentHashMap<String, ChatModel> chatModelCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StreamingChatModel> streamingModelCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ThinkingStreamingChatModel> thinkingModelCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, EmbeddingModel> embeddingModelCache = new ConcurrentHashMap<>();

    public ModelFactory(LlmConfigStore configStore, TraceCollector traceCollector) {
        this.configStore = configStore;
        this.traceCollector = traceCollector;
    }

    public ChatModel getChatModelByModelId(String modelId) {
        LlmModelConfig model = resolveChatModel(modelId);
        LlmVendorConfig vendor = configStore.getVendor(model.getVendorId());
        String cacheKey = vendor.getId() + ":" + model.getModelName();
        return chatModelCache.computeIfAbsent(cacheKey, k -> createChatModel(vendor, model.getModelName()));
    }

    public ChatModel getDefaultChatModel() {
        LlmModelConfig model = configStore.getFirstChatModel();
        if (model == null) {
            throw new BusinessException(ErrorCode.LLM_NO_CHAT_MODEL, "未配置 chat 模型");
        }
        return getChatModelByModelId(model.getId());
    }

    public StreamingChatModel getStreamingChatModelByModelId(String modelId) {
        LlmModelConfig model = resolveChatModel(modelId);
        LlmVendorConfig vendor = configStore.getVendor(model.getVendorId());
        String cacheKey = vendor.getId() + ":" + model.getModelName();
        return streamingModelCache.computeIfAbsent(cacheKey, k -> createStreamingChatModel(vendor, model.getModelName()));
    }

    public StreamingChatModel getDefaultStreamingChatModel() {
        LlmModelConfig model = configStore.getFirstChatModel();
        if (model == null) {
            throw new BusinessException(ErrorCode.LLM_NO_CHAT_MODEL, "未配置 chat 模型");
        }
        return getStreamingChatModelByModelId(model.getId());
    }

    public ThinkingStreamingChatModel getThinkingStreamingChatModelByModelId(String modelId) {
        LlmModelConfig model = resolveChatModel(modelId);
        LlmVendorConfig vendor = configStore.getVendor(model.getVendorId());
        String cacheKey = vendor.getId() + ":" + model.getModelName();
        return thinkingModelCache.computeIfAbsent(cacheKey, k -> createThinkingStreamingChatModel(vendor, model.getModelName()));
    }

    public ThinkingStreamingChatModel getDefaultThinkingStreamingChatModel() {
        LlmModelConfig model = configStore.getFirstChatModel();
        if (model == null) {
            throw new BusinessException(ErrorCode.LLM_NO_CHAT_MODEL, "未配置 chat 模型");
        }
        return getThinkingStreamingChatModelByModelId(model.getId());
    }

    public EmbeddingModel getEmbeddingModel() {
        LlmModelConfig model = configStore.getFirstEmbeddingModel();
        if (model == null) {
            throw new BusinessException(ErrorCode.LLM_NO_EMBEDDING_MODEL, "未配置 embedding 模型");
        }
        LlmVendorConfig vendor = configStore.getVendor(model.getVendorId());
        String cacheKey = vendor.getId() + ":" + model.getModelName();
        return embeddingModelCache.computeIfAbsent(cacheKey, k -> createEmbeddingModel(vendor, model.getModelName()));
    }

    /**
     * 获取视觉对话模型（用于 PDF 图片描述等场景）
     * 业务含义：从已配置的 chat 模型中查找第一个 supportsVision=true 的模型
     */
    public ChatModel getVisionChatModel() {
        for (LlmVendorConfig vendor : configStore.getAllVendors()) {
            if (vendor.getModels() != null) {
                for (LlmModelConfig model : vendor.getModels()) {
                    if ("chat".equals(model.getType()) && model.isSupportsVision()) {
                        String cacheKey = vendor.getId() + ":" + model.getModelName();
                        return chatModelCache.computeIfAbsent(cacheKey, k -> createChatModel(vendor, model.getModelName()));
                    }
                }
            }
        }
        throw new BusinessException(ErrorCode.LLM_MODEL_NOT_CONFIGURED, "未配置支持视图理解的 chat 模型");
    }

    public void clearCacheForVendor(String vendorId) {
        String prefix = vendorId + ":";
        chatModelCache.keySet().removeIf(k -> k.startsWith(prefix));
        streamingModelCache.keySet().removeIf(k -> k.startsWith(prefix));
        thinkingModelCache.keySet().removeIf(k -> k.startsWith(prefix));
        embeddingModelCache.keySet().removeIf(k -> k.startsWith(prefix));
    }

    public void clearAllCache() {
        chatModelCache.clear();
        streamingModelCache.clear();
        thinkingModelCache.clear();
        embeddingModelCache.clear();
    }

    // ========== 私有创建方法 ==========

    /**
     * 解析 chat 模型配置（记录 id 优先，API 模型名兜底）
     * <p>
     * 业务含义：modelId 存在两种引用形式——对话链路由前端模型选择器传模型记录
     * UUID（存储 id 字段）；预置工作流模板的 AgentDefinition.modelId 引用 API
     * 模型名（如 glm-5.2）。只按 UUID 查找会导致预置模板 Agent 构建必然报
     * "配置的模型不存在"，故 id 未命中时按 modelName + chat 类型兜底解析。
     * </p>
     *
     * @param modelId 模型记录 UUID 或 API 模型名
     * @return chat 类型的模型配置
     */
    private LlmModelConfig resolveChatModel(String modelId) {
        LlmModelConfig model = configStore.getModel(modelId);
        if (model == null) {
            model = configStore.getModelByName(modelId, "chat");
        }
        if (model == null || !"chat".equals(model.getType())) {
            throw new BusinessException(ErrorCode.LLM_MODEL_NOT_FOUND, "模型不存在或类型不是 chat: " + modelId);
        }
        return model;
    }

    private ChatModel createChatModel(LlmVendorConfig vendor, String modelName) {
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(vendor.getBaseUrl())
                .apiKey(vendor.getApiKey())
                .modelName(modelName)
                .temperature(vendor.getTemperature())
                .timeout(vendor.getTimeout())
                .maxRetries(vendor.getMaxRetries());
        if (traceCollector.isEnabled()) {
            // 业务含义：仅 LangSmith 启用时挂载监听（AC-S02 默认关零开销，无 Key 时不产生采集事件）
            builder.listeners(List.of(new TraceChatModelListener(traceCollector)));
        }
        return builder.build();
    }

    private StreamingChatModel createStreamingChatModel(LlmVendorConfig vendor, String modelName) {
        OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder = OpenAiStreamingChatModel.builder()
                .baseUrl(vendor.getBaseUrl())
                .apiKey(vendor.getApiKey())
                .modelName(modelName)
                .temperature(vendor.getTemperature())
                .timeout(vendor.getTimeout());
        if (traceCollector.isEnabled()) {
            builder.listeners(List.of(new TraceChatModelListener(traceCollector)));
        }
        return builder.build();
    }

    private ThinkingStreamingChatModel createThinkingStreamingChatModel(LlmVendorConfig vendor, String modelName) {
        // 按 thinkingTrigger 配置选择实现类
        // enabled: 请求体含 thinking.type=enabled（火山引擎风格）
        // none: 模型名称自身触发思考（阿里百炼风格）
        ThinkingStreamingChatModel raw;
        if ("enabled".equals(vendor.getThinkingTrigger())) {
            raw = new ArkThinkingStreamingChatModel(
                    vendor.getBaseUrl(), vendor.getApiKey(), modelName, vendor.getTimeout());
        } else {
            raw = new BailianThinkingStreamingChatModel(
                    vendor.getBaseUrl(), vendor.getApiKey(), modelName, vendor.getTimeout());
        }
        if (!traceCollector.isEnabled()) {
            // 业务含义：默认关闭（AC-S02）时不包装装饰器，Thinking 系零额外开销
            return raw;
        }
        // 业务含义：Thinking 系无 listener 机制，包装追踪装饰器覆盖主链路采集（Task-09）。
        // 一处包装覆盖全部 Thinking 调用方（直答/拆解/恢复），业务流类零修改。
        return new TracingThinkingStreamingChatModel(raw, traceCollector, modelName);
    }

    private EmbeddingModel createEmbeddingModel(LlmVendorConfig vendor, String modelName) {
        return OpenAiEmbeddingModel.builder()
                .baseUrl(vendor.getBaseUrl())
                .apiKey(vendor.getApiKey())
                .modelName(modelName)
                .timeout(vendor.getTimeout())
                .build();
    }
}
