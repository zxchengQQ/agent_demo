package com.agentdemo.rag.store;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.rag.entity.KnowledgeBase;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于内存的知识库存储实现
 * <p>
 * 业务含义：开发阶段使用的内存实现，重启后数据丢失。
 * 后续可替换为 DbKnowledgeBaseStore（基于 MySQL）。
 * </p>
 */
@Component
public class InMemoryKnowledgeBaseStore implements KnowledgeBaseStore {

    /**
     * 知识库存储（key: id, value: KnowledgeBase）
     */
    private final ConcurrentHashMap<String, KnowledgeBase> store = new ConcurrentHashMap<>();

    /**
     * 名称索引（key: name, value: id）
     * 业务含义：用于名称唯一性校验和按名查找，避免遍历全量数据
     */
    private final ConcurrentHashMap<String, String> nameIndex = new ConcurrentHashMap<>();

    @Override
    public KnowledgeBase save(KnowledgeBase knowledgeBase) {
        // 业务含义：名称全局唯一，使用 putIfAbsent 原子性检查并写入索引，
        // 返回非 null 表示名称已存在，拒绝创建
        if (nameIndex.putIfAbsent(knowledgeBase.getName(), knowledgeBase.getId()) != null) {
            throw new BusinessException(ErrorCode.RAG_KNOWLEDGE_BASE_NAME_EXISTS);
        }
        store.put(knowledgeBase.getId(), knowledgeBase);
        return knowledgeBase;
    }

    @Override
    public KnowledgeBase findById(String id) {
        return store.get(id);
    }

    @Override
    public KnowledgeBase findByName(String name) {
        String id = nameIndex.get(name);
        if (id == null) {
            return null;
        }
        return store.get(id);
    }

    @Override
    public List<KnowledgeBase> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public void delete(String id) {
        // 业务含义：删除知识库时需同步删除名称索引，避免名称被永久占用
        KnowledgeBase kb = store.remove(id);
        if (kb != null) {
            nameIndex.remove(kb.getName());
        }
    }

    @Override
    public void updateDocumentCount(String id, int count) {
        KnowledgeBase kb = store.get(id);
        if (kb != null) {
            kb.setDocumentCount(count);
        }
    }
}
