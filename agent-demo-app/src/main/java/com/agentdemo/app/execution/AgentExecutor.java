package com.agentdemo.app.execution;

import com.agentdemo.app.adapter.AgenticAgentFactory;
import com.agentdemo.app.core.AgentDefinition;
import com.agentdemo.app.service.WorkflowCancelledException;
import com.agentdemo.app.service.WorkflowPausedException;
import com.agentdemo.app.service.WorkflowTimeoutException;
import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.TokenStream;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 执行器
 * <p>
 * 业务含义：封装单个 Agent 的构建 + 失败重试 + 流式输出，供所有执行策略复用。
 * 从 P1 的 WorkflowExecutionService 抽取，实现单一职责（策略模式基础设施层）。
 * </p>
 * <p>
 * 核心方法：
 * 1. executeWithRetry() 失败自动重试，推送 step_retry/step_error 事件（AC-015/AC-026）
 * 2. executeStreaming() 通过 TokenStream 回调捕获流式输出并推送 token 事件（AC-009）
 * 3. checkCancelled() 静态取消标志检查（AC-022）
 * </p>
 */
@Component
public class AgentExecutor {

    /** 单个 Agent 执行超时阈值（分钟），测试时可注入更小值 */
    private final long agentTimeoutMinutes;

    private final AgenticAgentFactory agentFactory;

    /**
     * 默认构造器（Spring 注入，默认 5 分钟超时）
     *
     * @param agentFactory Agent 构建工厂
     */
    @org.springframework.beans.factory.annotation.Autowired
    public AgentExecutor(AgenticAgentFactory agentFactory) {
        this(agentFactory, 5);
    }

    /**
     * 构造器（测试可注入超时阈值）
     *
     * @param agentFactory        Agent 构建工厂
     * @param agentTimeoutMinutes 单个 Agent 执行超时阈值（分钟）
     */
    public AgentExecutor(AgenticAgentFactory agentFactory, long agentTimeoutMinutes) {
        this.agentFactory = agentFactory;
        this.agentTimeoutMinutes = agentTimeoutMinutes;
    }

    /**
     * 执行单个 Agent（含自动重试）
     * <p>
     * 业务含义：首次执行失败后自动重试，重试次数由模板 maxRetries 决定（AC-026）。
     * 每次重试推送 step_retry 事件，重试耗尽后推送 step_error 并抛出异常（AC-015）。
     * </p>
     *
     * @param agentDef   Agent 定义
     * @param input      输入文本
     * @param emitter    SSE 发射器
     * @param agentIndex Agent 索引（用于事件标识）
     * @param maxRetries 最大重试次数
     * @param modelId    模型 ID
     * @return Agent 完整输出
     */
    public String executeWithRetry(AgentDefinition agentDef, String input,
                                   SseEmitter emitter, int agentIndex,
                                   int maxRetries, String modelId) {
        Object agent = agentFactory.buildAgent(agentDef);

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return executeStreaming(agent, agentDef, input, emitter, agentIndex);
            } catch (WorkflowTimeoutException e) {
                // 业务含义：超时不重试（每次重试都会超时），直接上抛由协调层推送 TIMEOUT（AC-023）
                throw e;
            } catch (WorkflowCancelledException e) {
                // 业务含义：用户取消不重试，直接上抛由协调层推送 TERMINATED（AC-022）
                throw e;
            } catch (Exception e) {
                if (attempt < maxRetries) {
                    WorkflowEventPublisher.send(emitter, "step_retry", Map.of(
                            "agentIndex", agentIndex,
                            "retryCount", attempt + 1,
                            "remainingRetries", maxRetries - attempt - 1));
                } else {
                    WorkflowEventPublisher.send(emitter, "step_error", Map.of(
                            "agentIndex", agentIndex,
                            "error", e.getMessage(),
                            "retryCount", maxRetries));
                    // P3 变更：重试耗尽抛 WorkflowPausedException（协调层据此进入 PAUSED 而非 FAILED，
                    // 携带失败 Agent 名与步骤索引供前端高亮定位，AC-016；子类兼容既有 BusinessException 分支）
                    throw new WorkflowPausedException(agentDef.getName(), agentIndex,
                            "Agent 执行失败: " + agentDef.getName() + ": " + e.getMessage(), e);
                }
            }
        }
        throw new IllegalStateException("不应到达此处");
    }

    /**
     * 执行 Agent 并捕获流式输出
     * <p>
     * 业务含义：通过反射调用 Agent 接口的 @Agent 方法获取 TokenStream，
     * 注册 onPartialResponse（推送 token 事件，AC-009）、onCompleteResponse、
     * onError 回调，用 CompletableFuture 等待流式完成并返回完整文本。
     * 超时抛出 WorkflowTimeoutException（AC-023）。
     * </p>
     */
    String executeStreaming(Object agent, AgentDefinition agentDef,
                            String input, SseEmitter emitter, int agentIndex) {
        Method executeMethod = Arrays.stream(agentDef.getInterfaceClass().getMethods())
                .filter(m -> m.isAnnotationPresent(Agent.class))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.WORKFLOW_EXECUTION_FAILED,
                        "Agent 接口未标注 @Agent 方法: " + agentDef.getInterfaceClass().getName()));

        final TokenStream stream;
        try {
            stream = (TokenStream) executeMethod.invoke(agent, input);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String detail = (cause.getMessage() != null) ? ": " + cause.getMessage() : "";
            // 业务含义：调用阶段失败也需携带底层根因（如模型未配置/LLM 调用错误），便于定位
            throw new BusinessException(ErrorCode.WORKFLOW_EXECUTION_FAILED,
                    "Agent 调用失败: " + agentDef.getName() + detail, cause);
        } catch (IllegalAccessException e) {
            throw new BusinessException(ErrorCode.WORKFLOW_EXECUTION_FAILED,
                    "Agent 方法不可访问: " + agentDef.getName(), e);
        }

        CompletableFuture<String> future = new CompletableFuture<>();

        // 业务含义：onPartialResponse 每次返回 token 片段，实时推送给客户端（AC-009）
        stream.onPartialResponse(token ->
                        WorkflowEventPublisher.send(emitter, "token", Map.of("agentIndex", agentIndex, "content", token)))
                .onCompleteResponse(response -> {
                    String fullResponse = (response.aiMessage() != null) ? response.aiMessage().text() : null;
                    future.complete(fullResponse != null ? fullResponse : "");
                })
                .onError(future::completeExceptionally)
                .start();

        try {
            // 等待流式完成，超时阈值由 agentTimeoutMinutes 决定（AC-023）
            return future.get(agentTimeoutMinutes, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            throw new WorkflowTimeoutException("Agent 执行超时: " + agentDef.getName(), e);
        } catch (ExecutionException e) {
            Throwable cause = (e.getCause() != null) ? e.getCause() : e;
            if (cause instanceof WorkflowCancelledException) {
                throw (WorkflowCancelledException) cause;
            }
            // 业务含义：错误信息必须携带底层根因（如"未配置 chat 模型"/"LLM 连接超时"），便于用户定位问题
            String detail = (cause.getMessage() != null) ? ": " + cause.getMessage() : "";
            throw new BusinessException(ErrorCode.WORKFLOW_EXECUTION_FAILED,
                    "Agent 执行异常: " + agentDef.getName() + detail, cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkflowCancelledException("Agent 执行被中断", e);
        }
    }

    /**
     * 检查取消标志，已取消则抛出 WorkflowCancelledException（AC-022）
     * <p>
     * 业务含义：所有执行策略在每步 Agent 执行前调用，用户终止后立即停止。
     * </p>
     *
     * @param cancelFlag 取消标志（null 视为未取消）
     */
    public static void checkCancelled(AtomicBoolean cancelFlag) {
        if (cancelFlag != null && cancelFlag.get()) {
            throw new WorkflowCancelledException();
        }
    }
}
