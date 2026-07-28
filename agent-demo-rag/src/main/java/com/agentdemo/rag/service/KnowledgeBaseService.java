package com.agentdemo.rag.service;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.rag.entity.DocumentInfo;
import com.agentdemo.rag.entity.KnowledgeBase;
import com.agentdemo.rag.store.DocumentStore;
import com.agentdemo.rag.store.EmbeddingStoreFactory;
import com.agentdemo.rag.store.KnowledgeBaseStore;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 知识库管理服务
 * <p>
 * 业务含义：知识库是文档的容器，本服务负责知识库的创建、查询和级联删除。
 * 级联删除需同时清理向量存储中的 Embedding 数据、文档元数据和知识库记录，
 * 避免产生孤儿向量导致检索结果指向已删除的文档。
 * </p>
 */
@Slf4j
@Service
public class KnowledgeBaseService {

    private final KnowledgeBaseStore knowledgeBaseStore;
    private final DocumentStore documentStore;
    private final EmbeddingStoreFactory embeddingStoreFactory;

    public KnowledgeBaseService(KnowledgeBaseStore knowledgeBaseStore,
                                DocumentStore documentStore,
                                EmbeddingStoreFactory embeddingStoreFactory) {
        this.knowledgeBaseStore = knowledgeBaseStore;
        this.documentStore = documentStore;
        this.embeddingStoreFactory = embeddingStoreFactory;
    }

    /**
     * 创建知识库
     * <p>
     * 业务含义：生成唯一 ID 并校验名称全局唯一性，防止用户创建同名知识库导致检索歧义。
     * ID 使用 UUID 去横线格式，保证分布式环境下不冲突且 URL 友好。
     * </p>
     *
     * @param name        知识库名称（全局唯一）
     * @param description 知识库描述（可为 null）
     * @return 创建后的知识库实体
     * @throws BusinessException 名称已存在时抛出 RAG_KNOWLEDGE_BASE_NAME_EXISTS
     */
    public KnowledgeBase create(String name, String description) {
        // 校验名称唯一性：同名知识库会导致 Agent 检索时定位歧义，必须拦截
        if (knowledgeBaseStore.findByName(name) != null) {
            throw new BusinessException(ErrorCode.RAG_KNOWLEDGE_BASE_NAME_EXISTS,
                    "知识库名称已存在: " + name);
        }

        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setId(UUID.randomUUID().toString().replace("-", ""));
        knowledgeBase.setName(name);
        knowledgeBase.setDescription(description);
        knowledgeBase.setDocumentCount(0);
        knowledgeBase.setCreateTime(LocalDateTime.now());

        return knowledgeBaseStore.save(knowledgeBase);
    }

    /**
     * 查询所有知识库
     *
     * @return 知识库列表
     */
    public List<KnowledgeBase> list() {
        return knowledgeBaseStore.findAll();
    }

    /**
     * 级联删除知识库
     * <p>
     * 业务含义：删除知识库时必须级联清理其下所有文档的向量数据，
     * 否则向量存储中会残留孤儿 Embedding，检索时返回指向已删除文档的无效结果。
     * 删除顺序：先删向量数据 -> 再删文档元数据 -> 最后删知识库记录，
     * 保证任何一步失败时知识库记录仍存在，便于重试。
     * </p>
     *
     * @param id 知识库 ID
     * @throws BusinessException 知识库不存在时抛出 RAG_KNOWLEDGE_BASE_NOT_FOUND
     */
    public void delete(String id) {
        // 校验存在性：删除不存在的知识库属于无效操作，明确报错而非静默成功
        KnowledgeBase knowledgeBase = knowledgeBaseStore.findById(id);
        if (knowledgeBase == null) {
            throw new BusinessException(ErrorCode.RAG_KNOWLEDGE_BASE_NOT_FOUND,
                    "知识库不存在: " + id);
        }

        // 获取知识库下所有文档，用于级联清理向量数据
        List<DocumentInfo> documents = documentStore.findByKnowledgeBaseId(id);
        EmbeddingStore<TextSegment> embeddingStore = embeddingStoreFactory.getEmbeddingStore();

        // 逐文档清理向量数据：按 metadata 中的 documentId 过滤删除对应 Embedding
        for (DocumentInfo doc : documents) {
            removeEmbeddingByDocumentId(embeddingStore, doc.getId());
        }

        // 删除所有文档元数据记录
        for (DocumentInfo doc : documents) {
            documentStore.delete(doc.getId());
        }

        // 最后删除知识库记录
        knowledgeBaseStore.delete(id);
        log.info("级联删除知识库完成, id={}, name={}, 删除文档数={}", id, knowledgeBase.getName(), documents.size());
    }

    /**
     * 按 documentId 删除向量存储中的 Embedding 数据
     * <p>
     * 业务含义：文档上传时为每个 TextSegment 添加了 metadata("documentId", documentId)，
     * 删除时通过 metadata 过滤器定位并删除该文档的所有向量，实现文档级别的向量清理。
     * </p>
     *
     * @param embeddingStore 向量存储
     * @param documentId     文档 ID
     */
    private void removeEmbeddingByDocumentId(EmbeddingStore<TextSegment> embeddingStore, String documentId) {
        try {
            // 通过 metadata 过滤器批量删除该文档的所有向量数据
            Filter filter = MetadataFilterBuilder.metadataKey("documentId").isEqualTo(documentId);
            embeddingStore.removeAll(filter);
        } catch (Exception e) {
            // 向量删除失败不阻断流程：记录日志后继续，避免残留文档元数据阻止知识库删除
            log.warn("删除文档向量数据失败, documentId={}", documentId, e);
        }
    }
}
