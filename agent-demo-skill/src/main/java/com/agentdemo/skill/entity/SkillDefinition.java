package com.agentdemo.skill.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 技能定义实体
 * <p>
 * 业务含义：一个 Skill 能力包的完整定义（技术方案 skill-entity）：
 * 元数据（id/name/description）+ 领域指令 + 只读参考资源 + 自带脚本工具。
 * Agent 平时仅感知元数据（渐进式披露），匹配后经 loadSkill 加载完整内容。
 * </p>
 * <p>
 * 安全约束：instruction 与 resources 为"用户提供的领域指令"，创建期经 SkillContentValidator
 * 校验（AC-S01）；激活后以信任层标注注入（AC-S02），优先级低于平台安全规则。
 * scripts 为 Skill 自带预定义参数化脚本（白名单语言），激活时动态注册为工具，受脚本护栏管控（AC-T03/S06）。
 * </p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SkillDefinition {

    /** 技能唯一标识（kebab-case，如 weekly-report） */
    private String id;

    /** 技能名称（展示用） */
    private String name;

    /** 技能描述（目录段元数据，Agent 依据此判断是否匹配，须简洁） */
    private String description;

    /** 领域指令全文（激活后注入，创建期受 Token 上限约束） */
    private String instruction;

    /** 只读参考资源列表（指令+资源型技能携带，如模板） */
    private List<SkillResource> resources;

    /** 自带脚本工具列表（CR-001：替代原 boundToolIds 绑定系统工具；激活时动态注册 skill_{id}_{script}） */
    private List<SkillScript> scripts;

    /** 是否启用（false 时不出现在目录段、不可被激活，AC-E04） */
    private boolean enabled = true;

    /** 技能来源（PRESET 预置 / CUSTOM 自定义） */
    private SkillSource source = SkillSource.CUSTOM;
}
