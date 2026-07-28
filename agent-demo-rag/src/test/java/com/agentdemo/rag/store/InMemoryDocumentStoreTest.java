package com.agentdemo.rag.store;

import com.agentdemo.rag.entity.DocumentInfo;
import com.agentdemo.rag.entity.DocumentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文档内存存储测试
 * <p>
 * 验证 InMemoryDocumentStore 的 CRUD 操作、知识库索引一致性和状态更新逻辑。
 * </p>
 */
@DisplayName("文档内存存储测试")
class InMemoryDocumentStoreTest {

    private InMemoryDocumentStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryDocumentStore();
    }

    private DocumentInfo createDoc(String id, String kbId) {
        DocumentInfo doc = new DocumentInfo();
        doc.setId(id);
        doc.setKnowledgeBaseId(kbId);
        doc.setFileName("test.pdf");
        doc.setFileSize(1024L);
        doc.setFormat("pdf");
        doc.setStatus(DocumentStatus.PENDING);
        doc.setChunkCount(0);
        doc.setUploadTime(LocalDateTime.now());
        return doc;
    }

    @Test
    @DisplayName("save 后 findById 返回该文档")
    void saveAndFindByIdShouldReturnDocument() {
        DocumentInfo doc = createDoc("doc-001", "kb-001");
        store.save(doc);

        DocumentInfo found = store.findById("doc-001");
        assertNotNull(found, "save 后应能通过 ID 找到文档");
        assertEquals("test.pdf", found.getFileName());
    }

    @Test
    @DisplayName("findByKnowledgeBaseId 返回该知识库下文档列表，无文档返回空列表")
    void findByKnowledgeBaseIdShouldReturnList() {
        store.save(createDoc("doc-001", "kb-001"));
        store.save(createDoc("doc-002", "kb-001"));
        store.save(createDoc("doc-003", "kb-002"));

        List<DocumentInfo> docs = store.findByKnowledgeBaseId("kb-001");
        assertEquals(2, docs.size(), "kb-001 下应有 2 个文档");

        List<DocumentInfo> empty = store.findByKnowledgeBaseId("kb-999");
        assertTrue(empty.isEmpty(), "无文档的知识库应返回空列表");
    }

    @Test
    @DisplayName("delete 后 findById 返回 null")
    void deleteShouldRemoveDocument() {
        store.save(createDoc("doc-001", "kb-001"));

        store.delete("doc-001");
        assertNull(store.findById("doc-001"), "删除后 findById 应返回 null");
    }

    @Test
    @DisplayName("updateStatus 更新状态为 PROCESSING")
    void updateStatusToProcessing() {
        store.save(createDoc("doc-001", "kb-001"));

        store.updateStatus("doc-001", DocumentStatus.PROCESSING, null, null);
        DocumentInfo found = store.findById("doc-001");
        assertEquals(DocumentStatus.PROCESSING, found.getStatus(), "状态应更新为 PROCESSING");
    }

    @Test
    @DisplayName("updateStatus 更新状态为 COMPLETED 并设置 chunkCount")
    void updateStatusToCompleted() {
        store.save(createDoc("doc-001", "kb-001"));

        store.updateStatus("doc-001", DocumentStatus.COMPLETED, 15, null);
        DocumentInfo found = store.findById("doc-001");
        assertEquals(DocumentStatus.COMPLETED, found.getStatus(), "状态应更新为 COMPLETED");
        assertEquals(15, found.getChunkCount(), "chunkCount 应更新为 15");
    }

    @Test
    @DisplayName("updateStatus 更新状态为 FAILED 并设置 failReason")
    void updateStatusToFailed() {
        store.save(createDoc("doc-001", "kb-001"));

        store.updateStatus("doc-001", DocumentStatus.FAILED, null, "文档解析失败");
        DocumentInfo found = store.findById("doc-001");
        assertEquals(DocumentStatus.FAILED, found.getStatus(), "状态应更新为 FAILED");
        assertEquals("文档解析失败", found.getFailReason(), "failReason 应更新为文档解析失败");
    }
}
