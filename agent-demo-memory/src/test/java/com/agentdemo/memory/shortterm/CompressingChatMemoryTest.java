package com.agentdemo.memory.shortterm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CompressingChatMemory 单元测试（agent-context-engineering Task-02，AC-E01/AC-M01）
 * <p>
 * 业务含义：滚动摘要压缩记忆——窗口超限（>maxMessages）时对最旧非保护消息段生成摘要，
 * 附件/System/摘要消息永不压缩；摘要失败降级 FIFO 丢弃（现状行为，对话不中断）。
 * </p>
 */
class CompressingChatMemoryTest {

    private static final int MAX = 20;
    private static final int TARGET = MAX / 2;

    private AtomicReference<String> lastSummaryCall;

    /** 记录摘要调用的伪生成器（返回固定摘要文本） */
    private CompressingChatMemory.SummaryGenerator generator;
    private CompressingChatMemory memory;

    @BeforeEach
    void setUp() {
        lastSummaryCall = new AtomicReference<>();
        generator = (previous, messages) -> {
            StringBuilder sb = new StringBuilder();
            if (previous != null) {
                sb.append(previous).append(" | ");
            }
            sb.append("摘要").append(messages.size()).append("条");
            lastSummaryCall.set(sb.toString());
            return sb.toString();
        };
        memory = new CompressingChatMemory(MAX, generator);
    }

    private void addUser(String text) {
        memory.add(UserMessage.from(text));
    }

    private void addAi(String text) {
        memory.add(AiMessage.from(text));
    }

    private UserMessage attachment(CompressingChatMemory.AttachmentType type, String text) {
        return memory.newAttachmentMessage(type, text);
    }

    private int countByPrefix(List<dev.langchain4j.data.message.ChatMessage> msgs, String prefix) {
        return (int) msgs.stream()
                .filter(m -> m instanceof UserMessage um && um.singleText() != null && um.singleText().startsWith(prefix))
                .count();
    }

    @Test
    @DisplayName("窗口未超限时不触发压缩，消息完整保留")
    void add_未超限_不压缩() {
        for (int i = 0; i < MAX; i++) {
            addUser("消息" + i);
        }

        assertEquals(MAX, memory.messages().size());
        assertTrue(lastSummaryCall.get() == null, "未超限不应调用摘要生成器");
    }

    @Test
    @DisplayName("超限触发压缩：压缩后约半窗且摘要消息置于最前")
    void add_超限_触发压缩到半窗() {
        for (int i = 0; i < MAX + 5; i++) {
            addUser("消息" + i);
        }

        List<dev.langchain4j.data.message.ChatMessage> msgs = memory.messages();
        assertTrue(msgs.size() < MAX + 5, "应发生压缩（未保留全部 25 条），实际: " + msgs.size());
        assertTrue(msgs.size() >= TARGET && msgs.size() <= MAX, "压缩后应在半窗与窗口之间，实际: " + msgs.size());
        assertEquals(1, countByPrefix(msgs, CompressingChatMemory.SUMMARY_PREFIX), "应恰好一条摘要消息");
        assertTrue(((dev.langchain4j.data.message.UserMessage) msgs.get(0)).singleText().startsWith(CompressingChatMemory.SUMMARY_PREFIX),
                "摘要消息应置于最前");
    }

    @Test
    @DisplayName("附件消息永不压缩、永不被 FIFO 丢弃")
    void add_含附件_附件永不被压缩丢弃() {
        memory.add(attachment(CompressingChatMemory.AttachmentType.SKILL_INSTRUCTION, "技能说明书"));
        memory.add(attachment(CompressingChatMemory.AttachmentType.CATALOG, "技能目录"));
        for (int i = 0; i < MAX + 10; i++) {
            addUser("消息" + i);
        }

        List<dev.langchain4j.data.message.ChatMessage> msgs = memory.messages();
        assertEquals(1, countByPrefix(msgs, "【框架附件·SKILL_INSTRUCTION】"));
        assertEquals(1, countByPrefix(msgs, "【框架附件·CATALOG】"));
        assertTrue(countByPrefix(msgs, "【历史对话摘要】") == 1, "压缩仅生成一条摘要");
    }

    @Test
    @DisplayName("System 消息受保护不被压缩丢弃")
    void add_含System_System受保护() {
        memory.add(SystemMessage.from("系统提示词"));
        for (int i = 0; i < MAX + 10; i++) {
            addUser("消息" + i);
        }

        List<dev.langchain4j.data.message.ChatMessage> msgs = memory.messages();
        assertEquals(1, msgs.stream().filter(m -> m instanceof SystemMessage).count(), "System 消息应保留");
    }

    @Test
    @DisplayName("摘要生成失败时降级 FIFO 丢弃最旧非保护消息，不抛异常")
    void add_摘要失败_降级FIFO() {
        CompressingChatMemory failing = new CompressingChatMemory(MAX, (previous, messages) -> {
            throw new RuntimeException("摘要服务不可用");
        });
        failing.add(attachment(CompressingChatMemory.AttachmentType.CATALOG, "目录"));
        for (int i = 0; i < MAX + 3; i++) {
            failing.add(UserMessage.from("消息" + i));
        }

        List<dev.langchain4j.data.message.ChatMessage> msgs = failing.messages();
        assertTrue(msgs.size() < MAX + 3, "降级后应丢弃最旧消息");
        assertEquals(1, countByPrefix(msgs, "【框架附件·CATALOG】"), "降级也不应丢弃附件");
    }

    @Test
    @DisplayName("二次压缩滚动演进：旧摘要与新段合并生成新摘要，仍只一条摘要")
    void add_二次压缩_滚动演进() {
        for (int i = 0; i < MAX + 5; i++) {
            addUser("消息" + i);
        }
        String firstSummary = lastSummaryCall.get();
        assertNotNull(firstSummary);
        // 继续加消息触发二次压缩
        for (int i = 0; i < TARGET + 2; i++) {
            addUser("后段" + i);
        }

        List<dev.langchain4j.data.message.ChatMessage> msgs = memory.messages();
        assertEquals(1, countByPrefix(msgs, CompressingChatMemory.SUMMARY_PREFIX), "滚动后仍只有一条摘要");
        String secondSummary = lastSummaryCall.get();
        assertTrue(secondSummary.contains(firstSummary), "新摘要应包含旧摘要内容（滚动演进）");
        assertTrue(((dev.langchain4j.data.message.UserMessage) msgs.get(0)).singleText().startsWith(CompressingChatMemory.SUMMARY_PREFIX));
    }

    @Test
    @DisplayName("摘要消息自身受保护（压缩不会把摘要当普通消息丢/压）")
    void add_摘要受保护() {
        for (int i = 0; i < MAX + 5; i++) {
            addUser("消息" + i);
        }
        // 已有一条摘要；继续加消息再压缩，摘要消息不应消失
        for (int i = 0; i < TARGET + 2; i++) {
            addUser("更多" + i);
        }
        assertEquals(1, countByPrefix(memory.messages(), CompressingChatMemory.SUMMARY_PREFIX));
    }

    @Test
    @DisplayName("clear 清空全部消息")
    void clear_清空() {
        addUser("a");
        addUser("b");
        memory.clear();
        assertTrue(memory.messages().isEmpty());
    }

    @Test
    @DisplayName("messages() 返回不可变视图，外部修改不生效")
    void messages_不可变视图() {
        addUser("a");
        List<dev.langchain4j.data.message.ChatMessage> view = memory.messages();
        assertThrows(UnsupportedOperationException.class, () -> view.add(UserMessage.from("x")));
    }

    @Test
    @DisplayName("hasAttachmentType 正确检测指定类型附件")
    void hasAttachmentType_检测指定类型() {
        memory.add(attachment(CompressingChatMemory.AttachmentType.SKILL_INSTRUCTION, "指令"));

        assertTrue(memory.hasAttachmentType(CompressingChatMemory.AttachmentType.SKILL_INSTRUCTION));
        assertFalse(memory.hasAttachmentType(CompressingChatMemory.AttachmentType.CATALOG));
    }

    @Test
    @DisplayName("工具结果消息作为普通消息参与压缩（可被归纳进摘要）")
    void add_工具结果_可被压缩() {
        memory.add(ToolExecutionResultMessage.from("t1", "httpGet", "结果1"));
        for (int i = 0; i < MAX + 3; i++) {
            addUser("消息" + i);
        }
        // 工具结果被压缩进摘要后不再单独存在（消息数受窗口上限约束）
        assertTrue(memory.messages().size() <= MAX);
    }

    @Test
    @DisplayName("全部为保护消息时不压缩（不抛异常、消息保留）")
    void add_全保护消息_不压缩() {
        List<dev.langchain4j.data.message.ChatMessage> attachOnly = new ArrayList<>();
        for (int i = 0; i < MAX + 10; i++) {
            attachOnly.add(attachment(CompressingChatMemory.AttachmentType.CATALOG, "目录" + i));
        }
        for (dev.langchain4j.data.message.ChatMessage m : attachOnly) {
            memory.add(m);
        }
        assertEquals(attachOnly.size(), memory.messages().size(), "全附件不应触发压缩");
    }
}
