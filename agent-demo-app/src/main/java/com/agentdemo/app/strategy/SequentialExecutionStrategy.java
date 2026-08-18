package com.agentdemo.app.strategy;

import com.agentdemo.app.core.AgentDefinition;
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
 * 串行编排策略
 * <p>
 * 业务含义：按顺序遍历模板中的 Agent，前一个 Agent 的输出作为下一个 Agent 的输入（AC-003）。
 * 从 P1 的 WorkflowExecutionService.executeSequential 迁移，改用 AgentExecutor/WorkflowEventPublisher/WorkflowContext。
 * </p>
 * <p>
 * P3 断点续执行：ctx 经 attachOrNewContext 挂载（恢复时复用暂停前状态），
 * 每步经 executeOrSkip 判定——恢复 key 命中则跳过（step_skipped），历史输出衔接链式输入（AC-017）。
 * </p>
 */
@Component
public class SequentialExecutionStrategy extends AbstractExecutionStrategy {

    public SequentialExecutionStrategy(AgentExecutor agentExecutor) {
        super(agentExecutor);
    }

    @Override
    public OrchestrationMode supportedMode() {
        return OrchestrationMode.SEQUENTIAL;
    }

    @Override
    public String execute(WorkflowTemplate template, Map<String, Object> params,
                          SseEmitter emitter, WorkflowExecution execution,
                          String modelId, AtomicBoolean cancelFlag) {
        // P3：取已有 ctx（恢复场景保留已完成标记）或新建挂载（AC-017）
        WorkflowContext ctx = attachOrNewContext(execution);
        ctx.write("params", params);

        WorkflowEventPublisher.send(emitter, "workflow_start", Map.of(
                "executionId", execution.getExecutionId(),
                "templateName", template.getName(),
                "mode", "SEQUENTIAL",
                "agentCount", template.getAgents().size()));

        // 业务含义：串行模式初始输入为 topic 参数，后续 Agent 输入为前一 Agent 输出（AC-003）；
        // 恢复场景下已完成的 Agent 被跳过，其历史输出继续作为链式输入
        Object topic = params.get("topic");
        String currentInput = topic != null ? topic.toString() : "";
        long workflowStartTime = System.currentTimeMillis();

        for (int i = 0; i < template.getAgents().size(); i++) {
            AgentDefinition agentDef = template.getAgents().get(i);
            // P3：恢复 key 命中跳过（step_skipped + 历史输出），否则执行并写恢复 key（AC-017）；
            // 取消检查在 executeOrSkip 内部（跳过判定之前）
            currentInput = executeOrSkip(agentDef, currentInput, 0, ctx, emitter, execution,
                    template.getMaxRetries(), modelId, cancelFlag, i);
        }

        WorkflowEventPublisher.send(emitter, "workflow_complete", Map.of(
                "executionId", execution.getExecutionId(),
                "finalResult", currentInput,
                "mode", "SEQUENTIAL",
                "totalDurationMs", System.currentTimeMillis() - workflowStartTime));

        return currentInput;
    }
}
