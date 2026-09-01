package com.agentdemo.app.service;

import com.agentdemo.app.core.OrchestrationMode;
import com.agentdemo.app.core.WorkflowContext;
import com.agentdemo.app.core.WorkflowExecution;
import com.agentdemo.app.core.WorkflowExecutionStatus;
import com.agentdemo.app.core.WorkflowTemplate;
import com.agentdemo.app.execution.WorkflowEventPublisher;
import com.agentdemo.app.strategy.AbstractExecutionStrategy;
import com.agentdemo.app.strategy.WorkflowExecutionStrategy;
import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.observability.NoopTraceCollector;
import com.agentdemo.observability.TraceCollector;
import com.agentdemo.observability.TraceContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 工作流执行协调层（P2 重构为薄协调层）
 * <p>
 * 业务含义：负责执行实例的创建/注册/终止/历史查询，按模板 mode 分发到对应执行策略，
 * 统一处理异常（终止/超时/失败）。不包含具体编排算法（已迁移到策略层）。
 * </p>
 * <p>
 * 架构：策略模式三层分离 —— 协调层（本类）+ 策略层（WorkflowExecutionStrategy 实现）+
 * 基础设施（AgentExecutor / WorkflowEventPublisher / WorkflowContext）。
 * 新增编排模式只需新增 @Component 策略类，本类零修改（OCP）。
 * </p>
 */
@Service
public class WorkflowExecutionService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowExecutionService.class);

    /** 策略注册中心：mode -> strategy（Spring 自动注入所有 @Component 策略） */
    private final Map<OrchestrationMode, WorkflowExecutionStrategy> strategies;

    /** 追踪采集器（CR-001 Task-19：工作流根 span） */
    private final TraceCollector traceCollector;

    /** 执行实例存储（内存，BR-APP-008） */
    private final ConcurrentHashMap<String, WorkflowExecution> executions = new ConcurrentHashMap<>();

    /** 执行取消标志（按 executionId 隔离，保证并发执行独立） */
    private final ConcurrentHashMap<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    /**
     * 可恢复执行快照（executionId -> 暂停时的执行参数，P3 新增，AC-017）
     * 业务含义：Agent 重试耗尽暂停时保存原 template/params/modelId，resume 时据此重放；
     * 恢复成功/终止/失败等终态时清理，避免泄漏。
     */
    private final ConcurrentHashMap<String, ResumableExecutionState> resumableStates = new ConcurrentHashMap<>();

    /** 默认 HITL 会话超时（30 分钟，AC-E01，与技术方案 Sec 8.3 一致） */
    private static final long DEFAULT_HITL_TIMEOUT_MS = 30 * 60 * 1000L;

    /**
     * 兼容构造（测试场景：追踪走 Noop）
     *
     * @param strategyList 所有策略实现
     */
    public WorkflowExecutionService(List<WorkflowExecutionStrategy> strategyList) {
        this(strategyList, new NoopTraceCollector());
    }

    /**
     * Spring 自动注入所有策略实现，构建分发表 + 注入追踪采集器（CR-001 Task-19）
     * 新增模式只需新增 @Component 策略类，此处零修改（OCP）
     *
     * @param strategyList   所有策略实现
     * @param traceCollector 追踪采集器（工作流根 span）
     */
    @org.springframework.beans.factory.annotation.Autowired
    public WorkflowExecutionService(List<WorkflowExecutionStrategy> strategyList, TraceCollector traceCollector) {
        this.strategies = strategyList.stream()
                .collect(Collectors.toMap(WorkflowExecutionStrategy::supportedMode, s -> s));
        this.traceCollector = traceCollector != null ? traceCollector : new NoopTraceCollector();
    }

    /**
     * 执行工作流（异步，通过 SSE 推送事件）
     * <p>
     * 业务含义：创建执行实例 -> 查找策略 -> 异步执行 -> 统一异常处理。
     * 每次执行独立 ID + 独立状态 + 独立线程（AC-020 并发执行）。
     * </p>
     *
     * @param template   工作流模板
     * @param parameters 执行参数（如 topic）
     * @param emitter    SSE 发射器
     * @param modelId    模型 ID（null 使用默认模型）
     * @return 执行实例 ID
     * @throws BusinessException 模板 mode 无对应策略时抛 WORKFLOW_MODE_NOT_SUPPORTED
     */
    public String execute(WorkflowTemplate template, Map<String, Object> parameters,
                          SseEmitter emitter, String modelId) {
        String executionId = generateExecutionId();
        WorkflowExecution execution = new WorkflowExecution(executionId, template.getId(), template.getName());
        execution.setMode(template.getMode()); // 记录编排模式（AC-027 历史展示）
        execution.start();
        executions.put(executionId, execution);
        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        cancelFlags.put(executionId, cancelFlag);

        // 查找策略（同步失败：请求无法执行时立即返回错误）
        WorkflowExecutionStrategy strategy = strategies.get(template.getMode());
        if (strategy == null) {
            throw new BusinessException(ErrorCode.WORKFLOW_MODE_NOT_SUPPORTED,
                    "不支持的编排模式: " + template.getMode());
        }

        log.info("创建工作流执行: executionId={}, templateId={}, mode={}",
                executionId, template.getId(), template.getMode());

        // 异步执行（独立线程，不阻塞请求线程）
        runAsyncWithTrace(executionId, () -> {
            try {
                String result = strategy.execute(template, parameters, emitter,
                        execution, modelId, cancelFlag);
                execution.complete(result);
                recordWorkflowTerminal(execution, "COMPLETED");
                emitter.complete();
            } catch (WorkflowHITLException e) {
                // 业务含义：HITL 暂停信号（checkpoint/askUser）——进入 WAITING_USER（可恢复，AC-N01/N02）。
                // 必须置于 WorkflowPausedException 之前捕获（WorkflowHITLException 是其子类，Task-07）
                handleHITLPaused(emitter, execution, e, template, parameters, modelId);
            } catch (WorkflowPausedException e) {
                // P3：重试耗尽进入 PAUSED（可恢复）而非 FAILED 终态（AC-016）。
                // 必须置于 BusinessException 之前捕获（WorkflowPausedException 是其子类）
                handlePaused(emitter, execution, e, template, parameters, modelId);
            } catch (WorkflowCancelledException e) {
                handleTerminated(emitter, execution, e.getMessage());
            } catch (WorkflowTimeoutException e) {
                handleTimeout(emitter, execution, e.getMessage());
            } catch (BusinessException e) {
                // 业务含义：记录完整堆栈便于定位底层根因（如模型未配置/LLM 调用失败）
                log.error("工作流执行失败: executionId={}, error={}", executionId, e.getMessage(), e);
                handleFailure(emitter, execution, e.getMessage());
            } catch (Exception e) {
                log.error("工作流执行异常: executionId={}", executionId, e);
                handleFailure(emitter, execution, e.getMessage());
            }
        });

        // 注册 emitter 生命周期回调：客户端断开/超时时取消执行，避免异步线程继续向已 complete 的 emitter 发送事件
        emitter.onTimeout(() -> cancel(executionId));
        emitter.onError(e -> cancel(executionId));

        return executionId;
    }

    /**
     * 查询执行实例
     *
     * @param executionId 执行 ID
     * @return 执行实例（不存在返回 null）
     */
    public WorkflowExecution getExecution(String executionId) {
        return executions.get(executionId);
    }

    /**
     * 获取所有执行实例（按开始时间倒序），供执行历史列表展示（AC-027）
     *
     * @return 执行实例列表
     */
    public List<WorkflowExecution> listExecutions() {
        return executions.values().stream()
                .sorted(Comparator.comparing(WorkflowExecution::getStartTime,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
    }

    /**
     * 终止执行
     * <p>
     * 业务含义：用户手动终止工作流（AC-022），设置取消标志，
     * 执行线程在下一个 Agent 步骤前检查并停止。
     * 已结束（完成/失败/终止/超时）的执行不可重复终止（AC-022）。
     * P3：PAUSED 状态允许终止（终态化），同时清理恢复快照。
     * </p>
     *
     * @param executionId 执行 ID
     * @throws BusinessException 执行不存在（WORKFLOW_NOT_FOUND）或已终止（WORKFLOW_ALREADY_TERMINATED）
     */
    public void terminate(String executionId) {
        WorkflowExecution execution = executions.get(executionId);
        if (execution == null) {
            throw new BusinessException(ErrorCode.WORKFLOW_NOT_FOUND, executionId);
        }
        WorkflowExecutionStatus status = execution.getStatus();
        if (status == WorkflowExecutionStatus.COMPLETED
                || status == WorkflowExecutionStatus.FAILED
                || status == WorkflowExecutionStatus.TERMINATED
                || status == WorkflowExecutionStatus.TIMEOUT) {
            throw new BusinessException(ErrorCode.WORKFLOW_ALREADY_TERMINATED, executionId);
        }
        AtomicBoolean flag = cancelFlags.get(executionId);
        if (flag != null) {
            flag.set(true);
        }
        execution.terminate();
        // P3：终态化时清理恢复快照（PAUSED 终止后不可再 resume，AC-022）
        resumableStates.remove(executionId);
        log.info("终止工作流执行: executionId={}", executionId);
    }

    /**
     * 取消执行（emitter 超时/断开时调用）
     *
     * @param executionId 执行 ID
     */
    private void cancel(String executionId) {
        AtomicBoolean flag = cancelFlags.get(executionId);
        if (flag != null) {
            flag.set(true);
        }
    }

    /**
     * 处理用户终止（AC-022）：推送 workflow_failed(status=TERMINATED)
     */
    private void handleTerminated(SseEmitter emitter, WorkflowExecution execution, String message) {
        log.warn("工作流被终止: executionId={}, reason={}", execution.getExecutionId(), message);
        execution.terminate();
        recordWorkflowTerminal(execution, "TERMINATED");
        com.agentdemo.app.execution.WorkflowEventPublisher.send(emitter, "workflow_failed", Map.of(
                "executionId", execution.getExecutionId(),
                "status", WorkflowExecutionStatus.TERMINATED.name(),
                "error", message != null ? message : "用户主动终止"));
        emitter.complete();
    }

    /**
     * 处理执行超时（AC-023）：推送 workflow_failed(status=TIMEOUT)
     */
    private void handleTimeout(SseEmitter emitter, WorkflowExecution execution, String message) {
        log.warn("工作流执行超时: executionId={}", execution.getExecutionId());
        execution.timeout();
        recordWorkflowTerminal(execution, "TIMEOUT");
        com.agentdemo.app.execution.WorkflowEventPublisher.send(emitter, "workflow_failed", Map.of(
                "executionId", execution.getExecutionId(),
                "status", WorkflowExecutionStatus.TIMEOUT.name(),
                "error", message != null ? message : "工作流执行超时"));
        emitter.complete();
    }

    /**
     * 处理执行失败：推送 workflow_failed(status=FAILED)
     */
    private void handleFailure(SseEmitter emitter, WorkflowExecution execution, String message) {
        log.error("工作流执行失败: executionId={}, error={}", execution.getExecutionId(), message);
        execution.fail(message);
        recordWorkflowTerminal(execution, "FAILED");
        WorkflowEventPublisher.send(emitter, "workflow_failed", Map.of(
                "executionId", execution.getExecutionId(),
                "status", WorkflowExecutionStatus.FAILED.name(),
                "error", message != null ? message : "工作流执行失败"));
        emitter.complete();
    }

    // ===== P3 新增：暂停与恢复（AC-016/AC-017）=====

    /**
     * 处理执行暂停（Agent 重试耗尽）
     * <p>
     * 业务含义：与 FAILED 终态不同，暂停保留恢复入口——状态置 PAUSED（endTime 不设置），
     * 保存恢复快照（原 template/params/modelId），推送 workflow_paused 事件告知前端可恢复，
     * 随后关闭当前 SSE 流（resume 时建立新流）。
     * </p>
     * <p>
     * 可见性说明：包可见（非 private）——单测需在测试线程直接调用以验证事件契约
     * （MockedStatic 仅拦截创建线程的静态调用，无法拦截 ForkJoinPool 异步线程）。
     * </p>
     */
    void handlePaused(SseEmitter emitter, WorkflowExecution execution, WorkflowPausedException e,
                      WorkflowTemplate template, Map<String, Object> parameters, String modelId) {
        log.warn("工作流暂停（可恢复）: executionId={}, failedAgent={}, failedIndex={}, error={}",
                execution.getExecutionId(), e.getFailedAgentName(), e.getFailedIndex(), e.getMessage());
        // 顺序约束：状态变更（pause）必须最后执行——外部（如测试/前端轮询）一旦观察到
        // PAUSED，快照与事件必然已就绪，避免"看到暂停但快照未写入/事件未发出"的竞态
        resumableStates.put(execution.getExecutionId(), new ResumableExecutionState(template, parameters, modelId));
        WorkflowEventPublisher.send(emitter, "workflow_paused", Map.of(
                "executionId", execution.getExecutionId(),
                "failedAgent", e.getFailedAgentName() != null ? e.getFailedAgentName() : "",
                "failedIndex", e.getFailedIndex(),
                "error", e.getMessage() != null ? e.getMessage() : "Agent 执行失败",
                "resumable", true));
        emitter.complete();
        execution.pause(e.getFailedAgentName(), e.getMessage());
        recordWorkflowTerminal(execution, "PAUSED");
    }

    /**
     * 处理 HITL 暂停（Agent askUser 追问 / @HumanCheckpoint 检查点，Task-07）
     * <p>
     * 业务含义：与失败暂停（handlePaused）语义不同——HITL 暂停是"等待用户输入"（WAITING_USER），
     * 保存 HITL 快照（hitlState，含消息列表/提问数据或工具确认数据/暂停步骤）供 hitlReply 恢复，
     * 按 hitlMode 推送暂停事件（askUser/checkpoint 推 ask_user；toolConfirm 推 tool_confirm，Task-15）
     * + workflow_waiting 告知前端渲染提问/确认卡片，随后关闭当前 SSE 流。
     * 与 handlePaused 完全分离，互不干扰（PAUSED 流程零回归，AC-N01/N02）。
     * </p>
     * <p>
     * 可见性说明：包可见（非 private）——单测需在测试线程直接调用以验证事件契约
     * （MockedStatic 仅拦截创建线程的静态调用，无法拦截 ForkJoinPool 异步线程）。
     * </p>
     */
    void handleHITLPaused(SseEmitter emitter, WorkflowExecution execution, WorkflowHITLException e,
                          WorkflowTemplate template, Map<String, Object> parameters, String modelId) {
        WorkflowHITLState hitlState = e.getHitlState();
        WorkflowHITLState.PendingStep pendingStep = hitlState.getPendingStep();
        WorkflowHITLState.AskUserData askUserData = hitlState.getAskUserData();
        log.info("工作流 HITL 暂停（等待用户）: executionId={}, agent={}, agentIndex={}, mode={}",
                execution.getExecutionId(), pendingStep.getAgentName(),
                pendingStep.getAgentIndex(), hitlState.getHitlMode());
        // 顺序约束：状态变更（waitUser）必须最后执行——外部（如测试/前端轮询）一旦观察到
        // WAITING_USER，快照与事件必然已就绪，避免"看到等待但快照未写入/事件未发出"的竞态
        resumableStates.put(execution.getExecutionId(),
                new ResumableExecutionState(template, parameters, modelId, hitlState));
        // 业务含义：按 hitlMode 分流暂停事件——askUser/checkpoint 推送 ask_user（提问数据），
        // toolConfirm 推送 tool_confirm（工具四要素，Task-15，AC-H01/H02）；workflow_waiting 双模式统一推送
        if (WorkflowHITLState.MODE_TOOL_CONFIRM.equals(hitlState.getHitlMode())) {
            WorkflowHITLState.ToolConfirmData toolConfirmData = hitlState.getToolConfirmData();
            WorkflowEventPublisher.send(emitter, "tool_confirm", Map.of(
                    "agentIndex", pendingStep.getAgentIndex(),
                    "agentName", pendingStep.getAgentName(),
                    "toolName", toolConfirmData != null ? toolConfirmData.getToolName() : "",
                    "toolDescription", toolConfirmData != null ? toolConfirmData.getToolDescription() : "",
                    "arguments", toolConfirmData != null ? toolConfirmData.getArguments() : ""));
        } else {
            WorkflowEventPublisher.send(emitter, "ask_user", Map.of(
                    "agentIndex", pendingStep.getAgentIndex(),
                    "agentName", pendingStep.getAgentName(),
                    "type", askUserData != null ? askUserData.getType() : "",
                    "question", askUserData != null ? askUserData.getQuestion() : "",
                    "options", (askUserData != null && askUserData.getOptions() != null)
                            ? askUserData.getOptions() : List.of(),
                    "retryCount", askUserData != null ? askUserData.getRetryCount() : 0));
        }
        WorkflowEventPublisher.send(emitter, "workflow_waiting", Map.of(
                "executionId", execution.getExecutionId(),
                "agentIndex", pendingStep.getAgentIndex(),
                "agentName", pendingStep.getAgentName(),
                "hitlMode", hitlState.getHitlMode(),
                "resumable", true));
        emitter.complete();
        execution.waitUser();
        recordWorkflowTerminal(execution, "WAITING_USER");
    }

    /**
     * 恢复暂停的执行（AC-017）
     * <p>
     * 业务含义：从快照取出原始 template/params/modelId 重放策略——策略通过
     * execution.context 中的恢复 key 跳过已完成步骤，仅重执行失败步骤。
     * 恢复成功或进入终态时清理快照；恢复中再次暂停则保留快照（循环暂停-恢复）。
     * </p>
     *
     * @param executionId 执行 ID
     * @param emitter     恢复执行的新 SSE 发射器（新事件流）
     * @throws BusinessException 执行不存在（WORKFLOW_NOT_FOUND 5500）或状态/快照不可恢复（WORKFLOW_NOT_RESUMABLE 5507）
     */
    public void resume(String executionId, SseEmitter emitter) {
        WorkflowExecution execution = executions.get(executionId);
        if (execution == null) {
            throw new BusinessException(ErrorCode.WORKFLOW_NOT_FOUND, executionId);
        }
        if (execution.getStatus() != WorkflowExecutionStatus.PAUSED) {
            throw new BusinessException(ErrorCode.WORKFLOW_NOT_RESUMABLE,
                    "工作流当前状态不支持恢复: " + execution.getStatus().name());
        }
        ResumableExecutionState snapshot = resumableStates.get(executionId);
        if (snapshot == null) {
            throw new BusinessException(ErrorCode.WORKFLOW_NOT_RESUMABLE,
                    "恢复快照不存在（可能已被终止或清理）: " + executionId);
        }

        WorkflowExecutionStrategy strategy = strategies.get(snapshot.getTemplate().getMode());
        if (strategy == null) {
            throw new BusinessException(ErrorCode.WORKFLOW_MODE_NOT_SUPPORTED,
                    "不支持的编排模式: " + snapshot.getTemplate().getMode());
        }

        execution.resumeFromPause();
        // 重建取消标志：旧 flag 在暂停期间可能被污染（如 emitter 超时触发 cancel）
        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        cancelFlags.put(executionId, cancelFlag);

        log.info("恢复工作流执行: executionId={}", executionId);

        String snapshotModelId = snapshot.getModelId();
        Map<String, Object> snapshotParams = snapshot.getParameters();
        runAsyncWithTrace(executionId, () -> {
            try {
                String result = strategy.execute(snapshot.getTemplate(), snapshotParams, emitter,
                        execution, snapshotModelId, cancelFlag);
                execution.complete(result);
                recordWorkflowTerminal(execution, "COMPLETED");
                // 恢复成功：清理快照（防止对 COMPLETED 执行再次 resume）
                resumableStates.remove(executionId);
                emitter.complete();
            } catch (WorkflowHITLException e) {
                // 业务含义：恢复执行中再次触发 HITL（Agent askUser/检查点）——再次进入 WAITING_USER，
                // 保留/更新 HITL 快照（Task-07，循环暂停-恢复）
                handleHITLPaused(emitter, execution, e, snapshot.getTemplate(), snapshotParams, snapshotModelId);
            } catch (WorkflowPausedException e) {
                // 循环暂停-恢复：恢复中再次重试耗尽，再次暂停并保留/更新快照
                handlePaused(emitter, execution, e, snapshot.getTemplate(), snapshotParams, snapshotModelId);
            } catch (WorkflowCancelledException e) {
                handleTerminated(emitter, execution, e.getMessage());
                resumableStates.remove(executionId);
            } catch (WorkflowTimeoutException e) {
                handleTimeout(emitter, execution, e.getMessage());
                resumableStates.remove(executionId);
            } catch (BusinessException e) {
                log.error("工作流恢复执行失败: executionId={}, error={}", executionId, e.getMessage(), e);
                handleFailure(emitter, execution, e.getMessage());
                resumableStates.remove(executionId);
            } catch (Exception e) {
                log.error("工作流恢复执行异常: executionId={}", executionId, e);
                handleFailure(emitter, execution, e.getMessage());
                resumableStates.remove(executionId);
            }
        });

        // 注册新 emitter 生命周期回调（与 execute 同模式）
        emitter.onTimeout(() -> cancel(executionId));
        emitter.onError(ex -> cancel(executionId));
    }

    /**
     * 恢复 HITL 暂停的执行（Task-08）
     * <p>
     * 业务含义：用户回复 Agent 提问（askUser）或确认检查点（checkpoint）后恢复工作流——
     * 校验 WAITING_USER + HITL 快照存在，checkpoint 拒绝（approved=false）直接终止（AC-S01）；
     * 其余情况状态回 RUNNING + 推送 workflow_resumed，将 HITL 恢复上下文（HitlResume）写入
     * ctx 恢复 key（hitl:{iteration}:{agentName}），重放策略时策略层据此以恢复方式执行暂停步
     * （避免重放死循环，技术方案 Sec 11），后续步骤正常继续。恢复成功/终态化清理快照，
     * 恢复中再次 HITL 暂停则保留/更新快照（循环暂停-恢复，AC-N03/AC-H02）。
     * </p>
     *
     * @param executionId 执行 ID
     * @param message     用户回复文本（askUser 模式；checkpoint 模式为 null）
     * @param approved    检查点确认结果（checkpoint 模式：true=执行 / false=拒绝；askUser 模式为 null）
     * @param emitter     恢复执行的新 SSE 发射器（新事件流）
     * @throws BusinessException 执行不存在（WORKFLOW_NOT_FOUND 5500）或状态/快照不可恢复（WORKFLOW_NOT_RESUMABLE 5507）
     */
    public void hitlReply(String executionId, String message, Boolean approved, SseEmitter emitter) {
        WorkflowExecution execution = executions.get(executionId);
        if (execution == null) {
            throw new BusinessException(ErrorCode.WORKFLOW_NOT_FOUND, executionId);
        }
        if (execution.getStatus() != WorkflowExecutionStatus.WAITING_USER) {
            throw new BusinessException(ErrorCode.WORKFLOW_NOT_RESUMABLE,
                    "工作流当前状态不支持 HITL 回复: " + execution.getStatus().name());
        }
        ResumableExecutionState snapshot = resumableStates.get(executionId);
        if (snapshot == null || snapshot.getHitlState() == null) {
            throw new BusinessException(ErrorCode.WORKFLOW_NOT_RESUMABLE,
                    "HITL 恢复快照不存在（可能已被终止或清理）: " + executionId);
        }

        WorkflowHITLState hitlState = snapshot.getHitlState();
        WorkflowTemplate template = snapshot.getTemplate();
        Map<String, Object> snapshotParams = snapshot.getParameters();
        String snapshotModelId = snapshot.getModelId();

        // 业务含义：checkpoint 拒绝（approved=false）直接终止工作流（AC-S01）——同步执行，
        // 推送 workflow_failed(TERMINATED) + 清理快照（终态化，不可再恢复）
        if (WorkflowHITLState.MODE_CHECKPOINT.equals(hitlState.getHitlMode())
                && Boolean.FALSE.equals(approved)) {
            execution.terminate();
            resumableStates.remove(executionId);
            WorkflowEventPublisher.send(emitter, "workflow_failed", Map.of(
                    "executionId", executionId,
                    "status", WorkflowExecutionStatus.TERMINATED.name(),
                    "error", "用户拒绝了检查点确认，工作流已终止"));
            emitter.complete();
            // CR-001 Task-20：checkpoint 拒绝在同步线程（HTTP 请求线程），临时注入执行 ID 保持聚合
            TraceContextHolder.set(new TraceContextHolder.TraceContext(TraceContextHolder.currentTraceId(), executionId));
            try {
                recordWorkflowTerminal(execution, "TERMINATED");
            } finally {
                TraceContextHolder.clear();
            }
            return;
        }

        WorkflowExecutionStrategy strategy = strategies.get(template.getMode());
        if (strategy == null) {
            throw new BusinessException(ErrorCode.WORKFLOW_MODE_NOT_SUPPORTED,
                    "不支持的编排模式: " + template.getMode());
        }

        // 业务含义：将 HITL 恢复上下文写入 ctx 恢复 key（hitl:{iteration}:{agentName}），
        // 策略重放时 executeOrSkip/Supervisor 据此识别暂停步并以恢复方式执行（避免死循环）。
        // ctx 在 HITL 暂停时已由策略挂载（attachOrNewContext），此处兜底新建保证不丢失。
        // 该写入必须先于排队消费——即使本次回复后仍有排队 HITL，重放时也能恢复已回复的暂停步
        WorkflowContext ctx = execution.getContext();
        if (ctx == null) {
            ctx = new WorkflowContext();
            execution.attachContext(ctx);
        }
        WorkflowHITLState.PendingStep pendingStep = hitlState.getPendingStep();
        ctx.write(AbstractExecutionStrategy.hitlResumeKey(pendingStep.getIteration(), pendingStep.getAgentName()),
                new WorkflowHITLState.HitlResume(hitlState, message, approved));

        // 业务含义：并行 HITL 排队消费（Task-09，AC-E02）——并行分组多个 Agent 同时 askUser 时
        // 第一个生效进入 WAITING_USER，其余由并行策略写入 ctx 排队列表。用户回复后先消费队列：
        // 仍有排队 HITL 则更新快照并再次进入 WAITING_USER（按序继续提问），队列清空才真正恢复执行。
        // 保证同一 executionId 同时只有一个 WAITING_USER（状态始终不离开 WAITING_USER）
        WorkflowHITLException pending = pollPendingHitl(execution);
        if (pending != null) {
            log.info("消费并行排队 HITL: executionId={}, agent={}",
                    executionId, pending.getHitlState().getPendingStep().getAgentName());
            resumableStates.put(executionId,
                    new ResumableExecutionState(template, snapshotParams, snapshotModelId, pending.getHitlState()));
            handleHITLPaused(emitter, execution, pending, template, snapshotParams, snapshotModelId);
            return;
        }

        // 顺序约束：状态恢复 RUNNING + workflow_resumed 推送（同步完成），
        // 保证外部观察到 RUNNING 时恢复上下文已就绪
        execution.resumeFromWait();
        WorkflowEventPublisher.send(emitter, "workflow_resumed", Map.of(
                "executionId", executionId,
                "status", WorkflowExecutionStatus.RUNNING.name()));

        // 重建取消标志：旧 flag 在等待期间可能被污染（如 emitter 超时触发 cancel）
        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        cancelFlags.put(executionId, cancelFlag);

        log.info("恢复工作流 HITL 执行: executionId={}, hitlMode={}, agent={}",
                executionId, hitlState.getHitlMode(), pendingStep.getAgentName());

        runAsyncWithTrace(executionId, () -> {
            try {
                String result = strategy.execute(template, snapshotParams, emitter,
                        execution, snapshotModelId, cancelFlag);
                execution.complete(result);
                recordWorkflowTerminal(execution, "COMPLETED");
                // 恢复成功：清理快照（防止对 COMPLETED 执行再次 hitlReply）
                resumableStates.remove(executionId);
                emitter.complete();
            } catch (WorkflowHITLException e) {
                // 业务含义：恢复中再次触发 HITL（Agent askUser/检查点）——再次进入 WAITING_USER，
                // 保留/更新 HITL 快照（循环暂停-恢复，Task-08）
                handleHITLPaused(emitter, execution, e, template, snapshotParams, snapshotModelId);
            } catch (WorkflowPausedException e) {
                // 业务含义：恢复中 Agent 重试耗尽——转 PAUSED（失败暂停，非 WAITING_USER，AC-H02）
                handlePaused(emitter, execution, e, template, snapshotParams, snapshotModelId);
            } catch (WorkflowCancelledException e) {
                handleTerminated(emitter, execution, e.getMessage());
                resumableStates.remove(executionId);
            } catch (WorkflowTimeoutException e) {
                handleTimeout(emitter, execution, e.getMessage());
                resumableStates.remove(executionId);
            } catch (BusinessException e) {
                log.error("工作流 HITL 恢复执行失败: executionId={}, error={}", executionId, e.getMessage(), e);
                handleFailure(emitter, execution, e.getMessage());
                resumableStates.remove(executionId);
            } catch (Exception e) {
                log.error("工作流 HITL 恢复执行异常: executionId={}", executionId, e);
                handleFailure(emitter, execution, e.getMessage());
                resumableStates.remove(executionId);
            }
        });

        // 注册新 emitter 生命周期回调（与 resume 同模式）
        emitter.onTimeout(() -> cancel(executionId));
        emitter.onError(ex -> cancel(executionId));
    }

    /**
     * 从 ctx 排队列表取出下一个待处理的 HITL 请求（Task-09，AC-E02）
     * <p>
     * 业务含义：并行分组多个 Agent 同时 askUser 时，第一个生效进入 WAITING_USER，
     * 其余由并行策略写入 ctx 排队列表（PENDING_HITL_KEY）。用户每次 hitlReply 恢复时
     * 调用本方法按序消费——取出第一个（剩余写回 ctx），协调层据此更新快照并再次进入
     * WAITING_USER（继续问下一个问题），直到队列清空才真正重放策略执行。
     * </p>
     *
     * @param execution 执行实例（从其 context 读取排队列表）
     * @return 下一个待处理的 HITL 异常（无排队返回 null）
     */
    private WorkflowHITLException pollPendingHitl(WorkflowExecution execution) {
        WorkflowContext ctx = execution.getContext();
        if (ctx == null) {
            return null;
        }
        Object raw = ctx.read(WorkflowContext.PENDING_HITL_KEY);
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        @SuppressWarnings("unchecked")
        List<WorkflowHITLException> pending = new ArrayList<>((List<WorkflowHITLException>) list);
        WorkflowHITLException first = pending.remove(0);
        // 业务含义：用空列表表示清空（ConcurrentHashMap 不允许 null value，WorkflowContext.write 不可写 null）
        ctx.write(WorkflowContext.PENDING_HITL_KEY, pending);
        return first;
    }

    /**
     * 定时清理超时未回复的 WAITING_USER 执行（Task-09，AC-E01）
     * <p>
     * 业务含义：与 HumanInteractionManager 会话清理同频（每 5 分钟），扫描等待用户输入
     * 超过 30 分钟的执行置 TIMEOUT（终态）并清理 HITL 快照，防止僵尸等待占用内存。
     * 模块归属说明：executions 存储于本模块（agent-demo-app），HumanInteractionManager
     * 位于 agent-demo-agent（会话级），无法跨模块访问工作流执行实例，故超时清理在协调层实现。
     * </p>
     */
    @Scheduled(fixedRate = 5 * 60 * 1000L)
    public void cleanupExpiredWaitingUsers() {
        cleanupExpiredWaitingUsers(DEFAULT_HITL_TIMEOUT_MS);
    }

    /**
     * 清理超时未回复的 WAITING_USER 执行（测试可直调，指定超时阈值）
     *
     * @param timeoutMillis 超时时间（毫秒）
     */
    public void cleanupExpiredWaitingUsers(long timeoutMillis) {
        long now = System.currentTimeMillis();
        for (WorkflowExecution execution : executions.values()) {
            if (execution.getStatus() != WorkflowExecutionStatus.WAITING_USER) {
                continue;
            }
            LocalDateTime waitTime = execution.getWaitUserTime();
            if (waitTime == null) {
                continue;
            }
            long waitedMs = now - waitTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            if (waitedMs > timeoutMillis) {
                execution.timeout();
                resumableStates.remove(execution.getExecutionId());
                log.info("清理超时 HITL 等待: executionId={}, 等待 {} ms", execution.getExecutionId(), waitedMs);
            }
        }
    }

    /**
     * 生成唯一执行 ID
     */
    private String generateExecutionId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 带追踪上下文的异步执行（CR-001 Task-19，决策 8：executionId 作 thread 聚合键，AC-M03）
     * <p>
     * 业务含义：仿 AgentController.runTracedAsync 模式--提交线程捕获 MDC traceId（本地日志互查键），
     * 异步线程内设置 TraceContextHolder（traceId + executionId）+ 开启请求级根 span，
     * 执行结束 finally endRequest + 清理（AC-E05 无线程上下文泄漏）。
     * </p>
     * <p>
     * BUG 修复（原 §11 风险 4 已知边界）：HITL 暂停（WAITING_USER）经 recordWorkflowTerminal
     * 中 markHITLPause 保存根 span 上下文，hitlReply 恢复轮经 resumeRequest 以其为父续接
     * 同一 trace--单次工作流执行（含多轮人机交互）在 LangSmith 呈现一条完整链路；
     * 新执行/无续接记录（如失败恢复 resume）时回退独立新 trace。
     * </p>
     *
     * @param executionId 执行 ID（thread 聚合键 + trace 续接键）
     * @param task        异步任务
     */
    private void runAsyncWithTrace(String executionId, Runnable task) {
        // 业务含义：在提交线程（HTTP 请求线程）捕获 MDC traceId--MDC 为 ThreadLocal 不跨线程传播
        String traceId = TraceContextHolder.currentTraceId();
        CompletableFuture.runAsync(() -> {
            TraceContextHolder.set(new TraceContextHolder.TraceContext(traceId, executionId));
            // BUG 修复：续接键=executionId（WAITING_USER 暂停时已标记；新执行无记录回退独立新 trace）
            traceCollector.resumeRequest(executionId);
            try {
                task.run();
            } finally {
                traceCollector.endRequest();
                TraceContextHolder.clear();
            }
        });
    }

    /**
     * 工作流级 span 上报（CR-001 Task-20，AC-N07）
     * <p>
     * 业务含义：各终态（完成/失败/终止/超时/暂停等待用户）统一上报 WorkflowExecutionEvent；
     * 埋点自身异常静默降级（AC-E05）。durationMs 取 startTime 到当前的历史总耗时（恢复语义保留）。
     * </p>
     */
    private void recordWorkflowTerminal(WorkflowExecution execution, String status) {
        try {
            // BUG 修复：WAITING_USER（HITL 等待用户）暂停时标记 trace 续接点，
            // hitlReply 恢复轮共享同一 trace（此时根 span 仍为当前线程父上下文，可安全捕获）
            if ("WAITING_USER".equals(status)) {
                traceCollector.markHITLPause(execution.getExecutionId());
            }
            long durationMs = 0;
            LocalDateTime start = execution.getStartTime();
            if (start != null) {
                durationMs = java.time.Duration.between(start, LocalDateTime.now()).toMillis();
            }
            traceCollector.recordWorkflow(new TraceCollector.WorkflowExecutionEvent(
                    execution.getExecutionId(), execution.getTemplateId(), execution.getTemplateName(),
                    execution.getMode() != null ? execution.getMode().name() : "",
                    status, durationMs, execution.getFinalResult()));
        } catch (Exception e) {
            log.warn("LangSmith 工作流采集失败（降级跳过）: {}", e.getMessage());
        }
    }
}
