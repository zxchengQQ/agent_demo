package com.agentdemo.skill.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SkillDefinition 实体测试
 * <p>
 * 业务含义：验证技能定义实体的 Jackson 序列化往返与字段语义
 * （CR-001 Task-29：boundToolIds → scripts[]）。实体是技能域所有能力的数据基础。
 * </p>
 */
class SkillDefinitionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldRoundTripJsonWithoutLoss() throws Exception {
        SkillDefinition skill = new SkillDefinition();
        skill.setId("weekly-report");
        skill.setName("周报撰写专家");
        skill.setDescription("编写周报的领域技能");
        skill.setInstruction("按结构编写周报：本周成果/数据/风险/下周计划。");
        skill.setResources(List.of(
                new SkillResource("weekly-report-template", "【本周成果】\n【本周数据】\n【风险】\n【下周计划】")));
        skill.setScripts(List.of(
                new SkillScript("http-get", "shell", "通过 HTTP 获取公开数据",
                        List.of(new ScriptParam("url", "string", true, "目标 URL")),
                        "#!/bin/bash\ncurl -s \"$URL\"")));
        skill.setEnabled(true);
        skill.setSource(SkillSource.PRESET);

        String json = objectMapper.writeValueAsString(skill);
        SkillDefinition parsed = objectMapper.readValue(json, SkillDefinition.class);

        assertThat(parsed.getId()).isEqualTo("weekly-report");
        assertThat(parsed.getName()).isEqualTo("周报撰写专家");
        assertThat(parsed.getDescription()).isEqualTo("编写周报的领域技能");
        assertThat(parsed.getInstruction()).isEqualTo("按结构编写周报：本周成果/数据/风险/下周计划。");
        assertThat(parsed.getResources()).hasSize(1);
        assertThat(parsed.getResources().get(0).getName()).isEqualTo("weekly-report-template");
        assertThat(parsed.getResources().get(0).getContent()).contains("【本周成果】");
        assertThat(parsed.getScripts()).hasSize(1);
        assertThat(parsed.getScripts().get(0).getName()).isEqualTo("http-get");
        assertThat(parsed.isEnabled()).isTrue();
        assertThat(parsed.getSource()).isEqualTo(SkillSource.PRESET);
    }

    @Test
    void shouldRoundTripScriptsWithParams() throws Exception {
        SkillScript script = new SkillScript("http-get", "python3", "通过 HTTP 获取公开数据并解析",
                List.of(
                        new ScriptParam("url", "string", true, "目标 URL"),
                        new ScriptParam("timeout", "integer", false, "超时秒数")),
                "import sys\nimport urllib.request\nprint(urllib.request.urlopen(sys.argv[1]).read())");

        String json = objectMapper.writeValueAsString(script);
        SkillScript parsed = objectMapper.readValue(json, SkillScript.class);

        assertThat(parsed.getName()).isEqualTo("http-get");
        assertThat(parsed.getLanguage()).isEqualTo("python3");
        assertThat(parsed.getParams()).hasSize(2);
        assertThat(parsed.getParams().get(0).getName()).isEqualTo("url");
        assertThat(parsed.getParams().get(0).getType()).isEqualTo("string");
        assertThat(parsed.getParams().get(0).isRequired()).isTrue();
        assertThat(parsed.getParams().get(1).isRequired()).isFalse();
        assertThat(parsed.getContent()).contains("urllib.request");
    }

    @Test
    void shouldIgnoreLegacyBoundToolIdsField() throws Exception {
        // 向后兼容（CR-001）：含旧版 boundToolIds 字段的 JSON 反序列化不报错，scripts 正常解析
        String legacyJson = """
                {"id":"data-query-assistant","name":"数据查询助手","description":"查询技能","instruction":"指令",
                 "boundToolIds":["builtin:httpGet"],
                 "scripts":[{"name":"http-get","language":"shell","description":"HTTP 获取",
                    "params":[{"name":"url","type":"string","required":true,"description":"URL"}],
                    "content":"curl -s \\"$URL\\""}],
                 "enabled":true,"source":"PRESET"}
                """;

        SkillDefinition parsed = objectMapper.readValue(legacyJson, SkillDefinition.class);

        assertThat(parsed.getId()).isEqualTo("data-query-assistant");
        assertThat(parsed.getScripts()).hasSize(1);
        assertThat(parsed.getScripts().get(0).getName()).isEqualTo("http-get");
    }

    @Test
    void shouldUseDefaultsForMissingFields() throws Exception {
        // 序列化时缺省字段语义：enabled 缺省 true、source 缺省 CUSTOM（技术方案 Task-02）
        SkillDefinition skill = new SkillDefinition();
        skill.setId("custom-skill");
        skill.setName("自定义技能");
        assertThat(skill.isEnabled()).isTrue();
        assertThat(skill.getSource()).isEqualTo(SkillSource.CUSTOM);
        assertThat(skill.getResources()).isNull();
        assertThat(skill.getScripts()).isNull();
    }

    @Test
    void shouldValidateScriptLanguageWhitelist() {
        // 语言白名单（CR-001 Task-29：shell/python3）
        assertThat(ScriptLanguage.isAllowed("shell")).isTrue();
        assertThat(ScriptLanguage.isAllowed("python3")).isTrue();
        assertThat(ScriptLanguage.isAllowed("bash")).isTrue();
        assertThat(ScriptLanguage.isAllowed("ruby")).isFalse();
        assertThat(ScriptLanguage.isAllowed("node")).isFalse();
        assertThat(ScriptLanguage.isAllowed(null)).isFalse();
    }
}
