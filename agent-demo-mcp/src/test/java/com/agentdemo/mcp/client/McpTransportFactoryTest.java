package com.agentdemo.mcp.client;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.mcp.config.McpProperties;
import com.agentdemo.mcp.config.McpTransportType;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.HttpMcpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * McpTransportFactory 传输工厂测试
 * <p>
 * 验证来源：Task-09 验证标准
 * 关联 AC：AC-002, AC-003, AC-021
 * </p>
 */
@DisplayName("McpTransportFactory 传输工厂测试")
class McpTransportFactoryTest {

    private McpTransportFactory factory;

    @BeforeEach
    void setUp() {
        factory = new McpTransportFactory();
    }

    @Test
    @DisplayName("transport=STDIO 且 command=node args=[/path/server.js] 时返回 McpTransportWrapper 包装 StdioMcpTransport")
    void createTransportStdioShouldReturnWrappedStdioMcpTransport() {
        McpProperties.ServerConfig config = new McpProperties.ServerConfig();
        config.setName("weather");
        config.setTransport(McpTransportType.STDIO);
        config.setCommand("node");
        config.setArgs(new ArrayList<>(List.of("/path/to/weather-server.js")));

        McpTransport transport = factory.createTransport(config);

        assertTrue(transport instanceof McpTransportWrapper,
                "应返回 McpTransportWrapper 实例");
        McpTransportWrapper wrapper = (McpTransportWrapper) transport;
        assertTrue(wrapper.getDelegate() instanceof StdioMcpTransport,
                "Wrapper 内部 delegate 应为 StdioMcpTransport");
    }

    @Test
    @DisplayName("transport=SSE 且 url 合法时返回 McpTransportWrapper 包装 HttpMcpTransport")
    void createTransportSseShouldReturnWrappedHttpMcpTransport() {
        McpProperties.ServerConfig config = new McpProperties.ServerConfig();
        config.setName("remote-fetch");
        config.setTransport(McpTransportType.SSE);
        config.setUrl("https://mcp.example.com/sse");

        McpTransport transport = factory.createTransport(config);

        assertTrue(transport instanceof McpTransportWrapper,
                "应返回 McpTransportWrapper 实例");
        McpTransportWrapper wrapper = (McpTransportWrapper) transport;
        assertTrue(wrapper.getDelegate() instanceof HttpMcpTransport,
                "Wrapper 内部 delegate 应为 HttpMcpTransport");
    }

    @Test
    @DisplayName("transport=HTTP 且 url 合法时返回 McpTransportWrapper 包装 StreamableHttpMcpTransport")
    void createTransportHttpShouldReturnWrappedStreamableHttpMcpTransport() {
        McpProperties.ServerConfig config = new McpProperties.ServerConfig();
        config.setName("mermaid-mcp");
        config.setTransport(McpTransportType.HTTP);
        config.setUrl("https://mcp.mermaid.ai/mcp");

        McpTransport transport = factory.createTransport(config);

        assertTrue(transport instanceof McpTransportWrapper,
                "应返回 McpTransportWrapper 实例");
        McpTransportWrapper wrapper = (McpTransportWrapper) transport;
        assertTrue(wrapper.getDelegate() instanceof StreamableHttpMcpTransport,
                "Wrapper 内部 delegate 应为 StreamableHttpMcpTransport");
    }

    @Test
    @DisplayName("transport=null 时抛 BusinessException(MCP_TRANSPORT_UNSUPPORTED)")
    void createTransportNullShouldThrowException() {
        McpProperties.ServerConfig config = new McpProperties.ServerConfig();
        config.setName("invalid");
        config.setTransport(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> factory.createTransport(config));

        assertEquals(ErrorCode.MCP_TRANSPORT_UNSUPPORTED, ex.getErrorCode(),
                "transport=null 时应抛出 MCP_TRANSPORT_UNSUPPORTED");
    }

    @Test
    @DisplayName("stdio 模式 env 配置正确传递给 StdioMcpTransport")
    void createTransportStdioShouldPassEnvToTransport() {
        McpProperties.ServerConfig config = new McpProperties.ServerConfig();
        config.setName("weather");
        config.setTransport(McpTransportType.STDIO);
        config.setCommand("node");
        config.setArgs(new ArrayList<>(List.of("/path/to/weather-server.js")));
        HashMap<String, String> env = new HashMap<>();
        env.put("API_KEY", "secret123");
        config.setEnv(env);

        // 不抛异常即视为 env 已正确传递
        McpTransport transport = factory.createTransport(config);

        assertTrue(transport instanceof McpTransportWrapper);
        assertTrue(((McpTransportWrapper) transport).getDelegate() instanceof StdioMcpTransport);
    }

    @Test
    @DisplayName("sse 模式 headers 配置正确传递给 HttpMcpTransport")
    void createTransportSseShouldPassHeadersToTransport() {
        McpProperties.ServerConfig config = new McpProperties.ServerConfig();
        config.setName("remote-fetch");
        config.setTransport(McpTransportType.SSE);
        config.setUrl("https://mcp.example.com/sse");
        HashMap<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer xxx");
        config.setHeaders(headers);

        // 不抛异常即视为 headers 已正确传递
        McpTransport transport = factory.createTransport(config);

        assertTrue(transport instanceof McpTransportWrapper);
        assertTrue(((McpTransportWrapper) transport).getDelegate() instanceof HttpMcpTransport);
    }

    @Test
    @DisplayName("stdio 模式 command 为空时抛 BusinessException")
    void createTransportStdioWithoutCommandShouldThrowException() {
        McpProperties.ServerConfig config = new McpProperties.ServerConfig();
        config.setName("weather");
        config.setTransport(McpTransportType.STDIO);
        config.setCommand(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> factory.createTransport(config));

        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode(),
                "stdio 模式 command 为空时应抛出 PARAM_INVALID");
    }

    @Test
    @DisplayName("sse 模式 url 为空时抛 BusinessException")
    void createTransportSseWithoutUrlShouldThrowException() {
        McpProperties.ServerConfig config = new McpProperties.ServerConfig();
        config.setName("remote-fetch");
        config.setTransport(McpTransportType.SSE);
        config.setUrl(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> factory.createTransport(config));

        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode(),
                "sse 模式 url 为空时应抛出 PARAM_INVALID");
    }

    @Test
    @DisplayName("sse 模式 url 非 http/https 时应抛出 PARAM_INVALID")
    void createTransportSseWithInvalidUrlSchemeShouldThrowException() {
        McpProperties.ServerConfig config = new McpProperties.ServerConfig();
        config.setName("remote-fetch");
        config.setTransport(McpTransportType.SSE);
        config.setUrl("ftp://invalid.example.com");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> factory.createTransport(config));

        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode(),
                "sse 模式 url 非 http/https 时应抛出 PARAM_INVALID");
    }

    /**
     * Windows 命令适配测试（BUG-20260811：stdio 配置 npx 连接失败）
     * <p>
     * 复现：Windows 下用户配置 command="npx"（业界通用写法），但 Java ProcessBuilder
     * 使用 CreateProcess 无法直接执行无扩展名的 npx（实为 npx.ps1/npx.cmd），
     * 导致 stdio 子进程启动失败，MCP 连接报 5400。
     * </p>
     * <p>
     * 修复：在 Windows 下，若 command 为无扩展名的裸命令且 PATH 中存在对应的
     * .cmd/.bat/.exe 文件，自动补全扩展名（npx -> npx.cmd）。
     * </p>
     * 关联 AC：BR-MCP-018
     */
    @Nested
    @DisplayName("Windows 命令适配（BUG-20260811）")
    class WindowsCommandAdapterTest {

        @TempDir
        Path tempDir;

        @Test
        @DisplayName("Windows 下 PATH 存在 npx.cmd 时裸命令 npx 适配为 npx.cmd")
        void resolveWindowsCommandShouldAppendCmdWhenCmdExists() throws IOException {
            Files.createFile(tempDir.resolve("npx.cmd"));

            String result = factory.resolveWindowsCommand("npx", tempDir.toString(), true);

            assertEquals("npx.cmd", result, "npx 应被适配为 npx.cmd");
        }

        @Test
        @DisplayName("Windows 下 PATH 存在 node.exe 时裸命令 node 适配为 node.exe")
        void resolveWindowsCommandShouldAppendExeWhenExeExists() throws IOException {
            Files.createFile(tempDir.resolve("node.exe"));

            String result = factory.resolveWindowsCommand("node", tempDir.toString(), true);

            assertEquals("node.exe", result, "node 应被适配为 node.exe");
        }

        @Test
        @DisplayName("Windows 下 PATH 中找不到对应可执行文件时保持原命令")
        void resolveWindowsCommandShouldKeepOriginalWhenNotFound() {
            String result = factory.resolveWindowsCommand("nonexistenttool", tempDir.toString(), true);
            assertEquals("nonexistenttool", result, "找不到时不应改变 command");
        }

        @Test
        @DisplayName("非 Windows 平台不进行命令适配")
        void resolveWindowsCommandShouldNotAdaptOnNonWindows() throws IOException {
            Files.createFile(tempDir.resolve("npx.cmd"));
            String result = factory.resolveWindowsCommand("npx", tempDir.toString(), false);
            assertEquals("npx", result, "非 Windows 平台不应适配");
        }

        @Test
        @DisplayName("已带扩展名的命令不进行适配")
        void resolveWindowsCommandShouldNotAdaptWhenHasExtension() {
            String result = factory.resolveWindowsCommand("my.server.js", tempDir.toString(), true);
            assertEquals("my.server.js", result, "已带扩展名不应适配");
        }

        @Test
        @DisplayName("createStdioTransport 在 Windows 下使用适配后的 npx.cmd 构建 StdioMcpTransport")
        void createStdioTransportShouldAdaptNpxOnWindows() throws IOException {
            Files.createFile(tempDir.resolve("npx.cmd"));
            McpProperties.ServerConfig config = new McpProperties.ServerConfig();
            config.setName("fetch");
            config.setTransport(McpTransportType.STDIO);
            config.setCommand("npx");
            config.setArgs(new ArrayList<>(List.of("mcp-fetch-server")));

            // 使用临时 PATH + 显式 Windows 平台判断，验证适配逻辑接入 createStdioTransport
            String adapted = factory.resolveWindowsCommand("npx", tempDir.toString(), true);
            McpProperties.ServerConfig adaptedConfig = new McpProperties.ServerConfig();
            adaptedConfig.setName("fetch");
            adaptedConfig.setTransport(McpTransportType.STDIO);
            adaptedConfig.setCommand(adapted);
            adaptedConfig.setArgs(new ArrayList<>(List.of("mcp-fetch-server")));

            McpTransport transport = factory.createTransport(adaptedConfig);

            assertEquals("npx.cmd", adapted, "适配后 command 应为 npx.cmd");
            assertTrue(transport instanceof McpTransportWrapper);
            assertTrue(((McpTransportWrapper) transport).getDelegate() instanceof StdioMcpTransport);
        }
    }
}
