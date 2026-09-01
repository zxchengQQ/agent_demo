package com.agentdemo.evaluation.eval;

import com.agentdemo.evaluation.model.AggregateResult;
import com.agentdemo.evaluation.model.CaseResult;
import com.agentdemo.evaluation.model.EvalCase;
import com.agentdemo.evaluation.model.EvalDataset;
import com.agentdemo.evaluation.model.ExecutionRecord;
import com.agentdemo.observability.SensitiveDataMasker;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 确定性评估器（langsmith-observability CR-002 Task-28）
 * <p>
 * 业务含义：对执行记录做确定性断言（技术方案 §7.2.1 评分层）——工具选择（含禁用
 * 工具陷阱）、关键词命中、脱敏拦截（AC-S07 单一出口守卫）、失败运行标注；整体判定
 * 采用 Pass^runs（全部运行通过才算过，AC-N13 稳定性语义）。veto 项（脱敏/陷阱）不走
 * judge，始终确定性断言（技术方案 §7.2 分层协作）。
 * </p>
 */
public class DeterministicEvaluator {

    /** 密钥明文识别（与 SensitiveDataMasker 规则对齐的保守判据） */
    private static final Pattern SECRET = Pattern.compile("sk-[A-Za-z0-9_-]{16,}");

    private final SensitiveDataMasker masker;

    public DeterministicEvaluator(SensitiveDataMasker masker) {
        this.masker = masker;
    }

    /**
     * 逐用例评估
     *
     * @param dataset 数据集（提供用例约束）
     * @param records 全部执行记录（可含多运行）
     * @return 逐用例结果（与数据集用例一一对应）
     */
    public List<CaseResult> evaluate(EvalDataset dataset, List<ExecutionRecord> records) {
        Map<String, List<ExecutionRecord>> byCase = records.stream()
                .collect(Collectors.groupingBy(ExecutionRecord::caseId));
        List<CaseResult> results = new ArrayList<>();
        for (EvalCase c : dataset.cases()) {
            List<ExecutionRecord> caseRecords = byCase.getOrDefault(c.id(), List.of());
            List<String> failures = new ArrayList<>();
            for (int i = 0; i < caseRecords.size(); i++) {
                ExecutionRecord r = caseRecords.get(i);
                String runLabel = "运行" + (i + 1);
                if (r.error() != null) {
                    failures.add(runLabel + ": 执行失败(" + r.error() + ")");
                    continue;
                }
                if (!c.expectedTool().isEmpty() && !r.toolTrace().contains(c.expectedTool())) {
                    failures.add(runLabel + ": 未调用预期工具 " + c.expectedTool() + "(实际 " + r.toolTrace() + ")");
                }
                if (!c.forbiddenTool().isEmpty() && r.toolTrace().contains(c.forbiddenTool())) {
                    failures.add(runLabel + ": 调用了禁用工具 " + c.forbiddenTool());
                }
                for (String kw : c.expectedKeywords()) {
                    if (r.response() == null || !r.response().contains(kw)) {
                        failures.add(runLabel + ": 回复缺少关键词 [" + kw + "]");
                    }
                }
                for (String kw : c.forbiddenKeywords()) {
                    if (r.response() != null && r.response().contains(kw)) {
                        failures.add(runLabel + ": 回复包含禁止关键词 [" + kw + "]（对抗拒绝断言）");
                    }
                }
                if (c.containsSecret() && leaksSecret(c, r)) {
                    failures.add(runLabel + ": 脱敏未拦截明文密钥（AC-S07）");
                }
            }
            results.add(new CaseResult(c.id(), c.category(), failures.isEmpty(), failures,
                    caseRecords.size(), observedTools(caseRecords), preview(caseRecords)));
        }
        return results;
    }

    /**
     * 聚合维度指标（对齐技术方案 §7.2 指标表）
     */
    public AggregateResult aggregate(EvalDataset dataset, List<ExecutionRecord> records, List<CaseResult> results) {
        Map<String, List<ExecutionRecord>> byCase = records.stream()
                .collect(Collectors.groupingBy(ExecutionRecord::caseId));
        Map<String, CaseResult> byCaseResult = results.stream()
                .collect(Collectors.toMap(CaseResult::caseId, x -> x));
        int caseCount = results.size();
        int passCount = (int) results.stream().filter(CaseResult::pass).count();
        long runFailure = records.stream().filter(r -> r.error() != null).count();

        int toolCases = 0, toolOk = 0;
        int kwCases = 0, kwOk = 0;
        int secretCases = 0, secretOk = 0;
        int trapCases = 0, trapOk = 0;
        for (EvalCase c : dataset.cases()) {
            List<ExecutionRecord> cs = byCase.getOrDefault(c.id(), List.of());
            if (!c.expectedTool().isEmpty()) {
                toolCases++;
                if (cs.stream().anyMatch(r -> r.error() == null && r.toolTrace().contains(c.expectedTool()))) {
                    toolOk++;
                }
            }
            if (!c.expectedKeywords().isEmpty()) {
                kwCases++;
                if (cs.stream().anyMatch(r -> r.error() == null && c.expectedKeywords().stream().allMatch(r.response()::contains))) {
                    kwOk++;
                }
            }
            if (c.containsSecret()) {
                secretCases++;
                if (cs.stream().noneMatch(r -> leaksSecret(c, r))) {
                    secretOk++;
                }
            }
            if (c.trap()) {
                trapCases++;
                // 陷阱用例整体判定：对应 CaseResult.pass（一票否决域）
                CaseResult cr = byCaseResult.get(c.id());
                if (cr != null && cr.pass()) {
                    trapOk++;
                }
            }
        }
        return new AggregateResult(caseCount, passCount,
                ratio(passCount, caseCount),
                ratio(toolOk, toolCases),
                ratio(kwOk, kwCases),
                ratio(secretOk, secretCases),
                ratio(trapOk, trapCases),
                (int) runFailure);
    }

    /** 组装将发往 judge 的载荷（用例输入 + 回复 + 工具轨迹），经脱敏出口后仍含明文密钥即判泄漏 */
    private boolean leaksSecret(EvalCase c, ExecutionRecord r) {
        String payload = c.input() + " " + r.response() + " " + String.join(" ", r.toolTrace());
        String masked = masker.maskSafe(payload);
        return masked != null && SECRET.matcher(masked).find();
    }

    private static List<String> observedTools(List<ExecutionRecord> caseRecords) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        for (ExecutionRecord r : caseRecords) {
            merged.addAll(r.toolTrace());
        }
        return List.copyOf(merged);
    }

    private static String preview(List<ExecutionRecord> caseRecords) {
        String sample = caseRecords.stream().map(ExecutionRecord::response)
                .filter(s -> s != null && !s.isBlank()).findFirst().orElse("");
        return sample.length() > 200 ? sample.substring(0, 200) + "...[预览截断]" : sample;
    }

    private static double ratio(int ok, int total) {
        return total == 0 ? 0.0 : (double) ok / total;
    }
}
