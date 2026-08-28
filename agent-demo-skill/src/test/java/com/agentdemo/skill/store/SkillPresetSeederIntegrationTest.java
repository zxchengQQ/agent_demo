package com.agentdemo.skill.store;

import com.agentdemo.skill.config.SkillProperties;
import com.agentdemo.skill.entity.SkillDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 预置播种集成验证（阶段一集成验证）
 * <p>
 * 业务含义：从真实 classpath 资源目录（src/main/resources/skills）播种，验证
 * 3 个预置技能开机可用且三形态正确（技术方案 Task-05 阶段完成标准）。
 * 等价于 SkillPresetSeeder 在启动时执行的播种路径。
 * </p>
 */
class SkillPresetSeederIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldSeedPresetsFromRealClasspathDir() {
        // 定位真实 classpath 预置目录（与 SkillPresetSeeder.run 等价）
        var resource = getClass().getClassLoader().getResource("skills");
        assertThat(resource).as("classpath skills 目录应存在").isNotNull();

        SkillProperties properties = new SkillProperties();
        properties.setStorageDir(tempDir.toString());
        SkillStore store = new SkillStore(properties);

        // 与 ApplicationRunner 相同的播种入口
        new SkillPresetSeeder(store).run(null);

        List<SkillDefinition> enabled = store.getEnabledSkills();
        assertThat(enabled).hasSize(3);
        assertThat(enabled.stream().map(SkillDefinition::getId))
                .containsExactlyInAnyOrder("weekly-report-expert", "tech-doc-writer", "data-query-assistant");

        // 预置技能内容校验通过（不触发校验器阻断，保证出厂内容合规）
        SkillDefinition weekly = store.get("weekly-report-expert").orElseThrow();
        assertThat(weekly.getInstruction()).isNotBlank();
        assertThat(weekly.getResources()).isNotEmpty();
        assertThat(weekly.getSource()).isEqualTo(com.agentdemo.skill.entity.SkillSource.PRESET);
    }
}
