package com.agentdemo.web.dto;

import com.agentdemo.mcp.config.McpTransportType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 创建 MCP Server 请求 DTO
 * <p>
 * 业务含义：前端调用 POST /api/mcp/servers 接口的请求参数。
 * 通过 Bean Validation 校验名称格式、必填字段，并通过自定义校验注解
 * 确保 transport=STDIO 时 command 非空、transport=SSE 时 url 为合法 HTTP/HTTPS（AC-023, AC-024, AC-028）。
 * </p>
 */
@Data
@ValidMcpServerConfig
public class CreateMcpServerRequest {

    /**
     * Server 唯一标识（1-50 字符，中文/字母/数字/下划线/连字符）
     */
    @NotBlank(message = "Server 名称不能为空")
    @Pattern(regexp = "^[\\u4e00-\\u9fa5a-zA-Z0-9_-]{1,50}$",
            message = "Server 名称仅允许中文、字母、数字、下划线和连字符，长度 1-50")
    @Size(min = 1, max = 50, message = "Server 名称长度需在 1-50 字符之间")
    private String name;

    /** 传输方式：STDIO 或 SSE */
    @NotNull(message = "传输方式不能为空")
    private McpTransportType transport;

    /** 是否启用 */
    private boolean enabled = true;

    // === stdio 专用 ===
    private String command;
    private List<String> args;
    private Map<String, String> env;

    // === sse 专用 ===
    private String url;
    private Map<String, String> headers;

    // === 通用 ===
    private Duration toolTimeout;
}
