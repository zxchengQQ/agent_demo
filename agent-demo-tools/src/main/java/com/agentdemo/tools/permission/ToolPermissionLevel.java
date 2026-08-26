package com.agentdemo.tools.permission;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 工具权限等级枚举
 * <p>
 * 业务含义：每个工具在执行前被划分为三个等级之一——
 * ALLOW（放行，直接执行）/ ASK（需确认，暂停等待用户批准）/ DENY（禁止，加载期过滤 + 执行期兜底拒绝）。
 * </p>
 * <p>
 * 字符串表示采用小写（allow/ask/deny），与持久化 JSON 文件格式及管理 API 的取值约定一致。
 * </p>
 */
public enum ToolPermissionLevel {

    /** 放行：无需确认直接执行（只读安全工具、知识库检索等） */
    ALLOW("allow"),

    /** 需确认：执行前暂停并向用户请求批准（有副作用/敏感访问工具） */
    ASK("ask"),

    /** 禁止：加载期不注入 LLM，执行期兜底拒绝（禁区工具） */
    DENY("deny");

    private final String code;

    ToolPermissionLevel(String code) {
        this.code = code;
    }

    /**
     * 序列化取值：JSON 文件中存小写字符串（allow/ask/deny）
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * 从字符串解析权限等级（大小写不敏感，容忍首尾空白）
     *
     * @param value 字符串值，如 "allow" / "ASK" / " Deny "
     * @return 对应枚举；null 输入返回 null；非法值抛 {@link IllegalArgumentException}
     */
    public static ToolPermissionLevel parse(String value) {
        if (value == null) {
            return null;
        }
        for (ToolPermissionLevel level : values()) {
            if (level.code.equalsIgnoreCase(value.trim())) {
                return level;
            }
        }
        throw new IllegalArgumentException("无效的权限等级: " + value + "，仅支持 allow/ask/deny");
    }

    /**
     * 反序列化入口：JSON 文件中的小写字符串还原为枚举
     */
    @JsonCreator
    public static ToolPermissionLevel fromCode(String code) {
        return parse(code);
    }
}
