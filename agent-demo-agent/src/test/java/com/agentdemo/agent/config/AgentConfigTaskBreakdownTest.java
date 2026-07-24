package com.agentdemo.agent.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentConfig 任务拆解配置测试
 * <p>
 * 验证标准来源：Task-01 验证标准
 * 关联 AC：AC-013（子任务数量上限规则）、AC-016（子任务数据结构完整性）
 * </p>
 */
class AgentConfigTaskBreakdownTest {

    /**
     * 验证 taskBreakdownPlanPrompt 默认值非空且包含 JSON 关键词
     * 业务含义：规划提示词需指导 LLM 以 JSON 格式返回子任务列表
     */
    @Test
    void taskBreakdownPlanPromptShouldContainJsonKeyword() {
        AgentConfig config = new AgentConfig();
        String prompt = config.getTaskBreakdownPlanPrompt();

        assertNotNull(prompt, "taskBreakdownPlanPrompt 不应为 null");
        assertTrue(prompt.contains("JSON"), "提示词应包含 JSON 关键词");
    }

    /**
     * 验证 taskExecutionSystemPrompt 默认值非空且包含"工具"关键词
     * 业务含义：执行提示词需告知 LLM 可以使用工具
     */
    @Test
    void taskExecutionSystemPromptShouldContainToolKeyword() {
        AgentConfig config = new AgentConfig();
        String prompt = config.getTaskExecutionSystemPrompt();

        assertNotNull(prompt, "taskExecutionSystemPrompt 不应为 null");
        assertTrue(prompt.contains("工具"), "提示词应包含 工具 关键词");
    }

    /**
     * 验证 taskSummaryPrompt 默认值非空且包含"总结"关键词
     * 业务含义：总结提示词需指导 LLM 生成总结
     */
    @Test
    void taskSummaryPromptShouldContainSummaryKeyword() {
        AgentConfig config = new AgentConfig();
        String prompt = config.getTaskSummaryPrompt();

        assertNotNull(prompt, "taskSummaryPrompt 不应为 null");
        assertTrue(prompt.contains("总结"), "提示词应包含 总结 关键词");
    }

    /**
     * 验证 taskExecutionMaxIterations 默认值为 8
     * 业务含义：子任务执行最大 ReAct 迭代次数默认 8 次
     */
    @Test
    void taskExecutionMaxIterationsShouldDefaultTo8() {
        AgentConfig config = new AgentConfig();

        assertEquals(8, config.getTaskExecutionMaxIterations(),
                "taskExecutionMaxIterations 默认值应为 8");
    }

    /**
     * 验证 taskBreakdownMaxSubtasks 默认值为 10
     * 业务含义：子任务数量上限默认 10 个（AC-013）
     */
    @Test
    void taskBreakdownMaxSubtasksShouldDefaultTo10() {
        AgentConfig config = new AgentConfig();

        assertEquals(10, config.getTaskBreakdownMaxSubtasks(),
                "taskBreakdownMaxSubtasks 默认值应为 10");
    }

    /**
     * 验证配置项可通过 setter 修改（可配置性）
     */
    @Test
    void taskBreakdownConfigShouldBeConfigurable() {
        AgentConfig config = new AgentConfig();
        config.setTaskExecutionMaxIterations(5);
        config.setTaskBreakdownMaxSubtasks(8);
        config.setTaskBreakdownPlanPrompt("自定义规划提示词");
        config.setTaskExecutionSystemPrompt("自定义执行提示词");
        config.setTaskSummaryPrompt("自定义总结提示词");

        assertEquals(5, config.getTaskExecutionMaxIterations());
        assertEquals(8, config.getTaskBreakdownMaxSubtasks());
        assertEquals("自定义规划提示词", config.getTaskBreakdownPlanPrompt());
        assertEquals("自定义执行提示词", config.getTaskExecutionSystemPrompt());
        assertEquals("自定义总结提示词", config.getTaskSummaryPrompt());
    }

    /**
     * 验证现有配置项不受影响（向后兼容）
     */
    @Test
    void existingConfigShouldNotBeAffected() {
        AgentConfig config = new AgentConfig();

        assertEquals(10, config.getMaxIterations(), "maxIterations 默认值应仍为 10");
        assertEquals(20, config.getChatMemoryWindowSize(), "chatMemoryWindowSize 默认值应仍为 20");
        assertEquals(8, config.getThinkingMaxIterations(), "thinkingMaxIterations 默认值应仍为 8");
        assertNotNull(config.getDefaultSystemPrompt(), "defaultSystemPrompt 应不为 null");
        assertNotNull(config.getThinkingSystemPrompt(), "thinkingSystemPrompt 应不为 null");
        assertNotNull(config.getThinkingReactSystemPrompt(), "thinkingReactSystemPrompt 应不为 null");
        assertTrue(config.isEnableLogging(), "enableLogging 默认值应仍为 true");
    }
}
