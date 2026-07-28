package com.agentdemo.rag.store;

import com.agentdemo.rag.entity.DocumentChunk;

import java.util.List;

/**
 * 文档分块存储接口
 * <p>
 * 业务含义：抽象文档分块信息的持久化能力，支持后续从内存存储替换为数据库存储。
 * 设计原则：接口与实现分离，本阶段提供 InMemory 实现，后续可替换为 DB 实现。
 * </p>
 */
public interface DocumentChunkStore {

    /**
     * 保存文档的分块列表（覆盖已存在的数据）
     *
     * @param documentId 文档 ID
     * @param chunks     分块列表
     */
    void saveChunks(String documentId, List<DocumentChunk> chunks);

    /**
     * 查询文档的分块列表
     *
     * @param documentId 文档 ID
     * @return 分块列表，无数据返回空列表
     */
    List<DocumentChunk> getChunks(String documentId);

    /**
     * 删除文档的所有分块（文档删除时级联调用）
     *
     * @param documentId 文档 ID
     */
    void deleteChunks(String documentId);
}
