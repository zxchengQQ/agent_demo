package com.agentdemo.evaluation.model;

import java.util.List;

/**
 * 单次执行记录（langsmith-observability CR-002，AC-N10 执行层产物）
 * <p>
 * 业务含义：一次真实对话执行的完整留痕（请求/回复/工具轨迹/失败标注），
 * 是确定性评估器与 judge 的输入（技术方案 §7.2.1 执行层）。
 * </p>
 *
 * @param caseId          所属用例 ID
 * @param input           用户输入
 * @param response        Agent 最终回复（失败时为空串）
 * @param toolTrace       实际调用的工具名序列（按调用序，确定性断言用）
 * @param error           失败原因（null 表示成功）
 * @param durationMs      执行耗时
 * @param toolTraceDetail 含工具结果的轨迹文本（judge 幻觉复核用，已截断；空=未采集到）
 */
public record ExecutionRecord(
        String caseId,
        String input,
        String response,
        List<String> toolTrace,
        String error,
        long durationMs,
        String toolTraceDetail) {

    public ExecutionRecord {
        response = response == null ? "" : response;
        toolTrace = toolTrace == null ? List.of() : List.copyOf(toolTrace);
        toolTraceDetail = toolTraceDetail == null ? "" : toolTraceDetail;
    }

    /** 兼容构造（无工具结果详情，如 mock/测试场景） */
    public ExecutionRecord(String caseId, String input, String response, List<String> toolTrace,
                           String error, long durationMs) {
        this(caseId, input, response, toolTrace, error, durationMs, "");
    }
}
