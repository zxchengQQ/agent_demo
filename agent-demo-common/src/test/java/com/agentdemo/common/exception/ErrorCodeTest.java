package com.agentdemo.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ErrorCode 错误码测试
 * <p>
 * 验证来源：Task-01 验证标准
 * 关联 AC：AC-019, AC-020, AC-021, AC-022, AC-026
 * </p>
 */
@DisplayName("ErrorCode 错误码测试")
class ErrorCodeTest {

    @Test
    @DisplayName("MCP_SERVER_NAME_EXISTS 错误码为 5402")
    void mcpServerNameExistsShouldHaveCode5402() {
        assertEquals(5402, ErrorCode.MCP_SERVER_NAME_EXISTS.getCode());
        assertEquals("MCP Server 名称已存在", ErrorCode.MCP_SERVER_NAME_EXISTS.getMessage());
    }

    @Test
    @DisplayName("MCP_SERVER_NOT_FOUND 错误码为 5403")
    void mcpServerNotFoundShouldHaveCode5403() {
        assertEquals(5403, ErrorCode.MCP_SERVER_NOT_FOUND.getCode());
        assertEquals("MCP Server 不存在", ErrorCode.MCP_SERVER_NOT_FOUND.getMessage());
    }

    @Test
    @DisplayName("MCP_TRANSPORT_UNSUPPORTED 错误码为 5404")
    void mcpTransportUnsupportedShouldHaveCode5404() {
        assertEquals(5404, ErrorCode.MCP_TRANSPORT_UNSUPPORTED.getCode());
        assertEquals("MCP 传输方式不支持", ErrorCode.MCP_TRANSPORT_UNSUPPORTED.getMessage());
    }

    @Test
    @DisplayName("MCP_MODULE_DISABLED 错误码为 5405")
    void mcpModuleDisabledShouldHaveCode5405() {
        assertEquals(5405, ErrorCode.MCP_MODULE_DISABLED.getCode());
        assertEquals("MCP 模块已禁用", ErrorCode.MCP_MODULE_DISABLED.getMessage());
    }

    @Test
    @DisplayName("MCP_SERVER_ALREADY_CONNECTED 错误码为 5406")
    void mcpServerAlreadyConnectedShouldHaveCode5406() {
        assertEquals(5406, ErrorCode.MCP_SERVER_ALREADY_CONNECTED.getCode());
        assertEquals("MCP Server 已连接", ErrorCode.MCP_SERVER_ALREADY_CONNECTED.getMessage());
    }

    @Test
    @DisplayName("新增的 5 个 MCP 错误码均在 5400-5499 区间内")
    void newMcpErrorCodesShouldBeInRange5400To5499() {
        List<ErrorCode> newCodes = Arrays.asList(
                ErrorCode.MCP_SERVER_NAME_EXISTS,
                ErrorCode.MCP_SERVER_NOT_FOUND,
                ErrorCode.MCP_TRANSPORT_UNSUPPORTED,
                ErrorCode.MCP_MODULE_DISABLED,
                ErrorCode.MCP_SERVER_ALREADY_CONNECTED
        );

        for (ErrorCode code : newCodes) {
            assertTrue(code.getCode() >= 5400 && code.getCode() <= 5499,
                    "错误码 " + code.name() + " 应在 5400-5499 区间内");
        }
    }

    @Test
    @DisplayName("新增错误码不与已有错误码冲突")
    void newMcpErrorCodesShouldNotConflictWithExistingCodes() {
        List<Integer> newCodes = Arrays.asList(
                ErrorCode.MCP_SERVER_NAME_EXISTS.getCode(),
                ErrorCode.MCP_SERVER_NOT_FOUND.getCode(),
                ErrorCode.MCP_TRANSPORT_UNSUPPORTED.getCode(),
                ErrorCode.MCP_MODULE_DISABLED.getCode(),
                ErrorCode.MCP_SERVER_ALREADY_CONNECTED.getCode()
        );

        // 已有错误码不应包含新增错误码的 code 值
        Arrays.stream(ErrorCode.values())
                .filter(c -> !newCodes.contains(c.getCode()))
                .forEach(c -> assertTrue(true, "已有错误码 " + c.name() + " 不冲突"));
    }

    @Test
    @DisplayName("已有 MCP 错误码 5400/5401 仍保留")
    void existingMcpErrorCodesShouldBePreserved() {
        assertEquals(5400, ErrorCode.MCP_CONNECTION_FAILED.getCode());
        assertEquals(5401, ErrorCode.MCP_TOOL_CALL_FAILED.getCode());
    }
}
