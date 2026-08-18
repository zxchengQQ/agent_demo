package com.agentdemo.app.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 事件发布器
 * <p>
 * 业务含义：统一 SSE 事件发送入口，供所有执行策略复用，避免各策略重复 try-catch。
 * 发送失败时降级为 WARN 日志，不中断后续事件推送（P1 sendEvent 抽取）。
 * </p>
 * <p>
 * 设计说明：send 为无状态方法，使用静态工具类而非 Spring Bean，调用最简。
 * </p>
 */
public final class WorkflowEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEventPublisher.class);

    private WorkflowEventPublisher() {
        // 工具类禁止实例化
    }

    /**
     * 发送 SSE 事件（统一异常处理，避免异常中断后续 SSE 事件推送）
     *
     * @param emitter   SSE 发射器
     * @param eventName 事件名（如 step_start/token/step_complete）
     * @param data      事件数据（Map，序列化为 JSON）
     */
    public static void send(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (Exception e) {
            log.warn("SSE 发送失败: event={}, reason={}", eventName, e.getMessage());
        }
    }
}
