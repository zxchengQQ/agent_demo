package com.agentdemo.evaluation.eval;

import com.agentdemo.evaluation.model.AggregateResult;
import com.agentdemo.evaluation.model.CaseResult;
import com.agentdemo.evaluation.model.EvalDataset;
import com.agentdemo.evaluation.model.ExecutionRecord;
import com.agentdemo.evaluation.runner.EvaluationRunner;
import com.agentdemo.evaluation.runner.RecordingTraceCollector;

import java.util.List;

/**
 * A/B 对比实验运行器（langsmith-observability CR-002 Task-32）
 * <p>
 * 业务含义：同一数据集按两配置分别执行并逐指标对比（AC-N13）——各配置 Pass^runs
 * （强制下限 3 次取均值，防单次运行噪声结论）→ 确定性聚合 → 逐指标差异 + 显著性声明
 * （噪声带宽内判平局，§7.2 统计显著性声明）。
 * </p>
 */
public class ABComparisonRunner {

    /** 每配置最小运行次数（Pass^3 强制，AC-N13） */
    private static final int MIN_RUNS = 3;

    /**
     * 执行对比实验
     *
     * @param dataset   评估数据集（两配置共用）
     * @param runs      每配置运行次数（<3 时强制为 3）
     * @param a         配置 A
     * @param b         配置 B
     * @param evaluator 确定性评估器
     * @return 对比报告
     */
    public ABReport compare(EvalDataset dataset, int runs, RunSpec a, RunSpec b, DeterministicEvaluator evaluator) {
        int effectiveRuns = Math.max(MIN_RUNS, runs);
        AggregateResult aggA = runConfig(dataset, effectiveRuns, a, evaluator);
        AggregateResult aggB = runConfig(dataset, effectiveRuns, b, evaluator);

        List<ABRow> rows = List.of(
                row("passRate", aggA.passRate(), aggB.passRate()),
                row("toolSelectionRate", aggA.toolSelectionRate(), aggB.toolSelectionRate()),
                row("keywordMatchRate", aggA.keywordMatchRate(), aggB.keywordMatchRate()),
                row("maskInterceptRate", aggA.maskInterceptRate(), aggB.maskInterceptRate()),
                row("trapInterceptRate", aggA.trapInterceptRate(), aggB.trapInterceptRate()));
        boolean significant = rows.stream().anyMatch(r -> !"TIED".equals(r.winner()));
        return new ABReport(a.label(), b.label(), effectiveRuns, rows, significant);
    }

    private AggregateResult runConfig(EvalDataset dataset, int runs, RunSpec spec, DeterministicEvaluator evaluator) {
        RecordingTraceCollector collector = new RecordingTraceCollector();
        EvaluationRunner er = new EvaluationRunner(spec.invoker(), collector);
        List<ExecutionRecord> records = er.run(dataset, runs);
        List<CaseResult> results = evaluator.evaluate(dataset, records);
        return evaluator.aggregate(dataset, records, results);
    }

    private static ABRow row(String metric, double a, double b) {
        double delta = a - b;
        String winner;
        if (Math.abs(delta) <= BaselineManager.NOISE_THRESHOLD) {
            winner = "TIED";
        } else {
            winner = delta > 0 ? "A" : "B";
        }
        return new ABRow(metric, a, b, delta, winner);
    }
}
