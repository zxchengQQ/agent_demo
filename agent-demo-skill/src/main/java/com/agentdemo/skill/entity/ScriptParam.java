package com.agentdemo.skill.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 脚本声明式参数（CR-001 Task-29）
 * <p>
 * 业务含义：Skill 自带脚本的入参契约（技术方案 3.1）。Agent 按 params 声明传参，
 * SkillScriptExecutor 执行前校验类型/必填/取值范围（防 shell 注入，AC-T03/S06）。
 * </p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScriptParam {

    /** 参数名（唯一，脚本内以环境变量/位置参数注入） */
    private String name;

    /** 参数类型：string / integer / number / boolean */
    private String type;

    /** 是否必填 */
    private boolean required;

    /** 参数说明（Agent 传参依据） */
    private String description;

    public ScriptParam() {
    }

    public ScriptParam(String name, String type, boolean required, String description) {
        this.name = name;
        this.type = type;
        this.required = required;
        this.description = description;
    }
}
