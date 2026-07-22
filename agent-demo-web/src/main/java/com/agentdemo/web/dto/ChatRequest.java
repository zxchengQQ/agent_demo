package com.agentdemo.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * 对话请求 DTO
 * <p>
 * 业务含义：前端调用 /api/agent/chat 接口的请求参数。
 * </p>
 */
@Data
public class ChatRequest {

    /**
     * 会话 ID（可选，为空则新建会话）
     */
    private String sessionId;

    /**
     * 用户消息（必填）
     */
    @NotBlank(message = "消息内容不能为空")
    @Size(max = 4000, message = "消息长度不能超过 4000 字符")
    private String message;

    /**
     * Agent 类型（可选，默认 SINGLE）
     */
    private String agentType;

    /**
     * 指定模型（可选，为空用默认模型）
     */
    private String model;

    /**
     * 扩展参数
     */
    private Map<String, Object> options;

    /**
     * 是否开启深度思考（CR-001 新增），默认 false
     * <p>
     * 业务含义：前端"深度思考"开关状态，true 时后端走思考流式路径（推送 reasoning + token 事件），
     * null/false 时走原有流式路径（仅 token 事件，零回归）。
     * </p>
     */
    private Boolean enableThinking;
}
