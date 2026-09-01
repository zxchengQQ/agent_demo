package com.agentdemo.evaluation.harness;

import com.agentdemo.evaluation.config.EvalProperties;
import com.agentdemo.evaluation.eval.BaselineEntry;
import com.agentdemo.evaluation.eval.ComparisonReport;
import com.agentdemo.evaluation.eval.JudgeResult;
import com.agentdemo.evaluation.eval.MetricDelta;
import com.agentdemo.evaluation.model.AggregateResult;
import com.agentdemo.evaluation.model.CaseResult;
import com.agentdemo.evaluation.model.EvalDataset;
import com.agentdemo.evaluation.model.ExecutionRecord;
import com.agentdemo.observability.SensitiveDataMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 评估报告输出器（langsmith-observability CR-002 Task-31/34，决策 14）
 * <p>
 * 业务含义：将评估产物渲染为 Markdown 报告并落盘（data/eval/report.md）——
 * 首建报告（含基线快照）与对比报告（含劣化标注/噪声带宽声明，AC-N12）两种形态；
 * judge 缺席逐条标注（AC-E06 可辨）。所有衍生自运行数据的内容（回复预览/失败
 * 原因/judge 理由）经脱敏出口后落盘（AC-S07 产物零明文）。
 * </p>
 */
public class ReportWriter {

    private static final Logger log = LoggerFactory.getLogger(ReportWriter.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final EvalProperties props;
    private final SensitiveDataMasker masker;

    public ReportWriter(EvalProperties props, SensitiveDataMasker masker) {
        this.props = props;
        this.masker = masker;
    }

    /** 首建报告：含基线快照与聚合指标 */
    public void writeFirstBaseline(EvalDataset ds, List<ExecutionRecord> records, List<CaseResult> results,
                                   AggregateResult agg, List<JudgeResult> judgeResults, BaselineEntry first) {
        write(buildHeader(ds, records) + "\n## 评估结果（首次基线）\n"
                + caseTable(results, judgeResults) + "\n"
                + aggregateTable(agg) + "\n"
                + "## 首建基线\n\n- 数据集版本: " + first.version()
                + "\n- 模型: " + first.model() + " | 配置版本: " + first.configVersion()
                + "\n- Pass^runs 通过率: " + pct(first.passRate())
                + "\n\n> 本次为首建基线，后续变更重跑将以本基线为回归判定基准（AC-N12）。\n");
    }

    /** 对比报告：与基线逐指标对比 + 劣化标注 */
    public void writeComparison(EvalDataset ds, List<ExecutionRecord> records, List<CaseResult> results,
                                AggregateResult agg, List<JudgeResult> judgeResults,
                                BaselineEntry base, ComparisonReport comp) {
        StringBuilder sb = new StringBuilder(buildHeader(ds, records));
        sb.append("\n## 评估结果（基线对比）\n").append(caseTable(results, judgeResults)).append('\n')
          .append(aggregateTable(agg)).append("\n## 基线对比\n\n| 指标 | 基线 | 当前 | 差异 | 判定 |\n|---|---|---|---|---|\n");
        for (MetricDelta d : comp.deltas()) {
            sb.append("| ").append(d.metric()).append(" | ").append(pct(d.baseline()))
              .append(" | ").append(pct(d.current())).append(" | ").append(sign(d.delta()))
              .append(" | ").append(d.verdict()).append(" |\n");
        }
        sb.append("\n- 噪声带宽: ±").append((int) (comp.noiseThreshold() * 100))
          .append("pp（10 例规模 95% CI，带内差异不可决策）\n");
        if (comp.hasRegression()) {
            sb.append("- **劣化告警**: ").append(String.join(", ", comp.regressionMetrics())).append(" 超出噪声带宽，候选不通过\n");
        } else {
            sb.append("- 无超出噪声带宽的劣化。\n");
        }
        write(sb.toString());
    }

    private String buildHeader(EvalDataset ds, List<ExecutionRecord> records) {
        long errors = records.stream().filter(r -> r.error() != null).count();
        return "# Agent 本地评估报告\n\n- 时间: " + LocalDateTime.now().format(FMT)
                + "\n- 数据集版本: " + ds.version() + " | 用例数: " + ds.cases().size()
                + "\n- 运行次数/用例: " + props.getRuns() + " | 执行失败记录: " + errors
                + "\n- judge 模型: " + (props.getJudgeModelId().isBlank() ? "未启用" : props.getJudgeModelId())
                + " | Agent 模型: " + (props.getAgentModelId().isBlank() ? "默认" : props.getAgentModelId())
                + "\n- 配置版本: " + props.getConfigVersion();
    }

    private String caseTable(List<CaseResult> results, List<JudgeResult> judgeResults) {
        StringBuilder sb = new StringBuilder("## 逐用例\n\n| 用例 | 类型 | 通过 | 失败原因 | judge |\n|---|---|---|---|---|\n");
        for (int i = 0; i < results.size(); i++) {
            CaseResult cr = results.get(i);
            JudgeResult jr = i < judgeResults.size() ? judgeResults.get(i) : null;
            String judgeCell = jr == null ? "-" : (jr.absent()
                    ? "缺席(" + truncate(jr.absentReason(), 40) + ")"
                    : (jr.verdict().hallucination() != null && "detected".equals(jr.verdict().hallucination())
                        ? "幻觉 detected（veto 判负）" : "overall=" + jr.verdict().overall()));
            sb.append("| ").append(cr.caseId()).append(" | ").append(cr.category())
              .append(" | ").append(cr.pass() ? "✅" : "❌")
              .append(" | ").append(cr.failures().isEmpty() ? "-"
                        : truncate(masker.maskSafe(String.join("; ", cr.failures())), 80))
              .append(" | ").append(masker.maskSafe(judgeCell)).append(" |\n");
        }
        return sb.toString();
    }

    private String aggregateTable(AggregateResult agg) {
        return "## 聚合指标\n\n| 指标 | 值 |\n|---|---|\n"
                + "| Pass^runs 通过率 | " + pct(agg.passRate()) + " (" + agg.passCount() + "/" + agg.caseCount() + ") |\n"
                + "| 工具选择正确率 | " + pct(agg.toolSelectionRate()) + " |\n"
                + "| 关键词命中率 | " + pct(agg.keywordMatchRate()) + " |\n"
                + "| 脱敏拦截率 | " + pct(agg.maskInterceptRate()) + " |\n"
                + "| 陷阱拦截率 | " + pct(agg.trapInterceptRate()) + " |\n"
                + "| 执行失败运行数 | " + agg.runFailureCount() + " |\n";
    }

    private void write(String markdown) {
        try {
            Path out = Path.of(props.getReportPath());
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            Files.writeString(out, markdown);
            log.info("评估报告已写入: {}", out.toAbsolutePath());
        } catch (Exception e) {
            log.warn("评估报告写入失败: {}", e.getMessage());
        }
    }

    private static String pct(double v) {
        return String.format("%.0f%%", v * 100);
    }

    private static String sign(double d) {
        return d > 0 ? "+" + String.format("%.0f%%", d * 100) : String.format("%.0f%%", d * 100);
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() > max ? s.substring(0, max) + "…" : s);
    }
}
