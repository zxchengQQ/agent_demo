package com.agentdemo.agent.core;

import java.util.List;

/**
 * HITL 流式令牌流
 * <p>
 * 业务含义：在 ThinkingTokenStream 基础上增加 askUser 回调，
 * 用于 Agent 调用 askUser 工具时向前端推送提问事件（触发暂停-恢复流程）。
 * </p>
 * <p>
 * 调用方：web 层 AgentController.chatStream（当 enableHitl=true 时使用）
 * </p>
 */
public interface HitlTokenStream extends ThinkingTokenStream {

    // ==================== 覆盖父接口方法，协变返回类型为 HitlTokenStream ====================
    // 业务含义：保证链式调用 onAskUser 可用（父接口方法返回 ThinkingTokenStream，覆盖后返回 HitlTokenStream）

    @Override
    HitlTokenStream onPartialThinking(ThinkingConsumer consumer);

    @Override
    HitlTokenStream onPartialResponse(ResponseConsumer consumer);

    @Override
    HitlTokenStream onComplete(CompleteConsumer consumer);

    @Override
    HitlTokenStream onError(ErrorConsumer consumer);

    @Override
    HitlTokenStream onPartialThought(ThoughtConsumer consumer);

    @Override
    HitlTokenStream onAction(ActionConsumer consumer);

    @Override
    HitlTokenStream onObservation(ObservationConsumer consumer);

    @Override
    HitlTokenStream onFinalAnswer(FinalAnswerConsumer consumer);

    // ==================== 新增方法 ====================

    /**
     * 注册 askUser 回调
     * <p>
     * 业务含义：Agent 调用 askUser 工具时触发，携带提问类型、问题文本、选项列表和追问次数。
     * Controller 收到此回调后发送 ask_user SSE 事件并结束当前流。
     * </p>
     *
     * @param consumer askUser 消费者
     * @return this（链式调用）
     */
    HitlTokenStream onAskUser(AskUserConsumer consumer);

    /** askUser 消费者（携带提问类型、问题、选项和追问次数） */
    @FunctionalInterface
    interface AskUserConsumer {
        void accept(String type, String question, List<String> options, int retryCount);
    }
}
