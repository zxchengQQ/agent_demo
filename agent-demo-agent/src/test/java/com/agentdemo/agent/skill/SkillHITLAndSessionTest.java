package com.agentdemo.agent.skill;

import com.agentdemo.skill.config.SkillProperties;
import com.agentdemo.skill.entity.SkillDefinition;
import com.agentdemo.skill.session.SkillActivationSource;
import com.agentdemo.skill.session.SkillSessionManager;
import com.agentdemo.skill.store.SkillStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HITL 与会话态集成测试（技术方案 Task-26，AC-N03/S05/M03/M04/E03/E04/H01/H02）
 * <p>
 * 业务含义：手动指定优先不被替换（N03）、上限引导（S05）、会话隔离（M03）、
 * 排除不复发（M04）、运行中删除平滑退出（E04）、对话内越权忽略（E03）、
 * skill.enabled=false 全链路退化（6.6）。
 * </p>
 */
class SkillHITLAndSessionTest {

    private SkillStore store;
    private SkillSessionManager manager;
    private SkillProperties properties;

    @BeforeEach
    void setUp() {
        properties = new SkillProperties();
        properties.setStorageDir(Path.of(System.getProperty("java.io.tmpdir"), "skill-hitl-" + System.nanoTime()).toString());
        store = new SkillStore(properties);
        manager = new SkillSessionManager(store, properties);
        for (int i = 1; i <= 4; i++) {
            SkillDefinition s = new SkillDefinition();
            s.setId("s" + i);
            s.setName("技能" + i);
            s.setDescription("描述" + i);
            s.setInstruction("指令" + i);
            store.create(s);
        }
    }

    @Test
    void manualSkillsTakePrecedenceAndAreNotReplacedByAutoMatching() {
        // 手动指定 s1（AC-N03：手动指定优先）
        manager.applyManualSelection("sess", List.of("s1"), null);
        assertThat(manager.getActiveSkillIds("sess")).containsExactly("s1");

        // 手动模式下自主激活 s2 被拒（自动匹配挂起，不应被替换）
        SkillSessionManager.ActivationResult result = manager.activate("sess", "s2");
        assertThat(result.success()).isFalse();
        assertThat(manager.getActiveSkillIds("sess")).containsExactly("s1");
    }

    @Test
    void shouldGuideUserAtConcurrencyLimit() {
        // 达到上限（3）后第 4 个被拒（AC-S05），激活集不超限
        manager.activate("sess", "s1");
        manager.activate("sess", "s2");
        manager.activate("sess", "s3");
        SkillSessionManager.ActivationResult result = manager.activate("sess", "s4");
        assertThat(result.success()).isFalse();
        assertThat(result.reason()).contains("上限");
        assertThat(manager.getActiveSkillIds("sess")).hasSize(3);
    }

    @Test
    void shouldIsolateSessions() {
        manager.activate("sessA", "s1");
        manager.applyManualSelection("sessB", List.of("s2"), null);
        assertThat(manager.getActiveSkillIds("sessA")).containsExactly("s1");
        assertThat(manager.getActiveSkillIds("sessB")).containsExactly("s2");
        // 会话 A 排除不影响会话 B（AC-M03）
        manager.exclude("sessA", "s1");
        assertThat(manager.getActiveSkillIds("sessB")).containsExactly("s2");
    }

    @Test
    void excludedSkillDoesNotReactivate() {
        manager.activate("sess", "s1");
        manager.exclude("sess", "s1");
        // 排除后不复发（AC-M04）
        SkillSessionManager.ActivationResult result = manager.activate("sess", "s1");
        assertThat(result.success()).isFalse();
        assertThat(result.reason()).contains("排除");
    }

    @Test
    void deletedSkillExitsSmoothly() {
        manager.activate("sess", "s1");
        // 运行中删除技能（AC-E04 平滑退出）
        store.delete("s1");
        assertThat(manager.getActiveSkillIds("sess")).isEmpty();
    }

    @Test
    void disabledSkillExitsSmoothly() {
        manager.activate("sess", "s1");
        SkillDefinition disabled = store.get("s1").orElseThrow();
        disabled.setEnabled(false);
        store.update(disabled);
        assertThat(manager.getActiveSkillIds("sess")).isEmpty();
    }

    @Test
    void noConfigChangePathFromConversation() {
        // 对话通道无配置变更方法（AC-E03 结构隔离）——SkillSessionManager 仅暴露激活/排除，
        // 无 create/update/delete；配置变更仅经管理 API（SkillController）。
        // 验证：对话侧能触达的操作仅激活态，无法改技能定义
        manager.activate("sess", "s1");
        // 技能定义未被对话操作修改
        assertThat(store.get("s1").orElseThrow().getDescription()).isEqualTo("描述1");
    }

    @Test
    void disabledFeatureDegradesToBaseline() {
        SkillProperties disabledProps = new SkillProperties();
        disabledProps.setEnabled(false);
        SkillSessionManager disabledManager = new SkillSessionManager(store, disabledProps);

        // 能力禁用：激活被拒（退化，技术方案 6.6）
        SkillSessionManager.ActivationResult result = disabledManager.activate("sess", "s1");
        assertThat(result.success()).isFalse();
        assertThat(manager.getActiveSkillIds("sess")).isEmpty();
    }

    @Test
    void activationSourceIsRecorded() {
        // 自主激活 source=AUTO（AC-S04 来源透出）
        SkillSessionManager.ActivationResult auto = manager.activate("sess", "s1");
        assertThat(auto.source()).isEqualTo(SkillActivationSource.AUTO);
    }

    @Test
    void manualSelectionSetsManualMode() {
        manager.applyManualSelection("sess", List.of("s1"), null);
        assertThat(manager.isManualMode("sess")).isTrue();
        // 空数组重置自动（AC-N03 恢复自主匹配）
        manager.applyManualSelection("sess", List.of(), null);
        assertThat(manager.isManualMode("sess")).isFalse();
    }
}
