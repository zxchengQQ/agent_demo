package com.agentdemo.evaluation.eval;

/**
 * judge 评估结果（langsmith-observability CR-002 Task-29）
 * <p>
 * 业务含义：结构化判定 + 缺席标注的容器（AC-N11/AC-E06）——verdict 为 null 表示
 * 该维度/整体缺席（judge 不可用/解析失败/未配置），缺席项不参与指标聚合
 * （不得误计 0 分）。
 * </p>
 *
 * @param verdict      结构化判定（缺席为 null）
 * @param absentReason 缺席原因（正常判定为 null）
 */
public record JudgeResult(JudgeVerdict verdict, String absentReason) {

    public static JudgeResult present(JudgeVerdict verdict) {
        return new JudgeResult(verdict, null);
    }

    public static JudgeResult absent(String reason) {
        return new JudgeResult(null, reason);
    }

    public boolean absent() {
        return verdict == null;
    }
}
