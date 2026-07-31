package com.agentdemo.rag.retriever;

import com.agentdemo.llm.factory.ModelFactory;
import com.agentdemo.rag.config.RagProperties;
import com.agentdemo.rag.entity.KnowledgeBase;
import com.agentdemo.rag.store.EmbeddingStoreFactory;
import com.agentdemo.rag.store.KnowledgeBaseStore;
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
 * 知识库检索核心逻辑
 * <p>
 * 业务含义：CR-003 后作为知识库检索的核心逻辑类，被每个知识库动态生成的 Tool 实例委托调用。
 * 原单 Tool 入口 searchKnowledge 已标记为 @Deprecated，保留向后兼容。
 * 检索流程：按知识库 ID 定位 -> 向量化查询 -> 按 knowledgeBaseId 过滤检索 Top-N 片段 -> 组装文本返回。
 * </p>
 * <p>
 * 设计原则：
 * 1. 返回纯文本（非结构化），Agent 直接在 ReAct 循环中读取，无需解析 JSON
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
     * @deprecated CR-003 后改为由每个知识库独立的动态 Tool 调用 {@link #searchByKbId(String, String)}
     */
    @Deprecated(since = "CR-003", forRemoval = false)
    public String searchKnowledge(String knowledgeBaseName, String query) {
        // 1. 查找知识库：按名称定位目标知识库，不存在时返回提示文本而非抛异常（AC-024）
        KnowledgeBase kb = knowledgeBaseStore.findByName(knowledgeBaseName);
        if (kb == null) {
            return "知识库 '" + knowledgeBaseName + "' 不存在";
        }
        // CR-003: 原单 Tool 入口保留向后兼容，内部委托给按 kbId 检索的新方法
        return searchByKbId(kb.getId(), query);
    }

    /**
     * 按知识库 ID 检索与用户问题相关的文档片段
     * <p>
     * 业务含义：CR-003 新增的核心检索逻辑，供每个知识库动态生成的 Tool 实例委托调用。
     * kbId 在动态 Tool 创建时绑定，无需 LLM 传递，彻底消除知识库名称幻觉风险。
     * 检索流程：按 kbId 定位知识库 -> 向量化查询 -> 按 knowledgeBaseId 过滤检索 Top-N 片段 -> 组装文本返回。
     * 各类异常场景（知识库不存在/为空/无结果/服务异常）均返回提示文本，保证对话不中断。
     * </p>
     *
     * @param kbId 知识库 ID
     * @param query 检索问题
     * @return 检索结果文本或错误提示文本
     */
    public String searchByKbId(String kbId, String query) {
        // 1. 查找知识库：按 ID 定位目标知识库，不存在时返回提示文本而非抛异常
        KnowledgeBase kb = knowledgeBaseStore.findById(kbId);
        if (kb == null) {
            return "知识库 '" + kbId + "' 不存在";
        }

        // 2. 检查文档数：空知识库无需检索，直接返回提示（AC-016）
        if (kb.getDocumentCount() == 0) {
            return "知识库 '" + kb.getName() + "' 为空，暂无文档内容";
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
                EmbeddingMatch<TextSegment> match = matches.get(i);
                result.append("【片段").append(i + 1).append("】");

                // CR-002: 从 TextSegment.metadata 提取来源元数据，注入结果前缀
                // CR-003: 使用知识库真实名称构建来源前缀，格式为 {知识库名}/{文件名}
                String sourcePrefix = buildSourcePrefix(kb.getName(), match.embedded());
                if (sourcePrefix != null) {
                    result.append(sourcePrefix);
                }
                result.append("\n");
                result.append(match.embedded().text()).append("\n\n");
            }
            return result.toString();

        } catch (Exception e) {
            // 检索服务异常时降级为提示文本，避免 Agent 对话中断（AC-020）
            log.error("知识库检索失败: kbId={}, query={}", kbId, query, e);
            return "知识库服务暂时不可用，请稍后重试";
        }
    }

    /**
     * 从 TextSegment metadata 构建来源前缀（CR-002 修改）
     * <p>
     * 业务含义：检索结果中每个片段标注来源信息，包含知识库名和文件名，
     * 前端据此解析来源信息并展示在"引用来源"条中。
     * 格式：来源: {knowledgeBaseName}/{fileName} ({format}) {位置信息}
     * 位置信息：PDF 显示"第N页"，MD 显示章节"标题"，无位置信息时仅显示文件名和格式。
     * </p>
     *
     * @param knowledgeBaseName 知识库名称（从 searchKnowledge 参数透传）
     * @param segment 检索到的 TextSegment
     * @return 来源前缀文本，无 fileName 元数据时返回 null
     */
    private String buildSourcePrefix(String knowledgeBaseName, TextSegment segment) {
        if (!segment.metadata().containsKey("fileName")) {
            return null;
        }

        StringBuilder prefix = new StringBuilder("来源: ");
        // CR-002: 添加知识库名称，格式为 {知识库名}/{文件名}
        prefix.append(knowledgeBaseName).append("/").append(segment.metadata().getString("fileName"));

        // 追加格式
        if (segment.metadata().containsKey("format")) {
            prefix.append(" (").append(segment.metadata().getString("format")).append(")");
        }

        // 追加位置信息
        if (segment.metadata().containsKey("pageNumber")) {
            prefix.append(" 第").append(segment.metadata().getString("pageNumber")).append("页");
        } else if (segment.metadata().containsKey("headerText")) {
            prefix.append(" 章节\"").append(segment.metadata().getString("headerText")).append("\"");
        }

        return prefix.toString();
    }
}
