package com.agentdemo.agent.prompt;

import com.agentdemo.agent.config.AgentConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PromptTemplateLoader 测试
 * <p>
 * 验证标准来源：Task-03 验证标准
 * 关联 AC：AC-003（提示词组合机制）、AC-024（模板文件缺失降级）、AC-025（模板文件加载机制）
 * </p>
 */
class PromptTemplateLoaderTest {

    private AgentConfig agentConfig;
    private PromptTemplateLoader loader;

    @BeforeEach
    void setUp() {
        agentConfig = new AgentConfig();
        loader = new PromptTemplateLoader(agentConfig);
    }

    // ==================== 正常加载测试 ====================

    /**
     * 验证 composeSystemPrompt("general", "chat") 返回非空字符串
     * 内容包含 general.txt 和 chat.txt 的组合（以 "\n\n" 分隔）
     */
    @Test
    void composeGeneralChatShouldReturnCombinedPrompt() {
        String result = loader.composeSystemPrompt("general", "chat");

        assertNotNull(result, "组合提示词不应为 null");
        assertTrue(result.contains("通用 AI 助手"), "应包含 general.txt 角色身份");
        assertTrue(result.contains("主动调用相应工具"), "应包含 chat.txt 场景行为");
        assertTrue(result.contains("\n\n"), "角色和场景应以 \\n\\n 分隔");
    }

    /**
     * 验证 composeSystemPrompt("general", "chat") 结果中 general.txt 内容在前，chat.txt 内容在后
     */
    @Test
    void roleTemplateShouldPrecedeScenarioTemplate() {
        String result = loader.composeSystemPrompt("general", "chat");

        int roleIndex = result.indexOf("通用 AI 助手");
        int scenarioIndex = result.indexOf("主动调用相应工具");

        assertTrue(roleIndex >= 0, "应找到角色模板内容");
        assertTrue(scenarioIndex >= 0, "应找到场景模板内容");
        assertTrue(roleIndex < scenarioIndex, "角色模板内容应在场景模板内容之前");
    }

    // ==================== 回退测试 ====================

    /**
     * 验证 composeSystemPrompt("nonexistent", "chat") 回退到 general.txt 角色模板
     */
    @Test
    void nonexistentRoleShouldFallbackToGeneral() {
        String result = loader.composeSystemPrompt("nonexistent", "chat");

        assertNotNull(result, "回退后提示词不应为 null");
        assertTrue(result.contains("通用 AI 助手"), "应回退到 general.txt 角色身份");
        assertTrue(result.contains("主动调用相应工具"), "应仍包含 chat.txt 场景内容");
    }

    /**
     * 验证 composeSystemPrompt("general", "nonexistent") 回退到 AgentConfig 默认值
     */
    @Test
    void nonexistentScenarioShouldFallbackToAgentConfig() {
        String result = loader.composeSystemPrompt("general", "nonexistent");

        assertNotNull(result, "回退后提示词不应为 null");
        assertEquals(agentConfig.getDefaultSystemPrompt(), result,
                "场景模板不存在时应回退到 AgentConfig.defaultSystemPrompt");
    }

    // ==================== 默认角色测试 ====================

    /**
     * 验证 composeSystemPrompt("chat") 使用 agentConfig.getDefaultRole() 作为默认角色
     */
    @Test
    void singleArgComposeShouldUseDefaultRole() {
        agentConfig.setDefaultRole("code");
        String resultWithDefault = loader.composeSystemPrompt("chat");
        String resultWithExplicit = loader.composeSystemPrompt("code", "chat");

        assertEquals(resultWithExplicit, resultWithDefault,
                "composeSystemPrompt(\"chat\") 应等价于 composeSystemPrompt(getDefaultRole(), \"chat\")");
    }

    /**
     * 验证默认角色为 "general"
     */
    @Test
    void defaultRoleShouldBeGeneral() {
        assertEquals("general", agentConfig.getDefaultRole(),
                "AgentConfig.defaultRole 默认值应为 general");
    }

    // ==================== 场景常量测试 ====================

    /**
     * 验证场景名称常量值
     */
    @Test
    void scenarioConstantsShouldHaveCorrectValues() {
        assertEquals("chat", PromptTemplateLoader.SCENARIO_CHAT);
        assertEquals("thinking", PromptTemplateLoader.SCENARIO_THINKING);
        assertEquals("react", PromptTemplateLoader.SCENARIO_REACT);
        assertEquals("task-plan", PromptTemplateLoader.SCENARIO_TASK_PLAN);
        assertEquals("task-execute", PromptTemplateLoader.SCENARIO_TASK_EXECUTE);
        assertEquals("task-summary", PromptTemplateLoader.SCENARIO_TASK_SUMMARY);
    }

    // ==================== {{tools}} 占位符测试 ====================

    /**
     * 验证 task-execute.txt 模板中的 {{tools}} 占位符也被保留
     */
    @Test
    void toolsPlaceholderShouldBePreservedInTaskExecuteScenario() {
        String result = loader.composeSystemPrompt("general", "task-execute");

        assertTrue(result.contains("{{tools}}"), "task-execute 场景应保留 {{tools}} 占位符");
    }

    // ==================== 各场景加载验证 ====================

    /**
     * 验证现有场景模板都能正确加载（react.txt/thinking.txt 已随 unified-chat-mode 彻底删除，
     * 仅保留生产实际使用的 5 个场景）
     */
    @Test
    void allScenariosShouldLoadSuccessfully() {
        String[][] scenarios = {
                {"chat", "主动调用相应工具"},
                {"task-plan", "JSON 数组"},
                {"task-execute", "执行分配给你的子任务"},
                {"task-summary", "生成一份简洁的总结"},
                {"hitl", "人机交互规则"}
        };

        for (String[] scenario : scenarios) {
            String result = loader.composeSystemPrompt("general", scenario[0]);
            assertNotNull(result, "场景 " + scenario[0] + " 加载结果不应为 null");
            assertTrue(result.contains(scenario[1]),
                    "场景 " + scenario[0] + " 应包含特征文本: " + scenario[1]);
        }
    }

    /**
     * 验证所有 4 个角色模板都能正确加载
     */
    @Test
    void allRolesShouldLoadSuccessfully() {
        String[][] roles = {
                {"general", "通用 AI 助手"},
                {"code", "资深 Java 工程师"},
                {"data-analyst", "数据分析专家"},
                {"doc-writer", "技术文档撰写专家"}
        };

        for (String[] role : roles) {
            String result = loader.composeSystemPrompt(role[0], "chat");
            assertNotNull(result, "角色 " + role[0] + " 加载结果不应为 null");
            assertTrue(result.contains(role[1]),
                    "角色 " + role[0] + " 应包含特征文本: " + role[1]);
        }
    }
}
