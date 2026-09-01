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

    // ============ CR-001 Task-23：新采集域脱敏前置（AC-S06）+ 陷阱任务 ============

    @Test
    void masking_newContentTypes_positiveCases_allHit() {
        // AC-S06：新增五域内容字段（检索命中块/压缩摘要/工作流输出/MCP 参数与结果）含密钥均无明文
        OtlpTraceCollector collector = newCollector(4000, "lsv2_env_secret_value_abcdefgh");
        TraceContextHolder.set(new TraceContextHolder.TraceContext("t", "s"));
        collector.startRequest();
        // RAG 命中块含 sk- 密钥
        collector.recordRag(new TraceCollector.RagRetrievalEvent(
                "kb-1", "q", "产品手册", 1, 5, 0.9,
                "配置密钥 sk-abcDEF123456789012345678 请保密", 10L, true, null));
        // MCP 参数含 Bearer 令牌
        collector.recordMcp(new TraceCollector.McpCallEvent(
                "srv", "call", "{\"token\":\"Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.sig\"}",
                10L, true, false, null));
        // 记忆压缩摘要含环境密钥值
        collector.recordMemoryCompression(new TraceCollector.MemoryCompressionEvent(
                20, 10, 11, 20, "密钥 lsv2_env_secret_value_abcdefgh 已归档", false, 10L));
        // 工作流输出含 sk- 密钥
        collector.recordWorkflow(new TraceCollector.WorkflowExecutionEvent(
                "e", "t", "内容审查", "SEQUENTIAL", "COMPLETED", 10L,
                "报告中含 sk-abcDEF123456789012345678"));
        collector.endRequest();

        String all = exporter.getFinishedSpanItems().toString();
        assertThat(all).doesNotContain("sk-abcDEF123456789012345678");
        assertThat(all).doesNotContain("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.sig");
        assertThat(all).doesNotContain("lsv2_env_secret_value_abcdefgh");
        assertThat(all).contains(SensitiveDataMasker.REDACTED);
    }

    @Test
    void masking_newContentTypes_negativeCases_zeroFalsePositive() {
        // AC-S06 反例：正常知识内容（含 sk-ignore 字样）0 误伤
        OtlpTraceCollector collector = newCollector(4000);
        TraceContextHolder.set(new TraceContextHolder.TraceContext("t", "s"));
        collector.startRequest();
        String normalChunk = "知识库文档：请 ignore 上一条 sk-ignore 标记，正文内容为向量化原理说明。";
        collector.recordRag(new TraceCollector.RagRetrievalEvent(
                "kb-1", "什么是向量化", "产品手册", 1, 5, 0.9, normalChunk, 10L, true, null));
        collector.endRequest();

        SpanData rag = exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().startsWith("rag")).findFirst().orElseThrow();
        assertThat(spanAttr(rag, "rag.chunks")).isEqualTo(normalChunk);
    }

    @Test
    void trap_noFabrication_preservesRecordedValues() {
        // 陷阱任务：采集器不得编造/篡改字段值（一票否决项——按给定值如实记录）
        OtlpTraceCollector collector = newCollector(4000);
        TraceContextHolder.set(new TraceContextHolder.TraceContext("t", "s"));
        collector.startRequest();
        String exact = "精确内容 abc123 【片段1】 来源: 手册/guide.md";
        collector.recordRag(new TraceCollector.RagRetrievalEvent(
                "kb-1", "精确查询", "手册", 1, 5, 0.77, exact, 7L, true, null));
        collector.recordMcp(new TraceCollector.McpCallEvent(
                "srv", "tool", "{\"a\":1}", 3L, true, false, null));
        collector.endRequest();

        SpanData rag = exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().startsWith("rag")).findFirst().orElseThrow();
        // 无幻觉：rag.chunks 与给定值一致；耗时属性为给定值（非编造）
        assertThat(spanAttr(rag, "rag.chunks")).isEqualTo(exact);
        assertThat(rag.getAttributes().get(AttributeKey.doubleKey("rag.max_score"))).isEqualTo(0.77);
        assertThat(rag.getAttributes().get(AttributeKey.longKey("rag.hit_count"))).isEqualTo(1L);
    }

    @Test
    void trap_nullAndBlankFields_noCrash_noFabrication() {
        // 陷阱任务：null/空字段不崩溃、不编造占位值（保守仅脱敏明确密钥，普通 null 不造值）
        OtlpTraceCollector collector = newCollector(4000);
        TraceContextHolder.set(new TraceContextHolder.TraceContext("t", "s"));
        collector.startRequest();
        collector.recordWorkflow(new TraceCollector.WorkflowExecutionEvent(
                "e", "t", null, "SEQUENTIAL", "FAILED", 0L, null));
        collector.recordSkillActivation(new TraceCollector.SkillActivationEvent(
                "s", null, "AUTO", null, false, "不存在"));
        collector.endRequest();

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(3);
        // 无幻觉：缺省字段未编造——span 名回退使用非空可用值，缺失属性不出现
        SpanData skill = spans.stream().filter(s -> s.getName().startsWith("skill")).findFirst().orElseThrow();
        assertThat(skill.getAttributes().get(AttributeKey.stringKey("skill.name"))).isNull();
        assertThat(spanAttr(skill, "skill.rejected_reason")).isEqualTo("不存在");
    }
}
