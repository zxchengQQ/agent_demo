package com.agentdemo.tools.permission;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 工具权限配置属性
 * <p>
 * 业务含义：集中管理工具权限模块的可配置参数。配置前缀 tools.permission：
 * </p>
 * <ul>
 *     <li>file-path：显式权限配置的 JSON 持久化文件路径（默认 data/tool-permissions.json）</li>
 *     <li>enabled：权限功能总开关（默认 true；false 时过滤退化为 NONE、执行检查跳过，即回滚到现状行为）</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "tools.permission")
public class ToolPermissionProperties {

    /** 显式权限配置 JSON 文件路径（相对项目根目录） */
    private String filePath = "data/tool-permissions.json";

    /** 权限功能总开关（false = 功能降级回滚，等同现状行为） */
    private boolean enabled = true;
}
