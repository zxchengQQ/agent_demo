package com.agentdemo.agent.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * PendingInteraction 扩展单元测试
 * <p>
 * 验证标准来源：tool-permission-control 任务规划 Task-09 验证标准
 * 关联 AC：AC-N03（暂停状态载体）
 * 业务含义：权限确认（tool_confirm）暂停模式与三字段（pendingToolCallId/pendingToolName/pendingToolArguments）
 * 的保存与读取，为 ask 级工具拦截与恢复执行提供状态载体。
 * </p>
 */
class PendingInteractionTest {

    @Test
    @DisplayName("MODE_TOOL_CONFIRM 常量值为 tool_confirm 且与现有 mode 值不冲突")
    void toolConfirmMode_distinctFromExistingModes() {
        assertEquals("tool_confirm", PendingInteraction.MODE_TOOL_CONFIRM);
        assertNotEquals(PendingInteraction.MODE_DIRECT, PendingInteraction.MODE_TOOL_CONFIRM,
                "tool_confirm 不得与 direct 冲突");
        assertNotEquals(PendingInteraction.MODE_BREAKDOWN, PendingInteraction.MODE_TOOL_CONFIRM,
                "tool_confirm 不得与 breakdown 冲突");
    }

    @Test
    @DisplayName("三字段可保存与读取，参数 JSON 原样保留（批准后可直接执行）")
    void toolConfirmFields_roundTripPreservesArguments() {
        PendingInteraction pending = new PendingInteraction();
        String arguments = "{\"url\":\"https://example.com\",\"timeout\":5}";

        pending.setPendingToolCallId("call_123");
        pending.setPendingToolName("httpGet");
        pending.setPendingToolArguments(arguments);

        assertEquals("call_123", pending.getPendingToolCallId());
        assertEquals("httpGet", pending.getPendingToolName());
        // 业务含义：参数 JSON 必须原样保存，恢复时直接交给 ToolExecutor.execute 执行，不重复解析
        assertEquals(arguments, pending.getPendingToolArguments());
    }

    @Test
    @DisplayName("默认 mode 为 direct，三字段默认为 null（不影响现有 askUser 场景）")
    void newInstance_defaultsUnchanged() {
        PendingInteraction pending = new PendingInteraction();

        assertEquals(PendingInteraction.MODE_DIRECT, pending.getMode(), "默认 mode 仍为 direct");
        assertNull(pending.getPendingToolCallId());
        assertNull(pending.getPendingToolName());
        assertNull(pending.getPendingToolArguments());
    }
}
