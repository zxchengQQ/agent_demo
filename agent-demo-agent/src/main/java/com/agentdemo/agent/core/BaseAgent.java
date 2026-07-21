package com.agentdemo.agent.core;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * Agent 抽象接口
 * <p>
 * 业务含义：所有 Agent 实现的统一抽象，定义对话入口。
 * LangChain4j 的 AiServices 通过动态代理实现此接口，自动处理 ReAct 循环、工具调用、记忆管理。
 * </p>
 * <p>
 * 接口变更全链路影响评估：
 * - 新增/删除参数：需同步 web 层 ChatRequest DTO、app 层 SceneService
 * - 新增方法：需在所有实现类中实现
 * </p>
 * <p>
 * 调用方枚举：
 * - web 层 AgentController：调用 chat 方法（同步）、chatStream 方法（SSE 流式）
 * - app 层 SceneService：按场景路由调用不同 Agent
 * </p>
 */
public interface BaseAgent {

    /**
     * 同步对话
     * 业务含义：Agent 接收用户消息，经过 ReAct 循环（思考-行动-观察）后返回最终回复
     *
     * @param sessionId 会话 ID（用于记忆隔离，@MemoryId 让 LangChain4j 自动关联会话记忆）
     * @param message   用户消息（@UserMessage 标识为用户输入）
     * @return Agent 回复内容
     */
    String chat(@MemoryId String sessionId, @UserMessage String message);

    /**
     * 流式对话
     * 业务含义：Agent 接收用户消息，流式返回生成内容（逐字推送）。
     * LangChain4j AiServices 根据 StreamingChatModel 自动实现，返回 TokenStream 供调用方注册回调。
     *
     * 调用方：web 层 AgentController 的 SSE 接口（/api/agent/chat/stream）
     *
     * @param sessionId 会话 ID（用于记忆隔离，@MemoryId 让 LangChain4j 自动关联会话记忆）
     * @param message   用户消息（@UserMessage 标识为用户输入）
     * @return TokenStream 流式令牌（需调用 start() 启动流式输出）
     */
    TokenStream chatStream(@MemoryId String sessionId, @UserMessage String message);
}
