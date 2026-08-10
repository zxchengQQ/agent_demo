package com.agentdemo.mcp.client;

import com.agentdemo.mcp.entity.McpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * McpClientRegistry 独立存储层测试
 * <p>
 * 验证来源：Task-08 验证标准
 * 关联 AC：AC-029, AC-033
 * </p>
 */
@DisplayName("McpClientRegistry 独立存储层测试")
class McpClientRegistryTest {

    private McpClientRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new McpClientRegistry();
    }

    @Test
    @DisplayName("put(name, entry) 后 get(name) 返回同一 entry 引用")
    void putAndGetShouldReturnSameEntry() {
        McpClientEntry entry = createEntry("weather");

        registry.put("weather", entry);

        McpClientEntry retrieved = registry.get("weather");
        assertEquals(entry, retrieved, "应返回同一 entry 引用");
    }

    @Test
    @DisplayName("get(nonexistent) 返回 null")
    void getNonexistentShouldReturnNull() {
        McpClientEntry retrieved = registry.get("nonexistent");

        assertNull(retrieved, "不存在的 Server 应返回 null");
    }

    @Test
    @DisplayName("contains(name) 已存在返回 true，不存在返回 false")
    void containsShouldReturnCorrectBoolean() {
        registry.put("weather", createEntry("weather"));

        assertTrue(registry.contains("weather"), "已存在的 Server 应返回 true");
        assertFalse(registry.contains("nonexistent"), "不存在的 Server 应返回 false");
    }

    @Test
    @DisplayName("remove(name) 返回被移除的 entry，且 contains 返回 false")
    void removeShouldReturnRemovedEntry() {
        McpClientEntry entry = createEntry("weather");
        registry.put("weather", entry);

        McpClientEntry removed = registry.remove("weather");

        assertEquals(entry, removed, "应返回被移除的 entry");
        assertFalse(registry.contains("weather"), "移除后 contains 应返回 false");
        assertNull(registry.get("weather"), "移除后 get 应返回 null");
    }

    @Test
    @DisplayName("remove(nonexistent) 返回 null")
    void removeNonexistentShouldReturnNull() {
        McpClientEntry removed = registry.remove("nonexistent");

        assertNull(removed, "移除不存在的 Server 应返回 null");
    }

    @Test
    @DisplayName("list() 空时返回空集合，不返回 null")
    void listEmptyShouldReturnEmptyCollection() {
        Collection<McpClientEntry> list = registry.list();

        assertNotNull(list, "list 不应返回 null");
        assertTrue(list.isEmpty(), "空时应返回空集合");
    }

    @Test
    @DisplayName("list() 返回所有 entry")
    void listShouldReturnAllEntries() {
        registry.put("weather", createEntry("weather"));
        registry.put("github", createEntry("github"));

        Collection<McpClientEntry> list = registry.list();

        assertEquals(2, list.size(), "应返回 2 个 entry");
    }

    @Test
    @DisplayName("并发场景：多线程同时 put 不同 key 不抛异常")
    void concurrentPutShouldNotThrowException() throws InterruptedException {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final String name = "server-" + i;
            executor.submit(() -> {
                try {
                    registry.put(name, createEntry(name));
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "所有线程应在 5 秒内完成");
        assertEquals(threadCount, registry.list().size(), "应注册 threadCount 个 Server");

        executor.shutdown();
    }

    @Test
    @DisplayName("动态添加的 Server 仅存内存（验证 AC-033 内存存储特性）")
    void dynamicallyAddedServerShouldOnlyExistInMemory() {
        // 模拟动态添加 Server
        registry.put("dynamic-server", createEntry("dynamic-server"));

        // 验证内存中存在
        assertTrue(registry.contains("dynamic-server"), "动态添加的 Server 应存在于内存中");

        // 模拟"重启"：新建一个 registry 实例（旧实例的内存数据丢失）
        McpClientRegistry newRegistry = new McpClientRegistry();

        assertFalse(newRegistry.contains("dynamic-server"),
                "新建 registry 实例不应包含动态添加的 Server（验证内存存储特性）");
    }

    /**
     * 创建测试用 McpClientEntry
     */
    private McpClientEntry createEntry(String name) {
        McpServer server = new McpServer();
        server.setName(name);
        return new McpClientEntry(server, null, null);
    }
}
