package com.agentdemo.evaluation.eval;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 基线条目（langsmith-observability CR-002 Task-31）
 * <p>
 * 业务含义：一次评估运行的指标快照 + 运行元数据（技术方案 §7.2.1 报告层，决策 14）——
 * 元数据（数据集版本/模型/配置版本/创建时间）保证基线可追溯、可对比；
 * 配置版本用于识别"哪次变更产生了该基线"。
 * </p>
 *
 * @param version           数据集版本
 * @param createdAt         创建时间（ISO 本地时间）
 * @param model             被评模型名
 * @param configVersion     配置/Prompt 版本（变更追溯键）
 * @param caseCount         用例数
 * @param passCount         通过用例数
 * @param passRate          Pass^runs 通过率
 * @param toolSelectionRate 工具选择正确率
 * @param keywordMatchRate  关键词命中率
 * @param maskInterceptRate 脱敏拦截率
 * @param trapInterceptRate 陷阱拦截率
 * @param runFailureCount   执行失败运行次数
 */
public record BaselineEntry(
        String version,
        String createdAt,
        String model,
        String configVersion,
        int caseCount,
        int passCount,
        double passRate,
        double toolSelectionRate,
        double keywordMatchRate,
        double maskInterceptRate,
        double trapInterceptRate,
        int runFailureCount) {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static BaselineEntry of(String version, String model, String configVersion,
                                   int caseCount, int passCount, double passRate,
                                   double toolSelectionRate, double keywordMatchRate,
                                   double maskInterceptRate, double trapInterceptRate,
                                   int runFailureCount) {
        return new BaselineEntry(version, LocalDateTime.now().format(FMT), model, configVersion,
                caseCount, passCount, passRate, toolSelectionRate, keywordMatchRate,
                maskInterceptRate, trapInterceptRate, runFailureCount);
    }
}
