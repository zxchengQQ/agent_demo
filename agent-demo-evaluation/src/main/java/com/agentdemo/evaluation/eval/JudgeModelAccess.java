package com.agentdemo.evaluation.eval;

/**
 * judge 模型访问抽象（langsmith-observability CR-002 Task-29）
 * <p>
 * 业务含义：评估 harness 对"judge 模型调用"的唯一依赖点——生产装配为
 * {@link SpringJudgeModelAccess}（ModelFactory + LlmConfigStore），测试注入 mock
 * （三层 Mock 之模型层）。模型名解析用于同源 WARN（决策 13）。
 * </p>
 */
public interface JudgeModelAccess {

    /**
     * 以指定模型调用一次 judge
     *
     * @param prompt  judge prompt（已脱敏渲染）
     * @param modelId judge 模型 ID
     * @return 模型原始输出
     * @throws RuntimeException 模型不可用/配额耗尽等
     */
    String chat(String prompt, String modelId);

    /**
     * 解析模型 ID 对应的实际模型名（用于同源比对；未解析返回 null）
     */
    String resolvedModelName(String modelId);

    /**
     * 默认被评 Agent 模型名（agentModelId 未配置时兜底；无配置返回 null）
     */
    String defaultAgentModelName();
}
