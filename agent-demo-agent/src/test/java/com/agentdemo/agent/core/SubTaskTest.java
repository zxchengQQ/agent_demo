package com.agentdemo.agent.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * SubTask 测试
 * <p>
 * 验证标准来源：Task-01 验证标准
 * 关联 AC：AC-016（子任务数据结构完整性）
 * </p>
 */
class SubTaskTest {

    /**
     * 验证 SubTask 可通过构造器创建，index() 和 title() 返回正确值
     */
    @Test
    void shouldCreateSubTaskWithIndexAndTitle() {
        SubTask task = new SubTask(1, "分析需求");

        assertEquals(1, task.index(), "index 应返回 1");
        assertEquals("分析需求", task.title(), "title 应返回 '分析需求'");
    }

    /**
     * 验证 SubTask 可用 0 和空字符串创建（record 无校验，空值允许）
     */
    @Test
    void shouldCreateSubTaskWithZeroIndexAndEmptyTitle() {
        SubTask task = new SubTask(0, "");

        assertEquals(0, task.index(), "index 应返回 0");
        assertEquals("", task.title(), "title 应返回空字符串");
    }

    /**
     * 验证 SubTask 可用 null title 创建（record 无校验）
     */
    @Test
    void shouldCreateSubTaskWithNullTitle() {
        SubTask task = new SubTask(2, null);

        assertEquals(2, task.index());
        assertNotNull(task, "SubTask 实例不应为 null");
    }

    /**
     * 验证 SubTask 的 equals/hashCode（record 自动生成）
     */
    @Test
    void subTasksWithSameValuesShouldBeEqual() {
        SubTask task1 = new SubTask(1, "分析需求");
        SubTask task2 = new SubTask(1, "分析需求");

        assertEquals(task1, task2, "相同值的 SubTask 应相等");
        assertEquals(task1.hashCode(), task2.hashCode(), "相同值的 SubTask hashCode 应相等");
    }

    /**
     * 验证 SubTask 的 toString 包含 index 和 title
     */
    @Test
    void toStringShouldContainIndexAndTitle() {
        SubTask task = new SubTask(3, "调研方案");

        String str = task.toString();
        assertNotNull(str, "toString 不应为 null");
        assertTrue(str.contains("3"), "toString 应包含 index");
        assertTrue(str.contains("调研方案"), "toString 应包含 title");
    }

    private static void assertTrue(boolean condition, String message) {
        org.junit.jupiter.api.Assertions.assertTrue(condition, message);
    }
}
