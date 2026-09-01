package com.agentdemo.evaluation.eval;

import com.agentdemo.common.utils.JsonUtils;
import com.agentdemo.evaluation.model.EvalCase;
import com.agentdemo.evaluation.model.ExecutionRecord;
import com.agentdemo.observability.SensitiveDataMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLM-as-judge 评估器（langsmith-observability CR-002 Task-29）
 * <p>
 * 业务含义：对单条执行记录做语义维度评分（技术方案 §7.2.1 评分层，AC-N11）——
 * 渲染 judge prompt（输入/回复/工具轨迹全部经脱敏出口，AC-S07 judge 出境通道）→
 * 调用模型 → 结构化 JSON 契约解析。任何失败（未配置/调用异常/解析失败）一律
 * 缺席标注（AC-E06），不抛异常不误计 0 分。同源模型启动 WARN（决策 13）。
 * </p>
 */
public class JudgeEvaluator {

    private static final Logger log = LoggerFactory.getLogger(JudgeEvaluator.class);

    private final JudgeModelAccess modelAccess;
    private final SensitiveDataMasker masker;
    private final String promptTemplate;

    public JudgeEvaluator(JudgeModelAccess modelAccess, SensitiveDataMasker masker, String promptTemplate) {
        this.modelAccess = modelAccess;
        this.masker = masker;
        this.promptTemplate = promptTemplate;
    }

    /**
     * 对单条执行记录评分
     *
     * @param c           用例（提供输入与约束上下文）
     * @param r           执行记录（回复与工具轨迹）
     * @param judgeModelId judge 模型 ID（空则缺席）
     * @param agentModelId 被评 Agent 模型 ID（空则回退默认，用于同源比对）
     * @return judge 结果（缺席时不抛异常）
     */
    public JudgeResult evaluate(EvalCase c, ExecutionRecord r, String judgeModelId, String agentModelId) {
        if (judgeModelId == null || judgeModelId.isBlank()) {
            return JudgeResult.absent("未配置 judge 模型（eval.judge-model-id）");
        }
        if (isSameSource(judgeModelId, agentModelId)) {
            log.warn("同源模型风险: judge 与被评 Agent 均使用模型 [{}]，评分可能存在同源偏差（决策 13）",
                    modelAccess.resolvedModelName(judgeModelId));
        }
        String prompt = render(c, r);
        try {
            return parse(modelAccess.chat(prompt, judgeModelId));
        } catch (Exception e) {
            log.warn("judge 调用失败: {}", e.getMessage());
            return JudgeResult.absent("judge 调用失败: " + e.getMessage());
        }
    }

    /**
     * 同源模型判定（包内可见供测试与报告）
     */
    boolean isSameSource(String judgeModelId, String agentModelId) {
        String j = modelAccess.resolvedModelName(judgeModelId);
        String a = (agentModelId == null || agentModelId.isBlank())
                ? modelAccess.defaultAgentModelName()
                : modelAccess.resolvedModelName(agentModelId);
        return j != null && j.equals(a);
    }

    /** 渲染 judge prompt：三要素统一经脱敏出口（AC-S07）；工具轨迹优先含结果详情（judge 幻觉复核） */
    private String render(EvalCase c, ExecutionRecord r) {
        String trace = (r.toolTraceDetail() == null || r.toolTraceDetail().isBlank())
                ? String.join(", ", r.toolTrace())
                : r.toolTraceDetail();
        return promptTemplate
                .replace("{{input}}", masker.maskSafe(c.input()))
                .replace("{{response}}", masker.maskSafe(r.response()))
                .replace("{{toolTrace}}", masker.maskSafe(trace));
    }

    private JudgeResult parse(String raw) {
        try {
            return JudgeResult.present(enforceVeto(JsonUtils.fromJson(raw, JudgeVerdict.class)));
        } catch (Exception e) {
            log.warn("judge 输出解析失败: {}", e.getMessage());
            String preview = raw == null ? "null" : (raw.length() > 80 ? raw.substring(0, 80) : raw);
            return JudgeResult.absent("judge 输出解析失败: " + preview);
        }
    }

    /**
     * 幻觉一票否决归一（技术方案 §7.2 Rubric veto 项，代码层强制）：
     * judge 判定 hallucination=detected 时，无论其自身输出的 overall 如何，一律归一为 fail——
     * 防止 judge 自相矛盾（幻觉 detected 却 overall=pass）导致否决被放过。
     */
    private static JudgeVerdict enforceVeto(JudgeVerdict v) {
        if (v != null && "detected".equalsIgnoreCase(v.hallucination())
                && !"fail".equalsIgnoreCase(v.overall())) {
            return new JudgeVerdict(v.completenessScore(), v.completenessRationale(), v.hallucination(),
                    v.hallucinationRationale(), v.styleScore(), v.styleRationale(), "fail");
        }
        return v;
    }
}
