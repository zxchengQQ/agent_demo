package com.agentdemo.mcp.service;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.mcp.client.McpClientEntry;
import com.agentdemo.mcp.client.McpClientRegistry;
import com.agentdemo.mcp.client.McpTransportFactory;
import com.agentdemo.mcp.config.McpProperties;
import com.agentdemo.mcp.config.McpTransportType;
import com.agentdemo.mcp.entity.McpServer;
import com.agentdemo.mcp.entity.McpServerStatus;
import com.agentdemo.mcp.entity.McpToolInfo;
import com.agentdemo.mcp.tool.McpToolRegistrar;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * McpServerManager 核心服务单元测试
 * <p>
 * 验证标准来源：Task-13 验证标准（共 14 项）
 * 关联 AC：AC-001, AC-007~AC-011, AC-014~AC-017, AC-019~AC-020, AC-026, AC-029, AC-033, AC-034
 * </p>
 * <p>
 * 测试策略：
 * - McpClientRegistry/McpTransportFactory/McpToolRegistrar 用 @Mock 注解 Mock
 * - McpProperties 用真实实例（默认值即可，避免 strict stubbing）
 * - McpServerManager 用 spy，覆盖 protected createMcpClient() 返回 Mock McpClient，隔离真实连接创建
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class McpServerManagerTest {

    @Mock
    private McpClientRegistry clientRegistry;
    @Mock
    private McpTransportFactory transportFactory;
    @Mock
    private McpToolRegistrar toolRegistrar;

    private McpProperties mcpProperties;
    private McpServerManager manager;

    @BeforeEach
    void setUp() {
        mcpProperties = new McpProperties();
        // 用 spy 创建 manager，便于覆盖 createMcpClient 返回 Mock McpClient
        manager = spy(new McpServerManager(clientRegistry, transportFactory, toolRegistrar, mcpProperties));
    }

    // ==================== 辅助方法 ====================

    /** 创建 stdio 传输方式的 McpServer */
    private McpServer createStdioServer(String name) {
        McpServer server = new McpServer();
        server.setName(name);
        server.setTransport(McpTransportType.STDIO);
        server.setEnabled(true);
        server.setCommand("node");
        server.setArgs(List.of("/path/to/server.js"));
        return server;
    }

    /** 创建 sse 传输方式的 McpServer */
    private McpServer createSseServer(String name, String url) {
        McpServer server = new McpServer();
        server.setName(name);
        server.setTransport(McpTransportType.SSE);
        server.setEnabled(true);
        server.setUrl(url);
        return server;
    }

    /** 创建 Mock McpClient，listTools 返回指定工具规格 */
    private McpClient createMockClient(String... toolNames) {
        McpClient mockClient = mock(McpClient.class);
        List<ToolSpecification> specs = java.util.Arrays.stream(toolNames)
                .map(name -> ToolSpecification.builder()
                        .name(name)
                        .description("工具 " + name)
                        .build())
                .toList();
        lenient().when(mockClient.listTools()).thenReturn(specs);
        return mockClient;
    }

    /** 桩: transportFactory + createMcpClient，让 connect 走通到 listTools */
    private void stubConnectSuccess(McpTransport transport, McpClient mockClient) {
        lenient().when(transportFactory.createTransport(any())).thenReturn(transport);
        doReturn(mockClient).when(manager).createMcpClient(any(), any(), any());
    }

    // ==================== connect 组 ====================

    @Nested
    @DisplayName("connect() 建立 Server 连接")
    class ConnectTest {

        @Test
        @DisplayName("配置合法时：建立 McpClient、拉取工具、存入 Registry、注册工具、状态置 CONNECTED，返回含工具列表的 McpServer")
        void connect_whenValid_shouldEstablishConnectionAndRegisterTools() {
            // given
            McpServer weather = createStdioServer("weather");
            McpTransport mockTransport = mock(McpTransport.class);
            McpClient mockClient = createMockClient("getForecast", "getHistory");
            stubConnectSuccess(mockTransport, mockClient);

            // when
            McpServer result = manager.connect(weather);

            // then
            verify(transportFactory).createTransport(any());
            verify(manager).createMcpClient(eq("weather"), eq(mockTransport), any(Duration.class));
            verify(mockClient).listTools();

            // entry 存入 registry，状态为 CONNECTED
            ArgumentCaptor<McpClientEntry> entryCaptor = ArgumentCaptor.forClass(McpClientEntry.class);
            verify(clientRegistry).put(eq("weather"), entryCaptor.capture());
            McpClientEntry entry = entryCaptor.getValue();
            assertEquals(McpServerStatus.CONNECTED, entry.getStatus());

            // 注册工具被调用
            verify(toolRegistrar).registerTools(entry);

            // 返回的 McpServer 含工具列表
            assertNotNull(result);
            assertEquals(2, result.getTools().size());
            assertNotNull(result.getConnectTime());
            // 工具 registeredName 符合前缀规则 mcp_{serverName}_{toolName}
            assertEquals("mcp_weather_getForecast", result.getTools().get(0).getRegisteredName());
            assertEquals("mcp_weather_getHistory", result.getTools().get(1).getRegisteredName());
        }

        @Test
        @DisplayName("McpClient 创建失败时：抛 BusinessException(MCP_CONNECTION_FAILED)")
        void connect_whenMcpClientCreationFails_shouldThrowConnectionFailed() {
            // given
            McpServer weather = createStdioServer("weather");
            McpTransport mockTransport = mock(McpTransport.class);
            when(transportFactory.createTransport(any())).thenReturn(mockTransport);
            doThrow(new RuntimeException("连接被拒绝")).when(manager).createMcpClient(any(), any(), any());

            // when & then
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> manager.connect(weather));
            assertEquals(ErrorCode.MCP_CONNECTION_FAILED, ex.getErrorCode());
            // 未存入 registry、未注册工具
            verify(clientRegistry, never()).put(any(), any());
            verify(toolRegistrar, never()).registerTools(any());
        }
    }

    // ==================== addServer 组 ====================

    @Nested
    @DisplayName("addServer() 动态添加 Server")
    class AddServerTest {

        @Test
        @DisplayName("name 已存在时：抛 BusinessException(MCP_SERVER_NAME_EXISTS)")
        void addServer_whenNameExists_shouldThrowNameExists() {
            // given
            McpServer weather = createStdioServer("weather");
            when(clientRegistry.contains("weather")).thenReturn(true);

            // when & then
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> manager.addServer(weather));
            assertEquals(ErrorCode.MCP_SERVER_NAME_EXISTS, ex.getErrorCode());
            // 未尝试连接
            verify(transportFactory, never()).createTransport(any());
        }

        @Test
        @DisplayName("transport=STDIO 且 command 为空时：抛 BusinessException(PARAM_INVALID)")
        void addServer_whenStdioAndCommandEmpty_shouldThrowParamInvalid() {
            // given
            McpServer weather = createStdioServer("weather");
            weather.setCommand(null);
            when(clientRegistry.contains("weather")).thenReturn(false);
            // transportFactory 校验 command 为空，抛 PARAM_INVALID
            when(transportFactory.createTransport(any()))
                    .thenThrow(new BusinessException(ErrorCode.PARAM_INVALID, "stdio 必须 command"));

            // when & then
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> manager.addServer(weather));
            assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
        }

        @Test
        @DisplayName("transport=SSE 且 url 格式非法（非 HTTP/HTTPS）时：抛 BusinessException(PARAM_INVALID)")
        void addServer_whenSseAndUrlInvalid_shouldThrowParamInvalid() {
            // given
            McpServer fetch = createSseServer("fetch", "ftp://invalid");
            when(clientRegistry.contains("fetch")).thenReturn(false);
            when(transportFactory.createTransport(any()))
                    .thenThrow(new BusinessException(ErrorCode.PARAM_INVALID, "sse url 必须 http/https"));

            // when & then
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> manager.addServer(fetch));
            assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
        }

        @Test
        @DisplayName("配置合法时：校验唯一性通过 + connect 成功，返回含工具列表的 McpServer")
        void addServer_whenValid_shouldCheckUniqueAndConnect() {
            // given
            McpServer weather = createStdioServer("weather");
            when(clientRegistry.contains("weather")).thenReturn(false);
            McpTransport mockTransport = mock(McpTransport.class);
            McpClient mockClient = createMockClient("getForecast");
            stubConnectSuccess(mockTransport, mockClient);

            // when
            McpServer result = manager.addServer(weather);

            // then
            verify(clientRegistry).contains("weather");
            ArgumentCaptor<McpClientEntry> entryCaptor = ArgumentCaptor.forClass(McpClientEntry.class);
            verify(clientRegistry).put(eq("weather"), entryCaptor.capture());
            assertEquals(McpServerStatus.CONNECTED, entryCaptor.getValue().getStatus());
            assertEquals(1, result.getTools().size());
        }
    }

    // ==================== deleteServer 组 ====================

    @Nested
    @DisplayName("deleteServer() 删除 Server")
    class DeleteServerTest {

        @Test
        @DisplayName("Server 存在时：调用 Registrar.unregisterTools、调用 entry.close()、从 Registry 移除")
        void deleteServer_whenExists_shouldUnregisterAndCloseAndRemove() throws Exception {
            // given
            McpServer weather = createStdioServer("weather");
            McpClient mockClient = mock(McpClient.class);
            McpClientEntry entry = new McpClientEntry(weather, mockClient, mock(McpTransport.class));
            entry.setStatus(McpServerStatus.CONNECTED);
            when(clientRegistry.get("weather")).thenReturn(entry);

            // when
            manager.deleteServer("weather");

            // then
            verify(toolRegistrar).unregisterTools(entry);
            verify(mockClient).close(); // entry.close() 内部调用 mcpClient.close()
            verify(clientRegistry).remove("weather");
        }

        @Test
        @DisplayName("Server 不存在时：抛 BusinessException(MCP_SERVER_NOT_FOUND)")
        void deleteServer_whenNotExists_shouldThrowNotFound() {
            // given
            when(clientRegistry.get("nonexistent")).thenReturn(null);

            // when & then
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> manager.deleteServer("nonexistent"));
            assertEquals(ErrorCode.MCP_SERVER_NOT_FOUND, ex.getErrorCode());
            verify(clientRegistry, never()).remove(any());
        }
    }

    // ==================== reconnect 组 ====================

    @Nested
    @DisplayName("reconnect() 重连 Server")
    class ReconnectTest {

        @Test
        @DisplayName("Server 状态为 DISCONNECTED 时：关闭旧连接、重新 connect、状态置 CONNECTED")
        void reconnect_whenDisconnected_shouldCloseAndReconnect() throws Exception {
            // given
            McpServer weather = createStdioServer("weather");
            McpClient oldClient = createMockClient("getForecast");
            McpTransport mockTransport = mock(McpTransport.class);
            McpClientEntry entry = new McpClientEntry(weather, oldClient, mockTransport);
            entry.setStatus(McpServerStatus.DISCONNECTED);
            when(clientRegistry.get("weather")).thenReturn(entry);
            stubConnectSuccess(mockTransport, oldClient);

            // when
            McpServer result = manager.reconnect("weather");

            // then
            verify(oldClient).close(); // 旧连接关闭
            ArgumentCaptor<McpClientEntry> entryCaptor = ArgumentCaptor.forClass(McpClientEntry.class);
            verify(clientRegistry).put(eq("weather"), entryCaptor.capture());
            assertEquals(McpServerStatus.CONNECTED, entryCaptor.getValue().getStatus());
            assertNotNull(result);
            assertFalse(result.getTools().isEmpty());
        }

        @Test
        @DisplayName("Server 状态为 CONNECTED 时：抛 BusinessException(MCP_SERVER_ALREADY_CONNECTED)")
        void reconnect_whenConnected_shouldThrowAlreadyConnected() {
            // given
            McpServer weather = createStdioServer("weather");
            McpClientEntry entry = new McpClientEntry(weather, mock(McpClient.class), mock(McpTransport.class));
            entry.setStatus(McpServerStatus.CONNECTED);
            when(clientRegistry.get("weather")).thenReturn(entry);

            // when & then
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> manager.reconnect("weather"));
            assertEquals(ErrorCode.MCP_SERVER_ALREADY_CONNECTED, ex.getErrorCode());
        }

        @Test
        @DisplayName("Server 不存在时：抛 BusinessException(MCP_SERVER_NOT_FOUND)")
        void reconnect_whenNotExists_shouldThrowNotFound() {
            // given
            when(clientRegistry.get("nonexistent")).thenReturn(null);

            // when & then
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> manager.reconnect("nonexistent"));
            assertEquals(ErrorCode.MCP_SERVER_NOT_FOUND, ex.getErrorCode());
        }
    }

    // ==================== markDisconnected 组 ====================

    @Nested
    @DisplayName("markDisconnected() 标记断线")
    class MarkDisconnectedTest {

        @Test
        @DisplayName("Server 存在时：状态置 DISCONNECTED、调用 Registrar.unregisterTools")
        void markDisconnected_whenExists_shouldSetDisconnectedAndUnregister() {
            // given
            McpServer weather = createStdioServer("weather");
            McpClientEntry entry = new McpClientEntry(weather, mock(McpClient.class), mock(McpTransport.class));
            entry.setStatus(McpServerStatus.CONNECTED);
            when(clientRegistry.get("weather")).thenReturn(entry);

            // when
            manager.markDisconnected("weather");

            // then
            assertEquals(McpServerStatus.DISCONNECTED, entry.getStatus());
            assertNotNull(entry.getLastError());
            verify(toolRegistrar).unregisterTools(entry);
        }

        @Test
        @DisplayName("Server 不存在时：抛 BusinessException(MCP_SERVER_NOT_FOUND)")
        void markDisconnected_whenNotExists_shouldThrowNotFound() {
            when(clientRegistry.get("nonexistent")).thenReturn(null);
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> manager.markDisconnected("nonexistent"));
            assertEquals(ErrorCode.MCP_SERVER_NOT_FOUND, ex.getErrorCode());
        }
    }

    // ==================== list / getServer / listTools 组 ====================

    @Nested
    @DisplayName("list() / getServer() / listTools() 查询")
    class QueryTest {

        @Test
        @DisplayName("list() 返回所有 Server 元数据列表（含静态和动态）")
        void list_shouldReturnAllServers() {
            // given
            McpServer weather = createStdioServer("weather");
            McpServer fetch = createSseServer("fetch", "https://example.com/sse");
            McpClientEntry entry1 = new McpClientEntry(weather, mock(McpClient.class), mock(McpTransport.class));
            McpClientEntry entry2 = new McpClientEntry(fetch, mock(McpClient.class), mock(McpTransport.class));
            when(clientRegistry.list()).thenReturn(List.of(entry1, entry2));

            // when
            List<McpServer> result = manager.list();

            // then
            assertEquals(2, result.size());
            assertTrue(result.stream().anyMatch(s -> "weather".equals(s.getName())));
            assertTrue(result.stream().anyMatch(s -> "fetch".equals(s.getName())));
        }

        @Test
        @DisplayName("list() 当无 Server 时返回空列表")
        void list_whenEmpty_shouldReturnEmptyList() {
            when(clientRegistry.list()).thenReturn(List.of());
            List<McpServer> result = manager.list();
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("getServer() 当 Server 存在时返回 McpServer 元数据")
        void getServer_whenExists_shouldReturnServer() {
            McpServer weather = createStdioServer("weather");
            McpClientEntry entry = new McpClientEntry(weather, mock(McpClient.class), mock(McpTransport.class));
            when(clientRegistry.get("weather")).thenReturn(entry);

            McpServer result = manager.getServer("weather");
            assertNotNull(result);
            assertEquals("weather", result.getName());
        }

        @Test
        @DisplayName("getServer() 当 Server 不存在时返回 null")
        void getServer_whenNotExists_shouldReturnNull() {
            when(clientRegistry.get("nonexistent")).thenReturn(null);
            assertNull(manager.getServer("nonexistent"));
        }

        @Test
        @DisplayName("listTools() 当 Server 存在时返回该 Server 的工具元数据列表")
        void listTools_whenExists_shouldReturnTools() {
            McpServer weather = createStdioServer("weather");
            McpToolInfo tool1 = new McpToolInfo();
            tool1.setOriginalName("getForecast");
            tool1.setRegisteredName("mcp_weather_getForecast");
            weather.setTools(List.of(tool1));
            McpClientEntry entry = new McpClientEntry(weather, mock(McpClient.class), mock(McpTransport.class));
            when(clientRegistry.get("weather")).thenReturn(entry);

            List<McpToolInfo> result = manager.listTools("weather");
            assertEquals(1, result.size());
            assertEquals("mcp_weather_getForecast", result.get(0).getRegisteredName());
        }

        @Test
        @DisplayName("listTools() 当 Server 不存在时抛 BusinessException(MCP_SERVER_NOT_FOUND)")
        void listTools_whenNotExists_shouldThrowNotFound() {
            when(clientRegistry.get("nonexistent")).thenReturn(null);
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> manager.listTools("nonexistent"));
            assertEquals(ErrorCode.MCP_SERVER_NOT_FOUND, ex.getErrorCode());
        }
    }
}
