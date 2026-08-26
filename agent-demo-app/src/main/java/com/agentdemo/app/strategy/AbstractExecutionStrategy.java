package com.agentdemo.app.strategy;

import com.agentdemo.app.core.AgentDefinition;
import com.agentdemo.app.core.ParameterDefinition;
import com.agentdemo.app.core.StepExecution;
import com.agentdemo.app.core.StepStatus;
import com.agentdemo.app.core.WorkflowContext;
import com.agentdemo.app.core.WorkflowExecution;
import com.agentdemo.app.core.WorkflowTemplate;
import com.agentdemo.app.execution.AgentExecutor;
import com.agentdemo.app.execution.WorkflowEventPublisher;
import com.agentdemo.app.service.WorkflowHITLState;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 策略基类
 * <p>
 * 业务含义：抽取条件/循环策略共用的"顺序执行 Agent 列表"逻辑，避免代码重复。
 * 串行策略的初始输入为 params.topic，与本方法（读取 ctx.lastOutput）不同，暂不强行统一。
 * </p>
 */
public abstract class AbstractExecutionStrategy implements WorkflowExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(AbstractExecutionStrategy.class);

    protected final AgentExecutor agentExecutor;

    protected AbstractExecutionStrategy(AgentExecutor agentExecutor) {
        this.agentExecutor = agentExecutor;
    }

    /**
     * 从模板参数定义中提取首个 required string 参数的值，写入 ctx 的 "lastOutput" 键。
     * <p>
     * 业务含义：条件分支/循环/并行策略的 executeAgentList/executeGroup 读取 ctx.readAsString("lastOutput")
     * 作为首个 Agent 的输入。若不初始化，lastOutput 为空字符串，导致 Agent 收到空输入，
     * LLM 回复"请提供..."而非执行实际任务。
     * </p>
     *
     * @param ctx      工作流上下文
     * @param template 工作流模板（提供参数定义）
     * @param params   用户传入的参数
     */
    public static void initializeLastOutput(WorkflowContext ctx, WorkflowTemplate template,
                                                Map<String, Object> params) {
        if (template.getParameters() == null || params == null) {
            return;
        }
        template.getParameters().stream()
                .filter(p -> p.isRequired() && "string".equals(p.getType()))
                .findFirst()
                .map(p -> params.get(p.getName()))
                .filter(v -> v != null && !v.toString().isEmpty())
                .ifPresent(v -> ctx.write("lastOutput", v.toString()));
    }

    /**
     * 顺序执行 Agent 列表（供条件分支/循环体复用）
     * <p>
     * 业务含义：逐个执行 Agent，前一个输出作为下一个输入，结果写入共享 WorkflowContext。
     * 条件分支传 iteration=0，循环体传当前迭代轮次。
     * P3 断点续执行：执行点统一走 executeOrSkip——恢复 key 命中跳过（step_skipped），
     * 历史输出衔接链式输入；首次执行行为不变（AC-017）。
     * </p>
     *
     * @param agents       Agent 列表
     * @param ctx          共享上下文（初始输入读 lastOutput，输出写入 lastOutput）
     * @param emitter      SSE 发射器
     * @param execution    执行实例（记录步骤）
     * @param maxRetries   最大重试次数
     * @param modelId      模型 ID
     * @param cancelFlag   取消标志
     * @param iteration    当前迭代轮次（条件分支 0，循环体 1..N）
     * @return 最后一个 Agent 的输出
     */
    protected String executeAgentList(List<AgentDefinition> agents, WorkflowContext ctx,
                                      SseEmitter emitter, WorkflowExecution execution,
                                      int maxRetries, String modelId,
                                      AtomicBoolean cancelFlag, int iteration) {
        String currentInput = ctx.readAsString("lastOutput");
        for (int i = 0; i < agents.size(); i++) {
            currentInput = executeOrSkip(agents.get(i), currentInput, iteration, ctx, emitter,
                    execution, maxRetries, modelId, cancelFlag, i);
        }
        return currentInput;
    }

    // ===== P3 新增：断点续执行恢复基础设施（AC-017）=====

    /**
     * 取已有执行上下文或新建并挂载到 execution
     * <p>
     * 业务含义：首次执行时 execution 无 context（新建挂载）；恢复执行时策略重放
     * 直接取暂停前挂载的 ctx（保留已完成步骤标记），保证"跳过已完成"判定有据可查。
     * </p>
     */
    protected WorkflowContext attachOrNewContext(WorkflowExecution execution) {
        WorkflowContext ctx = execution.getContext();
        if (ctx == null) {
            ctx = new WorkflowContext();
            execution.attachContext(ctx);
        }
        return ctx;
    }

    /**
     * 统一恢复 key 规则："done:{iteration}:{agentName}"
     * <p>
     * 业务含义：SEQUENTIAL/CONDITIONAL/PARALLEL 用 iteration=0（agent 名模板内唯一），
     * LOOP 用当前轮次（同名 Agent 每轮各一个 key），SUPERVISOR 另用 supervisor:plan / subtask:{i}。
     * </p>
     */
    public static String resumeKey(int iteration, String agentName) {
        return "done:" + iteration + ":" + agentName;
    }

    /**
     * HITL 恢复 key 规则："hitl:{iteration}:{agentName}"（Task-08）
     * <p>
     * 业务含义：协调层 hitlReply 在用户回复后将该 key 写入 ctx，标记"此步是 HITL 暂停步"。
     * 策略重放时 done key 未命中但 hitl key 命中——说明该步已由用户回复/确认恢复，
     * 以恢复方式执行（而非重新触发 HITL 死循环，技术方案 Sec 11 风险缓解）。
     * </p>
     */
    public static String hitlResumeKey(int iteration, String agentName) {
        return "hitl:" + iteration + ":" + agentName;
    }

    /**
     * 执行或跳过单个 Agent（断点续执行核心，AC-017）
     * <p>
     * 业务含义：确定性重放——查恢复 key 命中（值非 null，空串也算完成）则跳过真实执行，
     * 推 step_skipped 事件并返回历史输出（作为链式输入）；未命中则正常执行并写恢复 key。
     * 取消检查置于跳过判定之前：恢复流中用户终止立即生效。
     * </p>
     *
     * @param agentDef   Agent 定义
     * @param input      输入文本（恢复场景下若前序跳过则为历史输出）
     * @param iteration  迭代轮次（非循环模式固定 0）
     * @param ctx        工作流上下文（恢复 key 读写）
     * @param emitter    SSE 发射器
     * @param execution  执行实例（记录步骤）
     * @param maxRetries 最大重试次数
     * @param modelId    模型 ID
     * @param cancelFlag 取消标志
     * @param agentIndex Agent 索引（事件标识）
     * @return Agent 输出（执行所得或历史恢复）
     */
    protected String executeOrSkip(AgentDefinition agentDef, String input, int iteration,
                                   WorkflowContext ctx, SseEmitter emitter,
                                   WorkflowExecution execution, int maxRetries,
                                   String modelId, AtomicBoolean cancelFlag, int agentIndex) {
        AgentExecutor.checkCancelled(cancelFlag);

        String key = resumeKey(iteration, agentDef.getName());
        Object saved = ctx.read(key);
        if (saved != null) {
            // 已完成：跳过（不调 LLM，省时省钱），历史输出作为下一 Agent 输入
            log.info("断点恢复跳过已完成步骤: key={}, agent={}", key, agentDef.getName());
            WorkflowEventPublisher.send(emitter, "step_skipped", Map.of(
                    "agentIndex", agentIndex,
                    "agentName", agentDef.getName(),
                    "reason", "断点恢复"));
            // 业务含义：跳过分支也须同步 lastOutput——循环模式的退出条件谓词在轮末读
            // lastOutput 判定，若暂停轮最后 Agent 被跳过而不更新，谓词会误读参数值导致提前退出
            ctx.write("lastOutput", saved.toString());
            return saved.toString();
        }

        // 业务含义：HITL 暂停步恢复（Task-08）——done key 未命中但 hitl key 命中，
        // 说明该步是用户已回复/确认的 HITL 暂停步，以恢复方式执行而非重新触发 HITL 死循环
        Object hitlResume = ctx.read(hitlResumeKey(iteration, agentDef.getName()));
        if (hitlResume instanceof WorkflowHITLState.HitlResume resume) {
            String output = resumePausedStep(resume, agentDef, iteration, ctx, emitter, execution, agentIndex);
            // 恢复完成写 done key（后续重放判定跳过）
            ctx.write(key, output);
            return output;
        }

        StepExecution step = new StepExecution(agentDef.getName(), agentIndex, StepStatus.RUNNING);
        execution.getSteps().add(step);
        WorkflowEventPublisher.send(emitter, "step_start", Map.of(
                "agentIndex", agentIndex,
                "agentName", agentDef.getName(),
                "iteration", iteration));

        String output = agentExecutor.executeWithRetry(agentDef, input, emitter, agentIndex,
                maxRetries, modelId, iteration, execution.getExecutionId());

        step.complete(output);
        // 业务含义：写恢复 key（AC-021 空输出也写——空串非 null，恢复时同样跳过）
        ctx.write(key, output);
        ctx.recordOutput(agentDef.getName(), output);
        ctx.write("lastOutput", output);

        WorkflowEventPublisher.send(emitter, "step_complete", Map.of(
                "agentIndex", agentIndex,
                "agentName", agentDef.getName(),
                "iteration", iteration,
                "durationMs", step.getDurationMs(),
                "outputLength", output.length()));
        return output;
    }

    /**
     * 以恢复方式执行 HITL 暂停步（Task-08）
     * <p>
     * 业务含义：用户回复/确认后重放策略时，暂停步不再重新触发 HITL，而是通过
     * AgentExecutor.executeHitlResume 恢复执行（askUser：注入回复续跑 ReAct；
     * checkpoint：确认后执行方法）。事件协议与正常执行一致（step_start/step_complete），
     * 完成后清除 hitl 恢复 key 并同步 lastOutput（AC-N03/AC-S01/AC-M01）。
     * </p>
     */
    protected String resumePausedStep(WorkflowHITLState.HitlResume resume, AgentDefinition agentDef,
                                    int iteration, WorkflowContext ctx, SseEmitter emitter,
                                    WorkflowExecution execution, int agentIndex) {
        StepExecution step = new StepExecution(agentDef.getName(), agentIndex, StepStatus.RUNNING);
        execution.getSteps().add(step);
        WorkflowEventPublisher.send(emitter, "step_start", Map.of(
                "agentIndex", agentIndex,
                "agentName", agentDef.getName(),
                "iteration", iteration));

        String output = agentExecutor.executeHitlResume(agentDef, resume.getHitlState(),
                resume.getMessage(), resume.getApproved(), emitter, agentIndex,
                execution.getExecutionId());

        step.complete(output);
        // 业务含义：清除 hitl 恢复 key（恢复已完成，避免后续重放再次命中）；
        // lastOutput 同步——循环模式退出谓词在轮末读取 lastOutput 判定
        ctx.getState().remove(hitlResumeKey(iteration, agentDef.getName()));
        ctx.recordOutput(agentDef.getName(), output);
        ctx.write("lastOutput", output);

        WorkflowEventPublisher.send(emitter, "step_complete", Map.of(
                "agentIndex", agentIndex,
                "agentName", agentDef.getName(),
                "iteration", iteration,
                "durationMs", step.getDurationMs(),
                "outputLength", output.length()));
        return output;
    }
}
