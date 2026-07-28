package com.agentdemo.rag.store;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.rag.entity.KnowledgeBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 知识库内存存储测试
 * <p>
 * 验证 InMemoryKnowledgeBaseStore 的 CRUD 操作、名称唯一性校验和索引一致性。
 * </p>
 */
@DisplayName("知识库内存存储测试")
class InMemoryKnowledgeBaseStoreTest {

    private InMemoryKnowledgeBaseStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryKnowledgeBaseStore();
    }

    private KnowledgeBase createKb(String id, String name) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(id);
        kb.setName(name);
        kb.setDescription("测试知识库");
        kb.setDocumentCount(0);
        kb.setCreateTime(LocalDateTime.now());
        return kb;
    }

    @Test
    @DisplayName("save 后 findById 应返回该知识库")
    void saveAndFindByIdShouldReturnKnowledgeBase() {
        KnowledgeBase kb = createKb("kb-001", "产品文档");
        store.save(kb);

        KnowledgeBase found = store.findById("kb-001");
        assertNotNull(found, "save 后应能通过 ID 找到知识库");
        assertEquals("产品文档", found.getName());
    }

    @Test
    @DisplayName("findByName 返回匹配的知识库，不存在返回 null")
    void findByNameShouldReturnMatchingOrNull() {
        store.save(createKb("kb-001", "产品文档"));

        KnowledgeBase found = store.findByName("产品文档");
        assertNotNull(found, "已存在的名称应能找到");
        assertEquals("kb-001", found.getId());

        KnowledgeBase notFound = store.findByName("不存在");
        assertNull(notFound, "不存在的名称应返回 null");
    }

    @Test
    @DisplayName("findAll 返回所有知识库")
    void findAllShouldReturnAllKnowledgeBases() {
        store.save(createKb("kb-001", "知识库A"));
        store.save(createKb("kb-002", "知识库B"));

        List<KnowledgeBase> all = store.findAll();
        assertEquals(2, all.size(), "应返回 2 个知识库");
    }

    @Test
    @DisplayName("delete 后 findById 返回 null")
    void deleteShouldRemoveKnowledgeBase() {
        store.save(createKb("kb-001", "产品文档"));

        store.delete("kb-001");
        assertNull(store.findById("kb-001"), "删除后 findById 应返回 null");
        // 业务含义：删除知识库时需同步删除名称索引，避免名称被永久占用
        assertNull(store.findByName("产品文档"), "删除后 findByName 也应返回 null");
    }

    @Test
    @DisplayName("save 重复名称抛 BusinessException")
    void saveDuplicateNameShouldThrow() {
        store.save(createKb("kb-001", "产品文档"));

        assertThrows(BusinessException.class,
                () -> store.save(createKb("kb-002", "产品文档")),
                "重复名称应抛出 BusinessException");
    }

    @Test
    @DisplayName("updateDocumentCount 更新文档计数")
    void updateDocumentCountShouldUpdate() {
        store.save(createKb("kb-001", "产品文档"));

        store.updateDocumentCount("kb-001", 5);
        KnowledgeBase found = store.findById("kb-001");
        assertEquals(5, found.getDocumentCount(), "文档计数应更新为 5");
    }
}
