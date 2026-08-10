package com.agentdemo.web.dto;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * MCP Server 配置条件校验注解
 * <p>
 * 业务含义：类级别校验，确保 transport 字段与对应传输方式专用字段的关联性：
 * - transport=STDIO 时 command 必须非空非空白（AC-023）
 * - transport=SSE 时 url 必须为 http/https 协议（AC-024）
 * </p>
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = McpServerConfigValidator.class)
@Documented
public @interface ValidMcpServerConfig {

    String message() default "MCP Server 配置不合法";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
