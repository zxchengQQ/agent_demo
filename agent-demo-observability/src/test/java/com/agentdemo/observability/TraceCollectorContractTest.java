package com.agentdemo.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * TraceCollector 六类新事件契约测试（CR-001 Task-15）
 * <p>
 * 业务含义：验证六类新事件 record 字段与技术方案 §7.1 span 设计表一一对应（AC-N05~N09），
 * NoopTraceCollector 六新方法无副作用、不抛异常（默认关闭零开销，AC-S02 语义保持）。
 * </p>
 */
class TraceCollectorContractTest {

    @Test
    void ragRetrievalEvent_hasAllDesignFields() {
        var e = new TraceCollector.RagRetrievalEvent(
                "kb-1", "什么是向量化", "产品手册", 3, 5, 0.86,
                "【片段1】内容...", 120L, true, null);
        assertThat(e.kbId()).isEqualTo("kb-1");
        assertThat(e.query()).isEqualTo("什么是向量化");
        assertThat(e.kbName()).isEqualTo("产品手册");
        assertThat(e.hitCount()).isEqualTo(3);
        assertThat(e.topK()).isEqualTo(5);
        assertThat(e.maxScore()).isEqualTo(0.86);
        assertThat(e.chunks()).contains("片段1");
        assertThat(e.durationMs()).isEqualTo(120L);
        assertThat(e.success()).isTrue();
        assertThat(e.errorMessage()).isNull();
    }

    @Test
    void memoryCompressionEvent_hasAllDesignFields() {
        var e = new TraceCollector.MemoryCompressionEvent(
                20, 10, 11, 20, "【历史对话摘要】...", false, 800L);
        assertThat(e.messagesBefore()).isEqualTo(20);
        assertThat(e.messagesAfter()).isEqualTo(10);
        assertThat(e.compressedCount()).isEqualTo(11);
        assertThat(e.window()).isEqualTo(20);
        assertThat(e.summary()).startsWith("【历史对话摘要】");
        assertThat(e.degraded()).isFalse();
        assertThat(e.durationMs()).isEqualTo(800L);
    }

    @Test
    void workflowExecutionEvent_hasAllDesignFields() {
        var e = new TraceCollector.WorkflowExecutionEvent(
                "exec-1", "tmpl-1", "内容审查", "SEQUENTIAL", "COMPLETED", 5000L, "最终报告");
        assertThat(e.executionId()).isEqualTo("exec-1");
        assertThat(e.templateId()).isEqualTo("tmpl-1");
        assertThat(e.templateName()).isEqualTo("内容审查");
        assertThat(e.mode()).isEqualTo("SEQUENTIAL");
        assertThat(e.status()).isEqualTo("COMPLETED");
        assertThat(e.durationMs()).isEqualTo(5000L);
        assertThat(e.finalResult()).isEqualTo("最终报告");
    }

    @Test
    void workflowStepEvent_hasAllDesignFields() {
        var e = new TraceCollector.WorkflowStepEvent(
                "exec-1", "写作助手", 2, "COMPLETED", 1200L, 1, "草稿输出");
        assertThat(e.executionId()).isEqualTo("exec-1");
        assertThat(e.agentName()).isEqualTo("写作助手");
        assertThat(e.index()).isEqualTo(2);
        assertThat(e.status()).isEqualTo("COMPLETED");
        assertThat(e.durationMs()).isEqualTo(1200L);
        assertThat(e.retryCount()).isEqualTo(1);
        assertThat(e.output()).isEqualTo("草稿输出");
    }

    @Test
    void mcpCallEvent_hasAllDesignFields() {
        var e = new TraceCollector.McpCallEvent(
                "weather-server", "getWeather", "{\"city\":\"北京\"}", 300L, true, false, null);
        assertThat(e.serverName()).isEqualTo("weather-server");
        assertThat(e.toolName()).isEqualTo("getWeather");
        assertThat(e.arguments()).isEqualTo("{\"city\":\"北京\"}");
        assertThat(e.durationMs()).isEqualTo(300L);
        assertThat(e.success()).isTrue();
        assertThat(e.disconnected()).isFalse();
        assertThat(e.errorMessage()).isNull();
    }

    @Test
    void skillActivationEvent_hasAllDesignFields() {
        var e = new TraceCollector.SkillActivationEvent(
                "skill-1", "数据分析", "AUTO", "skill_1_analyze", true, null);
        assertThat(e.skillId()).isEqualTo("skill-1");
        assertThat(e.skillName()).isEqualTo("数据分析");
        assertThat(e.source()).isEqualTo("AUTO");
        assertThat(e.boundTools()).isEqualTo("skill_1_analyze");
        assertThat(e.success()).isTrue();
        assertThat(e.rejectedReason()).isNull();
    }

    @Test
    void noopCollector_sixNewMethods_areSideEffectFree() {
        NoopTraceCollector noop = new NoopTraceCollector();
        assertThatCode(() -> noop.recordRag(new TraceCollector.RagRetrievalEvent(
                "kb-1", "q", "kb", 1, 5, 0.5, "c", 10L, true, null))).doesNotThrowAnyException();
        assertThatCode(() -> noop.recordMemoryCompression(new TraceCollector.MemoryCompressionEvent(
                20, 10, 11, 20, "s", false, 10L))).doesNotThrowAnyException();
        assertThatCode(() -> noop.recordWorkflow(new TraceCollector.WorkflowExecutionEvent(
                "e", "t", "n", "SEQUENTIAL", "COMPLETED", 10L, "r"))).doesNotThrowAnyException();
        assertThatCode(() -> noop.recordWorkflowStep(new TraceCollector.WorkflowStepEvent(
                "e", "a", 0, "COMPLETED", 10L, 0, "o"))).doesNotThrowAnyException();
        assertThatCode(() -> noop.recordMcp(new TraceCollector.McpCallEvent(
                "s", "t", "{}", 10L, true, false, null))).doesNotThrowAnyException();
        assertThatCode(() -> noop.recordSkillActivation(new TraceCollector.SkillActivationEvent(
                "s", "n", "AUTO", "t", true, null))).doesNotThrowAnyException();
        assertThat(noop.isEnabled()).isFalse();
    }
}
