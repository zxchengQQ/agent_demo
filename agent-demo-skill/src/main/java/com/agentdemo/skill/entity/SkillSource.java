package com.agentdemo.skill.entity;

/**
 * 技能来源
 * <p>
 * 业务含义：区分预置技能（平台出厂自带）与用户自定义技能，用于管理页标注来源与播种语义。
 * </p>
 */
public enum SkillSource {

    /** 预置技能：classpath 播种，用户可编辑但不影响重新播种语义 */
    PRESET,

    /** 用户自定义技能：管理页创建 */
    CUSTOM
}
