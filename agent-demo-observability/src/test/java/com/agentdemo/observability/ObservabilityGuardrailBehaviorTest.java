package com.agentdemo.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 脱敏与护栏行为测试（langsmith-observability Task-12）
 * <p>
 * 业务含义：跨 collector + masker + 装配栈的行为级验证（技术方案 §6 护栏落地）：
 * 脱敏正例 100% 命中 / 反例 0% 误伤（AC-S01）、无 Key 零外联（AC-S02）、
 * 上报失败静默降级不抛异常（AC-S04）、超长截断（AC-E02）、启停确定性（AC-E03）。
 * </p>
 */
class ObservabilityGuardrailBehaviorTest {

    private InMemorySpanExporter exporter;

    @AfterEach
    void tearDown() {
        TraceContextHolder.clear();
        if (exporter != null) {
            exporter.reset();
        }
    }

    private OtlpTraceCollector newCollector(int maxChars, String... secrets) {
        exporter = InMemorySpanExporter.create();
        SdkTracerProvider tp = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetry otel = OpenTelemetrySdk.builder().setTracerProvider(tp).build();
        return new OtlpTraceCollector(otel, new SensitiveDataMasker(maxChars, List.of(secrets)));
    }

    private String spanAttr(SpanData span, String key) {
        return span.getAttributes().get(AttributeKey.stringKey(key));
    }

    // ============ AC-S01：脱敏正例 100% 命中 / 反例 0% 误伤 ============

    @Test
    void masking_positiveCases_allHit_100Percent() {
        OtlpTraceCollector collector = newCollector(4000, "lsv2_env_secret_value_abcdefgh");
        TraceContextHolder.set(new TraceContextHolder.TraceContext("t", "s"));
        collector.startRequest();
        // 正例 1：sk- 密钥
        collector.recordLlm(new TraceCollector.LlmCallEvent("m", "key is sk-abcDEF123456789012345678", "ok",
                1, 1, 5L, true, null, "stop"));
        // 正例 2：Bearer 令牌
        collector.recordTool(new TraceCollector.ToolCallEvent("httpGet",
                "{\"url\":\"x\",\"h\":\"Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.sig\"}",
                "ok", 5L, true, null));
        // 正例 3：环境密钥值
        collector.recordLlm(new TraceCollector.LlmCallEvent("m", "secret is lsv2_env_secret_value_abcdefgh", "ok",
                1, 1, 5L, true, null, "stop"));
        collector.endRequest();

        List<SpanData> spans = exporter.getFinishedSpanItems();
        String all = spans.toString();
        // 100% 命中：三处密钥均无明文
        assertThat(all).doesNotContain("sk-abcDEF123456789012345678");
        assertThat(all).doesNotContain("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.sig");
        assertThat(all).doesNotContain("lsv2_env_secret_value_abcdefgh");
        // 脱敏占位出现
        assertThat(all).contains(SensitiveDataMasker.REDACTED);
    }

    @Test
    void masking_negativeCases_zeroFalsePositive() {
        OtlpTraceCollector collector = newCollector(4000);
        TraceContextHolder.set(new TraceContextHolder.TraceContext("t", "s"));
        collector.startRequest();
        // 反例：含 sk-/ignore 的正常文本不得被替换（0 误伤）
        String normal = "Please ignore the sk-ignore note and check the README";
        collector.recordLlm(new TraceCollector.LlmCallEvent("m", normal, "ok", 1, 1, 5L, true, null, "stop"));
        collector.endRequest();

        SpanData llm = exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().startsWith("chat")).findFirst().orElseThrow();
        // 0 误伤：原文完整保留
        assertThat(spanAttr(llm, "gen_ai.prompt")).isEqualTo(normal);
    }

    // ============ AC-S02：无 Key 零外联（Noop 装配，无 exporter） ============

    @Test
    void disabledConfig_usesNoopTraceCollector_noExporter() {
        // 默认配置（enabled=false）→ Noop 实现，无 OTel exporter 初始化（零外联）
        new ApplicationContextRunner()
                .withUserConfiguration(ObservabilityAutoConfiguration.class)
                .run(ctx -> {
                    TraceCollector bean = ctx.getBean(TraceCollector.class);
                    assertThat(bean).isInstanceOf(NoopTraceCollector.class);
                    // 行为级：任意采集调用不抛异常、无 span 产生（无 exporter）
                    assertThatCode(() -> {
                        bean.startRequest();
                        bean.recordLlm(new TraceCollector.LlmCallEvent("m", "p", "o", 1, 1, 1L, true, null, "stop"));
                        bean.endRequest();
                    }).doesNotThrowAnyException();
                });
    }

    // ============ AC-S04：上报失败静默降级（exporter 抛异常不传导主流程） ============

    @Test
    void exportFailure_silentDegradation_noThrow() {
        // 自定义 exporter：export 恒抛异常，模拟 LangSmith 上报失败
        SpanExporter throwingExporter = new SpanExporter() {
            @Override
            public io.opentelemetry.sdk.common.CompletableResultCode export(
                    java.util.Collection<SpanData> spans) {
                throw new RuntimeException("simulated export failure");
            }

            @Override
            public io.opentelemetry.sdk.common.CompletableResultCode flush() {
                return io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess();
            }

            @Override
            public io.opentelemetry.sdk.common.CompletableResultCode shutdown() {
                return io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess();
            }
        };
        SdkTracerProvider tp = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(throwingExporter))
                .build();
        OpenTelemetry otel = OpenTelemetrySdk.builder().setTracerProvider(tp).build();
        OtlpTraceCollector collector = new OtlpTraceCollector(otel, new SensitiveDataMasker(4000));
        TraceContextHolder.set(new TraceContextHolder.TraceContext("t", "s"));

        // 主流程（采集调用）不受 exporter 异常影响，不抛异常（AC-S04）
        assertThatCode(() -> {
            collector.startRequest();
            collector.recordLlm(new TraceCollector.LlmCallEvent("m", "p", "o", 1, 1, 1L, true, null, "stop"));
            collector.recordTool(new TraceCollector.ToolCallEvent("tool", "{}", "r", 1L, true, null));
            collector.endRequest();
        }).doesNotThrowAnyException();
    }

    // ============ AC-E02：超长内容截断 ============

    @Test
    void overlongField_truncatedAtSpanLevel() {
        OtlpTraceCollector collector = newCollector(100);
        TraceContextHolder.set(new TraceContextHolder.TraceContext("t", "s"));
        collector.startRequest();
        collector.recordTool(new TraceCollector.ToolCallEvent("httpGet", "{}", "x".repeat(5000), 5L, true, null));
        collector.endRequest();

        SpanData tool = exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals("httpGet")).findFirst().orElseThrow();
        String result = spanAttr(tool, "tool.result");
        // 截断至阈值 + 截断标识
        assertThat(result).hasSize(100 + SensitiveDataMasker.TRUNCATED.length());
        assertThat(result).endsWith(SensitiveDataMasker.TRUNCATED);
    }

    // ============ AC-E03：启停行为确定性（多次启停无残留） ============

    @Test
    void startEndCycles_noResidualState() {
        OtlpTraceCollector collector = newCollector(4000);
        TraceContextHolder.set(new TraceContextHolder.TraceContext("t", "s"));
        // 多次启停循环
        for (int i = 0; i < 3; i++) {
            collector.startRequest();
            collector.recordLlm(new TraceCollector.LlmCallEvent("m" + i, "p", "o", 1, 1, 1L, true, null, "stop"));
            collector.endRequest();
        }
        // 无未结束残留：InMemorySpanExporter 仅含已结束 span；再次启停仍正常
        assertThatCode(() -> {
            collector.startRequest();
            collector.endRequest();
        }).doesNotThrowAnyException();
        // 3 次请求共 6 span（3 根 + 3 LLM）+ 验证启停的 1 个根 span = 7
        assertThat(exporter.getFinishedSpanItems()).hasSize(7);
    }
}
