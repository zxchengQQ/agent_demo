package com.agentdemo.skill.tool;

import com.agentdemo.memory.shortterm.ChatMemoryManager;
import com.agentdemo.memory.shortterm.CompressingChatMemory;
import com.agentdemo.skill.config.SkillProperties;
import com.agentdemo.skill.entity.SkillDefinition;
import com.agentdemo.skill.entity.SkillResource;
import com.agentdemo.skill.prompt.SkillPromptComposer;
import com.agentdemo.skill.session.SkillSessionManager;
import com.agentdemo.skill.store.SkillStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SkillLoadTool 测试
 * <p>
 * 业务含义：验证 loadSkill 工具体的观察值生成（技术方案 Task-10，AC-T01/N01/E01/M04/S05）：
 * 成功返回指令要点+资源摘要、不存在返回候选列表、上限返回引导、排除返回告知、读取异常降级。
 * 同时验证注入 activate 逻辑（同步路径复用）。
 * </p>
 */
class SkillLoadToolTest {

    private SkillStore store;
    private SkillSessionManager sessionManager;
    private SkillLoadTool tool;
    private SkillProperties properties;

    @BeforeEach
    void setUp() {
        properties = new SkillProperties();
        properties.setStorageDir(Path.of(System.getProperty("java.io.tmpdir"), "skill-loadtool-" + System.nanoTime()).toString());
        store = new SkillStore(properties);
        sessionManager = new SkillSessionManager(store, properties);
        tool = new SkillLoadTool(store, sessionManager, properties);

        SkillDefinition s1 = new SkillDefinition();
        s1.setId("s1");
        s1.setName("技能一");
        s1.setDescription("描述一");
        s1.setInstruction("指令一");
        store.create(s1);

        SkillDefinition s2 = new SkillDefinition();
        s2.setId("s2");
        s2.setName("技能二");
        s2.setDescription("描述二");
        s2.setInstruction("指令二");
        s2.setResources(List.of(new SkillResource("template", "模板内容")));
        store.create(s2);

        store.create(skill("s3", "技能三"));
        store.create(skill("s4", "技能四"));
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
    void shouldActivateAndReturnInstructionSummary() {
        String result = tool.loadSkill("sess", "s1");
        assertThat(result).contains("技能一");
        assertThat(result).contains("指令一");
        // 激活态已写入（同步路径复用）
        assertThat(sessionManager.getActiveSkillIds("sess")).containsExactly("s1");
    }

    @Test
    void shouldReturnResourceSummaryWhenPresent() {
        String result = tool.loadSkill("sess", "s2");
        // 观察值含资源名称摘要（正文由激活段注入，观察值保持精简）
        assertThat(result).contains("template");
        assertThat(result).contains("参考资源");
    }

    @Test
    void shouldReturnCandidateListWhenSkillNotFound() {
        String result = tool.loadSkill("sess", "ghost");
        assertThat(result).contains("不存在");
        // 候选列表引导自纠
        assertThat(result).contains("s1");
        assertThat(sessionManager.getActiveSkillIds("sess")).isEmpty();
    }

    @Test
    void shouldReturnLimitGuidanceWhenMaxActiveReached() {
        tool.loadSkill("sess", "s1");
        tool.loadSkill("sess", "s2");
        tool.loadSkill("sess", "s3");
        String result = tool.loadSkill("sess", "s4");
        assertThat(result).contains("上限");
        assertThat(sessionManager.getActiveSkillIds("sess")).hasSize(properties.getMaxActiveSkills());
    }

    @Test
    void shouldReturnExclusionNoticeWhenExcluded() {
        sessionManager.exclude("sess", "s1");
        String result = tool.loadSkill("sess", "s1");
        assertThat(result).contains("排除");
        assertThat(sessionManager.getActiveSkillIds("sess")).isEmpty();
    }

    @Test
    void shouldReturnDisabledNoticeWhenSkillDisabled() {
        SkillDefinition disabled = store.get("s1").orElseThrow();
        disabled.setEnabled(false);
        store.update(disabled);
        String result = tool.loadSkill("sess", "s1");
        assertThat(result).contains("禁用");
        assertThat(sessionManager.getActiveSkillIds("sess")).isEmpty();
    }

    @Test
    void shouldNotExposeImplementationDetails() {
        String result = tool.loadSkill("sess", "ghost");
        // 观察值不含存储路径/配置文件细节（脱敏）
        assertThat(result).doesNotContain(".json").doesNotContain("storage");
    }

    // ==================== Task-05: 附件单点写入（agent-context-engineering，AC-T01/N01） ====================

    private ChatMemoryManager memoryManager;
    private SkillPromptComposer promptComposer;
    private SkillLoadTool attachmentTool;

    private void initAttachmentTool() {
        memoryManager = mock(ChatMemoryManager.class);
        promptComposer = mock(SkillPromptComposer.class);
        when(promptComposer.composeSkillInstructionAttachment(store.get("s1").orElseThrow()))
                .thenReturn("【技能 s1：技能一】\n指令一");
        attachmentTool = new SkillLoadTool(store, sessionManager, properties, memoryManager, promptComposer);
    }

    @Test
    void shouldWriteInstructionAttachmentOnActivation() {
        initAttachmentTool();
        attachmentTool.loadSkill("sess", "s1");

        verify(memoryManager).addAttachment("sess",
                CompressingChatMemory.AttachmentType.SKILL_INSTRUCTION, "【技能 s1：技能一】\n指令一");
    }

    @Test
    void shouldNotWriteAttachmentWhenActivationFails() {
        initAttachmentTool();
        attachmentTool.loadSkill("sess", "ghost");
        attachmentTool.loadSkill("sess", "s4"); // 上限

        verify(memoryManager, never()).addAttachment(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shouldNotFailWhenAttachmentTextBlank() {
        initAttachmentTool();
        when(promptComposer.composeSkillInstructionAttachment(store.get("s1").orElseThrow())).thenReturn("");
        String result = attachmentTool.loadSkill("sess", "s1");
        assertThat(result).contains("技能一");
        verify(memoryManager, never()).addAttachment(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shouldTolerateMissingMemoryDependency() {
        // 旧三参构造（未装配 memory/附件能力）激活正常，不抛异常（兼容测试与退化路径）
        String result = tool.loadSkill("sess", "s1");
        assertThat(result).contains("技能一");
    }
}
