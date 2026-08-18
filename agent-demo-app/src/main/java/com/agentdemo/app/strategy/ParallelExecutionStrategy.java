package com.agentdemo.app.strategy;

import com.agentdemo.app.core.AgentDefinition;
import com.agentdemo.app.core.OrchestrationMode;
import com.agentdemo.app.core.ParallelGroup;
import com.agentdemo.app.core.WorkflowContext;
import com.agentdemo.app.core.WorkflowExecution;
import com.agentdemo.app.core.WorkflowTemplate;
import com.agentdemo.app.execution.AgentExecutor;
import com.agentdemo.app.execution.WorkflowEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 并行编排策略
 * <p>
 * 业务含义：多个并行分组同时执行，每组内部 Agent 串行，各组结果写入共享 WorkflowContext，
 * 全部完成后汇总为综合报告（AC-004）。使用 CompletableFuture 并发，组间并行、组内串行。
 * </p>
 * <p>
 * P3 断点续执行（AC-017）：ctx 经 attachOrNewContext 挂载（恢复时保留已完成标记）；
 * 组内 Agent 经 executeOrSkip 判定——恢复 key 命中则整组或部分跳过（step_skipped），
 * 历史输出衔接组内链式输入；全跳过分组仍推 group_start/group_complete（前端进度完整）。
 * </p>
 */
@Component
public class ParallelExecutionStrategy extends AbstractExecutionStrategy {

    /** 并行执行线程池：固定 4 线程（与 CPU 核数取小） */
    private final Executor parallelExecutor = Executors.newFixedThreadPool(
            Math.min(4, Runtime.getRuntime().availableProcessors()),
            r -> {
                Thread t = new Thread(r);
                t.setName("workflow-parallel-" + t.getId());
                return t;
            });

    public ParallelExecutionStrategy(AgentExecutor agentExecutor) {
        super(agentExecutor);
    }

    @Override
    public OrchestrationMode supportedMode() {
        return OrchestrationMode.PARALLEL;
    }

    @Override
    public String execute(WorkflowTemplate template, Map<String, Object> params,
                          SseEmitter emitter, WorkflowExecution execution,
                          String modelId, AtomicBoolean cancelFlag) {
        // P3：取已有 ctx（恢复场景保留 done keys）或新建挂载（AC-017）
        WorkflowContext ctx = attachOrNewContext(execution);
        ctx.write("params", params);
        // 业务含义：参数展开到 ctx 顶层，使各分组能读取用户输入
        params.forEach(ctx::write);
        // 业务含义：初始化 lastOutput 为用户输入参数值，使每个分组的首个 Agent 收到实际输入而非空字符串
        initializeLastOutput(ctx, template, params);

        int agentCount = template.getParallelGroups().stream().mapToInt(g -> g.getAgents().size()).sum();
        WorkflowEventPublisher.send(emitter, "workflow_start", Map.of(
                "executionId", execution.getExecutionId(),
                "templateName", template.getName(),
                "mode", "PARALLEL",
                "agentCount", agentCount));

        long workflowStartTime = System.currentTimeMillis();
        List<CompletableFuture<String>> groupFutures = new ArrayList<>();
        AtomicInteger groupIndex = new AtomicInteger(0);

        // 业务含义：各分组首个 Agent 的输入统一取调度前的用户输入快照——
        // 若组内运行时读共享 lastOutput，先完成的分组会覆盖它，导致其他分组首 Agent 误读他组输出（竞态）
        String groupInitialInput = ctx.readAsString("lastOutput");

        // 业务含义：每个分组独立线程执行，组间并行、组内 Agent 串行（AC-004）
        for (ParallelGroup group : template.getParallelGroups()) {
            int gIdx = groupIndex.getAndIncrement();
            groupFutures.add(CompletableFuture.supplyAsync(() ->
                    executeGroup(group, gIdx, groupInitialInput, ctx, emitter, execution,
                            template.getMaxRetries(), modelId, cancelFlag),
                    parallelExecutor));
        }

        // 等待所有分组完成；解包 CompletableFuture 包装的异常（Cancelled/Timeout/Business），交协调层统一处理
        List<String> groupOutputs = new ArrayList<>();
        for (CompletableFuture<String> f : groupFutures) {
            try {
                groupOutputs.add(f.join());
            } catch (java.util.concurrent.CompletionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                throw e;
            }
        }

        String finalResult = summarizeParallel(template, groupOutputs);
        WorkflowEventPublisher.send(emitter, "workflow_complete", Map.of(
                "executionId", execution.getExecutionId(),
                "finalResult", finalResult,
                "mode", "PARALLEL",
                "totalDurationMs", System.currentTimeMillis() - workflowStartTime));

        return finalResult;
    }

    /**
     * 执行单个并行分组（组内 Agent 串行）
     * <p>
     * P3：组内执行点走 executeOrSkip（iteration=0）——恢复 key 命中跳过（step_skipped），
     * 历史输出衔接组内链式输入；组内串联用局部变量，避免并发分组经共享 lastOutput 串台。
     * 全跳过分组仍推 group_start/group_complete，前端进度完整（AC-017）。
     * </p>
     *
     * @param group        并行分组定义
     * @param gIdx         分组索引（事件标识）
     * @param initialInput 分组初始输入（用户输入快照）
     * @param ctx          共享上下文（恢复 key 读写）
     * @param emitter      SSE 发射器
     * @param execution    执行实例（记录步骤）
     * @param maxRetries   最大重试次数
     * @param modelId      模型 ID
     * @param cancelFlag   取消标志
     * @return 分组最后一个 Agent 的输出（执行所得或历史恢复）
     */
    private String executeGroup(ParallelGroup group, int gIdx, String initialInput, WorkflowContext ctx,
                                SseEmitter emitter, WorkflowExecution execution,
                                int maxRetries, String modelId, AtomicBoolean cancelFlag) {
        AgentExecutor.checkCancelled(cancelFlag);
        WorkflowEventPublisher.send(emitter, "group_start", Map.of(
                "groupIndex", gIdx,
                "groupName", group.getName(),
                "agentCount", group.getAgents().size()));

        long gStart = System.currentTimeMillis();
        String input = initialInput;
        String groupOutput = "";
        for (int i = 0; i < group.getAgents().size(); i++) {
            AgentDefinition agentDef = group.getAgents().get(i);
            // P3：恢复 key 命中跳过（step_skipped + 历史输出），否则执行并写恢复 key（AC-017）
            String output = executeOrSkip(agentDef, input, 0, ctx, emitter, execution,
                    maxRetries, modelId, cancelFlag, i);
            input = output;
            groupOutput = output;
        }

        WorkflowEventPublisher.send(emitter, "group_complete", Map.of(
                "groupIndex", gIdx,
                "groupName", group.getName(),
                "durationMs", System.currentTimeMillis() - gStart,
                "outputLength", groupOutput.length()));
        return groupOutput;
    }

    /**
     * 并行汇总：按分组顺序拼接为综合报告
     */
    private String summarizeParallel(WorkflowTemplate template, List<String> groupOutputs) {
        StringBuilder sb = new StringBuilder("【多角度综合审查报告】\n");
        List<ParallelGroup> groups = template.getParallelGroups();
        for (int i = 0; i < groups.size(); i++) {
            sb.append("## ").append(groups.get(i).getName()).append("\n")
              .append(groupOutputs.get(i) == null ? "" : groupOutputs.get(i)).append("\n\n");
        }
        return sb.toString();
    }
}
