package com.agentdemo.web.dto;

import dev.langchain4j.model.output.TokenUsage;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 对话响应 DTO
 * <p>
 * 业务含义：/api/agent/chat 接口的返回数据，包含 Agent 回复内容与会话信息。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {

    /**
     * 会话 ID（回传前端，用于后续多轮对话）
     */
    private String sessionId;

    /**
     * Agent 回复内容
     */
    private String content;

    /**
     * 实际使用的模型
     */
    private String model;

    /**
     * 耗时（毫秒）
     */
    private long duration;

    /**
     * 工具调用记录
     */
    private List<ToolCallInfo> toolCalls;

    /**
     * Token 用量（Task-16 新增）
     * <p>
     * 业务含义：携带大模型 API 返回的 Token 消耗数据，供前端展示用量统计。
     * 流式路径通过 SSE usage 事件单独推送，同步路径通过此字段返回。
     * </p>
     */
    private TokenUsage tokenUsage;

    /**
     * 工具调用信息
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolCallInfo {
        /**
         * 工具名称
         */
        private String toolName;

        /**
         * 调用参数
         */
        private String args;

        /**
         * 执行结果
         */
        private String result;
    }
}
