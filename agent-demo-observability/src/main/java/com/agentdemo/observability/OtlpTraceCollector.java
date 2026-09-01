package com.agentdemo.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

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

    /**
     * HITL 暂停续接表（BUG 修复：单任务多轮人机交互共享同一 trace）。
     * key=续接键（chat=sessionId / 工作流=executionId），value=暂停轮根 span 上下文。
     * 有界 LRU（256）：僵尸等待（用户永不回复）的残留条目不无限增长，每条仅两个十六进制串。
     */
    private final Map<String, SpanContext> hitlResumeContexts =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, SpanContext> eldest) {
                    return size() > 256;
                }
            });

    @Override
    public void markHITLPause(String resumeKey) {
        try {
            RootSpanContext ctx = rootSpanHolder.get();
            if (resumeKey == null || resumeKey.isBlank() || ctx == null) {
                return;
            }
            // 业务含义：HITL 暂停时根 span 即将随本次 HTTP 请求结束而关闭导出，
            // 保存其 SpanContext 供恢复轮以远程父上下文续接同一 trace（已结束 span 的
            // 上下文仍可作为父引用，OTel 标准异步续接模式）
            hitlResumeContexts.put(resumeKey, ctx.span().getSpanContext());
        } catch (Exception e) {
            log.warn("LangSmith HITL 暂停标记失败（降级跳过）: {}", e.getMessage());
        }
    }

    @Override
    public void resumeRequest(String resumeKey) {
        try {
            SpanContext parent = (resumeKey == null || resumeKey.isBlank())
                    ? null : hitlResumeContexts.remove(resumeKey);
            SpanBuilder builder = tracer.spanBuilder("agent.request").setSpanKind(SpanKind.SERVER);
            if (parent != null) {
                // 业务含义：以暂停轮根 span 为远程父上下文续接同一 trace（BUG 修复：
                // 多轮人机交互不再碎片化为多条独立 trace，LangSmith 呈现一条完整链路）
                builder.setParent(Context.root().with(Span.wrap(parent)));
            }
            Span root = builder.startSpan();
            Scope scope = root.makeCurrent();
            rootSpanHolder.set(new RootSpanContext(root, scope));
        } catch (Exception e) {
            log.warn("LangSmith HITL 续接根 span 启动失败（降级跳过）: {}", e.getMessage());
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

    @Override
    public void recordRag(RagRetrievalEvent event) {
        try {
            // 业务含义：RAG 检索 span（CR-001，AC-N05），动态 Tool 唯一收口埋点数据
            Instant endInstant = Instant.now();
            Instant startInstant = endInstant.minusMillis(event.durationMs());
            Span span = tracer.spanBuilder("rag search " + safe(event.kbName()))
                    .setSpanKind(SpanKind.CLIENT)
                    .setStartTimestamp(startInstant)
                    .startSpan();
            span.setAllAttributes(buildRagAttributes(event));
            setEventStatus(span, event.success(), event.errorMessage());
            span.end(endInstant);
        } catch (Exception e) {
            log.warn("LangSmith RAG 采集失败（降级跳过）: {}", e.getMessage());
        }
    }

    @Override
    public void recordMemoryCompression(MemoryCompressionEvent event) {
        try {
            // 业务含义：记忆压缩 span（CR-001，AC-N06），摘要结果脱敏截断后上报
            Instant endInstant = Instant.now();
            Instant startInstant = endInstant.minusMillis(event.durationMs());
            Span span = tracer.spanBuilder("memory compress")
                    .setSpanKind(SpanKind.CLIENT)
                    .setStartTimestamp(startInstant)
                    .startSpan();
            span.setAllAttributes(buildMemoryCompressionAttributes(event));
            span.setStatus(StatusCode.OK);
            span.end(endInstant);
        } catch (Exception e) {
            log.warn("LangSmith 记忆压缩采集失败（降级跳过）: {}", e.getMessage());
        }
    }

    @Override
    public void recordWorkflow(WorkflowExecutionEvent event) {
        try {
            // 业务含义：工作流级 span（CR-001，AC-N07/M03），根 span 语义（SERVER kind）
            Instant endInstant = Instant.now();
            Instant startInstant = endInstant.minusMillis(event.durationMs());
            Span span = tracer.spanBuilder("workflow " + safe(event.templateName()))
                    .setSpanKind(SpanKind.SERVER)
                    .setStartTimestamp(startInstant)
                    .startSpan();
            span.setAllAttributes(buildWorkflowAttributes(event));
            setEventStatus(span, "COMPLETED".equals(event.status()), event.status());
            span.end(endInstant);
        } catch (Exception e) {
            log.warn("LangSmith 工作流采集失败（降级跳过）: {}", e.getMessage());
        }
    }

    @Override
    public void recordWorkflowStep(WorkflowStepEvent event) {
        try {
            // 业务含义：工作流步骤 span（CR-001，AC-N07），executeOrSkip 统一收口埋点数据
            Instant endInstant = Instant.now();
            Instant startInstant = endInstant.minusMillis(event.durationMs());
            Span span = tracer.spanBuilder("step " + safe(event.agentName()))
                    .setSpanKind(SpanKind.CLIENT)
                    .setStartTimestamp(startInstant)
                    .startSpan();
            span.setAllAttributes(buildWorkflowStepAttributes(event));
            setEventStatus(span, "COMPLETED".equals(event.status()), event.status());
            span.end(endInstant);
        } catch (Exception e) {
            log.warn("LangSmith 工作流步骤采集失败（降级跳过）: {}", e.getMessage());
        }
    }

    @Override
    public void recordMcp(McpCallEvent event) {
        try {
            // 业务含义：MCP 协议层 span（CR-001，AC-N08），与 ToolExecutor 工具 span 双层平级（决策 9）
            Instant endInstant = Instant.now();
            Instant startInstant = endInstant.minusMillis(event.durationMs());
            Span span = tracer.spanBuilder("mcp " + safe(event.serverName()) + "." + safe(event.toolName()))
                    .setSpanKind(SpanKind.CLIENT)
                    .setStartTimestamp(startInstant)
                    .startSpan();
            span.setAllAttributes(buildMcpAttributes(event));
            setEventStatus(span, event.success(), event.errorMessage());
            span.end(endInstant);
        } catch (Exception e) {
            log.warn("LangSmith MCP 采集失败（降级跳过）: {}", e.getMessage());
        }
    }

    @Override
    public void recordSkillActivation(SkillActivationEvent event) {
        try {
            // 业务含义：Skill 激活 span（CR-001，AC-N09），成功/被拒均留痕；激活为瞬时内存操作，起止同刻
            Instant endInstant = Instant.now();
            Span span = tracer.spanBuilder("skill activate " + safe(event.skillName() != null ? event.skillName() : event.skillId()))
                    .setSpanKind(SpanKind.CLIENT)
                    .setStartTimestamp(endInstant)
                    .startSpan();
            span.setAllAttributes(buildSkillActivationAttributes(event));
            setEventStatus(span, event.success(), event.rejectedReason());
            span.end(endInstant);
        } catch (Exception e) {
            log.warn("LangSmith 技能激活采集失败（降级跳过）: {}", e.getMessage());
        }
    }

    /**
     * 统一设置 span 状态：成功 OK，失败 ERROR + error.type
     */
    private static void setEventStatus(Span span, boolean success, String errorMessage) {
        if (success) {
            span.setStatus(StatusCode.OK);
        } else {
            String err = safe(errorMessage);
            span.setStatus(StatusCode.ERROR, err);
            span.setAttribute("error.type", err);
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
     * 构建 RAG 检索 span 属性（技术方案 §7.1 检索 span 设计表，CR-001）
     */
    private Attributes buildRagAttributes(RagRetrievalEvent event) {
        AttributesBuilder b = Attributes.builder();
        b.put("rag.query", masker.maskSafe(event.query()));
        b.put("rag.kb.id", masker.maskSafe(event.kbId()));
        if (event.kbName() != null) {
            b.put("rag.kb.name", masker.maskSafe(event.kbName()));
        }
        b.put("rag.hit_count", event.hitCount());
        b.put("rag.top_k", event.topK());
        b.put("rag.max_score", event.maxScore());
        if (event.chunks() != null) {
            b.put("rag.chunks", masker.maskSafe(event.chunks()));
        }
        putContextAttributes(b);
        return b.build();
    }

    /**
     * 构建记忆压缩 span 属性（技术方案 §7.1 压缩 span 设计表，CR-001）
     */
    private Attributes buildMemoryCompressionAttributes(MemoryCompressionEvent event) {
        AttributesBuilder b = Attributes.builder();
        b.put("memory.messages_before", event.messagesBefore());
        b.put("memory.messages_after", event.messagesAfter());
        b.put("memory.compressed_count", event.compressedCount());
        b.put("memory.window", event.window());
        if (event.summary() != null) {
            b.put("memory.summary", masker.maskSafe(event.summary()));
        }
        b.put("memory.degraded", event.degraded());
        putContextAttributes(b);
        return b.build();
    }

    /**
     * 构建工作流级 span 属性（技术方案 §7.1 工作流 span 设计表，CR-001）
     */
    private Attributes buildWorkflowAttributes(WorkflowExecutionEvent event) {
        AttributesBuilder b = Attributes.builder();
        b.put("workflow.execution_id", masker.maskSafe(event.executionId()));
        b.put("workflow.template.id", masker.maskSafe(event.templateId()));
        b.put("workflow.template.name", masker.maskSafe(event.templateName()));
        b.put("workflow.mode", masker.maskSafe(event.mode()));
        b.put("workflow.status", masker.maskSafe(event.status()));
        b.put("workflow.duration_ms", event.durationMs());
        if (event.finalResult() != null) {
            b.put("workflow.final_result", masker.maskSafe(event.finalResult()));
        }
        putContextAttributes(b);
        return b.build();
    }

    /**
     * 构建工作流步骤 span 属性（技术方案 §7.1 步骤 span 设计表，CR-001）
     */
    private Attributes buildWorkflowStepAttributes(WorkflowStepEvent event) {
        AttributesBuilder b = Attributes.builder();
        b.put("workflow.execution_id", masker.maskSafe(event.executionId()));
        b.put("workflow.step.index", event.index());
        b.put("workflow.step.status", masker.maskSafe(event.status()));
        b.put("workflow.step.duration_ms", event.durationMs());
        b.put("workflow.step.retry_count", event.retryCount());
        if (event.output() != null) {
            b.put("workflow.step.output", masker.maskSafe(event.output()));
        }
        putContextAttributes(b);
        return b.build();
    }

    /**
     * 构建 MCP 协议层 span 属性（技术方案 §7.1 MCP span 设计表，CR-001）
     */
    private Attributes buildMcpAttributes(McpCallEvent event) {
        AttributesBuilder b = Attributes.builder();
        b.put("mcp.server", masker.maskSafe(event.serverName()));
        b.put("mcp.tool", masker.maskSafe(event.toolName()));
        if (event.arguments() != null) {
            b.put("mcp.arguments", masker.maskSafe(event.arguments()));
        }
        b.put("mcp.duration_ms", event.durationMs());
        b.put("mcp.disconnected", event.disconnected());
        putContextAttributes(b);
        return b.build();
    }

    /**
     * 构建 Skill 激活 span 属性（技术方案 §7.1 激活 span 设计表，CR-001）
     */
    private Attributes buildSkillActivationAttributes(SkillActivationEvent event) {
        AttributesBuilder b = Attributes.builder();
        b.put("skill.id", masker.maskSafe(event.skillId()));
        if (event.skillName() != null) {
            b.put("skill.name", masker.maskSafe(event.skillName()));
        }
        if (event.source() != null) {
            b.put("skill.source", masker.maskSafe(event.source()));
        }
        if (event.boundTools() != null) {
            b.put("skill.bound_tools", masker.maskSafe(event.boundTools()));
        }
        if (event.rejectedReason() != null) {
            b.put("skill.rejected_reason", masker.maskSafe(event.rejectedReason()));
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
