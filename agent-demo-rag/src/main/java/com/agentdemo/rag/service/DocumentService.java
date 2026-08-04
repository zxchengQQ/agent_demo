package com.agentdemo.rag.service;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.common.utils.SimpleTokenEstimator;
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
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 文档管理服务
 * <p>
 * 业务含义：负责文档上传、异步处理（解析->分块->向量化->入库）、状态查询和删除。
 * 文档上传后立即返回 PENDING 状态，后台线程异步执行处理流程，
 * 前端通过轮询 getStatus 获取处理进度。每个阶段失败都会标记文档为 FAILED 并记录原因。
 * </p>
 */
@Slf4j
@Service
public class DocumentService {

    /** 单个文档大小上限：10MB，与 DocumentLoader 保持一致 */
    private static final long MAX_SIZE = 10 * 1024 * 1024; // 10MB

    private final DocumentStore documentStore;
    private final KnowledgeBaseStore knowledgeBaseStore;
    private final DocumentLoader documentLoader;
    private final DocumentSplitterRegistry splitterRegistry;
    private final EmbeddingStoreFactory embeddingStoreFactory;
    private final ModelFactory modelFactory;
    private final RagProperties ragProperties;
    private final DocumentChunkStore documentChunkStore;
    /** CR-002 新增：PDF 图片提取器，仅在 PDF 且 extract-images=true 时触发 */
    private final ImageExtractor imageExtractor;
    /** CR-002 新增：图片描述生成器，调用视觉模型生成图片文本描述 */
    private final ImageDescriptor imageDescriptor;
    /** CR-002 新增：分割配置，用于读取 PDF 图片提取参数（extract-images/image-dpi） */
    private final SplitterProperties splitterProperties;

    public DocumentService(DocumentStore documentStore,
                           KnowledgeBaseStore knowledgeBaseStore,
                           DocumentLoader documentLoader,
                           DocumentSplitterRegistry splitterRegistry,
                           EmbeddingStoreFactory embeddingStoreFactory,
                           ModelFactory modelFactory,
                           RagProperties ragProperties,
                           DocumentChunkStore documentChunkStore,
                           ImageExtractor imageExtractor,
                           ImageDescriptor imageDescriptor,
                           SplitterProperties splitterProperties) {
        this.documentStore = documentStore;
        this.knowledgeBaseStore = knowledgeBaseStore;
        this.documentLoader = documentLoader;
        this.splitterRegistry = splitterRegistry;
        this.embeddingStoreFactory = embeddingStoreFactory;
        this.modelFactory = modelFactory;
        this.ragProperties = ragProperties;
        this.documentChunkStore = documentChunkStore;
        this.imageExtractor = imageExtractor;
        this.imageDescriptor = imageDescriptor;
        this.splitterProperties = splitterProperties;
    }

    /**
     * 上传文档
     * <p>
     * 业务含义：校验知识库存在性和文件合法性后，创建文档记录（PENDING），
     * 保存临时文件并触发异步处理。立即返回文档信息供前端轮询状态。
     * 知识库文档计数 +1 在此处完成，避免异步处理完成后才计数导致前端列表显示不同步。
     * </p>
     *
     * @param knowledgeBaseId 知识库 ID
     * @param file            上传的文件
     * @return 文档信息（状态为 PENDING）
     * @throws BusinessException 知识库不存在/格式不支持/大小超限
     */
    public DocumentInfo upload(String knowledgeBaseId, MultipartFile file) {
        // 校验知识库存在性：文档必须归属于有效知识库
        KnowledgeBase knowledgeBase = knowledgeBaseStore.findById(knowledgeBaseId);
        if (knowledgeBase == null) {
            throw new BusinessException(ErrorCode.RAG_KNOWLEDGE_BASE_NOT_FOUND,
                    "知识库不存在: " + knowledgeBaseId);
        }

        // 从文件名提取格式扩展名：取最后一个 "." 后的部分并转小写
        String fileName = file.getOriginalFilename();
        String[] parts = fileName.split("\\.");
        String format = parts[parts.length - 1].toLowerCase();

        // 校验格式：不在配置的支持列表内直接拒绝
        if (!ragProperties.getDocument().getSupportedFormats().contains(format)) {
            throw new BusinessException(ErrorCode.RAG_DOCUMENT_FORMAT_UNSUPPORTED,
                    "不支持的文档格式: " + format);
        }

        // 校验大小：超过 10MB 拒绝上传，防止内存溢出
        if (file.getSize() > MAX_SIZE) {
            throw new BusinessException(ErrorCode.RAG_DOCUMENT_SIZE_EXCEEDED,
                    "文档大小超过限制: " + file.getSize() + " bytes");
        }

        // 生成文档 ID（UUID 去横线，保证分布式唯一且 URL 友好）
        String documentId = UUID.randomUUID().toString().replace("-", "");

        DocumentInfo docInfo = new DocumentInfo();
        docInfo.setId(documentId);
        docInfo.setKnowledgeBaseId(knowledgeBaseId);
        docInfo.setFileName(fileName);
        docInfo.setFileSize(file.getSize());
        docInfo.setFormat(format);
        docInfo.setStatus(DocumentStatus.PENDING);
        docInfo.setChunkCount(0);
        docInfo.setFailReason(null);
        docInfo.setUploadTime(LocalDateTime.now());

        documentStore.save(docInfo);

        // 保存临时文件：异步处理线程需要读取文件内容进行解析
        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (IOException e) {
            log.error("读取文件内容失败, fileName={}", fileName, e);
            throw new BusinessException(ErrorCode.RAG_DOCUMENT_PARSE_FAILED, "读取文件内容失败", e);
        }
        saveTempFile(documentId, fileBytes);

        // 触发异步处理：解析->分块->向量化->入库
        processDocument(documentId, fileBytes, format, knowledgeBaseId);

        // 知识库文档计数 +1
        knowledgeBaseStore.updateDocumentCount(knowledgeBaseId, knowledgeBase.getDocumentCount() + 1);

        return docInfo;
    }

    /**
     * 异步处理文档
     * <p>
     * 业务含义：文档处理流水线，依次执行解析->分块->向量化->入库，
     * 每个阶段失败都会标记文档为 FAILED 并记录失败原因，便于前端展示和用户重试。
     * 处理完成后删除临时文件，避免磁盘空间泄漏。
     * </p>
     *
     * @param documentId      文档 ID
     * @param fileBytes       文件字节数组
     * @param format          文件格式
     * @param knowledgeBaseId 知识库 ID
     */
    @Async("ragTaskExecutor")
    public void processDocument(String documentId, byte[] fileBytes, String format, String knowledgeBaseId) {
        // 阶段 1：标记为处理中，通知前端文档开始处理
        documentStore.updateStatus(documentId, DocumentStatus.PROCESSING, null, null);

        // 阶段 2：解析文档为 ParsedDocument（含全文和可选的结构化分节）
        ParsedDocument parsedDoc;
        try {
            parsedDoc = documentLoader.load(fileBytes, format);
        } catch (Exception e) {
            log.error("文档解析失败, documentId={}", documentId, e);
            documentStore.updateStatus(documentId, DocumentStatus.FAILED, null, "文档解析失败");
            deleteTempFile(documentId);
            return;
        }

        // 阶段 3：文本分块，通过 DocumentSplitterRegistry 路由到专属分割器
        // 专属分割器按文件类型执行结构感知分割，失败时自动回退通用分割器
        // metadata（knowledgeBaseId、documentId、format、fileName 等）由 Registry 统一注入
        DocumentInfo docInfo = documentStore.findById(documentId);
        String fileName = docInfo != null ? docInfo.getFileName() : null;
        List<TextSegment> segments = splitterRegistry.split(parsedDoc, knowledgeBaseId, documentId, fileName);

        // 阶段 3.5：PDF 图片处理分支（CR-002 新增）
        // 业务含义：PDF 文档且开启图片提取时，将每页渲染为图片，调用视觉模型生成文本描述，
        // 描述作为独立 TextSegment（chunkType=image）追加到分块列表，与文本分块一同向量化入库。
        // 容错策略：图片提取/描述失败均不中断主流程，仅记录 WARN 并跳过图片（AC-024）
        if (isPdfImageExtractionEnabled(format)) {
            segments = processPdfImages(segments, fileBytes, documentId, knowledgeBaseId, fileName, format);
        }

        // 阶段 4：向量化，将文本分块批量转为 Embedding 向量
        // 业务含义：Embedding API 限制每次最多 10 个输入，需分批处理避免超限
        List<Embedding> embeddings;
        try {
            EmbeddingModel embeddingModel = modelFactory.getEmbeddingModel();
            embeddings = batchEmbed(embeddingModel, segments);
        } catch (Exception e) {
            log.error("文本向量化失败, documentId={}", documentId, e);
            documentStore.updateStatus(documentId, DocumentStatus.FAILED, null, "向量化失败");
            deleteTempFile(documentId);
            return;
        }

        // 阶段 5：向量入库，将 Embedding 和对应 TextSegment 存入向量存储
        try {
            EmbeddingStore<TextSegment> embeddingStore = embeddingStoreFactory.getEmbeddingStore();
            embeddingStore.addAll(embeddings, segments);
        } catch (Exception e) {
            log.error("向量存储失败, documentId={}", documentId, e);
            documentStore.updateStatus(documentId, DocumentStatus.FAILED, null, "向量存储失败");
            deleteTempFile(documentId);
            return;
        }

        // 阶段 5.5：保存分块信息到 DocumentChunkStore（CR-001 新增）
        // 业务含义：将分块文本内容独立存储，供前端查询展示。
        // 与 EmbeddingStore 中的 TextSegment 不同，此处保留分块索引和原始文本。
        // CR-002: 从 TextSegment.metadata 提取来源元数据（fileName、format、pageNumber/headerText）存入 DocumentChunk
        List<DocumentChunk> chunks = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            TextSegment segment = segments.get(i);
            DocumentChunk chunk = new DocumentChunk();
            chunk.setId(UUID.randomUUID().toString().replace("-", ""));
            chunk.setDocumentId(documentId);
            chunk.setChunkIndex(i);
            chunk.setContent(segment.text());
            chunk.setCharCount(segment.text().length());
            chunk.setTokenCount(SimpleTokenEstimator.estimate(segment.text()));
            // CR-002: 提取来源元数据（fileName、format、pageNumber、headerText 等已知字段）
            // 同时包含图片描述分块的元数据（chunkType、imagePath、imageDescription）
            Map<String, String> chunkMetadata = new HashMap<>();
            String[] sourceKeys = {"fileName", "format", "pageNumber", "headerText", "headerLevel",
                    "chunkType", "imagePath", "imageDescription"};
            for (String key : sourceKeys) {
                if (segment.metadata().containsKey(key)) {
                    chunkMetadata.put(key, segment.metadata().getString(key));
                }
            }
            chunk.setMetadata(chunkMetadata);
            chunks.add(chunk);
        }
        documentChunkStore.saveChunks(documentId, chunks);

        // 阶段 6：标记为已完成，记录分块数量
        documentStore.updateStatus(documentId, DocumentStatus.COMPLETED, segments.size(), null);
        deleteTempFile(documentId);
        log.info("文档处理完成, documentId={}, chunkCount={}", documentId, segments.size());
    }

    /**
     * 查询文档状态
     *
     * @param documentId 文档 ID
     * @return 文档信息
     * @throws BusinessException 文档不存在时抛出 RAG_DOCUMENT_NOT_FOUND
     */
    public DocumentInfo getStatus(String documentId) {
        DocumentInfo doc = documentStore.findById(documentId);
        if (doc == null) {
            throw new BusinessException(ErrorCode.RAG_DOCUMENT_NOT_FOUND,
                    "文档不存在: " + documentId);
        }
        return doc;
    }

    /**
     * 查询知识库下文档列表
     *
     * @param knowledgeBaseId 知识库 ID
     * @return 文档列表
     */
    public List<DocumentInfo> listByKnowledgeBase(String knowledgeBaseId) {
        return documentStore.findByKnowledgeBaseId(knowledgeBaseId);
    }

    /**
     * 查询文档的分块列表
     * <p>
     * 业务含义：返回文档处理完成时保存的分块信息，供前端展示分块详情。
     * 文档不存在或未处理完成时返回空列表。
     * </p>
     *
     * @param documentId 文档 ID
     * @return 分块列表，无数据返回空列表
     */
    public List<DocumentChunk> getChunks(String documentId) {
        return documentChunkStore.getChunks(documentId);
    }

    /**
     * 删除文档
     * <p>
     * 业务含义：删除文档需同时清理向量存储中的 Embedding 数据，
     * 避免检索到指向已删除文档的孤儿向量。同时递减知识库文档计数。
     * </p>
     *
     * @param documentId 文档 ID
     * @throws BusinessException 文档不存在时抛出 RAG_DOCUMENT_NOT_FOUND
     */
    public void delete(String documentId) {
        // 校验存在性
        DocumentInfo doc = documentStore.findById(documentId);
        if (doc == null) {
            throw new BusinessException(ErrorCode.RAG_DOCUMENT_NOT_FOUND,
                    "文档不存在: " + documentId);
        }

        // 删除向量数据：按 metadata 中的 documentId 过滤删除
        removeEmbeddingByDocumentId(documentId);

        // 删除分块记录（CR-001 新增）
        documentChunkStore.deleteChunks(documentId);

        // 删除文档元数据记录
        documentStore.delete(documentId);

        // 递减知识库文档计数
        KnowledgeBase kb = knowledgeBaseStore.findById(doc.getKnowledgeBaseId());
        if (kb != null) {
            knowledgeBaseStore.updateDocumentCount(kb.getId(), Math.max(0, kb.getDocumentCount() - 1));
        }

        // 删除文档对应的图片目录（BUG 修复：避免删除文档后残留孤儿图片数据占满磁盘）
        deleteImageDir(documentId);

        log.info("删除文档完成, documentId={}", documentId);
    }

    /**
     * 保存临时文件
     * <p>
     * 业务含义：异步处理线程通过文件 ID 定位临时文件读取内容，
     * 临时目录不存在时自动创建。
     * </p>
     *
     * @param documentId 文档 ID（作为临时文件名）
     * @param fileBytes  文件字节数组
     */
    private void saveTempFile(String documentId, byte[] fileBytes) {
        try {
            Path tempDir = Paths.get(ragProperties.getDocument().getTempDir());
            Files.createDirectories(tempDir);
            Path tempFile = tempDir.resolve(documentId);
            Files.write(tempFile, fileBytes);
        } catch (IOException e) {
            log.error("保存临时文件失败, documentId={}", documentId, e);
        }
    }

    /**
     * 删除临时文件
     *
     * @param documentId 文档 ID（作为临时文件名）
     */
    private void deleteTempFile(String documentId) {
        try {
            Path tempFile = Paths.get(ragProperties.getDocument().getTempDir()).resolve(documentId);
            Files.deleteIfExists(tempFile);
        } catch (IOException e) {
            log.warn("删除临时文件失败, documentId={}", documentId, e);
        }
    }

    /**
     * 删除文档对应的图片目录（BUG 修复新增）
     * <p>
     * 业务含义：PDF 图片提取时会在 {tempDir}/images/{documentId}/ 下保存图片文件，
     * 删除文档时需递归清理该目录，避免残留孤儿图片数据占满磁盘。
     * </p>
     * <p>
     * 容错策略：
     * 1. 目录不存在时直接返回（非 PDF 文档或未开启图片提取时无该目录，属正常情况）
     * 2. 单个文件删除失败仅记录 WARN，继续清理其余文件，避免一处失败导致整目录残留
     * 3. 整体 IOException 不抛出，不阻断删除主流程（文档元数据已删除，图片残留仅占磁盘不影响功能）
     * </p>
     *
     * @param documentId 文档 ID（作为图片子目录名）
     */
    private void deleteImageDir(String documentId) {
        Path imageDir = Paths.get(ragProperties.getDocument().getTempDir(), "images", documentId);
        if (!Files.exists(imageDir)) {
            return;
        }
        // 递归删除：先按 reverseOrder 排序确保子文件/目录先于父目录被删除
        try (Stream<Path> walk = Files.walk(imageDir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            log.warn("删除图片文件失败: path={}, error={}", path, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("删除文档图片目录失败: documentId={}, imageDir={}", documentId, imageDir, e);
            return;
        }
        log.info("删除文档图片目录完成: documentId={}, imageDir={}", documentId, imageDir);
    }

    /**
     * 按 documentId 删除向量存储中的 Embedding 数据
     * <p>
     * 业务含义：文档上传时为每个 TextSegment 添加了 metadata("documentId", documentId)，
     * 删除时通过 metadata 过滤器定位并删除该文档的所有向量。
     * </p>
     *
     * @param documentId 文档 ID
     */
    private void removeEmbeddingByDocumentId(String documentId) {
        try {
            EmbeddingStore<TextSegment> embeddingStore = embeddingStoreFactory.getEmbeddingStore();
            Filter filter = MetadataFilterBuilder.metadataKey("documentId").isEqualTo(documentId);
            embeddingStore.removeAll(filter);
        } catch (Exception e) {
            log.warn("删除文档向量数据失败, documentId={}", documentId, e);
        }
    }

    /**
     * 分批向量化文本分块
     * <p>
     * 业务含义：Embedding API 限制每次请求最多 10 个输入文本，
     * 当文档分块数量超过 10 时需分批调用，否则会触发 InvalidParameter 错误。
     * </p>
     *
     * @param embeddingModel 向量模型
     * @param segments       文本分块列表
     * @return 所有分块的 Embedding 向量列表（顺序与输入一致）
     */
    private List<Embedding> batchEmbed(EmbeddingModel embeddingModel, List<TextSegment> segments) {
        int batchSize = 10;
        List<Embedding> allEmbeddings = new ArrayList<>();
        for (int i = 0; i < segments.size(); i += batchSize) {
            int end = Math.min(i + batchSize, segments.size());
            List<TextSegment> batch = segments.subList(i, end);
            allEmbeddings.addAll(embeddingModel.embedAll(batch).content());
        }
        return allEmbeddings;
    }

    /**
     * 判断当前文档是否需要触发 PDF 图片提取（CR-002 新增）
     * <p>
     * 业务含义：仅 PDF 格式且 rag.splitter.pdf.extract-images=true 时触发图片提取。
     * TXT/MD 文档无图片概念，始终返回 false，避免误调用图片提取器。
     * </p>
     *
     * @param format 文件格式
     * @return 是否触发图片提取
     */
    private boolean isPdfImageExtractionEnabled(String format) {
        if (format == null || !"pdf".equalsIgnoreCase(format)) {
            return false;
        }
        SplitterProperties.PdfChunkConfig pdfConfig = splitterProperties.getPdf();
        return pdfConfig != null && pdfConfig.isExtractImages();
    }

    /**
     * 处理 PDF 图片：提取图片→生成描述→构造图片描述分块→追加到分块列表（CR-002 新增）
     * <p>
     * 业务含义：将 PDF 每页渲染为图片，调用视觉 ChatModel 生成文本描述，
     * 描述作为独立 TextSegment 追加到 segments 列表，metadata 标记 chunkType=image，
     * 包含 imagePath/imageDescription/pageNumber，供检索时定位图片来源。
     * </p>
     * <p>
     * 容错策略（AC-024）：
     * - 图片提取失败：记录 WARN，跳过图片处理，返回原 segments
     * - 视觉模型未配置/获取失败：记录 WARN，跳过描述生成，返回原 segments
     * - 单张图片描述失败：ImageDescriptor 内部已降级返回 null，此处跳过该图片
     * </p>
     *
     * @param segments        原文本分块列表
     * @param fileBytes       PDF 文件字节数组
     * @param documentId      文档 ID（用于构建图片存储子目录）
     * @param knowledgeBaseId 知识库 ID（注入到图片分块 metadata）
     * @param fileName        文件名（注入到图片分块 metadata）
     * @param format          文件格式（应为 "pdf"）
     * @return 追加了图片描述分块的完整分块列表
     */
    private List<TextSegment> processPdfImages(List<TextSegment> segments, byte[] fileBytes,
                                                String documentId, String knowledgeBaseId,
                                                String fileName, String format) {
        List<TextSegment> resultSegments = new ArrayList<>(segments);

        // 1. 提取图片
        SplitterProperties.PdfChunkConfig pdfConfig = splitterProperties.getPdf();
        Path imageDir = Paths.get(ragProperties.getDocument().getTempDir(), "images");
        List<ImageInfo> images;
        try {
            images = imageExtractor.extractImages(fileBytes, documentId, imageDir, pdfConfig.getImageDpi());
        } catch (Exception e) {
            log.warn("PDF 图片提取失败，跳过图片处理: documentId={}, error={}", documentId, e.getMessage());
            return resultSegments;
        }

        if (images == null || images.isEmpty()) {
            log.debug("PDF 未提取到图片，跳过图片处理: documentId={}", documentId);
            return resultSegments;
        }

        // 2. 获取视觉模型（未配置或失败时跳过图片描述生成）
        ChatModel visionChatModel;
        try {
            visionChatModel = modelFactory.getVisionChatModel();
        } catch (Exception e) {
            log.warn("视觉模型未配置或获取失败，跳过图片描述生成: documentId={}, error={}",
                    documentId, e.getMessage());
            return resultSegments;
        }

        // 3. 为每张图片生成描述，构造图片描述分块
        int imageCount = 0;
        for (ImageInfo image : images) {
            String description;
            try {
                description = imageDescriptor.describe(image, visionChatModel);
            } catch (Exception e) {
                // 双重防御：ImageDescriptor 内部已捕获异常，此处兜底
                log.warn("图片描述生成异常，跳过该图片: imagePath={}, error={}",
                        image.getImagePath(), e.getMessage());
                continue;
            }

            if (description == null || description.isEmpty()) {
                // 视觉模型失败/图片不可读等场景，跳过该图片（AC-024）
                continue;
            }

            // 构造图片描述分块：正文为描述文本，metadata 标记 chunkType=image 并包含来源信息
            Metadata imageMetadata = new Metadata()
                    .put("knowledgeBaseId", knowledgeBaseId)
                    .put("documentId", documentId)
                    .put("format", format)
                    .put("chunkType", "image")
                    .put("imagePath", image.getImagePath())
                    .put("imageDescription", description)
                    .put("pageNumber", String.valueOf(image.getPageNumber()));
            if (fileName != null) {
                imageMetadata = imageMetadata.put("fileName", fileName);
            }
            TextSegment imageSegment = TextSegment.from(description, imageMetadata);
            resultSegments.add(imageSegment);
            imageCount++;
        }

        log.info("PDF 图片处理完成: documentId={}, totalImages={}, describedImages={}",
                documentId, images.size(), imageCount);
        return resultSegments;
    }
}
