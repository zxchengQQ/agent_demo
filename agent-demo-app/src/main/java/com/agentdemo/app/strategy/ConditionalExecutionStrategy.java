package com.agentdemo.app.strategy;

import com.agentdemo.app.core.BranchDefinition;
import com.agentdemo.app.core.OrchestrationMode;
import com.agentdemo.app.core.WorkflowContext;
import com.agentdemo.app.core.WorkflowExecution;
import com.agentdemo.app.core.WorkflowTemplate;
import com.agentdemo.app.execution.AgentExecutor;
import com.agentdemo.app.execution.WorkflowEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 条件分支编排策略
 * <p>
 * 业务含义：依次评估各分支的 condition 谓词，命中第一个满足条件的分支执行其 Agent 序列（AC-005）。
 * 仅执行匹配分支的 Agent，推送 branch_selected 事件通知前端选中分支。
 * </p>
 * <p>
 * P3 断点续执行：ctx 经 attachOrNewContext 挂载（恢复时复用暂停前状态）；
 * 分支谓词为确定性谓词，基于恢复 ctx 重放的结果与首次执行一致（走原分支）；
 * 分支内 Agent 经 executeOrSkip 判定跳过已完成步骤（AC-017）。
 * </p>
 */
@Component
public class ConditionalExecutionStrategy extends AbstractExecutionStrategy {

    public ConditionalExecutionStrategy(AgentExecutor agentExecutor) {
        super(agentExecutor);
    }

    @Override
    public OrchestrationMode supportedMode() {
        return OrchestrationMode.CONDITIONAL;
    }

    @Override
    public String execute(WorkflowTemplate template, Map<String, Object> params,
                          SseEmitter emitter, WorkflowExecution execution,
                          String modelId, AtomicBoolean cancelFlag) {
        // P3：取已有 ctx（恢复场景保留已完成标记）或新建挂载（AC-017）
        WorkflowContext ctx = attachOrNewContext(execution);
        ctx.write("params", params);
        // 业务含义：参数展开到 ctx 顶层，供分支条件谓词直接读取（如 ctx.readAsString("question")）
        params.forEach(ctx::write);
        // 业务含义：初始化 lastOutput 为用户输入参数值，使首个 Agent 收到实际输入而非空字符串
        initializeLastOutput(ctx, template, params);

        WorkflowEventPublisher.send(emitter, "workflow_start", Map.of(
                "executionId", execution.getExecutionId(),
                "templateName", template.getName(),
                "mode", "CONDITIONAL",
                "agentCount", template.getBranches().size()));

        long workflowStartTime = System.currentTimeMillis();
        String finalResult = "";

        // 业务含义：命中第一个条件为 true 的分支，仅执行该分支的 Agent 序列（AC-005）
        for (int b = 0; b < template.getBranches().size(); b++) {
            AgentExecutor.checkCancelled(cancelFlag);
            BranchDefinition branch = template.getBranches().get(b);
            if (branch.getCondition().test(ctx)) {
                WorkflowEventPublisher.send(emitter, "branch_selected", Map.of(
                        "branchIndex", b,
                        "branchName", branch.getName(),
                        "agentCount", branch.getAgents().size()));
                finalResult = executeAgentList(branch.getAgents(), ctx, emitter, execution,
                        template.getMaxRetries(), modelId, cancelFlag, 0);
                break;
            }
        }

        WorkflowEventPublisher.send(emitter, "workflow_complete", Map.of(
                "executionId", execution.getExecutionId(),
                "finalResult", finalResult,
                "mode", "CONDITIONAL",
                "totalDurationMs", System.currentTimeMillis() - workflowStartTime));

        return finalResult;
    }
}
