package com.agentdemo.agent.core;

import dev.langchain4j.data.message.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 人机交互管理器
 * <p>
 * 业务含义：管理 Agent 与用户交互过程中的暂停状态。当 Agent 调用 askUser 工具时，
 * 将当前 ReAct 上下文保存到此管理器；用户回复后从此管理器加载状态恢复执行。
 * 会话级隔离，按 sessionId 存取，不同会话互不影响。
 * </p>
 * <p>
 * 调用方：HITLReActStream（saveInteraction）、AgentController（hasPending/loadInteraction/clearInteraction）
 * </p>
 */
@Service
public class HumanInteractionManager {

    private static final Logger log = LoggerFactory.getLogger(HumanInteractionManager.class);

    /** 默认超时时间（30 分钟，与 SessionManager 一致） */
    private static final long DEFAULT_TIMEOUT_MS = 30 * 60 * 1000L;

    /** pending 状态存储（key: sessionId, value: PendingInteraction） */
    private final ConcurrentHashMap<String, PendingInteraction> pendingMap = new ConcurrentHashMap<>();

    /**
     * 保存暂停状态
     *
     * @param sessionId   会话 ID
     * @param messages    暂停时的完整消息列表
     * @param askUserType 提问类型（text/confirm）
     * @param question    问题文本
     * @param options     选项列表（confirm 类型必填）
     * @param retryCount  当前追问次数
     * @param modelId     模型 ID
     * @param toolsJson   工具 JSON Schema
     */
    public void saveInteraction(String sessionId, List<ChatMessage> messages,
                                 String askUserType, String question,
                                 List<String> options, int retryCount,
                                 String modelId, String toolsJson) {
        PendingInteraction pending = new PendingInteraction();
        pending.setMessages(messages);
        pending.setAskUserType(askUserType);
        pending.setQuestion(question);
        pending.setOptions(options);
        pending.setRetryCount(retryCount);
        pending.setTimestamp(System.currentTimeMillis());
        pending.setModelId(modelId);
        pending.setToolsJson(toolsJson);
        pendingMap.put(sessionId, pending);
        log.info("保存 HITL 暂停状态: sessionId={}, retryCount={}, type={}", sessionId, retryCount, askUserType);
    }

    /**
     * 加载暂停状态
     *
     * @param sessionId 会话 ID
     * @return 暂停状态（不存在返回 null）
     */
    public PendingInteraction loadInteraction(String sessionId) {
        return pendingMap.get(sessionId);
    }

    /**
     * 判断是否有 pending 交互
     *
     * @param sessionId 会话 ID
     * @return 是否存在 pending
     */
    public boolean hasPending(String sessionId) {
        return pendingMap.containsKey(sessionId);
    }

    /**
     * 清除 pending 交互
     *
     * @param sessionId 会话 ID
     */
    public void clearInteraction(String sessionId) {
        pendingMap.remove(sessionId);
        log.info("清除 HITL 暂停状态: sessionId={}", sessionId);
    }

    /**
     * 定时清理超时的 pending 状态
     * 业务含义：每 5 分钟执行一次，清理超过 30 分钟未回复的暂停状态
     */
    @Scheduled(fixedRate = 5 * 60 * 1000L)
    public void cleanupExpired() {
        cleanupExpired(DEFAULT_TIMEOUT_MS);
    }

    /**
     * 清理超时的 pending 状态
     *
     * @param timeoutMillis 超时时间（毫秒）
     */
    public void cleanupExpired(long timeoutMillis) {
        int before = pendingMap.size();
        pendingMap.entrySet().removeIf(entry -> entry.getValue().isExpired(timeoutMillis));
        int cleaned = before - pendingMap.size();
        if (cleaned > 0) {
            log.info("清理超时 HITL 暂停状态: 清理 {} 个，剩余 {} 个", cleaned, pendingMap.size());
        }
    }
}
