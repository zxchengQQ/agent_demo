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
 * judge Prompt 制品契约冒烟（langsmith-observability CR-002 Task-30，EDD EVALUATE 本地代理）
 * <p>
 * 业务含义：在无真实模型环境下验证制品契约闭环（BUILD 集成验证）——制品可加载、
 * 含全部占位符、渲染正确、对符合契约的模型输出可完整解析（AC-N11）。
 * 真实模型 EVALUATE/TUNE 轮次依赖 ARK Key，与 Task-14 云侧联调同径延后。
 * </p>
 */
class JudgePromptTemplateTest {

    private final SensitiveDataMasker masker = new SensitiveDataMasker(4000);

    @Test
    void template_loadable_containsAllPlaceholders() {
        String t = JudgePromptTemplate.load();
        assertThat(t).isNotBlank();
        assertThat(t).contains("{{input}}", "{{response}}", "{{toolTrace}}");
        assertThat(t).contains("与回答长度无关");
        assertThat(t).contains("脱敏内容视为正常");
        assertThat(t).contains("\"overall\"");
    }

    @Test
    void renderedPrompt_fedToCanonicalModelResponse_parsesFully() {
        JudgeModelAccess access = mock(JudgeModelAccess.class);
        when(access.chat(anyString(), anyString())).thenReturn("""
                {"completenessScore":5,"completenessRationale":"完整","hallucination":"none","hallucinationRationale":"无编造","styleScore":4,"styleRationale":"简洁","overall":"pass"}
                """);
        JudgeEvaluator evaluator = new JudgeEvaluator(access, masker, JudgePromptTemplate.load());

        EvalCase c = new EvalCase("c1", "single-tool", "几点了", "getCurrentTime", List.of("时间"), false, false, "");
        JudgeResult r = evaluator.evaluate(c,
                new ExecutionRecord("c1", "几点了", "现在是 12:00", List.of("getCurrentTime"), null, 100),
                "judge-1", "agent-1");

        assertThat(r.absent()).isFalse();
        assertThat(r.verdict().completenessScore()).isEqualTo(5);
        assertThat(r.verdict().hallucination()).isEqualTo("none");
        assertThat(r.verdict().overall()).isEqualTo("pass");
    }

    @Test
    void renderedPrompt_masksSecretEvidence_beforeDispatch() {
        // 业务含义：AC-S07——制品渲染后发往 judge 的内容无明文密钥（脱敏出口前置）
        final String[] captured = new String[1];
        JudgeModelAccess access = mock(JudgeModelAccess.class);
        when(access.chat(anyString(), anyString())).thenAnswer(inv -> {
            captured[0] = inv.getArgument(0);
            return "{\"overall\":\"pass\"}";
        });
        JudgeEvaluator evaluator = new JudgeEvaluator(access, masker, JudgePromptTemplate.load());

        String secret = "sk-abcdefghijklmnopqrstuvwx";
        EvalCase c = new EvalCase("s1", "secret", "我的密钥 " + secret, "", List.of(), true, false, "");
        evaluator.evaluate(c, new ExecutionRecord("s1", c.input(), "保密", List.of(), null, 100), "judge-1", "agent-1");

        assertThat(captured[0]).doesNotContain(secret);
        assertThat(captured[0]).contains("[REDACTED]");
    }
}
