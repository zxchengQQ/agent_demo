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
import org.junit.jupiter.api.Test;

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
    @DisplayName("sse 模式 url 非法格式（非 http/https）时抛 BusinessException")
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
}
