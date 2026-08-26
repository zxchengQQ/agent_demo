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
        // 旧签名保留兼容直答路径，mode 默认 direct（统一模式恢复路由按 mode 分流）
        saveInteraction(sessionId, messages, askUserType, question, options, retryCount, modelId, toolsJson,
                PendingInteraction.MODE_DIRECT);
    }

    /**
     * 保存暂停状态（带暂停模式）
     * <p>
     * 业务含义：统一对话模式下暂停可能发生在直接对话路径（direct）或拆解子任务执行路径
     * （breakdown），恢复路由依据 mode 字段分流。
     * </p>
     *
     * @param sessionId   会话 ID
     * @param messages    暂停时的完整消息列表
     * @param askUserType 提问类型（text/confirm）
     * @param question    问题文本
     * @param options     选项列表（confirm 类型必填）
     * @param retryCount  当前追问次数
     * @param modelId     模型 ID
     * @param toolsJson   工具 JSON Schema
     * @param mode        暂停模式（direct/breakdown）
     */
    public void saveInteraction(String sessionId, List<ChatMessage> messages,
                                 String askUserType, String question,
                                 List<String> options, int retryCount,
                                 String modelId, String toolsJson, String mode) {
        PendingInteraction pending = new PendingInteraction();
        pending.setMessages(messages);
        pending.setAskUserType(askUserType);
        pending.setQuestion(question);
        pending.setOptions(options);
        pending.setRetryCount(retryCount);
        pending.setTimestamp(System.currentTimeMillis());
        pending.setModelId(modelId);
        pending.setToolsJson(toolsJson);
        pending.setMode(mode);
        pendingMap.put(sessionId, pending);
        log.info("保存 HITL 暂停状态: sessionId={}, retryCount={}, type={}, mode={}",
                sessionId, retryCount, askUserType, mode);
    }

    /**
     * 保存工具权限确认暂停状态（mode=tool_confirm）
     * <p>
     * 业务含义：HITLReActStream 拦截 ask 级工具调用时保存暂停上下文，附带被拦截
     * toolCall 的 id、工具名与参数 JSON。用户批准/拒绝后由 UnifiedChatStream 按 mode
     * 路由恢复：批准时直接执行 pendingToolName 并回填 id 匹配的结果消息（AC-N03/AC-S02）。
     * </p>
     *
     * @param sessionId     会话 ID
     * @param messages      暂停时的完整消息列表（含该轮 AiMessage 与已执行的 allow 工具结果）
     * @param modelId       模型 ID
     * @param toolsJson     工具 JSON Schema
     * @param toolCallId    被拦截工具调用的 toolCall id（恢复时 ToolExecutionResultMessage 需与 id 匹配）
     * @param toolName      待确认工具方法名（@Tool 方法名，批准后经 ToolExecutor 执行）
     * @param toolArguments 待确认工具参数 JSON（原样保存，批准后直接执行，不重复解析）
     */
    public void saveToolConfirmInteraction(String sessionId, List<ChatMessage> messages,
                                            String modelId, String toolsJson,
                                            String toolCallId, String toolName, String toolArguments) {
        PendingInteraction pending = new PendingInteraction();
        pending.setMessages(messages);
        pending.setTimestamp(System.currentTimeMillis());
        pending.setModelId(modelId);
        pending.setToolsJson(toolsJson);
        pending.setMode(PendingInteraction.MODE_TOOL_CONFIRM);
        pending.setPendingToolCallId(toolCallId);
        pending.setPendingToolName(toolName);
        pending.setPendingToolArguments(toolArguments);
        pendingMap.put(sessionId, pending);
        log.info("保存工具权限确认暂停状态: sessionId={}, toolName={}, mode={}",
                sessionId, toolName, PendingInteraction.MODE_TOOL_CONFIRM);
    }

    /**
     * 补充拆解上下文到已保存的 pending 状态
     * <p>
     * 业务含义：拆解子任务执行中 askUser 暂停时，HITLReActStream 先保存 ReAct 上下文
     * （mode 未知），TaskBreakdownStream 随后调用本方法补充拆解编排上下文（子任务计划、
     * 暂停位置、已完成结果），并将 mode 标记为 breakdown，使恢复路由能续跑剩余子任务。
     * </p>
     * <p>
     * pending 不存在时仅记录 WARN 不创建（防止意外写入孤儿状态）。
     * </p>
     *
     * @param sessionId        会话 ID
     * @param subTasks         拆解子任务列表
     * @param currentTaskIndex 暂停时正在执行的子任务 index（0-based）
     * @param subtaskResults   已完成子任务结果列表（按序）
     */
    public void attachBreakdownContext(String sessionId, List<SubTask> subTasks,
                                       int currentTaskIndex, List<String> subtaskResults) {
        PendingInteraction pending = pendingMap.get(sessionId);
        if (pending == null) {
            log.warn("attachBreakdownContext 失败：sessionId={} 无 pending 状态，忽略拆解上下文", sessionId);
            return;
        }
        pending.setSubTasks(subTasks);
        pending.setCurrentTaskIndex(currentTaskIndex);
        pending.setSubtaskResults(subtaskResults);
        pending.setMode(PendingInteraction.MODE_BREAKDOWN);
        log.info("附加拆解上下文: sessionId={}, currentTaskIndex={}, 已完成子任务数={}",
                sessionId, currentTaskIndex, subtaskResults == null ? 0 : subtaskResults.size());
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
