package com.agentdemo.evaluation.eval;

import com.agentdemo.evaluation.runner.AgentInvoker;

/**
 * A/B 对比实验单配置（langsmith-observability CR-002 Task-32）
 * <p>
 * 业务含义：一次对比实验的一方（如不同 Prompt 版本 / 不同模型 / 不同参数配置），
 * 由标签 + Agent 调用方构成（AC-N13）。label 用于报告可读，invoker 承载配置差异。
 * </p>
 *
 * @param label   配置标签（如 prompt-v1 / doubao-pro）
 * @param invoker 该配置下的 Agent 调用方（生产装配为 SimpleAgentInvoker 的不同实例）
 */
public record RunSpec(String label, AgentInvoker invoker) {
}
