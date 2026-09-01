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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评估编排器行为测试（langsmith-observability CR-002 Task-34 前置）
 * <p>
 * 业务含义：验证 EvaluationHarness 编排语义（AC-N12 基线首建/对比、AC-N11 judge
 * 逐用例评分、报告分派）——首建时生成并保存基线；存在基线时对比且不覆盖基线；
 * judge 仅对每用例首条成功记录评分（成本控制）。
 * </p>
 */
class EvaluationHarnessTest {

    @TempDir
    Path tempDir;

    private final EvalProperties props = new EvalProperties();
    private final EvalDatasetLoader loader = mock(EvalDatasetLoader.class);
    private final EvaluationRunner runner = mock(EvaluationRunner.class);
    private final DeterministicEvaluator deterministic = mock(DeterministicEvaluator.class);
    private final JudgeEvaluator judge = mock(JudgeEvaluator.class);
    private final BaselineManager baseline = mock(BaselineManager.class);
    private final ReportWriter writer = mock(ReportWriter.class);

    private EvaluationHarness harness() {
        return new EvaluationHarness(props, loader, runner, deterministic, judge, baseline, writer);
    }

    private Path datasetFile() throws Exception {
        Path f = tempDir.resolve("dataset.json");
        Files.writeString(f, "{}");
        return f;
    }

    private void stubAll(EvalDataset ds, List<ExecutionRecord> records, AggregateResult agg, List<CaseResult> results) {
        when(loader.load("{}")).thenReturn(ds);
        when(runner.run(ds, props.getRuns())).thenReturn(records);
        when(deterministic.evaluate(ds, records)).thenReturn(results);
        when(deterministic.aggregate(ds, records, results)).thenReturn(agg);
    }

    @Test
    void runOnce_noBaseline_buildsAndSavesBaseline_writesFirstBuildReport() throws Exception {
        props.setDatasetPath(datasetFile().toString());
        props.setBaselinePath(tempDir.resolve("baseline.json").toString());
        props.setRuns(3);
        props.setJudgeModelId("");

        EvalDataset ds = new EvalDataset("v1", List.of(
                new EvalCase("c1", "direct-answer", "你好", "", List.of("你好"), false, false, "")));
        ExecutionRecord rec = new ExecutionRecord("c1", "你好", "你好，有什么可以帮你", List.of(), null, 50);
        List<ExecutionRecord> records = List.of(rec);
        AggregateResult agg = new AggregateResult(1, 1, 1.0, 0.0, 1.0, 0.0, 0.0, 0);
        CaseResult cr = new CaseResult("c1", "direct-answer", true, List.of(), 1, List.of(), "你好");
        List<CaseResult> results = List.of(cr);
        stubAll(ds, records, agg, results);

        when(baseline.loadBaseline(tempDir.resolve("baseline.json"))).thenReturn(null);
        BaselineEntry built = mock(BaselineEntry.class);
        when(baseline.build(agg, "v1", "默认", "unknown")).thenReturn(built);

        harness().runOnce();

        verify(baseline).saveBaseline(eq(tempDir.resolve("baseline.json")), eq(built));
        verify(baseline, never()).compare(any(), any(), any());
        verify(writer).writeFirstBaseline(any(), any(), any(), any(), any(), any());
    }

    @Test
    void runOnce_hasBaseline_comparesWithoutOverwriting_writesComparisonReport() throws Exception {
        props.setDatasetPath(datasetFile().toString());
        props.setBaselinePath(tempDir.resolve("baseline.json").toString());
        props.setRuns(3);
        props.setJudgeModelId("");

        EvalDataset ds = new EvalDataset("v1", List.of(
                new EvalCase("c1", "direct-answer", "你好", "", List.of(), false, false, "")));
        ExecutionRecord rec = new ExecutionRecord("c1", "你好", "回复", List.of(), null, 50);
        List<ExecutionRecord> records = List.of(rec);
        AggregateResult agg = new AggregateResult(1, 1, 1.0, 0.0, 0.0, 0.0, 0.0, 0);
        CaseResult cr = new CaseResult("c1", "direct-answer", true, List.of(), 1, List.of(), "回复");
        List<CaseResult> results = List.of(cr);
        stubAll(ds, records, agg, results);

        BaselineEntry base = mock(BaselineEntry.class);
        when(baseline.loadBaseline(tempDir.resolve("baseline.json"))).thenReturn(base);
        ComparisonReport comp = mock(ComparisonReport.class);
        when(baseline.compare(base, agg, "unknown")).thenReturn(comp);

        harness().runOnce();

        verify(baseline, never()).saveBaseline(any(), any());
        verify(baseline).compare(base, agg, "unknown");
        verify(writer).writeComparison(any(), any(), any(), any(), any(), any(), eq(comp));
    }

    @Test
    void runOnce_judgeEnabled_scoresFirstSuccessfulRecordPerCase() throws Exception {
        props.setDatasetPath(datasetFile().toString());
        props.setBaselinePath(tempDir.resolve("baseline.json").toString());
        props.setJudgeModelId("judge-1");
        props.setAgentModelId("agent-1");
        props.setRuns(3);

        EvalCase c = new EvalCase("c1", "direct-answer", "你好", "", List.of(), false, false, "");
        EvalDataset ds = new EvalDataset("v1", List.of(c));
        ExecutionRecord rec = new ExecutionRecord("c1", "你好", "回复", List.of(), null, 50);
        List<ExecutionRecord> records = List.of(rec);
        AggregateResult agg = new AggregateResult(1, 1, 1.0, 0.0, 0.0, 0.0, 0.0, 0);
        CaseResult cr = new CaseResult("c1", "direct-answer", true, List.of(), 1, List.of(), "回复");
        List<CaseResult> results = List.of(cr);
        stubAll(ds, records, agg, results);
        when(baseline.loadBaseline(tempDir.resolve("baseline.json"))).thenReturn(null);

        harness().runOnce();

        verify(judge).evaluate(eq(c), eq(rec), eq("judge-1"), eq("agent-1"));
    }

    @Test
    void runOnce_judgeEnabled_skipsFailedRecords_scoresFirstSuccess() throws Exception {
        // 业务含义：judge 仅对每用例首条"成功"执行记录评分（成本控制）——
        // 首条失败记录不得送入 judge，需跳过取后续成功记录
        props.setDatasetPath(datasetFile().toString());
        props.setBaselinePath(tempDir.resolve("baseline.json").toString());
        props.setJudgeModelId("judge-1");
        props.setAgentModelId("agent-1");
        props.setRuns(2);

        EvalCase c = new EvalCase("c1", "direct-answer", "你好", "", List.of(), false, false, "");
        EvalDataset ds = new EvalDataset("v1", List.of(c));
        ExecutionRecord failed = new ExecutionRecord("c1", "你好", "", List.of(), "模型不可用", 10);
        ExecutionRecord success = new ExecutionRecord("c1", "你好", "回复", List.of(), null, 10);
        List<ExecutionRecord> records = List.of(failed, success);
        AggregateResult agg = new AggregateResult(1, 1, 1.0, 0.0, 1.0, 0.0, 0.0, 1);
        CaseResult cr = new CaseResult("c1", "direct-answer", true, List.of(), 2, List.of(), "回复");
        List<CaseResult> results = List.of(cr);
        stubAll(ds, records, agg, results);
        when(baseline.loadBaseline(tempDir.resolve("baseline.json"))).thenReturn(null);

        harness().runOnce();

        verify(judge).evaluate(eq(c), eq(success), eq("judge-1"), eq("agent-1"));
        verify(judge, never()).evaluate(eq(c), eq(failed), any(), any());
    }
}
