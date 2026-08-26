package com.agentdemo.app.strategy;

import com.agentdemo.app.core.AgentDefinition;
import com.agentdemo.app.core.OrchestrationMode;
import com.agentdemo.app.core.StepExecution;
import com.agentdemo.app.core.StepStatus;
import com.agentdemo.app.core.Subtask;
import com.agentdemo.app.core.SupervisorDefinition;
import com.agentdemo.app.core.WorkflowContext;
import com.agentdemo.app.core.WorkflowExecution;
import com.agentdemo.app.core.WorkflowTemplate;
import com.agentdemo.app.execution.AgentExecutor;
import com.agentdemo.app.execution.WorkflowEventPublisher;
import com.agentdemo.app.service.WorkflowHITLState;
import com.agentdemo.app.service.WorkflowPausedException;
import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Supervisor 层级编排策略（P3 Task-14/15，AC-007/AC-031）
 * <p>
 * 业务含义：主控 Agent 拆解任务为子任务 JSON -> 按 agent 字段三级路由 Worker 池 ->
 * 依次执行子任务（串行调度）-> 主控汇总为综合报告。
 * 主控/Worker 模型独立配置（AC-031，BR-APP-012）。
 * </p>
 * <p>
 * 断点续执行 key 约定：拆解结果存 "supervisor:plan"（List&lt;Subtask&gt;，恢复时不重新拆解）；
 * 子任务完成存 "subtask:{i}"（1-based 列表序号，恢复时跳过）；汇总不写 key（失败暂停后恢复时重跑）。
 * </p>
 */
@Component
public class SupervisorExecutionStrategy extends AbstractExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(SupervisorExecutionStrategy.class);

    /** 默认子任务上限（模板未配置时兜底） */
    private static final int DEFAULT_MAX_SUBTASKS = 5;

    public SupervisorExecutionStrategy(AgentExecutor agentExecutor) {
        super(agentExecutor);
    }

    @Override
    public OrchestrationMode supportedMode() {
        return OrchestrationMode.SUPERVISOR;
    }

    /**
     * 路由结果：worker 为路由目标，routed=true 表示精确/包含命中（主控指定的 Worker），
     * routed=false 表示兜底（主控点名无法对号，派第一个 Worker 顶上，前端可标注兜底标记）
     */
    record RoutingResult(AgentDefinition worker, boolean routed) {
    }

    /**
     * Worker 三级路由（Task-14，AC-007）
     * <p>
     * 业务含义：主控输出的 agent 名是 LLM 自由文本（"研究"/"研究 Agent"等变体），
     * 三级匹配保证子任务总能落到具体 Worker：① 精确匹配 ② 包含匹配（双向）
     * ③ 兜底第一个 Worker（log.warn，绝不撂挑子）。
     * </p>
     */
    static RoutingResult routeWorker(List<AgentDefinition> workers, String agentName) {
        if (agentName != null && !agentName.isBlank()) {
            for (AgentDefinition w : workers) {
                if (w.getName().equals(agentName)) {
                    return new RoutingResult(w, true);
                }
            }
            for (AgentDefinition w : workers) {
                if (w.getName().contains(agentName) || agentName.contains(w.getName())) {
                    return new RoutingResult(w, true);
                }
            }
        }
        log.warn("子任务路由兜底: 主控指定={}, 实际路由={}", agentName, workers.get(0).getName());
        return new RoutingResult(workers.get(0), false);
    }

    @Override
    public String execute(WorkflowTemplate template, Map<String, Object> params,
                          SseEmitter emitter, WorkflowExecution execution,
                          String modelId, AtomicBoolean cancelFlag) {
        SupervisorDefinition sup = template.getSupervisor();
        validateSupervisor(sup);

        WorkflowContext ctx = attachOrNewContext(execution);
        int maxSubtasks = sup.getMaxSubtasks() > 0 ? sup.getMaxSubtasks() : DEFAULT_MAX_SUBTASKS;
        Object taskObj = params.get("task");
        String task = taskObj != null ? taskObj.toString() : "";
        long startTime = System.currentTimeMillis();

        WorkflowEventPublisher.send(emitter, "workflow_start", Map.of(
                "executionId", execution.getExecutionId(),
                "templateName", template.getName(),
                "mode", "SUPERVISOR",
                "maxSubtasks", maxSubtasks));

        // ===== 阶段一：主控拆解（agentIndex=0）。恢复时 ctx 有 supervisor:plan 则跳过 =====
        List<Subtask> subtasks = planOrRestore(sup, template, ctx, task, emitter, execution, modelId,
                maxSubtasks, cancelFlag);

        // supervisor_plan 事件（前端渲染子任务卡片，含兜底标记）
        List<Map<String, Object>> subtaskItems = new ArrayList<>();
        for (Subtask st : subtasks) {
            RoutingResult routing = routeWorker(sup.getWorkers(), st.getAgent());
            Map<String, Object> item = new HashMap<>();
            item.put("id", st.getId());
            item.put("description", st.getDescription() != null ? st.getDescription() : "");
            item.put("agent", st.getAgent() != null ? st.getAgent() : "");
            item.put("routedAgent", routing.worker().getName());
            item.put("routed", routing.routed());
            subtaskItems.add(item);
        }
        WorkflowEventPublisher.send(emitter, "supervisor_plan", Map.of(
                "subtasks", subtaskItems,
                "totalSubtasks", subtasks.size()));

        // ===== 阶段二：依次调度子任务（agentIndex=1..N）。已完成子任务跳过（断点恢复）=====
        StringBuilder results = new StringBuilder();
        for (int i = 0; i < subtasks.size(); i++) {
            AgentExecutor.checkCancelled(cancelFlag);
            Subtask st = subtasks.get(i);
            RoutingResult routing = routeWorker(sup.getWorkers(), st.getAgent());
            int agentIndex = i + 1;

            WorkflowEventPublisher.send(emitter, "supervisor_dispatch", Map.of(
                    "subtaskIndex", agentIndex,
                    "totalSubtasks", subtasks.size(),
                    "description", st.getDescription() != null ? st.getDescription() : "",
                    "agentName", routing.worker().getName(),
                    "routed", routing.routed()));

            // 恢复判定：subtask:{i}（1-based 列表序号）有值 = 已完成，跳过并衔接历史输出
            Object saved = ctx.read("subtask:" + agentIndex);
            String output;
            if (saved != null) {
                log.info("断点恢复跳过已完成子任务: subtaskIndex={}, agent={}", agentIndex, routing.worker().getName());
                WorkflowEventPublisher.send(emitter, "step_skipped", Map.of(
                        "agentIndex", agentIndex,
                        "agentName", routing.worker().getName(),
                        "reason", "断点恢复"));
                output = saved.toString();
            } else {
                // 业务含义：Worker 输入携带原始任务上下文 + 明确的子任务职责，避免 Worker 脱离全局目标
                String workerInput = "原始任务：\n" + task + "\n\n你负责的子任务：\n" + st.getDescription();
                // 业务含义：HITL 暂停步恢复（Task-08）——该 worker 是用户已确认的检查点暂停步
                // （hitl key 命中），以恢复方式执行（跳过注解检测）而非重新触发 HITL 死循环（AC-N02/AC-S01）
                WorkflowHITLState.HitlResume hitlResume = readHitlResume(ctx, routing.worker().getName());
                if (hitlResume != null) {
                    output = resumePausedStep(hitlResume, routing.worker(), 0, ctx, emitter, execution, agentIndex);
                } else {
                    output = runStep(routing.worker(), workerInput, emitter, execution, agentIndex,
                            template.getMaxRetries(), modelId);
                }
                ctx.write("subtask:" + agentIndex, output);
            }
            results.append("## 子任务 ").append(agentIndex).append("（")
                    .append(st.getDescription() != null ? st.getDescription() : "").append("）\n")
                    .append(output).append("\n\n");
        }

        // ===== 阶段三：主控汇总（agentIndex=N+1，无恢复 key：失败暂停后恢复时重跑汇总）=====
        WorkflowEventPublisher.send(emitter, "supervisor_summary", Map.of(
                "subtaskCount", subtasks.size()));
        String finalResult = runStep(sup.getSummarizeAgent(), results.toString(), emitter, execution,
                subtasks.size() + 1, template.getMaxRetries(), modelId);

        WorkflowEventPublisher.send(emitter, "workflow_complete", Map.of(
                "executionId", execution.getExecutionId(),
                "finalResult", finalResult,
                "mode", "SUPERVISOR",
                "subtaskCount", subtasks.size(),
                "totalDurationMs", System.currentTimeMillis() - startTime));
        return finalResult;
    }

    /**
     * 校验 Supervisor 定义完整性（配置类失败，FAILED 终态而非暂停）
     */
    private void validateSupervisor(SupervisorDefinition sup) {
        if (sup == null || sup.getPlanAgent() == null || sup.getSummarizeAgent() == null
                || sup.getWorkers() == null || sup.getWorkers().isEmpty()) {
            throw new BusinessException(ErrorCode.WORKFLOW_EXECUTION_FAILED,
                    "SUPERVISOR 模板配置不完整：未配置 Worker 池或主控 Agent");
        }
    }

    /**
     * 主控拆解（含解析重试）或恢复跳过
     * <p>
     * 业务含义："调用 LLM + 解析 JSON" 是一个可重试单元——解析失败（LLM 输出格式漂移）
     * 时重新调用 LLM（最多 maxRetries 次），给模型自我纠正机会；重试耗尽抛
     * WorkflowPausedException 走暂停-恢复流程（用户可恢复重试）。恢复场景 ctx 已有
     * supervisor:plan，直接复用拆解结果（省一次强模型调用）。
     * </p>
     */
    @SuppressWarnings("unchecked")
    private List<Subtask> planOrRestore(SupervisorDefinition sup, WorkflowTemplate template,
                                        WorkflowContext ctx, String task, SseEmitter emitter,
                                        WorkflowExecution execution, String modelId,
                                        int maxSubtasks, AtomicBoolean cancelFlag) {
        Object saved = ctx.read("supervisor:plan");
        if (saved instanceof List<?> list) {
            // 恢复：拆解已完成，不重新调用主控
            WorkflowEventPublisher.send(emitter, "step_skipped", Map.of(
                    "agentIndex", 0,
                    "agentName", sup.getPlanAgent().getName(),
                    "reason", "断点恢复"));
            return (List<Subtask>) list;
        }

        StepExecution step = new StepExecution(sup.getPlanAgent().getName(), 0, StepStatus.RUNNING);
        execution.getSteps().add(step);
        WorkflowEventPublisher.send(emitter, "step_start", Map.of(
                "agentIndex", 0,
                "agentName", sup.getPlanAgent().getName(),
                "iteration", 0));

        BusinessException lastParseError = null;
        for (int attempt = 0; attempt <= template.getMaxRetries(); attempt++) {
            AgentExecutor.checkCancelled(cancelFlag);
            String planOutput = agentExecutor.executeWithRetry(sup.getPlanAgent(), task,
                    emitter, 0, template.getMaxRetries(), modelId, 0, execution.getExecutionId());
            try {
                List<Subtask> subtasks = SubtaskParser.parse(planOutput, maxSubtasks);
                step.complete(planOutput);
                ctx.write("supervisor:plan", subtasks);
                WorkflowEventPublisher.send(emitter, "step_complete", Map.of(
                        "agentIndex", 0,
                        "agentName", sup.getPlanAgent().getName(),
                        "iteration", 0,
                        "durationMs", step.getDurationMs(),
                        "outputLength", planOutput.length()));
                return subtasks;
            } catch (BusinessException e) {
                lastParseError = e;
                log.warn("主控拆解输出解析失败（第 {} 次）: {}", attempt + 1, e.getMessage());
            }
        }
        // 解析重试耗尽：按暂停处理（可恢复），与 Agent 执行失败语义一致
        throw new WorkflowPausedException(sup.getPlanAgent().getName(), 0,
                "主控拆解输出无法解析为子任务列表（已重试 " + (template.getMaxRetries() + 1) + " 次）: "
                        + (lastParseError != null ? lastParseError.getMessage() : ""), lastParseError);
    }

    /**
     * 执行单个步骤（step_start/step_complete 事件 + StepExecution 记录）
     * <p>
     * 业务含义：Supervisor 的 plan/子任务/汇总步骤恢复 key 与基类 done:{iteration}:{name}
     * 规则不同（supervisor:plan / subtask:{i} / 无 key），故不复用 executeOrSkip，
     * 仅复用事件协议与步骤记录（token/step_retry/step_error 由 AgentExecutor 推送）。
     * </p>
     */
    private String runStep(AgentDefinition agentDef, String input, SseEmitter emitter,
                           WorkflowExecution execution, int agentIndex, int maxRetries, String modelId) {
        StepExecution step = new StepExecution(agentDef.getName(), agentIndex, StepStatus.RUNNING);
        execution.getSteps().add(step);
        WorkflowEventPublisher.send(emitter, "step_start", Map.of(
                "agentIndex", agentIndex,
                "agentName", agentDef.getName(),
                "iteration", 0));

        String output = agentExecutor.executeWithRetry(agentDef, input, emitter, agentIndex,
                maxRetries, modelId, 0, execution.getExecutionId());

        step.complete(output);
        ctxRecordOutput(execution, agentDef.getName(), output);
        WorkflowEventPublisher.send(emitter, "step_complete", Map.of(
                "agentIndex", agentIndex,
                "agentName", agentDef.getName(),
                "iteration", 0,
                "durationMs", step.getDurationMs(),
                "outputLength", output.length()));
        return output;
    }

    private void ctxRecordOutput(WorkflowExecution execution, String agentName, String output) {
        if (execution.getContext() != null) {
            execution.getContext().recordOutput(agentName, output);
        }
    }

    /**
     * 读取 Worker 的 HITL 恢复上下文（supervisor 的 iteration 固定 0）
     * <p>
     * 业务含义：命中说明该 worker 是 HITL 暂停步（用户已回复/确认），重放时以恢复方式执行；
     * 未命中返回 null，走正常执行路径（零回归）。
     * </p>
     */
    private WorkflowHITLState.HitlResume readHitlResume(WorkflowContext ctx, String agentName) {
        Object resume = ctx.read(hitlResumeKey(0, agentName));
        return resume instanceof WorkflowHITLState.HitlResume hitlResume ? hitlResume : null;
    }
}
