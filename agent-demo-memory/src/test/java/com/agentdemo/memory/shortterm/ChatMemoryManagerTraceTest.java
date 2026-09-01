package com.agentdemo.memory.shortterm;

import com.agentdemo.llm.registry.ModelFactory;
import com.agentdemo.observability.TraceCollector;
import com.agentdemo.observability.TraceContextHolder;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatMemoryManager 记忆压缩埋点装配测试（CR-001 Task-18，AC-N06）
 * <p>
 * 业务含义：验证 ChatMemoryManager 创建记忆时注入的压缩监听器（闭包捕获 sessionId）
 * 将压缩事件上报 TraceCollector 且上报时 sessionId 已注入上下文（AC-N06 关联当次会话）。
 * </p>
 */
class ChatMemoryManagerTraceTest {

    private static final int WINDOW = 20;

    private ModelFactory modelFactory;
    private ChatModel chatModel;
    private MemoryCompressionProperties properties;
    private TraceCollector collector;
    private ChatMemoryManager manager;

    @BeforeEach
    void setUp() {
        modelFactory = mock(ModelFactory.class);
        chatModel = mock(ChatModel.class);
        when(modelFactory.getDefaultChatModel()).thenReturn(chatModel);
        when(chatModel.chat(any(List.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("关键实体摘要")).build());
        properties = new MemoryCompressionProperties();
        properties.setEnabled(true);
        collector = mock(TraceCollector.class);
        manager = new ChatMemoryManager(modelFactory, properties, collector);
        TraceContextHolder.clear();
    }

    @Test
    void compression_reportsEvent_withSessionAssociated() {
        // AC-N06：压缩发生 -> 上报事件 + 上报时 sessionId 已注入上下文（闭包捕获，即使无显式 holder）
        for (int i = 0; i < WINDOW + 5; i++) {
            manager.addUserMessage("sess-1", "消息" + i);
        }

        ArgumentCaptor<TraceCollector.MemoryCompressionEvent> captor =
                ArgumentCaptor.forClass(TraceCollector.MemoryCompressionEvent.class);
        verify(collector).recordMemoryCompression(captor.capture());
        TraceCollector.MemoryCompressionEvent e = captor.getValue();
        assertThat(e.messagesBefore()).isGreaterThanOrEqualTo(WINDOW);
        assertThat(e.messagesAfter()).isLessThan(WINDOW + 5);
        assertThat(e.summary()).contains("摘要");
        assertThat(e.degraded()).isFalse();
    }

    @Test
    void compression_reportsEvent_withSessionIdInContextHolder() {
        // AC-N06：压缩监听器上报前将 sessionId 注入 TraceContextHolder（闭包捕获）
        AtomicReference<String> sessionAtRecord = new AtomicReference<>();
        doAnswer(inv -> {
            sessionAtRecord.set(TraceContextHolder.currentSessionId());
            return null;
        }).when(collector).recordMemoryCompression(any(TraceCollector.MemoryCompressionEvent.class));

        for (int i = 0; i < WINDOW + 5; i++) {
            manager.addUserMessage("sess-1", "消息" + i);
        }
        assertThat(sessionAtRecord.get()).isEqualTo("sess-1");
        // 上报后清理临时上下文，不污染调用线程
        assertThat(TraceContextHolder.currentSessionId()).isNull();
    }

    @Test
    void compressionDisabled_noEventReported() {
        // AC-S02 语义：压缩关闭（FIFO）时不上报压缩事件
        properties.setEnabled(false);
        ChatMemoryManager fifo = new ChatMemoryManager(modelFactory, properties, collector);
        for (int i = 0; i < WINDOW + 5; i++) {
            fifo.addUserMessage("sess-1", "消息" + i);
        }
        verify(chatModel, never()).chat(any(List.class));
        verify(collector, never()).recordMemoryCompression(any(TraceCollector.MemoryCompressionEvent.class));
    }
}
