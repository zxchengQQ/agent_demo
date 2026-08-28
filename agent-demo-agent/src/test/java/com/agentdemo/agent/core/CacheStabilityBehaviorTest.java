package com.agentdemo.agent.core;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.agent.prompt.PromptTemplateLoader;
import com.agentdemo.agent.single.SessionToolResolver;
import com.agentdemo.skill.config.SkillProperties;
import com.agentdemo.skill.entity.ScriptParam;
import com.agentdemo.skill.entity.SkillDefinition;
import com.agentdemo.skill.entity.SkillScript;
import com.agentdemo.skill.script.SkillScriptExecutor;
import com.agentdemo.skill.script.SkillScriptToolRegistrar;
import com.agentdemo.skill.session.SkillSessionManager;
import com.agentdemo.skill.store.SkillStore;
import com.agentdemo.tools.registry.ToolRegistry;
import com.agentdemo.tools.registry.ToolSchemaConverter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 缓存稳定性行为测试（agent-context-engineering Task-14，AC-N01/T01/T02/T03）
 * <p>
 * 端到端断言（真实组件组合 + mock 工具注册表）：
 * ①技能激活前后系统提示词字节级一致（冻结契约，AC-N01/T02）；
 * ②技能激活不影响 {{tools}} 文本（基础工具集，AC-T02）；
 * ③全量工具集在激活后追加脚本工具（热刷新只追加语义，AC-T03）；
 * ④激活段不出现在系统提示词（单通道，AC-T01）。
 * </p>
 */
class CacheStabilityBehaviorTest {

    @Test
    @DisplayName("技能激活前后：系统提示词字节级一致，工具集只追加脚本工具")
    void 技能激活前后_系统提示词冻结_工具集只追加() {
        // 真实组件：ToolRegistry(mock 返回稳定默认工具) + SkillStore/SkillSessionManager(真实)
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        Object timeTool = new TimeTool();
        when(toolRegistry.getDefaultToolsForStreaming(List.of())).thenReturn(List.of(timeTool));
        when(toolRegistry.getDefaultToolsForDirect(List.of())).thenReturn(List.of(timeTool));

        SkillProperties properties = new SkillProperties();
        properties.setStorageDir(Path.of(System.getProperty("java.io.tmpdir"),
                "cache-behavior-" + System.nanoTime()).toString());
        SkillStore skillStore = new SkillStore(properties);
        SkillSessionManager skillSessionManager = new SkillSessionManager(skillStore, properties);
        SkillScriptToolRegistrar registrar = new SkillScriptToolRegistrar(toolRegistry, new SkillScriptExecutor());
        SessionToolResolver resolver = new SessionToolResolver(toolRegistry, new AgentConfig(),
                skillSessionManager, registrar);

        // 注册一个带脚本工具的技能
        SkillDefinition skill = new SkillDefinition();
        skill.setId("s1");
        skill.setName("周报专家");
        skill.setDescription("撰写周报");
        skill.setInstruction("按模板撰写周报");
        skill.setScripts(List.of(new SkillScript("render", "shell", "渲染周报",
                List.of(new ScriptParam("date", "string", true, "日期")), "echo done")));
        skillStore.create(skill);

        AgentConfig agentConfig = new AgentConfig();
        agentConfig.getTools().setDefaultTools(List.of());
        PromptTemplateLoader loader = new PromptTemplateLoader(agentConfig);
        ToolSchemaConverter converter = new ToolSchemaConverter(toolRegistry);

        // 激活前系统提示词
        String toolsBefore = converter.convertToDescriptionText(resolver.resolveSessionBaseTools("sess", null));
        String promptBefore = loader.composeSystemPrompt("general", "hitl").replace("{{tools}}", toolsBefore);

        // 激活技能（loadSkill 语义）
        skillSessionManager.activate("sess", "s1");

        // 激活后：基础工具集不变（冻结），全量工具集追加脚本工具（只追加）
        String toolsAfter = converter.convertToDescriptionText(resolver.resolveSessionBaseTools("sess", null));
        String promptAfter = loader.composeSystemPrompt("general", "hitl").replace("{{tools}}", toolsAfter);
        assertThat(promptAfter).isEqualTo(promptBefore).as("技能激活前后系统提示词字节级一致（AC-N01/T02）");

        List<Object> fullAfter = resolver.resolveSessionTools("sess", null, true);
        assertThat(resolver.findToolMethodNames(fullAfter.get(fullAfter.size() - 1)))
                .as("激活后全量工具集末尾为追加的脚本工具（AC-T03 只追加）")
                .anyMatch(name -> name.startsWith("skill_s1_"));
        assertThat(toolsAfter).as("基础工具集不含脚本工具名（AC-T02）").doesNotContain("skill_s1_");
    }

    /** 测试用工具 */
    static class TimeTool {
        @dev.langchain4j.agent.tool.Tool("获取当前时间")
        public String getCurrentTime() {
            return "12:00";
        }
    }
}
