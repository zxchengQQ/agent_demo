package com.agentdemo.tools.permission;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 内置工具默认权限注解
 * <p>
 * 业务含义：标注在工具类上声明该工具的默认权限等级（ALLOW/ASK/DENY），
 * 供 {@link com.agentdemo.tools.registry.ToolRegistry} 扫描注册时登记为默认等级。
 * 与项目 @Tool 声明式风格一致，权限语义内聚在工具类定义处。
 * </p>
 * <p>
 * 未标注此注解的内置工具按保守策略默认 ASK（技术方案 §3.2）。
 * 动态工具（知识库代理 / MCP）由注册时按类别兜底，无需也不可标注此注解。
 * </p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface DefaultToolPermission {

    /** 默认权限等级 */
    ToolPermissionLevel value();
}
