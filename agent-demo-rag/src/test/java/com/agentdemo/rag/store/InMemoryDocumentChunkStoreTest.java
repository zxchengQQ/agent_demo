package com.agentdemo.rag.store;

import com.agentdemo.rag.entity.DocumentChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文档分块内存存储测试
 * <p>
 * 验证 InMemoryDocumentChunkStore 的保存、查询和删除操作。
 * </p>
 */
@DisplayName("文档分块内存存储测试")
class InMemoryDocumentChunkStoreTest {

    private InMemoryDocumentChunkStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryDocumentChunkStore();
    }

    /**
     * 创建测试用分块列表
     */
    private List<DocumentChunk> createChunks(String documentId, int count) {
        List<DocumentChunk> chunks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            DocumentChunk chunk = new DocumentChunk();
            chunk.setId("chunk-" + i);
            chunk.setDocumentId(documentId);
            chunk.setChunkIndex(i);
            chunk.setContent("分块内容 " + i);
            chunk.setCharCount("分块内容 ".length() + String.valueOf(i).length());
            chunks.add(chunk);
        }
        return chunks;
    }

    @Test
    @DisplayName("saveChunks 后 getChunks 返回相同的分块列表")
    void saveChunksThenGetChunksShouldReturnSameList() {
        List<DocumentChunk> chunks = createChunks("doc-001", 5);
        store.saveChunks("doc-001", chunks);

        List<DocumentChunk> result = store.getChunks("doc-001");

        assertNotNull(result, "保存后应能查询到分块列表");
        assertEquals(5, result.size(), "分块数量应为 5");
        assertEquals(0, result.get(0).getChunkIndex(), "第一个分块索引应为 0");
        assertEquals(4, result.get(4).getChunkIndex(), "最后一个分块索引应为 4");
    }

    @Test
    @DisplayName("getChunks 查询不存在的文档返回空列表")
    void getChunksForNonExistentDocumentShouldReturnEmptyList() {
        List<DocumentChunk> result = store.getChunks("doc-999");

        assertNotNull(result, "查询不存在的文档不应返回 null");
        assertTrue(result.isEmpty(), "不存在的文档应返回空列表");
    }

    @Test
    @DisplayName("deleteChunks 后 getChunks 返回空列表")
    void deleteChunksThenGetChunksShouldReturnEmpty() {
        store.saveChunks("doc-001", createChunks("doc-001", 3));

        store.deleteChunks("doc-001");

        List<DocumentChunk> result = store.getChunks("doc-001");
        assertTrue(result.isEmpty(), "删除后应返回空列表");
    }

    @Test
    @DisplayName("saveChunks 覆盖已存在的分块数据")
    void saveChunksShouldOverwriteExistingData() {
        store.saveChunks("doc-001", createChunks("doc-001", 3));

        // 重新保存 5 个分块，应覆盖原有 3 个
        store.saveChunks("doc-001", createChunks("doc-001", 5));

        List<DocumentChunk> result = store.getChunks("doc-001");
        assertEquals(5, result.size(), "覆盖后分块数量应为 5");
    }

    @Test
    @DisplayName("不同文档的分块数据互相隔离")
    void chunksForDifferentDocumentsShouldBeIsolated() {
        store.saveChunks("doc-001", createChunks("doc-001", 3));
        store.saveChunks("doc-002", createChunks("doc-002", 5));

        List<DocumentChunk> result1 = store.getChunks("doc-001");
        List<DocumentChunk> result2 = store.getChunks("doc-002");

        assertEquals(3, result1.size(), "doc-001 应有 3 个分块");
        assertEquals(5, result2.size(), "doc-002 应有 5 个分块");
    }
}
