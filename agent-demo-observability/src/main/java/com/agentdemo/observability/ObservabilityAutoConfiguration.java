package com.agentdemo.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * LangSmith 可观测性自动装配（langsmith-observability，Task-07）
 * <p>
 * 业务含义：按开关与密钥决定装配 {@link NoopTraceCollector}（默认）还是
 * {@link OtlpTraceCollector}（启用）。三重护栏之一「默认关闭」（AC-S02）在此落地：
 * enabled=false 或未配 Key 时零初始化零外联。
 * </p>
 * <p>
 * Key 管理（AC-S03）：仅经环境变量注入（application.yml {@code ${LANGSMITH_API_KEY:}}），
 * 认证头在 exporter 构建处组装，不进日志、不进 span。
 * </p>
 * <p>
 * 静默降级（AC-S04）：OTLP 导出由 BatchSpanProcessor 异步批量执行，导出失败仅 WARN 不影响主流程。
 * </p>
 */
@Configuration
public class ObservabilityAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityAutoConfiguration.class);

    @Value("${langsmith.enabled:false}")
    private boolean enabled;

    @Value("${langsmith.api-key:}")
    private String apiKey;

    @Value("${langsmith.endpoint:https://api.smith.langchain.com/otel/v1/traces}")
    private String endpoint;

    @Value("${langsmith.max-field-chars:4000}")
    private int maxFieldChars;

    @Value("${langsmith.export-timeout-ms:5000}")
    private long exportTimeoutMs;

    @Value("${langsmith.export-interval-ms:5000}")
    private long exportIntervalMs;

    @Value("${langsmith.max-queue-size:2048}")
    private int maxQueueSize;

    @Value("${langsmith.project:agent-demo}")
    private String project;

    /**
     * 追踪采集器 Bean
     * <p>
     * 业务含义：未启用时装配 {@link NoopTraceCollector}（零开销）；启用时构建 OTel SDK +
     * OTLP HTTP exporter + BatchSpanProcessor 并装配 {@link OtlpTraceCollector}。
     * 埋点方（ModelFactory/ToolExecutor/Controller）统一注入本 Bean。
     * </p>
     */
    @Bean
    public TraceCollector traceCollector() {
        if (!enabled || apiKey == null || apiKey.isBlank()) {
            log.info("LangSmith 未启用（enabled={}, api-key 已配置={}），装配 NoopTraceCollector",
                    enabled, apiKey != null && !apiKey.isBlank());
            return new NoopTraceCollector();
        }
        log.info("LangSmith 已启用，装配 OtlpTraceCollector: endpoint={}, project={}, max-field-chars={}",
                endpoint, project, maxFieldChars);
        // 业务含义：OTel Java 的 OtlpHttpSpanExporter.setEndpoint 要求完整 URL（含 /v1/traces 信号路径），
        // 内部 HttpUrl.get(endpoint) 直接使用、不追加任何路径。配置若只给 base（如 .../otel 或带尾斜杠）
        // 会 POST 到缺 /v1/traces 的路径导致 LangSmith 404、trace 无法上传。这里统一解析为完整端点。
        String otlpEndpoint = resolveOtlpTracesEndpoint(endpoint);
        // 业务含义：OTLP HTTP exporter 携带 x-api-key 认证头（LangSmith OTLP 集成标准，与官方 SDK 一致）。
        // LangSmith 按 resource service.name / Langsmith-Project header 归属 project（官方 Python/JS SDK
        // 均显式设置）；未设置时 service.name 为 OTel 默认 unknown_service:java，trace 会落到非预期
        // project，导致 UI 中找不到记录。这里双写（Resource service.name + Langsmith-Project header）保证归属。
        Resource resource = Resource.getDefault().toBuilder()
                .put(AttributeKey.stringKey("service.name"), project)
                .build();
        OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(otlpEndpoint)
                .setTimeout(Duration.ofMillis(exportTimeoutMs))
                .addHeader("x-api-key", apiKey)
                .addHeader("Langsmith-Project", project)
                .build();
        BatchSpanProcessor processor = BatchSpanProcessor.builder(exporter)
                .setScheduleDelay(Duration.ofMillis(exportIntervalMs))
                .setMaxQueueSize(maxQueueSize)
                .build();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .addSpanProcessor(processor)
                .build();
        OpenTelemetry otel = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();
        return new OtlpTraceCollector(otel, new SensitiveDataMasker(maxFieldChars, collectEnvSecrets()));
    }

    /**
     * 收集环境变量中的密钥值参与脱敏精确替换（AC-S01 环境密钥值规则）
     */
    private List<String> collectEnvSecrets() {
        List<String> secrets = new ArrayList<>();
        for (String name : new String[]{"LANGSMITH_API_KEY", "ARK_API_KEY", "BAILIAN_API_KEY"}) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                secrets.add(value);
            }
        }
        return secrets;
    }

    /**
     * 解析 OTLP trace 完整端点：去尾部斜杠后，若未以 /v1/traces 结尾则补全。
     * <p>
     * 业务含义：兼容「base 形式（.../otel）」「带尾斜杠（.../otel/）」「完整端点（.../otel/v1/traces）」
     * 三种配置习惯，统一为 OTel Java exporter 需要的完整 URL（内部 HttpUrl.get 直接使用，不追加信号路径，
     * 缺 /v1/traces 会 POST 到错误路径导致 LangSmith 404）。
     * </p>
     */
    static String resolveOtlpTracesEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return endpoint;
        }
        String base = endpoint.replaceAll("/+$", "");
        if (base.endsWith("/v1/traces")) {
            return base;
        }
        return base + "/v1/traces";
    }
}
