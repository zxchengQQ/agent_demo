package com.agentdemo.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 创建/编辑技能请求 DTO
 * <p>
 * 业务含义：前端调用 POST/PUT /api/skill 接口的请求参数。
 * 字段与 SkillDefinition 对应，但管理页可控字段为元数据/指令/资源/自带脚本（不含 id/source 派生字段）。
 * 内容安全校验（AC-S01）在 Service 层 SkillContentValidator 执行（含脚本语言白名单/参数 schema）。
 * </p>
 */
@Data
public class SkillRequest {

    /**
     * 技能 id（仅创建时必填，kebab-case；编辑时经路径传 id）
     */
    @Pattern(regexp = "^[a-z0-9-]+$", message = "技能 id 仅允许小写字母、数字和横线")
    @Size(min = 1, max = 50, message = "技能 id 长度需在 1-50 字符之间")
    private String id;

    /**
     * 技能名称（必填）
     */
    @NotBlank(message = "技能名称不能为空")
    @Size(max = 50, message = "技能名称长度不能超过 50 字符")
    private String name;

    /**
     * 技能描述（目录段元数据，须简洁便于 Agent 匹配）
     */
    @NotBlank(message = "技能描述不能为空")
    @Size(max = 200, message = "技能描述长度不能超过 200 字符")
    private String description;

    /**
     * 领域指令全文
     */
    @Size(max = 4000, message = "技能指令长度不能超过 4000 字符")
    private String instruction;

    /**
     * 只读参考资源（名称+内容）
     */
    private List<ResourceItem> resources;

    /**
     * 自带脚本工具（CR-001：替代原 boundToolIds 绑定系统工具）
     */
    private List<ScriptItem> scripts;

    /**
     * 资源项
     */
    @Data
    public static class ResourceItem {
        private String name;
        private String content;
    }

    /**
     * 脚本声明项（CR-001）
     */
    @Data
    public static class ScriptItem {
        private String name;
        private String language;
        private String description;
        private List<ParamItem> params;
        private String content;
    }

    /**
     * 脚本参数项（CR-001）
     */
    @Data
    public static class ParamItem {
        private String name;
        private String type;
        private boolean required;
        private String description;
    }
}
