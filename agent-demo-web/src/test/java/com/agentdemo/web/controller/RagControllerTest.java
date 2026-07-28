package com.agentdemo.web.controller;

import com.agentdemo.common.result.Result;
import com.agentdemo.rag.entity.DocumentChunk;
import com.agentdemo.rag.entity.DocumentInfo;
import com.agentdemo.rag.entity.DocumentStatus;
import com.agentdemo.rag.entity.KnowledgeBase;
import com.agentdemo.rag.service.DocumentService;
import com.agentdemo.rag.service.KnowledgeBaseService;
import com.agentdemo.web.dto.CreateKnowledgeBaseRequest;
import com.agentdemo.web.dto.DocumentChunkResponse;
import com.agentdemo.web.dto.DocumentResponse;
import com.agentdemo.web.dto.DocumentStatusResponse;
import com.agentdemo.web.dto.KnowledgeBaseResponse;
import com.agentdemo.web.handler.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RAG 知识库接口测试
 * <p>
 * 采用直接调用 Controller 方法验证 Result 对象的方式（任务建议的备选方案），
 * 避免 standaloneSetup 消息转换器配置复杂度。参数校验失败测试使用 MockMvc 验证 400 状态码。
 * 所有 Service 依赖通过 @Mock 隔离，不启动 Spring 容器。
 * </p>
 */
@DisplayName("RAG 知识库接口测试")
@ExtendWith(MockitoExtension.class)
class RagControllerTest {

    private RagController controller;

    private MockMvc mockMvc;

    @Mock
    private KnowledgeBaseService knowledgeBaseService;

    @Mock
    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        controller = new RagController(knowledgeBaseService, documentService);
        // MockMvc 仅用于参数校验测试（@Valid 需 Spring MVC 拦截器触发）
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("POST /knowledges 合法 name 返回成功 + KnowledgeBaseResponse")
    void createKnowledgeBaseWithValidNameShouldReturnSuccess() {
        KnowledgeBase kb = createKnowledgeBase("kb001", "产品文档", "产品相关文档", 0);
        when(knowledgeBaseService.create(eq("产品文档"), eq("产品相关文档"))).thenReturn(kb);

        CreateKnowledgeBaseRequest request = new CreateKnowledgeBaseRequest();
        request.setName("产品文档");
        request.setDescription("产品相关文档");

        Result<KnowledgeBaseResponse> result = controller.create(request);

        assertTrue(result.isSuccess(), "应返回成功");
        assertNotNull(result.getData(), "响应数据不应为 null");
        assertEquals("kb001", result.getData().getId());
        assertEquals("产品文档", result.getData().getName());
        assertEquals("产品相关文档", result.getData().getDescription());
        assertEquals(0, result.getData().getDocumentCount());
    }

    @Test
    @DisplayName("POST /knowledges 空 name 返回 400（校验失败）")
    void createKnowledgeBaseWithBlankNameShouldReturn400() throws Exception {
        mockMvc.perform(post("/api/rag/knowledges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"description\":\"desc\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /knowledges 返回知识库列表")
    void listKnowledgeBasesShouldReturnList() {
        when(knowledgeBaseService.list()).thenReturn(List.of(
                createKnowledgeBase("kb001", "产品文档", "desc1", 2),
                createKnowledgeBase("kb002", "技术文档", "desc2", 5)));

        Result<List<KnowledgeBaseResponse>> result = controller.list();

        assertTrue(result.isSuccess());
        assertEquals(2, result.getData().size(), "应返回 2 个知识库");
        assertEquals("kb001", result.getData().get(0).getId());
        assertEquals("kb002", result.getData().get(1).getId());
    }

    @Test
    @DisplayName("DELETE /knowledges/{id} 返回成功")
    void deleteKnowledgeBaseShouldReturnSuccess() {
        doNothing().when(knowledgeBaseService).delete("kb001");

        Result<Void> result = controller.delete("kb001");

        assertTrue(result.isSuccess(), "删除知识库应返回成功");
        verify(knowledgeBaseService).delete("kb001");
    }

    @Test
    @DisplayName("POST /knowledges/{id}/documents 上传文件返回成功 + DocumentResponse(status=PENDING)")
    void uploadDocumentShouldReturnSuccessWithPendingStatus() {
        DocumentInfo doc = createDocumentInfo("doc001", "kb001", "test.txt", DocumentStatus.PENDING, 0);
        when(documentService.upload(eq("kb001"), any(MultipartFile.class))).thenReturn(doc);

        MultipartFile file = new MockMultipartFile(
                "file", "test.txt", "text/plain", "test content".getBytes());

        Result<DocumentResponse> result = controller.uploadDocument("kb001", file);

        assertTrue(result.isSuccess());
        assertNotNull(result.getData());
        assertEquals("doc001", result.getData().getDocumentId());
        assertEquals("test.txt", result.getData().getFileName());
        assertEquals("PENDING", result.getData().getStatus(), "上传后状态应为 PENDING");
    }

    @Test
    @DisplayName("GET /knowledges/{id}/documents 返回文档列表")
    void listDocumentsShouldReturnList() {
        when(documentService.listByKnowledgeBase("kb001")).thenReturn(List.of(
                createDocumentInfo("doc001", "kb001", "file1.txt", DocumentStatus.COMPLETED, 5),
                createDocumentInfo("doc002", "kb001", "file2.pdf", DocumentStatus.COMPLETED, 8)));

        Result<List<DocumentResponse>> result = controller.listDocuments("kb001");

        assertTrue(result.isSuccess());
        assertEquals(2, result.getData().size(), "应返回 2 个文档");
        assertEquals("doc001", result.getData().get(0).getDocumentId());
        assertEquals("doc002", result.getData().get(1).getDocumentId());
    }

    @Test
    @DisplayName("GET /documents/{id}/status 返回文档状态")
    void getDocumentStatusShouldReturnStatus() {
        DocumentInfo doc = createDocumentInfo("doc001", "kb001", "test.txt", DocumentStatus.COMPLETED, 5);
        when(documentService.getStatus("doc001")).thenReturn(doc);

        Result<DocumentStatusResponse> result = controller.getDocumentStatus("doc001");

        assertTrue(result.isSuccess());
        assertNotNull(result.getData());
        assertEquals("doc001", result.getData().getDocumentId());
        assertEquals("COMPLETED", result.getData().getStatus());
        assertEquals(5, result.getData().getChunkCount());
    }

    @Test
    @DisplayName("DELETE /documents/{id} 返回成功")
    void deleteDocumentShouldReturnSuccess() {
        doNothing().when(documentService).delete("doc001");

        Result<Void> result = controller.deleteDocument("doc001");

        assertTrue(result.isSuccess(), "删除文档应返回成功");
        verify(documentService).delete("doc001");
    }

    @Test
    @DisplayName("GET /documents/{id}/chunks 返回分块列表")
    void getDocumentChunksShouldReturnChunkList() {
        List<DocumentChunk> chunks = List.of(
                createDocumentChunk("doc001", 0, "第一个分块内容", 7),
                createDocumentChunk("doc001", 1, "第二个分块内容", 7),
                createDocumentChunk("doc001", 2, "第三个分块内容", 7));
        when(documentService.getChunks("doc001")).thenReturn(chunks);

        Result<List<DocumentChunkResponse>> result = controller.getDocumentChunks("doc001");

        assertTrue(result.isSuccess(), "应返回成功");
        assertNotNull(result.getData(), "响应数据不应为 null");
        assertEquals(3, result.getData().size(), "应返回 3 个分块");
        assertEquals(0, result.getData().get(0).getChunkIndex(), "第一个分块索引应为 0");
        assertEquals("第一个分块内容", result.getData().get(0).getContent());
        assertEquals(7, result.getData().get(0).getCharCount());
    }

    @Test
    @DisplayName("GET /documents/{id}/chunks 文档无分块时返回空列表")
    void getDocumentChunksWithNoChunksShouldReturnEmptyList() {
        when(documentService.getChunks("doc001")).thenReturn(List.of());

        Result<List<DocumentChunkResponse>> result = controller.getDocumentChunks("doc001");

        assertTrue(result.isSuccess(), "应返回成功");
        assertNotNull(result.getData(), "响应数据不应为 null");
        assertTrue(result.getData().isEmpty(), "无分块时应返回空列表");
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建测试用知识库
     */
    private KnowledgeBase createKnowledgeBase(String id, String name, String description, int docCount) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(id);
        kb.setName(name);
        kb.setDescription(description);
        kb.setDocumentCount(docCount);
        kb.setCreateTime(LocalDateTime.now());
        return kb;
    }

    /**
     * 创建测试用文档信息
     */
    private DocumentInfo createDocumentInfo(String id, String kbId, String fileName,
                                            DocumentStatus status, int chunkCount) {
        DocumentInfo doc = new DocumentInfo();
        doc.setId(id);
        doc.setKnowledgeBaseId(kbId);
        doc.setFileName(fileName);
        doc.setFileSize(1024);
        doc.setFormat(fileName.endsWith(".pdf") ? "pdf" : "txt");
        doc.setStatus(status);
        doc.setChunkCount(chunkCount);
        doc.setUploadTime(LocalDateTime.now());
        return doc;
    }

    /**
     * 创建测试用文档分块
     */
    private DocumentChunk createDocumentChunk(String documentId, int chunkIndex, String content, int charCount) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId("chunk-" + chunkIndex);
        chunk.setDocumentId(documentId);
        chunk.setChunkIndex(chunkIndex);
        chunk.setContent(content);
        chunk.setCharCount(charCount);
        return chunk;
    }
}
