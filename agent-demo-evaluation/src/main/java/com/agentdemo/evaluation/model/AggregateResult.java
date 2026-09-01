package com.agentdemo.evaluation.model;

/**
 * 聚合评估指标（langsmith-observability CR-002 Task-28）
 * <p>
 * 业务含义：确定性评估聚合输出，对齐技术方案 §7.2 评估指标表——工具选择正确率
 * （>=90%）、Pass^3 稳定性（>=80%）、脱敏拦截率（100% 零容忍）、陷阱拦截率。
 * 各维度率基于"有该维度约束的用例子集"计算，避免无约束用例稀释。
 * </p>
 *
 * @param caseCount           用例总数
 * @param passCount           Pass^runs 通过用例数
 * @param passRate            通过率（passCount / caseCount）
 * @param toolSelectionRate   预期工具用例中正确选择比例
 * @param keywordMatchRate    有关键词用例中全部命中比例
 * @param maskInterceptRate   脱敏用例中零明文泄漏比例（100% 目标）
 * @param trapInterceptRate   陷阱用例中通过比例（一票否决域）
 * @param runFailureCount     执行失败运行次数（error 记录数）
 */
public record AggregateResult(
        int caseCount,
        int passCount,
        double passRate,
        double toolSelectionRate,
        double keywordMatchRate,
        double maskInterceptRate,
        double trapInterceptRate,
        int runFailureCount) {
}
