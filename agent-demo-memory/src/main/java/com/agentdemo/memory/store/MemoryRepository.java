package com.agentdemo.memory.store;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * 记忆持久化接口
 * <p>
 * 业务含义：抽象记忆持久化能力，支持后续接入数据库（MySQL/H2）。
 * 设计原则：接口与实现分离，本阶段提供 InMemory 实现，后续可替换为 DB 实现。
 * </p>
 */
public interface MemoryRepository {

    /**
     * 保存会话消息
     *
     * @param sessionId 会话 ID
     * @param messages  消息列表
     */
    void saveMessages(String sessionId, List<ChatMessage> messages);

    /**
     * 加载会话历史消息
     *
     * @param sessionId 会话 ID
     * @return 消息列表
     */
    List<ChatMessage> loadMessages(String sessionId);

    /**
     * 删除会话消息
     *
     * @param sessionId 会话 ID
     */
    void deleteMessages(String sessionId);
}
