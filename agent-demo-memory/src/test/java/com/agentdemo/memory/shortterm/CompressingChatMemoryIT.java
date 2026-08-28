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
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 记忆压缩真实接入集成测试（agent-context-engineering Task-12，AC-E01/M01）
 * <p>
 * 业务含义：验证 ChatMemoryManager → 默认 ChatModel 摘要的完整链路（Mock→真实接入的集成验证）：
 * ①摘要请求确实携带"既有摘要 + 待归纳消息"（真实模型有足够上下文生成保留实体的摘要）；
 * ②摘要模型异常时 FIFO 降级且对话不中断（AC-E01）。
 * 真实模型调用（方舟）需运行环境 API Key，本测试以 mock ChatModel 验证接入契约与请求载荷。
 * </p>
 */
class CompressingChatMemoryIT {

    private static final int WINDOW = 20;

    private ModelFactory modelFactory;
    private ChatModel chatModel;
    private ChatMemoryManager manager;

    @BeforeEach
    void setUp() {
        modelFactory = mock(ModelFactory.class);
        chatModel = mock(ChatModel.class);
        when(modelFactory.getDefaultChatModel()).thenReturn(chatModel);
        manager = new ChatMemoryManager(modelFactory, new MemoryCompressionProperties());
    }

    @Test
    @DisplayName("摘要请求载荷：包含既有摘要与待归纳消息（真实模型可据此保留关键实体）")
    void 摘要请求_包含既有摘要与待归纳消息() {
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        when(chatModel.chat(anyList())).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("订单 ORD-12345 已发货；关键实体保留")).build());

        // 注入关键实体消息，触发压缩
        manager.addUserMessage("sess", "帮我查订单 ORD-12345 的物流");
        manager.addAssistantMessage("sess", "订单 ORD-12345 已发货，预计 8 月 18 日送达");
        for (int i = 0; i < WINDOW + 2; i++) {
            manager.addUserMessage("sess", "填充消息" + i);
        }

        // 断言发给摘要模型的请求包含待归纳的关键实体（AC-M01：压缩不丢失关键信息）
        org.mockito.Mockito.verify(chatModel, org.mockito.Mockito.atLeastOnce()).chat(captor.capture());
        boolean containsEntity = captor.getAllValues().stream()
                .flatMap(List::stream)
                .filter(m -> m instanceof UserMessage um && um.hasSingleText())
                .anyMatch(m -> ((UserMessage) m).singleText().contains("ORD-12345"));
        assertTrue(containsEntity, "摘要请求应包含关键实体消息（压缩不丢失关键信息）");

        // 摘要消息进入记忆（含模型返回的实体）
        List<ChatMessage> msgs = manager.getMemory("sess").messages();
        assertTrue(msgs.stream().anyMatch(m -> m instanceof UserMessage um
                && um.hasSingleText() && um.singleText().contains("ORD-12345")));
    }

    @Test
    @DisplayName("摘要模型异常时 FIFO 降级，对话不中断（AC-E01）")
    void 摘要模型异常_降级FIFO() {
        when(chatModel.chat(anyList())).thenThrow(new RuntimeException("模型不可用"));

        for (int i = 0; i < WINDOW + 3; i++) {
            manager.addUserMessage("sess", "消息" + i);
        }

        // 降级后消息仍受限（FIFO），不抛异常
        assertTrue(manager.getMemory("sess").messages().size() <= WINDOW,
                "摘要失败应降级 FIFO，对话不中断");
    }
}
