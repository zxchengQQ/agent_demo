package com.agentdemo.llm.config;

import com.agentdemo.llm.registry.ModelFactory;

import java.time.Duration;

/**
 * LLM 提供商配置访问契约（CR-002 Task-17 新增）
 * <p>
 * 业务含义：统一各 LLM 厂商配置（{@code ArkProperties}、{@code BailianProperties}）的访问方式，
 * 使编排层（{@link ModelFactory}）与厂商实现层（{@code LlmServiceProvider}）通过接口契约
 * 访问配置参数，解除对具体配置类的硬编码依赖。
 * </p>
 * <p>
 * 设计决策：接口隔离原则（ISP）下的配置访问抽象。厂商配置类仅需 {@code implements LlmProviderConfig}
 * 即可被注入到编排层，无需修改编排层代码（对应 AC-018）。
 * </p>
 */
public interface LlmProviderConfig {

    /**
     * 获取 LLM 服务 Base URL
     */
    String getBaseUrl();

    /**
     * 获取 API Key（从环境变量注入，禁止硬编码）
     */
    String getApiKey();

    /**
     * 获取调用超时时间
     */
    Duration getTimeout();

    /**
     * 获取最大重试次数（网络异常时自动重试）
     */
    int getMaxRetries();

    /**
     * 获取温度参数（0.0-1.0，值越高回复越发散，值越低越确定）
     */
    double getTemperature();

    /**
     * 根据场景获取模型名称
     * <p>
     * 业务含义：优先从 {@code models} Map 查找，未命中时回退到 {@code defaultModel}
     * </p>
     *
     * @param scene 场景标识（chat/code/lite 等）
     * @return 模型名称
     */
    String getModelName(String scene);

    /**
     * 获取 Embedding 模型名称
     * <p>
     * 业务含义：用于 RAG 文档向量化与长期记忆向量化，独立于对话模型配置
     * </p>
     *
     * @return Embedding 模型名称，未配置时返回 null
     */
    String getEmbeddingModel();

    /**
     * 获取视觉模型名称
     * <p>
     * 业务含义：用于 PDF 图片描述生成，未配置时返回 null，由调用方决定是否降级
     * </p>
     *
     * @return 视觉模型名称，未配置时返回 null
     */
    String getVisionModel();
}
