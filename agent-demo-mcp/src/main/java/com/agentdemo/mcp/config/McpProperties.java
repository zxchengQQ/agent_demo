package com.agentdemo.mcp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 协议模块配置属性
 * <p>
 * 业务含义：集中管理 MCP 客户端模块的所有可配置参数，包括模块开关、默认超时、
 * 静态预配置的 MCP Server 列表。通过 application.yml 中 mcp.* 前缀注入。
 * </p>
 * <p>
 * 设计原则：
 * 1. 模块开关：enabled=false 时整个 MCP 模块不加载（AC-022, AC-032）
 * 2. 双传输方式支持：servers 列表中可同时包含 stdio 和 sse 两种传输方式（AC-035）
 * 3. 超时可覆盖：default-tool-timeout 为全局默认值，单 Server 可通过 tool-timeout 覆盖（AC-030）
 * 4. 内存存储：动态添加的 Server 仅存内存，重启丢失（AC-033），与知识库"内存存储"风格一致
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "mcp")
public class McpProperties {

    /** MCP 模块总开关，false 时跳过启动加载并禁用所有 MCP API */
    private boolean enabled = true;

    /** 全局默认工具调用超时时间（可被单 Server 的 tool-timeout 覆盖） */
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration defaultToolTimeout = Duration.ofSeconds(60);

    /** 静态预配置 Server 列表（启动时自动加载；动态添加的 Server 仅存内存，重启丢失） */
    private List<ServerConfig> servers = new ArrayList<>();

    /**
     * 单个 MCP Server 的配置
     * <p>
     * 业务含义：根据 transport 字段决定哪些字段生效：
     * - transport=STDIO 时：command/args/env 生效，url/headers 被忽略
     * - transport=SSE 时：url/headers 生效，command/args/env 被忽略
     * </p>
     */
    @Data
    public static class ServerConfig {

        /** Server 唯一标识（与知识库"名称全局唯一"风格一致，REST API 路径形如 /api/mcp/servers/{name}） */
        private String name;

        /** 传输方式：STDIO（本地子进程）或 SSE（HTTP+SSE 远程） */
        private McpTransportType transport;

        /** 是否启用该 Server（false 时启动加载跳过该 Server，状态标记为 DISABLED） */
        private boolean enabled = true;

        // === stdio 传输方式专用字段 ===

        /** 启动 MCP Server 子进程的命令（如 node / python） */
        private String command;

        /** 命令行参数列表（如 ["/path/to/server.js", "--port", "8080"]） */
        private List<String> args = new ArrayList<>();

        /** 子进程环境变量 */
        private Map<String, String> env = new HashMap<>();

        // === sse 传输方式专用字段 ===

        /** 远程 MCP Server 的 SSE 端点 URL（如 https://mcp.example.com/sse） */
        private String url;

        /** HTTP 请求头（如 Authorization: Bearer xxx） */
        private Map<String, String> headers = new HashMap<>();

        // === 通用字段 ===

        /** 单 Server 工具调用超时（覆盖 default-tool-timeout） */
        @DurationUnit(ChronoUnit.SECONDS)
        private Duration toolTimeout;
    }
}
