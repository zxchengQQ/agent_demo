package com.agentdemo.tools.registry;

import com.agentdemo.tools.builtin.CalculatorTool;
import com.agentdemo.tools.builtin.TimeTool;
import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ToolExecutor 单元测试
 * <p>
 * 业务含义：验证 ReAct 循环中工具调用的执行逻辑，包括正常执行、工具不存在、异常处理、参数缺失等场景。
 * </p>
 */
class ToolExecutorTest {

    private ToolRegistry toolRegistry;
    private ToolExecutor toolExecutor;

    @BeforeEach
    void setUp() {
        toolRegistry = mock(ToolRegistry.class);
        toolExecutor = new ToolExecutor(toolRegistry);
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
