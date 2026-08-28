package com.agentdemo.skill.tool;

import com.agentdemo.memory.shortterm.ChatMemoryManager;
import com.agentdemo.memory.shortterm.CompressingChatMemory;
import com.agentdemo.skill.config.SkillProperties;
import com.agentdemo.skill.entity.SkillDefinition;
import com.agentdemo.skill.prompt.SkillPromptComposer;
import com.agentdemo.skill.session.SkillSessionManager;
import com.agentdemo.skill.store.SkillStore;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * loadSkill 工具（同步路径执行体 + 流式路径拦截共享逻辑）
 * <p>
 * 业务含义：Agent 渐进式披露的核心工具（技术方案 skill-tool，决策 4 双形态）：
 * 同步路径（AiServices 内部循环）经 ToolExecutor 反射执行本方法；流式路径（HITLReActStream）
 * 按工具名拦截后复用 SkillSessionManager.activate 逻辑（不执行本方法体）。
 * </p>
 * <p>
 * 观察值契约（供 LLM 理解下一步动作，脱敏不暴露实现细节）：
 * - 成功：技能名称 + 指令要点 + 资源摘要（激活态已写入 SkillSessionManager）
 * - 失败：技能不存在 → 候选列表引导自纠；已达上限 → 引导 askUser 选择；已排除/禁用 → 明确告知
 * </p>
 * <p>
 * 权限：@DefaultPermission(ALLOW) 注解默认放行 + ToolPermissionService 特判豁免（决策 6，
 * 激活为只读可回滚动作，无外部副作用，不应触发确认卡片）。
 * </p>
 */
@Component
@com.agentdemo.tools.permission.DefaultToolPermission(com.agentdemo.tools.permission.ToolPermissionLevel.ALLOW)
public class SkillLoadTool {

    private static final Logger log = LoggerFactory.getLogger(SkillLoadTool.class);

    private final SkillStore skillStore;
    private final SkillSessionManager sessionManager;
    private final SkillProperties properties;
    private final ChatMemoryManager memoryManager;
    private final SkillPromptComposer promptComposer;

    /**
     * Spring 装配构造（技能指令附件写入）
     * <p>
     * 业务含义：多构造器场景下必须标注 @Autowired，否则 Spring 因无默认构造器导致启动失败。
     * </p>
     */
    @org.springframework.beans.factory.annotation.Autowired
    public SkillLoadTool(SkillStore skillStore, SkillSessionManager sessionManager,
                         SkillProperties properties, ChatMemoryManager memoryManager,
                         SkillPromptComposer promptComposer) {
        this.skillStore = skillStore;
        this.sessionManager = sessionManager;
        this.properties = properties;
        this.memoryManager = memoryManager;
        this.promptComposer = promptComposer;
    }

    /**
     * 兼容构造（未装配 memory/技能附件能力：附件写入跳过，激活逻辑不变）
     */
    public SkillLoadTool(SkillStore skillStore, SkillSessionManager sessionManager, SkillProperties properties) {
        this(skillStore, sessionManager, properties, null, null);
    }

    /**
     * 加载技能（激活并返回指令要点作为观察值）
     *
     * @param sessionId 会话 ID（由 ReAct 循环以 MemoryId 语义传入）
     * @param skillName 技能名称或 id（须来自目录段清单）
     * @return 观察值文本（成功=指令要点；失败=状态说明与引导）
     */
    @Tool("加载并激活一个技能，获取其领域指令与参考资源。"
            + "适用场景：用户请求明确指向技能目录中的某个技能时（如\"帮我写周报\"对应周报技能），调用后可按该技能指令执行。"
            + "不适用场景：技能已激活、已被用户排除、请求与任何技能都不匹配、或已达并发激活上限时不要调用；"
            + "只需查询知识/信息而非执行领域流程时用知识库检索工具，不要用本工具。"
            + "参数 skillName 为技能的 id 或名称，必须来自技能目录清单。"
            + "返回：成功返回已加载技能的名称与指令要点；技能不存在时返回可用技能列表供重新选择；"
            + "已达上限时返回提示并要求用 askUser 询问用户是否替换。")
    public String loadSkill(String sessionId, String skillName) {
        if (!properties.isEnabled()) {
            return "技能能力未启用，请以通用能力回答。";
        }
        SkillDefinition skill = resolveSkill(skillName);
        if (skill == null) {
            return "技能不存在: " + skillName + "。可用技能: " + listAvailableSkillIds() + "。请基于用户请求判断是否需要重新选择技能。";
        }
        if (!skill.isEnabled()) {
            return "技能已被禁用，无法加载。请告知用户该技能暂不可用。";
        }
        SkillSessionManager.ActivationResult result = sessionManager.activate(sessionId, skill.getId());
        if (!result.success()) {
            log.info("loadSkill 激活被拒: sessionId={}, skillId={}, 原因={}", sessionId, skillName, result.reason());
            if (result.reason().contains("上限")) {
                return "已达并发激活上限（" + properties.getMaxActiveSkills() + " 个）。当前已激活技能无法新增，"
                        + "请使用 askUser 询问用户是否保持现有技能或替换其一，再继续任务。";
            }
            if (result.reason().contains("排除")) {
                return "该技能已被用户排除，不可激活。请尊重用户选择，不要再次尝试激活它。";
            }
            if (result.reason().contains("手动")) {
                return "当前为手动指定模式，仅可激活用户指定技能。请勿自主激活其他技能。";
            }
            return "技能激活失败: " + result.reason();
        }
        log.info("loadSkill 激活成功: sessionId={}, skillId={}", sessionId, skill.getId());
        writeInstructionAttachment(sessionId, skill);
        return buildObservation(skill);
    }

    /**
     * 指令附件单点写入（agent-context-engineering Task-05，AC-T01/N01）
     * <p>
     * 业务含义：激活成功的同一时点，将技能指令全文以 SKILL_INSTRUCTION 附件写入会话记忆
     * （emit-once）。同步路径（AiServices 直执行）与流式路径（拦截复用本方法）共用此入口，
     * 一处代码覆盖双路径。附件缺失仅损失跨轮持久性，当轮 Observation 仍有效，不阻断主流程。
     * </p>
     */
    private void writeInstructionAttachment(String sessionId, SkillDefinition skill) {
        if (memoryManager == null || promptComposer == null) {
            return;
        }
        try {
            String attachmentText = promptComposer.composeSkillInstructionAttachment(skill);
            if (attachmentText == null || attachmentText.isBlank()) {
                return;
            }
            memoryManager.addAttachment(sessionId,
                    CompressingChatMemory.AttachmentType.SKILL_INSTRUCTION, attachmentText);
            log.info("技能指令附件已写入会话记忆: sessionId={}, skillId={}", sessionId, skill.getId());
        } catch (Exception e) {
            log.warn("技能指令附件写入失败（仅损失跨轮持久性）: sessionId={}, skillId={}, 原因={}",
                    sessionId, skill.getId(), e.getMessage());
        }
    }

    /**
     * 解析技能：先按 id 精确匹配，再按名称精确匹配（目录段展示 id，容错名称）
     */
    private SkillDefinition resolveSkill(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return null;
        }
        var byId = skillStore.get(skillName.trim());
        if (byId.isPresent()) {
            return byId.get();
        }
        return skillStore.getEnabledSkills().stream()
                .filter(s -> skillName.trim().equals(s.getName()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 构造成功观察值：技能名称 + 指令要点 + 资源摘要（脱敏，不含存储细节）
     */
    private String buildObservation(SkillDefinition skill) {
        StringBuilder sb = new StringBuilder();
        sb.append("技能「").append(skill.getName()).append("」已加载。");
        if (skill.getInstruction() != null && !skill.getInstruction().isBlank()) {
            sb.append("指令：").append(summarize(skill.getInstruction(), 200));
        }
        if (skill.getResources() != null && !skill.getResources().isEmpty()) {
            sb.append(" 参考资源：");
            sb.append(skill.getResources().stream()
                    .filter(r -> r != null && r.getName() != null)
                    .map(r -> r.getName())
                    .collect(Collectors.joining("、")));
        }
        return sb.toString();
    }

    /**
     * 指令要点摘要（截断控制，防止观察值膨胀）
     */
    private String summarize(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "…";
    }

    private String listAvailableSkillIds() {
        return skillStore.getEnabledSkills().stream()
                .map(SkillDefinition::getId)
                .collect(Collectors.joining(", "));
    }
}
