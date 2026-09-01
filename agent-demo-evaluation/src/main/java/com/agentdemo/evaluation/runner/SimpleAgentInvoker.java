package com.agentdemo.evaluation.runner;

import com.agentdemo.agent.single.SimpleAgent;

/**
 * 真实 Agent 调用方（langsmith-observability CR-002 Task-27 生产装配）
 * <p>
 * 业务含义：经 SimpleAgent 发起真实对话（同步路径，默认工具集；AC-N10 执行层
 * 生产实现）。modelId 为空时用默认模型，否则按指定模型——支撑 A/B 对比的模型维度
 * 差异（AC-N13）。
 * </p>
 */
public class SimpleAgentInvoker implements AgentInvoker {

    private final SimpleAgent simpleAgent;
    private final String modelId;

    public SimpleAgentInvoker(SimpleAgent simpleAgent) {
        this(simpleAgent, "");
    }

    public SimpleAgentInvoker(SimpleAgent simpleAgent, String modelId) {
        this.simpleAgent = simpleAgent;
        this.modelId = modelId == null ? "" : modelId;
    }

    @Override
    public String invoke(String sessionId, String input) {
        if (modelId.isBlank()) {
            return simpleAgent.chat(sessionId, input);
        }
        return simpleAgent.chat(sessionId, input, modelId, null);
    }
}
