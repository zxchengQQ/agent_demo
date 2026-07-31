package com.agentdemo.llm.factory;

import dev.langchain4j.model.output.TokenUsage;

import java.util.List;

/**
 * 思考流式回调处理器（CR-001 新增，Task-02 扩展，Task-15 扩展）
 * <p>
 * 业务含义：ArkThinkingStreamingChatModel 的回调接口，区分推理内容与正式回复。
 * 位于 llm 模块，供 ArkThinkingStreamingChatModel 调用，由 agent 层的 ArkThinkingTokenStream 桥接实现。
 * </p>
 * <p>
 * Task-02 变更：
 * - 新增 {@link #onToolCalls(List)} 方法，支持工具调用回调
 * - {@link #onComplete(String, String, TokenUsage)} 新增 finishReason 参数，标识流式结束原因
 * </p>
 * <p>
 * Task-15 变更：
 * - {@link #onComplete(String, String, TokenUsage)} 新增 tokenUsage 参数，透传方舟返回的 Token 用量
 * </p>
 */
public interface ThinkingStreamHandler {

    /**
     * 推理内容片段（对应方舟 delta.reasoning_content）
     *
     * @param thinking 推理内容片段
     */
    void onPartialThinking(String thinking);

    /**
     * 正式回复片段（对应方舟 delta.content）
     *
     * @param token 回复内容片段
     */
    void onPartialResponse(String token);

    /**
     * 流式完成（携带完整正式回复、结束原因和 Token 用量）
     * <p>
     * 业务含义：当方舟 SSE 流的 finish_reason 非空时触发，finishReason 通常为 "stop"（正常结束）
     * 或 "tool_calls"（模型决定调用工具）。
     * </p>
     * <p>
     * Task-15：tokenUsage 透传方舟返回的 Token 用量（需在请求体开启 stream_options.include_usage）。
     * API 返回 usage 时为非 null，未返回时为 null。
     * </p>
     *
     * @param fullResponse 完整正式回复（所有 content 片段拼接）
     * @param finishReason 结束原因（stop / tool_calls 等）
     * @param tokenUsage   Token 用量（API 返回时非 null，未返回时为 null）
     */
    void onComplete(String fullResponse, String finishReason, TokenUsage tokenUsage);

    /**
     * 工具调用回调（Task-02 新增）
     * <p>
     * 业务含义：当模型决定调用外部工具时，通过此回调传递工具调用信息。
     * 调用方可据此执行工具调用并将结果回传给模型，实现 Agent 工具调用闭环。
     * </p>
     *
     * @param toolCalls 工具调用列表
     */
    void onToolCalls(List<ToolCall> toolCalls);

    /**
     * 异常回调
     *
     * @param error 异常信息
     */
    void onError(Throwable error);
}
