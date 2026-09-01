package com.agentdemo.evaluation.eval;

import com.agentdemo.common.utils.JsonUtils;
import com.agentdemo.evaluation.model.AggregateResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 基线管理器（langsmith-observability CR-002 Task-31）
 * <p>
 * 业务含义：基线生成/持久化/对比（技术方案 §7.2.1 报告层，AC-N12）——
 * 基线经 JSON 落盘（data/eval/baseline.json，决策 14）；对比按噪声带宽
 * （10 例规模 ±30pp，§7.2 统计显著性声明）判定，带内差异标"不可决策"；
 * 常规运行只读不覆盖基线（更新须显式重建命令，AC-N12 防静默覆盖）。
 * </p>
 */
public class BaselineManager {

    /** 噪声带宽：10 例规模 95% CI 约 ±30 个百分点（§7.2 统计显著性声明） */
    public static final double NOISE_THRESHOLD = 0.30;

    /**
     * 由聚合结果构建基线条目
     *
     * @param agg           确定性聚合指标
     * @param version       数据集版本
     * @param model         被评模型名
     * @param configVersion 配置/Prompt 版本
     */
    public BaselineEntry build(AggregateResult agg, String version, String model, String configVersion) {
        return BaselineEntry.of(version, model, configVersion, agg.caseCount(), agg.passCount(),
                agg.passRate(), agg.toolSelectionRate(), agg.keywordMatchRate(),
                agg.maskInterceptRate(), agg.trapInterceptRate(), agg.runFailureCount());
    }

    /**
     * 持久化基线（显式重建入口，非对比路径调用）
     */
    public void saveBaseline(Path file, BaselineEntry entry) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, JsonUtils.toJson(entry));
        } catch (Exception e) {
            throw new IllegalStateException("基线持久化失败: " + file, e);
        }
    }

    /**
     * 加载基线
     *
     * @return 基线条目；文件不存在返回 null（首次评估尚无基线）
     */
    public BaselineEntry loadBaseline(Path file) {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return JsonUtils.fromJson(Files.readString(file), BaselineEntry.class);
        } catch (Exception e) {
            throw new IllegalStateException("基线加载失败: " + file, e);
        }
    }

    /**
     * 基线对比（只读，不写文件）
     *
     * @param base          基线（null 表示无基线，返回空报告）
     * @param current       当前聚合指标
     * @param configVersion 当前配置版本（追溯）
     * @return 对比报告
     */
    public ComparisonReport compare(BaselineEntry base, AggregateResult current, String configVersion) {
        if (base == null) {
            return new ComparisonReport(List.of(), false, List.of(), NOISE_THRESHOLD, configVersion);
        }
        List<MetricDelta> deltas = new ArrayList<>();
        deltas.add(metric("passRate", base.passRate(), current.passRate()));
        deltas.add(metric("toolSelectionRate", base.toolSelectionRate(), current.toolSelectionRate()));
        deltas.add(metric("keywordMatchRate", base.keywordMatchRate(), current.keywordMatchRate()));
        deltas.add(metric("maskInterceptRate", base.maskInterceptRate(), current.maskInterceptRate()));
        deltas.add(metric("trapInterceptRate", base.trapInterceptRate(), current.trapInterceptRate()));

        List<String> regression = deltas.stream()
                .filter(d -> "DEGRADED".equals(d.verdict()))
                .map(MetricDelta::metric).toList();
        return new ComparisonReport(deltas, !regression.isEmpty(), regression, NOISE_THRESHOLD, configVersion);
    }

    private static MetricDelta metric(String name, double baseline, double current) {
        double delta = current - baseline;
        String verdict;
        if (delta == 0) {
            verdict = "UNCHANGED";
        } else if (Math.abs(delta) <= NOISE_THRESHOLD) {
            verdict = "WITHIN_NOISE";
        } else if (delta > 0) {
            verdict = "IMPROVED";
        } else {
            verdict = "DEGRADED";
        }
        return new MetricDelta(name, baseline, current, delta, verdict);
    }
}
