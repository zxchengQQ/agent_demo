package com.agentdemo.memory.shortterm;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 记忆压缩监听器测试（CR-001 Task-18，AC-N06/E05）
 * <p>
 * 业务含义：验证 CompressingChatMemory 压缩完成（成功/降级）路径触发 CompressionListener，
 * 且无监听器构造不影响既有压缩行为。
 * </p>
 */
class CompressingChatMemoryListenerTest {

    private static final List<ChatMessage> msgs(int n) {
        List<ChatMessage> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(UserMessage.from("消息" + i));
        }
        return list;
    }

    @Test
    void successCompression_firesListener_withStats() {
        AtomicReference<CompressingChatMemory.CompressionStats> captured = new AtomicReference<>();
        CompressingChatMemory.CompressionListener listener = captured::set;
        CompressingChatMemory memory = new CompressingChatMemory(
                3, 2, (prev, seg) -> "归纳摘要", listener);

        for (ChatMessage m : msgs(4)) {
            memory.add(m);
        }

        CompressingChatMemory.CompressionStats s = captured.get();
        assertThat(s).isNotNull();
        assertThat(s.messagesBefore()).isEqualTo(4);
        assertThat(s.messagesAfter()).isEqualTo(2);
        assertThat(s.compressedCount()).isEqualTo(3);
        assertThat(s.window()).isEqualTo(3);
        assertThat(s.summary()).isEqualTo("归纳摘要");
        assertThat(s.degraded()).isFalse();
        assertThat(s.durationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void degradedCompression_firesListener_withDegradedFlag() {
        AtomicReference<CompressingChatMemory.CompressionStats> captured = new AtomicReference<>();
        CompressingChatMemory.CompressionListener listener = captured::set;
        CompressingChatMemory memory = new CompressingChatMemory(
                3, 2, (prev, seg) -> {
                    throw new IllegalStateException("摘要模型返回空");
                }, listener);

        for (ChatMessage m : msgs(4)) {
            memory.add(m);
        }

        CompressingChatMemory.CompressionStats s = captured.get();
        assertThat(s).isNotNull();
        assertThat(s.degraded()).isTrue();
        assertThat(s.summary()).isNull();
        assertThat(s.messagesBefore()).isEqualTo(4);
        assertThat(s.messagesAfter()).isEqualTo(3);
        assertThat(s.compressedCount()).isEqualTo(1);
        // AC-E01：降级后对话记忆不中断
        assertThat(memory.messages()).hasSize(3);
    }

    @Test
    void noListenerConstructor_keepsExistingBehavior() {
        // 无监听器构造：既有压缩行为零变化（不 NPE、正常压缩）
        CompressingChatMemory memory = new CompressingChatMemory(
                3, (prev, seg) -> "摘要");
        for (ChatMessage m : msgs(4)) {
            memory.add(m);
        }
        assertThat(memory.messages()).hasSizeLessThan(4);
    }
}
