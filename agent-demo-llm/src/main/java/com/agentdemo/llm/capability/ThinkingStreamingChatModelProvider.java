package com.agentdemo.llm.capability;

import com.agentdemo.llm.thinking.ThinkingStreamingChatModel;

/**
 * 思考流式对话能力接口（CR-002 Task-17 新增）
 * <p>
 * 业务含义：声明 LLM 厂商提供思考流式对话模型（{@link ThinkingStreamingChatModel}）的能力契约。
 * Provider 作为工厂返回 {@link ThinkingStreamingChatModel} 实例，由调用方（如 {@code TaskBreakdownStream}）
 * 调用实例的 {@code stream(messages, handler)} 方法。
 * </p>
 * <p>
 * 设计决策修正（Task-20 实施时发现）：
 * 原设计 {@code extends ThinkingStreamingChatModel} 会让 Provider 自身作为流式模型实例，
 * 但 Provider 是 Spring 单例且需要按 modelName 缓存不同实例，
 * 自身作为模型实例无法适配多 modelName 切换场景。
 * 因此改为工厂方法 {@link #getThinkingStreamingChatModel(String)}，
 * 保持调用方（{@code TaskBreakdownStream}）使用 {@link ThinkingStreamingChatModel} 类型零改动。
 * </p>
 */
public interface ThinkingStreamingChatModelProvider {

    /**
     * 按场景获取思考流式对话模型实例
     * <p>
     * 业务含义：根据场景标识获取对应 {@link ThinkingStreamingChatModel} 实例，
     * 通过缓存复用避免重复创建（对应 AC-022）。
     * </p>
     *
     * @param scene 场景标识（chat/code/lite 等），null 或空表示默认
     * @return {@link ThinkingStreamingChatModel} 实例
     */
    ThinkingStreamingChatModel getThinkingStreamingChatModel(String scene);
}
