package com.agentdemo.app.strategy;

import com.agentdemo.app.core.LoopDefinition;
import com.agentdemo.app.core.OrchestrationMode;
import com.agentdemo.app.core.WorkflowContext;
import com.agentdemo.app.core.WorkflowExecution;
import com.agentdemo.app.core.WorkflowTemplate;
import com.agentdemo.app.execution.AgentExecutor;
import com.agentdemo.app.execution.WorkflowEventPublisher;
import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 循环编排策略
 * <p>
 * 业务含义：执行循环体 Agent，每轮结束后评估 exitCondition；
 * 满足条件或达到 maxIterations 上限则退出（AC-006/AC-029/BR-APP-014）。
 * </p>
 * <p>
 * P3 断点续执行（AC-017）：恢复时按 ctx.iterationCount 轮次重放——
 * 历史完整轮（&lt; 暂停轮）整轮跳过：Agent 全跳过、不评估 exitCondition、不重复递增；
 * 暂停轮（== 暂停轮）轮内 Agent 级跳过（done:{轮次}:{Agent} 命中即跳过）；
 * 新轮（&gt; 暂停轮）正常执行。
 * </p>
 */
@Component
public class LoopExecutionStrategy extends AbstractExecutionStrategy {

    public LoopExecutionStrategy(AgentExecutor agentExecutor) {
        super(agentExecutor);
    }

    @Override
    public OrchestrationMode supportedMode() {
        return OrchestrationMode.LOOP;
    }

    @Override
    public String execute(WorkflowTemplate template, Map<String, Object> params,
                          SseEmitter emitter, WorkflowExecution execution,
                          String modelId, AtomicBoolean cancelFlag) {
        // P3：取已有 ctx（恢复场景含 iterationCount 与 done keys）或新建挂载（AC-017）
        WorkflowContext ctx = attachOrNewContext(execution);
        ctx.write("params", params);
        // 业务含义：参数展开到 ctx 顶层，供退出条件谓词直接读取
        params.forEach(ctx::write);
        // 业务含义：初始化 lastOutput 为用户输入参数值，使首轮循环的首个 Agent 收到实际输入而非空字符串
        initializeLastOutput(ctx, template, params);
        LoopDefinition loop = template.getLoop();

        // 业务含义：循环必须配置最大迭代次数，防止无限循环（BR-APP-014/AC-029）
        if (loop.getMaxIterations() <= 0) {
            throw new BusinessException(ErrorCode.WORKFLOW_PARAM_MISSING,
                    "循环工作流必须配置最大迭代次数");
        }

        // P3：暂停轮次 = 恢复前 ctx 已递增的轮数（轮开始时递增；首次执行为 0，不影响 P2 行为）
        int pausedIteration = ctx.getIterationCount();

        WorkflowEventPublisher.send(emitter, "workflow_start", Map.of(
                "executionId", execution.getExecutionId(),
                "templateName", template.getName(),
                "mode", "LOOP",
                "maxIterations", loop.getMaxIterations()));

        long workflowStartTime = System.currentTimeMillis();
        String finalResult = "";
        boolean maxReached = false;

        // 业务含义：每轮执行循环体后评估退出条件；达标提前退出，否则达上限退出（AC-006/AC-029）
        for (int iteration = 1; iteration <= loop.getMaxIterations(); iteration++) {
            AgentExecutor.checkCancelled(cancelFlag);

            // P3 轮次重放：历史轮与暂停轮在暂停前已递增过 iterationCount，不重复递增；
            // 仅新轮递增，保证恢复后最终 iterationCount 与实际轮次一致（AC-017）
            boolean historicalRound = iteration < pausedIteration;
            boolean pausedRound = iteration == pausedIteration;
            if (!historicalRound && !pausedRound) {
                ctx.incrementIteration();
            }

            // 业务含义：每轮（含恢复重放轮）都推 loop_iteration，保证前端轮次进度完整
            WorkflowEventPublisher.send(emitter, "loop_iteration", Map.of(
                    "iteration", iteration,
                    "maxIterations", loop.getMaxIterations(),
                    "agentCount", loop.getAgents().size()));

            // P3：executeOrSkip 按 done:{iteration}:{Agent} 判定——历史轮 Agent 全跳过、
            // 暂停轮跳过已完成工序，历史输出衔接轮内链式输入
            finalResult = executeAgentList(loop.getAgents(), ctx, emitter, execution,
                    template.getMaxRetries(), modelId, cancelFlag, iteration);

            // P3：历史完整轮不评估 exitCondition——恢复时 ctx 状态可能已满足退出条件，
            // 若评估会误判提前退出，跳过尚未执行的暂停轮（Task-10 核心风险点）
            if (historicalRound) {
                continue;
            }

            if (loop.getExitCondition().test(ctx)) {
                break;
            }
            if (iteration >= loop.getMaxIterations()) {
                maxReached = true; // AC-029：达到最大迭代次数退出
            }
        }

        execution.setIterationCount(ctx.getIterationCount());
        WorkflowEventPublisher.send(emitter, "workflow_complete", Map.of(
                "executionId", execution.getExecutionId(),
                "finalResult", finalResult,
                "mode", "LOOP",
                "iterationCount", ctx.getIterationCount(),
                "exitReason", maxReached ? "达到最大迭代次数退出" : "退出条件满足",
                "totalDurationMs", System.currentTimeMillis() - workflowStartTime));

        return finalResult;
    }
}
