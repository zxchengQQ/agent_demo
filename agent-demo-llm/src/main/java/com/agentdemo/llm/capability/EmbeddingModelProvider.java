package com.agentdemo.llm.capability;

import dev.langchain4j.model.embedding.EmbeddingModel;

/**
 * 向量化能力接口（CR-002 Task-17 新增）
 * <p>
 * 业务含义：声明 LLM 厂商提供文本向量化（{@link EmbeddingModel}）的能力契约。
 * 用于 RAG 文档向量化与长期记忆向量化。
 * </p>
 */
public interface EmbeddingModelProvider {

    /**
     * 获取 Embedding 模型实例
     *
     * @return EmbeddingModel 实例（线程安全，缓存复用）
     */
    EmbeddingModel getEmbeddingModel();
}
