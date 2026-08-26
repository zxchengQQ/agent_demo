package com.agentdemo.web.dto;

import lombok.Data;

/**
 * 工具权限更新请求 DTO
 * <p>
 * 业务含义：管理页调整工具权限等级（allow/ask/deny）的请求体（AC-N01）。
 * permission 为小写字符串，经 ToolPermissionLevel.parse 解析校验，非法值由 Controller 转参数错误。
 * </p>
 */
@Data
public class UpdateToolPermissionRequest {

    /**
     * 目标权限等级（allow=放行 / ask=需确认 / deny=禁止）
     */
    private String permission;
}
