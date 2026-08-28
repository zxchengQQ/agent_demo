package com.agentdemo.skill.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SkillProperties 配置绑定测试
 * <p>
 * 业务含义：验证技能域默认值与 application.yml 覆盖绑定（技术方案 Task-01）。
 * 默认值保证零配置开箱即用，覆盖路径验证配置项可调。
 * </p>
 */
class SkillPropertiesTest {

    @Test
    void defaultsShouldBeSane() {
        SkillProperties props = new SkillProperties();
        // 默认启用技能能力（平台第 10 能力域开箱即用）
        assertThat(props.isEnabled()).isTrue();
        // 并发激活上限：需求 AC-S05 硬约束，默认 3
        assertThat(props.getMaxActiveSkills()).isEqualTo(3);
        // 存储目录默认 data/skills（ToolPermissionService data/*.json 同构）
        assertThat(props.getStorageDir()).isEqualTo("data/skills");
    }

    @Test
    void shouldApplyOverrideValues() {
        SkillProperties props = new SkillProperties();
        props.setEnabled(false);
        props.setMaxActiveSkills(5);
        props.setStorageDir("/tmp/custom-skills");
        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getMaxActiveSkills()).isEqualTo(5);
        assertThat(props.getStorageDir()).isEqualTo("/tmp/custom-skills");
    }
}
