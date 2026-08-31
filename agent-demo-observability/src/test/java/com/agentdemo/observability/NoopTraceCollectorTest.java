package com.agentdemo.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * NoopTraceCollector 测试（Task-03）
 * <p>
 * 业务含义：未启用 LangSmith 时的默认采集器必须零副作用——任意调用不抛异常、
 * 无状态变化（AC-S02 默认关零开销的技术根基）。
 * </p>
 */
class NoopTraceCollectorTest {

    private final NoopTraceCollector collector = new NoopTraceCollector();

    @Test
    void isEnabled_returnsFalse() {
        // 业务含义：Noop 默认关闭（AC-S02），ModelFactory 据此跳过埋点挂载实现零开销
        assertThat(collector.isEnabled()).isFalse();
    }

    @Test
    void startRequest_endRequest_shouldBeNoop() {
        assertThatCode(() -> {
            collector.startRequest();
            collector.endRequest();
        }).doesNotThrowAnyException();
    }

    @Test
    void recordLlm_shouldBeNoop() {
        assertThatCode(() -> collector.recordLlm(new TraceCollector.LlmCallEvent(
                "glm-5.2", "user: hi", "assistant: hello",
                10, 20, 500L, true, null, "stop")))
                .doesNotThrowAnyException();
    }

    @Test
    void recordLlm_errorVariant_shouldBeNoop() {
        assertThatCode(() -> collector.recordLlm(new TraceCollector.LlmCallEvent(
                "glm-5.2", "user: hi", null,
                -1, -1, 800L, false, "connection timeout", null)))
                .doesNotThrowAnyException();
    }

    @Test
    void recordTool_shouldBeNoop() {
        assertThatCode(() -> collector.recordTool(new TraceCollector.ToolCallEvent(
                "getCurrentTime", "{\"timezone\":\"Asia/Shanghai\"}",
                "2026-08-29 10:00:00", 12L, true, null)))
                .doesNotThrowAnyException();
    }

    @Test
    void recordTool_errorVariant_shouldBeNoop() {
        assertThatCode(() -> collector.recordTool(new TraceCollector.ToolCallEvent(
                "httpGet", "{\"url\":\"https://example.com\"}", null,
                1500L, false, "connect timeout")))
                .doesNotThrowAnyException();
    }
}
