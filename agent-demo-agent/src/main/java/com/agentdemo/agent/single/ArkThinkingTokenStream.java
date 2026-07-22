package com.agentdemo.agent.single;

import com.agentdemo.agent.core.ThinkingTokenStream;
import com.agentdemo.llm.factory.ArkThinkingStreamingChatModel;
import com.agentdemo.llm.factory.ThinkingStreamHandler;
import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * 方舟思考流式令牌流实现（CR-001 新增）
 * <p>
 * 业务含义：将 ArkThinkingStreamingChatModel 的 ThinkingStreamHandler 回调桥接到
 * ThinkingTokenStream 的 4 个消费者，供 AgentController SSE 接口分别推送 reasoning 和 token 事件。
 * </p>
 * <p>
 * 位于 agent 模块的原因：ThinkingTokenStream 接口在 agent 模块，
 * ArkThinkingStreamingChatModel 在 llm 模块，agent 依赖 llm 故可同时访问两者。
 * </p>
 */
public class ArkThinkingTokenStream implements ThinkingTokenStream {

    private final ArkThinkingStreamingChatModel model;
    private final List<ChatMessage> messages;

    private ThinkingConsumer thinkingConsumer;
    private ResponseConsumer responseConsumer;
    private CompleteConsumer completeConsumer;
    private ErrorConsumer errorConsumer;

    public ArkThinkingTokenStream(ArkThinkingStreamingChatModel model, List<ChatMessage> messages) {
        this.model = model;
        this.messages = messages;
    }

    @Override
    public ThinkingTokenStream onPartialThinking(ThinkingConsumer consumer) {
        this.thinkingConsumer = consumer;
        return this;
    }

    @Override
    public ThinkingTokenStream onPartialResponse(ResponseConsumer consumer) {
        this.responseConsumer = consumer;
        return this;
    }

    @Override
    public ThinkingTokenStream onComplete(CompleteConsumer consumer) {
        this.completeConsumer = consumer;
        return this;
    }

    @Override
    public ThinkingTokenStream onError(ErrorConsumer consumer) {
        this.errorConsumer = consumer;
        return this;
    }

    /**
     * 启动流式调用
     * 业务含义：构造 ThinkingStreamHandler 桥接 4 个回调，调用 model.stream 执行流式调用。
     * ArkThinkingStreamingChatModel 内部会异步解析 SSE 流并通过 handler 回调。
     */
    @Override
    public void start() {
        // 业务含义：桥接 ThinkingStreamHandler 回调到 ThinkingTokenStream 消费者
        ThinkingStreamHandler handler = new ThinkingStreamHandler() {
            @Override
            public void onPartialThinking(String thinking) {
                if (thinkingConsumer != null) {
                    thinkingConsumer.accept(thinking);
                }
            }

            @Override
            public void onPartialResponse(String token) {
                if (responseConsumer != null) {
                    responseConsumer.accept(token);
                }
            }

            @Override
            public void onComplete(String fullResponse) {
                if (completeConsumer != null) {
                    completeConsumer.accept(fullResponse);
                }
            }

            @Override
            public void onError(Throwable error) {
                if (errorConsumer != null) {
                    errorConsumer.accept(error);
                }
            }
        };
        model.stream(messages, handler);
    }
}
