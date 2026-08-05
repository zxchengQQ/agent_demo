package com.agentdemo.llm.capability;

import dev.langchain4j.model.chat.ChatModel;

/**
 * 同步对话能力接口（CR-002 Task-17 新增）
 * <p>
 * 业务含义：声明 LLM 厂商提供同步对话（{@link ChatModel}）的能力契约。
 * 厂商按需实现此接口，未实现时编排层通过 {@code instanceof} 检测并抛出明确异常。
 * </p>
 * <p>
 * 接口隔离原则（ISP）：每种能力独立成接口，厂商按需实现，避免胖接口污染。
 * </p>
 */
public interface ChatModelProvider {

    /**
     * 按场景获取同步对话模型
     *
     * @param scene 场景标识（chat/code/lite 等），null 或空表示默认
     * @return ChatModel 实例（线程安全，缓存复用）
     */
    ChatModel getChatModel(String scene);
}
