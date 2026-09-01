package com.agentdemo.agent.config;

import com.agentdemo.agent.prompt.PromptTemplateLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentConfig 降级默认值与场景模板语义一致性守护测试（CR-001 Task-21，AC-E03）
 * <p>
 * 业务含义：场景模板缺失时系统降级至 AgentConfig 内置默认提示词。为保证降级后
 * 对话质量不骤降，默认值必须与当前模板内容保持语义一致（模板为权威来源）。
 * 本测试双向守护：模板含核心语义锚点（模板完整性）+ 默认值含相同锚点（防默认值漂移）。
 * 模板演进后默认值未同步时，本测试将失败提示同步。
 * </p>
 */
class AgentConfigFallbackConsistencyTest {

    private AgentConfig config;
    private PromptTemplateLoader loader;

    @BeforeEach
    void setUp() {
        config = new AgentConfig();
        loader = new PromptTemplateLoader(config);
    }

    @Test
    void defaultSystemPromptShouldAlignWithChatTemplate() {
        assertFallbackAligned(loader.loadScenarioTemplate("chat"),
                config.getDefaultSystemPrompt(),
                new String[]{"主动调用相应工具", "读取文件"});
    }

    @Test
    void taskBreakdownPlanPromptShouldAlignWithTaskPlanTemplate() {
        assertFallbackAligned(loader.loadScenarioTemplate("task-plan"),
                config.getTaskBreakdownPlanPrompt(),
                new String[]{"JSON 数组", "最多拆解为 10"});
    }

    @Test
    void taskExecutionSystemPromptShouldAlignWithTaskExecuteTemplate() {
        assertFallbackAligned(loader.loadScenarioTemplate("task-execute"),
                config.getTaskExecutionSystemPrompt(),
                new String[]{"执行分配给你的子任务", "ReAct"});
    }

    @Test
    void taskSummaryPromptShouldAlignWithTaskSummaryTemplate() {
        assertFallbackAligned(loader.loadScenarioTemplate("task-summary"),
                config.getTaskSummaryPrompt(),
                new String[]{"概括主要发现"});
    }

    private void assertFallbackAligned(String template, String fallback, String[] anchors) {
        assertNotNull(template, "模板应可加载（守护测试前提）");
        for (String anchor : anchors) {
            assertTrue(template.contains(anchor),
                    "模板应包含核心语义锚点: " + anchor);
            assertTrue(fallback != null && fallback.contains(anchor),
                    "AgentConfig 默认值应与模板语义一致，缺失锚点: " + anchor
                            + "（模板演进后默认值未同步？请同步 AgentConfig 降级默认值）");
        }
    }
}
