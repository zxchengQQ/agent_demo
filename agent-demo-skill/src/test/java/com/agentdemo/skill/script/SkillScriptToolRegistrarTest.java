package com.agentdemo.skill.script;

import com.agentdemo.skill.entity.ScriptParam;
import com.agentdemo.skill.entity.SkillDefinition;
import com.agentdemo.skill.entity.SkillScript;
import com.agentdemo.tools.registry.ToolRegistry;
import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SkillScriptToolRegistrar 脚本工具动态生成/缓存测试（CR-001 Task-33，AC-T02/T05）
 * <p>
 * 业务含义：技能激活后其自带脚本经 ByteBuddy 动态生成带 @Tool 注解的工具对象
 * （方法名 skill_{skillId}_{scriptName}，参数按声明 schema），由 SessionToolResolver 注入
 * 会话工具集（不经 ToolRegistry，不进入系统权限模型，AC-T03）。
 * </p>
 */
class SkillScriptToolRegistrarTest {

    private SkillScriptToolRegistrar registrar;
    private ToolRegistry toolRegistry;

    @BeforeEach
    void setUp() {
        toolRegistry = mock(ToolRegistry.class);
        registrar = new SkillScriptToolRegistrar(toolRegistry, new SkillScriptExecutor());
    }

    private SkillDefinition skillWithScript(String skillId, SkillScript script) {
        SkillDefinition skill = new SkillDefinition();
        skill.setId(skillId);
        skill.setName("测试技能");
        skill.setDescription("描述");
        skill.setInstruction("指令");
        skill.setScripts(List.of(script));
        return skill;
    }

    private SkillScript script(String name, String lang, String content, List<ScriptParam> params) {
        return new SkillScript(name, lang, "脚本描述-" + name, params, content);
    }

    @Test
    void shouldProvideGeneratedToolWithMethodNameAndAnnotation() throws Exception {
        SkillDefinition skill = skillWithScript("demo", script("http-get", "shell",
                "echo $SKILL_PARAM_URL", List.of(new ScriptParam("url", "string", true, "URL"))));

        List<Object> tools = registrar.ensureRegistered(skill);
        assertThat(tools).hasSize(1);

        // 方法名 skill_{skillId}_{scriptName}（非标识符字符替换为下划线）
        Method method = tools.get(0).getClass().getMethod("skill_demo_http_get", String.class);
        assertThat(method).isNotNull();
        assertThat(method.isAnnotationPresent(Tool.class)).isTrue();
    }

    @Test
    void shouldCacheToolsAcrossCalls() {
        SkillDefinition skill = skillWithScript("demo", script("s1", "shell", "echo ok", List.of()));
        Object first = registrar.ensureRegistered(skill).get(0);
        Object second = registrar.ensureRegistered(skill).get(0);
        assertThat(second).isSameAs(first);
    }

    @Test
    void shouldInvokeScriptViaGeneratedTool() throws Exception {
        SkillDefinition skill = skillWithScript("echo", script("say", "shell", "echo $SKILL_PARAM_MSG",
                List.of(new ScriptParam("msg", "string", true, "消息"))));

        Object tool = registrar.ensureRegistered(skill).get(0);
        Method method = tool.getClass().getMethod("skill_echo_say", String.class);
        Object result = method.invoke(tool, "你好世界");
        assertThat(result).isInstanceOf(String.class);
        assertThat(result.toString()).contains("你好世界");
    }

    @Test
    void shouldReturnEmptyWhenNoScripts() {
        SkillDefinition skill = new SkillDefinition();
        skill.setId("plain");
        assertThat(registrar.ensureRegistered(skill)).isEmpty();
    }

    @Test
    void shouldUnregisterSkillScripts() {
        SkillDefinition skill = skillWithScript("demo", script("s1", "shell", "echo ok", List.of()));
        Object first = registrar.ensureRegistered(skill).get(0);
        registrar.unregisterSkill("demo");
        // 注销后缓存清空，重新激活会生成新实例（内存泄漏防护）
        Object second = registrar.ensureRegistered(skill).get(0);
        assertThat(second).isNotSameAs(first);
    }
}
