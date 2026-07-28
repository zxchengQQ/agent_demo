package com.agentdemo.rag.store;

import com.agentdemo.rag.entity.DocumentChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于内存的文档分块存储实现
 * <p>
 * 业务含义：开发阶段使用的内存实现，重启后数据丢失。
 * 后续可替换为 DbDocumentChunkStore（基于 MySQL）。
 * </p>
 */
@Component
public class InMemoryDocumentChunkStore implements DocumentChunkStore {

    /**
     * 分块存储（key: documentId, value: 该文档的分块列表）
     * 业务含义：按文档 ID 隔离分块数据，支持 O(1) 定位文档的分块列表
     */
    private final ConcurrentHashMap<String, List<DocumentChunk>> store = new ConcurrentHashMap<>();

    @Override
    public void saveChunks(String documentId, List<DocumentChunk> chunks) {
        // 业务含义：保存分块时覆盖已有数据，确保分块列表与最新处理结果一致
        store.put(documentId, new ArrayList<>(chunks));
    }

    @Override
    public List<DocumentChunk> getChunks(String documentId) {
        // 业务含义：返回分块列表的副本，避免外部修改影响内部存储
        List<DocumentChunk> chunks = store.get(documentId);
        return chunks != null ? new ArrayList<>(chunks) : new ArrayList<>();
    }

    @Override
    public void deleteChunks(String documentId) {
        store.remove(documentId);
    }
}
