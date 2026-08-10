package com.agentdemo.mcp.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * McpProperties 配置类测试
 * <p>
 * 验证来源：Task-04 验证标准
 * 关联 AC：AC-030, AC-032, AC-035
 * </p>
 */
@DisplayName("McpProperties 配置类测试")
class McpPropertiesTest {

    @Test
    @DisplayName("McpProperties 默认值：enabled=true, defaultToolTimeout=60s, servers=空列表")
    void mcpPropertiesShouldHaveDefaultValues() {
        McpProperties properties = new McpProperties();

        assertTrue(properties.isEnabled(), "默认应启用 MCP 模块");
        assertEquals(Duration.ofSeconds(60), properties.getDefaultToolTimeout(),
                "默认工具调用超时为 60s");
        assertNotNull(properties.getServers(), "servers 列表不应为 null");
        assertTrue(properties.getServers().isEmpty(), "默认 servers 列表应为空");
    }

    @Test
    @DisplayName("mcp.enabled=false 时 isEnabled() 返回 false")
    void mcpPropertiesEnabledFalseShouldReturnFalse() {
        McpProperties properties = new McpProperties();
        properties.setEnabled(false);

        assertFalse(properties.isEnabled(), "禁用 MCP 模块时 isEnabled() 应返回 false");
    }

    @Test
    @DisplayName("mcp.default-tool-timeout=30s 时返回 Duration.ofSeconds(30)")
    void mcpPropertiesCustomTimeoutShouldBeParsed() {
        McpProperties properties = new McpProperties();
        properties.setDefaultToolTimeout(Duration.ofSeconds(30));

        assertEquals(Duration.ofSeconds(30), properties.getDefaultToolTimeout());
    }

    @Test
    @DisplayName("ServerConfig 默认值：enabled=true, args/env/headers 为空集合")
    void serverConfigShouldHaveDefaultValues() {
        McpProperties.ServerConfig serverConfig = new McpProperties.ServerConfig();

        assertTrue(serverConfig.isEnabled(), "ServerConfig 默认应启用");
        assertNotNull(serverConfig.getArgs(), "args 不应为 null");
        assertTrue(serverConfig.getArgs().isEmpty(), "默认 args 应为空列表");
        assertNotNull(serverConfig.getEnv(), "env 不应为 null");
        assertTrue(serverConfig.getEnv().isEmpty(), "默认 env 应为空 Map");
        assertNotNull(serverConfig.getHeaders(), "headers 不应为 null");
        assertTrue(serverConfig.getHeaders().isEmpty(), "默认 headers 应为空 Map");
    }

    @Test
    @DisplayName("ServerConfig 设置 stdio 传输方式相关字段")
    void serverConfigShouldAcceptStdioFields() {
        McpProperties.ServerConfig serverConfig = new McpProperties.ServerConfig();
        serverConfig.setName("weather");
        serverConfig.setTransport(McpTransportType.STDIO);
        serverConfig.setCommand("node");
        serverConfig.setArgs(new ArrayList<>(java.util.List.of("/path/to/server.js")));

        java.util.HashMap<String, String> env = new java.util.HashMap<>();
        env.put("API_KEY", "xxx");
        serverConfig.setEnv(env);

        assertEquals("weather", serverConfig.getName());
        assertEquals(McpTransportType.STDIO, serverConfig.getTransport());
        assertEquals("node", serverConfig.getCommand());
        assertEquals(1, serverConfig.getArgs().size());
        assertEquals("/path/to/server.js", serverConfig.getArgs().get(0));
        assertEquals("xxx", serverConfig.getEnv().get("API_KEY"));
    }

    @Test
    @DisplayName("ServerConfig 设置 sse 传输方式相关字段")
    void serverConfigShouldAcceptSseFields() {
        McpProperties.ServerConfig serverConfig = new McpProperties.ServerConfig();
        serverConfig.setName("remote-fetch");
        serverConfig.setTransport(McpTransportType.SSE);
        serverConfig.setUrl("https://mcp.example.com/sse");

        HashMap<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer xxx");
        serverConfig.setHeaders(headers);

        assertEquals("remote-fetch", serverConfig.getName());
        assertEquals(McpTransportType.SSE, serverConfig.getTransport());
        assertEquals("https://mcp.example.com/sse", serverConfig.getUrl());
        assertEquals("Bearer xxx", serverConfig.getHeaders().get("Authorization"));
    }

    @Test
    @DisplayName("ServerConfig 支持单 Server 超时配置（覆盖 default-tool-timeout）")
    void serverConfigShouldAcceptToolTimeoutOverride() {
        McpProperties.ServerConfig serverConfig = new McpProperties.ServerConfig();
        serverConfig.setToolTimeout(Duration.ofSeconds(120));

        assertEquals(Duration.ofSeconds(120), serverConfig.getToolTimeout());
    }

    @Test
    @DisplayName("McpTransportType 枚举包含 STDIO、SSE 和 HTTP 三个值")
    void mcpTransportTypeShouldContainStdioAndSse() {
        assertEquals(3, McpTransportType.values().length, "McpTransportType 应有 3 个枚举值");
        assertEquals(McpTransportType.STDIO, McpTransportType.valueOf("STDIO"));
        assertEquals(McpTransportType.SSE, McpTransportType.valueOf("SSE"));
        assertEquals(McpTransportType.HTTP, McpTransportType.valueOf("HTTP"));
    }

    @Test
    @DisplayName("McpProperties servers 列表支持多个 Server 同时配置（验证 stdio 和 sse 并存）")
    void mcpPropertiesServersShouldSupportMultipleTransports() {
        McpProperties properties = new McpProperties();

        McpProperties.ServerConfig stdioServer = new McpProperties.ServerConfig();
        stdioServer.setName("weather");
        stdioServer.setTransport(McpTransportType.STDIO);
        stdioServer.setCommand("node");

        McpProperties.ServerConfig sseServer = new McpProperties.ServerConfig();
        sseServer.setName("remote-fetch");
        sseServer.setTransport(McpTransportType.SSE);
        sseServer.setUrl("https://mcp.example.com/sse");

        properties.setServers(new ArrayList<>(java.util.List.of(stdioServer, sseServer)));

        assertEquals(2, properties.getServers().size(), "应支持同时配置多个 Server");
        assertEquals(McpTransportType.STDIO, properties.getServers().get(0).getTransport());
        assertEquals(McpTransportType.SSE, properties.getServers().get(1).getTransport());
    }

    @Test
    @DisplayName("ServerConfig enabled=false 时表示该 Server 被禁用（验证 AC-032）")
    void serverConfigEnabledFalseShouldIndicateDisabled() {
        McpProperties.ServerConfig serverConfig = new McpProperties.ServerConfig();
        serverConfig.setEnabled(false);

        assertFalse(serverConfig.isEnabled(), "enabled=false 时应表示该 Server 被禁用");
    }
}
