package com.agentdemo.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OtlpTraceCollector 测试（Task-06）
 * <p>
 * 业务含义：验证 span 构建与 GenAI 属性（技术方案 §7.1）、父子归属（AC-T01 共享 trace）、
 * 脱敏单一出口（AC-S01）、失败 span（AC-T02/T03）。用 InMemorySpanExporter 无外联断言。
 * </p>
 */
class OtlpTraceCollectorTest {

    private InMemorySpanExporter exporter;
    private OtlpTraceCollector collector;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetry otel = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();
        collector = new OtlpTraceCollector(otel, new SensitiveDataMasker(4000));
        TraceContextHolder.set(new TraceContextHolder.TraceContext("trace-1", "session-1"));
    }

    @AfterEach
    void tearDown() {
        TraceContextHolder.clear();
        exporter.reset();
    }

    private SpanData spanByName(List<SpanData> spans, String name) {
        return spans.stream().filter(s -> s.getName().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void isEnabled_returnsTrue() {
        // 业务含义：Otlp 实现已装配即启用（ModelFactory 据此挂载埋点）
        assertThat(collector.isEnabled()).isTrue();
    }

    @Test
    void exportedSpan_resource_serviceName_unknownServiceJava() {
        // 业务含义：生产装配（SdkTracerProvider.builder().build() 未显式设置 Resource）
        // 导出 span 的 service.name 为 OTel 默认值 unknown_service:java。
        // LangSmith 按 resource service.name / Langsmith-Project header 归属 project，
        // 未设置时 trace 落到非预期 project（如 unknown_service:java），用户按默认 project 找不到记录。
        collector.startRequest();
        collector.recordLlm(new TraceCollector.LlmCallEvent(
                "glm-5.2", "user: hi", "assistant: hello", 10, 20, 100L, true, null, "stop"));
        collector.endRequest();

        SpanData llm = spanByName(exporter.getFinishedSpanItems(), "chat glm-5.2");
        assertThat(llm.getResource().getAttribute(AttributeKey.stringKey("service.name")))
                .isEqualTo("unknown_service:java");
    }

    @Test
    void exportedSpan_resource_serviceName_isConfiguredProject() {
        // 业务含义：修复后生产装配显式设置 Resource（service.name = langsmith.project），
        // 导出 span 的 service.name 正确归属 LangSmith project（替代 unknown_service:java）
        InMemorySpanExporter resExporter = InMemorySpanExporter.create();
        Resource resource = Resource.getDefault().toBuilder()
                .put(AttributeKey.stringKey("service.name"), "agent-demo")
                .build();
        SdkTracerProvider tp = SdkTracerProvider.builder()
                .setResource(resource)
                .addSpanProcessor(SimpleSpanProcessor.create(resExporter))
                .build();
        OtlpTraceCollector c = new OtlpTraceCollector(
                OpenTelemetrySdk.builder().setTracerProvider(tp).build(), new SensitiveDataMasker(4000));
        c.startRequest();
        c.recordLlm(new TraceCollector.LlmCallEvent(
                "glm-5.2", "user: hi", "assistant: hello", 10, 20, 100L, true, null, "stop"));
        c.endRequest();

        SpanData llm = resExporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals("chat glm-5.2")).findFirst().orElseThrow();
        assertThat(llm.getResource().getAttribute(AttributeKey.stringKey("service.name")))
                .isEqualTo("agent-demo");
    }

    @Test
    void request_spans_shareSameTraceId_andParentChild() {
        collector.startRequest();
        collector.recordLlm(new TraceCollector.LlmCallEvent(
                "glm-5.2", "user: hi", "assistant: hello", 10, 20, 100L, true, null, "stop"));
        collector.recordTool(new TraceCollector.ToolCallEvent(
                "getCurrentTime", "{\"tz\":\"Asia/Shanghai\"}", "2026-08-29", 5L, true, null));
        collector.endRequest();

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(3);

        SpanData root = spanByName(spans, "agent.request");
        SpanData llm = spanByName(spans, "chat glm-5.2");
        SpanData tool = spanByName(spans, "getCurrentTime");
        // AC-T01：同 trace，父子归属
        assertThat(llm.getTraceId()).isEqualTo(root.getTraceId());
        assertThat(tool.getTraceId()).isEqualTo(root.getTraceId());
        assertThat(llm.getParentSpanId()).isEqualTo(root.getSpanId());
        assertThat(tool.getParentSpanId()).isEqualTo(root.getSpanId());
    }

    @Test
    void llmSpan_hasGenAiAttributes() {
        collector.startRequest();
        collector.recordLlm(new TraceCollector.LlmCallEvent(
                "glm-5.2", "user: hi", "assistant: hello", 10, 20, 100L, true, null, "stop"));
        collector.endRequest();

        SpanData llm = spanByName(exporter.getFinishedSpanItems(), "chat glm-5.2");
        var attrs = llm.getAttributes();
        assertThat(attrs.get(AttributeKey.stringKey("gen_ai.operation.name"))).isEqualTo("chat");
        assertThat(attrs.get(AttributeKey.stringKey("gen_ai.request.model"))).isEqualTo("glm-5.2");
        assertThat(attrs.get(AttributeKey.longKey("gen_ai.usage.input_tokens"))).isEqualTo(10L);
        assertThat(attrs.get(AttributeKey.longKey("gen_ai.usage.output_tokens"))).isEqualTo(20L);
        // AC-M01 / AC-N02：traceId 与 sessionId（双写 thread 键）
        assertThat(attrs.get(AttributeKey.stringKey("log.trace_id"))).isEqualTo("trace-1");
        assertThat(attrs.get(AttributeKey.stringKey("gen_ai.conversation.id"))).isEqualTo("session-1");
        assertThat(attrs.get(AttributeKey.stringKey("langsmith.thread.id"))).isEqualTo("session-1");
        assertThat(llm.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
    }

    @Test
    void toolSpan_hasToolAttributes_andOkStatus() {
        collector.startRequest();
        collector.recordTool(new TraceCollector.ToolCallEvent(
                "getCurrentTime", "{\"tz\":\"Asia/Shanghai\"}", "2026-08-29 10:00:00", 5L, true, null));
        collector.endRequest();

        SpanData tool = spanByName(exporter.getFinishedSpanItems(), "getCurrentTime");
        var attrs = tool.getAttributes();
        assertThat(attrs.get(AttributeKey.stringKey("tool.name"))).isEqualTo("getCurrentTime");
        assertThat(attrs.get(AttributeKey.stringKey("tool.arguments"))).isEqualTo("{\"tz\":\"Asia/Shanghai\"}");
        assertThat(attrs.get(AttributeKey.stringKey("tool.result"))).isEqualTo("2026-08-29 10:00:00");
        assertThat(tool.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
    }

    @Test
    void failureEvents_produceErrorStatus() {
        collector.startRequest();
        collector.recordLlm(new TraceCollector.LlmCallEvent(
                "glm-5.2", "user: hi", null, -1, -1, 800L, false, "connection timeout", null));
        collector.recordTool(new TraceCollector.ToolCallEvent(
                "httpGet", "{\"url\":\"https://x.com\"}", null, 1500L, false, "connect timeout"));
        collector.endRequest();

        List<SpanData> spans = exporter.getFinishedSpanItems();
        // AC-T02/T03：失败也产 span，status=ERROR 且带 error.type
        SpanData llm = spanByName(spans, "chat glm-5.2");
        assertThat(llm.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(llm.getAttributes().get(AttributeKey.stringKey("error.type"))).isEqualTo("connection timeout");
        SpanData tool = spanByName(spans, "httpGet");
        assertThat(tool.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(tool.getAttributes().get(AttributeKey.stringKey("error.type"))).isEqualTo("connect timeout");
    }

    @Test
    void llmPrompt_withSecretKey_isMasked() {
        collector.startRequest();
        collector.recordLlm(new TraceCollector.LlmCallEvent(
                "glm-5.2", "密钥是 sk-abcDEF123456789012345678，请处理", "done", 1, 1, 10L, true, null, "stop"));
        collector.endRequest();

        SpanData llm = spanByName(exporter.getFinishedSpanItems(), "chat glm-5.2");
        String prompt = llm.getAttributes().get(AttributeKey.stringKey("gen_ai.prompt"));
        // AC-S01：脱敏单一出口生效，密钥不出 payload
        assertThat(prompt).doesNotContain("sk-abcDEF123456789012345678").contains("[REDACTED]");
    }
}
