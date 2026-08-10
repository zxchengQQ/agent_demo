package com.agentdemo.mcp.entity;

/**
 * MCP Server 状态枚举
 * <p>
 * 业务含义：标识 MCP Server 在生命周期中的当前状态，用于状态机管理与 REST API 状态查询。
 * </p>
 * <p>
 * 状态流转：
 * 1. 启动加载时：enabled=false → DISABLED；连接成功 → CONNECTED；连接失败 → ERROR
 * 2. 运行时：CONNECTED → DISCONNECTED（断线检测）；DISCONNECTED → CONNECTED（重连成功）
 * 3. 动态添加：CONNECTED（成功）/ ERROR（失败，抛异常给调用方）
 * </p>
 */
public enum McpServerStatus {

    /** 已连接（McpClient 可用，工具已注册到 ToolRegistry） */
    CONNECTED,

    /** 已断线（McpClient 不可用，工具已从 ToolRegistry 注销，可重连） */
    DISCONNECTED,

    /** 错误（连接失败或运行时异常，静态 Server 启动加载失败时标记，不阻塞应用启动） */
    ERROR,

    /** 已禁用（静态配置 enabled=false，启动加载时跳过） */
    DISABLED
}
