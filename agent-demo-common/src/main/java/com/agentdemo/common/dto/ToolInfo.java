package com.agentdemo.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具信息 DTO
 * <p>
 * 业务含义：前端工具选择器和设置页面展示的工具"名片"，
 * 包含工具标识、类别、描述、是否默认加载。
 * 工具标识格式为 category:name（如 builtin:getCurrentTime、mcp:mermaid-mcp）。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolInfo {

    /** 工具标识，格式 category:name（如 builtin:getCurrentTime、mcp:mermaid-mcp） */
    private String id;

    /** 工具类别：builtin / mcp / rag */
    private String category;

    /** 工具名称（方法名或 serverName） */
    private String name;

    /** 工具描述（@Tool 注解的 value 或 MCP 工具描述） */
    private String description;

    /** 是否为默认加载工具 */
    @JsonProperty("isDefault")
    private boolean isDefault;
}