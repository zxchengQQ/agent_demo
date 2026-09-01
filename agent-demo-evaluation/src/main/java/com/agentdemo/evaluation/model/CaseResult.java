package com.agentdemo.evaluation.model;

import java.util.List;

/**
 * 单用例评估结果（langsmith-observability CR-002 Task-28）
 * <p>
 * 业务含义：确定性评估器对单用例的判定输出——整体 pass 由全部运行次数
 * （Pass^runs）共同决定，failures 逐条记录失败原因与运行序号（技术方案 §7.2.1 评分层）。
 * </p>
 *
 * @param caseId          用例 ID
 * @param category        用例类型
 * @param pass            是否整体通过（全部运行通过）
 * @param failures        失败原因列表（含运行序号；空=通过）
 * @param runs            运行次数
 * @param observedTools   实际观察到的工具名（跨运行合并）
 * @param responsePreview 回复摘要（供报告人读，截断）
 */
public record CaseResult(
        String caseId,
        String category,
        boolean pass,
        List<String> failures,
        int runs,
        List<String> observedTools,
        String responsePreview) {

    public CaseResult {
        failures = failures == null ? List.of() : List.copyOf(failures);
        observedTools = observedTools == null ? List.of() : List.copyOf(observedTools);
        responsePreview = responsePreview == null ? "" : responsePreview;
    }
}
