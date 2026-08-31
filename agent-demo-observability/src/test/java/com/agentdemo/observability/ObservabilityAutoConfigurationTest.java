package com.agentdemo.observability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ObservabilityAutoConfiguration 测试（Task-07，集成验证）
 * <p>
 * 业务含义：验证默认关闭零外联（AC-S02）与启停行为确定性（AC-E03）：
 * 默认/未配 Key 装配 Noop；enabled+Key 装配 Otlp。
 * </p>
 */
class ObservabilityAutoConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(ObservabilityAutoConfiguration.class);

    @Test
    void defaultState_usesNoopTraceCollector() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(TraceCollector.class);
            assertThat(ctx.getBean(TraceCollector.class)).isInstanceOf(NoopTraceCollector.class);
        });
    }

    @Test
    void enabled_butNoApiKey_usesNoopTraceCollector() {
        new ApplicationContextRunner()
                .withPropertyValues("langsmith.enabled=true", "langsmith.api-key=")
                .withUserConfiguration(ObservabilityAutoConfiguration.class)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(TraceCollector.class);
                    assertThat(ctx.getBean(TraceCollector.class)).isInstanceOf(NoopTraceCollector.class);
                });
    }

    @Test
    void enabled_withApiKey_usesOtlpTraceCollector() {
        new ApplicationContextRunner()
                .withPropertyValues("langsmith.enabled=true", "langsmith.api-key=lsv2_test_key_abcdefghijklmn")
                .withUserConfiguration(ObservabilityAutoConfiguration.class)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(TraceCollector.class);
                    assertThat(ctx.getBean(TraceCollector.class)).isInstanceOf(OtlpTraceCollector.class);
                });
    }

    // ===== 端点解析（修复：endpoint 缺 /v1/traces → OTel Java exporter 直接使用 → LangSmith 404） =====

    @Test
    void resolveOtlpTracesEndpoint_appendsSignalPath() {
        // 业务含义：兼容 base / 带尾斜杠 / 完整端点三种配置，统一补全为 /v1/traces
        assertThat(ObservabilityAutoConfiguration.resolveOtlpTracesEndpoint("https://api.smith.langchain.com/otel"))
                .isEqualTo("https://api.smith.langchain.com/otel/v1/traces");
        assertThat(ObservabilityAutoConfiguration.resolveOtlpTracesEndpoint("https://api.smith.langchain.com/otel/"))
                .isEqualTo("https://api.smith.langchain.com/otel/v1/traces");
        assertThat(ObservabilityAutoConfiguration.resolveOtlpTracesEndpoint("https://api.smith.langchain.com/otel//"))
                .isEqualTo("https://api.smith.langchain.com/otel/v1/traces");
    }

    @Test
    void resolveOtlpTracesEndpoint_keepsFullEndpointUnchanged() {
        assertThat(ObservabilityAutoConfiguration.resolveOtlpTracesEndpoint("https://api.smith.langchain.com/otel/v1/traces"))
                .isEqualTo("https://api.smith.langchain.com/otel/v1/traces");
        assertThat(ObservabilityAutoConfiguration.resolveOtlpTracesEndpoint("http://127.0.0.1:4318/v1/traces"))
                .isEqualTo("http://127.0.0.1:4318/v1/traces");
        assertThat(ObservabilityAutoConfiguration.resolveOtlpTracesEndpoint(null)).isNull();
        assertThat(ObservabilityAutoConfiguration.resolveOtlpTracesEndpoint("")).isEmpty();
    }
}
