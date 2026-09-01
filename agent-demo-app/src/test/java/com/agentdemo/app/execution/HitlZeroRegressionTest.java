package com.agentdemo.app.execution;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.agent.prompt.PromptTemplateLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HITL 零回归哨兵测试（agent-context-engineering Task-18，AC-H01/M02）
 * <p>
 * 业务含义：组装改造（系统提示词冻结、技能段移出）后，HITL 路径的关键提示词结构
 * 必须完整保留——hitl 场景含 askUser 规则与工具协议措辞；hitl-guidance 引导段可加载。
 * 完整 askUser/tool_confirm/checkpoint 流程回归由既有测试套件
 * （AgentExecutorHITLTest / AgentExecutorCheckpointTest / HITLReActStreamTest）覆盖，
 * 本类作为提示词结构完整性哨兵 + 恢复路径 SystemMessage 一致性断言。
 * </p>
 */
class HitlZeroRegressionTest {

    @Test
    @DisplayName("hitl 场景模板保留 askUser 交互规则与工具协议措辞（AC-H01 提示词结构零回归）")
    void hitl场景模板_结构完整() {
        PromptTemplateLoader loader = new PromptTemplateLoader(new AgentConfig());
        String hitl = loader.composeSystemPrompt("general", PromptTemplateLoader.SCENARIO_HITL);
        assertNotNull(hitl);
        assertTrue(hitl.contains("askUser"), "hitl 场景应含 askUser 交互规则（AC-H01）");
        assertTrue(hitl.contains("确认") && hitl.contains("3 次"),
                "hitl 场景应含确认与追问上限规则");
        assertTrue(hitl.contains("tools 参数"), "hitl 场景应声明工具协议措辞（冻结契约支撑）");
    }

    @Test
    @DisplayName("hitl-guidance 引导段可加载且含工具引导（工作流 HITL 三段组合）")
    void hitlGuidance_可加载() {
        PromptTemplateLoader loader = new PromptTemplateLoader(new AgentConfig());
        String guidance = loader.loadScenarioTemplate(PromptTemplateLoader.SCENARIO_HITL_GUIDANCE);
        assertNotNull(guidance, "hitl-guidance 应可加载");
        assertTrue(guidance.contains("askUser"), "引导段应含 askUser 使用引导");
        assertTrue(guidance.contains("{{tools}}"), "引导段应保留 {{tools}} 占位符");
    }

    @Test
    @DisplayName("hitl-guidance 经共享片段展开后公共规则完整且不再内联重复（CR-001 AC-N04）")
    void hitlGuidance_共享片段展开() {
        PromptTemplateLoader loader = new PromptTemplateLoader(new AgentConfig());
        String guidance = loader.loadScenarioTemplate(PromptTemplateLoader.SCENARIO_HITL_GUIDANCE);
        assertNotNull(guidance, "hitl-guidance 应可加载");

        String[] sharedKeywords = {
                "tools 参数",
                "缺少必要参数",
                "多种可选方案",
                "有副作用",
                "无需确认",
                "不要为确认而确认",
                "最多追问 3 次",
                "选项或示例引导",
                "2-4 个选项",
                "更具体的选项",
                "不要盲目重试",
                "超出已有工具的能力范围"
        };
        for (String kw : sharedKeywords) {
            assertTrue(guidance.contains(kw), "hitl-guidance 展开后应包含公共规则: " + kw);
        }
        assertFalse(guidance.contains("### 追问策略"),
                "hitl-guidance 不应再内联重复的追问策略段落（应来自共享片段）");
        assertTrue(guidance.contains("{{tools}}"), "引导段应保留 {{tools}} 占位符");
    }
}
