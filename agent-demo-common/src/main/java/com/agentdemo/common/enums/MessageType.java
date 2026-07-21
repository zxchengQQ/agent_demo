package com.agentdemo.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 消息类型枚举
 * <p>
 * 业务含义：标识对话历史中消息的角色类型，用于记忆系统组织消息。
 * 与 LangChain4j 的 ChatMessage 类型对应。
 * </p>
 */
@Getter
@AllArgsConstructor
public enum MessageType {

    /**
     * 系统消息：设定 Agent 角色与行为约束
     */
    SYSTEM("SYSTEM", "系统消息"),

    /**
     * 用户消息：终端用户输入
     */
    USER("USER", "用户消息"),

    /**
     * 助手消息：LLM 生成的回复
     */
    ASSISTANT("ASSISTANT", "助手消息"),

    /**
     * 工具消息：工具执行结果返回
     */
    TOOL("TOOL", "工具消息");

    private final String code;
    private final String desc;
}
