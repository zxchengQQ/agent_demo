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
     * 注册显式 ReAct 推理片段回调（Thought 阶段）
     * <p>
     * 业务含义：显式 ReAct 循环中每轮的推理内容（Thought），区别于
     * onPartialThinking（方舟原生 reasoning_content 流式片段）。
     * iteration 标识当前 ReAct 迭代轮次（从 1 开始）。
     * </p>
     */
    ThinkingTokenStream onPartialThought(ThoughtConsumer consumer);

    /**
     * 注册工具调用回调（Action 阶段）
     * <p>
     * 业务含义：显式 ReAct 循环中模型决定调用某工具时触发，
     * 携带工具名、参数及当前迭代轮次。
     * </p>
     */
    ThinkingTokenStream onAction(ActionConsumer consumer);

    /**
     * 注册工具结果回调（Observation 阶段）
     * <p>
     * 业务含义：显式 ReAct 循环中工具执行返回结果时触发，
     * 携带结果文本及当前迭代轮次。
     * </p>
     */
    ThinkingTokenStream onObservation(ObservationConsumer consumer);

    /**
     * 注册最终回答标记回调
     * <p>
     * 业务含义：显式 ReAct 循环结束、模型给出最终回答时触发，
     * 仅携带迭代轮次，标识该轮为终止轮。
     * </p>
     */
    ThinkingTokenStream onFinalAnswer(FinalAnswerConsumer consumer);

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

    /** 显式 ReAct 推理片段消费者（Thought，携带迭代轮次） */
    @FunctionalInterface
    interface ThoughtConsumer {
        void accept(String thought, int iteration);
    }

    /** 工具调用消费者（Action，携带工具名、参数及迭代轮次） */
    @FunctionalInterface
    interface ActionConsumer {
        void accept(String toolName, String arguments, int iteration);
    }

    /** 工具结果消费者（Observation，携带结果及迭代轮次） */
    @FunctionalInterface
    interface ObservationConsumer {
        void accept(String result, int iteration);
    }

    /** 最终回答标记消费者（携带迭代轮次） */
    @FunctionalInterface
    interface FinalAnswerConsumer {
        void accept(int iteration);
    }
}
