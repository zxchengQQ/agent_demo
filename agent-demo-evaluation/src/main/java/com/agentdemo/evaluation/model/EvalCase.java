package com.agentdemo.evaluation.model;

import java.util.List;

/**
 * 评估用例（langsmith-observability CR-002，AC-N10 数据侧）
 * <p>
 * 业务含义：一条用例 = 输入 + 预期要点（目标工具/关键词）+ 陷阱/脱敏标记，
 * 供确定性评估器断言（技术方案 §7.2.1 数据层）。陷阱用例驱动一票否决
 * （幻觉判负），脱敏用例驱动安全 veto（AC-S07）。
 * </p>
 *
 * @param id                用例唯一标识
 * @param category          用例类型（direct-answer / single-tool / react / failure-recovery / secret / trap 等）
 * @param input             用户输入
 * @param expectedTool      预期调用的工具名（无工具期望为空串）
 * @param forbiddenTool     禁止调用的工具名（陷阱用例：越界请求不得调用，空串表示无约束）
 * @param expectedKeywords  最终回复应包含的关键词（可为空）
 * @param forbiddenKeywords 最终回复禁止包含的关键词（对抗拒绝断言：仅命中"跟随注入"的输出特征，
 *                            CR-001 审查加固，可为空）
 * @param containsSecret    是否含密钥正例（脱敏验证，AC-S07）
 * @param trap              是否陷阱用例（诱导幻觉/越界请求，一票否决）
 * @param trapExpectation   陷阱预期（NO_FABRICATION=不得编造；REJECT_OUT_OF_SCOPE=应拒绝越界）
 */
public record EvalCase(
        String id,
        String category,
        String input,
        String expectedTool,
        String forbiddenTool,
        List<String> expectedKeywords,
        List<String> forbiddenKeywords,
        boolean containsSecret,
        boolean trap,
        String trapExpectation) {

    public EvalCase {
        expectedTool = expectedTool == null ? "" : expectedTool;
        forbiddenTool = forbiddenTool == null ? "" : forbiddenTool;
        expectedKeywords = expectedKeywords == null ? List.of() : expectedKeywords;
        forbiddenKeywords = forbiddenKeywords == null ? List.of() : forbiddenKeywords;
        trapExpectation = trapExpectation == null ? "" : trapExpectation;
    }

    /**
     * 便捷构造：无禁止工具与禁止关键词（非陷阱用例默认入口，兼容数据集缺省字段）
     */
    public EvalCase(String id, String category, String input, String expectedTool,
                    List<String> expectedKeywords, boolean containsSecret, boolean trap, String trapExpectation) {
        this(id, category, input, expectedTool, "", expectedKeywords, List.of(), containsSecret, trap, trapExpectation);
    }

    /**
     * 便捷构造：含禁止工具、无禁止关键词（陷阱用例入口，兼容既有调用方）
     */
    public EvalCase(String id, String category, String input, String expectedTool, String forbiddenTool,
                    List<String> expectedKeywords, boolean containsSecret, boolean trap, String trapExpectation) {
        this(id, category, input, expectedTool, forbiddenTool, expectedKeywords, List.of(), containsSecret, trap, trapExpectation);
    }
}
