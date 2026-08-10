package com.agentdemo.mcp.client;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.mcp.config.McpProperties;
import com.agentdemo.mcp.config.McpTransportType;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.HttpMcpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP 传输方式工厂
 * <p>
 * 业务含义：根据 ServerConfig.transport 字段创建对应的 McpTransport 实例，
 * 屏蔽 LangChain4j 不同传输方式的构建细节。MCP 协议定义了三种标准传输方式：
 * 1. stdio - 通过子进程的标准输入输出通信，适合本地 MCP Server
 * 2. SSE - 通过 HTTP POST 发请求 + Server-Sent Events 接收推送（旧版规范），适合远程 MCP Server
 * 3. HTTP - Streamable HTTP 传输（新版 MCP 2025-06-18 规范），单端点请求响应模式
 * </p>
 * <p>
 * 设计原则：
 * 1. 单一职责：仅负责 transport 创建，不涉及 McpClient 构建与连接管理
 * 2. 参数校验：在创建前校验必填字段（command/url），快速失败而非延迟到连接时
 * 3. 日志可观测：创建时记录传输方式与关键字段，便于调试
 * </p>
 */
@Slf4j
@Component
public class McpTransportFactory {

    /**
     * 根据配置创建 MCP 传输对象
     *
     * @param config Server 配置
     * @return McpTransport 实例（StdioMcpTransport 或 HttpMcpTransport）
     * @throws BusinessException transport 不支持时抛 MCP_TRANSPORT_UNSUPPORTED；
     *                           必填字段缺失或格式非法时抛 PARAM_INVALID
     */
    public McpTransport createTransport(McpProperties.ServerConfig config) {
        McpTransportType transport = config.getTransport();
        if (transport == null) {
            throw new BusinessException(ErrorCode.MCP_TRANSPORT_UNSUPPORTED,
                    "MCP Server " + config.getName() + " 的 transport 字段未配置");
        }

        McpTransport rawTransport = switch (transport) {
            case STDIO -> createStdioTransport(config);
            case SSE -> createSseTransport(config);
            case HTTP -> createHttpTransport(config);
        };

        // CR-001: 用 McpTransportWrapper 包装原始 Transport，拦截缓存工具调用的原始 JSON-RPC 响应
        return new McpTransportWrapper(rawTransport);
    }

    /**
     * 创建 stdio 传输对象
     * <p>
     * 业务含义：将 command + args 合并为完整命令列表（如 ["node", "/path/to/server.js"]），
     * 传给 StdioMcpTransport.Builder.command()。env 环境变量直接传递。
     * </p>
     */
    private McpTransport createStdioTransport(McpProperties.ServerConfig config) {
        if (config.getCommand() == null || config.getCommand().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "stdio 模式的 Server " + config.getName() + " 必须配置 command 字段");
        }

        // 合并 command + args 为完整命令列表
        List<String> command = new ArrayList<>();
        command.add(config.getCommand());
        if (config.getArgs() != null) {
            command.addAll(config.getArgs());
        }

        StdioMcpTransport.Builder builder = new StdioMcpTransport.Builder()
                .command(command)
                .logEvents(true);

        // 传递环境变量
        Map<String, String> env = config.getEnv();
        if (env != null && !env.isEmpty()) {
            builder.environment(env);
        }

        log.info("创建 stdio 传输: server={}, command={}", config.getName(), command);
        return builder.build();
    }

    /**
     * 创建 HTTP+SSE 传输对象
     * <p>
     * 业务含义：校验 url 必须为 http/https 协议，传给 HttpMcpTransport.Builder.sseUrl()。
     * headers 直接传递给 customHeaders()。
     * </p>
     */
    private McpTransport createSseTransport(McpProperties.ServerConfig config) {
        if (config.getUrl() == null || config.getUrl().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "sse 模式的 Server " + config.getName() + " 必须配置 url 字段");
        }

        String url = config.getUrl().trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "sse 模式的 Server " + config.getName() + " 的 url 必须为 http/https 协议，实际: " + url);
        }

        HttpMcpTransport.Builder builder = new HttpMcpTransport.Builder()
                .sseUrl(url)
                .logRequests(true)
                .logResponses(true);

        // 传递自定义请求头
        Map<String, String> headers = config.getHeaders();
        if (headers != null && !headers.isEmpty()) {
            builder.customHeaders(headers);
        }

        log.info("创建 sse 传输: server={}, url={}", config.getName(), url);
        return builder.build();
    }

    /**
     * 创建 Streamable HTTP 传输对象（新版 MCP 2025-06-18 规范）
     * <p>
     * 业务含义：校验 url 必须为 http/https 协议，传给 StreamableHttpMcpTransport.Builder.url()。
     * headers 直接传递给 customHeaders()。与 SSE 的区别：单端点请求响应模式，无需 SSE 长连接。
     * </p>
     */
    private McpTransport createHttpTransport(McpProperties.ServerConfig config) {
        if (config.getUrl() == null || config.getUrl().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "http 模式的 Server " + config.getName() + " 必须配置 url 字段");
        }

        String url = config.getUrl().trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "http 模式的 Server " + config.getName() + " 的 url 必须为 http/https 协议，实际: " + url);
        }

        StreamableHttpMcpTransport.Builder builder = new StreamableHttpMcpTransport.Builder()
                .url(url)
                .logRequests(true)
                .logResponses(true);

        // 传递自定义请求头
        Map<String, String> headers = config.getHeaders();
        if (headers != null && !headers.isEmpty()) {
            builder.customHeaders(headers);
        }

        log.info("创建 streamable http 传输: server={}, url={}", config.getName(), url);
        return builder.build();
    }
}
