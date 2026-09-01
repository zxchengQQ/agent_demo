package com.agentdemo.evaluation.eval;

/**
 * A/B 对比单指标行（langsmith-observability CR-002 Task-32）
 * <p>
 * 业务含义：两配置在单指标上的对比（AC-N13）——winner 语义：
 * A/B=超出噪声带宽的真实胜负；TIED=带内平局（不做轻率胜负断言，§7.2 统计显著性）。
 * </p>
 *
 * @param metric  指标名
 * @param valueA  配置 A 均值
 * @param valueB  配置 B 均值
 * @param delta   值 A - 值 B
 * @param winner  胜者（A / B / TIED）
 */
public record ABRow(String metric, double valueA, double valueB, double delta, String winner) {
}
