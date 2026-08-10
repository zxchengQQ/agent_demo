package com.agentdemo.mcp.entity;

import com.agentdemo.mcp.config.McpTransportType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * McpServer + McpToolInfo 实体类测试
 * <p>
 * 验证来源：Task-06 验证标准
 * 关联 AC：AC-004, AC-011
 * </p>
 */
@DisplayName("McpServer + McpToolInfo 实体类测试")
class McpServerTest {

    @Test
    @DisplayName("McpServer 默认值：enabled=true, args/env/headers/tools 为空集合")
    void mcpServerShouldHaveDefaultValues() {
        McpServer server = new McpServer();

        assertTrue(server.isEnabled(), "Server 默认应启用");
        assertNotNull(server.getArgs(), "args 不应为 null");
        assertTrue(server.getArgs().isEmpty(), "默认 args 应为空列表");
        assertNotNull(server.getEnv(), "env 不应为 null");
        assertTrue(server.getEnv().isEmpty(), "默认 env 应为空 Map");
        assertNotNull(server.getHeaders(), "headers 不应为 null");
        assertTrue(server.getHeaders().isEmpty(), "默认 headers 应为空 Map");
        assertNotNull(server.getTools(), "tools 不应为 null");
        assertTrue(server.getTools().isEmpty(), "默认 tools 应为空列表");
    }

    @Test
    @DisplayName("McpServer setter 设置 stdio 相关字段后 getter 返回对应值")
    void mcpServerShouldAcceptStdioFields() {
        McpServer server = new McpServer();
        server.setName("weather");
        server.setTransport(McpTransportType.STDIO);
        server.setCommand("node");
        server.getArgs().add("/path/to/server.js");
        server.getEnv().put("API_KEY", "xxx");

        assertEquals("weather", server.getName());
        assertEquals(McpTransportType.STDIO, server.getTransport());
        assertEquals("node", server.getCommand());
        assertEquals(1, server.getArgs().size());
        assertEquals("xxx", server.getEnv().get("API_KEY"));
    }

    @Test
    @DisplayName("McpServer 支持 toolTimeout 字段（Duration 类型）")
    void mcpServerShouldSupportToolTimeout() {
        McpServer server = new McpServer();
        server.setToolTimeout(Duration.ofSeconds(120));

        assertEquals(Duration.ofSeconds(120), server.getToolTimeout());
    }

    @Test
    @DisplayName("McpServer 支持 connectTime 与 lastActiveTime 字段")
    void mcpServerShouldSupportTimeFields() {
        McpServer server = new McpServer();
        LocalDateTime now = LocalDateTime.now();
        server.setConnectTime(now);
        server.setLastActiveTime(now);

        assertEquals(now, server.getConnectTime());
        assertEquals(now, server.getLastActiveTime());
    }

    @Test
    @DisplayName("McpToolInfo setter 设置字段后 getter 返回对应值")
    void mcpToolInfoShouldAcceptFields() {
        McpToolInfo toolInfo = new McpToolInfo();
        toolInfo.setOriginalName("getForecast");
        toolInfo.setRegisteredName("mcp_weather_getForecast");
        toolInfo.setDescription("获取指定城市的天气预报");
        toolInfo.setParametersSchema("{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}");

        assertEquals("getForecast", toolInfo.getOriginalName());
        assertEquals("mcp_weather_getForecast", toolInfo.getRegisteredName());
        assertEquals("获取指定城市的天气预报", toolInfo.getDescription());
        assertEquals("{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}",
                toolInfo.getParametersSchema());
    }
}
