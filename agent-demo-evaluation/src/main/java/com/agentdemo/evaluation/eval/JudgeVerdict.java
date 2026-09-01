package com.agentdemo.evaluation.eval;

/**
 * judge 结构化判定（langsmith-observability CR-002 Task-29/30）
 * <p>
 * 业务含义：judge 输出 JSON 契约的解析模型（技术方案 §7.2.1 输出契约）——
 * 维度分值（1-5）+ 理由 + 幻觉判定 + 总体判定。解析失败时由 {@link JudgeResult}
 * 缺席标注（AC-E06），本 record 不承载缺席态。
 * </p>
 *
 * @param completenessScore    回答完整性分值（1-5，缺失为 null）
 * @param completenessRationale 完整性理由
 * @param hallucination        幻觉判定：none / detected（缺失为 null）
 * @param hallucinationRationale 幻觉理由
 * @param styleScore           风格分值（1-5，缺失为 null）
 * @param styleRationale       风格理由
 * @param overall              总体判定：pass / fail（缺失为 null）
 */
public record JudgeVerdict(
        Integer completenessScore,
        String completenessRationale,
        String hallucination,
        String hallucinationRationale,
        Integer styleScore,
        String styleRationale,
        String overall) {
}
