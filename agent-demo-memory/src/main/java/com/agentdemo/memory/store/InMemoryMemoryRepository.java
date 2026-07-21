package com.agentdemo.memory.store;

import dev.langchain4j.data.message.ChatMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于内存的记忆持久化实现
 * <p>
 * 业务含义：开发阶段使用的内存实现，重启后数据丢失。
 * 后续可替换为 DbMemoryRepository（基于 MySQL + MyBatis-Plus）。
 * </p>
 */
@Repository
@ConditionalOnMissingBean(MemoryRepository.class)
public class InMemoryMemoryRepository implements MemoryRepository {

    /**
     * 会话消息存储（key: sessionId, value: 消息列表）
     */
    private final ConcurrentHashMap<String, List<ChatMessage>> store = new ConcurrentHashMap<>();

    @Override
    public void saveMessages(String sessionId, List<ChatMessage> messages) {
        store.put(sessionId, new ArrayList<>(messages));
    }

    @Override
    public List<ChatMessage> loadMessages(String sessionId) {
        return new ArrayList<>(store.getOrDefault(sessionId, new ArrayList<>()));
    }

    @Override
    public void deleteMessages(String sessionId) {
        store.remove(sessionId);
    }
}
