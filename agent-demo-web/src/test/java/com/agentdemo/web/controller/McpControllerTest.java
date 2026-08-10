package com.agentdemo.web.controller;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.common.result.Result;
import com.agentdemo.mcp.config.McpProperties;
import com.agentdemo.mcp.config.McpTransportType;
import com.agentdemo.mcp.entity.McpServer;
import com.agentdemo.mcp.entity.McpServerStatus;
import com.agentdemo.mcp.entity.McpToolInfo;
import com.agentdemo.mcp.service.McpServerManager;
import com.agentdemo.web.dto.CreateMcpServerRequest;
import com.agentdemo.web.dto.McpServerResponse;
import com.agentdemo.web.dto.McpToolResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * McpController REST API 单元测试
 * <p>
 * 验证标准来源：Task-16 验证标准（12 项）
 * 关联 AC：AC-007~AC-011, AC-020, AC-022
 * </p>
 * <p>
 * 测试策略：直接调用 Controller 方法验证 Result 对象，Service 通过 @Mock 隔离。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class McpControllerTest {

    @Mock
    private McpServerManager serverManager;

    private McpProperties mcpProperties;
    private McpController controller;

    @BeforeEach
    void setUp() {
        mcpProperties = new McpProperties();
        mcpProperties.setEnabled(true);
        controller = new McpController(serverManager, mcpProperties);
    }

    // ==================== 辅助方法 ====================

    private McpServer createConnectedServer(String name, int toolCount) {
        McpServer server = new McpServer();
        server.setName(name);
        server.setTransport(McpTransportType.STDIO);
        server.setEnabled(true);
        server.setStatus(McpServerStatus.CONNECTED);
        server.setConnectTime(LocalDateTime.now());
        // 添加 toolCount 个工具
        for (int i = 0; i < toolCount; i++) {
            McpToolInfo tool = new McpToolInfo();
            tool.setOriginalName("tool" + i);
            tool.setRegisteredName("mcp_" + name + "_tool" + i);
            server.getTools().add(tool);
        }
        return server;
    }

    private CreateMcpServerRequest validStdioRequest() {
        CreateMcpServerRequest req = new CreateMcpServerRequest();
        req.setName("weather");
        req.setTransport(McpTransportType.STDIO);
        req.setCommand("node");
        return req;
    }

    // ==================== list 组 ====================

    @Nested
    @DisplayName("GET /api/mcp/servers 查询列表")
    class ListTest {

        @Test
        @DisplayName("返回 200 + Result.success(serverList)")
        void list_shouldReturnServerList() {
            when(serverManager.list()).thenReturn(List.of(
                    createConnectedServer("weather", 2),
                    createConnectedServer("fetch", 1)));

            Result<List<McpServerResponse>> result = controller.list();

            assertTrue(result.isSuccess());
            assertEquals(2, result.getData().size());
            assertEquals("weather", result.getData().get(0).getName());
            assertEquals(2, result.getData().get(0).getToolCount());
            assertEquals(McpServerStatus.CONNECTED, result.getData().get(0).getStatus());
        }

        @Test
        @DisplayName("mcp.enabled=false 时返回 MCP_MODULE_DISABLED")
        void list_whenDisabled_shouldReturnModuleDisabled() {
            mcpProperties.setEnabled(false);
            Result<List<McpServerResponse>> result = controller.list();
            assertFalse(result.isSuccess());
            assertEquals(ErrorCode.MCP_MODULE_DISABLED.getCode(), result.getCode());
            verify(serverManager, never()).list();
        }
    }

    // ==================== add 组 ====================

    @Nested
    @DisplayName("POST /api/mcp/servers 添加 Server")
    class AddTest {

        @Test
        @DisplayName("合法请求返回 200 + Result.success(serverDetail)，状态为 CONNECTED")
        void add_whenValid_shouldReturnConnectedServer() {
            CreateMcpServerRequest req = validStdioRequest();
            McpServer connected = createConnectedServer("weather", 2);
            when(serverManager.addServer(any())).thenReturn(connected);

            Result<McpServerResponse> result = controller.add(req);

            assertTrue(result.isSuccess());
            assertEquals("weather", result.getData().getName());
            assertEquals(McpServerStatus.CONNECTED, result.getData().getStatus());
            assertEquals(2, result.getData().getToolCount());
        }

        @Test
        @DisplayName("name 已存在抛出 BusinessException(MCP_SERVER_NAME_EXISTS)，由 GlobalExceptionHandler 转为 Result.error")
        void add_whenNameExists_shouldThrowNameExists() {
            CreateMcpServerRequest req = validStdioRequest();
            when(serverManager.addServer(any()))
                    .thenThrow(new BusinessException(ErrorCode.MCP_SERVER_NAME_EXISTS, "名称已存在: weather"));

            BusinessException ex = assertThrows(BusinessException.class, () -> controller.add(req));
            assertEquals(ErrorCode.MCP_SERVER_NAME_EXISTS, ex.getErrorCode());
        }

        @Test
        @DisplayName("mcp.enabled=false 时返回 MCP_MODULE_DISABLED")
        void add_whenDisabled_shouldReturnModuleDisabled() {
            mcpProperties.setEnabled(false);
            Result<McpServerResponse> result = controller.add(validStdioRequest());
            assertFalse(result.isSuccess());
            assertEquals(ErrorCode.MCP_MODULE_DISABLED.getCode(), result.getCode());
        }
    }

    // ==================== delete 组 ====================

    @Nested
    @DisplayName("DELETE /api/mcp/servers/{name} 删除 Server")
    class DeleteTest {

        @Test
        @DisplayName("Server 存在时返回 200 + Result.success()")
        void delete_whenExists_shouldReturnSuccess() {
            Result<Void> result = controller.delete("weather");
            assertTrue(result.isSuccess());
            verify(serverManager).deleteServer("weather");
        }

        @Test
        @DisplayName("Server 不存在时抛出 BusinessException(MCP_SERVER_NOT_FOUND)")
        void delete_whenNotExists_shouldThrowNotFound() {
            doThrow(new BusinessException(ErrorCode.MCP_SERVER_NOT_FOUND, "不存在"))
                    .when(serverManager).deleteServer("nonexistent");

            BusinessException ex = assertThrows(BusinessException.class, () -> controller.delete("nonexistent"));
            assertEquals(ErrorCode.MCP_SERVER_NOT_FOUND, ex.getErrorCode());
        }

        @Test
        @DisplayName("mcp.enabled=false 时返回 MCP_MODULE_DISABLED")
        void delete_whenDisabled_shouldReturnModuleDisabled() {
            mcpProperties.setEnabled(false);
            Result<Void> result = controller.delete("weather");
            assertFalse(result.isSuccess());
            assertEquals(ErrorCode.MCP_MODULE_DISABLED.getCode(), result.getCode());
        }
    }

    // ==================== reconnect 组 ====================

    @Nested
    @DisplayName("POST /api/mcp/servers/{name}/reconnect 重连")
    class ReconnectTest {

        @Test
        @DisplayName("DISCONNECTED 状态重连成功返回 200 + serverDetail")
        void reconnect_whenDisconnected_shouldReturnSuccess() {
            McpServer reconnected = createConnectedServer("weather", 2);
            when(serverManager.reconnect("weather")).thenReturn(reconnected);

            Result<McpServerResponse> result = controller.reconnect("weather");

            assertTrue(result.isSuccess());
            assertEquals(McpServerStatus.CONNECTED, result.getData().getStatus());
        }

        @Test
        @DisplayName("CONNECTED 状态重连抛出 BusinessException(MCP_SERVER_ALREADY_CONNECTED)")
        void reconnect_whenConnected_shouldThrowAlreadyConnected() {
            when(serverManager.reconnect("weather"))
                    .thenThrow(new BusinessException(ErrorCode.MCP_SERVER_ALREADY_CONNECTED, "已连接"));

            BusinessException ex = assertThrows(BusinessException.class, () -> controller.reconnect("weather"));
            assertEquals(ErrorCode.MCP_SERVER_ALREADY_CONNECTED, ex.getErrorCode());
        }

        @Test
        @DisplayName("Server 不存在抛出 BusinessException(MCP_SERVER_NOT_FOUND)")
        void reconnect_whenNotExists_shouldThrowNotFound() {
            when(serverManager.reconnect("nonexistent"))
                    .thenThrow(new BusinessException(ErrorCode.MCP_SERVER_NOT_FOUND, "不存在"));

            BusinessException ex = assertThrows(BusinessException.class, () -> controller.reconnect("nonexistent"));
            assertEquals(ErrorCode.MCP_SERVER_NOT_FOUND, ex.getErrorCode());
        }
    }

    // ==================== listTools 组 ====================

    @Nested
    @DisplayName("GET /api/mcp/servers/{name}/tools 查询工具")
    class ListToolsTest {

        @Test
        @DisplayName("Server 存在时返回 200 + toolList")
        void listTools_whenExists_shouldReturnToolList() {
            McpToolInfo tool = new McpToolInfo();
            tool.setOriginalName("getForecast");
            tool.setRegisteredName("mcp_weather_getForecast");
            tool.setDescription("天气预报");
            tool.setParametersSchema("{\"type\":\"object\"}");
            when(serverManager.listTools("weather")).thenReturn(List.of(tool));

            Result<List<McpToolResponse>> result = controller.listTools("weather");

            assertTrue(result.isSuccess());
            assertEquals(1, result.getData().size());
            assertEquals("mcp_weather_getForecast", result.getData().get(0).getRegisteredName());
            assertEquals("天气预报", result.getData().get(0).getDescription());
        }

        @Test
        @DisplayName("Server 不存在时抛出 BusinessException(MCP_SERVER_NOT_FOUND)")
        void listTools_whenNotExists_shouldThrowNotFound() {
            when(serverManager.listTools("nonexistent"))
                    .thenThrow(new BusinessException(ErrorCode.MCP_SERVER_NOT_FOUND, "不存在"));

            BusinessException ex = assertThrows(BusinessException.class, () -> controller.listTools("nonexistent"));
            assertEquals(ErrorCode.MCP_SERVER_NOT_FOUND, ex.getErrorCode());
        }

        @Test
        @DisplayName("mcp.enabled=false 时返回 MCP_MODULE_DISABLED")
        void listTools_whenDisabled_shouldReturnModuleDisabled() {
            mcpProperties.setEnabled(false);
            Result<List<McpToolResponse>> result = controller.listTools("weather");
            assertFalse(result.isSuccess());
            assertEquals(ErrorCode.MCP_MODULE_DISABLED.getCode(), result.getCode());
        }
    }
}
