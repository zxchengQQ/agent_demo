package com.agentdemo.web.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

/**
 * TraceId 拦截器
 * <p>
 * 业务含义：为每个请求生成唯一 traceId 放入 MDC，贯穿整个请求链路，
 * 便于日志串联与问题定位。响应头回传 traceId 供前端关联。
 * </p>
 */
public class TraceIdInterceptor implements HandlerInterceptor {

    /**
     * TraceId 请求头名称
     */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /**
     * MDC 中 traceId 的 key
     */
    public static final String TRACE_ID_KEY = "traceId";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 业务含义：优先从请求头获取 traceId（支持链路透传），没有则生成新的
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        MDC.put(TRACE_ID_KEY, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 业务含义：请求结束后清理 MDC，防止内存泄漏（线程池复用场景）
        MDC.remove(TRACE_ID_KEY);
    }
}
