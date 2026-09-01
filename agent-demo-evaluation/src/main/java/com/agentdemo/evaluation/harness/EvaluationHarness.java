package com.agentdemo.evaluation.harness;

import com.agentdemo.evaluation.config.EvalProperties;
import com.agentdemo.evaluation.eval.BaselineEntry;
import com.agentdemo.evaluation.eval.BaselineManager;
import com.agentdemo.evaluation.eval.ComparisonReport;
import com.agentdemo.evaluation.eval.DeterministicEvaluator;
import com.agentdemo.evaluation.eval.JudgeEvaluator;
import com.agentdemo.evaluation.eval.JudgeResult;
import com.agentdemo.evaluation.loader.EvalDatasetLoader;
import com.agentdemo.evaluation.model.AggregateResult;
import com.agentdemo.evaluation.model.CaseResult;
import com.agentdemo.evaluation.model.EvalCase;
import com.agentdemo.evaluation.model.EvalDataset;
import com.agentdemo.evaluation.model.ExecutionRecord;
import com.agentdemo.evaluation.runner.EvaluationRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 评估编排器（langsmith-observability CR-002 Task-34）
 * <p>
 * 业务含义：端到端评估流水线（技术方案 §7.2.1）——加载数据集 → 逐例执行 → 确定性
 * 评估 → judge 语义评分（每用例首条成功记录，成本控制）→ 基线首建/对比（AC-N12）
 * → 报告落盘。手动触发（CLI），不随正常启动执行。
 * </p>
 */
public class EvaluationHarness {

    private static final Logger log = LoggerFactory.getLogger(EvaluationHarness.class);

    private final EvalProperties props;
    private final EvalDatasetLoader loader;
    private final EvaluationRunner runner;
    private final DeterministicEvaluator deterministic;
    private final JudgeEvaluator judge;
    private final BaselineManager baseline;
    private final ReportWriter writer;

    public EvaluationHarness(EvalProperties props, EvalDatasetLoader loader, EvaluationRunner runner,
                             DeterministicEvaluator deterministic, JudgeEvaluator judge,
                             BaselineManager baseline, ReportWriter writer) {
        this.props = props;
        this.loader = loader;
        this.runner = runner;
        this.deterministic = deterministic;
        this.judge = judge;
        this.baseline = baseline;
        this.writer = writer;
    }

    /** 执行一次完整评估（CLI 触发入口） */
    public void runOnce() {
        long start = System.currentTimeMillis();
        Path datasetPath = Path.of(props.getDatasetPath());
        if (!Files.exists(datasetPath)) {
            // 业务含义：数据集缺失必须显式失败（抛异常 -> CLI 非零退出码），
            // 避免"评估完成但无报告、退出码 0"的静默假成功（AC-E06 退出码可辨语义）
            throw new IllegalStateException("评估数据集不存在，评估中止: " + datasetPath.toAbsolutePath());
        }
        EvalDataset ds = load(datasetPath);
        List<ExecutionRecord> records = runner.run(ds, props.getRuns());
        List<CaseResult> results = deterministic.evaluate(ds, records);
        AggregateResult agg = deterministic.aggregate(ds, records, results);
        List<JudgeResult> judgeResults = judgeCases(ds, records);

        Path baselinePath = Path.of(props.getBaselinePath());
        BaselineEntry base = baseline.loadBaseline(baselinePath);
        String model = props.getAgentModelId().isBlank() ? "默认" : props.getAgentModelId();
        if (base == null) {
            BaselineEntry first = baseline.build(agg, ds.version(), model, props.getConfigVersion());
            baseline.saveBaseline(baselinePath, first);
            writer.writeFirstBaseline(ds, records, results, agg, judgeResults, first);
            log.info("评估完成（首建基线）: {} 用例, Pass^runs={}, 耗时 {}ms",
                    agg.caseCount(), pct(agg.passRate()), System.currentTimeMillis() - start);
        } else {
            ComparisonReport comp = baseline.compare(base, agg, props.getConfigVersion());
            writer.writeComparison(ds, records, results, agg, judgeResults, base, comp);
            log.info("评估完成（基线对比）: Pass^runs={}, 劣化={}, 耗时 {}ms",
                    pct(agg.passRate()), comp.hasRegression(), System.currentTimeMillis() - start);
        }
    }

    private EvalDataset load(Path datasetPath) {
        try {
            return loader.load(Files.readString(datasetPath));
        } catch (Exception e) {
            throw new IllegalStateException("评估数据集读取失败: " + datasetPath, e);
        }
    }

    /** judge 逐用例评分：仅对每用例首条成功执行记录评分（成本控制，AC-N11/AC-E06） */
    private List<JudgeResult> judgeCases(EvalDataset ds, List<ExecutionRecord> records) {
        List<JudgeResult> out = new ArrayList<>();
        for (EvalCase c : ds.cases()) {
            if (props.getJudgeModelId() == null || props.getJudgeModelId().isBlank()) {
                out.add(JudgeResult.absent("未配置 judge 模型（eval.judge-model-id）"));
                continue;
            }
            ExecutionRecord firstOk = records.stream()
                    .filter(r -> r.caseId().equals(c.id()) && r.error() == null)
                    .findFirst().orElse(null);
            if (firstOk == null) {
                out.add(JudgeResult.absent("无成功执行记录可评"));
                continue;
            }
            out.add(judge.evaluate(c, firstOk, props.getJudgeModelId(), props.getAgentModelId()));
        }
        return out;
    }

    private static String pct(double v) {
        return String.format("%.0f%%", v * 100);
    }
}
