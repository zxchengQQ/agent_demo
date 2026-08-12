package com.agentdemo.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 测试连接请求 DTO
 * <p>
 * 业务含义：前端"测试连接"按钮的请求参数，后端用此 baseUrl 和 apiKey
 * 向 LLM 厂商发送 GET /models 请求验证连通性和认证有效性。
 * </p>
 */
@Data
public class TestConnectionRequest {

    /** API Base URL（必填） */
    @NotBlank(message = "Base URL 不能为空")
    private String baseUrl;

    /** API Key（必填） */
    @NotBlank(message = "API Key 不能为空")
    private String apiKey;
}
