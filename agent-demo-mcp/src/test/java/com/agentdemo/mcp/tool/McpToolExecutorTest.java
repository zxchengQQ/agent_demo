package com.agentdemo.mcp.tool;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.mcp.client.McpClientEntry;
import com.agentdemo.mcp.client.McpClientRegistry;
import com.agentdemo.mcp.client.McpTransportWrapper;
import com.agentdemo.mcp.entity.McpServer;
import com.agentdemo.mcp.entity.McpServerStatus;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.McpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * McpToolExecutor 工具执行器测试
 * <p>
 * 验证来源：Task-10 验证标准 + Task-24/25 CR-002 适配
 * 关联 AC：AC-012, AC-013, AC-018, AC-027, AC-036, AC-037, AC-038, AC-041
 * </p>
 */
@DisplayName("McpToolExecutor 工具执行器测试")
@ExtendWith(MockitoExtension.class)
class McpToolExecutorTest {

    @Mock
    private McpClientRegistry clientRegistry;

    @Mock
    private McpClient mcpClient;

    @Mock
    private McpContentParser contentParser;

    private McpToolExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new McpToolExecutor(clientRegistry, contentParser);
    }

    // ==================== 基础执行流程测试（#1-#8）====================

    @Test
    @DisplayName("execute 当 Server CONNECTED + executeTool 成功时从 Wrapper 缓存解析返回文本")
    void executeShouldReturnResultWhenConnected() {
        String rawResponse = "{\"jsonrpc\":\"2.0\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"晴天 25度\"}]}}";
        McpClientEntry entry = createConnectedEntryWithWrapper("weather", rawResponse);
        when(mcpClient.executeTool(any(ToolExecutionRequest.class))).thenReturn(null);
        when(contentParser.parse(rawResponse)).thenReturn("晴天 25度");

        String result = executor.execute("weather", "getForecast", "{\"city\":\"北京\"}");

        assertEquals("晴天 25度", result);
    }

    @Test
    @DisplayName("execute 当 argsJson 为 null 时传入空 JSON 不抛异常")
    void executeShouldAcceptNullArgsJson() {
        String rawResponse = "{\"jsonrpc\":\"2.0\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"结果\"}]}}";
        McpClientEntry entry = createConnectedEntryWithWrapper("weather", rawResponse);
        when(mcpClient.executeTool(any(ToolExecutionRequest.class))).thenReturn(null);
        when(contentParser.parse(rawResponse)).thenReturn("结果");

        String result = executor.execute("weather", "getForecast", null);

        assertEquals("结果", result);
    }

    @Test
    @DisplayName("execute 当 argsJson 格式错误时抛 BusinessException(MCP_TOOL_CALL_FAILED)")
    void executeShouldThrowWhenArgsJsonInvalid() {
        McpClientEntry entry = createConnectedEntry("weather");
        when(clientRegistry.get("weather")).thenReturn(entry);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> executor.execute("weather", "getForecast", "invalid json"));

        assertEquals(ErrorCode.MCP_TOOL_CALL_FAILED, ex.getErrorCode());
    }

    @Test
    @DisplayName("execute 当 Server 不存在时抛 BusinessException(MCP_TOOL_CALL_FAILED)")
    void executeShouldThrowWhenServerNotFound() {
        when(clientRegistry.get("nonexistent")).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> executor.execute("nonexistent", "tool", "{}"));

        assertEquals(ErrorCode.MCP_TOOL_CALL_FAILED, ex.getErrorCode());
    }

    @Test
    @DisplayName("execute 当 Server 状态非 CONNECTED 时抛 BusinessException(MCP_TOOL_CALL_FAILED)")
    void executeShouldThrowWhenServerNotConnected() {
        McpClientEntry entry = createEntryWithStatus("weather", McpServerStatus.DISCONNECTED);
        when(clientRegistry.get("weather")).thenReturn(entry);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> executor.execute("weather", "tool", "{}"));

        assertEquals(ErrorCode.MCP_TOOL_CALL_FAILED, ex.getErrorCode());
    }

    @Test
    @DisplayName("execute 当 mcpClient.executeTool 抛 RuntimeException（非 Unsupported）时包装为 BusinessException")
    void executeShouldWrapRuntimeException() {
        McpClientEntry entry = createConnectedEntry("weather");
        when(clientRegistry.get("weather")).thenReturn(entry);
        when(mcpClient.executeTool(any(ToolExecutionRequest.class)))
                .thenThrow(new RuntimeException("工具内部错误"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> executor.execute("weather", "tool", "{}"));

        assertEquals(ErrorCode.MCP_TOOL_CALL_FAILED, ex.getErrorCode());
    }

    @Test
    @DisplayName("execute 当 mcpClient.executeTool 抛 IOException 时标记 DISCONNECTED 并抛 BusinessException")
    void executeShouldMarkDisconnectedOnIOException() {
        McpClientEntry entry = createConnectedEntry("weather");
        when(clientRegistry.get("weather")).thenReturn(entry);
        when(mcpClient.executeTool(any(ToolExecutionRequest.class)))
                .thenThrow(new RuntimeException(new IOException("连接断开")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> executor.execute("weather", "tool", "{}"));

        assertEquals(ErrorCode.MCP_TOOL_CALL_FAILED, ex.getErrorCode());
        assertEquals(McpServerStatus.DISCONNECTED, entry.getStatus());
    }

    @Test
    @DisplayName("execute 当 argsJson 为空字符串时视为合法（等同于 null）")
    void executeShouldAcceptEmptyArgsJson() {
        String rawResponse = "{\"jsonrpc\":\"2.0\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"结果\"}]}}";
        McpClientEntry entry = createConnectedEntryWithWrapper("weather", rawResponse);
        when(mcpClient.executeTool(any(ToolExecutionRequest.class))).thenReturn(null);
        when(contentParser.parse(rawResponse)).thenReturn("结果");

        String result = executor.execute("weather", "getForecast", "");

        assertEquals("结果", result);
    }

    // ==================== CR-002: 统一解析路径测试（#9-#14）====================

    @Test
    @DisplayName("execute 当 executeTool 成功时统一从 Wrapper 缓存解析（不使用 ToolExecutionResult）")
    void executeShouldParseFromWrapperWhenSuccess() {
        String rawResponse = "{\"jsonrpc\":\"2.0\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"OK\"}]}}";
        McpClientEntry entry = createConnectedEntryWithWrapper("weather", rawResponse);
        when(mcpClient.executeTool(any(ToolExecutionRequest.class))).thenReturn(null);
        when(contentParser.parse(rawResponse)).thenReturn("OK");

        String result = executor.execute("weather", "tool", "{}");

        assertEquals("OK", result);
    }

    @Test
    @DisplayName("execute 当 executeTool 抛 Unsupported content type 时从 Wrapper 缓存解析（不视为错误）")
    void executeShouldParseFromWrapperWhenUnsupportedContentType() {
        String rawResponse = "{\"jsonrpc\":\"2.0\",\"result\":{\"content\":[{\"type\":\"image\",\"url\":\"https://example.com/img.png\"}]}}";
        McpClientEntry entry = createConnectedEntryWithWrapper("mermaid-mcp", rawResponse);
        when(mcpClient.executeTool(any(ToolExecutionRequest.class)))
                .thenThrow(new RuntimeException("Unsupported content type: \"image\""));
        when(contentParser.parse(rawResponse)).thenReturn("![图片](https://example.com/img.png)");

        String result = executor.execute("mermaid-mcp", "render", "{}");

        assertEquals("![图片](https://example.com/img.png)", result);
    }

    @Test
    @DisplayName("execute 当 Wrapper 缓存含图片 URL 时 contentParser 返回 Markdown 图片语法")
    void executeShouldReturnMarkdownImageViaContentParser() {
        String rawResponse = "{\"jsonrpc\":\"2.0\",\"result\":{\"content\":[{\"type\":\"image\",\"url\":\"https://example.com/diagram.png\"}]}}";
        McpClientEntry entry = createConnectedEntryWithWrapper("mermaid-mcp", rawResponse);
        when(mcpClient.executeTool(any(ToolExecutionRequest.class)))
                .thenThrow(new RuntimeException("Unsupported content type: \"image\""));
        when(contentParser.parse(rawResponse)).thenReturn("![图片](https://example.com/diagram.png)");

        String result = executor.execute("mermaid-mcp", "render", "{}");

        assertTrue(result.contains("![图片](https://example.com/diagram.png)"),
                "结果应包含 Markdown 图片语法");
    }

    @Test
    @DisplayName("execute 当 Wrapper 缓存含 base64 图片时 contentParser 返回描述文本")
    void executeShouldReturnBase64ImageInfoViaContentParser() {
        String rawResponse = "{\"jsonrpc\":\"2.0\",\"result\":{\"content\":[{\"type\":\"image\",\"data\":\"abc123\",\"mimeType\":\"image/png\"}]}}";
        McpClientEntry entry = createConnectedEntryWithWrapper("mermaid-mcp", rawResponse);
        when(mcpClient.executeTool(any(ToolExecutionRequest.class)))
                .thenThrow(new RuntimeException("Unsupported content type: \"image\""));
        String expected = "[图片] 已生成 image/png 格式图片，base64 数据长度: 6 字符";
        when(contentParser.parse(rawResponse)).thenReturn(expected);

        String result = executor.execute("mermaid-mcp", "render", "{}");

        assertTrue(result.contains("[图片]"), "结果应包含图片标识");
        assertTrue(result.contains("image/png"), "结果应包含 MIME 类型");
    }

    @Test
    @DisplayName("execute 当 Wrapper 缓存含混合内容时 contentParser 返回拼接文本")
    void executeShouldReturnMixedContentViaContentParser() {
        String rawResponse = "{\"jsonrpc\":\"2.0\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"图表已生成\"},{\"type\":\"image\",\"url\":\"https://example.com/chart.png\"}]}}";
        McpClientEntry entry = createConnectedEntryWithWrapper("mermaid-mcp", rawResponse);
        when(mcpClient.executeTool(any(ToolExecutionRequest.class)))
                .thenThrow(new RuntimeException("Unsupported content type: \"image\""));
        when(contentParser.parse(rawResponse)).thenReturn("图表已生成\n![图片](https://example.com/chart.png)");

        String result = executor.execute("mermaid-mcp", "render", "{}");

        assertTrue(result.contains("图表已生成"), "结果应包含文本内容");
        assertTrue(result.contains("![图片](https://example.com/chart.png)"), "结果应包含 Markdown 图片语法");
    }

    @Test
    @DisplayName("execute 当 contentParser 返回 null 时回退到降级提示")
    void executeShouldFallbackWhenContentParserReturnsNull() {
        String rawResponse = "{\"jsonrpc\":\"2.0\",\"result\":{}}";
        McpClientEntry entry = createConnectedEntryWithWrapper("mermaid-mcp", rawResponse);
        when(mcpClient.executeTool(any(ToolExecutionRequest.class)))
                .thenThrow(new RuntimeException("Unsupported content type: \"image\""));
        when(contentParser.parse(rawResponse)).thenReturn(null);

        String result = executor.execute("mermaid-mcp", "render", "{}");

        assertTrue(result.contains("工具调用已成功"), "contentParser 返回 null 时应回退到降级提示");
    }

    // ==================== 降级提示测试（#15-#16）====================

    @Test
    @DisplayName("execute 当 Wrapper 缓存为 null 时返回降级提示")
    void executeShouldFallbackWhenWrapperCacheIsNull() {
        McpTransportWrapper mockWrapper = mock(McpTransportWrapper.class);
        when(mockWrapper.getLastRawResponse()).thenReturn(null);
        McpServer server = new McpServer();
        server.setName("mermaid-mcp");
        McpClientEntry entry = new McpClientEntry(server, mcpClient, mockWrapper);
        entry.setStatus(McpServerStatus.CONNECTED);
        when(clientRegistry.get("mermaid-mcp")).thenReturn(entry);
        when(mcpClient.executeTool(any(ToolExecutionRequest.class)))
                .thenThrow(new RuntimeException("Unsupported content type: \"image\""));

        String result = executor.execute("mermaid-mcp", "render", "{}");

        assertTrue(result.contains("工具调用已成功"), "Wrapper 缓存为 null 时应回退到降级提示");
    }

    @Test
    @DisplayName("execute 当 entry 无 Wrapper 时返回降级提示")
    void executeShouldFallbackWhenNoWrapper() {
        McpClientEntry entry = createConnectedEntry("mermaid-mcp");
        when(clientRegistry.get("mermaid-mcp")).thenReturn(entry);
        when(mcpClient.executeTool(any(ToolExecutionRequest.class)))
                .thenThrow(new RuntimeException("Unsupported content type: \"image\""));

        String result = executor.execute("mermaid-mcp", "render", "{}");

        assertTrue(result.contains("工具调用已成功"), "无 Wrapper 时应回退到降级提示");
    }

    // ==================== 辅助方法 ====================

    private McpClientEntry createConnectedEntry(String name) {
        return createEntryWithStatus(name, McpServerStatus.CONNECTED);
    }

    private McpClientEntry createEntryWithStatus(String name, McpServerStatus status) {
        McpServer server = new McpServer();
        server.setName(name);
        McpClientEntry entry = new McpClientEntry(server, mcpClient, null);
        entry.setStatus(status);
        return entry;
    }

    /**
     * 创建带 McpTransportWrapper 的 CONNECTED 状态 Entry
     * Wrapper 的 getLastRawResponse() 返回指定的原始 JSON-RPC 响应
     */
    private McpClientEntry createConnectedEntryWithWrapper(String name, String rawResponse) {
        McpTransportWrapper mockWrapper = mock(McpTransportWrapper.class);
        when(mockWrapper.getLastRawResponse()).thenReturn(rawResponse);
        McpServer server = new McpServer();
        server.setName(name);
        McpClientEntry entry = new McpClientEntry(server, mcpClient, mockWrapper);
        entry.setStatus(McpServerStatus.CONNECTED);
        when(clientRegistry.get(name)).thenReturn(entry);
        return entry;
    }
}
