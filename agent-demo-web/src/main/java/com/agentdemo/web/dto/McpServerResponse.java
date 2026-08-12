package com.agentdemo.web.dto;

import com.agentdemo.mcp.entity.McpServerStatus;
import com.agentdemo.mcp.config.McpTransportType;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

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

    /** sse/http Server 的连接 URL（stdio 类型为 null，AC-036 前端地址显示用） */
    private String url;

    /** stdio Server 的执行命令（sse/http 类型为 null，AC-036 前端地址显示用） */
    private String command;

    /** stdio Server 的命令参数（sse/http 类型为 null，AC-036 前端地址显示用） */
    private List<String> args;

    /** 最近错误信息（ERROR/DISCONNECTED 状态时填充） */
    private String lastError;

    /** 首次连接时间 */
    private LocalDateTime connectTime;
}
