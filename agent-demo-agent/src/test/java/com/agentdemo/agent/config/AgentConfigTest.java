package com.agentdemo.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentConfig 测试
 * <p>
 * 验证标准来源：Task-01 验证标准
 * 关联 AC：AC-018（最大迭代次数可配置）、AC-019（系统提示词含 ReAct 引导）
 * </p>
 */
class AgentConfigTest {

    /**
     * 验证 thinkingMaxIterations 默认值为 8
     * 业务含义：深度思考模式 ReAct 循环最大迭代次数默认 8 次
     */
    @Test
    void thinkingMaxIterationsShouldDefaultTo8() {
        AgentConfig config = new AgentConfig();

        assertEquals(8, config.getThinkingMaxIterations(),
                "thinkingMaxIterations 默认值应为 8");
    }

    /**
     * 验证 thinkingReactSystemPrompt 默认值包含 ReAct 引导关键词
     * 业务含义：系统提示词必须引导 LLM 使用 Thought/Action/Observation 结构化标签
     */
    @Test
    void thinkingReactSystemPromptShouldContainReActKeywords() {
        AgentConfig config = new AgentConfig();

        String prompt = config.getThinkingReactSystemPrompt();
        assertNotNull(prompt, "thinkingReactSystemPrompt 不应为 null");
        assertTrue(prompt.contains("Thought"), "提示词应包含 Thought 关键词");
        assertTrue(prompt.contains("Action"), "提示词应包含 Action 关键词");
        assertTrue(prompt.contains("Observation"), "提示词应包含 Observation 关键词");
    }

    /**
     * 验证 thinkingReactSystemPrompt 默认值包含工具能力描述
     * 业务含义：提示词应告知 LLM 可以调用工具
     */
    @Test
    void thinkingReactSystemPromptShouldContainToolDescription() {
        AgentConfig config = new AgentConfig();

        String prompt = config.getThinkingReactSystemPrompt();
        assertNotNull(prompt, "thinkingReactSystemPrompt 不应为 null");
        assertTrue(prompt.contains("工具") || prompt.contains("tool"),
                "提示词应包含工具能力描述");
    }

    /**
     * 验证 thinkingMaxIterations 可通过 setter 修改
     * 业务含义：管理员可通过配置文件调整最大迭代次数（AC-018）
     */
    @Test
    void thinkingMaxIterationsShouldBeConfigurable() {
        AgentConfig config = new AgentConfig();
        config.setThinkingMaxIterations(5);

        assertEquals(5, config.getThinkingMaxIterations(),
                "thinkingMaxIterations 应可通过 setter 设置为 5");
    }

    /**
     * 验证 thinkingReactSystemPrompt 可通过 setter 修改
     */
    @Test
    void thinkingReactSystemPromptShouldBeConfigurable() {
        AgentConfig config = new AgentConfig();
        config.setThinkingReactSystemPrompt("自定义提示词");

        assertEquals("自定义提示词", config.getThinkingReactSystemPrompt(),
                "thinkingReactSystemPrompt 应可通过 setter 设置");
    }

    /**
     * 验证现有配置项不受影响（向后兼容）
     */
    @Test
    void existingConfigShouldNotBeAffected() {
        AgentConfig config = new AgentConfig();

        assertEquals(10, config.getMaxIterations(), "maxIterations 默认值应仍为 10");
        assertEquals(20, config.getChatMemoryWindowSize(), "chatMemoryWindowSize 默认值应仍为 20");
        assertNotNull(config.getDefaultSystemPrompt(), "defaultSystemPrompt 应不为 null");
        assertNotNull(config.getThinkingSystemPrompt(), "thinkingSystemPrompt 应不为 null");
        assertTrue(config.isEnableLogging(), "enableLogging 默认值应仍为 true");
        assertEquals("./data", config.getFileAllowedDir(), "fileAllowedDir 默认值应仍为 ./data");
    }

    // ==================== CR-001 Task-18 测试 ====================

    /**
     * 验证 thinkingReactSystemPrompt 默认值不包含硬编码工具名（CR-001 Task-18）
     * 业务含义：工具描述应通过运行时 convertToDescriptionText() 动态生成，不硬编码在提示词配置中
     */
    @Test
    void thinkingReactSystemPromptShouldNotContainHardcodedToolNames() {
        AgentConfig config = new AgentConfig();
        String prompt = config.getThinkingReactSystemPrompt();

        assertFalse(prompt.contains("calculate"), "提示词不应硬编码 calculate 工具名");
        assertFalse(prompt.contains("getCurrentTime"), "提示词不应硬编码 getCurrentTime 工具名");
        assertFalse(prompt.contains("getCurrentDate"), "提示词不应硬编码 getCurrentDate 工具名");
        assertFalse(prompt.contains("httpGet"), "提示词不应硬编码 httpGet 工具名");
        assertFalse(prompt.contains("httpPost"), "提示词不应硬编码 httpPost 工具名");
        assertFalse(prompt.contains("readFile"), "提示词不应硬编码 readFile 工具名");
    }

    /**
     * 验证 thinkingReactSystemPrompt 默认值不包含硬编码工具描述段（CR-001 Task-18）
     * 业务含义：工具描述段由 convertToDescriptionText() 动态生成，不在提示词配置中硬编码
     */
    @Test
    void thinkingReactSystemPromptShouldNotContainHardcodedToolDescriptionSection() {
        AgentConfig config = new AgentConfig();
        String prompt = config.getThinkingReactSystemPrompt();

        assertFalse(prompt.contains("你可以调用以下工具来获取信息"),
                "提示词不应包含硬编码工具描述段（由 convertToDescriptionText 动态生成）");
        assertFalse(prompt.contains("计算器 calculate"),
                "提示词不应包含硬编码工具描述内容");
    }
}
