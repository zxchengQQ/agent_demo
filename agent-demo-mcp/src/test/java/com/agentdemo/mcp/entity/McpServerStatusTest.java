package com.agentdemo.mcp.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * McpServerStatus 枚举测试
 * <p>
 * 验证来源：Task-05 验证标准
 * 关联 AC：AC-034
 * </p>
 */
@DisplayName("McpServerStatus 枚举测试")
class McpServerStatusTest {

    @Test
    @DisplayName("McpServerStatus 包含恰好 4 个枚举值：CONNECTED, DISCONNECTED, ERROR, DISABLED")
    void mcpServerStatusShouldContainFourValues() {
        assertEquals(4, McpServerStatus.values().length,
                "McpServerStatus 应有 4 个枚举值");
    }

    @Test
    @DisplayName("McpServerStatus.valueOf(\"CONNECTED\") 返回对应枚举")
    void valueOfConnectedShouldReturnEnum() {
        assertEquals(McpServerStatus.CONNECTED, McpServerStatus.valueOf("CONNECTED"));
    }

    @Test
    @DisplayName("McpServerStatus.valueOf(\"DISCONNECTED\") 返回对应枚举")
    void valueOfDisconnectedShouldReturnEnum() {
        assertEquals(McpServerStatus.DISCONNECTED, McpServerStatus.valueOf("DISCONNECTED"));
    }

    @Test
    @DisplayName("McpServerStatus.valueOf(\"ERROR\") 返回对应枚举")
    void valueOfErrorShouldReturnEnum() {
        assertEquals(McpServerStatus.ERROR, McpServerStatus.valueOf("ERROR"));
    }

    @Test
    @DisplayName("McpServerStatus.valueOf(\"DISABLED\") 返回对应枚举")
    void valueOfDisabledShouldReturnEnum() {
        assertEquals(McpServerStatus.DISABLED, McpServerStatus.valueOf("DISABLED"));
    }

    @Test
    @DisplayName("McpServerStatus.valueOf(\"INVALID\") 抛出 IllegalArgumentException")
    void valueOfInvalidShouldThrowException() {
        assertThrows(IllegalArgumentException.class, () -> McpServerStatus.valueOf("INVALID"));
    }
}
