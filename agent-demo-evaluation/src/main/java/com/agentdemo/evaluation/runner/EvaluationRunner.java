package com.agentdemo.evaluation.runner;

import com.agentdemo.evaluation.model.EvalCase;
import com.agentdemo.evaluation.model.EvalDataset;
import com.agentdemo.evaluation.model.ExecutionRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 评估执行器（langsmith-observability CR-002 Task-27）
 * <p>
 * 业务含义：逐例真实发起对话并留痕（AC-N10）——每例每次运行使用全新会话
 * （会话隔离语义）；支持 Pass^runs 多次运行；单例失败标注 error 后继续后续用例，
 * 不中断整体评估（AC-N10"单例失败标注后继续"）。工具轨迹经
 * {@link RecordingTraceCollector} 采集（执行前 reset 防跨用例串扰）。
 * </p>
 */
public class EvaluationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvaluationRunner.class);

    private final AgentInvoker invoker;
    private final RecordingTraceCollector collector;

    public EvaluationRunner(AgentInvoker invoker, RecordingTraceCollector collector) {
        this.invoker = invoker;
        this.collector = collector;
    }

    /**
     * 执行整个数据集
     *
     * @param dataset 评估数据集
     * @param runs    每用例运行次数（Pass^runs，至少 1）
     * @return 全部执行记录（条数 = 用例数 × runs）
     */
    public List<ExecutionRecord> run(EvalDataset dataset, int runs) {
        int effectiveRuns = Math.max(1, runs);
        List<ExecutionRecord> all = new ArrayList<>();
        for (EvalCase c : dataset.cases()) {
            for (int r = 0; r < effectiveRuns; r++) {
                String sessionId = UUID.randomUUID().toString();
                long start = System.currentTimeMillis();
                try {
                    collector.reset();
                    String response = invoker.invoke(sessionId, c.input());
                    List<String> trace = collector.toolNames();
                    all.add(new ExecutionRecord(c.id(), c.input(), response, trace, null, elapsed(start),
                            collector.toolTraceDetail()));
                } catch (Exception e) {
                    // 业务含义：单例失败标注后继续，不中断整体评估（AC-N10）
                    log.warn("用例 {} 第 {} 次执行失败: {}", c.id(), r + 1, e.getMessage());
                    all.add(new ExecutionRecord(c.id(), c.input(), "", collector.toolNames(),
                            e.getClass().getSimpleName() + ": " + e.getMessage(), elapsed(start),
                            collector.toolTraceDetail()));
                }
            }
        }
        return all;
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
