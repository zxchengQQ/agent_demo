package com.agentdemo.memory.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话管理器
 * <p>
 * 业务含义：管理会话生命周期，包括创建、查询、关闭、超时清理。
 * 会话是 Agent 多轮对话的基础，通过 sessionId 隔离不同用户的记忆。
 * </p>
 * <p>
 * 调用方：web 层（每次请求创建/查询会话）、agent 层
 * </p>
 */
@Service
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    /**
     * 默认会话超时时间（30 分钟无活跃自动清理）
     */
    private static final long DEFAULT_TIMEOUT_MS = 30 * 60 * 1000L;

    /**
     * 会话存储（key: sessionId, value: SessionMetadata）
     */
    private final ConcurrentHashMap<String, SessionMetadata> sessionMap = new ConcurrentHashMap<>();

    /**
     * 创建新会话
     *
     * @return 会话 ID
     */
    public String createSession() {
        return createSession(null);
    }

    /**
     * 创建新会话并指定元信息
     *
     * @param userId 用户标识（可选）
     * @return 会话 ID
     */
    public String createSession(String userId) {
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        SessionMetadata metadata = new SessionMetadata(sessionId);
        metadata.setUserId(userId);
        sessionMap.put(sessionId, metadata);
        log.info("创建会话: sessionId={}, userId={}", sessionId, userId);
        return sessionId;
    }

    /**
     * 获取会话元信息
     *
     * @param sessionId 会话 ID
     * @return 会话元信息（不存在返回 null）
     */
    public SessionMetadata getSession(String sessionId) {
        SessionMetadata metadata = sessionMap.get(sessionId);
        if (metadata != null) {
            metadata.updateActiveTime();
        }
        return metadata;
    }

    /**
     * 判断会话是否存在
     *
     * @param sessionId 会话 ID
     * @return 是否存在
     */
    public boolean exists(String sessionId) {
        return sessionMap.containsKey(sessionId);
    }

    /**
     * 关闭会话
     *
     * @param sessionId 会话 ID
     */
    public void closeSession(String sessionId) {
        sessionMap.remove(sessionId);
        log.info("关闭会话: sessionId={}", sessionId);
    }

    /**
     * 定时清理超时会话
     * 业务含义：每 5 分钟执行一次，清理超过 30 分钟未活跃的会话，释放内存
     */
    @Scheduled(fixedRate = 5 * 60 * 1000L)
    public void cleanupExpiredSessions() {
        cleanupExpiredSessions(DEFAULT_TIMEOUT_MS);
    }

    /**
     * 清理超时会话
     *
     * @param timeoutMillis 超时时间（毫秒）
     */
    public void cleanupExpiredSessions(long timeoutMillis) {
        int before = sessionMap.size();
        sessionMap.entrySet().removeIf(entry -> entry.getValue().isExpired(timeoutMillis));
        int cleaned = before - sessionMap.size();
        if (cleaned > 0) {
            log.info("清理超时会话: 清理 {} 个，剩余 {} 个", cleaned, sessionMap.size());
        }
    }

    /**
     * 获取当前活跃会话数
     *
     * @return 会话数
     */
    public int activeSessionCount() {
        return sessionMap.size();
    }
}
