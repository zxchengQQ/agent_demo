package com.agentdemo.skill.prompt;

import com.agentdemo.skill.config.SkillProperties;
import com.agentdemo.skill.entity.SkillDefinition;
import com.agentdemo.skill.entity.SkillResource;
import com.agentdemo.skill.session.SkillSessionManager;
import com.agentdemo.skill.store.SkillStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SkillPromptComposer 测试
 * <p>
 * 业务含义：验证技能提示词段组装（技术方案 Task-07，AC-T01/N01/S02/E04）：
 * 目录段仅含未激活技能元数据（无指令全文）、激活段含指令+资源全文且信任层包裹、
 * 空目录零输出、skill.enabled=false 全空、禁用/删除技能自动剔除。
 * </p>
 */
class SkillPromptComposerTest {

    private SkillStore store;
    private SkillSessionManager sessionManager;
    private SkillPromptComposer composer;

    @BeforeEach
    void setUp() {
        SkillProperties properties = new SkillProperties();
        properties.setStorageDir(Path.of(System.getProperty("java.io.tmpdir"), "skill-composer-" + System.nanoTime()).toString());
        store = new SkillStore(properties);
        sessionManager = new SkillSessionManager(store, properties);
        composer = new SkillPromptComposer(store, sessionManager, properties);
        store.create(skill("s1", "技能一", "描述一", "指令一", null));
        store.create(skill("s2", "技能二", "描述二", "指令二",
                List.of(new SkillResource("template", "模板内容"))));
    }

    private SkillDefinition skill(String id, String name, String desc, String instruction, List<SkillResource> resources) {
        SkillDefinition s = new SkillDefinition();
        s.setId(id);
        s.setName(name);
        s.setDescription(desc);
        s.setInstruction(instruction);
        s.setResources(resources);
        return s;
    }

    @Test
    void catalogShouldContainOnlyInactiveSkillMetadata() {
        String catalog = composer.composeCatalogSegment("sess");
        assertThat(catalog).isNotBlank();
        // 含两个未激活技能的元数据
        assertThat(catalog).contains("技能一").contains("描述一");
        assertThat(catalog).contains("技能二").contains("描述二");
        // 不含指令全文（渐进式披露，AC-T01）
        assertThat(catalog).doesNotContain("指令一").doesNotContain("指令二");
    }

    @Test
    void catalogShouldExcludeActivatedExcludedAndDisabled() {
        // 激活 s1 → 目录只含 s2
        sessionManager.activate("sess", "s1");
        String catalog = sessionManager.isManualMode("sess") ? "" : composer.composeCatalogSegment("sess");
        assertThat(catalog).doesNotContain("技能一");

        // 排除 s2 → 目录为空
        sessionManager.applyManualSelection("sess", List.of(), List.of("s2"));
        String catalogAfterExclude = composer.composeCatalogSegment("sess");
        assertThat(catalogAfterExclude).doesNotContain("技能二");

        // 禁用技能自动剔除
        sessionManager.applyManualSelection("sess", List.of(), List.of());
        SkillDefinition disabled = store.get("s1").orElseThrow();
        disabled.setEnabled(false);
        store.update(disabled);
        String catalogAfterDisable = composer.composeCatalogSegment("sess");
        assertThat(catalogAfterDisable).doesNotContain("技能一").contains("技能二");
    }

    @Test
    void activatedSegmentShouldContainInstructionAndResourcesWithTrustLayer() {
        sessionManager.activate("sess", "s2");
        String activated = composer.composeActivatedSegment("sess");
        assertThat(activated).isNotBlank();
        // 指令全文 + 资源内容
        assertThat(activated).contains("指令二").contains("模板内容");
        // 信任层标注（分层信任，AC-S02）
        assertThat(activated).contains("领域指令").contains("安全规则");
    }

    @Test
    void emptyCatalogWhenNoEnabledSkills() {
        // 禁用全部技能 → 无候选，目录空串（零 Token 开销）
        for (String id : List.of("s1", "s2")) {
            SkillDefinition s = store.get(id).orElseThrow();
            s.setEnabled(false);
            store.update(s);
        }
        String catalog = composer.composeCatalogSegment("empty-sess");
        assertThat(catalog).isEmpty();
    }

    @Test
    void catalogShouldBeEmptyWhenAllSkillsActive() {
        // 全部技能已激活 → 目录空串（无候选）
        sessionManager.activate("sess", "s1");
        sessionManager.activate("sess", "s2");
        String catalog = composer.composeCatalogSegment("sess");
        assertThat(catalog).isEmpty();
    }

    @Test
    void disabledFeatureShouldProduceEmptySegments() {
        SkillProperties disabledProps = new SkillProperties();
        disabledProps.setEnabled(false);
        SkillSessionManager disabledManager = new SkillSessionManager(store, disabledProps);
        SkillPromptComposer disabledComposer = new SkillPromptComposer(store, disabledManager, disabledProps);

        assertThat(disabledComposer.composeCatalogSegment("sess")).isEmpty();
        assertThat(disabledComposer.composeActivatedSegment("sess")).isEmpty();
    }

    @Test
    void deletedSkillShouldVanishFromActivatedSegment() {
        sessionManager.activate("sess", "s1");
        store.delete("s1");
        // 删除后激活段不含该技能（AC-E04 平滑退出）
        String activated = composer.composeActivatedSegment("sess");
        assertThat(activated).doesNotContain("指令一");
    }

    // ==================== Task-04: 附件文本生成（agent-context-engineering，AC-T01/S01） ====================

    @Test
    void catalogAttachmentShouldContainSkillMetadataWithoutInstruction() {
        String catalog = composer.composeCatalogAttachment("sess");
        assertThat(catalog).isNotBlank();
        assertThat(catalog).contains("技能一").contains("描述一");
        assertThat(catalog).contains("技能二").contains("描述二");
        // 渐进式披露：附件目录同样不含指令全文（AC-T01）
        assertThat(catalog).doesNotContain("指令一").doesNotContain("指令二");
    }

    @Test
    void skillInstructionAttachmentShouldContainInstructionAndTrustLayer() {
        SkillDefinition skill = store.get("s2").orElseThrow();
        String instruction = composer.composeSkillInstructionAttachment(skill);
        assertThat(instruction).isNotBlank();
        assertThat(instruction).contains("指令二").contains("模板内容");
        // 信任层标注保留在指令附件内（分层信任，AC-S01）
        assertThat(instruction).contains("领域指令").contains("安全规则");
    }

    @Test
    void statusAttachmentShouldReturnMessage() {
        String status = composer.composeStatusAttachment("技能 s2 已被用户排除");
        assertThat(status).isEqualTo("技能 s2 已被用户排除");
    }

    @Test
    void attachmentTextShouldBeEmptyWhenFeatureDisabled() {
        SkillProperties disabledProps = new SkillProperties();
        disabledProps.setEnabled(false);
        SkillSessionManager disabledManager = new SkillSessionManager(store, disabledProps);
        SkillPromptComposer disabledComposer = new SkillPromptComposer(store, disabledManager, disabledProps);

        assertThat(disabledComposer.composeCatalogAttachment("sess")).isEmpty();
        assertThat(disabledComposer.composeSkillInstructionAttachment(store.get("s1").orElse(null))).isEmpty();
        assertThat(disabledComposer.composeStatusAttachment("状态")).isEmpty();
    }
}
