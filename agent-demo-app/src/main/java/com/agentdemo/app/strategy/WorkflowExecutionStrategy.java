package com.agentdemo.app.strategy;

import com.agentdemo.app.core.OrchestrationMode;
import com.agentdemo.app.core.WorkflowExecution;
import com.agentdemo.app.core.WorkflowTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 工作流执行策略
 * <p>
 * 业务含义：封装不同编排模式（串行/并行/条件/循环）的执行算法（策略模式核心扩展点）。
 * 新增编排模式时只需实现此接口并注册为 @Component，
 * WorkflowExecutionService 通过 Spring 自动注入发现策略，无需修改核心类（OCP）。
 * </p>
 */
public interface WorkflowExecutionStrategy {

    /**
     * 该策略支持的编排模式
     *
     * @return 编排模式枚举
     */
    OrchestrationMode supportedMode();

    /**
     * 执行编排算法
     *
     * @param template   工作流模板（含 mode 特定的配置：parallelGroups/branches/loop）
     * @param params     执行参数
     * @param emitter    SSE 发射器
     * @param execution  执行实例（用于记录步骤和状态）
     * @param modelId    模型 ID（null 使用默认模型）
     * @param cancelFlag 取消标志（每步执行前检查，AC-022）
     * @return 最终结果文本
     */
    String execute(WorkflowTemplate template, Map<String, Object> params,
                   SseEmitter emitter, WorkflowExecution execution,
                   String modelId, AtomicBoolean cancelFlag);
}
