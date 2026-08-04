package com.agentdemo.rag.service;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.llm.factory.ModelFactory;
import com.agentdemo.rag.config.RagProperties;
import com.agentdemo.rag.entity.DocumentChunk;
import com.agentdemo.rag.entity.DocumentInfo;
import com.agentdemo.rag.entity.DocumentStatus;
import com.agentdemo.rag.entity.KnowledgeBase;
import com.agentdemo.splitter.config.SplitterProperties;
import com.agentdemo.splitter.loader.DocumentLoader;
import com.agentdemo.splitter.loader.ParsedDocument;
import com.agentdemo.splitter.splitter.DocumentSplitterRegistry;
import com.agentdemo.splitter.splitter.image.ImageDescriptor;
import com.agentdemo.splitter.splitter.image.ImageExtractor;
import com.agentdemo.splitter.splitter.image.ImageInfo;
import com.agentdemo.rag.store.DocumentChunkStore;
import com.agentdemo.rag.store.DocumentStore;
import com.agentdemo.rag.store.EmbeddingStoreFactory;
import com.agentdemo.rag.store.KnowledgeBaseStore;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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

    @Mock
    private ImageExtractor imageExtractor;

    @Mock
    private ImageDescriptor imageDescriptor;

    @Mock
    private SplitterProperties splitterProperties;

    @Mock
    private ChatModel visionChatModel;

    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        documentService = spy(new DocumentService(
                documentStore, knowledgeBaseStore, documentLoader,
                splitterRegistry, embeddingStoreFactory, modelFactory,
                ragProperties, documentChunkStore,
                imageExtractor, imageDescriptor, splitterProperties));
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

    // ==================== CR-002: 图片处理分支测试 ====================

    @Test
    @DisplayName("AC-023: PDF 文档开启图片提取后，分块列表应包含 chunkType=image 的图片描述分块")
    void processPdfWithImageExtractionShouldAddImageSegments() {
        setupProcessDocumentMocks();

        // 开启图片提取
        SplitterProperties.PdfChunkConfig pdfConfig = new SplitterProperties.PdfChunkConfig(1200, 200, 600);
        pdfConfig.setExtractImages(true);
        pdfConfig.setImageDpi(144);
        when(splitterProperties.getPdf()).thenReturn(pdfConfig);

        // Mock PDF 解析与文本分块
        ParsedDocument parsedDoc = ParsedDocument.builder().text("文本内容").format("pdf").build();
        when(documentLoader.load(any(byte[].class), eq("pdf"))).thenReturn(parsedDoc);

        List<TextSegment> textSegments = new ArrayList<>(List.of(
                TextSegment.from("第一个文本分块", new Metadata().put("pageNumber", "1")),
                TextSegment.from("第二个文本分块", new Metadata().put("pageNumber", "2"))));
        when(splitterRegistry.split(any(ParsedDocument.class), anyString(), anyString(), any()))
                .thenReturn(textSegments);

        // Mock 图片提取返回 2 张图片
        List<ImageInfo> images = List.of(
                ImageInfo.builder().imagePath("/tmp/page1_img0.png").pageNumber(1).imageIndex(0).build(),
                ImageInfo.builder().imagePath("/tmp/page2_img0.png").pageNumber(2).imageIndex(0).build());
        when(imageExtractor.extractImages(any(byte[].class), anyString(), any(), anyInt()))
                .thenReturn(images);

        // Mock 图片描述生成
        when(imageDescriptor.describe(any(ImageInfo.class), eq(visionChatModel)))
                .thenReturn("第一页图片描述")
                .thenReturn("第二页图片描述");

        // Mock 向量化
        when(embeddingModel.embedAll(anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<TextSegment> segments = invocation.getArgument(0);
            List<Embedding> embeddings = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                embeddings.add(new Embedding(new float[]{1.0f, 2.0f, 3.0f}));
            }
            return new Response<>(embeddings);
        });

        byte[] fileBytes = "pdf-bytes".getBytes();
        documentService.processDocument("docPdf01", fileBytes, "pdf", "kb001");

        // 验证：保存的分块包含文本分块 + 2 个图片分块 = 4
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DocumentChunk>> chunksCaptor = ArgumentCaptor.forClass(List.class);
        verify(documentChunkStore).saveChunks(eq("docPdf01"), chunksCaptor.capture());
        List<DocumentChunk> savedChunks = chunksCaptor.getValue();

        long imageChunkCount = savedChunks.stream()
                .filter(c -> "image".equals(c.getMetadata().get("chunkType")))
                .count();
        assertEquals(2, imageChunkCount, "应包含 2 个 chunkType=image 的图片描述分块");
        assertEquals(4, savedChunks.size(), "总分块数应为 2 个文本 + 2 个图片 = 4");
    }

    @Test
    @DisplayName("AC-023: 图片描述分块的 metadata 应包含 imagePath/imageDescription/pageNumber")
    void imageSegmentMetadataShouldContainImageInfo() {
        setupProcessDocumentMocks();

        SplitterProperties.PdfChunkConfig pdfConfig = new SplitterProperties.PdfChunkConfig(1200, 200, 600);
        pdfConfig.setExtractImages(true);
        when(splitterProperties.getPdf()).thenReturn(pdfConfig);

        ParsedDocument parsedDoc = ParsedDocument.builder().text("文本").format("pdf").build();
        when(documentLoader.load(any(byte[].class), eq("pdf"))).thenReturn(parsedDoc);
        when(splitterRegistry.split(any(ParsedDocument.class), anyString(), anyString(), any()))
                .thenReturn(new ArrayList<>(List.of(TextSegment.from("文本分块"))));

        when(imageExtractor.extractImages(any(byte[].class), anyString(), any(), anyInt()))
                .thenReturn(List.of(ImageInfo.builder()
                        .imagePath("/tmp/docX/page1_img0.png")
                        .pageNumber(3)
                        .imageIndex(0)
                        .build()));
        when(imageDescriptor.describe(any(ImageInfo.class), eq(visionChatModel)))
                .thenReturn("第三页包含柱状图");

        when(embeddingModel.embedAll(anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<TextSegment> segments = invocation.getArgument(0);
            List<Embedding> embeddings = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                embeddings.add(new Embedding(new float[]{1.0f}));
            }
            return new Response<>(embeddings);
        });

        documentService.processDocument("docPdf02", "pdf".getBytes(), "pdf", "kb001");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DocumentChunk>> chunksCaptor = ArgumentCaptor.forClass(List.class);
        verify(documentChunkStore).saveChunks(eq("docPdf02"), chunksCaptor.capture());
        List<DocumentChunk> savedChunks = chunksCaptor.getValue();

        DocumentChunk imageChunk = savedChunks.stream()
                .filter(c -> "image".equals(c.getMetadata().get("chunkType")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("应存在 chunkType=image 的分块"));

        assertEquals("/tmp/docX/page1_img0.png", imageChunk.getMetadata().get("imagePath"),
                "metadata 应包含 imagePath");
        assertEquals("第三页包含柱状图", imageChunk.getMetadata().get("imageDescription"),
                "metadata 应包含 imageDescription");
        assertEquals("3", imageChunk.getMetadata().get("pageNumber"),
                "metadata 应包含 pageNumber");
        assertEquals("image", imageChunk.getMetadata().get("chunkType"),
                "metadata 应标记 chunkType=image");
        assertEquals("第三页包含柱状图", imageChunk.getContent(),
                "图片描述分块正文应为图片描述文本");
    }

    @Test
    @DisplayName("AC-023: 图片描述分块应与文本分块一同向量化入库")
    void imageSegmentsShouldBeEmbeddedTogetherWithTextSegments() {
        setupProcessDocumentMocks();

        SplitterProperties.PdfChunkConfig pdfConfig = new SplitterProperties.PdfChunkConfig(1200, 200, 600);
        pdfConfig.setExtractImages(true);
        when(splitterProperties.getPdf()).thenReturn(pdfConfig);

        ParsedDocument parsedDoc = ParsedDocument.builder().text("文本").format("pdf").build();
        when(documentLoader.load(any(byte[].class), eq("pdf"))).thenReturn(parsedDoc);
        when(splitterRegistry.split(any(ParsedDocument.class), anyString(), anyString(), any()))
                .thenReturn(new ArrayList<>(List.of(TextSegment.from("文本分块"))));

        when(imageExtractor.extractImages(any(byte[].class), anyString(), any(), anyInt()))
                .thenReturn(List.of(ImageInfo.builder()
                        .imagePath("/tmp/p1.png").pageNumber(1).imageIndex(0).build()));
        when(imageDescriptor.describe(any(ImageInfo.class), eq(visionChatModel)))
                .thenReturn("图片描述内容");

        when(embeddingModel.embedAll(anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<TextSegment> segments = invocation.getArgument(0);
            List<Embedding> embeddings = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                embeddings.add(new Embedding(new float[]{1.0f}));
            }
            return new Response<>(embeddings);
        });

        documentService.processDocument("docPdf03", "pdf".getBytes(), "pdf", "kb001");

        // 验证：embedAll 调用时入参的 segments 应同时包含文本分块和图片描述分块
        ArgumentCaptor<List<TextSegment>> embedCaptor = ArgumentCaptor.forClass(List.class);
        verify(embeddingModel).embedAll(embedCaptor.capture());
        List<TextSegment> embeddedSegments = embedCaptor.getValue();

        // 由于 batchEmbed 分批（每批 10 个），1 个文本 + 1 个图片描述应一批完成
        assertTrue(embeddedSegments.size() >= 2,
                "向量化时应至少包含 1 个文本 + 1 个图片描述分块，实际: " + embeddedSegments.size());

        // 验证 embeddingStore.addAll 入参含图片描述分块
        ArgumentCaptor<List<TextSegment>> storeCaptor = ArgumentCaptor.forClass(List.class);
        verify(embeddingStore).addAll(anyList(), storeCaptor.capture());
        List<TextSegment> storedSegments = storeCaptor.getValue();
        boolean hasImageSegment = storedSegments.stream()
                .anyMatch(s -> "image".equals(s.metadata().getString("chunkType")));
        assertTrue(hasImageSegment, "向量存储应包含 chunkType=image 的图片描述分块");
    }

    @Test
    @DisplayName("AC-024: 视觉模型失败时图片被跳过，文档状态为 COMPLETED")
    void visionModelFailureShouldSkipImagesAndComplete() {
        setupProcessDocumentMocks();

        SplitterProperties.PdfChunkConfig pdfConfig = new SplitterProperties.PdfChunkConfig(1200, 200, 600);
        pdfConfig.setExtractImages(true);
        when(splitterProperties.getPdf()).thenReturn(pdfConfig);

        ParsedDocument parsedDoc = ParsedDocument.builder().text("文本").format("pdf").build();
        when(documentLoader.load(any(byte[].class), eq("pdf"))).thenReturn(parsedDoc);
        when(splitterRegistry.split(any(ParsedDocument.class), anyString(), anyString(), any()))
                .thenReturn(new ArrayList<>(List.of(TextSegment.from("文本分块"))));

        when(imageExtractor.extractImages(any(byte[].class), anyString(), any(), anyInt()))
                .thenReturn(List.of(ImageInfo.builder()
                        .imagePath("/tmp/p1.png").pageNumber(1).imageIndex(0).build()));
        // 视觉模型失败，describe 返回 null
        when(imageDescriptor.describe(any(ImageInfo.class), eq(visionChatModel)))
                .thenReturn(null);

        when(embeddingModel.embedAll(anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<TextSegment> segments = invocation.getArgument(0);
            List<Embedding> embeddings = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                embeddings.add(new Embedding(new float[]{1.0f}));
            }
            return new Response<>(embeddings);
        });

        documentService.processDocument("docPdf04", "pdf".getBytes(), "pdf", "kb001");

        // 验证：文档状态为 COMPLETED（未因图片失败而失败）
        verify(documentStore).updateStatus(eq("docPdf04"), eq(DocumentStatus.COMPLETED), anyInt(), eq(null));

        // 验证：保存的分块仅含文本分块（图片被跳过）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DocumentChunk>> chunksCaptor = ArgumentCaptor.forClass(List.class);
        verify(documentChunkStore).saveChunks(eq("docPdf04"), chunksCaptor.capture());
        List<DocumentChunk> savedChunks = chunksCaptor.getValue();
        boolean hasImageChunk = savedChunks.stream()
                .anyMatch(c -> "image".equals(c.getMetadata().get("chunkType")));
        assertFalse(hasImageChunk, "视觉模型失败时图片应被跳过，不应包含图片描述分块");
    }

    @Test
    @DisplayName("extract-images=false 时不执行图片处理（分块列表仅含文本）")
    void imageExtractionDisabledShouldNotProcessImages() {
        setupProcessDocumentMocks();

        // 显式禁用图片提取
        SplitterProperties.PdfChunkConfig pdfConfig = new SplitterProperties.PdfChunkConfig(1200, 200, 600);
        pdfConfig.setExtractImages(false);
        when(splitterProperties.getPdf()).thenReturn(pdfConfig);

        ParsedDocument parsedDoc = ParsedDocument.builder().text("文本").format("pdf").build();
        when(documentLoader.load(any(byte[].class), eq("pdf"))).thenReturn(parsedDoc);
        when(splitterRegistry.split(any(ParsedDocument.class), anyString(), anyString(), any()))
                .thenReturn(new ArrayList<>(List.of(TextSegment.from("文本分块"))));

        when(embeddingModel.embedAll(anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<TextSegment> segments = invocation.getArgument(0);
            List<Embedding> embeddings = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                embeddings.add(new Embedding(new float[]{1.0f}));
            }
            return new Response<>(embeddings);
        });

        documentService.processDocument("docPdf05", "pdf".getBytes(), "pdf", "kb001");

        // 验证：从未调用 imageExtractor
        verify(imageExtractor, never())
                .extractImages(any(byte[].class), anyString(), any(), anyInt());
        // 验证：从未调用 visionChatModel
        verify(modelFactory, never()).getVisionChatModel();
    }

    @Test
    @DisplayName("TXT 文档不触发图片处理")
    void txtDocumentShouldNotTriggerImageProcessing() {
        setupProcessDocumentMocks();

        // TXT 不应触发图片提取（即使 PDF 配置开启也不应生效）
        SplitterProperties.PdfChunkConfig pdfConfig = new SplitterProperties.PdfChunkConfig(1200, 200, 600);
        pdfConfig.setExtractImages(true);
        when(splitterProperties.getPdf()).thenReturn(pdfConfig);

        ParsedDocument parsedDoc = ParsedDocument.builder().text("文本内容").format("txt").build();
        when(documentLoader.load(any(byte[].class), eq("txt"))).thenReturn(parsedDoc);
        when(splitterRegistry.split(any(ParsedDocument.class), anyString(), anyString(), any()))
                .thenReturn(new ArrayList<>(List.of(TextSegment.from("文本分块"))));

        when(embeddingModel.embedAll(anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<TextSegment> segments = invocation.getArgument(0);
            List<Embedding> embeddings = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                embeddings.add(new Embedding(new float[]{1.0f}));
            }
            return new Response<>(embeddings);
        });

        documentService.processDocument("docTxt01", "txt".getBytes(), "txt", "kb001");

        // 验证：TXT 文档不应调用图片提取
        verify(imageExtractor, never())
                .extractImages(any(byte[].class), anyString(), any(), anyInt());
    }

    @Test
    @DisplayName("AC-024: 视觉模型未配置（抛异常）时图片被跳过，文档仍 COMPLETED")
    void visionModelNotConfiguredShouldSkipImagesAndComplete() {
        setupProcessDocumentMocks();

        SplitterProperties.PdfChunkConfig pdfConfig = new SplitterProperties.PdfChunkConfig(1200, 200, 600);
        pdfConfig.setExtractImages(true);
        when(splitterProperties.getPdf()).thenReturn(pdfConfig);

        // 视觉模型未配置：getVisionChatModel 抛 BusinessException
        when(modelFactory.getVisionChatModel())
                .thenThrow(new BusinessException(ErrorCode.LLM_MODEL_NOT_CONFIGURED, "VISION_MODEL 未配置"));

        ParsedDocument parsedDoc = ParsedDocument.builder().text("文本").format("pdf").build();
        when(documentLoader.load(any(byte[].class), eq("pdf"))).thenReturn(parsedDoc);
        when(splitterRegistry.split(any(ParsedDocument.class), anyString(), anyString(), any()))
                .thenReturn(new ArrayList<>(List.of(TextSegment.from("文本分块"))));

        when(imageExtractor.extractImages(any(byte[].class), anyString(), any(), anyInt()))
                .thenReturn(List.of(ImageInfo.builder()
                        .imagePath("/tmp/p1.png").pageNumber(1).imageIndex(0).build()));

        when(embeddingModel.embedAll(anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<TextSegment> segments = invocation.getArgument(0);
            List<Embedding> embeddings = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                embeddings.add(new Embedding(new float[]{1.0f}));
            }
            return new Response<>(embeddings);
        });

        documentService.processDocument("docPdf06", "pdf".getBytes(), "pdf", "kb001");

        // 验证：视觉模型未配置时文档仍能 COMPLETED
        verify(documentStore).updateStatus(eq("docPdf06"), eq(DocumentStatus.COMPLETED), anyInt(), eq(null));
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
        // BUG 修复后 delete() 会调用 deleteImageDir() 读取临时目录，需补充 mock
        lenient().when(ragProperties.getDocument()).thenReturn(createDocumentConfig());

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

    @Test
    @DisplayName("删除文档时同步清理图片目录（BUG 修复：避免孤儿图片数据残留）")
    void deleteShouldAlsoRemoveImageDir(@TempDir Path tempDir) throws Exception {
        // 准备：在 tempDir/images/docWithImages/ 下创建多张图片（含子结构，验证递归删除）
        Path docImageDir = tempDir.resolve("images").resolve("docWithImages");
        Files.createDirectories(docImageDir);
        Files.write(docImageDir.resolve("page1_img1.png"), new byte[]{1, 2, 3});
        Files.write(docImageDir.resolve("page2_img1.png"), new byte[]{4, 5, 6});
        assertTrue(Files.exists(docImageDir), "前置条件：图片目录应存在");

        DocumentInfo doc = createDocumentInfo("docWithImages", "kb001", "test.pdf");
        KnowledgeBase kb = createKnowledgeBase("kb001", "测试知识库", 1);

        when(documentStore.findById("docWithImages")).thenReturn(doc);
        when(knowledgeBaseStore.findById("kb001")).thenReturn(kb);
        when(embeddingStoreFactory.getEmbeddingStore()).thenReturn(embeddingStore);
        RagProperties.Document document = new RagProperties.Document();
        document.setTempDir(tempDir.toString());
        when(ragProperties.getDocument()).thenReturn(document);

        documentService.delete("docWithImages");

        // 断言：图片目录及其下所有图片文件已被递归清理
        assertFalse(Files.exists(docImageDir),
                "删除文档后图片目录应被清理，避免孤儿数据残留");
        // images 父目录可保留（其他文档可能共享），仅校验本文档子目录
        verify(documentStore).delete("docWithImages");
    }

    @Test
    @DisplayName("删除文档时无图片目录不应报错（非 PDF 或未开启图片提取的容错）")
    void deleteWithoutImageDirShouldNotFail(@TempDir Path tempDir) {
        // 准备：仅创建文档元数据，不创建图片目录（模拟 TXT 文档或未开启图片提取）
        DocumentInfo doc = createDocumentInfo("docNoImages", "kb001", "test.txt");
        KnowledgeBase kb = createKnowledgeBase("kb001", "测试知识库", 1);

        when(documentStore.findById("docNoImages")).thenReturn(doc);
        when(knowledgeBaseStore.findById("kb001")).thenReturn(kb);
        when(embeddingStoreFactory.getEmbeddingStore()).thenReturn(embeddingStore);
        RagProperties.Document document = new RagProperties.Document();
        document.setTempDir(tempDir.toString());
        when(ragProperties.getDocument()).thenReturn(document);

        // 不应抛异常，删除流程正常完成
        documentService.delete("docNoImages");

        verify(documentStore).delete("docNoImages");
        verify(knowledgeBaseStore).updateDocumentCount("kb001", 0);
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

        // CR-002: 默认禁用图片提取（不影响既有 TXT/MD 测试），具体测试用例单独覆盖
        SplitterProperties.PdfChunkConfig pdfConfig = new SplitterProperties.PdfChunkConfig(1200, 200, 600);
        pdfConfig.setExtractImages(false);
        lenient().when(splitterProperties.getPdf()).thenReturn(pdfConfig);
        lenient().when(modelFactory.getVisionChatModel()).thenReturn(visionChatModel);
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
