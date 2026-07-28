package com.agentdemo.rag.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实体类测试
 * <p>
 * 验证 DocumentStatus 枚举值完整性，以及 KnowledgeBase、DocumentInfo 的 @Data 生成的 getter/setter。
 * </p>
 */
@DisplayName("实体类测试")
class EntityTest {

    @Test
    @DisplayName("DocumentStatus 应包含 4 个状态值")
    void documentStatusShouldContainFourValues() {
        DocumentStatus[] statuses = DocumentStatus.values();
        assertEquals(4, statuses.length, "DocumentStatus 应包含 4 个状态值");

        // 业务含义：验证 4 个状态值均存在，确保状态机流转覆盖所有阶段
        var statusList = Arrays.asList(statuses);
        assertTrue(statusList.contains(DocumentStatus.PENDING), "应包含 PENDING");
        assertTrue(statusList.contains(DocumentStatus.PROCESSING), "应包含 PROCESSING");
        assertTrue(statusList.contains(DocumentStatus.COMPLETED), "应包含 COMPLETED");
        assertTrue(statusList.contains(DocumentStatus.FAILED), "应包含 FAILED");
    }

    @Test
    @DisplayName("KnowledgeBase 的 getter/setter 应正常工作")
    void knowledgeBaseGetterSetterShouldWork() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId("kb-001");
        kb.setName("产品文档");
        kb.setDescription("产品相关技术文档");
        kb.setDocumentCount(5);
        LocalDateTime now = LocalDateTime.now();
        kb.setCreateTime(now);

        assertEquals("kb-001", kb.getId());
        assertEquals("产品文档", kb.getName());
        assertEquals("产品相关技术文档", kb.getDescription());
        assertEquals(5, kb.getDocumentCount());
        assertEquals(now, kb.getCreateTime());
    }

    @Test
    @DisplayName("DocumentInfo 的 getter/setter 应正常工作")
    void documentInfoGetterSetterShouldWork() {
        DocumentInfo doc = new DocumentInfo();
        doc.setId("doc-001");
        doc.setKnowledgeBaseId("kb-001");
        doc.setFileName("产品手册.pdf");
        doc.setFileSize(5242880L);
        doc.setFormat("pdf");
        doc.setStatus(DocumentStatus.COMPLETED);
        doc.setChunkCount(15);
        doc.setFailReason(null);
        LocalDateTime now = LocalDateTime.now();
        doc.setUploadTime(now);

        assertEquals("doc-001", doc.getId());
        assertEquals("kb-001", doc.getKnowledgeBaseId());
        assertEquals("产品手册.pdf", doc.getFileName());
        assertEquals(5242880L, doc.getFileSize());
        assertEquals("pdf", doc.getFormat());
        assertEquals(DocumentStatus.COMPLETED, doc.getStatus());
        assertEquals(15, doc.getChunkCount());
        assertNull(doc.getFailReason());
        assertEquals(now, doc.getUploadTime());
    }

    @Test
    @DisplayName("DocumentChunk 的 getter/setter 应正常工作")
    void documentChunkGetterSetterShouldWork() {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId("chunk-001");
        chunk.setDocumentId("doc-001");
        chunk.setChunkIndex(0);
        chunk.setContent("这是第一个分块的文本内容");
        chunk.setCharCount(13);

        assertEquals("chunk-001", chunk.getId());
        assertEquals("doc-001", chunk.getDocumentId());
        assertEquals(0, chunk.getChunkIndex());
        assertEquals("这是第一个分块的文本内容", chunk.getContent());
        assertEquals(13, chunk.getCharCount());
    }
}
