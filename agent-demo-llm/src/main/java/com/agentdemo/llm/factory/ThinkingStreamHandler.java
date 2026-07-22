package com.agentdemo.llm.factory;

/**
 * 思考流式回调处理器（CR-001 新增）
 * <p>
 * 业务含义：ArkThinkingStreamingChatModel 的回调接口，区分推理内容与正式回复。
 * 位于 llm 模块，供 ArkThinkingStreamingChatModel 调用，由 agent 层的 ArkThinkingTokenStream 桥接实现。
 * </p>
 */
public interface ThinkingStreamHandler {

    /**
     * 推理内容片段（对应方舟 delta.reasoning_content）
     */
    void onPartialThinking(String thinking);

    /**
     * 正式回复片段（对应方舟 delta.content）
     */
    void onPartialResponse(String token);

    /**
     * 流式完成（携带完整正式回复）
     */
    void onComplete(String fullResponse);

    /**
     * 异常
     */
    void onError(Throwable error);
}
