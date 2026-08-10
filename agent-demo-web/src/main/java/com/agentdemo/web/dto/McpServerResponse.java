package com.agentdemo.web.dto;

import com.agentdemo.mcp.entity.McpServerStatus;
import com.agentdemo.mcp.config.McpTransportType;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * MCP Server 响应 DTO
 * <p>
 * 业务含义：MCP Server 相关接口的统一返回结构，屏蔽内部实体细节，
 * 包含 Server 名称、传输方式、状态、工具数量等关键信息（AC-007, AC-008）。
 * </p>
 */
@Data
public class McpServerResponse {

    /** Server 名称 */
    private String name;

    /** 传输方式 */
    private McpTransportType transport;

    /** 当前状态 */
    private McpServerStatus status;

    /** 是否启用 */
    private boolean enabled;

    /** 工具数量 */
    private int toolCount;

    /** 最近错误信息（ERROR/DISCONNECTED 状态时填充） */
    private String lastError;

    /** 首次连接时间 */
    private LocalDateTime connectTime;
}
