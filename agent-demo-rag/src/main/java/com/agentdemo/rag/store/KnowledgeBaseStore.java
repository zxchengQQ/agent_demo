package com.agentdemo.rag.store;

import com.agentdemo.rag.entity.KnowledgeBase;

import java.util.List;

/**
 * 知识库元数据存储接口
 * <p>
 * 业务含义：抽象知识库的持久化能力，支持后续从内存存储替换为数据库存储。
 * 设计原则：接口与实现分离，本阶段提供 InMemory 实现，后续可替换为 DB 实现。
 * </p>
 */
public interface KnowledgeBaseStore {

    /**
     * 保存知识库
     *
     * @param knowledgeBase 知识库实体
     * @return 保存后的知识库实体
     */
    KnowledgeBase save(KnowledgeBase knowledgeBase);

    /**
     * 按 ID 查找知识库
     *
     * @param id 知识库 ID
     * @return 知识库实体，不存在返回 null
     */
    KnowledgeBase findById(String id);

    /**
     * 按名称查找知识库
     *
     * @param name 知识库名称
     * @return 知识库实体，不存在返回 null
     */
    KnowledgeBase findByName(String name);

    /**
     * 查询所有知识库
     *
     * @return 知识库列表
     */
    List<KnowledgeBase> findAll();

    /**
     * 删除知识库
     *
     * @param id 知识库 ID
     */
    void delete(String id);

    /**
     * 更新知识库的文档计数
     *
     * @param id    知识库 ID
     * @param count 文档数量
     */
    void updateDocumentCount(String id, int count);
}
