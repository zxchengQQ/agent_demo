package com.agentdemo.llm.registry;

import com.agentdemo.observability.TraceCollector;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.output.TokenUsage;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * LangChain4j ChatModel 监听器（langsmith-observability，Task-08）
 * <p>
 * 业务含义：将 OpenAI 系模型（OpenAiChatModel / OpenAiStreamingChatModel）的调用事件
 * （请求/响应/错误）转换为 {@link TraceCollector.LlmCallEvent} 上报。
 * span 句柄无需跨回调传递——collector 侧按事件独立构建 span（技术方案 §3.1 采集点适配层）。
 * </p>
 * <p>
 * 挂载点：{@link ModelFactory#createChatModel} / {@link ModelFactory#createStreamingChatModel}
 * 经 builder.listeners(...) 注入（模型实例缓存重建时随构建自动挂载）。
 * </p>
 */
public class TraceChatModelListener implements ChatModelListener {

    /** attributes 中记录请求起始时间戳的 key */
    private static final String KEY_START_NANOS = "agentdemo.llm.startNanos";

    private final TraceCollector collector;

    public TraceChatModelListener(TraceCollector collector) {
        this.collector = collector;
    }

    @Override
    public void onRequest(ChatModelRequestContext ctx) {
        // 业务含义：记录请求起始时刻，onResponse/onError 据此计算耗时
        ctx.attributes().put(KEY_START_NANOS, System.nanoTime());
    }

    @Override
    public void onResponse(ChatModelResponseContext ctx) {
        ChatRequest request = ctx.chatRequest();
        String modelName = safe(request.parameters().modelName());
        String promptText = serializeMessages(request.messages());
        String outputText = ctx.chatResponse().aiMessage() != null
                ? ctx.chatResponse().aiMessage().text() : null;
        TokenUsage usage = ctx.chatResponse().metadata().tokenUsage();
        int inputTokens = usage != null && usage.inputTokenCount() != null ? usage.inputTokenCount() : -1;
        int outputTokens = usage != null && usage.outputTokenCount() != null ? usage.outputTokenCount() : -1;
        String finishReason = ctx.chatResponse().metadata().finishReason() != null
                ? ctx.chatResponse().metadata().finishReason().toString() : null;
        collector.recordLlm(new TraceCollector.LlmCallEvent(
                modelName, promptText, outputText,
                inputTokens, outputTokens, elapsedMs(ctx.attributes()), true, null, finishReason));
    }

    @Override
    public void onError(ChatModelErrorContext ctx) {
        ChatRequest request = ctx.chatRequest();
        String modelName = safe(request.parameters().modelName());
        String promptText = serializeMessages(request.messages());
        String errorMessage = ctx.error() != null ? ctx.error().getMessage() : null;
        // AC-T02：失败也产事件，含错误信息
        collector.recordLlm(new TraceCollector.LlmCallEvent(
                modelName, promptText, null,
                -1, -1, elapsedMs(ctx.attributes()), false, safe(errorMessage), null));
    }

    /**
     * 计算调用耗时（onRequest 到 onResponse/onError 的毫秒差；无起始时刻返回 0）
     */
    private long elapsedMs(Map<Object, Object> attributes) {
        Object start = attributes.get(KEY_START_NANOS);
        if (start instanceof Long startNanos) {
            return (System.nanoTime() - startNanos) / 1_000_000L;
        }
        return 0L;
    }

    /**
     * 序列化消息列表为 trace 用的纯文本（按角色拼接）
     */
    private String serializeMessages(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        return messages.stream()
                .map(m -> m.type().name() + ": " + messageText(m))
                .collect(Collectors.joining("\n"));
    }

    /**
     * 提取单条消息文本（按消息类型取 text）
     */
    private String messageText(ChatMessage m) {
        if (m instanceof AiMessage ai) {
            return safe(ai.text());
        }
        if (m instanceof UserMessage user) {
            return user.hasSingleText() ? safe(user.singleText()) : user.toString();
        }
        if (m instanceof SystemMessage sys) {
            return safe(sys.text());
        }
        return m.toString();
    }

    private static String safe(String s) {
        return s != null ? s : "";
    }
}
