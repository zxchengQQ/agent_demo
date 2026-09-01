package com.agentdemo.evaluation.eval;

import java.util.List;

/**
 * 基线对比报告（langsmith-observability CR-002 Task-31）
 * <p>
 * 业务含义：评估重跑后与基线对比的聚合输出（AC-N12）——逐指标增量 +
 * 是否发生劣化（DEGRADED）汇总。供报告人读与候选验证门槛判定（劣化即不通过）。
 * </p>
 *
 * @param deltas            逐指标增量
 * @param hasRegression     是否存在超出噪声带宽的劣化
 * @param regressionMetrics 劣化指标名列表
 * @param noiseThreshold    噪声带宽（0.30 = ±30pp）
 * @param configVersion     当前配置版本（追溯本次对比的变更）
 */
public record ComparisonReport(
        List<MetricDelta> deltas,
        boolean hasRegression,
        List<String> regressionMetrics,
        double noiseThreshold,
        String configVersion) {
}
