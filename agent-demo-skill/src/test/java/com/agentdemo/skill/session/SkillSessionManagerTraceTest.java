package com.agentdemo.skill.session;

import com.agentdemo.observability.TraceCollector;
import com.agentdemo.skill.config.SkillProperties;
import com.agentdemo.skill.entity.SkillDefinition;
import com.agentdemo.skill.entity.SkillScript;
import com.agentdemo.skill.store.SkillStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Skill 激活埋点测试（CR-001 Task-22，AC-N09/E05）
 * <p>
 * 业务含义：验证 SkillSessionManager 激活成功/被拒（activate）与手动批量选择
 * （applyManualSelection）均上报 SkillActivationEvent（skillId/skillName/source/绑定工具/拒绝原因）。
 * </p>
 */
class SkillSessionManagerTraceTest {

    private SkillProperties properties;
    private SkillStore store;
    private TraceCollector collector;
    private SkillSessionManager manager;

    @BeforeEach
    void setUp() {
        properties = new SkillProperties();
        properties.setStorageDir(Path.of(System.getProperty("java.io.tmpdir"),
                "skill-trace-test-" + System.nanoTime()).toString());
        properties.setMaxActiveSkills(2);
        store = new SkillStore(properties);
        collector = mock(TraceCollector.class);
        manager = new SkillSessionManager(store, properties, collector);
        SkillDefinition s1 = new SkillDefinition();
        s1.setId("s1");
        s1.setName("数据分析");
        s1.setEnabled(true);
        SkillScript script = new SkillScript();
        script.setName("analyze");
        s1.setScripts(List.of(script));
        store.create(s1);
        SkillDefinition s2 = new SkillDefinition();
        s2.setId("s2");
        s2.setName("报告生成");
        s2.setEnabled(true);
        store.create(s2);
        SkillDefinition s3 = new SkillDefinition();
        s3.setId("s3");
        s3.setName("翻译");
        s3.setEnabled(true);
        store.create(s3);
    }

    private TraceCollector.SkillActivationEvent capturedLast() {
        ArgumentCaptor<TraceCollector.SkillActivationEvent> captor =
                ArgumentCaptor.forClass(TraceCollector.SkillActivationEvent.class);
        verify(collector).recordSkillActivation(captor.capture());
        return captor.getValue();
    }

    @Test
    void activate_success_reportsEvent_withSkillInfo() {
        manager.activate("sess", "s1");

        TraceCollector.SkillActivationEvent e = capturedLast();
        assertThat(e.skillId()).isEqualTo("s1");
        assertThat(e.skillName()).isEqualTo("数据分析");
        assertThat(e.source()).isEqualTo("AUTO");
        assertThat(e.boundTools()).contains("analyze");
        assertThat(e.success()).isTrue();
        assertThat(e.rejectedReason()).isNull();
    }

    @Test
    void activate_rejected_reportsReason() {
        // 上限拒绝：被拒激活留痕（AC-N09）——maxActiveSkills=2，激活 s1/s2 填满后 s3 被拒
        manager.activate("sess", "s1");
        manager.activate("sess", "s2");
        SkillSessionManager.ActivationResult rejected = manager.activate("sess", "s3");

        assertThat(rejected.success()).isFalse();
        ArgumentCaptor<TraceCollector.SkillActivationEvent> captor =
                ArgumentCaptor.forClass(TraceCollector.SkillActivationEvent.class);
        verify(collector, times(3)).recordSkillActivation(captor.capture());
        List<TraceCollector.SkillActivationEvent> all = captor.getAllValues();
        TraceCollector.SkillActivationEvent last = all.get(all.size() - 1);
        assertThat(last.success()).isFalse();
        assertThat(last.rejectedReason()).contains("上限");
    }

    @Test
    void applyManualSelection_reportsManualEvents() {
        manager.applyManualSelection("sess", List.of("s1", "s2"), null);

        ArgumentCaptor<TraceCollector.SkillActivationEvent> captor =
                ArgumentCaptor.forClass(TraceCollector.SkillActivationEvent.class);
        verify(collector, times(2)).recordSkillActivation(captor.capture());
        assertThat(captor.getAllValues()).allMatch(e -> "MANUAL".equals(e.source()));
        assertThat(captor.getAllValues()).extracting(TraceCollector.SkillActivationEvent::skillId)
                .containsExactlyInAnyOrder("s1", "s2");
    }

    @Test
    void activate_unknownSkill_reportsRejection() {
        manager.activate("sess", "ghost");

        TraceCollector.SkillActivationEvent e = capturedLast();
        assertThat(e.success()).isFalse();
        assertThat(e.rejectedReason()).contains("不存在");
    }
}
