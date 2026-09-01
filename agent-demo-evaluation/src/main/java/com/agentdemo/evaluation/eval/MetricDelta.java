package com.agentdemo.evaluation.eval;

/**
 * 单指标对比增量（langsmith-observability CR-002 Task-31）
 * <p>
 * 业务含义：基线 vs 当前的单指标差异与判定（AC-N12）——verdict 语义：
 * IMPROVED/DEGRADED=超出噪声带宽的真实变化；WITHIN_NOISE=带内差异不可决策
 * （§7.2 统计显著性声明）；UNCHANGED=零差异。
 * </p>
 *
 * @param metric   指标名（passRate 等）
 * @param baseline 基线值
 * @param current  当前值
 * @param delta    当前 - 基线
 * @param verdict  判定（IMPROVED/DEGRADED/UNCHANGED/WITHIN_NOISE）
 */
public record MetricDelta(
        String metric,
        double baseline,
        double current,
        double delta,
        String verdict) {
}
