package com.agentdemo.web.dto;

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
