package com.agentdemo.web.dto;

import lombok.Data;

/**
 * MCP 工具响应 DTO
 * <p>
 * 业务含义：GET /api/mcp/servers/{name}/tools 接口的返回结构，
 * 包含工具原始名、注册名（mcp_{serverName}_{toolName}）、描述、参数 JSON Schema（AC-011）。
 * </p>
 */
@Data
public class McpToolResponse {

    /** MCP Server 返回的原始工具名 */
    private String originalName;

    /** 注册到 ToolRegistry 的工具方法名（mcp_{serverName}_{toolName}） */
    private String registeredName;

    /** 工具描述 */
    private String description;

    /** 参数 JSON Schema 字符串 */
    private String parametersSchema;
}
