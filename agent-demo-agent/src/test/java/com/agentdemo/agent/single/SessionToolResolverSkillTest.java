package com.agentdemo.agent.single;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.skill.config.SkillProperties;
import com.agentdemo.skill.entity.ScriptParam;
import com.agentdemo.skill.entity.SkillDefinition;
import com.agentdemo.skill.entity.SkillScript;
import com.agentdemo.skill.script.SkillScriptExecutor;
import com.agentdemo.skill.script.SkillScriptToolRegistrar;
import com.agentdemo.skill.session.SkillSessionManager;
import com.agentdemo.skill.store.SkillStore;
import com.agentdemo.tools.registry.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SessionToolResolver 技能自带脚本工具合并测试（CR-001 Task-34，AC-T02/T03/T05）
 * <p>
 * 业务含义：激活技能的自带脚本工具（skill_{skillId}_{scriptName}）直接注入会话工具集，
 * **不经 ToolRegistry 登记权限、不进入系统权限模型**（决策 9，AC-T03）；技能非激活/禁用/删除
 * 后不注入（AC-T05/E04）。
 * </p>
 */
class SessionToolResolverSkillTest {

    private ToolRegistry toolRegistry;
    private SkillStore skillStore;
    private SkillSessionManager skillSessionManager;
    private SessionToolResolver resolver;
    private AgentConfig agentConfig;

    @BeforeEach
    void setUp() {
        toolRegistry = mock(ToolRegistry.class);
        agentConfig = new AgentConfig();
        agentConfig.setEnableLogging(false);
        agentConfig.getTools().setDefaultTools(List.of());

        SkillProperties properties = new SkillProperties();
        properties.setStorageDir(Path.of(System.getProperty("java.io.tmpdir"), "skill-resolver-" + System.nanoTime()).toString());
        skillStore = new SkillStore(properties);
        skillSessionManager = new SkillSessionManager(skillStore, properties);

        SkillScriptToolRegistrar registrar = new SkillScriptToolRegistrar(toolRegistry, new SkillScriptExecutor());
        resolver = new SessionToolResolver(toolRegistry, agentConfig, skillSessionManager, registrar);
    }

    private SkillDefinition skill(String id) {
        SkillDefinition s = new SkillDefinition();
        s.setId(id);
        s.setName("技能-" + id);
        s.setDescription("描述");
        s.setInstruction("指令");
        s.setScripts(List.of(new SkillScript("http-get", "shell", "HTTP 获取",
                List.of(new ScriptParam("url", "string", true, "URL")),
                "curl -s \"$SKILL_PARAM_URL\"")));
        return s;
    }

    private boolean hasScriptTool(List<Object> tools, String toolName) {
        return tools.stream()
                .flatMap(t -> resolver.findToolMethodNames(t).stream())
                .anyMatch(n -> n.equals(toolName));
    }

    @Test
    void shouldInjectScriptToolsOfActiveSkill() {
        skillStore.create(skill("s1"));
        skillSessionManager.activate("sess", "s1");

        // askSupported=true（流式路径）
        List<Object> tools = resolver.resolveSessionTools("sess", null, true);
        assertThat(hasScriptTool(tools, "skill_s1_http_get")).isTrue();
    }

    @Test
    void shouldNotApplySystemPermissionModelToScriptTools() {
        // AC-T03：脚本工具不进入系统权限模型——同步路径（askSupported=false，无确认能力）也注入
        skillStore.create(skill("s1"));
        skillSessionManager.activate("sess", "s1");

        List<Object> tools = resolver.resolveSessionTools("sess", null, false);
        assertThat(hasScriptTool(tools, "skill_s1_http_get")).isTrue();
    }

    @Test
    void shouldNotInjectToolsOfInactiveSkill() {
        skillStore.create(skill("s1"));
        // 未激活 → 脚本工具不注入
        List<Object> tools = resolver.resolveSessionTools("sess", null, true);
        assertThat(hasScriptTool(tools, "skill_s1_http_get")).isFalse();
    }

    @Test
    void shouldNotInjectDisabledOrDeletedSkillTools() {
        SkillDefinition s = skill("s1");
        skillStore.create(s);
        skillSessionManager.activate("sess", "s1");

        // 禁用技能后激活集实时剔除 → 脚本工具不注入（AC-E04 联动）
        s.setEnabled(false);
        skillStore.update(s);
        List<Object> tools = resolver.resolveSessionTools("sess", null, true);
        assertThat(hasScriptTool(tools, "skill_s1_http_get")).isFalse();
    }

    @Test
    void shouldDedupeScriptToolsAcrossCalls() {
        skillStore.create(skill("s1"));
        skillSessionManager.activate("sess", "s1");

        // 同一技能脚本工具经 Registrar 缓存，多次解析同一实例（不重复生成）
        List<Object> first = resolver.resolveSessionTools("sess", null, true);
        List<Object> second = resolver.resolveSessionTools("sess", null, true);
        assertThat(hasScriptTool(first, "skill_s1_http_get")).isTrue();
        assertThat(second).containsExactlyElementsOf(first);
    }

    // ==================== Task-01: resolveSessionBaseTools（冻结工具集，agent-context-engineering） ====================

    @Test
    void baseToolsShouldNotIncludeScriptToolsOfActiveSkill() {
        // AC-T02：基础工具集（{{tools}} 文本来源）不含技能脚本工具，会话内字节级稳定
        skillStore.create(skill("s1"));
        skillSessionManager.activate("sess", "s1");

        List<Object> full = resolver.resolveSessionTools("sess", null, true);
        List<Object> base = resolver.resolveSessionBaseTools("sess", null, true);

        assertThat(hasScriptTool(full, "skill_s1_http_get")).isTrue();
        assertThat(hasScriptTool(base, "skill_s1_http_get")).isFalse();
    }

    @Test
    void baseToolsShouldBeStableAcrossSkillActivation() {
        // 技能激活前/后的基础工具集完全一致（冻结契约）
        List<Object> before = resolver.resolveSessionBaseTools("sess", null, true);
        skillStore.create(skill("s1"));
        skillSessionManager.activate("sess", "s1");
        List<Object> after = resolver.resolveSessionBaseTools("sess", null, true);

        assertThat(after).containsExactlyElementsOf(before);
    }

    @Test
    void baseToolsShouldUseSessionCacheOfSpecifiedToolIds() {
        // 指定工具经会话缓存，后续 null 沿用（与全量解析共用 ids 缓存）
        SkillDefinition s = skill("s1");
        skillStore.create(s);

        when(toolRegistry.resolveToolsForStreaming(java.util.List.of("builtin:httpGet")))
                .thenReturn(java.util.List.of(new FakeHttpTool()));
        resolver.resolveSessionBaseTools("sess", java.util.List.of("builtin:httpGet"));

        List<Object> fromCache = resolver.resolveSessionBaseTools("sess", null, true);
        assertThat(hasScriptTool(fromCache, "httpGet")).isTrue();
    }

    /** 模拟指定工具 */
    static class FakeHttpTool {
        @dev.langchain4j.agent.tool.Tool("HTTP GET 请求")
        public String httpGet(String url) {
            return "{}";
        }
    }
}
