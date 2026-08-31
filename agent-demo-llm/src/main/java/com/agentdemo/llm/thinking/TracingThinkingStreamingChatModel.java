package com.agentdemo.llm.thinking;

import com.agentdemo.observability.TraceCollector;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.output.TokenUsage;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Thinking 流式模型追踪装饰器（langsmith-observability，Task-09）
 * <p>
 * 业务含义：覆盖统一对话主链路的 LLM 采集（技术方案 §3.1 采集点适配层、决策 4）。
 * ThinkingStreamingChatModel 为自定义 HTTP 实现，无 LangChain4j listener 机制，
 * 故在 ModelFactory 返回处包装本装饰器：请求侧参数（messages/toolsJson/modelName）
 * 在 stream() 调用时可得，响应侧在包装的 handler.onComplete 获取真实 TokenUsage
 * （技术难点 3：现有 SSE usage 为估算值，此处取 API 真实值）。
 * </p>
 * <p>
 * 一处包装覆盖全部 Thinking 调用方（直答/拆解/恢复），业务流类零修改。
 * 采集失败静默降级（AC-S04）：记录事件抛异常时不回抛，仅跳过本条。
 * </p>
 */
public class TracingThinkingStreamingChatModel implements ThinkingStreamingChatModel {

    private final ThinkingStreamingChatModel delegate;
    private final TraceCollector collector;
    private final String modelName;

    /**
     * @param delegate   被包装的真实 Thinking 模型
     * @param collector  追踪采集器
     * @param modelName  实际模型名（span 属性，AC-M02 如实记录）
     */
    public TracingThinkingStreamingChatModel(ThinkingStreamingChatModel delegate,
                                             TraceCollector collector, String modelName) {
        this.delegate = delegate;
        this.collector = collector;
        this.modelName = modelName;
    }

    @Override
    public void stream(List<ChatMessage> messages, ThinkingStreamHandler handler) {
        stream(messages, null, handler);
    }

    @Override
    public void stream(List<ChatMessage> messages, String toolsJson, ThinkingStreamHandler handler) {
        long startNanos = System.nanoTime();
        String promptText = serializeMessages(messages);
        // 业务含义：包装 handler——透传全部回调，仅在 onComplete/onError 时记录事件
        delegate.stream(messages, toolsJson, new TracingHandler(handler, promptText, startNanos));
    }

    /**
     * 包装回调处理器：透传 + 采集
     */
    private class TracingHandler implements ThinkingStreamHandler {

        private final ThinkingStreamHandler delegateHandler;
        private final String promptText;
        private final long startNanos;

        private TracingHandler(ThinkingStreamHandler delegateHandler, String promptText, long startNanos) {
            this.delegateHandler = delegateHandler;
            this.promptText = promptText;
            this.startNanos = startNanos;
        }

        @Override
        public void onPartialThinking(String thinking) {
            delegateHandler.onPartialThinking(thinking);
        }

        @Override
        public void onPartialResponse(String token) {
            delegateHandler.onPartialResponse(token);
        }

        @Override
        public void onComplete(String fullResponse, String finishReason, TokenUsage tokenUsage) {
            try {
                long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
                int inputTokens = tokenUsage != null && tokenUsage.inputTokenCount() != null
                        ? tokenUsage.inputTokenCount() : -1;
                int outputTokens = tokenUsage != null && tokenUsage.outputTokenCount() != null
                        ? tokenUsage.outputTokenCount() : -1;
                // AC-N03：真实 Token 值（技术难点 3）；AC-M02：模型名如实
                collector.recordLlm(new TraceCollector.LlmCallEvent(
                        modelName, promptText, fullResponse,
                        inputTokens, outputTokens, durationMs, true, null, finishReason));
            } catch (Exception e) {
                // AC-S04：采集失败静默降级，不影响主流程
            }
            delegateHandler.onComplete(fullResponse, finishReason, tokenUsage);
        }

        @Override
        public void onToolCalls(List<ToolCall> toolCalls) {
            delegateHandler.onToolCalls(toolCalls);
        }

        @Override
        public void onError(Throwable error) {
            try {
                long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
                // AC-T02：失败也产事件，含错误信息
                collector.recordLlm(new TraceCollector.LlmCallEvent(
                        modelName, promptText, null,
                        -1, -1, durationMs, false, error != null ? error.getMessage() : null, null));
            } catch (Exception e) {
                // AC-S04：静默降级
            }
            delegateHandler.onError(error);
        }
    }

    /**
     * 序列化消息列表为 trace 纯文本（按角色拼接）
     */
    private String serializeMessages(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        return messages.stream()
                .map(m -> m.type().name() + ": " + messageText(m))
                .collect(Collectors.joining("\n"));
    }

    private String messageText(ChatMessage m) {
        if (m instanceof AiMessage ai) {
            return ai.text() != null ? ai.text() : "";
        }
        if (m instanceof UserMessage user) {
            return user.hasSingleText() ? user.singleText() : user.toString();
        }
        if (m instanceof SystemMessage sys) {
            return sys.text() != null ? sys.text() : "";
        }
        return m.toString();
    }
}
