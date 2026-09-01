package com.agentdemo.evaluation.eval;

import com.agentdemo.evaluation.model.EvalCase;
import com.agentdemo.evaluation.model.ExecutionRecord;
import com.agentdemo.observability.SensitiveDataMasker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LLM-as-judge 评估器行为测试（langsmith-observability CR-002 Task-29）
 * <p>
 * 业务含义：验证 judge 调用与结构化解析语义（AC-N11）——合法 JSON 契约解析、
 * 解析失败缺席标注（AC-E06）、未配置缺席、出境脱敏（AC-S07 judge 通道）、
 * 同源模型 WARN（决策 13）。
 * </p>
 */
class JudgeEvaluatorTest {

    private final JudgeModelAccess access = mock(JudgeModelAccess.class);
    private final SensitiveDataMasker masker = new SensitiveDataMasker(4000);
    private final String template = """
            你是评估阅卷员。
            用户输入：{{input}}
            Agent 回复：{{response}}
            工具轨迹：{{toolTrace}}
            请输出 JSON：{"completenessScore":5,"completenessRationale":"...","hallucination":"none","hallucinationRationale":"...","styleScore":4,"styleRationale":"...","overall":"pass"}
            """;
    private final JudgeEvaluator evaluator = new JudgeEvaluator(access, masker, template);

    private EvalCase caseWith(String id, String input, boolean secret) {
        return new EvalCase(id, "direct-answer", input, "", List.of(), secret, false, "");
    }

    @Test
    void validJson_parsesIntoVerdict() {
        when(access.chat(anyString(), anyString())).thenReturn("""
                {"completenessScore":5,"completenessRationale":"完整回答","hallucination":"none","hallucinationRationale":"无编造","styleScore":4,"styleRationale":"简洁","overall":"pass"}
                """);
        JudgeResult r = evaluator.evaluate(caseWith("c1", "你好", false),
                new ExecutionRecord("c1", "你好", "回复内容", List.of(), null, 100), "judge-1", "agent-1");

        assertThat(r.absent()).isFalse();
        assertThat(r.verdict().completenessScore()).isEqualTo(5);
        assertThat(r.verdict().hallucination()).isEqualTo("none");
        assertThat(r.verdict().overall()).isEqualTo("pass");
    }

    @Test
    void invalidJson_marksAbsent_notCrash() {
        when(access.chat(anyString(), anyString())).thenReturn("不是 JSON：这是模型闲聊");
        JudgeResult r = evaluator.evaluate(caseWith("c1", "你好", false),
                new ExecutionRecord("c1", "你好", "回复", List.of(), null, 100), "judge-1", "agent-1");

        assertThat(r.absent()).isTrue();
        assertThat(r.absentReason()).contains("解析失败");
    }

    @Test
    void chatThrows_marksAbsent_notZeroScore() {
        when(access.chat(anyString(), anyString())).thenThrow(new RuntimeException("配额耗尽"));
        JudgeResult r = evaluator.evaluate(caseWith("c1", "你好", false),
                new ExecutionRecord("c1", "你好", "回复", List.of(), null, 100), "judge-1", "agent-1");

        assertThat(r.absent()).isTrue();
        assertThat(r.absentReason()).contains("配额耗尽");
    }

    @Test
    void blankJudgeModel_marksAbsent() {
        JudgeResult r = evaluator.evaluate(caseWith("c1", "你好", false),
                new ExecutionRecord("c1", "你好", "回复", List.of(), null, 100), "", "agent-1");

        assertThat(r.absent()).isTrue();
        assertThat(r.absentReason()).contains("未配置");
    }

    @Test
    void secretCase_promptIsMasked_noPlaintextLeak() {
        // 业务含义：AC-S07——发往 judge 模型的 prompt 必须经脱敏出口，无明文密钥
        final String[] capturedPrompt = new String[1];
        when(access.chat(anyString(), anyString())).thenAnswer(inv -> {
            capturedPrompt[0] = inv.getArgument(0);
            return "{\"overall\":\"pass\",\"completenessScore\":5}";
        });
        String secret = "sk-abcdefghijklmnopqrstuvwx";
        EvalCase c = caseWith("s1", "我的密钥是 " + secret, true);
        evaluator.evaluate(c, new ExecutionRecord("s1", c.input(), "已保密", List.of(), null, 100), "judge-1", "agent-1");

        assertThat(capturedPrompt[0]).doesNotContain(secret);
        assertThat(capturedPrompt[0]).contains("[REDACTED]");
    }

    @Test
    void render_usesToolTraceDetailWithResult_whenPresent() {
        // 业务含义：judge 幻觉复核需工具结果证据——渲染优先采用含结果的轨迹详情
        final String[] capturedPrompt = new String[1];
        when(access.chat(anyString(), anyString())).thenAnswer(inv -> {
            capturedPrompt[0] = inv.getArgument(0);
            return "{\"overall\":\"pass\"}";
        });
        ExecutionRecord rec = new ExecutionRecord("c1", "几点了", "现在是 2026-08-31 12:00", List.of("getCurrentTime"),
                null, 100, "getCurrentTime → 2026-08-31 12:00:00");
        evaluator.evaluate(caseWith("c1", "几点了", false), rec, "judge-1", "agent-1");

        assertThat(capturedPrompt[0]).contains("getCurrentTime → 2026-08-31 12:00:00");
    }

    @Test
    void sameSource_detected_true() {
        when(access.resolvedModelName("judge-1")).thenReturn("doubao-pro");
        when(access.resolvedModelName("agent-1")).thenReturn("doubao-pro");
        assertThat(evaluator.isSameSource("judge-1", "agent-1")).isTrue();
    }

    @Test
    void differentSource_detected_false() {
        when(access.resolvedModelName("judge-1")).thenReturn("doubao-pro");
        when(access.resolvedModelName("agent-1")).thenReturn("deepseek-chat");
        assertThat(evaluator.isSameSource("judge-1", "agent-1")).isFalse();
    }

    @Test
    void blankAgentModel_fallsBackToDefault() {
        when(access.resolvedModelName("judge-1")).thenReturn("doubao-pro");
        when(access.defaultAgentModelName()).thenReturn("doubao-pro");
        assertThat(evaluator.isSameSource("judge-1", "")).isTrue();
    }

    @Test
    void hallucinationDetected_overallForcedToFail_evenIfModelSaysPass() {
        // 业务含义：§7.2 幻觉一票否决——judge 判定 hallucination=detected 时，代码层强制 overall=fail
        // （即便模型自相矛盾输出 overall=pass），否决不被放过
        when(access.chat(anyString(), anyString())).thenReturn("""
                {"completenessScore":5,"completenessRationale":"完整","hallucination":"detected","hallucinationRationale":"编造了工具结果","styleScore":4,"styleRationale":"简洁","overall":"pass"}
                """);
        JudgeResult r = evaluator.evaluate(caseWith("c1", "你好", false),
                new ExecutionRecord("c1", "你好", "回复", List.of(), null, 100), "judge-1", "agent-1");

        assertThat(r.absent()).isFalse();
        assertThat(r.verdict().hallucination()).isEqualTo("detected");
        assertThat(r.verdict().overall()).isEqualTo("fail");
    }
}
