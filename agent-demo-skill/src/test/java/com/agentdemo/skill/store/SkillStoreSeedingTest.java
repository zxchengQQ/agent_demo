package com.agentdemo.skill.store;

import com.agentdemo.skill.config.SkillProperties;
import com.agentdemo.skill.entity.SkillDefinition;
import com.agentdemo.skill.entity.SkillScript;
import com.agentdemo.skill.entity.SkillSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SkillStore 预置技能播种测试
 * <p>
 * 业务含义：验证预置技能从 classpath 幂等播种（技术方案 Task-05，AC-N05/N06）。
 * 覆盖：首次播种生成 3 个预置技能、二次播种不覆盖用户修改、预置三形态字段正确。
 * </p>
 */
class SkillStoreSeedingTest {

    @TempDir
    Path tempDir;

    private SkillProperties properties;
    private SkillStore store;

    @BeforeEach
    void setUp() {
        properties = new SkillProperties();
        properties.setStorageDir(tempDir.toString());
        store = new SkillStore(properties);
    }

    @Test
    void shouldSeedPresetsFromClasspath() throws Exception {
        Path presetsDir = Path.of(
                "src/main/resources/skills").toAbsolutePath();

        store.seedPresets(presetsDir);

        // 3 个预置技能就位且启用
        List<SkillDefinition> enabled = store.getEnabledSkills();
        assertThat(enabled).hasSize(3);
        List<String> ids = enabled.stream().map(SkillDefinition::getId).toList();
        assertThat(ids).contains("weekly-report-expert", "tech-doc-writer", "data-query-assistant");

        // 三形态字段正确：纯指令型（无资源无工具）
        SkillDefinition weekly = store.get("weekly-report-expert").orElseThrow();
        assertThat(weekly.getSource()).isEqualTo(SkillSource.PRESET);
        assertThat(weekly.getResources()).isNotEmpty();
        assertThat(weekly.getScripts()).isNull();
        assertThat(weekly.getInstruction()).isNotBlank();

        // 指令+资源型
        SkillDefinition techDoc = store.get("tech-doc-writer").orElseThrow();
        assertThat(techDoc.getResources()).isNotEmpty();
        assertThat(techDoc.getResources().get(0).getContent()).contains("# {文档标题}");

        // 指令+自带脚本工具型（CR-001 Task-31：data-query-assistant 带 http-get 脚本）
        SkillDefinition dataQuery = store.get("data-query-assistant").orElseThrow();
        assertThat(dataQuery.getScripts()).hasSize(1);
        SkillScript httpGet = dataQuery.getScripts().get(0);
        assertThat(httpGet.getName()).isEqualTo("http-get");
        assertThat(httpGet.getLanguage()).isEqualTo("shell");
        assertThat(httpGet.getContent()).contains("curl");
        assertThat(httpGet.getParams()).isNotEmpty();
        assertThat(httpGet.getParams().get(0).getName()).isEqualTo("url");
        assertThat(httpGet.getParams().get(0).isRequired()).isTrue();
    }

    @Test
    void shouldBeIdempotentAndNotOverwriteUserEdits() throws Exception {
        Path presetsDir = Path.of("src/main/resources/skills").toAbsolutePath();
        store.seedPresets(presetsDir);

        // 用户修改预置技能
        SkillDefinition edited = store.get("weekly-report-expert").orElseThrow();
        edited.setName("用户自定义周报");
        store.update(edited);

        // 二次播种不应覆盖用户修改
        store.seedPresets(presetsDir);
        assertThat(store.get("weekly-report-expert").orElseThrow().getName()).isEqualTo("用户自定义周报");
    }

    @Test
    void shouldWritePresetFilesToDisk() throws Exception {
        Path presetsDir = Path.of("src/main/resources/skills").toAbsolutePath();
        store.seedPresets(presetsDir);

        // 标准目录结构落盘（CR-001 Task-30）
        assertThat(Files.exists(tempDir.resolve("weekly-report-expert").resolve("SKILL.md"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("data-query-assistant").resolve("SKILL.md"))).isTrue();
    }
}
