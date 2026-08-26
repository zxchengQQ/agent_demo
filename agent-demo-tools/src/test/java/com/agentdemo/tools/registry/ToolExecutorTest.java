package com.agentdemo.tools.registry;

import com.agentdemo.tools.builtin.CalculatorTool;
import com.agentdemo.tools.builtin.TimeTool;
import com.agentdemo.tools.permission.ToolPermissionLevel;
import com.agentdemo.tools.permission.ToolPermissionService;
import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ToolExecutor 单元测试
 * <p>
 * 业务含义：验证 ReAct 循环中工具调用的执行逻辑，包括正常执行、工具不存在、异常处理、参数缺失等场景；
 * 以及执行期权限兜底（deny 拦截零调用、checkPermission 三态查询、降级开关）。
 * </p>
 */
class ToolExecutorTest {

    private ToolRegistry toolRegistry;
    private ToolPermissionService permissionService;
    private ToolExecutor toolExecutor;

    @BeforeEach
    void setUp() {
        toolRegistry = mock(ToolRegistry.class);
        permissionService = mock(ToolPermissionService.class);
        toolExecutor = new ToolExecutor(toolRegistry, permissionService);
    }

    @Test
    void execute_calculator_returnsResultContainingExpressionAndValue() {
        when(toolRegistry.listTools()).thenReturn(List.of(new CalculatorTool()));

        String result = toolExecutor.execute("calculate", "{\"expression\":\"2+3\"}");

        assertNotNull(result);
        assertTrue(result.contains("2+3"), "结果应包含原始表达式 2+3");
        assertTrue(result.contains("5"), "结果应包含计算结果 5");
    }

    @Test
    void execute_getCurrentTime_returnsNonEmptyTimeString() {
        when(toolRegistry.listTools()).thenReturn(List.of(new TimeTool()));

        String result = toolExecutor.execute("getCurrentTime", "{}");

        assertNotNull(result);
        assertFalse(result.isEmpty(), "时间工具返回不应为空");
        // 时间格式 yyyy-MM-dd HH:mm:ss 包含 "-" 和 ":"
        assertTrue(result.contains("-"), "时间字符串应包含日期分隔符 -");
    }

    @Test
    void execute_nonExistentTool_returnsNotFoundMessage() {
        when(toolRegistry.listTools()).thenReturn(List.of(new CalculatorTool()));

        String result = toolExecutor.execute("nonExistentTool", "{}");

        assertTrue(result.startsWith("工具不存在: nonExistentTool"), "应返回工具不存在错误");
        assertTrue(result.contains("可用工具"), "应包含可用工具列表");
        assertTrue(result.contains("calculate"), "可用工具列表应包含已注册工具名");
    }

    @Test
    void execute_toolThrowsException_returnsFailureMessageWithoutThrowing() {
        when(toolRegistry.listTools()).thenReturn(List.of(new FailingTool()));

        // 工具方法抛出异常时，execute 不应抛出异常，而是返回错误信息
        String result = assertDoesNotThrow(() ->
                toolExecutor.execute("fail", "{}")
        );

        assertTrue(result.startsWith("工具执行失败:"), "应返回以 '工具执行失败:' 开头的错误信息");
    }

    @Test
    void execute_missingParam_doesNotThrowAndReturnsFailureMessage() {
        when(toolRegistry.listTools()).thenReturn(List.of(new CalculatorTool()));

        // 参数 JSON 缺少 expression 字段，expression 注入默认值 null
        // CalculatorTool.calculate(null) 内部会抛异常，但 ToolExecutor 不应抛异常
        String result = assertDoesNotThrow(() ->
                toolExecutor.execute("calculate", "{}")
        );

        assertNotNull(result);
        // 参数缺失导致工具内部失败，应返回失败信息而非抛异常
        assertTrue(result.startsWith("工具执行失败:"), "参数缺失时应返回失败信息，不抛异常");
    }

    @Test
    void execute_missingIntParam_usesDefaultValue() {
        when(toolRegistry.listTools()).thenReturn(List.of(new DefaultParamTool()));

        // 参数 JSON 缺少 count 字段，int 参数注入默认值 0
        String result = assertDoesNotThrow(() ->
                toolExecutor.execute("echoCount", "{}")
        );

        assertNotNull(result);
        // count 默认值 0，方法正常返回 "count: 0"
        assertTrue(result.contains("0"), "int 参数缺失时应使用默认值 0");
    }

    // ==================== 执行期权限兜底（Task-08） ====================

    @Test
    void denyTool_execute_returnsDenyMessageWithZeroInvocation() {
        // given：deny 工具已注册，getToolMeta 映射 toolId，权限服务裁决 DENY
        CountingTool countingTool = new CountingTool();
        when(toolRegistry.listTools()).thenReturn(List.of(countingTool));
        when(toolRegistry.getToolMeta("deniedAction"))
                .thenReturn(Optional.of(new ToolRegistry.ToolMeta("builtin:deniedAction", "被禁止的动作")));
        when(permissionService.getPermission("builtin:deniedAction")).thenReturn(ToolPermissionLevel.DENY);

        // when
        String result = assertDoesNotThrow(() ->
                toolExecutor.execute("deniedAction", "{}")
        );

        // then：返回拒绝文案且方法体零触发
        assertTrue(result.contains("禁止调用"), "应返回权限拒绝文案");
        assertEquals(0, countingTool.invocationCount, "deny 工具方法体不应被调用（零触发）");
    }

    @Test
    void denyTool_denyMessage_hidesPermissionConfigDetails() {
        // given
        CountingTool countingTool = new CountingTool();
        when(toolRegistry.listTools()).thenReturn(List.of(countingTool));
        when(toolRegistry.getToolMeta("deniedAction"))
                .thenReturn(Optional.of(new ToolRegistry.ToolMeta("builtin:deniedAction", "被禁止的动作")));
        when(permissionService.getPermission("builtin:deniedAction")).thenReturn(ToolPermissionLevel.DENY);

        // when
        String result = toolExecutor.execute("deniedAction", "{}");

        // then：信息边界——拒绝文案不得暴露 toolId 与权限等级细节
        assertFalse(result.contains("builtin:deniedAction"), "拒绝文案不应暴露 toolId");
        assertFalse(result.toLowerCase().contains("deny"), "拒绝文案不应暴露权限等级字符串");
        assertFalse(result.contains("被禁止的动作"), "拒绝文案不应携带工具描述");
    }

    @Test
    void checkPermission_threeLevels_returnsCorrectLevelAndToolId() {
        when(toolRegistry.getToolMeta("allowAction"))
                .thenReturn(Optional.of(new ToolRegistry.ToolMeta("builtin:allowAction", "放行动作")));
        when(toolRegistry.getToolMeta("askAction"))
                .thenReturn(Optional.of(new ToolRegistry.ToolMeta("builtin:askAction", "需确认动作")));
        when(toolRegistry.getToolMeta("denyAction"))
                .thenReturn(Optional.of(new ToolRegistry.ToolMeta("builtin:denyAction", "禁止动作")));
        when(permissionService.getPermission("builtin:allowAction")).thenReturn(ToolPermissionLevel.ALLOW);
        when(permissionService.getPermission("builtin:askAction")).thenReturn(ToolPermissionLevel.ASK);
        when(permissionService.getPermission("builtin:denyAction")).thenReturn(ToolPermissionLevel.DENY);

        ToolExecutor.ToolPermissionCheck allow = toolExecutor.checkPermission("allowAction");
        ToolExecutor.ToolPermissionCheck ask = toolExecutor.checkPermission("askAction");
        ToolExecutor.ToolPermissionCheck deny = toolExecutor.checkPermission("denyAction");

        assertEquals(ToolPermissionLevel.ALLOW, allow.level());
        assertEquals("builtin:allowAction", allow.toolId());
        assertEquals("放行动作", allow.toolDescription());

        assertEquals(ToolPermissionLevel.ASK, ask.level());
        assertEquals("builtin:askAction", ask.toolId());

        assertEquals(ToolPermissionLevel.DENY, deny.level());
        assertEquals("builtin:denyAction", deny.toolId());
    }

    @Test
    void checkPermission_unknownTool_returnsAskWithoutThrowing() {
        when(toolRegistry.getToolMeta("unknownTool")).thenReturn(Optional.empty());

        ToolExecutor.ToolPermissionCheck check = assertDoesNotThrow(() ->
                toolExecutor.checkPermission("unknownTool")
        );

        assertEquals(ToolPermissionLevel.ASK, check.level(), "未知工具应保守返回 ASK");
        assertNull(check.toolId());
    }

    @Test
    void denyTool_execute_whenPermissionDisabled_bypassesAndExecutes() {
        // given：权限功能关闭（enabled=false）时 getPermission 返回 ALLOW，等同现状行为
        CountingTool countingTool = new CountingTool();
        when(toolRegistry.listTools()).thenReturn(List.of(countingTool));
        when(toolRegistry.getToolMeta("deniedAction"))
                .thenReturn(Optional.of(new ToolRegistry.ToolMeta("builtin:deniedAction", "被禁止的动作")));
        when(permissionService.getPermission("builtin:deniedAction")).thenReturn(ToolPermissionLevel.ALLOW);

        // when
        String result = toolExecutor.execute("deniedAction", "{}");

        // then：降级开关生效，工具正常执行
        assertEquals(1, countingTool.invocationCount, "权限关闭时 deny 工具应正常执行");
        assertTrue(result.contains("executed"), "应返回工具执行结果");
    }

    @Test
    void allowTool_execute_behaviorUnchanged() {
        // given：allow 工具（与现状一致，getPermission 返回 ALLOW）
        when(toolRegistry.listTools()).thenReturn(List.of(new CalculatorTool()));
        when(toolRegistry.getToolMeta("calculate"))
                .thenReturn(Optional.of(new ToolRegistry.ToolMeta("builtin:calculator", "计算器")));
        when(permissionService.getPermission("builtin:calculator")).thenReturn(ToolPermissionLevel.ALLOW);

        String result = toolExecutor.execute("calculate", "{\"expression\":\"2+3\"}");

        assertNotNull(result);
        assertTrue(result.contains("2+3"), "allow 工具行为与现状完全一致");
        assertTrue(result.contains("5"), "allow 工具应正常返回计算结果");
    }

    /**
     * 测试用工具类：记录方法体调用次数，用于验证 deny 拦截零触发
     */
    public static class CountingTool {
        int invocationCount = 0;

        @Tool("被禁止的动作")
        public String deniedAction() {
            invocationCount++;
            return "executed";
        }
    }

    /**
     * 测试用工具类：方法总是抛出异常
     */
    public static class FailingTool {
        @Tool("总是失败的工具")
        public String fail() {
            throw new RuntimeException("故意失败");
        }
    }

    /**
     * 测试用工具类：用于验证参数缺失时基本类型默认值注入
     */
    public static class DefaultParamTool {
        @Tool("回显 count 参数")
        public String echoCount(int count) {
            return "count: " + count;
        }
    }
}
