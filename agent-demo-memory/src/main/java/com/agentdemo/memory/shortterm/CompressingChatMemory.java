package com.agentdemo.memory.shortterm;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 滚动摘要压缩记忆（agent-context-engineering Task-02，AC-E01/AC-M01）
 * <p>
 * 业务含义：以滚动摘要替代 MessageWindowChatMemory 的纯 FIFO 硬截断——
 * 窗口超限时对最旧非保护消息段调用摘要生成器归纳为一条摘要，压缩至半窗（滞回）；
 * 框架附件（技能目录/指令/状态）、System 消息、摘要消息**永不压缩、永不被丢弃**。
 * 摘要生成失败时降级为 FIFO 丢弃最旧非保护消息（现状行为），对话不中断。
 * </p>
 * <p>
 * 附件与摘要均以文本前缀标记（本类常量）识别；ChatMemoryManager.addAttachment 通过
 * {@link #newAttachmentMessage(AttachmentType, String)} 构造附件消息。
 * </p>
 */
public class CompressingChatMemory implements ChatMemory {

    private static final Logger log = LoggerFactory.getLogger(CompressingChatMemory.class);

    /** 附件消息前缀（含类型标记，形如"【框架附件·CATALOG】…"） */
    public static final String ATTACHMENT_PREFIX = "【框架附件·";
    /** 摘要消息前缀 */
    public static final String SUMMARY_PREFIX = "【历史对话摘要】";
    /** 附件帧结束标记（尾部，供识别完整附件边界） */
    public static final String ATTACHMENT_SUFFIX = "】";

    /** 会话内框架附件类型 */
    public enum AttachmentType {
        /** 技能目录（会话首请求写入一次） */
        CATALOG,
        /** 技能指令（loadSkill 激活时写入一次，emit-once） */
        SKILL_INSTRUCTION,
        /** 状态变更（如技能排除） */
        STATUS
    }

    /**
     * 摘要生成回调（由 ChatMemoryManager 注入真实 ChatModel 实现，测试可 mock）
     */
    @FunctionalInterface
    public interface SummaryGenerator {
        /**
         * @param previousSummary 旧摘要正文（首次为 null，滚动演进时非 null）
         * @param messagesToCompress 本次要压缩的消息段（已按时间从旧到新排序）
         * @return 新摘要正文；返回 null/空白视为失败（触发 FIFO 降级）
         */
        String summarize(String previousSummary, List<ChatMessage> messagesToCompress);
    }

    private final Object id = new Object();
    private final int maxMessages;
    private final int compressTarget;
    private final SummaryGenerator summarizer;
    private final List<ChatMessage> messages = new ArrayList<>();

    /**
     * @param maxMessages 窗口上限（超出触发压缩）
     * @param summarizer  摘要生成回调
     */
    public CompressingChatMemory(int maxMessages, SummaryGenerator summarizer) {
        this(maxMessages, Math.max(1, maxMessages / 2), summarizer);
    }

    /**
     * @param maxMessages    窗口上限
     * @param compressTarget 压缩目标（压缩后消息数上限）
     * @param summarizer     摘要生成回调
     */
    public CompressingChatMemory(int maxMessages, int compressTarget, SummaryGenerator summarizer) {
        this.maxMessages = maxMessages;
        this.compressTarget = compressTarget;
        this.summarizer = summarizer;
    }

    @Override
    public Object id() {
        return id;
    }

    @Override
    public void add(ChatMessage message) {
        messages.add(message);
        if (messages.size() > maxMessages) {
            compactIfNeeded();
        }
    }

    @Override
    public void set(Iterable<ChatMessage> newMessages) {
        messages.clear();
        for (ChatMessage m : newMessages) {
            messages.add(m);
        }
    }

    @Override
    public List<ChatMessage> messages() {
        return Collections.unmodifiableList(messages);
    }

    @Override
    public void clear() {
        messages.clear();
    }

    /**
     * 构造框架附件消息（以文本标记帧包裹，供压缩识别与 hasAttachmentType 检测）
     *
     * @param type 附件类型
     * @param text 附件正文
     * @return 附件 UserMessage
     */
    public UserMessage newAttachmentMessage(AttachmentType type, String text) {
        return UserMessage.from(ATTACHMENT_PREFIX + type.name() + ATTACHMENT_SUFFIX + "\n" + text);
    }

    /**
     * 判断会话记忆是否已存在指定类型附件
     */
    public boolean hasAttachmentType(AttachmentType type) {
        String prefix = ATTACHMENT_PREFIX + type.name() + ATTACHMENT_SUFFIX;
        return messages.stream()
                .filter(m -> m instanceof UserMessage um && um.singleText() != null)
                .anyMatch(m -> ((UserMessage) m).singleText().startsWith(prefix));
    }

    /**
     * 是否框架附件消息（按前缀识别）
     */
    public static boolean isAttachment(ChatMessage message) {
        return message instanceof UserMessage um
                && um.singleText() != null
                && um.singleText().startsWith(ATTACHMENT_PREFIX);
    }

    /**
     * 是否摘要消息（按前缀识别）
     */
    public static boolean isSummaryMessage(ChatMessage message) {
        return message instanceof UserMessage um
                && um.singleText() != null
                && um.singleText().startsWith(SUMMARY_PREFIX);
    }

    /**
     * 是否保护消息（System / 附件 / 摘要）——永不压缩、永不被 FIFO 丢弃
     */
    private static boolean isProtected(ChatMessage message) {
        return message instanceof SystemMessage || isAttachment(message) || isSummaryMessage(message);
    }

    /**
     * 触发条件：消息数 > maxMessages（逐条 add 触发，天然形成滞回——压缩至半窗后
     * 需再积累约半个窗口才再次触发）
     */
    private void compactIfNeeded() {
        if (messages.size() <= compressTarget) {
            return;
        }
        List<ChatMessage> protectedMsgs = new ArrayList<>();
        List<ChatMessage> compressible = new ArrayList<>();
        for (ChatMessage m : messages) {
            if (isProtected(m)) {
                protectedMsgs.add(m);
            } else {
                compressible.add(m);
            }
        }
        if (compressible.isEmpty()) {
            return;
        }

        // 压缩后需满足：protected + 摘要1条 + 剩余 <= compressTarget
        int needToCompress = Math.max(1, messages.size() - compressTarget + 1);
        int toCompress = Math.min(needToCompress, compressible.size());
        List<ChatMessage> segment = new ArrayList<>(compressible.subList(0, toCompress));
        String previousSummary = protectedMsgs.stream()
                .filter(CompressingChatMemory::isSummaryMessage)
                .map(m -> ((UserMessage) m).singleText())
                .findFirst()
                .orElse(null);

        try {
            String summary = summarizer.summarize(previousSummary, segment);
            if (summary == null || summary.isBlank()) {
                throw new IllegalArgumentException("摘要生成为空");
            }
            List<ChatMessage> rebuilt = new ArrayList<>();
            // System 保持最前
            protectedMsgs.stream().filter(m -> m instanceof SystemMessage).forEach(rebuilt::add);
            // 新摘要紧随其后（概括最旧内容）
            rebuilt.add(UserMessage.from(SUMMARY_PREFIX + "\n" + summary));
            // 其余保护消息（附件）保持原相对顺序
            protectedMsgs.stream()
                    .filter(m -> !(m instanceof SystemMessage) && !isSummaryMessage(m))
                    .forEach(rebuilt::add);
            // 剩余近期消息
            rebuilt.addAll(compressible.subList(toCompress, compressible.size()));

            messages.clear();
            messages.addAll(rebuilt);
        } catch (Exception e) {
            // 降级铁律：摘要失败不得阻断对话，FIFO 丢弃最旧非保护消息（现状行为）
            log.warn("[memory-compress] 摘要生成失败，降级 FIFO 丢弃最旧可压缩消息: {}", e.getMessage());
            removeOldestCompressible();
        }
    }

    private void removeOldestCompressible() {
        for (int i = 0; i < messages.size(); i++) {
            if (!isProtected(messages.get(i))) {
                messages.remove(i);
                return;
            }
        }
    }
}
