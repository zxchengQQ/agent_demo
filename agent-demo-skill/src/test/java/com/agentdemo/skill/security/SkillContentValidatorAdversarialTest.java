package com.agentdemo.skill.security;

import com.agentdemo.skill.entity.ScriptParam;
import com.agentdemo.skill.entity.SkillDefinition;
import com.agentdemo.skill.entity.SkillScript;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 恶意技能内容对抗性测试（技术方案 Task-23/6.7，AC-S01）
 * <p>
 * 业务含义：使用对抗集（skill-adversarial/content-adversarial.json）参数化验证
 * 四类恶意指令 100% 拦截、良性样本零误拦截。
 * </p>
 */
class SkillContentValidatorAdversarialTest {

    private final SkillContentValidator validator = new SkillContentValidator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 加载对抗集全部用例 */
    static List<JsonNode> cases() throws Exception {
        try (InputStream is = SkillContentValidatorAdversarialTest.class.getClassLoader()
                .getResourceAsStream("skill-adversarial/content-adversarial.json")) {
            JsonNode root = new ObjectMapper().readTree(is);
            List<JsonNode> list = new ArrayList<>();
            root.get("cases").forEach(list::add);
            return list;
        }
    }

    @ParameterizedTest
    @MethodSource("cases")
    void adversarialCasesShouldBeHandled(JsonNode testCase) {
        SkillDefinition skill = new SkillDefinition();
        skill.setId(testCase.get("id").asText());
        skill.setName("对抗样本");
        skill.setDescription("描述");
        skill.setInstruction(testCase.get("instruction").asText());

        SkillContentValidator.Result result = validator.validate(skill);

        boolean expectBlocked = testCase.get("expectBlocked").asBoolean();
        if (expectBlocked) {
            // 恶意样本 100% 拦截（AC-S01）
            assertThat(result.blocked())
                    .as("恶意样本应被拦截: " + testCase.get("id").asText())
                    .isTrue();
        } else {
            // 良性样本零误拦截
            assertThat(result.blocked())
                    .as("良性样本不应被拦截: " + testCase.get("id").asText())
                    .isFalse();
        }
    }

    private SkillDefinition skillWithScript(SkillScript script) {
        SkillDefinition skill = new SkillDefinition();
        skill.setId("script-skill");
        skill.setName("脚本技能");
        skill.setDescription("描述");
        skill.setInstruction("指令");
        skill.setScripts(List.of(script));
        return skill;
    }

    @Test
    void shouldBlockNonWhitelistedLanguageScript() {
        // 脚本语言非白名单（仅 shell/python3）→ 阻断（AC-S01，CR-001）
        SkillDefinition skill = skillWithScript(
                new SkillScript("s", "ruby", "脚本", List.of(), "puts 1"));
        assertThat(validator.validate(skill).blocked()).isTrue();
    }

    @Test
    void shouldBlockDangerousScriptCommand() {
        // 脚本含危险命令 → 阻断（AC-S01/S06，CR-001）
        SkillDefinition skill = skillWithScript(
                new SkillScript("s", "shell", "脚本", List.of(), "rm -rf /"));
        assertThat(validator.validate(skill).blocked()).isTrue();
    }

    @Test
    void shouldBlockDuplicateParamName() {
        // 参数 schema 非法（重复参数名）→ 阻断（AC-S01，CR-001）
        SkillDefinition skill = skillWithScript(new SkillScript("s", "shell", "脚本",
                List.of(new ScriptParam("url", "string", true, "URL"),
                        new ScriptParam("url", "integer", false, "重复")),
                "echo ok"));
        assertThat(validator.validate(skill).blocked()).isTrue();
    }

    @Test
    void shouldBlockInvalidParamType() {
        // 参数类型非法（非 string/integer/number/boolean）→ 阻断（CR-001）
        SkillDefinition skill = skillWithScript(new SkillScript("s", "shell", "脚本",
                List.of(new ScriptParam("x", "date", false, "非法类型")),
                "echo ok"));
        assertThat(validator.validate(skill).blocked()).isTrue();
    }

    @Test
    void shouldNotBlockBenignScript() {
        // 良性脚本不误拦截（CR-001）
        SkillDefinition skill = skillWithScript(new SkillScript("http-get", "shell",
                "HTTP 获取公开数据",
                List.of(new ScriptParam("url", "string", true, "URL")),
                "curl -s \"$SKILL_PARAM_URL\""));
        assertThat(validator.validate(skill).blocked()).isFalse();
    }
}
