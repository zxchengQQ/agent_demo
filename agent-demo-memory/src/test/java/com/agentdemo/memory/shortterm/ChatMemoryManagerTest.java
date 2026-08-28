package com.agentdemo.memory.shortterm;

import com.agentdemo.llm.registry.ModelFactory;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatMemoryManager 改造测试（agent-context-engineering Task-03，AC-E01/AC-N01）
 * <p>
 * 业务含义：压缩记忆装配（CompressingChatMemory + 默认 ChatModel 摘要）、附件 API
 * （addAttachment/hasAttachment）、压缩关闭回退 FIFO。
 * </p>
 */
class ChatMemoryManagerTest {

    private static final int WINDOW = 20;

    private ModelFactory modelFactory;
    private ChatModel chatModel;
    private MemoryCompressionProperties properties;
    private ChatMemoryManager manager;

    @BeforeEach
    void setUp() {
        modelFactory = mock(ModelFactory.class);
        chatModel = mock(ChatModel.class);
        when(modelFactory.getDefaultChatModel()).thenReturn(chatModel);
        properties = new MemoryCompressionProperties();
        properties.setEnabled(true);
        manager = new ChatMemoryManager(modelFactory, properties);
    }

    @Test
    @DisplayName("压缩开启：超限时经默认 ChatModel 生成摘要")
    void 压缩开启_超限_调用ChatModel生成摘要() {
        when(chatModel.chat(any(java.util.List.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("关键实体摘要")).build());

        for (int i = 0; i < WINDOW + 5; i++) {
            manager.addUserMessage("sess", "消息" + i);
        }

        verify(chatModel).chat(any(java.util.List.class));
        List<ChatMessage> msgs = manager.getMemory("sess").messages();
        assertTrue(msgs.size() < WINDOW + 5, "应发生压缩");
        assertTrue(msgs.stream().anyMatch(m -> m instanceof UserMessage um
                && um.hasSingleText() && um.singleText().startsWith(CompressingChatMemory.SUMMARY_PREFIX)),
                "应包含摘要消息");
    }

    @Test
    @DisplayName("压缩关闭：退化为 FIFO 窗口行为（不调用摘要模型）")
    void 压缩关闭_退化为FIFO() {
        properties.setEnabled(false);
        ChatMemoryManager fifoManager = new ChatMemoryManager(modelFactory, properties);

        for (int i = 0; i < WINDOW + 5; i++) {
            fifoManager.addUserMessage("sess", "消息" + i);
        }

        verify(chatModel, never()).chat(any(java.util.List.class));
        assertEquals(WINDOW, fifoManager.getMemory("sess").messages().size(), "FIFO 应恰好保留窗口上限");
    }

    @Test
    @DisplayName("无 LLM 装配（兼容构造）：压缩关闭，不抛异常")
    void 无装配_兼容构造_压缩关闭() {
        ChatMemoryManager bare = new ChatMemoryManager();

        for (int i = 0; i < WINDOW + 3; i++) {
            bare.addUserMessage("sess", "消息" + i);
        }
        assertEquals(WINDOW, bare.getMemory("sess").messages().size());
    }

    @Test
    @DisplayName("addAttachment 写入带类型标记的附件消息")
    void addAttachment_写入附件() {
        manager.addAttachment("sess", CompressingChatMemory.AttachmentType.SKILL_INSTRUCTION, "技能指令全文");

        List<ChatMessage> msgs = manager.getMemory("sess").messages();
        assertTrue(msgs.stream().anyMatch(m -> m instanceof UserMessage um
                && um.hasSingleText()
                && um.singleText().startsWith("【框架附件·SKILL_INSTRUCTION】")),
                "应写入带 SKILL_INSTRUCTION 类型标记的附件");
    }

    @Test
    @DisplayName("hasAttachment 正确检测指定类型附件")
    void hasAttachment_检测类型() {
        manager.addAttachment("sess", CompressingChatMemory.AttachmentType.CATALOG, "目录");

        assertTrue(manager.hasAttachment("sess", CompressingChatMemory.AttachmentType.CATALOG));
        assertFalse(manager.hasAttachment("sess", CompressingChatMemory.AttachmentType.SKILL_INSTRUCTION));
        assertFalse(manager.hasAttachment("不存在会话", CompressingChatMemory.AttachmentType.CATALOG));
    }

    @Test
    @DisplayName("压缩开启时附件消息不被压缩丢弃")
    void 压缩开启_附件不被丢弃() {
        when(chatModel.chat(any(java.util.List.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("摘要")).build());

        manager.addAttachment("sess", CompressingChatMemory.AttachmentType.CATALOG, "目录");
        for (int i = 0; i < WINDOW + 10; i++) {
            manager.addUserMessage("sess", "消息" + i);
        }

        assertTrue(manager.hasAttachment("sess", CompressingChatMemory.AttachmentType.CATALOG),
                "压缩后附件应保留");
    }

    @Test
    @DisplayName("addUserMessage/addAssistantMessage/clearMemory 行为不回归")
    void 基础API_不回归() {
        manager.addUserMessage("sess", "用户消息");
        manager.addAssistantMessage("sess", "助手回复");
        assertEquals(2, manager.getMemory("sess").messages().size());

        manager.clearMemory("sess");
        assertFalse(manager.exists("sess"), "清空后会话记忆应移除");
    }
}
