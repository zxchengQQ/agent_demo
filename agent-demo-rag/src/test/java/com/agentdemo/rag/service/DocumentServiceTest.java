package com.agentdemo.rag.service;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.llm.factory.ModelFactory;
import com.agentdemo.rag.config.RagProperties;
import com.agentdemo.rag.entity.DocumentChunk;
import com.agentdemo.rag.entity.DocumentInfo;
import com.agentdemo.rag.entity.DocumentStatus;
import com.agentdemo.rag.entity.KnowledgeBase;
import com.agentdemo.splitter.loader.DocumentLoader;
import com.agentdemo.splitter.loader.ParsedDocument;
import com.agentdemo.splitter.splitter.DocumentSplitterRegistry;
import com.agentdemo.rag.store.DocumentChunkStore;
import com.agentdemo.rag.store.DocumentStore;
import com.agentdemo.rag.store.EmbeddingStoreFactory;
import com.agentdemo.rag.store.KnowledgeBaseStore;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文档管理服务测试
 * <p>
 * 验证文档上传（校验、PENDING 状态返回）、异步处理（解析->分块->向量化->入库的状态流转）、
 * 状态查询、列表查询和删除逻辑。所有外部依赖通过 Mock 隔离。
 * </p>
 */
@DisplayName("文档管理服务测试")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentServiceTest {

    @Mock
    private DocumentStore documentStore;

    @Mock
    private KnowledgeBaseStore knowledgeBaseStore;

    @Mock
    private DocumentLoader documentLoader;

    @Mock
    private DocumentSplitterRegistry splitterRegistry;

    @Mock
    private EmbeddingStoreFactory embeddingStoreFactory;

    @Mock
    private ModelFactory modelFactory;

    @Mock
    private RagProperties ragProperties;

    @Mock
    private DocumentChunkStore documentChunkStore;

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private EmbeddingStore<TextSegment> embeddingStore;

    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        documentService = spy(new DocumentService(
                documentStore, knowledgeBaseStore, documentLoader,
                splitterRegistry, embeddingStoreFactory, modelFactory,
                ragProperties, documentChunkStore));
    }

    // ==================== upload 测试 ====================

    @Test
    @DisplayName("上传文档成功，返回 PENDING 状态的 DocumentInfo")
    void uploadShouldReturnPendingDocument() {
        KnowledgeBase kb = createKnowledgeBase("kb001", "测试知识库", 0);
        when(knowledgeBaseStore.findById("kb001")).thenReturn(kb);
        when(ragProperties.getDocument()).thenReturn(createDocumentConfig());

        doNothing().when(documentService)
                .processDocument(anyString(), any(byte[].class), anyString(), anyString());

        MultipartFile file = new MockMultipartFile("file", "test.txt", "text/plain", "test content".getBytes());

        DocumentInfo result = documentService.upload("kb001", file);

        assertNotNull(result.getId(), "文档 ID 不应为 null");
        assertEquals("test.txt", result.getFileName());
        assertEquals("txt", result.getFormat());
        assertEquals(DocumentStatus.PENDING, result.getStatus(), "上传后状态应为 PENDING");
        assertEquals(0, result.getChunkCount(), "初始分块数应为 0");
        verify(documentStore).save(any(DocumentInfo.class));
        verify(knowledgeBaseStore).updateDocumentCount("kb001", 1);
    }

    @Test
    @DisplayName("上传到不存在的知识库抛 RAG_KNOWLEDGE_BASE_NOT_FOUND")
    void uploadToNonExistentKbShouldThrow() {
        when(knowledgeBaseStore.findById("不存在")).thenReturn(null);

        MultipartFile file = new MockMultipartFile("file", "test.txt", "text/plain", "test".getBytes());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> documentService.upload("不存在", file));

        assertEquals(ErrorCode.RAG_KNOWLEDGE_BASE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("上传超过 10MB 的文件抛 RAG_DOCUMENT_SIZE_EXCEEDED")
    void uploadOversizedFileShouldThrow() {
        KnowledgeBase kb = createKnowledgeBase("kb001", "测试知识库", 0);
        when(knowledgeBaseStore.findById("kb001")).thenReturn(kb);
        when(ragProperties.getDocument()).thenReturn(createDocumentConfig());

        byte[] largeContent = new byte[11 * 1024 * 1024];
        MultipartFile file = new MockMultipartFile("file", "large.txt", "text/plain", largeContent);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> documentService.upload("kb001", file));

        assertEquals(ErrorCode.RAG_DOCUMENT_SIZE_EXCEEDED, ex.getErrorCode());
    }

    @Test
    @DisplayName("上传不支持的格式抛 RAG_DOCUMENT_FORMAT_UNSUPPORTED")
    void uploadUnsupportedFormatShouldThrow() {
        KnowledgeBase kb = createKnowledgeBase("kb001", "测试知识库", 0);
        when(knowledgeBaseStore.findById("kb001")).thenReturn(kb);
        when(ragProperties.getDocument()).thenReturn(createDocumentConfig());

        MultipartFile file = new MockMultipartFile("file", "doc.docx", "application/octet-stream", "test".getBytes());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> documentService.upload("kb001", file));

        assertEquals(ErrorCode.RAG_DOCUMENT_FORMAT_UNSUPPORTED, ex.getErrorCode());
    }

    // ==================== processDocument 测试 ====================

    @Test
    @DisplayName("文档处理正常流程：PROCESSING -> COMPLETED，chunkCount > 0")
    void processDocumentNormalFlowShouldComplete() {
        setupProcessDocumentMocks();

        ParsedDocument parsedDoc = ParsedDocument.builder()
                .text("这是一段测试文本内容，用于验证分块和向量化流程").format("txt").build();
        when(documentLoader.load(any(byte[].class), eq("txt"))).thenReturn(parsedDoc);

        when(embeddingModel.embedAll(anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<TextSegment> segments = invocation.getArgument(0);
            List<Embedding> embeddings = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                embeddings.add(new Embedding(new float[]{1.0f, 2.0f, 3.0f}));
            }
            return new Response<>(embeddings);
        });

        byte[] fileBytes = "test".getBytes();
        documentService.processDocument("doc001", fileBytes, "txt", "kb001");

        verify(documentStore).updateStatus("doc001", DocumentStatus.PROCESSING, null, null);

        ArgumentCaptor<Integer> chunkCountCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(documentStore).updateStatus(eq("doc001"), eq(DocumentStatus.COMPLETED), chunkCountCaptor.capture(), eq(null));
        assertTrue(chunkCountCaptor.getValue() > 0, "完成的分块数应大于 0");
    }

    @Test
    @DisplayName("文档解析失败：status=FAILED，failReason=文档解析失败")
    void processDocumentParseFailureShouldFail() {
        setupProcessDocumentMocks();

        when(documentLoader.load(any(byte[].class), eq("pdf")))
                .thenThrow(new BusinessException(ErrorCode.RAG_DOCUMENT_PARSE_FAILED, "解析失败"));

        byte[] fileBytes = "corrupted".getBytes();
        documentService.processDocument("doc002", fileBytes, "pdf", "kb001");

        verify(documentStore).updateStatus("doc002", DocumentStatus.PROCESSING, null, null);
        verify(documentStore).updateStatus("doc002", DocumentStatus.FAILED, null, "文档解析失败");
    }

    @Test
    @DisplayName("文本向量化失败：status=FAILED，failReason=向量化失败")
    void processDocumentEmbeddingFailureShouldFail() {
        setupProcessDocumentMocks();

        ParsedDocument parsedDoc = ParsedDocument.builder().text("测试文本内容").format("txt").build();
        when(documentLoader.load(any(byte[].class), eq("txt"))).thenReturn(parsedDoc);

        when(modelFactory.getEmbeddingModel()).thenReturn(embeddingModel);
        when(embeddingModel.embedAll(anyList()))
                .thenThrow(new RuntimeException("Embedding API 调用失败"));

        byte[] fileBytes = "test".getBytes();
        documentService.processDocument("doc003", fileBytes, "txt", "kb001");

        verify(documentStore).updateStatus("doc003", DocumentStatus.PROCESSING, null, null);
        verify(documentStore).updateStatus("doc003", DocumentStatus.FAILED, null, "向量化失败");
    }

    @Test
    @DisplayName("文档处理完成时应保存分块信息到 DocumentChunkStore")
    void processDocumentShouldSaveChunksOnCompletion() {
        setupProcessDocumentMocks();

        ParsedDocument parsedDoc = ParsedDocument.builder()
                .text("这是一段测试文本内容，用于验证分块和向量化流程").format("txt").build();
        when(documentLoader.load(any(byte[].class), eq("txt"))).thenReturn(parsedDoc);

        when(embeddingModel.embedAll(anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<TextSegment> segments = invocation.getArgument(0);
            List<Embedding> embeddings = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                embeddings.add(new Embedding(new float[]{1.0f, 2.0f, 3.0f}));
            }
            return new Response<>(embeddings);
        });

        byte[] fileBytes = "test".getBytes();
        documentService.processDocument("doc001", fileBytes, "txt", "kb001");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DocumentChunk>> chunksCaptor = ArgumentCaptor.forClass(List.class);
        verify(documentChunkStore).saveChunks(eq("doc001"), chunksCaptor.capture());
        List<DocumentChunk> savedChunks = chunksCaptor.getValue();
        assertTrue(savedChunks.size() > 0, "保存的分块列表不应为空");
        for (int i = 0; i < savedChunks.size(); i++) {
            assertEquals(i, savedChunks.get(i).getChunkIndex(), "分块索引应从 0 开始递增");
            assertEquals("doc001", savedChunks.get(i).getDocumentId(), "分块应关联正确的文档 ID");
            assertNotNull(savedChunks.get(i).getContent(), "分块内容不应为 null");
            assertTrue(savedChunks.get(i).getCharCount() > 0, "分块字符数应大于 0");
        }
    }

    @Test
    @DisplayName("CR-002: DocumentChunk 保存时包含来源元数据（fileName、format、pageNumber）")
    void processDocumentShouldSaveChunksWithMetadata() {
        setupProcessDocumentMocks();

        // Mock DocumentInfo with fileName
        DocumentInfo docInfo = new DocumentInfo();
        docInfo.setId("doc001");
        docInfo.setFileName("测试文档.pdf");
        docInfo.setFormat("pdf");
        when(documentStore.findById("doc001")).thenReturn(docInfo);

        // Mock splitterRegistry.split to return segments WITH metadata
        List<TextSegment> testSegments = List.of(
                TextSegment.from("第一个分块内容", new Metadata()
                        .put("fileName", "测试文档.pdf").put("format", "pdf").put("pageNumber", "1")),
                TextSegment.from("第二个分块内容", new Metadata()
                        .put("fileName", "测试文档.pdf").put("format", "pdf").put("pageNumber", "2")));
        when(splitterRegistry.split(any(ParsedDocument.class), anyString(), anyString(), any()))
                .thenReturn(testSegments);

        ParsedDocument parsedDoc = ParsedDocument.builder()
                .text("这是一段测试文本内容").format("pdf").build();
        when(documentLoader.load(any(byte[].class), eq("pdf"))).thenReturn(parsedDoc);

        when(embeddingModel.embedAll(anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<TextSegment> segments = invocation.getArgument(0);
            List<Embedding> embeddings = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                embeddings.add(new Embedding(new float[]{1.0f, 2.0f, 3.0f}));
            }
            return new Response<>(embeddings);
        });

        byte[] fileBytes = "test".getBytes();
        documentService.processDocument("doc001", fileBytes, "pdf", "kb001");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DocumentChunk>> chunksCaptor = ArgumentCaptor.forClass(List.class);
        verify(documentChunkStore).saveChunks(eq("doc001"), chunksCaptor.capture());
        List<DocumentChunk> savedChunks = chunksCaptor.getValue();

        assertTrue(savedChunks.size() > 0, "保存的分块列表不应为空");
        for (DocumentChunk chunk : savedChunks) {
            assertNotNull(chunk.getMetadata(), "分块 metadata 不应为 null");
            assertEquals("测试文档.pdf", chunk.getMetadata().get("fileName"), "metadata 应包含 fileName");
            assertEquals("pdf", chunk.getMetadata().get("format"), "metadata 应包含 format");
        }
        // 验证第一个分块包含 pageNumber
        assertEquals("1", savedChunks.get(0).getMetadata().get("pageNumber"), "PDF 分块应包含 pageNumber");
        assertEquals("2", savedChunks.get(1).getMetadata().get("pageNumber"), "第二个分块 pageNumber 应为 2");
    }

    @Test
    @DisplayName("文档解析失败时不应保存分块信息")
    void processDocumentParseFailureShouldNotSaveChunks() {
        setupProcessDocumentMocks();

        when(documentLoader.load(any(byte[].class), eq("pdf")))
                .thenThrow(new BusinessException(ErrorCode.RAG_DOCUMENT_PARSE_FAILED, "解析失败"));

        byte[] fileBytes = "corrupted".getBytes();
        documentService.processDocument("doc002", fileBytes, "pdf", "kb001");

        verify(documentChunkStore, org.mockito.Mockito.never()).saveChunks(anyString(), anyList());
    }

    // ==================== getStatus 测试 ====================

    @Test
    @DisplayName("查询文档状态返回 DocumentInfo")
    void getStatusShouldReturnDocument() {
        DocumentInfo doc = createDocumentInfo("doc001", "kb001", "test.txt");
        when(documentStore.findById("doc001")).thenReturn(doc);

        DocumentInfo result = documentService.getStatus("doc001");

        assertEquals("doc001", result.getId());
        assertEquals("test.txt", result.getFileName());
    }

    @Test
    @DisplayName("查询不存在的文档抛 RAG_DOCUMENT_NOT_FOUND")
    void getStatusNonExistentShouldThrow() {
        when(documentStore.findById("不存在")).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> documentService.getStatus("不存在"));

        assertEquals(ErrorCode.RAG_DOCUMENT_NOT_FOUND, ex.getErrorCode());
    }

    // ==================== listByKnowledgeBase 测试 ====================

    @Test
    @DisplayName("查询知识库下文档列表")
    void listByKnowledgeBaseShouldReturnList() {
        List<DocumentInfo> docs = List.of(
                createDocumentInfo("doc001", "kb001", "file1.txt"),
                createDocumentInfo("doc002", "kb001", "file2.pdf"));
        when(documentStore.findByKnowledgeBaseId("kb001")).thenReturn(docs);

        List<DocumentInfo> result = documentService.listByKnowledgeBase("kb001");

        assertEquals(2, result.size(), "应返回 2 个文档");
    }

    // ==================== delete 测试 ====================

    @Test
    @DisplayName("删除文档成功：删除向量->删除记录->递减计数")
    void deleteShouldRemoveDocAndDecrementCount() {
        DocumentInfo doc = createDocumentInfo("doc001", "kb001", "test.txt");
        KnowledgeBase kb = createKnowledgeBase("kb001", "测试知识库", 1);

        when(documentStore.findById("doc001")).thenReturn(doc);
        when(knowledgeBaseStore.findById("kb001")).thenReturn(kb);
        when(embeddingStoreFactory.getEmbeddingStore()).thenReturn(embeddingStore);

        documentService.delete("doc001");

        verify(embeddingStore).removeAll(any(Filter.class));
        verify(documentChunkStore).deleteChunks("doc001");
        verify(documentStore).delete("doc001");
        verify(knowledgeBaseStore).updateDocumentCount("kb001", 0);
    }

    @Test
    @DisplayName("删除不存在的文档抛 RAG_DOCUMENT_NOT_FOUND")
    void deleteNonExistentShouldThrow() {
        when(documentStore.findById("不存在")).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> documentService.delete("不存在"));

        assertEquals(ErrorCode.RAG_DOCUMENT_NOT_FOUND, ex.getErrorCode());
    }

    // ==================== 辅助方法 ====================

    /**
     * 设置 processDocument 测试所需的公共 Mock
     */
    private void setupProcessDocumentMocks() {
        // splitterRegistry.split 返回测试分块
        List<TextSegment> testSegments = List.of(
                TextSegment.from("这是第一个分块内容"),
                TextSegment.from("这是第二个分块内容"));
        lenient().when(splitterRegistry.split(any(ParsedDocument.class), anyString(), anyString(), any()))
                .thenReturn(testSegments);

        RagProperties.Document document = new RagProperties.Document();
        document.setTempDir(System.getProperty("java.io.tmpdir") + "/rag-test");
        lenient().when(ragProperties.getDocument()).thenReturn(document);

        lenient().when(modelFactory.getEmbeddingModel()).thenReturn(embeddingModel);
        lenient().when(embeddingStoreFactory.getEmbeddingStore()).thenReturn(embeddingStore);
    }

    /**
     * 创建测试用知识库
     */
    private KnowledgeBase createKnowledgeBase(String id, String name, int docCount) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(id);
        kb.setName(name);
        kb.setDocumentCount(docCount);
        return kb;
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
        doc.setUploadTime(java.time.LocalDateTime.now());
        return doc;
    }

    /**
     * 创建文档配置（支持格式 + 临时目录）
     */
    private RagProperties.Document createDocumentConfig() {
        RagProperties.Document document = new RagProperties.Document();
        document.setSupportedFormats(List.of("txt", "md", "pdf"));
        document.setTempDir(System.getProperty("java.io.tmpdir") + "/rag-test");
        return document;
    }
}
