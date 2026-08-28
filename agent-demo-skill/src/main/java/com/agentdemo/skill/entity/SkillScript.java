package com.agentdemo.skill.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 技能自带脚本工具声明（CR-001 Task-29）
 * <p>
 * 业务含义：Skill 自带的预定义参数化脚本（技术方案 skill-script）。脚本语言受白名单
 * 约束（shell/python3，ScriptLanguage），Agent 只能按声明参数调用，不可执行任意代码
 * （AC-T03/S06）。脚本内容创建期经 SkillContentValidator 校验（AC-S01）。
 * </p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SkillScript {

    /** 脚本名（kebab-case；注册工具名 skill_{skillId}_{name}） */
    private String name;

    /** 脚本语言（白名单：shell/python3，ScriptLanguage.isAllowed） */
    private String language;

    /** 脚本工具描述（Agent 路由依据，须简洁） */
    private String description;

    /** 声明式参数（Agent 按此传参，脚本护栏校验） */
    private List<ScriptParam> params;

    /** 脚本内容（白名单语言可执行文本） */
    private String content;

    public SkillScript() {
    }

    public SkillScript(String name, String language, String description, List<ScriptParam> params, String content) {
        this.name = name;
        this.language = language;
        this.description = description;
        this.params = params;
        this.content = content;
    }
}
