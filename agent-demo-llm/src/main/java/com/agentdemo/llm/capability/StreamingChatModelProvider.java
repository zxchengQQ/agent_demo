package com.agentdemo.llm.capability;

import dev.langchain4j.model.chat.StreamingChatModel;

/**
 * 流式对话能力接口（CR-002 Task-17 新增）
 * <p>
 * 业务含义：声明 LLM 厂商提供流式对话（{@link StreamingChatModel}）的能力契约。
 * 流式模型用于 SSE 逐字输出场景，与同步模型分别构建。
 * </p>
 */
public interface StreamingChatModelProvider {

    /**
     * 按场景获取流式对话模型
     *
     * @param scene 场景标识（chat/code/lite 等），null 或空表示默认
     * @return StreamingChatModel 实例（线程安全，缓存复用）
     */
    StreamingChatModel getStreamingChatModel(String scene);
}
