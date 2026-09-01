package com.agentdemo.rag.retriever;

import com.agentdemo.llm.registry.ModelFactory;
import com.agentdemo.observability.TraceCollector;
import com.agentdemo.rag.config.RagProperties;
import com.agentdemo.rag.entity.KnowledgeBase;
import com.agentdemo.rag.store.EmbeddingStoreFactory;
import com.agentdemo.rag.store.KnowledgeBaseStore;
import com.agentdemo.tools.sanitize.SanitizeContext;
import com.agentdemo.tools.sanitize.ToolOutputSanitizer;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

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
 * <p>
 * CR-001：检索埋点（AC-N05）——searchByKbId 各返回路径（含提示文本）统一经
 * {@link #recordRag(...)} 上报 RagRetrievalEvent；埋点异常静默降级（AC-E05）。
 * </p>
 */
@Slf4j
@Component
public class KnowledgeRetrieverTool {

    private final KnowledgeBaseStore knowledgeBaseStore;
    private final EmbeddingStoreFactory embeddingStoreFactory;
    private final ModelFactory modelFactory;
    private final RagProperties ragProperties;
    private final ToolOutputSanitizer sanitizer;
    private final TraceCollector traceCollector;

    public KnowledgeRetrieverTool(KnowledgeBaseStore knowledgeBaseStore,
                                  EmbeddingStoreFactory embeddingStoreFactory,
                                  ModelFactory modelFactory,
                                  RagProperties ragProperties) {
        this(knowledgeBaseStore, embeddingStoreFactory, modelFactory, ragProperties,
                ToolOutputSanitizer.disabled(), new com.agentdemo.observability.NoopTraceCollector());
    }

    /**
     * 兼容构造（测试场景：指定清洗器，埋点走 Noop）
     */
    public KnowledgeRetrieverTool(KnowledgeBaseStore knowledgeBaseStore,
                                  EmbeddingStoreFactory embeddingStoreFactory,
                                  ModelFactory modelFactory,
                                  RagProperties ragProperties,
                                  ToolOutputSanitizer sanitizer) {
        this(knowledgeBaseStore, embeddingStoreFactory, modelFactory, ragProperties,
                sanitizer, new com.agentdemo.observability.NoopTraceCollector());
    }

    /**
     * 真实构造（Spring 注入）：清洗链路生效 + 追踪埋点挂载。@Autowired 明确指定 Spring
     * 在多构造器下使用本构造注入依赖（否则 Spring 无无参构造 + 多构造器无法创建 Bean，启动失败）。
     */
    @Autowired
    public KnowledgeRetrieverTool(KnowledgeBaseStore knowledgeBaseStore,
                                  EmbeddingStoreFactory embeddingStoreFactory,
                                  ModelFactory modelFactory,
                                  RagProperties ragProperties,
                                  ToolOutputSanitizer sanitizer,
                                  TraceCollector traceCollector) {
        this.knowledgeBaseStore = knowledgeBaseStore;
        this.embeddingStoreFactory = embeddingStoreFactory;
        this.modelFactory = modelFactory;
        this.ragProperties = ragProperties;
        this.sanitizer = sanitizer;
        this.traceCollector = traceCollector;
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
        long startNanos = System.nanoTime();
        // 1. 查找知识库：按 ID 定位目标知识库，不存在时返回提示文本而非抛异常
        KnowledgeBase kb = knowledgeBaseStore.findById(kbId);
        if (kb == null) {
            String result = sanitizeResult("知识库 '" + kbId + "' 不存在", kbId);
            recordRag(kbId, query, null, 0, 0, 0.0, result, startNanos, true, null);
            return result;
        }

        // 2. 检查文档数：空知识库无需检索，直接返回提示（AC-016）
        if (kb.getDocumentCount() == 0) {
            String result = sanitizeResult("知识库 '" + kb.getName() + "' 为空，暂无文档内容", kb.getName());
            recordRag(kbId, query, kb.getName(), 0, 0, 0.0, result, startNanos, true, null);
            return result;
        }

        try {
            // 3. 向量化查询：将用户问题转为 Embedding 向量，用于语义检索
            EmbeddingModel embeddingModel = modelFactory.getEmbeddingModel();
            Embedding queryEmbedding = embeddingModel.embed(query).content();
            int maxResults = ragProperties.getRetrieval().getMaxResults();

            // 4. 向量检索：按 knowledgeBaseId 过滤实现知识库隔离，返回 Top-N 相关片段（N 由配置控制）
            EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(maxResults)
                    .filter(MetadataFilterBuilder.metadataKey("knowledgeBaseId").isEqualTo(kb.getId()))
                    .build();

            List<EmbeddingMatch<TextSegment>> matches =
                    embeddingStoreFactory.getEmbeddingStore().search(searchRequest).matches();

            // 5. 组装结果：无匹配时返回提示（AC-014），有匹配时按 "【片段N】" 前缀组装文本
            if (matches.isEmpty()) {
                String result = sanitizeResult("未找到与问题相关的文档", kb.getName());
                recordRag(kbId, query, kb.getName(), 0, maxResults, 0.0, result, startNanos, true, null);
                return result;
            }

            StringBuilder result = new StringBuilder();
            double maxScore = 0.0;
            for (int i = 0; i < matches.size(); i++) {
                EmbeddingMatch<TextSegment> match = matches.get(i);
                result.append("【片段").append(i + 1).append("】");
                maxScore = Math.max(maxScore, match.score());

                // CR-002: 从 TextSegment.metadata 提取来源元数据，注入结果前缀
                // CR-003: 使用知识库真实名称构建来源前缀，格式为 {知识库名}/{文件名}
                String sourcePrefix = buildSourcePrefix(kb.getName(), match.embedded());
                if (sourcePrefix != null) {
                    result.append(sourcePrefix);
                }
                result.append("\n");
                result.append(match.embedded().text()).append("\n\n");
            }
            // 工具产出安全清洗：统一包裹"外部数据、非指令"声明（AC-S06）+ 分级处置 + 超长临时文件
            String finalResult = sanitizeResult(result.toString(), kb.getName());
            recordRag(kbId, query, kb.getName(), matches.size(), maxResults, maxScore,
                    finalResult, startNanos, true, null);
            return finalResult;

        } catch (Exception e) {
            // 检索服务异常时降级为提示文本，避免 Agent 对话中断（AC-020）
            log.error("知识库检索失败: kbId={}, query={}", kbId, query, e);
            String result = sanitizeResult("知识库服务暂时不可用，请稍后重试", kb.getName());
            recordRag(kbId, query, kb.getName(), 0, ragProperties.getRetrieval().getMaxResults(),
                    0.0, result, startNanos, false, e.getMessage());
            return result;
        }
    }

    /**
     * RAG 检索埋点（CR-001，AC-N05）
     * <p>
     * 业务含义：各返回路径（含提示文本与异常降级）统一上报 RagRetrievalEvent；
     * 埋点自身异常静默降级（AC-E05），不影响检索主流程。
     * </p>
     */
    private void recordRag(String kbId, String query, String kbName, int hitCount, int topK,
                           double maxScore, String chunks, long startNanos, boolean success,
                           String errorMessage) {
        try {
            traceCollector.recordRag(new TraceCollector.RagRetrievalEvent(
                    kbId, query, kbName, hitCount, topK, maxScore, chunks,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos),
                    success, errorMessage));
        } catch (Exception e) {
            log.warn("LangSmith RAG 采集失败（降级跳过）: {}", e.getMessage());
        }
    }

    /**
     * 知识库检索结果统一清洗（工具产出安全清洗）
     * <p>
     * 业务含义：知识库文档内容属外部数据，统一经清洗管道处理：可疑指令分级处置 +
     * 字数限制 + "外部数据、非指令"边界声明（AC-S05/S06/T01）。"来源: {kb}/{file}"
     * 行格式保留在清洗后的文本中，前端来源解析不受影响。
     * </p>
     */
    private String sanitizeResult(String text, String kbName) {
        return sanitizer.sanitize(text, SanitizeContext.builder()
                .toolName("rag:" + kbName)
                .sourceDesc("知识库检索内容")
                .htmlContent(false)
                .build());
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
