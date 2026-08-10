package com.agentdemo.mcp.client;

import com.agentdemo.mcp.entity.McpServer;
import com.agentdemo.mcp.entity.McpServerStatus;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * McpClientEntry 聚合对象测试
 * <p>
 * 验证来源：Task-07 验证标准
 * 关联 AC：AC-009, AC-027
 * </p>
 */
@DisplayName("McpClientEntry 聚合对象测试")
@ExtendWith(MockitoExtension.class)
class McpClientEntryTest {

    @Mock
    private McpClient mcpClient;

    @Mock
    private McpTransport transport;

    @Test
    @DisplayName("构造 McpClientEntry 后 getter 返回对应值")
    void constructorShouldSetFields() {
        McpServer server = new McpServer();
        server.setName("weather");

        McpClientEntry entry = new McpClientEntry(server, mcpClient, transport);

        assertEquals("weather", entry.getServer().getName());
        assertEquals(mcpClient, entry.getMcpClient());
        assertEquals(transport, entry.getTransport());
    }

    @Test
    @DisplayName("setStatus(CONNECTED) 后 getStatus() 返回 CONNECTED")
    void setStatusShouldUpdateStatus() {
        McpClientEntry entry = new McpClientEntry(new McpServer(), mcpClient, transport);

        entry.setStatus(McpServerStatus.CONNECTED);

        assertEquals(McpServerStatus.CONNECTED, entry.getStatus());
    }

    @Test
    @DisplayName("setLastError 后 getLastError 返回对应值")
    void setLastErrorShouldUpdateError() {
        McpClientEntry entry = new McpClientEntry(new McpServer(), mcpClient, transport);

        entry.setLastError("连接超时");

        assertEquals("连接超时", entry.getLastError());
    }

    @Test
    @DisplayName("close() 调用 mcpClient.close()，不抛异常")
    void closeShouldCallMcpClientCloseWithoutException() throws Exception {
        McpClientEntry entry = new McpClientEntry(new McpServer(), mcpClient, transport);
        doNothing().when(mcpClient).close();

        entry.close();

        verify(mcpClient).close();
    }

    @Test
    @DisplayName("close() 当 mcpClient.close() 抛 IOException 时仅记录 WARN 日志，不向上抛出")
    void closeShouldSwallowIOExceptionFromMcpClient() throws Exception {
        McpClientEntry entry = new McpClientEntry(new McpServer(), mcpClient, transport);
        doThrow(new IOException("子进程已退出")).when(mcpClient).close();

        // 不抛异常即视为通过
        entry.close();

        verify(mcpClient).close();
    }

    @Test
    @DisplayName("close() 当 mcpClient 为 null 时不抛 NPE")
    void closeShouldNotThrowNpeWhenMcpClientIsNull() {
        McpClientEntry entry = new McpClientEntry(new McpServer(), null, transport);

        // 不抛 NPE 即视为通过
        entry.close();
    }

    @Test
    @DisplayName("getMcpClient 当构造时传入 null 应返回 null")
    void getMcpClientShouldReturnNullWhenConstructedWithNull() {
        McpClientEntry entry = new McpClientEntry(new McpServer(), null, null);

        assertNull(entry.getMcpClient());
        assertNull(entry.getTransport());
    }

    // ==================== CR-001: getTransportWrapper 测试 ====================

    @Test
    @DisplayName("getTransportWrapper 当 transport 为 McpTransportWrapper 时返回 wrapper 实例")
    void getTransportWrapperShouldReturnWrapperWhenTransportIsWrapper() {
        McpTransportWrapper wrapper = new McpTransportWrapper(transport);
        McpClientEntry entry = new McpClientEntry(new McpServer(), mcpClient, wrapper);

        McpTransportWrapper result = entry.getTransportWrapper();

        assertNotNull(result);
        assertSame(wrapper, result);
    }

    @Test
    @DisplayName("getTransportWrapper 当 transport 非 McpTransportWrapper 时返回 null")
    void getTransportWrapperShouldReturnNullWhenTransportIsNotWrapper() {
        // transport 是 mock(McpTransport.class)，不是 McpTransportWrapper
        McpClientEntry entry = new McpClientEntry(new McpServer(), mcpClient, transport);

        McpTransportWrapper result = entry.getTransportWrapper();

        assertNull(result);
    }

    @Test
    @DisplayName("getTransportWrapper 当 transport 为 null 时返回 null")
    void getTransportWrapperShouldReturnNullWhenTransportIsNull() {
        McpClientEntry entry = new McpClientEntry(new McpServer(), mcpClient, null);

        McpTransportWrapper result = entry.getTransportWrapper();

        assertNull(result);
    }
}
