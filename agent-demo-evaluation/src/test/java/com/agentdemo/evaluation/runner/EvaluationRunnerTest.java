package com.agentdemo.evaluation.runner;

import com.agentdemo.evaluation.model.EvalCase;
import com.agentdemo.evaluation.model.EvalDataset;
import com.agentdemo.evaluation.model.ExecutionRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 评估执行层行为测试（langsmith-observability CR-002 Task-27）
 * <p>
 * 业务含义：验证 EvaluationRunner 逐例真实执行语义（AC-N10）——每例独立会话、
 * 运行次数（Pass^runs）支持、工具轨迹采集、单例失败标注后继续不中断。
 * </p>
 */
class EvaluationRunnerTest {

    private final RecordingTraceCollector collector = new RecordingTraceCollector();

    /**
     * 记录每次调用的会话与输入，供断言独立会话与次数
     */
    private static class Invocation {
        final String sessionId;
        final String input;

        Invocation(String sessionId, String input) {
            this.sessionId = sessionId;
            this.input = input;
        }
    }

    private AgentInvoker invokerReturning(ConcurrentLinkedQueue<Invocation> log) {
        return (sessionId, input) -> {
            log.add(new Invocation(sessionId, input));
            collector.recordTool(new com.agentdemo.observability.TraceCollector.ToolCallEvent(
                    "getCurrentTime", "{}", "2026-08-31 12:00", 5, true, null));
            return "现在是 2026-08-31 12:00";
        };
    }

    @Test
    void run_eachCaseRunsSpecifiedTimes_withFreshSession() {
        ConcurrentLinkedQueue<Invocation> log = new ConcurrentLinkedQueue<>();
        EvaluationRunner runner = new EvaluationRunner(invokerReturning(log), collector);

        EvalCase c1 = new EvalCase("case-1", "direct-answer", "你好", "", List.of(), false, false, "");
        EvalCase c2 = new EvalCase("case-2", "single-tool", "几点了", "getCurrentTime", List.of("时间"), false, false, "");
        EvalDataset ds = new EvalDataset("v1", List.of(c1, c2));

        List<ExecutionRecord> records = runner.run(ds, 3);

        // 2 用例 × 3 次 = 6 次执行
        assertThat(records).hasSize(6);
        assertThat(log).hasSize(6);
        // 每例 3 次会话互不相同（独立会话，AC-N10）
        List<String> case1Sessions = log.stream().filter(i -> i.input.equals("你好"))
                .map(i -> i.sessionId).toList();
        assertThat(case1Sessions).hasSize(3);
        assertThat(case1Sessions).allMatch(s -> isUuid(s));
        assertThat(case1Sessions.stream().distinct().count()).isEqualTo(3);
        // 输入透传
        assertThat(log).extracting(i -> i.input).containsExactlyInAnyOrder("你好", "你好", "你好", "几点了", "几点了", "几点了");
    }

    @Test
    void run_capturesToolTraceIntoRecord() {
        ConcurrentLinkedQueue<Invocation> log = new ConcurrentLinkedQueue<>();
        EvaluationRunner runner = new EvaluationRunner(invokerReturning(log), collector);

        EvalCase c = new EvalCase("case-1", "single-tool", "几点了", "getCurrentTime", List.of("时间"), false, false, "");
        List<ExecutionRecord> records = runner.run(new EvalDataset("v1", List.of(c)), 1);

        assertThat(records.get(0).toolTrace()).containsExactly("getCurrentTime");
        assertThat(records.get(0).response()).contains("2026-08-31");
        assertThat(records.get(0).error()).isNull();
        assertThat(records.get(0).durationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void run_capturesToolTraceDetailWithResult() {
        // 业务含义：judge 幻觉复核需工具结果（而非仅工具名）——轨迹详情应含结果文本
        RecordingTraceCollector c = new RecordingTraceCollector();
        AgentInvoker invoker = (s, i) -> {
            c.recordTool(new com.agentdemo.observability.TraceCollector.ToolCallEvent(
                    "calculate", "{}", "56088", 5, true, null));
            return "56088";
        };
        EvaluationRunner runner = new EvaluationRunner(invoker, c);

        EvalCase case1 = new EvalCase("case-1", "single-tool", "计算", "calculate", List.of(), false, false, "");
        List<ExecutionRecord> records = runner.run(new EvalDataset("v1", List.of(case1)), 1);

        assertThat(records.get(0).toolTraceDetail()).contains("calculate").contains("56088");
    }

    @Test
    void run_agentFailure_marksError_andContinuesNextCase() {
        ConcurrentLinkedQueue<Invocation> log = new ConcurrentLinkedQueue<>();
        AgentInvoker invoker = (sessionId, input) -> {
            log.add(new Invocation(sessionId, input));
            if (input.contains("失败")) {
                throw new IllegalStateException("模型不可用");
            }
            return "正常回复";
        };
        EvaluationRunner runner = new EvaluationRunner(invoker, collector);

        EvalCase bad = new EvalCase("case-bad", "direct-answer", "触发失败", "", List.of(), false, false, "");
        EvalCase good = new EvalCase("case-good", "direct-answer", "正常", "", List.of(), false, false, "");
        List<ExecutionRecord> records = runner.run(new EvalDataset("v1", List.of(bad, good)), 1);

        assertThat(records).hasSize(2);
        assertThat(records.get(0).error()).contains("模型不可用");
        assertThat(records.get(0).response()).isEmpty();
        assertThat(records.get(1).error()).isNull();
        assertThat(records.get(1).response()).isEqualTo("正常回复");
    }

    @Test
    void run_emptyDataset_returnsEmptyList() {
        EvaluationRunner runner = new EvaluationRunner((s, i) -> "x", collector);
        assertThat(runner.run(new EvalDataset("v1", List.of()), 3)).isEmpty();
    }

    private static boolean isUuid(String s) {
        try {
            UUID.fromString(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
