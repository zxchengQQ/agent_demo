package com.agentdemo.app.service;

import com.agentdemo.app.core.OrchestrationMode;
import com.agentdemo.app.core.WorkflowExecution;
import com.agentdemo.app.core.WorkflowExecutionStatus;
import com.agentdemo.app.core.WorkflowTemplate;
import com.agentdemo.app.execution.WorkflowEventPublisher;
import com.agentdemo.app.strategy.WorkflowExecutionStrategy;
import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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

    /**
     * Spring 自动注入所有策略实现，构建分发表
     * 新增模式只需新增 @Component 策略类，此处零修改（OCP）
     *
     * @param strategyList 所有策略实现
     */
    public WorkflowExecutionService(List<WorkflowExecutionStrategy> strategyList) {
        this.strategies = strategyList.stream()
                .collect(Collectors.toMap(WorkflowExecutionStrategy::supportedMode, s -> s));
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
        CompletableFuture.runAsync(() -> {
            try {
                String result = strategy.execute(template, parameters, emitter,
                        execution, modelId, cancelFlag);
                execution.complete(result);
                emitter.complete();
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
        CompletableFuture.runAsync(() -> {
            try {
                String result = strategy.execute(snapshot.getTemplate(), snapshotParams, emitter,
                        execution, snapshotModelId, cancelFlag);
                execution.complete(result);
                // 恢复成功：清理快照（防止对 COMPLETED 执行再次 resume）
                resumableStates.remove(executionId);
                emitter.complete();
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
     * 生成唯一执行 ID
     */
    private String generateExecutionId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
