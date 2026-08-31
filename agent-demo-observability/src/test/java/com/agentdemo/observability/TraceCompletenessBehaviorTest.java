package com.agentdemo.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
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
 * 采集完整性行为测试（langsmith-observability Task-13）
 * <p>
 * 业务含义：模拟一次含模型切换与失败注入的完整 ReAct 对话，验证采集链路完整性
 * （技术方案 §7.1）：ReAct 多轮 span 完整（AC-T01）、失败 span 不丢（AC-T02/T03）、
 * 同 trace 归属与 thread 聚合属性（AC-N01/N02）、模型切换如实（AC-M02）、
 * 本地日志互查键（AC-M01）。
 * </p>
 */
class TraceCompletenessBehaviorTest {

    private InMemorySpanExporter exporter;
    private OtlpTraceCollector collector;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        SdkTracerProvider tp = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetry otel = OpenTelemetrySdk.builder().setTracerProvider(tp).build();
        collector = new OtlpTraceCollector(otel, new SensitiveDataMasker(4000));
        TraceContextHolder.set(new TraceContextHolder.TraceContext("trace-react-1", "session-react-1"));
    }

    @AfterEach
    void tearDown() {
        TraceContextHolder.clear();
        exporter.reset();
    }

    private SpanData spanByName(String name) {
        return exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals(name)).findFirst().orElseThrow();
    }

    private String attr(SpanData span, String key) {
        return span.getAttributes().get(AttributeKey.stringKey(key));
    }

    /**
     * 模拟：模型A调用 -> 工具A -> 模型A调用（tool_calls 中间轮）-> 模型B调用（会话切换）-> 工具B -> 模型B最终回复
     * 另注入：模型C调用失败（AC-T02）
     */
    @Test
    void reactLoop_spanSequence_completeAndSharedTrace() {
        collector.startRequest();
        // 轮1：模型A -> 工具A
        collector.recordLlm(new TraceCollector.LlmCallEvent(
                "modelA", "user: 查订单", "tool_calls", 10, 5, 100L, true, null, "tool_calls"));
        collector.recordTool(new TraceCollector.ToolCallEvent(
                "queryOrder", "{\"id\":\"ORD-1\"}", "订单已发货", 20L, true, null));
        // 轮2：模型A（中间轮）-> 工具B
        collector.recordLlm(new TraceCollector.LlmCallEvent(
                "modelA", "user: 查物流", "tool_calls", 15, 6, 90L, true, null, "tool_calls"));
        collector.recordTool(new TraceCollector.ToolCallEvent(
                "queryLogistics", "{\"id\":\"ORD-1\"}", "已到达上海", 15L, true, null));
        // 轮3：会话切换模型B -> 最终回复
        collector.recordLlm(new TraceCollector.LlmCallEvent(
                "modelB", "user: 汇总", "订单已发货，物流到达上海", 12, 30, 200L, true, null, "stop"));
        // 失败注入：模型C调用失败（AC-T02，不应丢失）
        collector.recordLlm(new TraceCollector.LlmCallEvent(
                "modelC", "user: x", null, -1, -1, 800L, false, "connection timeout", null));
        collector.endRequest();

        List<SpanData> spans = exporter.getFinishedSpanItems();

        // AC-T01：ReAct 多轮 span 完整无缺失（1 根 + 4 LLM + 2 工具 = 7）
        assertThat(spans).hasSize(7);
        assertThat(spanByName("chat modelA")).isNotNull();
        assertThat(spanByName("chat modelB")).isNotNull();
        assertThat(spanByName("chat modelC")).isNotNull();
        assertThat(spanByName("queryOrder")).isNotNull();
        assertThat(spanByName("queryLogistics")).isNotNull();

        // AC-N01：所有 span 共享同一 trace（同一次用户消息归属一条 trace）
        String rootTraceId = spanByName("agent.request").getTraceId();
        for (SpanData s : spans) {
            assertThat(s.getTraceId()).isEqualTo(rootTraceId);
        }
        // 根 span 为所有采集 span 的父节点（AC-T01 父子归属）
        String rootSpanId = spanByName("agent.request").getSpanId();
        for (SpanData s : spans) {
            if (!"agent.request".equals(s.getName())) {
                assertThat(s.getParentSpanId()).isEqualTo(rootSpanId);
            }
        }

        // AC-N02：thread 聚合属性（conversation.id + thread.id 双写）
        assertThat(attr(spanByName("chat modelB"), "gen_ai.conversation.id")).isEqualTo("session-react-1");
        assertThat(attr(spanByName("chat modelB"), "langsmith.thread.id")).isEqualTo("session-react-1");

        // AC-M01：本地日志互查键
        assertThat(attr(spanByName("queryOrder"), "log.trace_id")).isEqualTo("trace-react-1");

        // AC-M02：模型切换如实记录（各 span 模型名不同）
        assertThat(attr(spanByName("chat modelA"), "gen_ai.request.model")).isEqualTo("modelA");
        assertThat(attr(spanByName("chat modelB"), "gen_ai.request.model")).isEqualTo("modelB");
    }

    @Test
    void failureToolSpan_notLost_andErrorRecorded() {
        collector.startRequest();
        // 工具失败（AC-T03）：失败 span 不被丢弃
        collector.recordLlm(new TraceCollector.LlmCallEvent(
                "modelA", "user: hi", "tool_calls", 5, 3, 50L, true, null, "tool_calls"));
        collector.recordTool(new TraceCollector.ToolCallEvent(
                "httpGet", "{\"url\":\"https://x.com\"}", null, 1500L, false, "connect timeout"));
        collector.recordLlm(new TraceCollector.LlmCallEvent(
                "modelA", "user: hi", "重试或告知", 5, 8, 60L, true, null, "stop"));
        collector.endRequest();

        SpanData tool = spanByName("httpGet");
        // AC-T03：失败 span 存在且含异常信息
        assertThat(tool).isNotNull();
        assertThat(attr(tool, "error.type")).isEqualTo("connect timeout");
        assertThat(tool.getStatus().getStatusCode().name()).isEqualTo("ERROR");
        // 失败不中断其余 span（后续 LLM span 仍在）
        assertThat(spanByName("chat modelA")).isNotNull();
    }

    @Test
    void emptyRequest_noSpansLeftOver() {
        // 无采集事件的请求：仅根 span，无残留
        collector.startRequest();
        collector.endRequest();
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        assertThat(exporter.getFinishedSpanItems().get(0).getName()).isEqualTo("agent.request");
    }
}
