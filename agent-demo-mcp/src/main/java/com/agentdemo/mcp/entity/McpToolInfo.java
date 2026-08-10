package com.agentdemo.mcp.entity;

import lombok.Data;

/**
 * MCP 工具元数据
 * <p>
 * 业务含义：单个 MCP 工具的元数据，连接成功后由 McpClient.listTools() 拉取，
 * 包含原始工具名、注册名（加 mcp_{serverName}_ 前缀）、工具描述、参数 JSON Schema。
 * </p>
 */
@Data
public class McpToolInfo {

    /** MCP Server 返回的原始工具名（如 getForecast） */
    private String originalName;

    /** 注册到 ToolRegistry 的工具方法名（格式：mcp_{serverName}_{toolName}，如 mcp_weather_getForecast） */
    private String registeredName;

    /** 工具描述（来自 MCP Server，包含工具用途说明，会写入 @Tool 注解的 value） */
    private String description;

    /** 参数 JSON Schema 字符串（描述工具参数结构，供 LLM 理解参数格式） */
    private String parametersSchema;
}
