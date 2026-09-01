package com.agentdemo.evaluation.eval;

import com.agentdemo.evaluation.model.EvalCase;
import com.agentdemo.evaluation.model.EvalDataset;
import com.agentdemo.evaluation.runner.AgentInvoker;
import com.agentdemo.observability.SensitiveDataMasker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A/B 对比实验行为测试（langsmith-observability CR-002 Task-32）
 * <p>
 * 业务含义：验证双配置同数据集对比语义（AC-N13）——各配置 Pass^runs（下限 3 次
 * 强制）取均值、逐指标对比、噪声带宽内判平局（不轻率断言胜负，§7.2 统计显著性）。
 * </p>
 */
class ABComparisonRunnerTest {

    private final DeterministicEvaluator evaluator = new DeterministicEvaluator(new SensitiveDataMasker(4000));
    private final ABComparisonRunner runner = new ABComparisonRunner();

    private EvalCase toolCase(String id) {
        return new EvalCase(id, "single-tool", "几点了", "getCurrentTime", List.of("时间"), false, false, "");
    }

    @Test
    void compare_producesRowsForAllRateMetrics() {
        EvalDataset ds = new EvalDataset("v1", List.of(toolCase("c1")));
        RunSpec a = new RunSpec("prompt-v1", (s, i) -> "现在是 12:00");
        RunSpec b = new RunSpec("prompt-v2", (s, i) -> "现在是 12:00");

        ABReport report = runner.compare(ds, 3, a, b, evaluator);

        assertThat(report.labelA()).isEqualTo("prompt-v1");
        assertThat(report.labelB()).isEqualTo("prompt-v2");
        assertThat(report.runs()).isEqualTo(3);
        assertThat(report.rows()).extracting(ABRow::metric)
                .containsExactly("passRate", "toolSelectionRate", "keywordMatchRate", "maskInterceptRate", "trapInterceptRate");
    }

    @Test
    void compare_equalConfigs_allTied() {
        EvalDataset ds = new EvalDataset("v1", List.of(toolCase("c1")));
        RunSpec a = new RunSpec("A", (s, i) -> "现在是 12:00");
        RunSpec b = new RunSpec("B", (s, i) -> "现在是 12:00");

        ABReport report = runner.compare(ds, 3, a, b, evaluator);

        assertThat(report.rows()).allMatch(r -> r.winner().equals("TIED"));
        assertThat(report.significant()).isFalse();
    }

    @Test
    void compare_clearWinner_assignsWinner() {
        // 业务含义：AC-N13——两配置在可区分维度（关键词命中）差异超过噪声带宽（±30pp）时，
        // winner 明确赋值且 significant=true（防止单次运行结论、带内不轻率断言胜负）
        EvalDataset ds = new EvalDataset("v1", List.of(
                new EvalCase("c1", "direct-answer", "你好", "", "", List.of("时间"), false, false, "")));
        RunSpec a = new RunSpec("A", (s, i) -> "好的");                     // 缺关键词 -> 全败
        RunSpec b = new RunSpec("B", (s, i) -> "现在是 12:00 时间");          // 命中关键词 -> 全过

        ABReport report = runner.compare(ds, 3, a, b, evaluator);

        ABRow pass = report.rows().stream().filter(r -> r.metric().equals("passRate")).findFirst().orElseThrow();
        assertThat(pass.winner()).isEqualTo("B");
        assertThat(Math.abs(pass.delta())).isGreaterThan(BaselineManager.NOISE_THRESHOLD);
        assertThat(report.significant()).isTrue();
    }

    @Test
    void compare_runsClampedToMinimumThree() {
        // 业务含义：AC-N13 强制 Pass^3——即使调用方传 1 次，也至少运行 3 次取均值
        EvalDataset ds = new EvalDataset("v1", List.of(toolCase("c1")));
        AtomicInteger callsA = new AtomicInteger();
        RunSpec a = new RunSpec("A", (s, i) -> {
            callsA.incrementAndGet();
            return "回复";
        });
        RunSpec b = new RunSpec("B", (s, i) -> "回复");

        runner.compare(ds, 1, a, b, evaluator);

        assertThat(callsA.get()).isEqualTo(3);
    }

    @Test
    void compare_tiesDeclaredWithinNoise() {
        // 一例全对 vs 一例全对（同分）-> 平局；再验证 winner 含 TIED 语义在报告中可读
        EvalDataset ds = new EvalDataset("v1", List.of(toolCase("c1")));
        RunSpec a = new RunSpec("A", (s, i) -> "现在是 12:00");
        RunSpec b = new RunSpec("B", (s, i) -> "现在是 12:00");
        ABReport report = runner.compare(ds, 3, a, b, evaluator);
        assertThat(report.significant()).isFalse();
    }
}
