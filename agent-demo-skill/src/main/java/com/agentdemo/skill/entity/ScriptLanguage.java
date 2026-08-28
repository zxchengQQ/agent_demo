package com.agentdemo.skill.entity;

import java.util.Set;

/**
 * 脚本语言白名单（CR-001 Task-29）
 * <p>
 * 业务含义：Skill 自带脚本的可执行语言约束（技术方案决策 9，AC-S01/S06）。
 * 白名单为单一事实来源：SkillContentValidator（创建期）与 SkillScriptExecutor（执行期）
 * 均经 isAllowed 判定，保证只执行受支持语言脚本。
 * </p>
 */
public final class ScriptLanguage {

    /** 允许执行的脚本语言 */
    public static final Set<String> ALLOWED = Set.of("shell", "bash", "python3");

    private ScriptLanguage() {
    }

    public static boolean isAllowed(String language) {
        return language != null && ALLOWED.contains(language.trim().toLowerCase());
    }
}
