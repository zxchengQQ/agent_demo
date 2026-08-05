package com.agentdemo.llm.registry;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.llm.capability.*;
import com.agentdemo.llm.config.LlmProperties;
import com.agentdemo.llm.exception.UnsupportedCapabilityException;
import com.agentdemo.llm.provider.LlmServiceProvider;
import com.agentdemo.llm.thinking.ThinkingStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 模型工厂（CR-002 Task-24 重构为注册表路由模式）
 * <p>
 * 业务含义：统一管理 LLM 模型实例的创建与获取，支持按场景路由到不同模型。
 * 根据 {@code llm.provider} 配置自动选择当前使用的 LLM 提供商（火山引擎方舟或阿里百炼）。
 * </p>
 * <p>
 * 重构要点（CR-002 Task-24）：
 * <ul>
 *   <li>构造器从 {@code (ArkProperties, LlmProperties, BailianProperties)} 改为
 *       {@code (LlmProperties, List<LlmServiceProvider>)}，通过 Spring 自动注入所有厂商策略</li>
 *   <li>内部持有 {@code Map<String, LlmServiceProvider>} 注册表，按 {@code providerCode} 路由</li>
 *   <li>移除全部 7 处 {@code if (provider == BAILIAN) {...} else {...}} 硬编码分支（对应 AC-019）</li>
 *   <li>缓存（{@code chatModelCache} 等）迁移到 Provider 实例内部，Provider 为 Spring 单例，
 *       缓存语义与原 ModelFactory 保持一致（对应 AC-022）</li>
 *   <li>{@code getVisionChatModel()} 通过 {@code instanceof VisionChatModelProvider} 检测能力是否支持，
 *       未实现时抛出 {@link UnsupportedCapabilityException}（对应 AC-021）</li>
 *   <li>新增厂商仅需新增 {@code @Component} 实现类，本类零修改（对应 AC-018）</li>
 * </ul>
 * </p>
 * <p>
 * 公开方法签名（保持向前兼容）：
 * <ul>
 *   <li>{@link #getChatModel(String)}、{@link #getDefaultChatModel()}</li>
 *   <li>{@link #getStreamingChatModel(String)}、{@link #getDefaultStreamingChatModel()}</li>
 *   <li>{@link #getThinkingStreamingChatModel()}</li>
 *   <li>{@link #getEmbeddingModel()}</li>
 *   <li>{@link #getVisionChatModel()}</li>
 * </ul>
 * </p>
 * <p>
 * 调用方：agent 层（构建 AiServices 时调用）、memory 层（Embedding）、rag 层（Embedding）
 * </p>
 */
@Component
public class ModelFactory {

    /**
     * LLM 配置（用于读取当前激活的厂商代码）
     */
    private final LlmProperties llmProperties;

    /**
     * 厂商策略注册表（按 providerCode 索引，不可变）
     * <p>
     * 业务含义：Spring 启动时通过 {@code List<LlmServiceProvider>} 一次注入所有厂商策略，
     * 转换为不可变 Map 后按 {@code providerCode} 查找。
     * 新增厂商仅需新增 {@code @Component} 实现类，注册表自动扩展（对应 AC-018）。
     * </p>
     */
    private final Map<String, LlmServiceProvider> providerRegistry;

    /**
     * 构造模型工厂（CR-002 Task-24 新签名）
     * <p>
     * 业务含义：通过 Spring 构造器注入 {@link LlmProperties} 和所有 {@link LlmServiceProvider} 实现，
     * 将 Provider 列表转换为按 {@code providerCode} 索引的不可变 Map。
     * </p>
     *
     * @param llmProperties LLM 配置（用于读取当前激活的厂商代码）
     * @param providers     所有厂商策略实现列表（由 Spring 自动注入，标注 {@code @Component} 的实现类）
     */
    public ModelFactory(LlmProperties llmProperties, List<LlmServiceProvider> providers) {
        this.llmProperties = llmProperties;
        this.providerRegistry = providers.stream()
                .collect(Collectors.toUnmodifiableMap(
                        LlmServiceProvider::getProviderCode, Function.identity()));
    }

    /**
     * 按场景获取对话模型
     * <p>
     * 业务含义：根据当前提供商的 {@code providerCode} 从注册表查找对应 Provider，
     * 委托给 {@link ChatModelProvider#getChatModel(String)} 返回模型实例。
     * </p>
     *
     * @param scene 场景标识（chat/code/lite 等），null 或空表示默认
     * @return ChatModel 实例（线程安全，由 Provider 缓存复用）
     * @throws BusinessException 当前提供商未注册或 API Key 未配置时抛出
     */
    public ChatModel getChatModel(String scene) {
        return getProvider().getChatModel(scene);
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
     * <p>
     * 业务含义：流式模型用于 SSE 逐字输出场景，与同步模型分别构建。
     * 委托给 {@link StreamingChatModelProvider#getStreamingChatModel(String)}。
     * </p>
     *
     * @param scene 场景标识
     * @return StreamingChatModel 实例（线程安全，由 Provider 缓存复用）
     */
    public StreamingChatModel getStreamingChatModel(String scene) {
        return getProvider().getStreamingChatModel(scene);
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
     * 业务含义：根据当前 {@code llm.provider} 路由到对应提供商的思考流式模型实现，
     * 委托给 {@link ThinkingStreamingChatModelProvider#getThinkingStreamingChatModel(String)}。
     * 通过 Provider 内部缓存复用避免重复创建（对应 AC-022）。
     * </p>
     *
     * @return ThinkingStreamingChatModel 实例（火山引擎或阿里百炼实现）
     * @throws BusinessException 当前提供商的 API Key 未配置时抛出
     */
    public ThinkingStreamingChatModel getThinkingStreamingChatModel() {
        // 委托给 Provider，传 null 表示使用默认场景
        return getProvider().getThinkingStreamingChatModel(null);
    }

    /**
     * 获取 Embedding 模型
     * <p>
     * 业务含义：委托给 {@link EmbeddingModelProvider#getEmbeddingModel()}，
     * 用于 RAG 文档向量化与长期记忆向量化。
     * </p>
     *
     * @return EmbeddingModel 实例（线程安全，由 Provider 缓存复用）
     */
    public EmbeddingModel getEmbeddingModel() {
        return getProvider().getEmbeddingModel();
    }

    /**
     * 获取视觉对话模型（CR-002 新增，Task-24 加入能力检测）
     * <p>
     * 业务含义：用于 PDF 图片描述生成，返回支持图片输入的 ChatModel 实例。
     * 通过 {@code instanceof VisionChatModelProvider} 检测当前厂商是否实现视觉能力接口，
     * 未实现时抛出 {@link UnsupportedCapabilityException}（对应 AC-021）。
     * </p>
     *
     * @return 支持图片输入的 ChatModel 实例（由 Provider 缓存复用）
     * @throws UnsupportedCapabilityException 当前厂商未实现 {@link VisionChatModelProvider} 接口时抛出
     * @throws BusinessException              视觉模型名称未配置或 API Key 缺失时抛出
     */
    public ChatModel getVisionChatModel() {
        LlmServiceProvider provider = getProvider();
        // ISP 检测：厂商未实现 VisionChatModelProvider 接口时抛出明确异常（对应 AC-021）
        if (!(provider instanceof VisionChatModelProvider)) {
            throw new UnsupportedCapabilityException(provider.getProviderCode(), "vision");
        }
        return ((VisionChatModelProvider) provider).getVisionChatModel();
    }

    /**
     * 从注册表中查找当前激活的厂商策略
     * <p>
     * 业务含义：读取 {@link LlmProperties#getProviderCode()} 获取当前激活的厂商代码，
     * 从 {@link #providerRegistry} 中查找对应 {@link LlmServiceProvider}。
     * 未注册时抛出 {@link BusinessException}（错误码 {@link ErrorCode#LLM_PROVIDER_NOT_FOUND}）。
     * </p>
     *
     * @return 当前激活的厂商策略
     * @throws BusinessException 未注册对应厂商代码时抛出
     */
    private LlmServiceProvider getProvider() {
        String code = llmProperties.getProviderCode();
        LlmServiceProvider provider = providerRegistry.get(code);
        if (provider == null) {
            throw new BusinessException(ErrorCode.LLM_PROVIDER_NOT_FOUND,
                    "未找到 LLM 提供商: " + code + "，已注册: " + providerRegistry.keySet());
        }
        return provider;
    }
}
