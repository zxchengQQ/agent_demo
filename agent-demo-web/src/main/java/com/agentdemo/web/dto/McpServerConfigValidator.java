package com.agentdemo.web.dto;

import com.agentdemo.mcp.config.McpTransportType;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * MCP Server 配置条件校验器
 * <p>
 * 业务含义：校验 transport 与对应字段的关联性：
 * - STDIO 模式 command 必须非空非空白（AC-023）
 * - SSE 模式 url 必须为 http/https 协议（AC-024）
 * - HTTP 模式 url 必须为 http/https 协议（同 SSE 的 url 校验规则）
 * </p>
 */
public class McpServerConfigValidator implements ConstraintValidator<ValidMcpServerConfig, CreateMcpServerRequest> {

    @Override
    public boolean isValid(CreateMcpServerRequest req, ConstraintValidatorContext context) {
        if (req == null || req.getTransport() == null) {
            // transport 为 null 由 @NotNull 处理，此处跳过
            return true;
        }

        McpTransportType transport = req.getTransport();
        boolean valid = true;

        if (transport == McpTransportType.STDIO) {
            // stdio 模式 command 必须非空非空白
            if (req.getCommand() == null || req.getCommand().isBlank()) {
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate("stdio 传输方式必须指定 command 字段")
                        .addPropertyNode("command")
                        .addConstraintViolation();
                valid = false;
            }
        } else if (transport == McpTransportType.SSE || transport == McpTransportType.HTTP) {
            // sse/http 模式 url 必须为 http/https 协议
            String url = req.getUrl();
            if (url == null || url.isBlank()) {
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate(transport.toString().toLowerCase() + " 传输方式必须指定 url 字段")
                        .addPropertyNode("url")
                        .addConstraintViolation();
                valid = false;
            } else if (!url.startsWith("http://") && !url.startsWith("https://")) {
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate(transport.toString().toLowerCase() + " 传输方式的 url 必须为 http/https 协议")
                        .addPropertyNode("url")
                        .addConstraintViolation();
                valid = false;
            }
        }

        return valid;
    }
}
