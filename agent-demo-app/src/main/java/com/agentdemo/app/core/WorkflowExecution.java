package com.agentdemo.app.core;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 工作流执行实例
 * <p>
 * 业务含义：记录一次工作流执行的完整状态，包括执行 ID、状态、步骤记录和最终结果。
 * 存储在内存中（ConcurrentHashMap），应用重启后丢失（BR-APP-008）。
 * </p>
 */
@Data
public class WorkflowExecution {
    private String executionId;
    private String templateId;
    private String templateName;
    private WorkflowExecutionStatus status;
    private List<StepExecution> steps;
    private String finalResult;
    private LocalDateTime startTime;
    private LocalDateTime endTime;

    // ===== P2 新增 =====
    /** 编排模式（execute 分发时写入，供执行历史列表展示，AC-027） */
    private OrchestrationMode mode;

    /** 迭代次数（循环模式结束写入，供历史列表展示，AC-029） */
    private int iterationCount;

    // ===== P3 新增 =====
    /** 执行上下文（策略创建后挂载；暂停时协调层从此读取恢复所需状态，AC-017） */
    private WorkflowContext context;

    // ===== 工作流 HITL Task-09 新增 =====
    /** 进入 WAITING_USER 的时间（会话超时清理依据，AC-E01） */
    private LocalDateTime waitUserTime;

    public WorkflowExecution(String executionId, String templateId, String templateName) {
        this.executionId = executionId;
        this.templateId = templateId;
        this.templateName = templateName;
        this.status = WorkflowExecutionStatus.PENDING;
        // 业务含义：并行编排（ParallelExecutionStrategy）多线程并发向 steps 追加步骤，
        // ArrayList 非线程安全会触发扩容竞态（ArrayIndexOutOfBoundsException），改用写时复制集合保证并发安全
        this.steps = new CopyOnWriteArrayList<>();
    }

    /**
     * 开始执行
     */
    public void start() {
        this.status = WorkflowExecutionStatus.RUNNING;
        this.startTime = LocalDateTime.now();
    }

    /**
     * 标记完成
     *
     * @param finalResult 最终结果
     */
    public void complete(String finalResult) {
        this.status = WorkflowExecutionStatus.COMPLETED;
        this.finalResult = finalResult;
        this.endTime = LocalDateTime.now();
    }

    /**
     * 标记失败
     *
     * @param error 错误信息
     */
    public void fail(String error) {
        this.status = WorkflowExecutionStatus.FAILED;
        this.endTime = LocalDateTime.now();
    }

    /**
     * 标记终止
     */
    public void terminate() {
        this.status = WorkflowExecutionStatus.TERMINATED;
        this.endTime = LocalDateTime.now();
    }

    /**
     * 标记超时
     */
    public void timeout() {
        this.status = WorkflowExecutionStatus.TIMEOUT;
        this.endTime = LocalDateTime.now();
    }

    // ===== P3 新增：暂停/恢复状态机（AC-016/AC-017）=====

    /**
     * 暂停执行（Agent 重试耗尽后由协调层调用）
     * <p>
     * 业务含义：与 FAILED 终态不同，暂停是"中场休息"——endTime 不设置（非终态），
     * 用户可随时通过 resume 从断点继续，避免全部工序重跑。
     * </p>
     *
     * @param failedAgent 失败的 Agent 名（前端高亮定位用）
     * @param error       错误信息
     */
    public void pause(String failedAgent, String error) {
        this.status = WorkflowExecutionStatus.PAUSED;
        this.finalResult = null;
    }

    /**
     * 从暂停恢复
     * <p>
     * 业务含义：startTime 保留原值（历史总耗时语义），状态回 RUNNING；
     * 恢复后已完成步骤由策略跳过（确定性重放），从失败步骤重新执行。
     * </p>
     */
    public void resumeFromPause() {
        this.status = WorkflowExecutionStatus.RUNNING;
    }

    // ===== 工作流 HITL Task-03：等待用户输入状态机（AC-N01/N02/N03）=====

    /**
     * 标记等待用户输入（HITL 暂停后由协调层调用）
     * <p>
     * 业务含义：与 PAUSED（执行失败暂停）不同，WAITING_USER 是"等待用户决策"——
     * Agent 调用 askUser 或到达 @HumanCheckpoint 检查点后暂停，等待用户回复。
     * 同为非终态：endTime 不设置，用户回复后恢复为 RUNNING，或主动终止。
     * </p>
     */
    public void waitUser() {
        this.status = WorkflowExecutionStatus.WAITING_USER;
        this.finalResult = null;
        // 业务含义：记录进入 WAITING_USER 的时间——协调层 @Scheduled 清理据此判断
        // 超 30 分钟未回复的执行置 TIMEOUT（AC-E01）；循环暂停-恢复时每次 waitUser 重新计时
        this.waitUserTime = LocalDateTime.now();
    }

    /**
     * 从等待用户输入恢复（HITL 用户回复后由协调层调用，Task-08）
     * <p>
     * 业务含义：与 resumeFromPause（失败暂停恢复）语义对称——WAITING_USER 用户回复后
     * 状态回 RUNNING，startTime 保留原值（历史总耗时语义），恢复后策略重放续跑。
     * </p>
     */
    public void resumeFromWait() {
        this.status = WorkflowExecutionStatus.RUNNING;
    }

    /**
     * 挂载执行上下文（策略首行调用，幂等性由调用方保证——已有 ctx 时不覆盖）
     *
     * @param ctx 工作流上下文
     */
    public void attachContext(WorkflowContext ctx) {
        this.context = ctx;
    }
}
