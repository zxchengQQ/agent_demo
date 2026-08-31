package com.agentdemo.observability;

/**
 * 追踪采集器接口（langsmith-observability，Task-03）
 * <p>
 * 业务含义：LLM 调用与工具调用埋点的统一上报入口。埋点方（ModelFactory / ToolExecutor / 装饰器）
 * 只依赖本接口，不关心实现是 Noop（默认关闭）还是 OTLP 上报。
 * </p>
 * <p>
 * 事件字段设计对齐技术方案 §7.1 span 设计表；traceId/sessionId 不随事件传入，
 * 由实现方从 {@link TraceContextHolder} 读取（决策 5：ThreadLocal 上下文传播）。
 * </p>
 * <p>
 * 只读旁路设计（AC-S05）：本接口所有方法无返回值、无副作用，采集失败不得影响主流程
 * （实现方内部 try-catch，见技术方案 §3.3 失败表）。
 * </p>
 */
public interface TraceCollector {

    /**
     * 采集器是否启用。
     * <p>
     * 业务含义：供埋点装配方（ModelFactory）在默认关闭（AC-S02）时跳过 listener 挂载与
     * 装饰器包装，实现真正"零开销"——Noop 默认返回 false，Otlp 覆写为 true。
     * </p>
     *
     * @return true=启用，应挂载采集；false=未启用，跳过挂载
     */
    default boolean isEnabled() {
        return false;
    }

    /**
     * 开始一次请求级根 span（AC-T01：同一次用户消息的 LLM/工具 span 共享同一 trace）。
     * <p>
     * 在异步边界（Controller runAsync）内调用；实现方创建根 span 并使其 Context 成为
     * 当前线程的父上下文，后续 recordLlm/recordTool 生成的 span 自动成为其子 span。
     * 未启用（Noop）时为空操作。
     * </p>
     */
    void startRequest();

    /**
     * 结束请求级根 span（异步边界 finally 调用，与 {@link #startRequest()} 成对）。
     * 未启用（Noop）时为空操作。
     */
    void endRequest();

    /**
     * 记录一次 LLM 调用
     *
     * @param event LLM 调用事件
     */
    void recordLlm(LlmCallEvent event);

    /**
     * 记录一次工具调用
     *
     * @param event 工具调用事件
     */
    void recordTool(ToolCallEvent event);

    /**
     * LLM 调用事件（技术方案 §7.1 LLM span 数据来源）
     *
     * @param modelName      实际使用的模型名（AC-N03，会话内如实反映，AC-M02）
     * @param promptText     输入消息文本（messages 序列化）
     * @param outputText     输出回复文本（失败时可为 null）
     * @param inputTokens    输入 Token 数（未知传 -1；Thinking 系取 onComplete 真实值）
     * @param outputTokens   输出 Token 数（未知传 -1）
     * @param durationMs     调用耗时（毫秒）
     * @param success        是否成功
     * @param errorMessage   失败原因（成功时 null）
     * @param finishReason   结束原因（stop / tool_calls，未知可 null）
     */
    record LlmCallEvent(
            String modelName,
            String promptText,
            String outputText,
            int inputTokens,
            int outputTokens,
            long durationMs,
            boolean success,
            String errorMessage,
            String finishReason) {
    }

    /**
     * 工具调用事件（技术方案 §7.1 工具 span 数据来源）
     *
     * @param toolName      工具名（AC-N04）
     * @param arguments     入参 JSON（LLM 生成）
     * @param result        出参文本（已过 sanitize 管道，失败时可为 null）
     * @param durationMs    耗时（毫秒）
     * @param success       是否成功（AC-T03：失败也记录，含异常信息）
     * @param errorMessage  失败原因（成功时 null）
     */
    record ToolCallEvent(
            String toolName,
            String arguments,
            String result,
            long durationMs,
            boolean success,
            String errorMessage) {
    }
}
