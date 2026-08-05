package com.agentdemo.llm.capability;

import dev.langchain4j.model.chat.ChatModel;

/**
 * 视觉对话能力接口（CR-002 Task-17 新增）
 * <p>
 * 业务含义：声明 LLM 厂商提供视觉对话（支持图片输入的 {@link ChatModel}）的能力契约。
 * 用于 PDF 图片描述生成等场景。未实现此接口的厂商，调用 {@code getVisionChatModel()} 时
 * 由编排层抛出 {@code UnsupportedCapabilityException}（对应 AC-021）。
 * </p>
 */
public interface VisionChatModelProvider {

    /**
     * 获取支持图片输入的视觉对话模型
     *
     * @return 支持图片输入的 ChatModel 实例（线程安全，缓存复用）
     */
    ChatModel getVisionChatModel();
}
