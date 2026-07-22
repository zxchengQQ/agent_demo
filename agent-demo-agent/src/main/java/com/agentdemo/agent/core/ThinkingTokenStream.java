package com.agentdemo.agent.core;

/**
 * 思考流式令牌流（CR-001 新增）
 * <p>
 * 业务含义：区别于 LangChain4j TokenStream 仅回调 content，本接口区分推理内容与正式回复，
 * 供 AgentController SSE 接口分别推送 reasoning 和 token 事件。
 * </p>
 * <p>
 * 调用方：web 层 AgentController.chatStream（当 enableThinking=true 时使用）
 * </p>
 */
public interface ThinkingTokenStream {

    /**
     * 注册推理内容片段回调（对应方舟 delta.reasoning_content）
     */
    ThinkingTokenStream onPartialThinking(ThinkingConsumer consumer);

    /**
     * 注册正式回复片段回调（对应方舟 delta.content）
     */
    ThinkingTokenStream onPartialResponse(ResponseConsumer consumer);

    /**
     * 注册流式完成回调
     */
    ThinkingTokenStream onComplete(CompleteConsumer consumer);

    /**
     * 注册异常回调
     */
    ThinkingTokenStream onError(ErrorConsumer consumer);

    /**
     * 启动流式（异步）
     */
    void start();

    /** 推理内容片段消费者 */
    @FunctionalInterface
    interface ThinkingConsumer {
        void accept(String thinking);
    }

    /** 正式回复片段消费者 */
    @FunctionalInterface
    interface ResponseConsumer {
        void accept(String token);
    }

    /** 流式完成消费者（携带完整正式回复） */
    @FunctionalInterface
    interface CompleteConsumer {
        void accept(String fullResponse);
    }

    /** 异常消费者 */
    @FunctionalInterface
    interface ErrorConsumer {
        void accept(Throwable error);
    }
}
