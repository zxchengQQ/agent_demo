package com.agentdemo.agent.single;

import com.agentdemo.agent.core.ThinkingTokenStream;
import com.agentdemo.llm.thinking.ThinkingStreamHandler;
import com.agentdemo.llm.thinking.ThinkingStreamingChatModel;
import com.agentdemo.llm.thinking.ToolCall;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.output.TokenUsage;

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

    private final ThinkingStreamingChatModel model;
    private final List<ChatMessage> messages;

    private ThinkingConsumer thinkingConsumer;
    private ResponseConsumer responseConsumer;
    private CompleteConsumer completeConsumer;
    private ErrorConsumer errorConsumer;

    // CR-001 单轮思考模式不使用显式 ReAct 回调，以下字段仅保证接口适配编译通过
    private ThoughtConsumer thoughtConsumer;
    private ActionConsumer actionConsumer;
    private ObservationConsumer observationConsumer;
    private FinalAnswerConsumer finalAnswerConsumer;

    public ArkThinkingTokenStream(ThinkingStreamingChatModel model, List<ChatMessage> messages) {
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

    // 以下 4 个方法为 CR-001 单轮思考模式的空实现（仅赋值不消费），
    // 保证 ThinkingTokenStream 接口适配编译通过，向后兼容现有调用方
    @Override
    public ThinkingTokenStream onPartialThought(ThoughtConsumer consumer) {
        this.thoughtConsumer = consumer;
        return this;
    }

    @Override
    public ThinkingTokenStream onAction(ActionConsumer consumer) {
        this.actionConsumer = consumer;
        return this;
    }

    @Override
    public ThinkingTokenStream onObservation(ObservationConsumer consumer) {
        this.observationConsumer = consumer;
        return this;
    }

    @Override
    public ThinkingTokenStream onFinalAnswer(FinalAnswerConsumer consumer) {
        this.finalAnswerConsumer = consumer;
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

            // 业务含义：适配 Task-15 新签名，tokenUsage 由 ArkThinkingStreamingChatModel 传入，
            // 当前 ThinkingTokenStream 的 CompleteConsumer 仅消费 fullResponse，tokenUsage 暂不透传
            @Override
            public void onComplete(String fullResponse, String finishReason, TokenUsage tokenUsage) {
                if (completeConsumer != null) {
                    completeConsumer.accept(fullResponse);
                }
            }

            // 业务含义：Task-02 新增，调用方未注册工具调用消费者时做空实现
            @Override
            public void onToolCalls(List<ToolCall> toolCalls) {
                // 当前 ArkThinkingTokenStream 未注册工具调用消费者，不做任何事
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
