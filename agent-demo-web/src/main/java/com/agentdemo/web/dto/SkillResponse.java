package com.agentdemo.web.dto;

import com.agentdemo.skill.entity.SkillDefinition;
import lombok.Data;

import java.util.List;

/**
 * 技能响应 DTO
 * <p>
 * 业务含义：技能管理页列表/详情展示，含元数据、指令摘要、资源、自带脚本与状态。
 * 不含实现细节（存储路径/校验规则内部实现），与脱敏原则一致。
 * </p>
 */
@Data
public class SkillResponse {

    private String id;
    private String name;
    private String description;
    private String instruction;
    private List<ResourceResponse> resources;
    private List<ScriptResponse> scripts;
    private boolean enabled;
    private String source;

    /** 校验警告（创建/编辑时密钥类警告透传） */
    private List<String> warnings;

    /**
     * 资源响应项
     */
    @Data
    public static class ResourceResponse {
        private String name;
        private String content;
    }

    /**
     * 脚本响应项（CR-001）
     */
    @Data
    public static class ScriptResponse {
        private String name;
        private String language;
        private String description;
        private List<ParamResponse> params;
        private String content;
    }

    /**
     * 脚本参数响应项（CR-001）
     */
    @Data
    public static class ParamResponse {
        private String name;
        private String type;
        private boolean required;
        private String description;
    }

    /**
     * 从领域实体构造响应
     */
    public static SkillResponse from(SkillDefinition skill) {
        SkillResponse resp = new SkillResponse();
        resp.setId(skill.getId());
        resp.setName(skill.getName());
        resp.setDescription(skill.getDescription());
        resp.setInstruction(skill.getInstruction());
        if (skill.getResources() != null) {
            resp.setResources(skill.getResources().stream().map(r -> {
                ResourceResponse rr = new ResourceResponse();
                rr.setName(r.getName());
                rr.setContent(r.getContent());
                return rr;
            }).toList());
        }
        if (skill.getScripts() != null) {
            resp.setScripts(skill.getScripts().stream().map(s -> {
                ScriptResponse sr = new ScriptResponse();
                sr.setName(s.getName());
                sr.setLanguage(s.getLanguage());
                sr.setDescription(s.getDescription());
                sr.setContent(s.getContent());
                if (s.getParams() != null) {
                    sr.setParams(s.getParams().stream().map(p -> {
                        ParamResponse pr = new ParamResponse();
                        pr.setName(p.getName());
                        pr.setType(p.getType());
                        pr.setRequired(p.isRequired());
                        pr.setDescription(p.getDescription());
                        return pr;
                    }).toList());
                }
                return sr;
            }).toList());
        }
        resp.setEnabled(skill.isEnabled());
        resp.setSource(skill.getSource() == null ? null : skill.getSource().name());
        return resp;
    }
}
