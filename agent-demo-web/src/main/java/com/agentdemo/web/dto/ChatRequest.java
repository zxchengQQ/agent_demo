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
     * 模型 ID（可选，为空使用第一个可用 chat 模型）
     */
    private String model;

    /**
     * 扩展参数
     */
    private Map<String, Object> options;

    /**
     * 用户指定的知识库名称列表（可选）
     * <p>
     * 业务含义：前端知识库选择器选中的知识库名称。为空或 null 时 Agent 自主决策；
     * 非空时 AgentController 将其注入用户消息，引导 LLM 调用 searchKnowledge 时使用指定知识库。
     * </p>
     */
    private List<String> knowledgeBases;

    /**
     * 用户指定的工具标识列表（可选）
     * <p>
     * 业务含义：前端工具选择器选中的工具，格式为 category:name（如 mcp:mermaid-mcp）。
     * null → 沿用会话已绑定的工具（无绑定时用默认）；
     * 非空 → 解析并绑定到会话，后续轮次沿用；
     * 空数组 → 清除会话绑定，恢复仅默认工具。
     * </p>
     */
    private List<String> tools;

    /**
     * 工具权限确认结果（可选，tool_confirm 恢复专用）
     * <p>
     * 业务含义：ask 级工具确认卡片用户操作回传——true=批准（执行待确认工具，AC-N03）、
     * false=拒绝（回填拒绝文案，AC-S02）。null=普通消息（无权限确认意图），
     * 现有 HITL askUser 恢复链路零变更（反序列化兼容：旧请求体无此字段可正常解析）。
     * </p>
     */
    private Boolean toolApproved;
}
