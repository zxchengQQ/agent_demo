package com.agentdemo.observability;

import org.slf4j.MDC;

/**
 * 采集上下文持有器（langsmith-observability，Task-04）
 * <p>
 * 业务含义：traceId/sessionId 跨异步线程的传播载体（技术方案 §7.1 传播链、决策 5）。
 * SSE 编排运行在 {@code CompletableFuture.runAsync} 异步线程，MDC traceId 是 ThreadLocal
 * 不跨线程传播，故在 Controller 异步边界显式捕获并写入本持有器；埋点方（collector）在同一
 * 异步线程内读取。无显式上下文时回退读 MDC traceId（同步路径 Controller 线程内联执行场景）。
 * </p>
 *
 * @see TraceCollector
 */
public final class TraceContextHolder {

    /**
     * 本地日志 MDC 中 traceId 的 key（与 web 模块 TraceIdInterceptor 保持一致，obs 模块不依赖 web）
     */
    public static final String TRACE_ID_MDC_KEY = "traceId";

    /**
     * 追踪上下文值对象
     *
     * @param traceId   请求 traceId（与本地日志互查键，AC-M01）
     * @param sessionId 会话 ID（thread 聚合键，AC-N02）
     */
    public record TraceContext(String traceId, String sessionId) {}

    private static final ThreadLocal<TraceContext> CONTEXT = new ThreadLocal<>();

    private TraceContextHolder() {
    }

    /**
     * 设置当前线程的追踪上下文（异步边界入口调用）
     *
     * @param ctx 追踪上下文
     */
    public static void set(TraceContext ctx) {
        CONTEXT.set(ctx);
    }

    /**
     * 清除当前线程的追踪上下文（异步边界 finally 调用，防线程池复用泄漏）
     */
    public static void clear() {
        CONTEXT.remove();
    }

    /**
     * 获取当前线程显式设置的追踪上下文
     *
     * @return 上下文，未设置时为 null
     */
    public static TraceContext get() {
        return CONTEXT.get();
    }

    /**
     * 获取当前 traceId：优先显式上下文，回退 MDC（AC-M01）
     *
     * @return traceId，两者均无时 null
     */
    public static String currentTraceId() {
        TraceContext ctx = CONTEXT.get();
        if (ctx != null && ctx.traceId() != null && !ctx.traceId().isEmpty()) {
            return ctx.traceId();
        }
        return MDC.get(TRACE_ID_MDC_KEY);
    }

    /**
     * 获取当前 sessionId：仅从显式上下文取（MDC 无会话信息）
     *
     * @return sessionId，未设置时 null
     */
    public static String currentSessionId() {
        TraceContext ctx = CONTEXT.get();
        return ctx != null ? ctx.sessionId() : null;
    }
}
