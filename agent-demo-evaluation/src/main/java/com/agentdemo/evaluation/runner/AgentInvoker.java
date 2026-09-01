package com.agentdemo.evaluation.runner;

/**
 * Agent 调用方抽象（langsmith-observability CR-002 Task-27）
 * <p>
 * 业务含义：评估 harness 对"真实对话发起"的唯一依赖点——生产装配为
 * {@link SimpleAgentInvoker}（真实 SimpleAgent），测试注入 mock（三层 Mock 之
 * Agent 层）。入参会话由调用方（EvaluationRunner）保证每例独立（AC-N10）。
 * </p>
 */
@FunctionalInterface
public interface AgentInvoker {

    /**
     * 发起一次同步对话
     *
     * @param sessionId 会话 ID（评估时每例独立新建）
     * @param input     用户输入
     * @return Agent 最终回复文本
     */
    String invoke(String sessionId, String input);
}
