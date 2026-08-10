package com.agentdemo.mcp.config;

/**
 * MCP 传输方式枚举
 * <p>
 * 业务含义：标识 MCP Server 与 MCP 客户端之间的通信通道类型。
 * MCP 协议定义了三种标准传输方式：
 * 1. STDIO - 通过子进程的标准输入输出通信，适合本地 MCP Server
 * 2. SSE - 通过 HTTP POST 发请求 + Server-Sent Events 接收推送（旧版规范）
 * 3. HTTP - Streamable HTTP 传输（新版 MCP 2025-06-18 规范），单端点请求响应模式
 * </p>
 */
public enum McpTransportType {

    /** stdio 传输方式（本地子进程） */
    STDIO,

    /** HTTP+SSE 传输方式（远程 HTTP 端点，旧版规范） */
    SSE,

    /** Streamable HTTP 传输方式（远程 HTTP 端点，新版 MCP 2025-06-18 规范） */
    HTTP
}
