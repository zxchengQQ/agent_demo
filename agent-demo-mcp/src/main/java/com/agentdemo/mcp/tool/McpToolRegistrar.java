package com.agentdemo.mcp.tool;

import com.agentdemo.mcp.client.McpClientEntry;

/**
 * MCP 工具注册器接口
 * <p>
 * 业务含义：定义 MCP 工具注册到 ToolRegistry 与从 ToolRegistry 注销的契约，
 * 由 {@link com.agentdemo.mcp.service.McpServerManager} 在 Server 连接成功/断线/删除时调用。
 * </p>
 * <p>
 * 设计原则：接口隔离（ISP）— 抽取此接口打破 McpServerManager 与 McpToolRegistrar 实现类之间的循环依赖
 * （McpServerManager 依赖此接口注册/注销工具；McpToolRegistrar 实现类依赖 McpServerManager 执行启动加载）。
 * 实现类由 Task-14 提供（实现 ApplicationRunner + 此接口）。
 * </p>
 */
public interface McpToolRegistrar {

    /**
     * 将 entry 对应 Server 的所有工具注册到 ToolRegistry
     * <p>
     * 调用时机：McpServerManager.connect() 成功后；reconnect 成功后
     * </p>
     *
     * @param entry MCP 客户端聚合对象（含 tools 工具元数据列表）
     */
    void registerTools(McpClientEntry entry);

    /**
     * 从 ToolRegistry 注销 entry 对应 Server 的所有工具
     * <p>
     * 调用时机：McpServerManager.markDisconnected() 断线检测时；deleteServer() 删除时；reconnect 重连前
     * </p>
     *
     * @param entry MCP 客户端聚合对象（含 tools 工具元数据列表）
     */
    void unregisterTools(McpClientEntry entry);
}
