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

    // ===== CR-001 Task-16：六类新 span 构建（AC-N05~N09、AC-S06、AC-M03） =====

    @Test
    void ragSpan_hasDesignAttributes() {
        collector.startRequest();
        collector.recordRag(new TraceCollector.RagRetrievalEvent(
                "kb-1", "什么是向量化", "产品手册", 3, 5, 0.86,
                "【片段1】来源: 产品手册/guide.md\n内容...", 120L, true, null));
        collector.endRequest();

        SpanData rag = spanByName(exporter.getFinishedSpanItems(), "rag search 产品手册");
        var attrs = rag.getAttributes();
        assertThat(attrs.get(AttributeKey.stringKey("rag.query"))).isEqualTo("什么是向量化");
        assertThat(attrs.get(AttributeKey.stringKey("rag.kb.id"))).isEqualTo("kb-1");
        assertThat(attrs.get(AttributeKey.stringKey("rag.kb.name"))).isEqualTo("产品手册");
        assertThat(attrs.get(AttributeKey.longKey("rag.hit_count"))).isEqualTo(3L);
        assertThat(attrs.get(AttributeKey.longKey("rag.top_k"))).isEqualTo(5L);
        assertThat(attrs.get(AttributeKey.doubleKey("rag.max_score"))).isEqualTo(0.86);
        assertThat(attrs.get(AttributeKey.stringKey("rag.chunks"))).contains("片段1");
        // AC-M01/AC-N02：上下文属性注入
        assertThat(attrs.get(AttributeKey.stringKey("gen_ai.conversation.id"))).isEqualTo("session-1");
        assertThat(rag.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
    }

    @Test
    void ragSpan_failure_producesErrorStatus() {
        collector.startRequest();
        collector.recordRag(new TraceCollector.RagRetrievalEvent(
                "kb-1", "q", "产品手册", 0, 5, 0.0, null, 1500L, false, "embedding 服务异常"));
        collector.endRequest();

        SpanData rag = spanByName(exporter.getFinishedSpanItems(), "rag search 产品手册");
        assertThat(rag.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(rag.getAttributes().get(AttributeKey.stringKey("error.type"))).isEqualTo("embedding 服务异常");
    }

    @Test
    void memoryCompressionSpan_hasDesignAttributes() {
        collector.startRequest();
        collector.recordMemoryCompression(new TraceCollector.MemoryCompressionEvent(
                20, 10, 11, 20, "【历史对话摘要】用户咨询订单...", false, 800L));
        collector.endRequest();

        SpanData m = spanByName(exporter.getFinishedSpanItems(), "memory compress");
        var attrs = m.getAttributes();
        assertThat(attrs.get(AttributeKey.longKey("memory.messages_before"))).isEqualTo(20L);
        assertThat(attrs.get(AttributeKey.longKey("memory.messages_after"))).isEqualTo(10L);
        assertThat(attrs.get(AttributeKey.longKey("memory.compressed_count"))).isEqualTo(11L);
        assertThat(attrs.get(AttributeKey.longKey("memory.window"))).isEqualTo(20L);
        assertThat(attrs.get(AttributeKey.stringKey("memory.summary"))).startsWith("【历史对话摘要】");
        assertThat(attrs.get(AttributeKey.booleanKey("memory.degraded"))).isFalse();
    }

    @Test
    void workflowSpan_hasDesignAttributes_andServerKind() {
        collector.startRequest();
        collector.recordWorkflow(new TraceCollector.WorkflowExecutionEvent(
                "exec-1", "tmpl-1", "内容审查", "SEQUENTIAL", "COMPLETED", 5000L, "审查报告"));
        collector.endRequest();

        SpanData w = spanByName(exporter.getFinishedSpanItems(), "workflow 内容审查");
        var attrs = w.getAttributes();
        assertThat(attrs.get(AttributeKey.stringKey("workflow.execution_id"))).isEqualTo("exec-1");
        assertThat(attrs.get(AttributeKey.stringKey("workflow.template.id"))).isEqualTo("tmpl-1");
        assertThat(attrs.get(AttributeKey.stringKey("workflow.template.name"))).isEqualTo("内容审查");
        assertThat(attrs.get(AttributeKey.stringKey("workflow.mode"))).isEqualTo("SEQUENTIAL");
        assertThat(attrs.get(AttributeKey.stringKey("workflow.status"))).isEqualTo("COMPLETED");
        assertThat(attrs.get(AttributeKey.longKey("workflow.duration_ms"))).isEqualTo(5000L);
        assertThat(attrs.get(AttributeKey.stringKey("workflow.final_result"))).isEqualTo("审查报告");
        // AC-M03：工作流路径以 executionId 作聚合键（holder 的 sessionId）
        assertThat(attrs.get(AttributeKey.stringKey("gen_ai.conversation.id"))).isEqualTo("session-1");
        assertThat(w.getKind()).isEqualTo(io.opentelemetry.api.trace.SpanKind.SERVER);
    }

    @Test
    void workflowStepSpan_hasDesignAttributes() {
        collector.startRequest();
        collector.recordWorkflowStep(new TraceCollector.WorkflowStepEvent(
                "exec-1", "写作助手", 2, "COMPLETED", 1200L, 1, "草稿输出"));
        collector.endRequest();

        SpanData s = spanByName(exporter.getFinishedSpanItems(), "step 写作助手");
        var attrs = s.getAttributes();
        assertThat(attrs.get(AttributeKey.stringKey("workflow.execution_id"))).isEqualTo("exec-1");
        assertThat(attrs.get(AttributeKey.longKey("workflow.step.index"))).isEqualTo(2L);
        assertThat(attrs.get(AttributeKey.stringKey("workflow.step.status"))).isEqualTo("COMPLETED");
        assertThat(attrs.get(AttributeKey.longKey("workflow.step.duration_ms"))).isEqualTo(1200L);
        assertThat(attrs.get(AttributeKey.longKey("workflow.step.retry_count"))).isEqualTo(1L);
        assertThat(attrs.get(AttributeKey.stringKey("workflow.step.output"))).isEqualTo("草稿输出");
    }

    @Test
    void mcpSpan_hasDesignAttributes() {
        collector.startRequest();
        collector.recordMcp(new TraceCollector.McpCallEvent(
                "weather-server", "getWeather", "{\"city\":\"北京\"}", 300L, true, false, null));
        collector.endRequest();

        SpanData mcp = spanByName(exporter.getFinishedSpanItems(), "mcp weather-server.getWeather");
        var attrs = mcp.getAttributes();
        assertThat(attrs.get(AttributeKey.stringKey("mcp.server"))).isEqualTo("weather-server");
        assertThat(attrs.get(AttributeKey.stringKey("mcp.tool"))).isEqualTo("getWeather");
        assertThat(attrs.get(AttributeKey.stringKey("mcp.arguments"))).isEqualTo("{\"city\":\"北京\"}");
        assertThat(attrs.get(AttributeKey.longKey("mcp.duration_ms"))).isEqualTo(300L);
        assertThat(attrs.get(AttributeKey.booleanKey("mcp.disconnected"))).isFalse();
        assertThat(mcp.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
    }

    @Test
    void mcpSpan_disconnected_failure_producesErrorStatus() {
        collector.startRequest();
        collector.recordMcp(new TraceCollector.McpCallEvent(
                "weather-server", "getWeather", "{}", 500L, false, true, "IO 异常断线"));
        collector.endRequest();

        SpanData mcp = spanByName(exporter.getFinishedSpanItems(), "mcp weather-server.getWeather");
        assertThat(mcp.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(mcp.getAttributes().get(AttributeKey.booleanKey("mcp.disconnected"))).isTrue();
        assertThat(mcp.getAttributes().get(AttributeKey.stringKey("error.type"))).isEqualTo("IO 异常断线");
    }

    @Test
    void skillActivationSpan_hasDesignAttributes() {
        collector.startRequest();
        collector.recordSkillActivation(new TraceCollector.SkillActivationEvent(
                "skill-1", "数据分析", "AUTO", "skill_1_analyze", true, null));
        collector.endRequest();

        SpanData s = spanByName(exporter.getFinishedSpanItems(), "skill activate 数据分析");
        var attrs = s.getAttributes();
        assertThat(attrs.get(AttributeKey.stringKey("skill.id"))).isEqualTo("skill-1");
        assertThat(attrs.get(AttributeKey.stringKey("skill.name"))).isEqualTo("数据分析");
        assertThat(attrs.get(AttributeKey.stringKey("skill.source"))).isEqualTo("AUTO");
        assertThat(attrs.get(AttributeKey.stringKey("skill.bound_tools"))).isEqualTo("skill_1_analyze");
        assertThat(s.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
    }

    @Test
    void skillActivationRejected_hasReason_andErrorStatus() {
        collector.startRequest();
        collector.recordSkillActivation(new TraceCollector.SkillActivationEvent(
                "skill-1", null, "AUTO", null, false, "已达并发激活上限"));
        collector.endRequest();

        SpanData s = exporter.getFinishedSpanItems().stream()
                .filter(sp -> sp.getName().startsWith("skill activate")).findFirst().orElseThrow();
        assertThat(s.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(s.getAttributes().get(AttributeKey.stringKey("skill.rejected_reason"))).isEqualTo("已达并发激活上限");
    }

    @Test
    void newContentTypes_withSecretKeys_areMasked() {
        // AC-S06：新增五域内容字段全部经脱敏单一出口——检索命中块/MCP 参数/压缩摘要含密钥均无明文
        collector.startRequest();
        collector.recordRag(new TraceCollector.RagRetrievalEvent(
                "kb-1", "q", "产品手册", 1, 5, 0.9,
                "配置中密钥为 sk-abcDEF123456789012345678，请勿外泄", 10L, true, null));
        collector.recordMcp(new TraceCollector.McpCallEvent(
                "srv", "call", "{\"token\":\"Bearer abcDEFghIJKLmnopQRSTuvwXYZ1234567890\"}", 10L, true, false, null));
        collector.recordMemoryCompression(new TraceCollector.MemoryCompressionEvent(
                20, 10, 11, 20, "密钥 sk-abcDEF123456789012345678 已归档", false, 10L));
        collector.endRequest();

        SpanData rag = spanByName(exporter.getFinishedSpanItems(), "rag search 产品手册");
        assertThat(rag.getAttributes().get(AttributeKey.stringKey("rag.chunks")))
                .doesNotContain("sk-abcDEF123456789012345678").contains("[REDACTED]");
        SpanData mcp = spanByName(exporter.getFinishedSpanItems(), "mcp srv.call");
        assertThat(mcp.getAttributes().get(AttributeKey.stringKey("mcp.arguments")))
                .doesNotContain("abcDEFghIJKLmnopQRSTuvwXYZ1234567890").contains("[REDACTED]");
        SpanData mem = spanByName(exporter.getFinishedSpanItems(), "memory compress");
        assertThat(mem.getAttributes().get(AttributeKey.stringKey("memory.summary")))
                .doesNotContain("sk-abcDEF123456789012345678").contains("[REDACTED]");
    }

    // ==================== HITL 暂停/恢复 trace 续接（BUG 修复） ====================

    @Test
    void reproduction_hitlRounds_currentBehavior_fragmentsIntoSeparateTraces() {
        // 复现 BUG（用户报告：单任务多轮人机交互，LangSmith 呈现一条完整链路 + 多条独立短链路）：
        // 当前 Controller 对每个 HTTP 请求（含 HITL 恢复轮）都调用 startRequest() 创建随机 traceId
        // 的全新根 span -> 两轮 traceId 不同（碎片化）。本测试文档化该行为基线（非 HITL 独立请求
        // 保持独立 trace 是正确语义，碎片化问题由下方续接测试修复）。
        collector.startRequest();
        collector.recordLlm(new TraceCollector.LlmCallEvent(
                "glm-5.2", "q", "a", 10, 20, 100L, true, null, "stop"));
        collector.endRequest();
        collector.startRequest();
        collector.recordLlm(new TraceCollector.LlmCallEvent(
                "glm-5.2", "reply", "a2", 10, 20, 100L, true, null, "stop"));
        collector.endRequest();

        List<SpanData> roots = exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals("agent.request")).toList();
        assertThat(roots).hasSize(2);
        assertThat(roots.get(0).getTraceId()).isNotEqualTo(roots.get(1).getTraceId());
    }

    @Test
    void hitlPauseResume_roundsShareSameTrace() {
        // BUG 修复验证：单任务多轮人机交互应共享同一 trace（LangSmith 一条完整链路）--
        // 暂停轮 markHITLPause 保存根 span 上下文，恢复轮 resumeRequest 以其为父续接
        collector.startRequest();
        collector.recordLlm(new TraceCollector.LlmCallEvent(
                "glm-5.2", "q", "a", 10, 20, 100L, true, null, "tool_calls"));
        collector.markHITLPause("session-1");
        collector.endRequest();

        collector.resumeRequest("session-1");
        collector.recordLlm(new TraceCollector.LlmCallEvent(
                "glm-5.2", "reply", "final", 10, 20, 100L, true, null, "stop"));
        collector.endRequest();

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(4);
        List<SpanData> roots = spans.stream()
                .filter(s -> s.getName().equals("agent.request")).toList();
        assertThat(roots).hasSize(2);
        // 全部 span（两轮根 + 两轮子 span）共享同一 traceId：单任务一条完整链路
        String traceId = roots.get(0).getTraceId();
        assertThat(spans).allMatch(s -> s.getTraceId().equals(traceId));
        // 恢复轮根 span 是暂停轮根 span 的子节点（链路连续可览）
        assertThat(roots.get(1).getParentSpanId()).isEqualTo(roots.get(0).getSpanId());
    }

    @Test
    void hitlMultiRound_chainContinuesThroughEachPause() {
        // 三轮链式续接：每轮暂停均以最新根 span 为父，traceId 全程一致
        collector.startRequest();
        collector.markHITLPause("session-2");
        collector.endRequest();
        collector.resumeRequest("session-2");
        collector.markHITLPause("session-2");
        collector.endRequest();
        collector.resumeRequest("session-2");
        collector.endRequest();

        List<SpanData> roots = exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals("agent.request")).toList();
        assertThat(roots).hasSize(3);
        assertThat(roots.get(1).getParentSpanId()).isEqualTo(roots.get(0).getSpanId());
        assertThat(roots.get(2).getParentSpanId()).isEqualTo(roots.get(1).getSpanId());
        assertThat(roots.get(2).getTraceId()).isEqualTo(roots.get(0).getTraceId());
    }

    @Test
    void resumeRequest_withoutPause_fallsBackToFreshTrace() {
        // 无暂停记录（如服务重启后恢复/超时清理后新消息）回退独立新 trace，不误续接
        collector.startRequest();
        collector.endRequest();
        collector.resumeRequest("no-pause-session");
        collector.recordLlm(new TraceCollector.LlmCallEvent(
                "glm-5.2", "q", "a", 10, 20, 100L, true, null, "stop"));
        collector.endRequest();

        List<SpanData> roots = exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals("agent.request")).toList();
        assertThat(roots).hasSize(2);
        assertThat(roots.get(0).getTraceId()).isNotEqualTo(roots.get(1).getTraceId());
        // 新请求的子 span 归属新根
        SpanData llm = spanByName(exporter.getFinishedSpanItems(), "chat glm-5.2");
        assertThat(llm.getTraceId()).isEqualTo(roots.get(1).getTraceId());
    }
}
