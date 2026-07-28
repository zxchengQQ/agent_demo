package com.agentdemo.rag.store;

import com.agentdemo.rag.entity.DocumentInfo;
import com.agentdemo.rag.entity.DocumentStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 基于内存的文档存储实现
 * <p>
 * 业务含义：开发阶段使用的内存实现，重启后数据丢失。
 * 后续可替换为 DbDocumentStore（基于 MySQL）。
 * </p>
 */
@Component
public class InMemoryDocumentStore implements DocumentStore {

    /**
     * 文档存储（key: id, value: DocumentInfo）
     */
    private final ConcurrentHashMap<String, DocumentInfo> store = new ConcurrentHashMap<>();

    /**
     * 知识库索引（key: knowledgeBaseId, value: 该知识库下的文档 ID 列表）
     * 业务含义：加速按知识库查询文档列表，避免遍历全量数据
     */
    private final ConcurrentHashMap<String, List<String>> kbIndex = new ConcurrentHashMap<>();

    @Override
    public DocumentInfo save(DocumentInfo documentInfo) {
        store.put(documentInfo.getId(), documentInfo);
        // 业务含义：维护知识库到文档的反向索引，支持 O(1) 定位知识库下的文档列表
        kbIndex.computeIfAbsent(documentInfo.getKnowledgeBaseId(), k -> new CopyOnWriteArrayList<>())
                .add(documentInfo.getId());
        return documentInfo;
    }

    @Override
    public DocumentInfo findById(String id) {
        return store.get(id);
    }

    @Override
    public List<DocumentInfo> findByKnowledgeBaseId(String knowledgeBaseId) {
        List<String> docIds = kbIndex.getOrDefault(knowledgeBaseId, List.of());
        List<DocumentInfo> result = new ArrayList<>();
        for (String docId : docIds) {
            DocumentInfo doc = store.get(docId);
            if (doc != null) {
                result.add(doc);
            }
        }
        return result;
    }

    @Override
    public void delete(String id) {
        // 业务含义：删除文档时需同步从知识库索引中移除，保持索引一致性
        DocumentInfo doc = store.remove(id);
        if (doc != null) {
            List<String> docIds = kbIndex.get(doc.getKnowledgeBaseId());
            if (docIds != null) {
                docIds.remove(id);
            }
        }
    }

    @Override
    public void updateStatus(String id, DocumentStatus status, Integer chunkCount, String failReason) {
        // 业务含义：异步处理线程根据处理进度更新文档状态，
        // chunkCount 和 failReason 可为 null 表示该阶段不更新对应字段
        DocumentInfo doc = store.get(id);
        if (doc != null) {
            doc.setStatus(status);
            if (chunkCount != null) {
                doc.setChunkCount(chunkCount);
            }
            if (failReason != null) {
                doc.setFailReason(failReason);
            }
        }
    }
}
