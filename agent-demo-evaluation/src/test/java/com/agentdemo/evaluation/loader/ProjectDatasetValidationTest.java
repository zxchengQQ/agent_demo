package com.agentdemo.evaluation.loader;

import com.agentdemo.evaluation.config.EvalProperties;
import com.agentdemo.evaluation.eval.BaselineEntry;
import com.agentdemo.evaluation.eval.JudgeResult;
import com.agentdemo.evaluation.eval.JudgeVerdict;
import com.agentdemo.evaluation.harness.ReportWriter;
import com.agentdemo.evaluation.model.AggregateResult;
import com.agentdemo.evaluation.model.CaseResult;
import com.agentdemo.evaluation.model.EvalCase;
import com.agentdemo.evaluation.model.EvalDataset;
import com.agentdemo.evaluation.model.ExecutionRecord;
import com.agentdemo.observability.SensitiveDataMasker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 项目数据集与报告脱敏验证（langsmith-observability CR-002 Task-33/34）
 * <p>
 * 业务含义：验证 data/eval/dataset.json 真实数据集可解析、用例数与类别覆盖
 * （AC-N10 用例完备性）；报告落盘经脱敏出口——即使 Agent 回复回显合成密钥，
 * 报告文件中不得出现明文密钥（AC-S07 产物零明文）。
 * </p>
 */
class ProjectDatasetValidationTest {

    private final EvalDatasetLoader loader = new EvalDatasetLoader();
    private final SensitiveDataMasker masker = new SensitiveDataMasker(4000);

    @Test
    void projectDataset_parses_andCoversRequiredCategories() throws Exception {
        Path dataset = Path.of("../data/eval/dataset.json");
        if (!Files.exists(dataset)) {
            return; // 非仓库根目录运行时不阻塞（CLI 从根目录运行时生效）
        }
        EvalDataset ds = loader.load(Files.readString(dataset));

        assertThat(ds.cases()).hasSizeGreaterThanOrEqualTo(10);
        assertThat(ds.cases()).extracting(EvalCase::category)
                .contains("direct-answer", "single-tool", "react", "failure-recovery", "secret", "trap");
        // 用例 ID 唯一
        assertThat(ds.cases()).extracting(EvalCase::id).doesNotHaveDuplicates();
        // 陷阱用例必须有禁止工具或拒绝关键词（确定性断言可用）
        for (EvalCase c : ds.cases()) {
            if (c.trap()) {
                assertThat(c.forbiddenTool() + String.join("", c.expectedKeywords()))
                        .as("陷阱用例 %s 需含 forbiddenTool 或 expectedKeywords", c.id()).isNotEmpty();
            }
        }
    }

    @Test
    void report_renders_withoutPlaintextSecret_evenWhenResponseEchoesSecret(@TempDir Path dir) throws Exception {
        // 业务含义：AC-S07——Agent 若回显密钥，报告（衍生产物）必须经脱敏出口，零明文
        String secret = "sk-abcdefghijklmnopqrstuvwx";
        EvalProperties props = new EvalProperties();
        props.setReportPath(dir.resolve("report.md").toString());

        EvalCase c = new EvalCase("s1", "secret", "密钥 " + secret, "", "", List.of(), true, false, "");
        EvalDataset ds = new EvalDataset("v1", List.of(c));
        ExecutionRecord rec = new ExecutionRecord("s1", "in", "回复包含 " + secret, List.of(), null, 10);
        CaseResult cr = new CaseResult("s1", "secret", false,
                List.of("运行1: 脱敏未拦截明文密钥 " + secret), 1, List.of(), "回复包含 " + secret);
        JudgeResult jr = JudgeResult.present(new JudgeVerdict(5, "完整", "none", "无", 4, "简洁", "pass"));
        BaselineEntry first = BaselineEntry.of("v1", "默认", "unknown", 1, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0);

        ReportWriter writer = new ReportWriter(props, masker);
        writer.writeFirstBaseline(ds, List.of(rec), List.of(cr), agg(), List.of(jr), first);

        String report = Files.readString(dir.resolve("report.md"));
        assertThat(report).doesNotContain(secret);
    }

    private AggregateResult agg() {
        return new AggregateResult(1, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0);
    }
}
