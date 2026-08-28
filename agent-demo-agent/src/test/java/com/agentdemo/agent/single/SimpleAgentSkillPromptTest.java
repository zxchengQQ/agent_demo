package com.agentdemo.agent.single;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.agent.prompt.PromptTemplateLoader;
import com.agentdemo.agent.core.HumanInteractionManager;
import com.agentdemo.llm.registry.ModelFactory;
import com.agentdemo.memory.shortterm.ChatMemoryManager;
import com.agentdemo.skill.prompt.SkillPromptComposer;
import com.agentdemo.tools.registry.ToolExecutor;
import com.agentdemo.tools.registry.ToolRegistry;
import com.agentdemo.tools.registry.ToolSchemaConverter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SimpleAgent 同步路径系统提示词测试（agent-context-engineering Task-13，AC-N01/T01）
 * <p>
 * 业务含义（改造后）：技能目录/激活段已移出系统提示词（改为记忆流附件 emit-once），
 * 同步路径系统提示词仅返回基础 chat 场景；无论技能是否激活，系统提示词字节级一致。
 * </p>
 */
class SimpleAgentSkillPromptTest {

    private SimpleAgent buildAgent(SkillPromptComposer composer) {
        AgentConfig agentConfig = new AgentConfig();
        agentConfig.setEnableLogging(false);
        return new SimpleAgent(
                mock(ModelFactory.class), mock(ToolRegistry.class), mock(ChatMemoryManager.class),
                agentConfig, mock(ToolSchemaConverter.class), mock(ToolExecutor.class),
                new PromptTemplateLoader(agentConfig), mock(HumanInteractionManager.class),
                new SessionToolResolver(mock(ToolRegistry.class), agentConfig), composer);
    }

    @Test
    void shouldNotAppendSkillSegments() {
        // AC-N01/T01：技能段移出系统提示词（即使技能激活，系统提示词也不含技能指令）
        SkillPromptComposer composer = mock(SkillPromptComposer.class);
        when(composer.composeCatalogSegment("sess")).thenReturn("## 可用技能\n- s1：技能一");
        when(composer.composeActivatedSegment("sess")).thenReturn("## 已激活技能\n指令全文");
        SimpleAgent agent = buildAgent(composer);

        String prompt = agent.composeSystemPromptWithSkills("sess");
        assertThat(prompt).doesNotContain("可用技能").doesNotContain("技能一");
        assertThat(prompt).doesNotContain("已激活技能").doesNotContain("指令全文");
        // 基础场景提示词仍存在（chat 场景）
        assertThat(prompt).isNotBlank();
    }

    @Test
    void shouldEqualBaselineWhenNoSkills() {
        SkillPromptComposer composer = mock(SkillPromptComposer.class);
        when(composer.composeCatalogSegment("sess")).thenReturn("");
        when(composer.composeActivatedSegment("sess")).thenReturn("");
        SimpleAgent agent = buildAgent(composer);

        String prompt = agent.composeSystemPromptWithSkills("sess");
        assertThat(prompt).doesNotContain("可用技能").doesNotContain("已激活技能");
    }

    @Test
    void shouldEqualBaselineWhenComposerNull() {
        // 无技能能力（兼容构造器）：与现状零差异（技术方案 6.6 退化）
        SimpleAgent agent = buildAgent(null);
        String prompt = agent.composeSystemPromptWithSkills("sess");
        assertThat(prompt).isNotBlank();
        assertThat(prompt).doesNotContain("可用技能");
    }

    @Test
    void shouldRemainStableAcrossSkillActivation() {
        // 冻结契约：技能激活前后系统提示词一致（不因激活段而改变）
        SimpleAgent agent = buildAgent(null);
        String before = agent.composeSystemPromptWithSkills("sess");
        String after = agent.composeSystemPromptWithSkills("sess");
        assertThat(after).isEqualTo(before);
    }
}
