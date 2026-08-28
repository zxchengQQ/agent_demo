package com.agentdemo.skill.session;

/**
 * 技能激活来源
 * <p>
 * 业务含义：区分技能激活的触发方——AUTO 为 Agent 自主匹配激活（LLM 调 loadSkill），
 * MANUAL 为用户手动指定（会话级选择器）。用于激活事件透出与前端展示来源（AC-S04）。
 * </p>
 */
public enum SkillActivationSource {

    /** Agent 自主匹配激活 */
    AUTO,

    /** 用户手动指定 */
    MANUAL
}
