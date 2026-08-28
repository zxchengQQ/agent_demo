package com.agentdemo.skill.store;

import com.agentdemo.skill.config.SkillProperties;
import com.agentdemo.skill.entity.SkillDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SkillStore 标准目录结构迁移测试（CR-001 Task-30，AC-N08）
 * <p>
 * 业务含义：验证旧版单文件 JSON（data/skills/{id}.json）启动时自动迁移为
 * 标准目录结构（data/skills/{id}/SKILL.md + scripts/ + reference/），
 * 且迁移幂等（已迁移目录跳过，不覆盖用户编辑）、源文件备份。
 * </p>
 */
class SkillStoreMigrationTest {

    @TempDir
    Path tempDir;

    private SkillProperties properties;

    @BeforeEach
    void setUp() {
        properties = new SkillProperties();
        properties.setStorageDir(tempDir.toString());
    }

    @Test
    void shouldMigrateLegacyJsonToDirectoryStructure() throws Exception {
        // 旧版单文件 JSON（含资源与脚本，boundToolIds 兼容忽略）
        String legacy = """
                {"id":"legacy-skill","name":"旧技能","description":"描述","instruction":"指令内容",
                 "resources":[{"name":"r1","content":"资源内容"}],
                 "scripts":[{"name":"s1","language":"shell","description":"脚本描述","params":[],"content":"echo hi"}],
                 "enabled":true,"source":"CUSTOM"}
                """;
        Files.writeString(tempDir.resolve("legacy-skill.json"), legacy);

        SkillStore store = new SkillStore(properties);

        SkillDefinition skill = store.get("legacy-skill").orElseThrow();
        assertThat(skill.getName()).isEqualTo("旧技能");
        assertThat(skill.getInstruction()).isEqualTo("指令内容");
        assertThat(skill.getResources()).hasSize(1);
        assertThat(skill.getResources().get(0).getContent()).isEqualTo("资源内容");
        assertThat(skill.getScripts()).hasSize(1);
        assertThat(skill.getScripts().get(0).getContent()).isEqualTo("echo hi");

        // 标准目录结构生成
        Path dir = tempDir.resolve("legacy-skill");
        assertThat(Files.exists(dir.resolve("SKILL.md"))).isTrue();
        // 源文件备份保留（回滚方案）
        assertThat(Files.exists(tempDir.resolve("legacy-skill.json.bak"))).isTrue();
    }

    @Test
    void shouldNotReMigrateWhenDirectoryExists() throws Exception {
        // 已存在标准目录（用户已编辑/已迁移）时，旧 json 不覆盖
        SkillStore store = new SkillStore(properties);
        SkillDefinition existing = new SkillDefinition();
        existing.setId("x");
        existing.setName("目录已有");
        existing.setDescription("描述");
        existing.setInstruction("指令");
        store.create(existing);

        // 模拟残留的旧版 json（内容与目录不同）
        String legacy = """
                {"id":"x","name":"旧版新名字","description":"描述","instruction":"指令","enabled":true,"source":"CUSTOM"}
                """;
        Files.writeString(tempDir.resolve("x.json"), legacy);

        SkillStore reloaded = new SkillStore(properties);
        // 目录优先，不迁移覆盖
        assertThat(reloaded.get("x").orElseThrow().getName()).isEqualTo("目录已有");
    }

    @Test
    void shouldPersistAndReloadDirectoryStructureWithScriptsAndResources() throws Exception {
        SkillStore store = new SkillStore(properties);
        SkillDefinition skill = new SkillDefinition();
        skill.setId("with-assets");
        skill.setName("带资源技能");
        skill.setDescription("描述");
        skill.setInstruction("指令");
        skill.setResources(java.util.List.of(new com.agentdemo.skill.entity.SkillResource("tpl", "模板内容")));
        skill.setScripts(java.util.List.of(new com.agentdemo.skill.entity.SkillScript(
                "http-get", "shell", "脚本描述",
                java.util.List.of(new com.agentdemo.skill.entity.ScriptParam("url", "string", true, "URL")),
                "curl -s \"$URL\"")));
        store.create(skill);

        Path dir = tempDir.resolve("with-assets");
        assertThat(Files.exists(dir.resolve("SKILL.md"))).isTrue();
        assertThat(Files.exists(dir.resolve("reference").resolve("tpl"))).isTrue();
        assertThat(Files.exists(dir.resolve("scripts").resolve("http-get.sh"))).isTrue();

        // 重建加载完整
        SkillStore reloaded = new SkillStore(properties);
        SkillDefinition loaded = reloaded.get("with-assets").orElseThrow();
        assertThat(loaded.getName()).isEqualTo("带资源技能");
        assertThat(loaded.getResources().get(0).getContent()).isEqualTo("模板内容");
        assertThat(loaded.getScripts().get(0).getContent()).isEqualTo("curl -s \"$URL\"");
        assertThat(loaded.getScripts().get(0).getParams().get(0).getName()).isEqualTo("url");
    }
}
