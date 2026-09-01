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

    // ============ CR-001 Task-24：五域采集完整性（AC-N05~N09、AC-M03、AC-E05） ============

    /**
     * 模拟一次含五域事件 + LLM/工具的完整会话：检索/压缩/工作流+步骤/MCP/技能激活
     * 全部 span 存在、共享根 trace、携带聚合属性（AC-N05~N09、AC-M03）
     */
    @Test
    void fiveDomains_allSpansPresent_sharedTraceAndContext() {
        collector.startRequest();
        // 基础链路：LLM + 工具
        collector.recordLlm(new TraceCollector.LlmCallEvent(
                "modelA", "user: 分析", "tool_calls", 10, 5, 100L, true, null, "tool_calls"));
        collector.recordTool(new TraceCollector.ToolCallEvent(
                "queryData", "{\"id\":\"1\"}", "结果", 20L, true, null));
        // RAG 检索
        collector.recordRag(new TraceCollector.RagRetrievalEvent(
                "kb-1", "什么是向量化", "产品手册", 2, 5, 0.9, "【片段1】...", 50L, true, null));
        // 记忆压缩
        collector.recordMemoryCompression(new TraceCollector.MemoryCompressionEvent(
                20, 10, 11, 20, "【历史对话摘要】...", false, 800L));
        // 工作流级 + 步骤级
        collector.recordWorkflow(new TraceCollector.WorkflowExecutionEvent(
                "exec-1", "tmpl-1", "内容审查", "SEQUENTIAL", "COMPLETED", 5000L, "报告"));
        collector.recordWorkflowStep(new TraceCollector.WorkflowStepEvent(
                "exec-1", "审查 Agent", 0, "COMPLETED", 1200L, 0, "草稿"));
        // MCP 协议层
        collector.recordMcp(new TraceCollector.McpCallEvent(
                "weather-server", "getWeather", "{\"city\":\"北京\"}", 300L, true, false, null));
        // Skill 激活
        collector.recordSkillActivation(new TraceCollector.SkillActivationEvent(
                "skill-1", "数据分析", "AUTO", "skill_1_analyze", true, null));
        collector.endRequest();

        List<SpanData> spans = exporter.getFinishedSpanItems();
        // 1 根 + 1 LLM + 1 工具 + 1 检索 + 1 压缩 + 1 工作流 + 1 步骤 + 1 MCP + 1 激活 = 9
        assertThat(spans).hasSize(9);

        SpanData root = spanByName("agent.request");
        String rootTraceId = root.getTraceId();
        for (SpanData s : spans) {
            assertThat(s.getTraceId()).isEqualTo(rootTraceId);
            if (!"agent.request".equals(s.getName())) {
                assertThat(s.getParentSpanId()).isEqualTo(root.getSpanId());
            }
        }

        // AC-N05~N09：五域 span 必备字段
        assertThat(spanByName("rag search 产品手册").getAttributes().get(AttributeKey.longKey("rag.hit_count"))).isEqualTo(2L);
        assertThat(spanByName("memory compress").getAttributes().get(AttributeKey.booleanKey("memory.degraded"))).isFalse();
        assertThat(attr(spanByName("workflow 内容审查"), "workflow.execution_id")).isEqualTo("exec-1");
        assertThat(attr(spanByName("step 审查 Agent"), "workflow.step.output")).isEqualTo("草稿");
        assertThat(attr(spanByName("mcp weather-server.getWeather"), "mcp.tool")).isEqualTo("getWeather");
        assertThat(attr(spanByName("skill activate 数据分析"), "skill.source")).isEqualTo("AUTO");

        // AC-M03：工作流/步骤 span 按 executionId 关联（workflow.execution_id 一致）
        assertThat(attr(spanByName("workflow 内容审查"), "workflow.execution_id"))
                .isEqualTo(attr(spanByName("step 审查 Agent"), "workflow.execution_id"));
        // AC-M01/N02：会话聚合属性注入（holder sessionId）
        assertThat(attr(spanByName("mcp weather-server.getWeather"), "gen_ai.conversation.id")).isEqualTo("session-react-1");
        assertThat(attr(spanByName("skill activate 数据分析"), "log.trace_id")).isEqualTo("trace-react-1");
    }

    @Test
    void fiveDomains_failureInjection_spansNotLost() {
        // 失败注入：检索异常/MCP 断线/技能被拒均留痕（AC-N05~N09 失败不丢）
        collector.startRequest();
        collector.recordRag(new TraceCollector.RagRetrievalEvent(
                "kb-1", "q", "产品手册", 0, 5, 0.0, null, 500L, false, "embedding 异常"));
        collector.recordMcp(new TraceCollector.McpCallEvent(
                "srv", "tool", "{}", 300L, false, true, "IO 异常断线"));
        collector.recordSkillActivation(new TraceCollector.SkillActivationEvent(
                "skill-1", null, "AUTO", null, false, "已达并发激活上限"));
        collector.recordWorkflow(new TraceCollector.WorkflowExecutionEvent(
                "exec-1", "tmpl-1", "内容审查", "SEQUENTIAL", "FAILED", 5000L, null));
        collector.endRequest();

        assertThat(spanByName("rag search 产品手册").getStatus().getStatusCode().name()).isEqualTo("ERROR");
        assertThat(spanByName("mcp srv.tool").getAttributes().get(AttributeKey.booleanKey("mcp.disconnected"))).isTrue();
        assertThat(spanByName("skill activate skill-1").getStatus().getStatusCode().name()).isEqualTo("ERROR");
        assertThat(attr(spanByName("skill activate skill-1"), "skill.rejected_reason")).isEqualTo("已达并发激活上限");
        assertThat(spanByName("workflow 内容审查").getStatus().getStatusCode().name()).isEqualTo("ERROR");
    }

    @Test
    void contextHolder_cleared_noLeakAfterRequest() {
        // AC-E05：请求结束（finally）清理上下文，无 ThreadLocal 泄漏
        TraceContextHolder.set(new TraceContextHolder.TraceContext("trace-react-1", "session-react-1"));
        collector.startRequest();
        collector.recordRag(new TraceCollector.RagRetrievalEvent(
                "kb-1", "q", "手册", 1, 5, 0.9, "c", 10L, true, null));
        collector.endRequest();

        // 模拟异步边界 finally 清理
        TraceContextHolder.clear();
        assertThat(TraceContextHolder.currentSessionId()).isNull();
        assertThat(TraceContextHolder.currentTraceId()).isNull();
        // 清理后采集不串扰（新建上下文后记录到不同会话）
        TraceContextHolder.set(new TraceContextHolder.TraceContext("trace-2", "session-2"));
        collector.startRequest();
        collector.recordMcp(new TraceCollector.McpCallEvent("s", "t", "{}", 5L, true, false, null));
        collector.endRequest();
        assertThat(attr(spanByName("mcp s.t"), "gen_ai.conversation.id")).isEqualTo("session-2");
        TraceContextHolder.clear();
    }
}
