package com.agentdemo.rag.retriever;

import com.agentdemo.llm.factory.ModelFactory;
import com.agentdemo.rag.config.RagProperties;
import com.agentdemo.rag.entity.KnowledgeBase;
import com.agentdemo.rag.store.EmbeddingStoreFactory;
import com.agentdemo.rag.store.KnowledgeBaseStore;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识库检索工具
 * <p>
 * 业务含义：作为 Agent 的 @Tool 工具，Agent 在 ReAct 循环中自主决定是否调用，
 * 并根据用户问题选择目标知识库进行向量语义检索。
 * 检索流程：按知识库名称定位 -> 向量化查询 -> 按 knowledgeBaseId 过滤检索 Top-N 片段 -> 组装文本返回。
 * </p>
 * <p>
 * 设计原则：
 * 1. 工具返回纯文本（非结构化），Agent 直接在 ReAct 循环中读取，无需解析 JSON
 * 2. 异常不抛出而是返回错误提示文本，避免 Agent 对话中断（AC-020）
 * 3. 通过 metadata(knowledgeBaseId) 过滤实现知识库隔离检索
 * </p>
 */
@Slf4j
@Component
public class KnowledgeRetrieverTool {

    private final KnowledgeBaseStore knowledgeBaseStore;
    private final EmbeddingStoreFactory embeddingStoreFactory;
    private final ModelFactory modelFactory;
    private final RagProperties ragProperties;

    public KnowledgeRetrieverTool(KnowledgeBaseStore knowledgeBaseStore,
                                  EmbeddingStoreFactory embeddingStoreFactory,
                                  ModelFactory modelFactory,
                                  RagProperties ragProperties) {
        this.knowledgeBaseStore = knowledgeBaseStore;
        this.embeddingStoreFactory = embeddingStoreFactory;
        this.modelFactory = modelFactory;
        this.ragProperties = ragProperties;
    }

    /**
     * 从指定知识库中检索与用户问题相关的文档片段
     * <p>
     * 业务含义：Agent 在 ReAct 循环中判断用户问题可能涉及知识库内容时调用此工具。
     * 检索结果以 "【片段N】" 前缀组装为纯文本返回，供 LLM 作为上下文生成回答。
     * 各类异常场景（知识库不存在/为空/无结果/服务异常）均返回提示文本，保证对话不中断。
     * </p>
     *
     * @param knowledgeBaseName 知识库名称
     * @param query             检索问题
     * @return 检索结果文本或错误提示文本
     */
    @Tool("从指定知识库中检索与用户问题相关的文档片段。当用户的问题可能涉及知识库中的内容时调用此工具。参数 knowledgeBaseName 为知识库名称，query 为检索问题。")
    public String searchKnowledge(String knowledgeBaseName, String query) {
        // 1. 查找知识库：按名称定位目标知识库，不存在时返回提示文本而非抛异常（AC-024）
        KnowledgeBase kb = knowledgeBaseStore.findByName(knowledgeBaseName);
        if (kb == null) {
            return "知识库 '" + knowledgeBaseName + "' 不存在";
        }

        // 2. 检查文档数：空知识库无需检索，直接返回提示（AC-016）
        if (kb.getDocumentCount() == 0) {
            return "知识库 '" + knowledgeBaseName + "' 为空，暂无文档内容";
        }

        try {
            // 3. 向量化查询：将用户问题转为 Embedding 向量，用于语义检索
            EmbeddingModel embeddingModel = modelFactory.getEmbeddingModel();
            Embedding queryEmbedding = embeddingModel.embed(query).content();

            // 4. 向量检索：按 knowledgeBaseId 过滤实现知识库隔离，返回 Top-N 相关片段（N 由配置控制）
            EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(ragProperties.getRetrieval().getMaxResults())
                    .filter(MetadataFilterBuilder.metadataKey("knowledgeBaseId").isEqualTo(kb.getId()))
                    .build();

            List<EmbeddingMatch<TextSegment>> matches =
                    embeddingStoreFactory.getEmbeddingStore().search(searchRequest).matches();

            // 5. 组装结果：无匹配时返回提示（AC-014），有匹配时按 "【片段N】" 前缀组装文本
            if (matches.isEmpty()) {
                return "未找到与问题相关的文档";
            }

            StringBuilder result = new StringBuilder();
            for (int i = 0; i < matches.size(); i++) {
                result.append("【片段").append(i + 1).append("】\n");
                result.append(matches.get(i).embedded().text()).append("\n\n");
            }
            return result.toString();

        } catch (Exception e) {
            // 检索服务异常时降级为提示文本，避免 Agent 对话中断（AC-020）
            log.error("知识库检索失败: knowledgeBase={}, query={}", knowledgeBaseName, query, e);
            return "知识库服务暂时不可用，请稍后重试";
        }
    }
}
