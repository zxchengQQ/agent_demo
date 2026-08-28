package com.agentdemo.skill.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Skill 能力域配置属性
 * <p>
 * 业务含义：集中管理技能域的可配置参数——能力总开关、会话并发激活上限、存储目录。
 * 通过 application.yml 中 skill.* 前缀注入。
 * </p>
 * <p>
 * 设计要点：
 * 1. enabled 总开关：false 时全链路零影响退化（技术方案 6.6），Composer 输出空串、工具不注册
 * 2. maxActiveSkills：并发激活上限硬约束（需求 AC-S05，默认 3）
 * 3. storageDir：技能定义持久化目录（ToolPermissionService data/*.json 同构）
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "skill")
public class SkillProperties {

    /** 技能能力总开关，false 时全链路退化（技术方案 6.6） */
    private boolean enabled = true;

    /** 单会话并发激活技能上限（需求 AC-S05） */
    private int maxActiveSkills = 3;

    /** 技能定义持久化目录（每技能一个 JSON 文件） */
    private String storageDir = "data/skills";
}
