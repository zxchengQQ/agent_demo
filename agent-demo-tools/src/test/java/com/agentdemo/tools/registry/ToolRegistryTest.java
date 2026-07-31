package com.agentdemo.tools.registry;

import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ToolRegistry 单元测试
 * <p>
 * 业务含义：验证工具的动态注册、注销、列表查询能力，
 * 为 CR-003 知识库动态 Tool 注册提供注册表基础能力保障。
 * </p>
 */
class ToolRegistryTest {

    private ToolRegistry toolRegistry;

    @BeforeEach
    void setUp() {
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        when(applicationContext.getBeansWithAnnotation(org.springframework.stereotype.Component.class))
                .thenReturn(java.util.Collections.emptyMap());
        toolRegistry = new ToolRegistry(applicationContext);
    }

    @Test
    @DisplayName("动态注册带 @Tool 注解的工具后可在列表中查到")
    void registerShouldAddToolToList() {
        // given
        TestTool tool = new TestTool();

        // when
        toolRegistry.register(tool);

        // then
        List<Object> tools = toolRegistry.listTools();
        assertTrue(tools.contains(tool), "注册后的工具应在列表中");
        assertEquals(1, toolRegistry.size(), "工具数量应为 1");
    }

    @Test
    @DisplayName("动态注册 null 不抛异常且不影响列表")
    void registerNullShouldDoNothing() {
        // when
        toolRegistry.register(null);

        // then
        assertEquals(0, toolRegistry.size(), "注册 null 后工具数量仍为 0");
    }

    @Test
    @DisplayName("未注册任何工具时列表为空")
    void listToolsShouldReturnEmptyListWhenNoTools() {
        List<Object> tools = toolRegistry.listTools();

        assertNotNull(tools);
        assertTrue(tools.isEmpty(), "未注册工具时列表应为空");
    }

    @Test
    @DisplayName("按工具方法名注销工具")
    void unregisterToolShouldRemoveToolByMethodName() {
        // given
        TestTool tool = new TestTool();
        toolRegistry.register(tool);
        assertEquals(1, toolRegistry.size());

        // when
        toolRegistry.unregisterTool("testMethod");

        // then
        assertEquals(0, toolRegistry.size(), "注销后工具数量应为 0");
        assertFalse(toolRegistry.listTools().contains(tool), "注销后的工具不应在列表中");
    }

    @Test
    @DisplayName("注销不存在的工具名不抛异常")
    void unregisterNonExistentToolShouldDoNothing() {
        // given
        TestTool tool = new TestTool();
        toolRegistry.register(tool);

        // when
        toolRegistry.unregisterTool("notExisted");

        // then
        assertEquals(1, toolRegistry.size(), "注销不存在的工具名不应影响已有工具");
    }

    @Test
    @DisplayName("注册多个同名工具时注销会移除所有同名工具")
    void unregisterToolShouldRemoveAllToolsWithSameMethodName() {
        // given
        toolRegistry.register(new TestTool());
        toolRegistry.register(new TestTool());
        assertEquals(2, toolRegistry.size());

        // when
        toolRegistry.unregisterTool("testMethod");

        // then
        assertEquals(0, toolRegistry.size());
    }

    @Test
    @DisplayName("按类简单名获取工具")
    void getToolShouldReturnToolByClassSimpleName() {
        // given
        TestTool tool = new TestTool();
        toolRegistry.register(tool);

        // when
        Object result = toolRegistry.getTool(tool.getClass().getSimpleName());

        // then
        assertNotNull(result);
        assertEquals(tool, result);
    }

    @Test
    @DisplayName("获取不存在的工具返回 null")
    void getToolForNonExistentShouldReturnNull() {
        assertNull(toolRegistry.getTool("NotExisted"));
    }

    /**
     * 测试用工具类
     */
    public static class TestTool {
        @Tool("测试工具")
        public String testMethod(String arg) {
            return "result: " + arg;
        }
    }
}
