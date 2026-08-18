package com.agentdemo.app.execution;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * WorkflowEventPublisher 测试（P2 Task-05）
 * <p>
 * 业务含义：验证 SSE 事件统一发送入口：正常发送调用 emitter.send，
 * 发送失败时降级为 WARN 日志不抛异常，保证不中断后续事件推送。
 * </p>
 */
class WorkflowEventPublisherTest {

    @Test
    void send_shouldCallEmitterSendOnce() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        WorkflowEventPublisher.send(emitter, "step_start", Map.of("agentIndex", 0));

        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void send_shouldNotThrowWhenEmitterSendFails() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        // emitter.send 抛异常（如连接已关闭）
        doThrow(new IllegalStateException("连接已关闭")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        // 降级为 WARN 日志，不抛异常，不影响后续事件
        assertDoesNotThrow(() -> WorkflowEventPublisher.send(emitter, "step_complete", Map.of("agentIndex", 0)));
    }
}
