package com.agentdemo.memory.longterm;

import java.util.List;
import java.util.Map;

/**
 * 长期记忆接口
 * <p>
 * 业务含义：定义长期记忆能力，支持将重要对话向量化存储，供后续语义检索。
 * 三级记忆架构：短期（窗口）-> 中期（摘要）-> 长期（向量）。
 * </p>
 * <p>
 * 本阶段提供空实现，后续 RAG 模块实现后替换为基于 Milvus 的实现。
 * 提前定义接口，避免后续重构 agent 层。
 * </p>
 */
public interface LongTermMemory {

    /**
     * 存储记忆（自动向量化）
     *
     * @param sessionId 会话 ID
     * @param content   记忆内容
     * @param metadata  元数据（如时间、用户、标签等）
     */
    void store(String sessionId, String content, Map<String, Object> metadata);

    /**
     * 语义检索相关记忆
     *
     * @param query 查询文本
     * @param topK  返回条数
     * @return 相关记忆列表
     */
    List<String> retrieve(String query, int topK);

    /**
     * 按会话语义检索
     *
     * @param sessionId 会话 ID
     * @param query     查询文本
     * @param topK      返回条数
     * @return 相关记忆列表
     */
    List<String> retrieveBySession(String sessionId, String query, int topK);
}
