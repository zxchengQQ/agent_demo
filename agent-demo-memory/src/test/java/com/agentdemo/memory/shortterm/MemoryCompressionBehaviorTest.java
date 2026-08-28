package com.agentdemo.memory.shortterm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 记忆压缩与附件行为测试（agent-context-engineering Task-16，AC-E01/M01/M02）
 * <p>
 * 端到端断言（真实 CompressingChatMemory + 伪摘要生成器）：
 * ①超长会话压缩后对话不中断、摘要含关键实体（AC-E01/M01 指代保持）；
 * ②附件消息（目录/指令）在压缩后全部保留（AC-E01 附件保护）；
 * ③摘要失败降级 FIFO 且附件仍保留（AC-E01 降级）。
 * </p>
 */
class MemoryCompressionBehaviorTest {

    private static final int WINDOW = 20;

    private AtomicReference<String> lastSummary;
    private CompressingChatMemory memory;

    @BeforeEach
    void setUp() {
        lastSummary = new AtomicReference<>();
        memory = new CompressingChatMemory(WINDOW, (previous, messages) -> {
            StringBuilder sb = new StringBuilder();
            if (previous != null) {
                sb.append(previous).append(" | ");
            }
            sb.append("关键实体：ORD-12345；结论：已发货");
            lastSummary.set(sb.toString());
            return sb.toString();
        });
    }

    @Test
    @DisplayName("超长会话：压缩后不中断、摘要保留关键实体、附件保留（AC-E01/M01）")
    void 超长会话_压缩_摘要含实体_附件保留() {
        memory.add(memory.newAttachmentMessage(CompressingChatMemory.AttachmentType.CATALOG, "技能目录"));
        memory.add(memory.newAttachmentMessage(CompressingChatMemory.AttachmentType.SKILL_INSTRUCTION, "技能指令"));
        for (int i = 0; i < WINDOW + 5; i++) {
            memory.add(UserMessage.from("用户消息" + i));
            memory.add(AiMessage.from("助手回复" + i));
        }

        List<ChatMessage> msgs = memory.messages();
        // 压缩发生且摘要含关键实体（AC-M01 指代保持）
        assertTrue(msgs.stream().anyMatch(m -> m instanceof UserMessage um
                && um.singleText() != null && um.singleText().contains("ORD-12345")),
                "摘要应保留关键实体（压缩后仍可解析指代）");
        // 附件保留（AC-E01 附件保护）
        assertEquals(1, countByPrefix(msgs, "【框架附件·CATALOG】"));
        assertEquals(1, countByPrefix(msgs, "【框架附件·SKILL_INSTRUCTION】"));
        // 对话不中断：消息列表非空
        assertTrue(!msgs.isEmpty());
    }

    @Test
    @DisplayName("摘要失败降级 FIFO：对话不中断、附件保留（AC-E01）")
    void 摘要失败_降级FIFO_附件保留() {
        CompressingChatMemory failing = new CompressingChatMemory(WINDOW, (prev, msgs) -> {
            throw new RuntimeException("摘要模型不可用");
        });
        failing.add(failing.newAttachmentMessage(CompressingChatMemory.AttachmentType.CATALOG, "目录"));
        for (int i = 0; i < WINDOW + 3; i++) {
            failing.add(UserMessage.from("消息" + i));
        }

        List<ChatMessage> msgs = failing.messages();
        assertTrue(msgs.size() <= WINDOW, "降级后应 FIFO 约束消息数");
        assertEquals(1, countByPrefix(msgs, "【框架附件·CATALOG】"), "降级不应丢弃附件");
    }

    private int countByPrefix(List<ChatMessage> msgs, String prefix) {
        return (int) msgs.stream()
                .filter(m -> m instanceof UserMessage um && um.singleText() != null
                        && um.singleText().startsWith(prefix))
                .count();
    }
}
