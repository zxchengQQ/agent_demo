package com.agentdemo.evaluation.eval;

import java.util.List;

/**
 * A/B 对比实验报告（langsmith-observability CR-002 Task-32）
 * <p>
 * 业务含义：双配置同数据集对比的最终产物（AC-N13）——含运行次数、逐指标对比行、
 * 显著性声明（significant=false 表示全部带内平局，结论不具统计说服力）。
 * </p>
 *
 * @param labelA     配置 A 标签
 * @param labelB     配置 B 标签
 * @param runs       每配置运行次数（>=3，Pass^3 强制）
 * @param rows       逐指标对比行
 * @param significant 是否存在超出噪声带宽的胜负（统计显著性声明）
 */
public record ABReport(
        String labelA,
        String labelB,
        int runs,
        List<ABRow> rows,
        boolean significant) {
}
