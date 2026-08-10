package com.agentdemo.mcp.tool;

import com.agentdemo.mcp.client.McpClientEntry;
import com.agentdemo.mcp.client.McpClientRegistry;
import com.agentdemo.mcp.client.McpTransportWrapper;
import com.agentdemo.mcp.entity.McpServer;
import com.agentdemo.mcp.entity.McpServerStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.protocol.McpClientMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MCP 图片内容端到端集成测试（CR-001 + CR-002 适配）
 * <p>
 * 模拟 mermaid-mcp 工具返回图片内容的完整流程：
 * 1. Agent 调用 validate_and_render_mermaid_diagram 工具，传入 Mermaid 图表语法
 * 2. DefaultMcpClient.executeTool() 内部调用 transport.executeOperationWithResponse()
 * 3. McpTransportWrapper 拦截并缓存原始 JSON-RPC 响应
 * 4. ToolExecutionHelper.extractResult() 解析响应时抛出 Unsupported content type: "image"
 *    或 executeTool 成功返回（CR-002: 返回值被丢弃）
 * 5. McpToolExecutor 统一从 Wrapper 缓存通过 McpContentParser 解析，以 Markdown 格式返回
 * </p>
 * 关联 AC：AC-036, AC-037, AC-038, AC-040, AC-041
 */
@DisplayName("MCP 图片内容端到端集成测试（CR-001 + CR-002）")
@ExtendWith(MockitoExtension.class)
class McpImageContentE2ETest {

    @Mock
    private McpClientRegistry clientRegistry;

    @Mock
    private McpClient mcpClient;

    private McpToolExecutor executor;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * CR-002: 使用真实的 McpContentParser（非 mock），验证 McpToolExecutor 与 McpContentParser 的集成
     */
    @BeforeEach
    void setUp() {
        executor = new McpToolExecutor(clientRegistry, new McpContentParser());
    }

    // ==================== 端到端场景测试 ====================

    @Nested
    @DisplayName("场景一：mermaid-mcp 返回图片 URL（AC-036）")
    class ImageUrlScenario {

        @Test
        @DisplayName("完整流程：Agent 调用 mermaid 渲染工具 -> MCP 返回图片 URL -> 统一解析提取为 Markdown 图片语法")
        void e2e_mermaidMcpReturnsImageUrl() throws Exception {
            // === Given：构造 mermaid-mcp 返回图片 URL 的 JSON-RPC 响应 ===
            String imageUrl = "https://mermaid.ink/img/Z3JhcGggVEQKICAgIEFb5Y+R5patXSAtLT4gQtvml7bpl7RdCiAgICBCIC0tPiBDe+W8guWKqH0KICAgIEMgLS0+fOaXpXwgRFvlubTmkJ9dCiAgICBDIC0tPnzmml8gQg";
            String rawJsonRpcResponse = """
                    {"jsonrpc":"2.0","id":1,"result":{"content":[
                        {"type":"image","url":"%s"}
                    ]}}
                    """.formatted(imageUrl);
            JsonNode responseNode = objectMapper.readTree(rawJsonRpcResponse);

            // 创建真实的 McpTransportWrapper（非 mock），模拟真实拦截缓存行为
            McpTransport delegateTransport = mock(McpTransport.class);
            when(delegateTransport.executeOperationWithResponse(any(McpClientMessage.class)))
                    .thenReturn(CompletableFuture.completedFuture(responseNode));
            McpTransportWrapper wrapper = new McpTransportWrapper(delegateTransport);

            // 创建 mermaid-mcp 的 entry
            McpServer server = new McpServer();
            server.setName("mermaid-mcp");
            McpClientEntry entry = new McpClientEntry(server, mcpClient, wrapper);
            entry.setStatus(McpServerStatus.CONNECTED);
            when(clientRegistry.get("mermaid-mcp")).thenReturn(entry);

            // === When：模拟 DefaultMcpClient.executeTool() 的内部行为 ===
            // 真实流程：executeTool 内部调用 transport.executeOperationWithResponse（触发 Wrapper 缓存）
            //          然后调用 ToolExecutionHelper.extractResult() 解析时抛出异常
            when(mcpClient.executeTool(any(ToolExecutionRequest.class))).thenAnswer(invocation -> {
                wrapper.executeOperationWithResponse(mock(McpClientMessage.class)).get();
                throw new RuntimeException("Unsupported content type: \"image\"");
            });

            // Agent 调用工具
            String result = executor.execute("mermaid-mcp", "validate_and_render_mermaid_diagram",
                    "{\"mermaid\":\"graph TD; A-->B\"}");

            // === Then：验证系统从缓存的原始响应中提取了图片 URL，以 Markdown 格式返回 ===
            assertNotNull(result, "结果不应为 null");
            assertTrue(result.contains("![图片](" + imageUrl + ")"),
                    "结果应包含 Markdown 图片语法，实际: " + result);
            System.out.println("[场景一] 工具返回结果:\n" + result);
        }
    }

    @Nested
    @DisplayName("场景二：mermaid-mcp 返回 base64 图片（AC-037）")
    class ImageBase64Scenario {

        @Test
        @DisplayName("完整流程：MCP 返回 base64 图片 -> 统一解析提取 MIME 类型和数据长度")
        void e2e_mermaidMcpReturnsBase64Image() throws Exception {
            // === Given：构造包含 base64 图片的 JSON-RPC 响应 ===
            String base64Data = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";
            String rawJsonRpcResponse = """
                    {"jsonrpc":"2.0","id":2,"result":{"content":[
                        {"type":"image","data":"%s","mimeType":"image/png"}
                    ]}}
                    """.formatted(base64Data);
            JsonNode responseNode = objectMapper.readTree(rawJsonRpcResponse);

            McpTransport delegateTransport = mock(McpTransport.class);
            when(delegateTransport.executeOperationWithResponse(any(McpClientMessage.class)))
                    .thenReturn(CompletableFuture.completedFuture(responseNode));
            McpTransportWrapper wrapper = new McpTransportWrapper(delegateTransport);

            McpServer server = new McpServer();
            server.setName("mermaid-mcp");
            McpClientEntry entry = new McpClientEntry(server, mcpClient, wrapper);
            entry.setStatus(McpServerStatus.CONNECTED);
            when(clientRegistry.get("mermaid-mcp")).thenReturn(entry);

            // === When ===
            when(mcpClient.executeTool(any(ToolExecutionRequest.class))).thenAnswer(invocation -> {
                wrapper.executeOperationWithResponse(mock(McpClientMessage.class)).get();
                throw new RuntimeException("Unsupported content type: \"image\"");
            });

            String result = executor.execute("mermaid-mcp", "validate_and_render_mermaid_diagram",
                    "{\"mermaid\":\"graph TD; A-->B\"}");

            // === Then ===
            assertTrue(result.contains("[图片]"), "结果应包含图片标识");
            assertTrue(result.contains("image/png"), "结果应包含 MIME 类型 image/png");
            assertTrue(result.contains("base64"), "结果应包含 base64 提示");
            assertTrue(result.contains(String.valueOf(base64Data.length())),
                    "结果应包含 base64 数据长度: " + base64Data.length());
            System.out.println("[场景二] 工具返回结果:\n" + result);
        }
    }

    @Nested
    @DisplayName("场景三：mermaid-mcp 返回混合内容 text + image（AC-038）")
    class MixedContentScenario {

        @Test
        @DisplayName("完整流程：MCP 返回文本描述 + 图片 URL -> 统一解析拼接返回")
        void e2e_mermaidMcpReturnsMixedContent() throws Exception {
            // === Given：构造包含文本 + 图片的混合 JSON-RPC 响应 ===
            String imageUrl = "https://mermaid.ink/img/mixed123";
            String rawJsonRpcResponse = """
                    {"jsonrpc":"2.0","id":3,"result":{"content":[
                        {"type":"text","text":"Mermaid 图表验证通过，已渲染为 PNG 图片。"},
                        {"type":"image","url":"%s"}
                    ]}}
                    """.formatted(imageUrl);
            JsonNode responseNode = objectMapper.readTree(rawJsonRpcResponse);

            McpTransport delegateTransport = mock(McpTransport.class);
            when(delegateTransport.executeOperationWithResponse(any(McpClientMessage.class)))
                    .thenReturn(CompletableFuture.completedFuture(responseNode));
            McpTransportWrapper wrapper = new McpTransportWrapper(delegateTransport);

            McpServer server = new McpServer();
            server.setName("mermaid-mcp");
            McpClientEntry entry = new McpClientEntry(server, mcpClient, wrapper);
            entry.setStatus(McpServerStatus.CONNECTED);
            when(clientRegistry.get("mermaid-mcp")).thenReturn(entry);

            // === When ===
            when(mcpClient.executeTool(any(ToolExecutionRequest.class))).thenAnswer(invocation -> {
                wrapper.executeOperationWithResponse(mock(McpClientMessage.class)).get();
                throw new RuntimeException("Unsupported content type: \"image\"");
            });

            String result = executor.execute("mermaid-mcp", "validate_and_render_mermaid_diagram",
                    "{\"mermaid\":\"sequenceDiagram\\n    Alice->>Bob: Hello\"}");

            // === Then：验证文本和图片都被正确提取 ===
            assertTrue(result.contains("Mermaid 图表验证通过"), "结果应包含文本内容");
            assertTrue(result.contains("![图片](" + imageUrl + ")"), "结果应包含 Markdown 图片语法");
            // 验证文本在图片之前（保持响应中的顺序）
            int textIdx = result.indexOf("Mermaid 图表验证通过");
            int imgIdx = result.indexOf("![图片]");
            assertTrue(textIdx < imgIdx, "文本应在图片之前（保持响应顺序）");
            System.out.println("[场景三] 工具返回结果:\n" + result);
        }
    }

    @Nested
    @DisplayName("场景四：统一解析路径 + Transport 包装器透明性验证（AC-040, AC-041）")
    class WrapperTransparencyScenario {

        @Test
        @DisplayName("CR-002 统一解析：正常文本工具调用也走 Wrapper 缓存 -> McpContentParser 解析")
        void e2e_normalTextCallWithWrapper() throws Exception {
            // === Given：创建带 Wrapper 的 entry ===
            String rawJsonRpcResponse = """
                    {"jsonrpc":"2.0","id":4,"result":{"content":[
                        {"type":"text","text":"北京今天晴天，25度"}
                    ]}}
                    """;
            JsonNode responseNode = objectMapper.readTree(rawJsonRpcResponse);

            McpTransport delegateTransport = mock(McpTransport.class);
            when(delegateTransport.executeOperationWithResponse(any(McpClientMessage.class)))
                    .thenReturn(CompletableFuture.completedFuture(responseNode));
            McpTransportWrapper wrapper = new McpTransportWrapper(delegateTransport);

            McpServer server = new McpServer();
            server.setName("weather");
            McpClientEntry entry = new McpClientEntry(server, mcpClient, wrapper);
            entry.setStatus(McpServerStatus.CONNECTED);
            when(clientRegistry.get("weather")).thenReturn(entry);

            // CR-002: executeTool 成功时也触发 Wrapper 缓存（模拟真实 DefaultMcpClient 行为）
            when(mcpClient.executeTool(any(ToolExecutionRequest.class))).thenAnswer(invocation -> {
                wrapper.executeOperationWithResponse(mock(McpClientMessage.class)).get();
                return null; // CR-002: 返回值被丢弃
            });

            // === When ===
            String result = executor.execute("weather", "getForecast", "{\"city\":\"北京\"}");

            // === Then：统一解析路径从 Wrapper 缓存提取文本 ===
            assertEquals("北京今天晴天，25度", result, "CR-002 统一解析路径应从 Wrapper 缓存提取文本");
            System.out.println("[场景四-统一解析] 工具返回结果:\n" + result);
        }

        @Test
        @DisplayName("Wrapper 缓存在使用后应被清除（避免内存泄漏）")
        void e2e_cacheClearedAfterUse() throws Exception {
            String rawJsonRpcResponse = """
                    {"jsonrpc":"2.0","id":5,"result":{"content":[
                        {"type":"image","url":"https://example.com/clear-test.png"}
                    ]}}
                    """;
            JsonNode responseNode = objectMapper.readTree(rawJsonRpcResponse);

            McpTransport delegateTransport = mock(McpTransport.class);
            when(delegateTransport.executeOperationWithResponse(any(McpClientMessage.class)))
                    .thenReturn(CompletableFuture.completedFuture(responseNode));
            McpTransportWrapper wrapper = new McpTransportWrapper(delegateTransport);

            McpServer server = new McpServer();
            server.setName("mermaid-mcp");
            McpClientEntry entry = new McpClientEntry(server, mcpClient, wrapper);
            entry.setStatus(McpServerStatus.CONNECTED);
            when(clientRegistry.get("mermaid-mcp")).thenReturn(entry);

            when(mcpClient.executeTool(any(ToolExecutionRequest.class))).thenAnswer(invocation -> {
                wrapper.executeOperationWithResponse(mock(McpClientMessage.class)).get();
                throw new RuntimeException("Unsupported content type: \"image\"");
            });

            // 第一次调用：Wrapper 应缓存响应，Executor 提取后清除
            String result = executor.execute("mermaid-mcp", "render", "{}");
            assertTrue(result.contains("![图片]"), "第一次调用应成功提取图片");

            // 验证缓存已被清除
            assertNull(wrapper.getLastRawResponse(),
                    "Executor 提取后应清除 Wrapper 缓存，避免内存泄漏");
            System.out.println("[场景四-缓存清除] 验证通过，缓存已被清除");
        }
    }

    @Nested
    @DisplayName("场景五：异常边界情况")
    class EdgeCases {

        @Test
        @DisplayName("Wrapper 缓存为空时回退到降级提示信息")
        void e2e_fallbackWhenCacheEmpty() {
            // 创建 Wrapper 但不触发 executeOperationWithResponse（缓存为空）
            McpTransport delegateTransport = mock(McpTransport.class);
            McpTransportWrapper wrapper = new McpTransportWrapper(delegateTransport);

            McpServer server = new McpServer();
            server.setName("mermaid-mcp");
            McpClientEntry entry = new McpClientEntry(server, mcpClient, wrapper);
            entry.setStatus(McpServerStatus.CONNECTED);
            when(clientRegistry.get("mermaid-mcp")).thenReturn(entry);

            // executeTool 直接抛异常，未触发 Wrapper 缓存
            when(mcpClient.executeTool(any(ToolExecutionRequest.class)))
                    .thenThrow(new RuntimeException("Unsupported content type: \"image\""));

            String result = executor.execute("mermaid-mcp", "render", "{}");

            assertTrue(result.contains("工具调用已成功"), "缓存为空时应回退到降级提示");
            assertTrue(result.contains("无法直接展示"), "降级提示应包含无法展示说明");
            System.out.println("[场景五-边界] 回退提示:\n" + result);
        }

        @Test
        @DisplayName("原始响应 JSON 结构异常时回退到降级提示信息")
        void e2e_fallbackWhenJsonInvalid() throws Exception {
            // 构造合法 JSON 但缺少 result.content 结构
            JsonNode invalidNode = objectMapper.readTree("{\"unexpected\":\"structure\",\"no\":\"content\"}");

            McpTransport delegateTransport = mock(McpTransport.class);
            when(delegateTransport.executeOperationWithResponse(any(McpClientMessage.class)))
                    .thenReturn(CompletableFuture.completedFuture(invalidNode));
            McpTransportWrapper wrapper = new McpTransportWrapper(delegateTransport);

            McpServer server = new McpServer();
            server.setName("mermaid-mcp");
            McpClientEntry entry = new McpClientEntry(server, mcpClient, wrapper);
            entry.setStatus(McpServerStatus.CONNECTED);
            when(clientRegistry.get("mermaid-mcp")).thenReturn(entry);

            when(mcpClient.executeTool(any(ToolExecutionRequest.class))).thenAnswer(invocation -> {
                wrapper.executeOperationWithResponse(mock(McpClientMessage.class)).get();
                throw new RuntimeException("Unsupported content type: \"image\"");
            });

            String result = executor.execute("mermaid-mcp", "render", "{}");

            assertTrue(result.contains("工具调用已成功"), "JSON 格式损坏时应回退到降级提示");
            System.out.println("[场景五-JSON异常] 回退提示:\n" + result);
        }
    }
}
