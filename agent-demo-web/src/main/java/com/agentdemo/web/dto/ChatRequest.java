package com.agentdemo.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
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

    /**
     * 是否开启复杂任务拆解模式（CR-002 新增），默认 false
     * <p>
     * 业务含义：用户通过前端开关控制，开启后 Agent 先拆解为子任务再逐个执行。
     * true 时后端走 PlanAgent.chatTaskBreakdownStream 路径（推送 task_* 系列事件），
     * false/null 时走原有路径（零回归）。
     * 与 enableThinking 独立共存，可同时为 true（AC-011）。
     * </p>
     */
    private Boolean enableTaskBreakdown = false;

    /**
     * 用户指定的知识库名称列表（可选）
     * <p>
     * 业务含义：前端知识库选择器选中的知识库名称。为空或 null 时 Agent 自主决策；
     * 非空时 AgentController 将其注入用户消息，引导 LLM 调用 searchKnowledge 时使用指定知识库。
     * </p>
     */
    private List<String> knowledgeBases;
}
