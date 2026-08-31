package com.agentdemo.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * OTel 追踪采集器实现（langsmith-observability，Task-06）
 * <p>
 * 业务含义：将 {@link TraceCollector} 事件构建为 OpenTelemetry span，属性遵循 GenAI 语义约定
 * （技术方案 §7.1），并作为出境数据唯一出口接入 {@link SensitiveDataMasker} 脱敏（决策 6）。
 * </p>
 * <p>
 * 根 span 机制（AC-T01）：{@link #startRequest()} 创建请求级根 span 并使其 Context 成为当前
 * 线程父上下文，后续 recordLlm/recordTool 的 span 自动成为子 span——同一次用户消息的所有
 * span 共享同一 trace ID，LangSmith 侧呈现为一条完整 trace。
 * </p>
 * <p>
 * 静默降级（AC-S04）：全部 record/start/end 内部 try-catch，任何异常仅 WARN 不影响主流程。
 * </p>
 */
public class OtlpTraceCollector implements TraceCollector {

    private static final Logger log = LoggerFactory.getLogger(OtlpTraceCollector.class);

    private final Tracer tracer;
    private final SensitiveDataMasker masker;

    @Override
    public boolean isEnabled() {
        // 业务含义：Otlp 实现已装配即启用——ModelFactory 据此挂载 listener/装饰器（AC-S02）
        return true;
    }

    /**
     * 当前线程的根 span 上下文（startRequest/endRequest 成对维护）。
     * 同时持有 root span 与 scope：endRequest 必须先 scope.close() 再 end root span
     * （close 后 Span.current() 已恢复为无效 span，无法经 current() 取根 span）。
     */
    private final ThreadLocal<RootSpanContext> rootSpanHolder = new ThreadLocal<>();

    /**
     * 根 span 上下文（span 引用 + Context 作用域）
     */
    private record RootSpanContext(Span span, Scope scope) {}

    /**
     * @param openTelemetry 已初始化的 OpenTelemetry 实例（AutoConfiguration 注入）
     * @param masker        出境脱敏器（单一出口，AC-S01）
     */
    public OtlpTraceCollector(OpenTelemetry openTelemetry, SensitiveDataMasker masker) {
        this.tracer = openTelemetry.getTracer("agent-demo-observability");
        this.masker = masker;
    }

    @Override
    public void startRequest() {
        try {
            // 业务含义：请求级根 span，作为本次用户消息所有采集 span 的父节点（AC-T01 共享 trace）
            Span root = tracer.spanBuilder("agent.request").setSpanKind(SpanKind.SERVER).startSpan();
            Scope scope = root.makeCurrent();
            rootSpanHolder.set(new RootSpanContext(root, scope));
        } catch (Exception e) {
            log.warn("LangSmith 根 span 启动失败（降级跳过）: {}", e.getMessage());
        }
    }

    @Override
    public void endRequest() {
        try {
            RootSpanContext ctx = rootSpanHolder.get();
            if (ctx == null) {
                return;
            }
            rootSpanHolder.remove();
            ctx.scope().close();
            ctx.span().end();
        } catch (Exception e) {
            log.warn("LangSmith 根 span 结束失败（降级跳过）: {}", e.getMessage());
        }
    }

    @Override
    public void recordLlm(LlmCallEvent event) {
        try {
            // 业务含义：LLM span（GenAI semconv 属性），startRequest 已建立当前父 Context
            Instant endInstant = Instant.now();
            Instant startInstant = endInstant.minusMillis(event.durationMs());
            Span span = tracer.spanBuilder("chat " + safe(event.modelName()))
                    .setSpanKind(SpanKind.CLIENT)
                    .setStartTimestamp(startInstant)
                    .startSpan();
            span.setAllAttributes(buildLlmAttributes(event));
            if (event.success()) {
                span.setStatus(StatusCode.OK);
            } else {
                String err = safe(event.errorMessage());
                span.setStatus(StatusCode.ERROR, err);
                span.setAttribute("error.type", err);
            }
            span.end(endInstant);
        } catch (Exception e) {
            // AC-S04：采集失败仅 WARN，不影响主流程
            log.warn("LangSmith LLM 采集失败（降级跳过）: {}", e.getMessage());
        }
    }

    @Override
    public void recordTool(ToolCallEvent event) {
        try {
            Instant endInstant = Instant.now();
            Instant startInstant = endInstant.minusMillis(event.durationMs());
            Span span = tracer.spanBuilder(safe(event.toolName()))
                    .setSpanKind(SpanKind.CLIENT)
                    .setStartTimestamp(startInstant)
                    .startSpan();
            span.setAllAttributes(buildToolAttributes(event));
            if (event.success()) {
                span.setStatus(StatusCode.OK);
            } else {
                String err = safe(event.errorMessage());
                span.setStatus(StatusCode.ERROR, err);
                span.setAttribute("error.type", err);
            }
            span.end(endInstant);
        } catch (Exception e) {
            log.warn("LangSmith 工具采集失败（降级跳过）: {}", e.getMessage());
        }
    }

    /**
     * 构建 LLM span 属性（技术方案 §7.1 LLM span 设计表）
     */
    private Attributes buildLlmAttributes(LlmCallEvent event) {
        AttributesBuilder b = Attributes.builder();
        // GenAI 语义约定（GenAI semconv）
        b.put("gen_ai.operation.name", "chat");
        b.put("gen_ai.request.model", masker.maskSafe(event.modelName()));
        if (event.inputTokens() >= 0) {
            b.put("gen_ai.usage.input_tokens", event.inputTokens());
        }
        if (event.outputTokens() >= 0) {
            b.put("gen_ai.usage.output_tokens", event.outputTokens());
        }
        b.put("gen_ai.prompt", masker.maskSafe(event.promptText()));
        if (event.outputText() != null) {
            b.put("gen_ai.completion", masker.maskSafe(event.outputText()));
        }
        putContextAttributes(b);
        return b.build();
    }

    /**
     * 构建工具 span 属性（技术方案 §7.1 工具 span 设计表）
     */
    private Attributes buildToolAttributes(ToolCallEvent event) {
        AttributesBuilder b = Attributes.builder();
        b.put("tool.name", masker.maskSafe(event.toolName()));
        b.put("tool.arguments", masker.maskSafe(event.arguments()));
        if (event.result() != null) {
            b.put("tool.result", masker.maskSafe(event.result()));
        }
        putContextAttributes(b);
        return b.build();
    }

    /**
     * 注入上下文属性：本地日志互查键（AC-M01）与会话聚合键（AC-N02，双写备选）
     */
    private void putContextAttributes(AttributesBuilder b) {
        String traceId = TraceContextHolder.currentTraceId();
        String sessionId = TraceContextHolder.currentSessionId();
        if (traceId != null) {
            b.put("log.trace_id", traceId);
        }
        if (sessionId != null) {
            b.put("gen_ai.conversation.id", sessionId);
            b.put("langsmith.thread.id", sessionId);
        }
    }

    private static String safe(String s) {
        return s != null ? s : "";
    }
}
