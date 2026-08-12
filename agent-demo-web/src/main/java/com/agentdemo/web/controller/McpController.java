package com.agentdemo.web.controller;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.common.result.Result;
import com.agentdemo.mcp.config.McpProperties;
import com.agentdemo.mcp.entity.McpServer;
import com.agentdemo.mcp.entity.McpServerStatus;
import com.agentdemo.mcp.entity.McpToolInfo;
import com.agentdemo.mcp.service.McpServerManager;
import com.agentdemo.web.dto.CreateMcpServerRequest;
import com.agentdemo.web.dto.McpServerResponse;
import com.agentdemo.web.dto.McpToolResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * MCP Server 管理接口
 * <p>
 * 业务含义：提供 MCP Server 的 CRUD REST API，支持查询列表、动态添加、删除、重连、查询工具。
 * Controller 仅负责参数接收与结果转换，业务逻辑下沉到 McpServerManager。
 * mcp.enabled=false 时所有 API 返回 MCP_MODULE_DISABLED（AC-022）。
 * </p>
 */
@Tag(name = "MCP Server 管理", description = "MCP Server 增删改查与工具管理接口")
@RestController
@RequestMapping("/api/mcp")
public class McpController {

    private final McpServerManager serverManager;
    private final McpProperties mcpProperties;

    public McpController(McpServerManager serverManager, McpProperties mcpProperties) {
        this.serverManager = serverManager;
        this.mcpProperties = mcpProperties;
    }

    /**
     * 查询所有 MCP Server 列表（AC-007）
     */
    @Operation(summary = "查询 Server 列表", description = "返回所有已加载的 MCP Server 及状态")
    @GetMapping("/servers")
    public Result<List<McpServerResponse>> list() {
        if (!mcpProperties.isEnabled()) {
            return Result.error(ErrorCode.MCP_MODULE_DISABLED);
        }
        List<McpServer> servers = serverManager.list();
        List<McpServerResponse> responses = servers.stream()
                .map(this::toServerResponse)
                .toList();
        return Result.success(responses);
    }

    /**
     * 动态添加 MCP Server 并立即连接（AC-008）
     */
    @Operation(summary = "添加 Server", description = "动态添加新 MCP Server 并立即建立连接")
    @PostMapping("/servers")
    public Result<McpServerResponse> add(@Valid @RequestBody CreateMcpServerRequest request) {
        if (!mcpProperties.isEnabled()) {
            return Result.error(ErrorCode.MCP_MODULE_DISABLED);
        }
        McpServer server = toMcpServer(request);
        McpServer result = serverManager.addServer(server);
        return Result.success(toServerResponse(result));
    }

    /**
     * 删除 MCP Server，注销工具并关闭连接（AC-009）
     */
    @Operation(summary = "删除 Server", description = "断开连接并注销工具")
    @DeleteMapping("/servers/{name}")
    public Result<Void> delete(@PathVariable String name) {
        if (!mcpProperties.isEnabled()) {
            return Result.error(ErrorCode.MCP_MODULE_DISABLED);
        }
        serverManager.deleteServer(name);
        return Result.success();
    }

    /**
     * 重连已断线的 MCP Server（AC-010）
     */
    @Operation(summary = "重连 Server", description = "重新连接已断线或错误的 Server")
    @PostMapping("/servers/{name}/reconnect")
    public Result<McpServerResponse> reconnect(@PathVariable String name) {
        if (!mcpProperties.isEnabled()) {
            return Result.error(ErrorCode.MCP_MODULE_DISABLED);
        }
        McpServer result = serverManager.reconnect(name);
        return Result.success(toServerResponse(result));
    }

    /**
     * 查询指定 Server 的工具列表（AC-011）
     */
    @Operation(summary = "查询工具列表", description = "返回指定 Server 暴露的所有工具元数据")
    @GetMapping("/servers/{name}/tools")
    public Result<List<McpToolResponse>> listTools(@PathVariable String name) {
        if (!mcpProperties.isEnabled()) {
            return Result.error(ErrorCode.MCP_MODULE_DISABLED);
        }
        List<McpToolInfo> tools = serverManager.listTools(name);
        List<McpToolResponse> responses = tools.stream()
                .map(this::toToolResponse)
                .toList();
        return Result.success(responses);
    }

    // ==================== 实体转 DTO ====================

    private McpServerResponse toServerResponse(McpServer server) {
        McpServerResponse response = new McpServerResponse();
        response.setName(server.getName());
        response.setTransport(server.getTransport());
        response.setStatus(server.getStatus());
        response.setEnabled(server.isEnabled());
        response.setToolCount(server.getTools() != null ? server.getTools().size() : 0);
        response.setUrl(server.getUrl());
        response.setCommand(server.getCommand());
        response.setArgs(server.getArgs());
        response.setConnectTime(server.getConnectTime());
        return response;
    }

    private McpToolResponse toToolResponse(McpToolInfo tool) {
        McpToolResponse response = new McpToolResponse();
        response.setOriginalName(tool.getOriginalName());
        response.setRegisteredName(tool.getRegisteredName());
        response.setDescription(tool.getDescription());
        response.setParametersSchema(tool.getParametersSchema());
        return response;
    }

    private McpServer toMcpServer(CreateMcpServerRequest request) {
        McpServer server = new McpServer();
        server.setName(request.getName());
        server.setTransport(request.getTransport());
        server.setEnabled(request.isEnabled());
        server.setCommand(request.getCommand());
        server.setArgs(request.getArgs());
        server.setEnv(request.getEnv());
        server.setUrl(request.getUrl());
        server.setHeaders(request.getHeaders());
        server.setToolTimeout(request.getToolTimeout());
        return server;
    }
}
