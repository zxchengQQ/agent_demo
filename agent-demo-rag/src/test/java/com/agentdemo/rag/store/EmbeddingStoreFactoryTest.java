package com.agentdemo.rag.store;

import com.agentdemo.llm.factory.ModelFactory;
import com.agentdemo.rag.config.RagProperties;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.when;

/**
 * 向量存储工厂测试
 * <p>
 * 验证 EmbeddingStoreFactory 在 MEMORY 模式下的懒加载和单例行为。
 * 注意：Mock ModelFactory，不实际调用 Embedding API。
 * </p>
 */
@DisplayName("向量存储工厂测试")
@ExtendWith(MockitoExtension.class)
class EmbeddingStoreFactoryTest {

    @Mock
    private RagProperties ragProperties;

    @Mock
    private ModelFactory modelFactory;

    @InjectMocks
    private EmbeddingStoreFactory factory;

    @BeforeEach
    void setUp() {
        when(ragProperties.getStoreType()).thenReturn(RagProperties.StoreType.MEMORY);
    }

    @Test
    @DisplayName("getEmbeddingStore 返回 InMemoryEmbeddingStore 实例")
    void getEmbeddingStoreShouldReturnInMemoryInstance() {
        EmbeddingStore<TextSegment> store = factory.getEmbeddingStore();

        assertNotNull(store, "EmbeddingStore 不应为 null");
        assertInstanceOf(InMemoryEmbeddingStore.class, store,
                "MEMORY 模式应返回 InMemoryEmbeddingStore 实例");
    }

    @Test
    @DisplayName("多次调用返回同一实例（单例）")
    void multipleCallsShouldReturnSameInstance() {
        EmbeddingStore<TextSegment> first = factory.getEmbeddingStore();
        EmbeddingStore<TextSegment> second = factory.getEmbeddingStore();

        assertSame(first, second, "多次调用应返回同一实例（懒加载单例）");
    }
}
