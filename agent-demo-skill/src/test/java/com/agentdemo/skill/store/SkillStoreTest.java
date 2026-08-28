package com.agentdemo.skill.store;

import com.agentdemo.skill.config.SkillProperties;
import com.agentdemo.skill.entity.SkillDefinition;
import com.agentdemo.skill.entity.SkillSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SkillStore 测试
 * <p>
 * 业务含义：验证技能 CRUD 与 JSON 文件持久化（技术方案 Task-03，skill-store）。
 * 覆盖：CRUD、重启不丢（重建 Store 重新加载）、损坏 JSON 降级、重名拒绝。
 * </p>
 */
class SkillStoreTest {

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

    private SkillDefinition sampleSkill(String id, String name) {
        SkillDefinition skill = new SkillDefinition();
        skill.setId(id);
        skill.setName(name);
        skill.setDescription("描述-" + name);
        skill.setInstruction("指令-" + name);
        skill.setSource(SkillSource.CUSTOM);
        return skill;
    }

    @Test
    void shouldCreateListGetUpdateDelete() {
        SkillDefinition created = store.create(sampleSkill("s1", "技能一"));
        assertThat(created.getId()).isEqualTo("s1");

        assertThat(store.list()).hasSize(1);
        assertThat(store.get("s1")).isPresent();
        assertThat(store.get("s1").get().getName()).isEqualTo("技能一");

        SkillDefinition updated = store.update(sampleSkill("s1", "技能一改"));
        assertThat(updated.getName()).isEqualTo("技能一改");
        assertThat(store.get("s1").get().getName()).isEqualTo("技能一改");

        store.delete("s1");
        assertThat(store.list()).isEmpty();
        assertThat(store.get("s1")).isEmpty();
    }

    @Test
    void shouldPersistAcrossRestart() throws Exception {
        store.create(sampleSkill("persist", "持久化技能"));

        // 模拟重启：用同一目录重建 Store，应从文件重新加载
        SkillStore reloaded = new SkillStore(properties);
        assertThat(reloaded.get("persist")).isPresent();
        assertThat(reloaded.get("persist").get().getName()).isEqualTo("持久化技能");
        // 文件确实落盘在 storageDir 下（标准目录结构，CR-001 Task-30）
        assertThat(Files.exists(tempDir.resolve("persist").resolve("SKILL.md"))).isTrue();
    }

    @Test
    void shouldSkipCorruptJsonFile() throws Exception {
        store.create(sampleSkill("good", "正常技能"));
        // 写入损坏的 JSON 文件（旧版格式，迁移时解析失败跳过，WARN 降级，不阻断启动）
        Files.writeString(tempDir.resolve("corrupt.json"), "{ not valid json ");

        SkillStore reloaded = new SkillStore(properties);
        // 正常技能仍加载，损坏文件被跳过
        assertThat(reloaded.get("good")).isPresent();
        assertThat(reloaded.get("corrupt")).isEmpty();
        assertThat(reloaded.list()).hasSize(1);
    }

    @Test
    void shouldRejectDuplicateId() {
        store.create(sampleSkill("dup", "技能A"));
        // 重复 id 创建应被拒绝（名称唯一性语义：id 唯一）
        SkillDefinition duplicate = sampleSkill("dup", "技能B");
        try {
            store.create(duplicate);
            org.assertj.core.api.Assertions.fail("重复 id 创建应抛异常");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains("dup");
        }
    }

    @Test
    void shouldListAllWithStatus() {
        store.create(sampleSkill("e1", "启用技能"));
        SkillDefinition disabled = sampleSkill("e2", "禁用技能");
        disabled.setEnabled(false);
        store.create(disabled);

        assertThat(store.list()).hasSize(2);
        assertThat(store.getEnabledSkills()).hasSize(1);
        assertThat(store.getEnabledSkills().get(0).getId()).isEqualTo("e1");
    }

    @Test
    void shouldSeedPresetByContentIdempotent() throws Exception {
        String json = """
                {"id":"preset-a","name":"预置A","description":"描述A","instruction":"指令A","source":"PRESET","enabled":true}
                """;
        // 首次播种生效
        assertThat(store.seedPreset("preset-a.json", json)).isTrue();
        assertThat(store.get("preset-a")).isPresent();
        // 目录结构落盘
        assertThat(Files.exists(tempDir.resolve("preset-a").resolve("SKILL.md"))).isTrue();

        // 幂等：目标已存在则不覆盖（第二次播种跳过）
        String changed = """
                {"id":"preset-a","name":"预置A改","description":"描述A","instruction":"指令A","source":"PRESET","enabled":true}
                """;
        assertThat(store.seedPreset("preset-a.json", changed)).isFalse();
        assertThat(store.get("preset-a").get().getName()).isEqualTo("预置A");

        // 损坏内容跳过（WARN 降级）
        assertThat(store.seedPreset("preset-bad.json", "{ not valid json ")).isFalse();
        assertThat(store.get("preset-bad")).isEmpty();
    }
}
