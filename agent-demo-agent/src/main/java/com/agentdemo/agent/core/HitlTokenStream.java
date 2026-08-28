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

    /**
     * 注册工具权限确认回调（默认空实现，不破坏现有实现类）
     * <p>
     * 业务含义：HITLReActStream 拦截 ask 级工具调用时触发，携带 toolCallId、工具名、工具描述与参数 JSON。
     * Controller 收到此回调后发送 tool_confirm SSE 事件（前端渲染权限确认卡片，AC-H01）。
     * 默认返回 this 保证链式调用，未注册回调的实现类（现有）行为不受影响。
     * </p>
     *
     * @param consumer 工具确认消费者
     * @return this（链式调用）
     */
    default HitlTokenStream onToolConfirm(ToolConfirmConsumer consumer) {
        return this;
    }

    /**
     * 工具权限确认消费者（携带 toolCallId、工具名、工具描述、参数 JSON）
     * <p>
     * 业务含义：决策 2 方案 A——HITLReActStream 快照职责外移，仅触发 4 参回调（含 toolCallId），
     * 由宿主决定快照持久化。toolCallId 为恢复时 ToolExecutionResultMessage 的 id 匹配必需（AC-M02）。
     * </p>
     */
    @FunctionalInterface
    interface ToolConfirmConsumer {
        void accept(String toolCallId, String toolName, String toolDescription, String arguments);
    }

    /**
     * 注册技能激活回调（默认空实现，不破坏现有实现类）
     * <p>
     * 业务含义：HITLReActStream 拦截 loadSkill 工具调用并激活成功后触发，携带技能名、来源与绑定工具。
     * Controller 收到此回调后发送 skill_activated SSE 事件（前端渲染激活徽标，AC-S04）。
     * 默认返回 this 保证链式调用。
     * </p>
     *
     * @param consumer 技能激活消费者
     * @return this（链式调用）
     */
    default HitlTokenStream onSkillActivated(SkillActivatedConsumer consumer) {
        return this;
    }

    /**
     * 技能激活消费者（携带技能 id、名称、激活来源与绑定工具）
     */
    @FunctionalInterface
    interface SkillActivatedConsumer {
        void accept(String skillId, String skillName, String source, java.util.List<String> boundToolIds);
    }
}
