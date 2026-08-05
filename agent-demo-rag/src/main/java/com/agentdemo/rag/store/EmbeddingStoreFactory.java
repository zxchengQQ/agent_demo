package com.agentdemo.rag.store;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.llm.registry.ModelFactory;
import com.agentdemo.rag.config.RagProperties;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import org.springframework.stereotype.Component;

/**
 * 向量存储工厂
 * <p>
 * 业务含义：根据配置动态创建 EmbeddingStore 实例，支持 InMemory（开发）和 Milvus（生产）切换。
 * 通过懒加载 + 双重检查锁保证线程安全且只创建一次实例。
 * </p>
 */
@Component
public class EmbeddingStoreFactory {

    private final RagProperties ragProperties;
    private final ModelFactory modelFactory;

    /**
     * EmbeddingStore 实例（volatile 保证多线程可见性）
     */
    private volatile EmbeddingStore<TextSegment> embeddingStore;

    public EmbeddingStoreFactory(RagProperties ragProperties, ModelFactory modelFactory) {
        this.ragProperties = ragProperties;
        this.modelFactory = modelFactory;
    }

    /**
     * 获取 EmbeddingStore 实例（懒加载，双重检查锁）
     * <p>
     * 业务含义：首次调用时根据配置创建实例，后续复用，避免重复初始化。
     * 双重检查锁保证多线程环境下只创建一个实例。
     * </p>
     *
     * @return EmbeddingStore 实例
     */
    public EmbeddingStore<TextSegment> getEmbeddingStore() {
        if (embeddingStore == null) {
            synchronized (this) {
                if (embeddingStore == null) {
                    embeddingStore = createEmbeddingStore();
                }
            }
        }
        return embeddingStore;
    }

    /**
     * 根据配置创建 EmbeddingStore 实例
     * <p>
     * 业务含义：根据 ragProperties.getStoreType() 路由到不同的向量存储实现。
     * 创建失败时统一包装为 RAG_VECTOR_STORE_INIT_FAILED，避免上层处理多种异常类型。
     * </p>
     */
    private EmbeddingStore<TextSegment> createEmbeddingStore() {
        try {
            return switch (ragProperties.getStoreType()) {
                case MEMORY -> new InMemoryEmbeddingStore<>();
                case MILVUS -> createMilvusEmbeddingStore();
            };
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.RAG_VECTOR_STORE_INIT_FAILED, e);
        }
    }

    /**
     * 创建 Milvus EmbeddingStore 实例
     * <p>
     * 业务含义：使用 Milvus 作为向量数据库，支持生产级持久化存储。
     * 维度通过 EmbeddingModel.dimension() 动态获取，不硬编码，确保与 Embedding 模型一致。
     * </p>
     */
    private EmbeddingStore<TextSegment> createMilvusEmbeddingStore() {
        // 动态获取 Embedding 维度（豆包模型），不硬编码
        int dimension = modelFactory.getEmbeddingModel().dimension();
        return MilvusEmbeddingStore.builder()
                .host(ragProperties.getMilvus().getHost())
                .port(ragProperties.getMilvus().getPort())
                .collectionName(ragProperties.getMilvus().getCollectionName())
                .dimension(dimension)
                .indexType(IndexType.FLAT)
                .metricType(MetricType.COSINE)
                .build();
    }
}
