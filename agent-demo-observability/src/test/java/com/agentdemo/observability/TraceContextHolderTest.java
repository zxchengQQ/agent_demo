package com.agentdemo.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TraceContextHolder 测试（Task-04）
 * <p>
 * 业务含义：验证异步边界上下文传播机制（技术方案 §7.1 传播链、决策 5）：
 * 同线程 set/get 一致、clear 后为空、无显式上下文时回退 MDC traceId（AC-M01）、
 * 跨线程不串扰（异步线程不继承，规避 MDC 不跨线程的坑）。
 * </p>
 */
class TraceContextHolderTest {

    @AfterEach
    void tearDown() {
        TraceContextHolder.clear();
        MDC.clear();
    }

    @Test
    void set_thenGet_returnsSameContext() {
        TraceContextHolder.TraceContext ctx = new TraceContextHolder.TraceContext("trace-1", "session-1");
        TraceContextHolder.set(ctx);
        assertThat(TraceContextHolder.get()).isEqualTo(ctx);
    }

    @Test
    void clear_makesContextNull() {
        TraceContextHolder.set(new TraceContextHolder.TraceContext("trace-1", "session-1"));
        TraceContextHolder.clear();
        assertThat(TraceContextHolder.get()).isNull();
    }

    @Test
    void currentTraceId_fallsBackToMdc_whenNoExplicitContext() {
        MDC.put(TraceContextHolder.TRACE_ID_MDC_KEY, "mdc-trace-1");
        assertThat(TraceContextHolder.currentTraceId()).isEqualTo("mdc-trace-1");
    }

    @Test
    void currentTraceId_prefersExplicitContext_overMdc() {
        MDC.put(TraceContextHolder.TRACE_ID_MDC_KEY, "mdc-trace-1");
        TraceContextHolder.set(new TraceContextHolder.TraceContext("ctx-trace-2", "session-2"));
        assertThat(TraceContextHolder.currentTraceId()).isEqualTo("ctx-trace-2");
        // sessionId 仅从显式上下文取
        assertThat(TraceContextHolder.currentSessionId()).isEqualTo("session-2");
    }

    @Test
    void context_doesNotLeakAcrossThreads() throws Exception {
        TraceContextHolder.set(new TraceContextHolder.TraceContext("parent-trace", "parent-session"));
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            TraceContextHolder.TraceContext ctx = TraceContextHolder.get();
            return ctx == null ? "null" : ctx.traceId();
        });
        assertThat(future.get()).isEqualTo("null");
        // 主线程上下文不受异步线程影响
        assertThat(TraceContextHolder.get().traceId()).isEqualTo("parent-trace");
    }
}
