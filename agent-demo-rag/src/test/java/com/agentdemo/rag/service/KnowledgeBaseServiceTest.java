package com.agentdemo.rag.service;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.rag.entity.DocumentInfo;
import com.agentdemo.rag.entity.DocumentStatus;
import com.agentdemo.rag.entity.KnowledgeBase;
import com.agentdemo.rag.store.DocumentStore;
import com.agentdemo.rag.store.EmbeddingStoreFactory;
import com.agentdemo.rag.store.KnowledgeBaseStore;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库管理服务测试
 * <p>
 * 验证知识库创建（名称唯一性校验）、查询、级联删除（向量->文档->知识库）逻辑。
 * 所有外部依赖通过 Mock 隔离，不依赖真实存储。
 * </p>
 */
@DisplayName("知识库管理服务测试")
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseServiceTest {

    @Mock
    private KnowledgeBaseStore knowledgeBaseStore;

    @Mock
    private DocumentStore documentStore;

    @Mock
    private EmbeddingStoreFactory embeddingStoreFactory;

    @Mock
    private EmbeddingStore<TextSegment> embeddingStore;

    @InjectMocks
    private KnowledgeBaseService knowledgeBaseService;

    @Test
    @DisplayName("创建知识库成功，返回含 ID 的实体")
    void createShouldReturnKnowledgeBase() {
        when(knowledgeBaseStore.findByName("产品文档")).thenReturn(null);
        when(knowledgeBaseStore.save(any(KnowledgeBase.class))).thenAnswer(invocation -> invocation.getArgument(0));

        KnowledgeBase result = knowledgeBaseService.create("产品文档", "产品相关技术文档");

        assertNotNull(result.getId(), "ID 不应为 null");
        assertEquals("产品文档", result.getName());
        assertEquals("产品相关技术文档", result.getDescription());
        assertEquals(0, result.getDocumentCount(), "新建知识库文档数应为 0");
        assertNotNull(result.getCreateTime(), "创建时间不应为 null");
        verify(knowledgeBaseStore).save(any(KnowledgeBase.class));
    }

    @Test
    @DisplayName("创建同名知识库抛 RAG_KNOWLEDGE_BASE_NAME_EXISTS")
    void createWithDuplicateNameShouldThrow() {
        KnowledgeBase existing = new KnowledgeBase();
        existing.setName("已存在名称");
        when(knowledgeBaseStore.findByName("已存在名称")).thenReturn(existing);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> knowledgeBaseService.create("已存在名称", null));

        assertEquals(ErrorCode.RAG_KNOWLEDGE_BASE_NAME_EXISTS, ex.getErrorCode());
        verify(knowledgeBaseStore, never()).save(any(KnowledgeBase.class));
    }

    @Test
    @DisplayName("查询所有知识库调用 store.findAll")
    void listShouldCallFindAll() {
        List<KnowledgeBase> expected = List.of(new KnowledgeBase(), new KnowledgeBase());
        when(knowledgeBaseStore.findAll()).thenReturn(expected);

        List<KnowledgeBase> result = knowledgeBaseService.list();

        assertEquals(2, result.size(), "应返回 2 个知识库");
        verify(knowledgeBaseStore).findAll();
    }

    @Test
    @DisplayName("删除不存在的知识库抛 RAG_KNOWLEDGE_BASE_NOT_FOUND")
    void deleteNonExistentShouldThrow() {
        when(knowledgeBaseStore.findById("不存在")).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> knowledgeBaseService.delete("不存在"));

        assertEquals(ErrorCode.RAG_KNOWLEDGE_BASE_NOT_FOUND, ex.getErrorCode());
        verify(knowledgeBaseStore, never()).delete(anyString());
    }

    @Test
    @DisplayName("删除知识库触发级联删除：向量->文档->知识库")
    void deleteShouldCascadeRemoveDocumentsAndVectors() {
        String kbId = "kb001";
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(kbId);
        kb.setName("测试知识库");
        kb.setDocumentCount(2);

        DocumentInfo doc1 = createDocumentInfo("doc001", kbId, "file1.txt");
        DocumentInfo doc2 = createDocumentInfo("doc002", kbId, "file2.pdf");
        List<DocumentInfo> docs = List.of(doc1, doc2);

        when(knowledgeBaseStore.findById(kbId)).thenReturn(kb);
        when(documentStore.findByKnowledgeBaseId(kbId)).thenReturn(docs);
        when(embeddingStoreFactory.getEmbeddingStore()).thenReturn(embeddingStore);

        knowledgeBaseService.delete(kbId);

        // 验证：按 documentId 删除向量数据（每个文档一次）
        verify(embeddingStore, times(2)).removeAll(any(Filter.class));

        // 验证：删除每个文档记录
        verify(documentStore).delete("doc001");
        verify(documentStore).delete("doc002");

        // 验证：删除知识库记录
        verify(knowledgeBaseStore).delete(kbId);
    }

    @Test
    @DisplayName("删除无文档的知识库仅删除知识库记录")
    void deleteEmptyKnowledgeBaseShouldOnlyDeleteKb() {
        String kbId = "kb002";
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(kbId);
        kb.setName("空知识库");
        kb.setDocumentCount(0);

        when(knowledgeBaseStore.findById(kbId)).thenReturn(kb);
        when(documentStore.findByKnowledgeBaseId(kbId)).thenReturn(List.of());
        when(embeddingStoreFactory.getEmbeddingStore()).thenReturn(embeddingStore);

        knowledgeBaseService.delete(kbId);

        // 无文档时不调用向量删除
        verify(embeddingStore, never()).removeAll(any(Filter.class));
        // 仍删除知识库记录
        verify(knowledgeBaseStore).delete(kbId);
    }

    /**
     * 创建测试用文档信息
     */
    private DocumentInfo createDocumentInfo(String id, String kbId, String fileName) {
        DocumentInfo doc = new DocumentInfo();
        doc.setId(id);
        doc.setKnowledgeBaseId(kbId);
        doc.setFileName(fileName);
        doc.setFileSize(1024);
        doc.setFormat(fileName.endsWith(".pdf") ? "pdf" : "txt");
        doc.setStatus(DocumentStatus.COMPLETED);
        doc.setChunkCount(5);
        doc.setUploadTime(LocalDateTime.now());
        return doc;
    }
}
