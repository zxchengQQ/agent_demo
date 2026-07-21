package com.agentdemo.memory.session;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 会话元信息
 * <p>
 * 业务含义：记录会话的元数据，包括会话 ID、用户标识、创建时间、最后活跃时间等。
 * 用于会话管理与超时清理。
 * </p>
 */
@Data
public class SessionMetadata {

    /**
     * 会话 ID（UUID）
     */
    private String sessionId;

    /**
     * 用户标识（预留多租户支持）
     */
    private String userId;

    /**
     * 创建时间戳（毫秒）
     */
    private long createdAt;

    /**
     * 最后活跃时间戳（毫秒）
     */
    private long lastActiveAt;

    /**
     * 扩展属性
     */
    private Map<String, Object> attributes = new HashMap<>();

    public SessionMetadata() {
    }

    public SessionMetadata(String sessionId) {
        this.sessionId = sessionId;
        long now = System.currentTimeMillis();
        this.createdAt = now;
        this.lastActiveAt = now;
    }

    /**
     * 更新最后活跃时间
     */
    public void updateActiveTime() {
        this.lastActiveAt = System.currentTimeMillis();
    }

    /**
     * 判断会话是否超时
     *
     * @param timeoutMillis 超时时间（毫秒）
     * @return 是否超时
     */
    public boolean isExpired(long timeoutMillis) {
        return System.currentTimeMillis() - lastActiveAt > timeoutMillis;
    }
}
