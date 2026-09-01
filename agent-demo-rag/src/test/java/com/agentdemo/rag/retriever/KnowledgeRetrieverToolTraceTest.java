package com.agentdemo.rag.retriever;

import com.agentdemo.llm.registry.ModelFactory;
import com.agentdemo.observability.TraceCollector;
import com.agentdemo.rag.config.RagProperties;
import com.agentdemo.rag.entity.KnowledgeBase;
import com.agentdemo.rag.store.EmbeddingStoreFactory;
import com.agentdemo.rag.store.KnowledgeBaseStore;
import com.agentdemo.tools.sanitize.ToolOutputSanitizer;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RAG 检索埋点测试（CR-001 Task-17，AC-N05/E05）
 * <p>
 * 业务含义：验证 KnowledgeRetrieverTool.searchByKbId 在成功/失败/提示文本各路径均向
 * TraceCollector 上报 RagRetrievalEvent，且埋点异常不影响检索主流程（AC-E05）。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KnowledgeRetrieverToolTraceTest {

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
    @Mock
    private TraceCollector collector;

    private final Embedding queryEmbedding = new Embedding(new float[]{1.0f, 2.0f, 3.0f});

    private KnowledgeRetrieverTool tool;

    @BeforeEach
    void setUp() {
        tool = new KnowledgeRetrieverTool(knowledgeBaseStore, embeddingStoreFactory, modelFactory,
                ragProperties, ToolOutputSanitizer.disabled(), collector);
        RagProperties.Retrieval retrieval = new RagProperties.Retrieval();
        retrieval.setMaxResults(5);
        lenient().when(ragProperties.getRetrieval()).thenReturn(retrieval);
        lenient().when(modelFactory.getEmbeddingModel()).thenReturn(embeddingModel);
        lenient().when(embeddingModel.embed(anyString())).thenReturn(new Response<>(queryEmbedding));
        lenient().when(embeddingStoreFactory.getEmbeddingStore()).thenReturn(embeddingStore);
    }

    private KnowledgeBase kb(String id, String name, int docCount) {
        KnowledgeBase k = new KnowledgeBase();
        k.setId(id);
        k.setName(name);
        k.setDocumentCount(docCount);
        return k;
    }

    @Test
    void success_retrieval_reportsRagEvent_withAllFields() {
        KnowledgeBase k = kb("kb001", "产品文档", 2);
        when(knowledgeBaseStore.findById("kb001")).thenReturn(k);
        List<EmbeddingMatch<TextSegment>> matches = List.of(
                new EmbeddingMatch<>(0.9, "id1", queryEmbedding,
                        TextSegment.from("产品价格为 100 元",
                                new Metadata().put("fileName", "产品手册.pdf"))),
                new EmbeddingMatch<>(0.8, "id2", queryEmbedding, TextSegment.from("支持月付")));
        when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(matches));

        String result = tool.searchByKbId("kb001", "价格");

        assertThat(result).contains("片段1");
        ArgumentCaptor<TraceCollector.RagRetrievalEvent> captor =
                ArgumentCaptor.forClass(TraceCollector.RagRetrievalEvent.class);
        verify(collector).recordRag(captor.capture());
        TraceCollector.RagRetrievalEvent e = captor.getValue();
        assertThat(e.kbId()).isEqualTo("kb001");
        assertThat(e.query()).isEqualTo("价格");
        assertThat(e.kbName()).isEqualTo("产品文档");
        assertThat(e.hitCount()).isEqualTo(2);
        assertThat(e.topK()).isEqualTo(5);
        assertThat(e.maxScore()).isEqualTo(0.9);
        assertThat(e.chunks()).contains("片段1").contains("产品价格为 100 元");
        assertThat(e.success()).isTrue();
        assertThat(e.durationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void exception_reportsFailureEvent_butReturnsHintToCaller() {
        KnowledgeBase k = kb("kb001", "产品文档", 2);
        when(knowledgeBaseStore.findById("kb001")).thenReturn(k);
        when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenThrow(new RuntimeException("向量数据库连接失败"));

        String result = tool.searchByKbId("kb001", "q");

        // AC-E05：主流程不中断，仍返回降级提示
        assertThat(result).contains("知识库服务暂时不可用");
        ArgumentCaptor<TraceCollector.RagRetrievalEvent> captor =
                ArgumentCaptor.forClass(TraceCollector.RagRetrievalEvent.class);
        verify(collector).recordRag(captor.capture());
        assertThat(captor.getValue().success()).isFalse();
        assertThat(captor.getValue().errorMessage()).contains("向量数据库连接失败");
    }

    @Test
    void kbNotFound_reportsHintPath_asSuccess() {
        when(knowledgeBaseStore.findById("不存在")).thenReturn(null);

        String result = tool.searchByKbId("不存在", "q");

        assertThat(result).contains("不存在");
        ArgumentCaptor<TraceCollector.RagRetrievalEvent> captor =
                ArgumentCaptor.forClass(TraceCollector.RagRetrievalEvent.class);
        verify(collector).recordRag(captor.capture());
        assertThat(captor.getValue().success()).isTrue();
        assertThat(captor.getValue().hitCount()).isZero();
    }

    @Test
    void collectorException_doesNotAffectRetrieval() {
        // AC-E05：埋点自身异常静默降级，检索主流程不受影响
        org.mockito.Mockito.doThrow(new RuntimeException("collector boom"))
                .when(collector).recordRag(any(TraceCollector.RagRetrievalEvent.class));
        KnowledgeBase k = kb("kb001", "产品文档", 2);
        when(knowledgeBaseStore.findById("kb001")).thenReturn(k);
        when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(List.of()));

        String result = tool.searchByKbId("kb001", "q");

        assertThat(result).contains("未找到与问题相关的文档");
    }
}
