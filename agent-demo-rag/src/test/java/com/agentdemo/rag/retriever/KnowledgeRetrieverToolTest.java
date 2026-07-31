package com.agentdemo.rag.retriever;

import com.agentdemo.llm.factory.ModelFactory;
import com.agentdemo.rag.config.RagProperties;
import com.agentdemo.rag.entity.KnowledgeBase;
import com.agentdemo.rag.store.EmbeddingStoreFactory;
import com.agentdemo.rag.store.KnowledgeBaseStore;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库检索工具测试
 * <p>
 * 验证 KnowledgeRetrieverTool 的检索逻辑与各类异常场景的处理：
 * 知识库不存在、空知识库、正常检索、无匹配结果、服务异常、maxResults 参数传递、@Tool 注解。
 * 所有外部依赖（EmbeddingStore、EmbeddingModel 等）通过 Mock 隔离。
 * </p>
 * <p>
 * 使用 @MockitoSettings(strictness = LENIENT) 宽松模式：
 * 不同测试用例对 Mock 的使用范围不同（如"知识库不存在"测试不需要 stub embeddingModel），
 * 宽松模式避免 UnnecessaryStubbingException。
 * </p>
 */
@DisplayName("知识库检索工具测试")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KnowledgeRetrieverToolTest {

    @Mock
    private KnowledgeBaseStore knowledgeBaseStore;

    @Mock
    private EmbeddingStoreFactory embeddingStoreFactory;

    @Mock
    private ModelFactory modelFactory;

    @Mock
    private RagProperties ragProperties;

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private EmbeddingStore<TextSegment> embeddingStore;

    private KnowledgeRetrieverTool knowledgeRetrieverTool;

    /** 测试用查询向量 */
    private final Embedding queryEmbedding = new Embedding(new float[]{1.0f, 2.0f, 3.0f});

    @BeforeEach
    void setUp() {
        knowledgeRetrieverTool = new KnowledgeRetrieverTool(
                knowledgeBaseStore, embeddingStoreFactory, modelFactory, ragProperties);

        // 公共 Mock：检索配置 maxResults=5
        RagProperties.Retrieval retrieval = new RagProperties.Retrieval();
        retrieval.setMaxResults(5);
        retrieval.setMinScore(0.0);
        lenient().when(ragProperties.getRetrieval()).thenReturn(retrieval);

        // 公共 Mock：EmbeddingModel 和 EmbeddingStore
        lenient().when(modelFactory.getEmbeddingModel()).thenReturn(embeddingModel);
        lenient().when(embeddingModel.embed(anyString()))
                .thenReturn(new Response<>(queryEmbedding));
        lenient().when(embeddingStoreFactory.getEmbeddingStore()).thenReturn(embeddingStore);
    }

    @Test
    @DisplayName("知识库不存在时返回提示文本")
    void searchKnowledgeWhenKbNotExistsShouldReturnHint() {
        when(knowledgeBaseStore.findByName("不存在")).thenReturn(null);

        String result = knowledgeRetrieverTool.searchKnowledge("不存在", "query");

        assertEquals("知识库 '不存在' 不存在", result);
    }

    @Test
    @DisplayName("空知识库（documentCount=0）返回空提示文本")
    void searchKnowledgeWhenKbIsEmptyShouldReturnHint() {
        KnowledgeBase kb = createKnowledgeBase("kb001", "空知识库", 0);
        when(knowledgeBaseStore.findByName("空知识库")).thenReturn(kb);
        when(knowledgeBaseStore.findById("kb001")).thenReturn(kb);

        String result = knowledgeRetrieverTool.searchKnowledge("空知识库", "query");

        assertEquals("知识库 '空知识库' 为空，暂无文档内容", result);
    }

    @Test
    @DisplayName("正常检索返回包含【片段1】的结果文本")
    void searchKnowledgeNormalShouldReturnFragments() {
        KnowledgeBase kb = createKnowledgeBase("kb001", "产品文档", 2);
        when(knowledgeBaseStore.findByName("产品文档")).thenReturn(kb);
        when(knowledgeBaseStore.findById("kb001")).thenReturn(kb);

        // 构造检索匹配结果（含来源元数据）
        List<EmbeddingMatch<TextSegment>> matches = List.of(
                new EmbeddingMatch<>(0.9, "id1", queryEmbedding,
                        TextSegment.from("产品价格为 100 元", new Metadata()
                                .put("fileName", "产品手册.pdf").put("format", "pdf").put("pageNumber", "3"))),
                new EmbeddingMatch<>(0.8, "id2", queryEmbedding,
                        TextSegment.from("支持月付和年付", new Metadata()
                                .put("fileName", "产品手册.pdf").put("format", "pdf").put("pageNumber", "5"))));
        when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(matches));

        String result = knowledgeRetrieverTool.searchKnowledge("产品文档", "价格");

        assertTrue(result.contains("【片段1】"), "结果应包含【片段1】前缀");
        assertTrue(result.contains("产品价格为 100 元"), "结果应包含第一个片段内容");
        assertTrue(result.contains("【片段2】"), "结果应包含【片段2】前缀");
        assertTrue(result.contains("来源:"), "结果应包含来源前缀");
        assertTrue(result.contains("产品手册.pdf"), "结果应包含文件名");
    }

    @Test
    @DisplayName("CR-002: PDF 文档检索结果包含来源元数据（fileName、format、pageNumber）")
    void searchKnowledgeWithPdfMetadataShouldIncludePageNumber() {
        KnowledgeBase kb = createKnowledgeBase("kb001", "产品文档", 2);
        when(knowledgeBaseStore.findByName("产品文档")).thenReturn(kb);
        when(knowledgeBaseStore.findById("kb001")).thenReturn(kb);

        List<EmbeddingMatch<TextSegment>> matches = List.of(
                new EmbeddingMatch<>(0.9, "id1", queryEmbedding,
                        TextSegment.from("PDF表格内容", new Metadata()
                                .put("fileName", "报告.pdf").put("format", "pdf").put("pageNumber", "7"))));
        when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(matches));

        String result = knowledgeRetrieverTool.searchKnowledge("产品文档", "表格");

        assertTrue(result.contains("来源:"), "结果应包含来源前缀");
        assertTrue(result.contains("报告.pdf"), "结果应包含文件名");
        assertTrue(result.contains("pdf"), "结果应包含格式");
        assertTrue(result.contains("第7页"), "PDF 结果应包含页码");
    }

    @Test
    @DisplayName("CR-002: MD 文档检索结果包含来源元数据（fileName、format、headerText）")
    void searchKnowledgeWithMdMetadataShouldIncludeHeader() {
        KnowledgeBase kb = createKnowledgeBase("kb001", "产品文档", 2);
        when(knowledgeBaseStore.findByName("产品文档")).thenReturn(kb);
        when(knowledgeBaseStore.findById("kb001")).thenReturn(kb);

        List<EmbeddingMatch<TextSegment>> matches = List.of(
                new EmbeddingMatch<>(0.9, "id1", queryEmbedding,
                        TextSegment.from("部署步骤", new Metadata()
                                .put("fileName", "架构.md").put("format", "md").put("headerText", "系统部署"))));
        when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(matches));

        String result = knowledgeRetrieverTool.searchKnowledge("产品文档", "部署");

        assertTrue(result.contains("来源:"), "结果应包含来源前缀");
        assertTrue(result.contains("架构.md"), "结果应包含文件名");
        assertTrue(result.contains("md"), "结果应包含格式");
        assertTrue(result.contains("系统部署"), "MD 结果应包含章节标题");
    }

    @Test
    @DisplayName("CR-002: 无元数据的检索结果不包含来源前缀")
    void searchKnowledgeWithoutMetadataShouldNotIncludeSource() {
        KnowledgeBase kb = createKnowledgeBase("kb001", "产品文档", 2);
        when(knowledgeBaseStore.findByName("产品文档")).thenReturn(kb);
        when(knowledgeBaseStore.findById("kb001")).thenReturn(kb);

        // TextSegment.from("text") 没有 metadata
        List<EmbeddingMatch<TextSegment>> matches = List.of(
                new EmbeddingMatch<>(0.9, "id1", queryEmbedding, TextSegment.from("无来源信息的内容")));
        when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(matches));

        String result = knowledgeRetrieverTool.searchKnowledge("产品文档", "查询");

        assertTrue(result.contains("【片段1】"), "结果应包含片段前缀");
        assertTrue(result.contains("无来源信息的内容"), "结果应包含内容");
        assertFalse(result.contains("来源:"), "无元数据时不应包含来源前缀");
    }

    @Test
    @DisplayName("matches 为空时返回未找到提示")
    void searchKnowledgeNoMatchShouldReturnHint() {
        KnowledgeBase kb = createKnowledgeBase("kb001", "产品文档", 2);
        when(knowledgeBaseStore.findByName("产品文档")).thenReturn(kb);
        when(knowledgeBaseStore.findById("kb001")).thenReturn(kb);
        when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(List.of()));

        String result = knowledgeRetrieverTool.searchKnowledge("产品文档", "无关问题");

        assertEquals("未找到与问题相关的文档", result);
    }

    @Test
    @DisplayName("EmbeddingStore 异常时返回服务不可用提示")
    void searchKnowledgeWhenStoreThrowsShouldReturnServiceUnavailable() {
        KnowledgeBase kb = createKnowledgeBase("kb001", "产品文档", 2);
        when(knowledgeBaseStore.findByName("产品文档")).thenReturn(kb);
        when(knowledgeBaseStore.findById("kb001")).thenReturn(kb);
        when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenThrow(new RuntimeException("向量数据库连接失败"));

        String result = knowledgeRetrieverTool.searchKnowledge("产品文档", "query");

        assertEquals("知识库服务暂时不可用，请稍后重试", result);
    }

    @Test
    @DisplayName("检索请求 maxResults 参数正确传递为配置值 5")
    void searchKnowledgeShouldPassMaxResultsFromConfig() {
        KnowledgeBase kb = createKnowledgeBase("kb001", "产品文档", 2);
        when(knowledgeBaseStore.findByName("产品文档")).thenReturn(kb);
        when(knowledgeBaseStore.findById("kb001")).thenReturn(kb);
        when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(List.of()));

        knowledgeRetrieverTool.searchKnowledge("产品文档", "query");

        // 捕获 EmbeddingSearchRequest 验证 maxResults 参数
        ArgumentCaptor<EmbeddingSearchRequest> captor =
                ArgumentCaptor.forClass(EmbeddingSearchRequest.class);
        verify(embeddingStore).search(captor.capture());

        // EmbeddingSearchRequest.maxResults() 返回配置的检索上限
        assertEquals(5, captor.getValue().maxResults(), "maxResults 应为配置值 5");
    }

    @Test
    @DisplayName("CR-003: searchKnowledge 不再标注 @Tool 注解")
    void searchKnowledgeShouldNotHaveToolAnnotation() throws NoSuchMethodException {
        Method method = KnowledgeRetrieverTool.class.getMethod("searchKnowledge", String.class, String.class);

        assertNull(method.getAnnotation(Tool.class), "searchKnowledge 不应再标注 @Tool 注解");
    }

    @Test
    @DisplayName("CR-003: searchByKbId 正常检索返回包含【片段1】的结果文本")
    void searchByKbIdNormalShouldReturnFragments() {
        KnowledgeBase kb = createKnowledgeBase("kb001", "产品文档", 2);
        when(knowledgeBaseStore.findById("kb001")).thenReturn(kb);

        List<EmbeddingMatch<TextSegment>> matches = List.of(
                new EmbeddingMatch<>(0.9, "id1", queryEmbedding,
                        TextSegment.from("产品价格为 100 元", new Metadata()
                                .put("fileName", "产品手册.pdf").put("format", "pdf").put("pageNumber", "3"))),
                new EmbeddingMatch<>(0.8, "id2", queryEmbedding,
                        TextSegment.from("支持月付和年付", new Metadata()
                                .put("fileName", "产品手册.pdf").put("format", "pdf").put("pageNumber", "5"))));
        when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(matches));

        String result = knowledgeRetrieverTool.searchByKbId("kb001", "价格");

        assertTrue(result.contains("【片段1】"), "结果应包含【片段1】前缀");
        assertTrue(result.contains("产品价格为 100 元"), "结果应包含第一个片段内容");
        assertTrue(result.contains("【片段2】"), "结果应包含【片段2】前缀");
        assertTrue(result.contains("来源:"), "结果应包含来源前缀");
        assertTrue(result.contains("产品文档/产品手册.pdf"), "CR-003 来源格式应为 知识库名/文件名");
    }

    @Test
    @DisplayName("CR-003: searchByKbId 知识库不存在时返回提示文本")
    void searchByKbIdWhenKbNotExistsShouldReturnHint() {
        when(knowledgeBaseStore.findById("不存在")).thenReturn(null);

        String result = knowledgeRetrieverTool.searchByKbId("不存在", "query");

        assertEquals("知识库 '不存在' 不存在", result);
    }

    @Test
    @DisplayName("CR-003: searchByKbId 空知识库返回空提示文本")
    void searchByKbIdWhenKbIsEmptyShouldReturnHint() {
        KnowledgeBase kb = createKnowledgeBase("kb001", "空知识库", 0);
        when(knowledgeBaseStore.findById("kb001")).thenReturn(kb);

        String result = knowledgeRetrieverTool.searchByKbId("kb001", "query");

        assertEquals("知识库 '空知识库' 为空，暂无文档内容", result);
    }

    @Test
    @DisplayName("CR-003: searchByKbId matches 为空时返回未找到提示")
    void searchByKbIdNoMatchShouldReturnHint() {
        KnowledgeBase kb = createKnowledgeBase("kb001", "产品文档", 2);
        when(knowledgeBaseStore.findById("kb001")).thenReturn(kb);
        when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(List.of()));

        String result = knowledgeRetrieverTool.searchByKbId("kb001", "无关问题");

        assertEquals("未找到与问题相关的文档", result);
    }

    @Test
    @DisplayName("CR-003: searchByKbId EmbeddingStore 异常时返回服务不可用提示")
    void searchByKbIdWhenStoreThrowsShouldReturnServiceUnavailable() {
        KnowledgeBase kb = createKnowledgeBase("kb001", "产品文档", 2);
        when(knowledgeBaseStore.findById("kb001")).thenReturn(kb);
        when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenThrow(new RuntimeException("向量数据库连接失败"));

        String result = knowledgeRetrieverTool.searchByKbId("kb001", "query");

        assertEquals("知识库服务暂时不可用，请稍后重试", result);
    }

    @Test
    @DisplayName("CR-003: searchKnowledge 委托给 searchByKbId 仍可用")
    void searchKnowledgeShouldDelegateToSearchByKbId() {
        KnowledgeBase kb = createKnowledgeBase("kb001", "产品文档", 2);
        when(knowledgeBaseStore.findByName("产品文档")).thenReturn(kb);
        when(knowledgeBaseStore.findById("kb001")).thenReturn(kb);
        when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(List.of()));

        String result = knowledgeRetrieverTool.searchKnowledge("产品文档", "query");

        assertEquals("未找到与问题相关的文档", result);
        verify(knowledgeBaseStore).findById("kb001");
    }

    // ==================== 辅助方法 ====================

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
}
