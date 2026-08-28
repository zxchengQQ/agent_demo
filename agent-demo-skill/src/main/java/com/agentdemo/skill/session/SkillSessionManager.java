package com.agentdemo.skill.session;

import com.agentdemo.skill.config.SkillProperties;
import com.agentdemo.skill.entity.SkillDefinition;
import com.agentdemo.skill.store.SkillStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级技能激活态管理器
 * <p>
 * 业务含义：管理技能激活的会话级状态（技术方案 skill-session，AC-N03/N04/S05/M01/M03/M04/E04）：
 * 自主激活（auto）/手动指定（manual）/排除集合，以及并发上限校验与超时清理。
 * </p>
 * <p>
 * 关键设计：
 * 1. 激活态独立于对话消息窗口存储（AC-M01 跨轮持续，不受 FIFO 淘汰影响），按 sessionId 隔离（AC-M03）
 * 2. 上限硬约束：激活数 ≥ maxActiveSkills 时拒绝并返回原因（AC-S05）
 * 3. 排除语义：排除后从激活集移除并阻止后续自主激活（AC-M04）
 * 4. 有效性实时过滤：读取时校验技能存在且启用，禁用/删除自动退出激活集（AC-E04）
 * 5. 手动指定（applyManualSelection 非空）时仅保留手动技能；空数组重置自动（与 SessionToolResolver 三态同构）
 * </p>
 */
@Component
public class SkillSessionManager {

    private static final Logger log = LoggerFactory.getLogger(SkillSessionManager.class);

    /** 默认超时时间（30 分钟，与 SessionManager/HumanInteractionManager 一致） */
    private static final long DEFAULT_TIMEOUT_MS = 30 * 60 * 1000L;

    private final SkillStore skillStore;
    private final SkillProperties properties;

    /** 会话技能状态（key: sessionId） */
    private final ConcurrentHashMap<String, SessionSkillState> sessionStates = new ConcurrentHashMap<>();

    /** 会话最后活跃时间（key: sessionId → epoch millis），用于超时清理 */
    private final ConcurrentHashMap<String, Long> lastActive = new ConcurrentHashMap<>();

    /**
     * 单会话技能状态（可变：激活/排除集合原地增删）
     *
     * @param activeSkillIds   激活技能 id（有序去重，容量 ≤ maxActiveSkills）
     * @param manualMode       手动指定模式（true 时仅 manualSkillIds 生效，自动匹配挂起）
     * @param manualSkillIds   手动指定技能 id
     * @param excludedSkillIds 用户排除技能 id（阻止自主激活）
     */
    public static class SessionSkillState {

        final LinkedHashSet<String> activeSkillIds = new LinkedHashSet<>();
        boolean manualMode = false;
        final LinkedHashSet<String> manualSkillIds = new LinkedHashSet<>();
        final LinkedHashSet<String> excludedSkillIds = new LinkedHashSet<>();

        public static SessionSkillState empty() {
            return new SessionSkillState();
        }

        public LinkedHashSet<String> activeSkillIds() {
            return activeSkillIds;
        }

        public boolean manualMode() {
            return manualMode;
        }

        public LinkedHashSet<String> manualSkillIds() {
            return manualSkillIds;
        }

        public LinkedHashSet<String> excludedSkillIds() {
            return excludedSkillIds;
        }
    }

    /**
     * 激活结果
     *
     * @param success    是否激活成功
     * @param source     激活来源（成功时有效）
     * @param reason     失败原因（失败时有效：上限/排除/不存在/禁用/未启用）
     * @param skillId    技能 id
     */
    public record ActivationResult(boolean success, SkillActivationSource source, String reason, String skillId) {

        public static ActivationResult ok(String skillId, SkillActivationSource source) {
            return new ActivationResult(true, source, null, skillId);
        }

        public static ActivationResult fail(String skillId, String reason) {
            return new ActivationResult(false, null, reason, skillId);
        }
    }

    public SkillSessionManager(SkillStore skillStore, SkillProperties properties) {
        this.skillStore = skillStore;
        this.properties = properties;
    }

    /**
     * 自主激活技能（Agent 匹配后调用，source=AUTO）
     * <p>
     * 业务含义：LLM 调 loadSkill 的落点。校验存在/启用/排除/上限/重复幂等。
     * </p>
     *
     * @param sessionId 会话 ID
     * @param skillId   技能 id
     * @return 激活结果
     */
    public ActivationResult activate(String sessionId, String skillId) {
        if (!properties.isEnabled()) {
            return ActivationResult.fail(skillId, "技能能力未启用");
        }
        SessionSkillState state = state(sessionId);
        touch(sessionId);

        // 存在性与启用校验
        var skillOpt = skillStore.get(skillId);
        if (skillOpt.isEmpty()) {
            return ActivationResult.fail(skillId, "技能不存在");
        }
        if (!skillOpt.get().isEnabled()) {
            return ActivationResult.fail(skillId, "技能已被禁用");
        }

        // 排除校验（AC-M04）
        if (state.excludedSkillIds().contains(skillId)) {
            return ActivationResult.fail(skillId, "该技能已被用户排除，不可激活");
        }

        // 手动指定模式下挂起自主激活（AC-N03：仅手动技能生效）
        if (state.manualMode() && !state.manualSkillIds().contains(skillId)) {
            return ActivationResult.fail(skillId, "当前为手动指定模式，仅可激活用户指定的技能");
        }

        // 重复激活幂等
        if (state.activeSkillIds().contains(skillId)) {
            return ActivationResult.ok(skillId, SkillActivationSource.AUTO);
        }

        // 上限校验（AC-S05）
        if (state.activeSkillIds().size() >= properties.getMaxActiveSkills()) {
            return ActivationResult.fail(skillId,
                    "已达并发激活上限（" + properties.getMaxActiveSkills() + "），需要用户选择替换");
        }

        state.activeSkillIds().add(skillId);
        log.info("技能激活: sessionId={}, skillId={}, source=AUTO", sessionId, skillId);
        return ActivationResult.ok(skillId, SkillActivationSource.AUTO);
    }

    /**
     * 应用会话级手动指定与排除选择
     * <p>
     * 业务含义：会话级选择器提交（ChatRequest.skills/excludedSkills）的落点（技术方案 1.4）。
     * skills 语义：null=不变 / 空数组=重置自动（清空激活与手动）/ 非空=手动指定并激活（source=MANUAL）。
     * excludedSkills：null=不变 / 非空=设置排除集合。
     * </p>
     *
     * @param sessionId      会话 ID
     * @param skills         手动指定技能 id 列表（null/空/非空 三态）
     * @param excludedSkills 排除技能 id 列表（null=不变 / 非空=设置）
     */
    public void applyManualSelection(String sessionId, List<String> skills, List<String> excludedSkills) {
        if (!properties.isEnabled()) {
            return;
        }
        SessionSkillState state = state(sessionId);
        touch(sessionId);

        if (skills != null) {
            if (skills.isEmpty()) {
                // 空数组：重置为自动模式，清空激活与手动指定
                state.activeSkillIds().clear();
                state.manualSkillIds().clear();
                state.manualMode = false;
                log.info("技能选择重置为自动模式: sessionId={}", sessionId);
            } else {
                // 非空：手动指定模式，仅保留这些技能
                state.manualSkillIds().clear();
                state.manualSkillIds().addAll(skills);
                state.activeSkillIds().clear();
                state.manualMode = true;
                for (String skillId : skills) {
                    // 仅激活存在的启用技能（无效 id 静默忽略，管理侧校验另行负责）
                    var opt = skillStore.get(skillId);
                    if (opt.isPresent() && opt.get().isEnabled()
                            && state.activeSkillIds().size() < properties.getMaxActiveSkills()) {
                        state.activeSkillIds().add(skillId);
                    }
                }
                log.info("技能手动指定: sessionId={}, skills={}", sessionId, skills);
            }
        }

        if (excludedSkills != null) {
            state.excludedSkillIds().clear();
            state.excludedSkillIds().addAll(excludedSkills);
            // 排除即时生效：从激活集移除被排除技能
            state.activeSkillIds().removeIf(state.excludedSkillIds()::contains);
            state.manualSkillIds().removeIf(state.excludedSkillIds()::contains);
            log.info("技能排除设置: sessionId={}, excludedSkills={}", sessionId, excludedSkills);
        }
    }

    /**
     * 用户排除技能（前端徽标"×"操作）
     *
     * @param sessionId 会话 ID
     * @param skillId   技能 id
     */
    public void exclude(String sessionId, String skillId) {
        if (skillId == null) {
            return;
        }
        SessionSkillState state = state(sessionId);
        touch(sessionId);
        state.excludedSkillIds().add(skillId);
        state.activeSkillIds().remove(skillId);
        state.manualSkillIds().remove(skillId);
        log.info("技能被用户排除: sessionId={}, skillId={}", sessionId, skillId);
    }

    /**
     * 查询会话当前激活技能 id（实时有效性过滤：禁用/删除技能自动退出，AC-E04）
     */
    public List<String> getActiveSkillIds(String sessionId) {
        if (sessionId == null) {
            return List.of();
        }
        SessionSkillState state = sessionStates.get(sessionId);
        if (state == null) {
            return List.of();
        }
        // 过滤已删除/已禁用技能（实时有效性）
        List<String> valid = new ArrayList<>();
        for (String id : state.activeSkillIds()) {
            var opt = skillStore.get(id);
            if (opt.isPresent() && opt.get().isEnabled()) {
                valid.add(id);
            }
        }
        // 惰性剔除失效 id，避免累积
        if (valid.size() != state.activeSkillIds().size()) {
            state.activeSkillIds().retainAll(valid);
        }
        return valid;
    }

    /**
     * 查询技能定义（委托 SkillStore，供工具合并/提示词组装等场景便捷读取）
     *
     * @param skillId 技能 id
     * @return 技能定义（不存在返回 Optional.empty）
     */
    public Optional<SkillDefinition> getSkillDefinition(String skillId) {
        return skillStore.get(skillId);
    }

    /**
     * 解析技能 id（支持 id 或名称精确匹配，供 loadSkill 拦截器反查实际 id）
     *
     * @param nameOrId 技能 id 或名称
     * @return 实际技能 id；无法解析返回 null
     */
    public String resolveSkillId(String nameOrId) {
        if (nameOrId == null || nameOrId.isBlank()) {
            return null;
        }
        var byId = skillStore.get(nameOrId.trim());
        if (byId.isPresent()) {
            return byId.get().getId();
        }
        return skillStore.getEnabledSkills().stream()
                .filter(s -> nameOrId.trim().equals(s.getName()))
                .map(SkillDefinition::getId)
                .findFirst()
                .orElse(null);
    }

    /**
     * 查询会话排除技能 id
     */
    public List<String> getExcludedSkillIds(String sessionId) {
        if (sessionId == null) {
            return List.of();
        }
        SessionSkillState state = sessionStates.get(sessionId);
        return state == null ? List.of() : new ArrayList<>(state.excludedSkillIds());
    }

    /**
     * 查询会话是否为手动指定模式
     */
    public boolean isManualMode(String sessionId) {
        SessionSkillState state = sessionStates.get(sessionId);
        return state != null && state.manualMode();
    }

    /**
     * 清空会话技能状态（会话删除时调用）
     */
    public void clearSession(String sessionId) {
        sessionStates.remove(sessionId);
        lastActive.remove(sessionId);
    }

    /**
     * 定时清理超时会话状态（对齐 SessionManager 30 分钟超时）
     */
    @Scheduled(fixedRate = 5 * 60 * 1000L)
    public void cleanupExpired() {
        cleanupExpired(DEFAULT_TIMEOUT_MS);
    }

    /**
     * 清理超过指定时间未活跃的会话状态
     *
     * @param timeoutMillis 超时时间（毫秒）
     */
    public void cleanupExpired(long timeoutMillis) {
        long now = System.currentTimeMillis();
        long cutoff = now - timeoutMillis;
        sessionStates.keySet().removeIf(sessionId -> {
            Long last = lastActive.get(sessionId);
            // <= 保证 timeoutMillis=0 时立即清理（测试/异常路径语义）
            return last != null && last <= cutoff;
        });
        lastActive.entrySet().removeIf(e -> e.getValue() <= cutoff);
    }

    // ==================== 内部 ====================

    private SessionSkillState state(String sessionId) {
        return sessionStates.computeIfAbsent(sessionId, k -> SessionSkillState.empty());
    }

    private void touch(String sessionId) {
        lastActive.put(sessionId, System.currentTimeMillis());
    }
}
