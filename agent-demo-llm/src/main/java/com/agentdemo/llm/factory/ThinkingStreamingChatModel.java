package com.agentdemo.llm.factory;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * 思考流式对话模型抽象接口（CR-001 Task-09 新增）
 * <p>
 * 业务含义：统一火山引擎方舟和阿里百炼的思考流式模型调用方式，
 * 为 Agent 层的深度思考模式和任务拆解功能提供与提供商无关的抽象。
 * </p>
 * <p>
 * 设计决策：将原 {@link ArkThinkingStreamingChatModel} 中对外暴露的流式调用方法抽象为接口，
 * 使 {@link ModelFactory#getThinkingStreamingChatModel()} 可以返回不同提供商的实现，
 * 解除返回类型对火山引擎具体类的硬编码依赖。
 * </p>
 * <p>
 * 业务规则（BR-LLM-014）：阿里百炼模式下支持深度思考模式和任务拆解功能，
 * 行为与火山引擎模式一致。
 * </p>
 */
public interface ThinkingStreamingChatModel {

    /**
     * 单轮思考流式对话（不带工具调用）
     * <p>
     * 业务含义：调用方传入消息列表和回调处理器，实现类负责 HTTP 请求、SSE 解析、回调分发。
     * 实现类需保证：
     * <ul>
     *   <li>推理内容（reasoning_content）通过 handler.onPartialThinking 推送</li>
     *   <li>正式回复（content）通过 handler.onPartialResponse 推送</li>
     *   <li>流式结束时通过 handler.onComplete 推送完整回复和结束原因</li>
     *   <li>异常时通过 handler.onError 推送异常信息</li>
     * </ul>
     *
     * @param messages 消息列表
     * @param handler  流式回调处理器
     */
    void stream(List<ChatMessage> messages, ThinkingStreamHandler handler);

    /**
     * ReAct 思考流式对话（带工具调用）
     * <p>
     * 业务含义：与单轮模式类似，但额外支持 LLM 调用外部工具。实现类需：
     * <ul>
     *   <li>在请求体中包含 tools 字段（OpenAI 兼容格式）</li>
     *   <li>解析 LLM 返回的 tool_calls 并通过 handler.onToolCalls 推送</li>
     *   <li>finishReason 为 "tool_calls" 时通过 onComplete 透传</li>
     * </ul>
     *
     * @param messages  消息列表
     * @param toolsJson 工具 JSON Schema 字符串（OpenAI 兼容格式），null 或空字符串表示不传 tools
     * @param handler   流式回调处理器
     */
    void stream(List<ChatMessage> messages, String toolsJson, ThinkingStreamHandler handler);
}
