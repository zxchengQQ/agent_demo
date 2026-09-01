package com.agentdemo.evaluation.eval;

import com.agentdemo.evaluation.model.AggregateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 基线管理行为测试（langsmith-observability CR-002 Task-31）
 * <p>
 * 业务含义：验证基线生成/持久化/对比语义（AC-N12）——JSON 基线含运行元数据、
 * 缺失文件返回空、对比按噪声带宽（±30pp）判定改善/劣化/不可决策、
 * 常规运行只读不覆盖基线（显式重建）。
 * </p>
 */
class BaselineManagerTest {

    private final BaselineManager manager = new BaselineManager();

    private AggregateResult agg(double passRate, double toolRate, double maskRate, double trapRate) {
        return new AggregateResult(10, (int) (10 * passRate), passRate, toolRate, 0.9, maskRate, trapRate, 0);
    }

    @Test
    void build_carriesMetadataAndMetrics() {
        BaselineEntry e = manager.build(agg(0.8, 0.9, 1.0, 1.0), "v1", "doubao-pro", "prompt-v1.0");

        assertThat(e.version()).isEqualTo("v1");
        assertThat(e.model()).isEqualTo("doubao-pro");
        assertThat(e.configVersion()).isEqualTo("prompt-v1.0");
        assertThat(e.passRate()).isEqualTo(0.8);
        assertThat(e.maskInterceptRate()).isEqualTo(1.0);
        assertThat(e.createdAt()).isNotBlank();
    }

    @Test
    void saveThenLoad_roundTrips() throws Exception {
        Path file = Files.createTempFile("baseline", ".json");
        BaselineEntry e = manager.build(agg(0.8, 0.9, 1.0, 1.0), "v1", "doubao-pro", "prompt-v1.0");
        manager.saveBaseline(file, e);

        BaselineEntry loaded = manager.loadBaseline(file);
        assertThat(loaded).isEqualTo(e);
    }

    @Test
    void loadMissingFile_returnsNull() {
        assertThat(manager.loadBaseline(Path.of("/nonexistent/baseline.json"))).isNull();
    }

    @Test
    void compare_improvedBeyondNoise_isImproved() {
        BaselineEntry base = manager.build(agg(0.5, 0.9, 1.0, 1.0), "v1", "doubao-pro", "v1");
        ComparisonReport report = manager.compare(base, agg(0.9, 0.9, 1.0, 1.0), "v2");

        MetricDelta d = metric(report, "passRate");
        assertThat(d.verdict()).isEqualTo("IMPROVED");
        assertThat(report.hasRegression()).isFalse();
    }

    @Test
    void compare_degradedBeyondNoise_isRegression() {
        BaselineEntry base = manager.build(agg(0.9, 0.9, 1.0, 1.0), "v1", "doubao-pro", "v1");
        ComparisonReport report = manager.compare(base, agg(0.5, 0.9, 1.0, 1.0), "v2");

        MetricDelta d = metric(report, "passRate");
        assertThat(d.verdict()).isEqualTo("DEGRADED");
        assertThat(report.hasRegression()).isTrue();
        assertThat(report.regressionMetrics()).contains("passRate");
    }

    @Test
    void compare_withinNoise_isNotDecision() {
        // 业务含义：10 例规模 95% CI 约 ±30pp，分差小于噪声带宽不做迭代决策（§7.2 统计显著性声明）
        BaselineEntry base = manager.build(agg(0.6, 0.9, 1.0, 1.0), "v1", "doubao-pro", "v1");
        ComparisonReport report = manager.compare(base, agg(0.7, 0.9, 1.0, 1.0), "v2");

        MetricDelta d = metric(report, "passRate");
        assertThat(d.verdict()).isEqualTo("WITHIN_NOISE");
        assertThat(report.hasRegression()).isFalse();
    }

    @Test
    void compare_zeroDelta_isUnchanged() {
        BaselineEntry base = manager.build(agg(0.8, 0.9, 1.0, 1.0), "v1", "doubao-pro", "v1");
        ComparisonReport report = manager.compare(base, agg(0.8, 0.9, 1.0, 1.0), "v2");

        assertThat(metric(report, "passRate").verdict()).isEqualTo("UNCHANGED");
    }

    @Test
    void compare_coversAllRateMetrics() {
        BaselineEntry base = manager.build(agg(0.8, 0.9, 1.0, 1.0), "v1", "doubao-pro", "v1");
        ComparisonReport report = manager.compare(base, agg(0.8, 0.9, 1.0, 1.0), "v2");

        assertThat(report.deltas()).extracting(MetricDelta::metric)
                .containsExactly("passRate", "toolSelectionRate", "keywordMatchRate", "maskInterceptRate", "trapInterceptRate");
    }

    private static MetricDelta metric(ComparisonReport report, String name) {
        return report.deltas().stream().filter(d -> d.metric().equals(name)).findFirst().orElseThrow();
    }
}
