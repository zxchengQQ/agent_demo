package com.agentdemo.skill.session;

import com.agentdemo.skill.config.SkillProperties;
import com.agentdemo.skill.entity.SkillDefinition;
import com.agentdemo.skill.store.SkillStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SkillSessionManager 测试
 * <p>
 * 业务含义：验证会话级技能激活态管理（技术方案 Task-06，AC-N03/N04/S05/M01/M03/M04/E04）：
 * 激活（auto/manual 来源、上限、排除、重复幂等）、手动指定三态、排除、会话隔离、超时清理、
 * 有效性实时过滤（禁用/删除技能自动退出）。
 * </p>
 */
class SkillSessionManagerTest {

    private SkillStore store;
    private SkillSessionManager manager;
    private int maxActive;

    @BeforeEach
    void setUp() {
        SkillProperties properties = new SkillProperties();
        properties.setStorageDir(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "skill-session-test-" + System.nanoTime()).toString());
        maxActive = properties.getMaxActiveSkills();
        store = new SkillStore(properties);
        // 预置 4 个启用技能供测试
        store.create(skill("s1", "技能一"));
        store.create(skill("s2", "技能二"));
        store.create(skill("s3", "技能三"));
        store.create(skill("s4", "技能四"));
        manager = new SkillSessionManager(store, properties);
    }

    private SkillDefinition skill(String id, String name) {
        SkillDefinition s = new SkillDefinition();
        s.setId(id);
        s.setName(name);
        s.setDescription("描述-" + name);
        s.setInstruction("指令-" + name);
        return s;
    }

    @Test
    void shouldActivateSkillWithSource() {
        SkillSessionManager.ActivationResult result = manager.activate("sess", "s1");
        assertThat(result.success()).isTrue();
        assertThat(result.source()).isEqualTo(SkillActivationSource.AUTO);
        assertThat(manager.getActiveSkillIds("sess")).containsExactly("s1");
    }

    @Test
    void shouldRejectExcludedSkill() {
        manager.applyManualSelection("sess", null, List.of("s1"));
        SkillSessionManager.ActivationResult result = manager.activate("sess", "s1");
        assertThat(result.success()).isFalse();
        assertThat(result.reason()).contains("排除");
    }

    @Test
    void shouldRejectUnknownOrDisabledSkill() {
        // 不存在的技能
        SkillSessionManager.ActivationResult unknown = manager.activate("sess", "ghost");
        assertThat(unknown.success()).isFalse();

        // 禁用的技能
        SkillDefinition disabled = store.get("s4").orElseThrow();
        disabled.setEnabled(false);
        store.update(disabled);
        SkillSessionManager.ActivationResult disabledResult = manager.activate("sess", "s4");
        assertThat(disabledResult.success()).isFalse();
    }

    @Test
    void shouldEnforceMaxActiveLimit() {
        manager.activate("sess", "s1");
        manager.activate("sess", "s2");
        manager.activate("sess", "s3");
        // 第 4 个被上限拒绝（AC-S05）
        SkillSessionManager.ActivationResult result = manager.activate("sess", "s4");
        assertThat(result.success()).isFalse();
        assertThat(result.reason()).contains("上限");
        assertThat(manager.getActiveSkillIds("sess")).hasSize(maxActive);
    }

    @Test
    void shouldBeIdempotentOnRepeatedActivation() {
        manager.activate("sess", "s1");
        SkillSessionManager.ActivationResult again = manager.activate("sess", "s1");
        assertThat(again.success()).isTrue();
        assertThat(manager.getActiveSkillIds("sess")).containsExactly("s1");
    }

    @Test
    void shouldApplyManualSelectionThreeStates() {
        // 非空：指定并激活（source=manual），自动匹配挂起
        manager.applyManualSelection("sess", List.of("s1", "s2"), null);
        assertThat(manager.getActiveSkillIds("sess")).containsExactlyInAnyOrder("s1", "s2");

        // 空数组：重置为自动（清空激活与排除）
        manager.applyManualSelection("sess", List.of(), null);
        assertThat(manager.getActiveSkillIds("sess")).isEmpty();

        // null：不变
        manager.activate("sess", "s1");
        manager.applyManualSelection("sess", null, null);
        assertThat(manager.getActiveSkillIds("sess")).containsExactly("s1");
    }

    @Test
    void shouldExcludeSkillFromActiveAndPreventReactivation() {
        manager.activate("sess", "s1");
        manager.exclude("sess", "s1");
        // 排除后从激活集移除
        assertThat(manager.getActiveSkillIds("sess")).doesNotContain("s1");
        // 后续激活被拒（AC-M04 排除后不复发）
        SkillSessionManager.ActivationResult result = manager.activate("sess", "s1");
        assertThat(result.success()).isFalse();
        assertThat(result.reason()).contains("排除");
    }

    @Test
    void shouldIsolateSessions() {
        manager.activate("sessA", "s1");
        manager.applyManualSelection("sessB", List.of("s2"), null);
        assertThat(manager.getActiveSkillIds("sessA")).containsExactly("s1");
        assertThat(manager.getActiveSkillIds("sessB")).containsExactly("s2");
    }

    @Test
    void shouldFilterDisabledOrDeletedSkillFromActive() {
        manager.activate("sess", "s1");
        manager.activate("sess", "s2");
        // 删除 s1 → getActiveSkillIds 实时剔除
        store.delete("s1");
        assertThat(manager.getActiveSkillIds("sess")).containsExactly("s2");
        // 禁用 s2 → 同样剔除（AC-E04 平滑退出）
        SkillDefinition disabled = store.get("s2").orElseThrow();
        disabled.setEnabled(false);
        store.update(disabled);
        assertThat(manager.getActiveSkillIds("sess")).isEmpty();
    }

    @Test
    void shouldCleanupExpiredSessions() {
        manager.activate("sess", "s1");
        assertThat(manager.getActiveSkillIds("sess")).containsExactly("s1");
        // 超时清理后会话状态消失（AC-M03 生命周期）
        manager.cleanupExpired(0L);
        assertThat(manager.getActiveSkillIds("sess")).isEmpty();
    }
}
