package com.agentdemo.rag.store;

import com.agentdemo.rag.entity.DocumentInfo;
import com.agentdemo.rag.entity.DocumentStatus;

import java.util.List;

/**
 * 文档元数据存储接口
 * <p>
 * 业务含义：抽象文档元数据的持久化能力，支持后续从内存存储替换为数据库存储。
 * 设计原则：接口与实现分离，本阶段提供 InMemory 实现，后续可替换为 DB 实现。
 * </p>
 */
public interface DocumentStore {

    /**
     * 保存文档信息
     *
     * @param documentInfo 文档信息实体
     * @return 保存后的文档信息实体
     */
    DocumentInfo save(DocumentInfo documentInfo);

    /**
     * 按 ID 查找文档
     *
     * @param id 文档 ID
     * @return 文档信息实体，不存在返回 null
     */
    DocumentInfo findById(String id);

    /**
     * 按知识库 ID 查找文档列表
     *
     * @param knowledgeBaseId 知识库 ID
     * @return 文档信息列表，无文档返回空列表
     */
    List<DocumentInfo> findByKnowledgeBaseId(String knowledgeBaseId);

    /**
     * 删除文档
     *
     * @param id 文档 ID
     */
    void delete(String id);

    /**
     * 更新文档处理状态
     *
     * @param id         文档 ID
     * @param status     处理状态
     * @param chunkCount 分块数量（可为 null，表示不更新）
     * @param failReason 失败原因（可为 null，表示不更新）
     */
    void updateStatus(String id, DocumentStatus status, Integer chunkCount, String failReason);
}
