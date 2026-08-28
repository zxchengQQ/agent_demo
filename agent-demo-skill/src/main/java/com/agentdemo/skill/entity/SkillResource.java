package com.agentdemo.skill.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 技能只读参考资源
 * <p>
 * 业务含义：Skill 携带的领域参考内容（如周报模板/文档模板），激活时作为资源注入
 * （需求 AC-N05）。资源为只读文本，无执行语义（能力禁区：不携带可执行脚本）。
 * </p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SkillResource {

    /** 资源名称（唯一标识） */
    private String name;

    /** 资源内容（文本，创建期受 Token 上限约束） */
    private String content;

    public SkillResource() {
    }

    public SkillResource(String name, String content) {
        this.name = name;
        this.content = content;
    }
}
