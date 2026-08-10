package com.agentdemo.mcp.client;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.mcp.client.McpCallContext;
import dev.langchain4j.mcp.client.transport.McpOperationHandler;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.protocol.McpClientMessage;
import dev.langchain4j.mcp.protocol.McpInitializeRequest;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MCP Transport 包装器（CR-001）
 * <p>
 * 业务含义：作为 MCP 传输通道的"透明薄膜"，包裹在原始 Transport 外层。
 * 当 MCP Server 返回图片等非文本内容时，LangChain4j 内部 ToolExecutionHelper
 * 会抛出 {@code Unsupported content type: "image"} 异常，导致工具调用结果丢失。
 * 此包装器在透明代理所有 Transport 方法的同时，拦截工具调用的原始 JSON-RPC 响应
 * 并缓存到 AtomicReference，供 McpToolExecutor 在异常路径下提取图片信息。
 * </p>
 * <p>
 * 设计原则：
 * 1. 透明代理：所有接口方法直接委托给 delegate，不修改任何行为
 * 2. 仅缓存响应：仅在 executeOperationWithResponse 方法返回的 Future 完成后缓存响应
 * 3. 跨线程可见：使用 AtomicReference 存储缓存，因为 HTTP 传输的 CompletableFuture
 *    在异步 I/O 线程上完成，而 extractFromRawResponse 在 servlet 线程上读取
 * </p>
 * 关联 AC：AC-040
 * 关联 BR：BR-MCP-021
 */
@Slf4j
public class McpTransportWrapper implements McpTransport {

    /** 被包装的原始 Transport 对象（StdioMcpTransport / HttpMcpTransport / StreamableHttpMcpTransport） */
    private final McpTransport delegate;

    /** 原子引用缓存：存储最近一次工具调用的原始 JSON-RPC 响应字符串 */
    private final AtomicReference<String> lastRawResponse = new AtomicReference<>();

    /**
     * 构造包装器
     *
     * @param delegate 被包装的原始 Transport
     */
    public McpTransportWrapper(McpTransport delegate) {
        this.delegate = delegate;
    }

    /**
     * 获取被包装的原始 Transport
     *
     * @return 原始 Transport 实例
     */
    public McpTransport getDelegate() {
        return delegate;
    }

    /**
     * 获取最近一次缓存的原始 JSON-RPC 响应
     * <p>
     * 业务含义：当 mcpClient.executeTool() 抛出 Unsupported content type 异常时，
     * McpToolExecutor 调用此方法获取缓存的原始响应，从中提取图片 URL/base64 信息。
     * </p>
     *
     * @return 原始 JSON-RPC 响应字符串，无缓存时返回 null
     */
    public String getLastRawResponse() {
        return lastRawResponse.get();
    }

    /**
     * 清除缓存的原始响应
     * <p>
     * 业务含义：工具调用完成后清理缓存，避免内存泄漏。
     * </p>
     */
    public void clearCachedResponse() {
        lastRawResponse.set(null);
    }

    // ==================== 拦截方法：缓存原始响应 ====================

    @Override
    public CompletableFuture<JsonNode> executeOperationWithResponse(McpClientMessage request) {
        return delegate.executeOperationWithResponse(request).whenComplete((result, error) -> {
            if (result != null) {
                lastRawResponse.set(result.toString());
            }
        });
    }

    @Override
    public CompletableFuture<JsonNode> executeOperationWithResponse(McpCallContext context) {
        return delegate.executeOperationWithResponse(context).whenComplete((result, error) -> {
            if (result != null) {
                lastRawResponse.set(result.toString());
            }
        });
    }

    // ==================== 透明代理方法 ====================

    @Override
    public void start(McpOperationHandler messageHandler) {
        delegate.start(messageHandler);
    }

    @Override
    public CompletableFuture<JsonNode> initialize(McpInitializeRequest request) {
        return delegate.initialize(request);
    }

    @Override
    public void executeOperationWithoutResponse(McpClientMessage request) {
        delegate.executeOperationWithoutResponse(request);
    }

    @Override
    public void executeOperationWithoutResponse(McpCallContext context) {
        delegate.executeOperationWithoutResponse(context);
    }

    @Override
    public void checkHealth() {
        delegate.checkHealth();
    }

    @Override
    public void onFailure(Runnable actionOnFailure) {
        delegate.onFailure(actionOnFailure);
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
