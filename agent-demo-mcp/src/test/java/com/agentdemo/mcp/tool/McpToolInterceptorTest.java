package com.agentdemo.mcp.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * McpToolInterceptor 拦截器测试
 * <p>
 * 验证来源：Task-11 验证标准
 * 关联 AC：AC-012
 * </p>
 */
@DisplayName("McpToolInterceptor 拦截器测试")
@ExtendWith(MockitoExtension.class)
class McpToolInterceptorTest {

    @Mock
    private McpToolExecutor toolExecutor;

    private McpToolInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new McpToolInterceptor("weather", "getForecast", toolExecutor);
    }

    @Test
    @DisplayName("构造 McpToolInterceptor 成功持有 serverName/toolName/toolExecutor")
    void constructorShouldHoldFields() {
        // 通过行为验证字段已持有
        when(toolExecutor.execute(eq("weather"), eq("getForecast"), eq("{\"city\":\"北京\"}")))
                .thenReturn("晴天 25度");

        String result = interceptor.execute(new Object[]{"{\"city\":\"北京\"}"});

        assertEquals("晴天 25度", result);
    }

    @Test
    @DisplayName("execute 调用 toolExecutor.execute(serverName, toolName, argsJson) 并返回其结果")
    void executeShouldDelegateToToolExecutor() {
        when(toolExecutor.execute("weather", "getForecast", "{\"city\":\"上海\"}"))
                .thenReturn("多云 28度");

        String result = interceptor.execute(new Object[]{"{\"city\":\"上海\"}"});

        assertEquals("多云 28度", result);
    }

    @Test
    @DisplayName("execute 当参数为 null 时不抛 NPE，转给 toolExecutor 处理")
    void executeShouldAcceptNullArgs() {
        when(toolExecutor.execute("weather", "getForecast", null))
                .thenReturn("结果");

        String result = interceptor.execute(new Object[]{null});

        assertEquals("结果", result);
    }

    @Test
    @DisplayName("execute 当参数数组为空时抛 IllegalStateException（不应抛 ArrayIndexOutOfBoundsException）")
    void executeShouldThrowIllegalStateWhenArgsEmpty() {
        assertThrows(IllegalStateException.class,
                () -> interceptor.execute(new Object[]{}));
    }

    @Test
    @DisplayName("execute 当参数数组为 null 时抛 IllegalStateException（不应抛 NPE）")
    void executeShouldThrowIllegalStateWhenArgsNull() {
        assertThrows(IllegalStateException.class,
                () -> interceptor.execute(null));
    }

    @Test
    @DisplayName("不同 Server 的拦截器持有独立的 serverName/toolName")
    void differentInterceptorsShouldHoldIndependentFields() {
        McpToolInterceptor githubInterceptor = new McpToolInterceptor("github", "search", toolExecutor);
        when(toolExecutor.execute("github", "search", "{}")).thenReturn("github 结果");

        String result = githubInterceptor.execute(new Object[]{"{}"});

        assertEquals("github 结果", result);
    }
}
