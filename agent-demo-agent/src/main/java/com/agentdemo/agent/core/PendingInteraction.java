package com.agentdemo.agent.core;

import dev.langchain4j.data.message.ChatMessage;
import lombok.Data;

import java.util.List;

/**
 * HITL 暂停交互状态
 * <p>
 * 业务含义：Agent 调用 askUser 工具暂停执行时，将当前 ReAct 上下文保存为此对象。
 * 用户回复后，通过此对象恢复执行--加载消息列表、添加用户回复为 Observation、继续 ReAct 循环。
 * </p>
 * <p>
 * 调用方：HITLReActStream（保存）、AgentController/SimpleAgent（加载恢复）
 * </p>
 */
@Data
public class PendingInteraction {

    /** 暂停时的完整消息列表（含系统提示词、历史对话、ReAct 上下文） */
    private List<ChatMessage> messages;

    /** 提问类型：text（开放式追问）或 confirm（确认型交互） */
    private String askUserType;

    /** 问题文本 */
    private String question;

    /** 选项列表（confirm 类型必填，text 类型为 null） */
    private List<String> options;

    /** 当前连续追问次数（同一会话中连续 askUser 调用次数） */
    private int retryCount;

    /** 暂停时间戳（用于超时清理） */
    private long timestamp;

    /** 使用的模型 ID（恢复时需要） */
    private String modelId;

    /** 工具 JSON Schema（恢复时需要，避免重新计算） */
    private String toolsJson;

    /**
     * 判断是否已过期
     *
     * @param timeoutMillis 超时时间（毫秒）
     * @return 是否过期
     */
    public boolean isExpired(long timeoutMillis) {
        return System.currentTimeMillis() - timestamp > timeoutMillis;
    }
}
