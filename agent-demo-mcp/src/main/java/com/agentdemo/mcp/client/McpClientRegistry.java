package com.agentdemo.mcp.client;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 客户端注册表 - 纯存储层
 * <p>
 * 业务含义：作为 McpClientEntry 的统一存储层，解耦 McpToolExecutor 与 McpServerManager，
 * 避免循环依赖（方案 B：McpToolExecutor 依赖 McpClientRegistry 而非 McpServerManager）。
 * </p>
 * <p>
 * 设计原则：
 * 1. 纯存储：本类只提供 get/put/remove/list/contains 操作，无任何业务逻辑
 * 2. 线程安全：使用 ConcurrentHashMap 支持多线程并发访问（动态 API 与启动加载可能同时操作）
 * 3. 内存存储：动态添加的 Server 仅存内存，重启丢失（AC-033），与知识库 InMemoryStore 风格一致
 * </p>
 * <p>
 * 调用方：
 * - McpServerManager：put/remove 管理 entry 生命周期
 * - McpToolExecutor：get 查找 entry 用于工具调用
 * - McpController：list 查询 Server 列表
 * </p>
 */
@Component
public class McpClientRegistry {

    /** Server 注册表，按 name 索引，使用 ConcurrentHashMap 保证线程安全 */
    private final ConcurrentHashMap<String, McpClientEntry> servers = new ConcurrentHashMap<>();

    /**
     * 注册 Server entry
     *
     * @param name  Server 唯一标识
     * @param entry 客户端聚合对象
     */
    public void put(String name, McpClientEntry entry) {
        servers.put(name, entry);
    }

    /**
     * 获取 Server entry
     *
     * @param name Server 唯一标识
     * @return entry（不存在返回 null）
     */
    public McpClientEntry get(String name) {
        return servers.get(name);
    }

    /**
     * 移除 Server entry（返回旧值用于资源清理）
     *
     * @param name Server 唯一标识
     * @return 被移除的 entry（不存在返回 null）
     */
    public McpClientEntry remove(String name) {
        return servers.remove(name);
    }

    /**
     * 获取所有 Server entry
     *
     * @return entry 集合（空时返回空集合，不返回 null）
     */
    public Collection<McpClientEntry> list() {
        return servers.values();
    }

    /**
     * 检查 name 是否已存在（用于唯一性校验）
     *
     * @param name Server 唯一标识
     * @return 已存在返回 true
     */
    public boolean contains(String name) {
        return servers.containsKey(name);
    }
}
