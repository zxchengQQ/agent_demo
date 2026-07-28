package com.agentdemo.web.controller;

import com.agentdemo.common.result.Result;
import com.agentdemo.rag.entity.DocumentChunk;
import com.agentdemo.rag.entity.DocumentInfo;
import com.agentdemo.rag.entity.KnowledgeBase;
import com.agentdemo.rag.service.DocumentService;
import com.agentdemo.rag.service.KnowledgeBaseService;
import com.agentdemo.web.dto.CreateKnowledgeBaseRequest;
import com.agentdemo.web.dto.DocumentChunkResponse;
import com.agentdemo.web.dto.DocumentResponse;
import com.agentdemo.web.dto.DocumentStatusResponse;
import com.agentdemo.web.dto.KnowledgeBaseResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * RAG 知识库接口
 * <p>
 * 业务含义：提供知识库与文档管理的 REST API，支持知识库创建/查询/删除、
 * 文档上传/查询/状态轮询/删除。接口路径统一 /api/rag 前缀。
 * Controller 仅负责参数接收与结果转换，业务逻辑下沉到 Service 层。
 * </p>
 */
@Tag(name = "RAG 知识库", description = "知识库与文档管理接口")
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final DocumentService documentService;

    public RagController(KnowledgeBaseService knowledgeBaseService, DocumentService documentService) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.documentService = documentService;
    }

    /**
     * 创建知识库
     *
     * @param request 创建请求（含名称、描述）
     * @return 创建后的知识库信息
     */
    @Operation(summary = "创建知识库", description = "创建新的知识库，名称全局唯一")
    @PostMapping("/knowledges")
    public Result<KnowledgeBaseResponse> create(@Valid @RequestBody CreateKnowledgeBaseRequest request) {
        KnowledgeBase kb = knowledgeBaseService.create(request.getName(), request.getDescription());
        return Result.success(toKnowledgeBaseResponse(kb));
    }

    /**
     * 查询知识库列表
     *
     * @return 知识库列表
     */
    @Operation(summary = "查询知识库列表", description = "返回所有知识库")
    @GetMapping("/knowledges")
    public Result<List<KnowledgeBaseResponse>> list() {
        List<KnowledgeBase> knowledgeBases = knowledgeBaseService.list();
        List<KnowledgeBaseResponse> responses = knowledgeBases.stream()
                .map(this::toKnowledgeBaseResponse)
                .toList();
        return Result.success(responses);
    }

    /**
     * 删除知识库（级联删除其下所有文档与向量数据）
     *
     * @param knowledgeBaseId 知识库 ID
     * @return 操作结果
     */
    @Operation(summary = "删除知识库", description = "级联删除知识库及其下所有文档与向量数据")
    @DeleteMapping("/knowledges/{knowledgeBaseId}")
    public Result<Void> delete(@PathVariable String knowledgeBaseId) {
        knowledgeBaseService.delete(knowledgeBaseId);
        return Result.success();
    }

    /**
     * 上传文档到指定知识库
     *
     * @param knowledgeBaseId 知识库 ID
     * @param file            上传的文件
     * @return 文档信息（状态为 PENDING）
     */
    @Operation(summary = "上传文档", description = "上传文档到指定知识库，异步处理解析与向量化")
    @PostMapping("/knowledges/{knowledgeBaseId}/documents")
    public Result<DocumentResponse> uploadDocument(@PathVariable String knowledgeBaseId,
                                                   @RequestParam("file") MultipartFile file) {
        DocumentInfo doc = documentService.upload(knowledgeBaseId, file);
        return Result.success(toDocumentResponse(doc));
    }

    /**
     * 查询知识库下文档列表
     *
     * @param knowledgeBaseId 知识库 ID
     * @return 文档列表
     */
    @Operation(summary = "查询文档列表", description = "返回指定知识库下的所有文档")
    @GetMapping("/knowledges/{knowledgeBaseId}/documents")
    public Result<List<DocumentResponse>> listDocuments(@PathVariable String knowledgeBaseId) {
        List<DocumentInfo> documents = documentService.listByKnowledgeBase(knowledgeBaseId);
        List<DocumentResponse> responses = documents.stream()
                .map(this::toDocumentResponse)
                .toList();
        return Result.success(responses);
    }

    /**
     * 查询文档处理状态
     *
     * @param documentId 文档 ID
     * @return 文档状态信息
     */
    @Operation(summary = "查询文档状态", description = "查询文档处理进度，供前端轮询")
    @GetMapping("/documents/{documentId}/status")
    public Result<DocumentStatusResponse> getDocumentStatus(@PathVariable String documentId) {
        DocumentInfo doc = documentService.getStatus(documentId);
        return Result.success(toDocumentStatusResponse(doc));
    }

    /**
     * 删除文档（同时清理向量数据）
     *
     * @param documentId 文档 ID
     * @return 操作结果
     */
    @Operation(summary = "删除文档", description = "删除文档及其向量数据")
    @DeleteMapping("/documents/{documentId}")
    public Result<Void> deleteDocument(@PathVariable String documentId) {
        documentService.delete(documentId);
        return Result.success();
    }

    /**
     * 查询文档分块列表
     *
     * @param documentId 文档 ID
     * @return 分块列表
     */
    @Operation(summary = "查询文档分块列表", description = "返回文档的分块详情列表，包含分块索引、文本内容和字符数")
    @GetMapping("/documents/{documentId}/chunks")
    public Result<List<DocumentChunkResponse>> getDocumentChunks(@PathVariable String documentId) {
        List<DocumentChunk> chunks = documentService.getChunks(documentId);
        List<DocumentChunkResponse> responses = chunks.stream()
                .map(this::toDocumentChunkResponse)
                .toList();
        return Result.success(responses);
    }

    // ==================== 实体转 DTO ====================

    /**
     * KnowledgeBase 实体转 KnowledgeBaseResponse
     */
    private KnowledgeBaseResponse toKnowledgeBaseResponse(KnowledgeBase kb) {
        KnowledgeBaseResponse response = new KnowledgeBaseResponse();
        response.setId(kb.getId());
        response.setName(kb.getName());
        response.setDescription(kb.getDescription());
        response.setDocumentCount(kb.getDocumentCount());
        response.setCreateTime(kb.getCreateTime());
        return response;
    }

    /**
     * DocumentInfo 实体转 DocumentResponse
     * <p>
     * 业务含义：status 枚举转为字符串名称，便于前端直接展示且不依赖枚举定义。
     * </p>
     */
    private DocumentResponse toDocumentResponse(DocumentInfo doc) {
        DocumentResponse response = new DocumentResponse();
        response.setDocumentId(doc.getId());
        response.setFileName(doc.getFileName());
        response.setFileSize(doc.getFileSize());
        response.setFormat(doc.getFormat());
        response.setStatus(doc.getStatus().name());
        response.setChunkCount(doc.getChunkCount());
        response.setFailReason(doc.getFailReason());
        response.setUploadTime(doc.getUploadTime());
        return response;
    }

    /**
     * DocumentInfo 实体转 DocumentStatusResponse
     */
    private DocumentStatusResponse toDocumentStatusResponse(DocumentInfo doc) {
        DocumentStatusResponse response = new DocumentStatusResponse();
        response.setDocumentId(doc.getId());
        response.setStatus(doc.getStatus().name());
        response.setChunkCount(doc.getChunkCount());
        response.setFailReason(doc.getFailReason());
        return response;
    }

    /**
     * DocumentChunk 实体转 DocumentChunkResponse
     */
    private DocumentChunkResponse toDocumentChunkResponse(DocumentChunk chunk) {
        DocumentChunkResponse response = new DocumentChunkResponse();
        response.setChunkIndex(chunk.getChunkIndex());
        response.setContent(chunk.getContent());
        response.setCharCount(chunk.getCharCount());
        return response;
    }
}
