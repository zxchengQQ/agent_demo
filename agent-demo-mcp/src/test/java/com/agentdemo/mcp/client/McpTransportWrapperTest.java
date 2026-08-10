package com.agentdemo.mcp.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.mcp.client.McpCallContext;
import dev.langchain4j.mcp.client.transport.McpOperationHandler;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.protocol.McpClientMessage;
import dev.langchain4j.mcp.protocol.McpInitializeRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * McpTransportWrapper 包装器测试
 * <p>
 * 验证来源：Task-18 验证标准
 * 关联 AC：AC-040（Transport 包装器透明代理 + 响应缓存）
 * </p>
 */
@DisplayName("McpTransportWrapper 包装器测试")
@ExtendWith(MockitoExtension.class)
class McpTransportWrapperTest {

    @Mock
    private McpTransport delegate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("构造 McpTransportWrapper 包装 delegate 成功")
    void constructorShouldWrapDelegate() {
        McpTransportWrapper wrapper = new McpTransportWrapper(delegate);
        assertEquals(delegate, wrapper.getDelegate());
    }

    @Test
    @DisplayName("start() 委托给 delegate 执行")
    void startShouldDelegate() {
        McpTransportWrapper wrapper = new McpTransportWrapper(delegate);
        McpOperationHandler handler = mock(McpOperationHandler.class);

        wrapper.start(handler);

        verify(delegate).start(handler);
    }

    @Test
    @DisplayName("close() 委托给 delegate 执行")
    void closeShouldDelegate() throws Exception {
        McpTransportWrapper wrapper = new McpTransportWrapper(delegate);

        wrapper.close();

        verify(delegate).close();
    }

    @Test
    @DisplayName("executeOperationWithResponse 后 getLastRawResponse 返回缓存的原始 JSON-RPC 响应")
    void shouldCacheResponseAfterExecuteOperation() throws Exception {
        McpTransportWrapper wrapper = new McpTransportWrapper(delegate);
        String rawJson = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}}";
        JsonNode mockResponse = objectMapper.readTree(rawJson);
        when(delegate.executeOperationWithResponse(any(McpClientMessage.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        McpClientMessage request = mock(McpClientMessage.class);
        wrapper.executeOperationWithResponse(request).get();

        assertNotNull(wrapper.getLastRawResponse());
        assertTrue(wrapper.getLastRawResponse().contains("hello"));
    }

    @Test
    @DisplayName("executeOperationWithResponse(McpCallContext) 后 getLastRawResponse 返回缓存的响应")
    void shouldCacheResponseAfterExecuteOperationWithContext() throws Exception {
        McpTransportWrapper wrapper = new McpTransportWrapper(delegate);
        String rawJson = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[{\"type\":\"image\",\"data\":\"abc\"}]}}";
        JsonNode mockResponse = objectMapper.readTree(rawJson);
        when(delegate.executeOperationWithResponse(any(McpCallContext.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        McpCallContext context = mock(McpCallContext.class);
        wrapper.executeOperationWithResponse(context).get();

        assertNotNull(wrapper.getLastRawResponse());
        assertTrue(wrapper.getLastRawResponse().contains("image"));
    }

    @Test
    @DisplayName("clearCachedResponse 后 getLastRawResponse 返回 null")
    void clearCachedResponseShouldClearCache() throws Exception {
        McpTransportWrapper wrapper = new McpTransportWrapper(delegate);
        JsonNode mockResponse = objectMapper.readTree("{\"result\":\"cached\"}");
        when(delegate.executeOperationWithResponse(any(McpClientMessage.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));
        wrapper.executeOperationWithResponse(mock(McpClientMessage.class)).get();
        assertNotNull(wrapper.getLastRawResponse());

        wrapper.clearCachedResponse();

        assertNull(wrapper.getLastRawResponse());
    }

    @Test
    @DisplayName("跨线程缓存可见（AtomicReference）：异步 I/O 线程缓存，调用线程可读取")
    void cacheShouldBeVisibleAcrossThreads() throws Exception {
        // 模拟真实场景：CompletableFuture 在异步线程上完成（如 HTTP 传输的 I/O 线程），
        // 但 getLastRawResponse() 在另一个线程（如 servlet 线程）上调用
        McpTransportWrapper wrapper = new McpTransportWrapper(delegate);
        String expectedJson = "{\"result\":\"async-io-thread-data\"}";
        JsonNode mockResponse = objectMapper.readTree(expectedJson);

        // 模拟 delegate 在异步线程上完成 Future
        when(delegate.executeOperationWithResponse(any(McpClientMessage.class)))
                .thenReturn(CompletableFuture.supplyAsync(() -> mockResponse));

        // 在主线程上调用（Future 在异步线程上完成）
        wrapper.executeOperationWithResponse(mock(McpClientMessage.class)).get();

        // 在主线程上读取缓存 —— 应该能看到异步线程设置的值
        String cached = wrapper.getLastRawResponse();
        assertNotNull(cached, "跨线程缓存应可见（AtomicReference 保证内存可见性）");
        assertTrue(cached.contains("async-io-thread-data"),
                "主线程应能读取异步 I/O 线程缓存的响应");
    }

    @Test
    @DisplayName("delegate 抛异常时异常原样传播，不被 Wrapper 吞掉")
    void exceptionShouldPropagate() {
        McpTransportWrapper wrapper = new McpTransportWrapper(delegate);
        RuntimeException expected = new RuntimeException("connection error");
        when(delegate.executeOperationWithResponse(any(McpClientMessage.class)))
                .thenThrow(expected);

        RuntimeException actual = assertThrows(RuntimeException.class,
                () -> wrapper.executeOperationWithResponse(mock(McpClientMessage.class)));

        assertSame(expected, actual);
    }

    @Test
    @DisplayName("initialize() 委托给 delegate 执行")
    void initializeShouldDelegate() {
        McpTransportWrapper wrapper = new McpTransportWrapper(delegate);
        McpInitializeRequest request = mock(McpInitializeRequest.class);
        CompletableFuture<JsonNode> mockFuture = CompletableFuture.completedFuture(null);
        when(delegate.initialize(request)).thenReturn(mockFuture);

        wrapper.initialize(request);

        verify(delegate).initialize(request);
    }

    @Test
    @DisplayName("executeOperationWithoutResponse 委托给 delegate 执行")
    void executeWithoutResponseShouldDelegate() {
        McpTransportWrapper wrapper = new McpTransportWrapper(delegate);
        McpClientMessage request = mock(McpClientMessage.class);

        wrapper.executeOperationWithoutResponse(request);

        verify(delegate).executeOperationWithoutResponse(request);
    }

    @Test
    @DisplayName("checkHealth 委托给 delegate 执行")
    void checkHealthShouldDelegate() {
        McpTransportWrapper wrapper = new McpTransportWrapper(delegate);

        wrapper.checkHealth();

        verify(delegate).checkHealth();
    }

    @Test
    @DisplayName("onFailure 委托给 delegate 执行")
    void onFailureShouldDelegate() {
        McpTransportWrapper wrapper = new McpTransportWrapper(delegate);
        Runnable action = () -> {};

        wrapper.onFailure(action);

        verify(delegate).onFailure(action);
    }
}
