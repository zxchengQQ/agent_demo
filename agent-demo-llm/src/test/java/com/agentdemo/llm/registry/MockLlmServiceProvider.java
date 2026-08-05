package com.agentdemo.llm.registry;

import com.agentdemo.llm.capability.VisionChatModelProvider;
import com.agentdemo.llm.provider.LlmServiceProvider;
import com.agentdemo.llm.thinking.ThinkingStreamHandler;
import com.agentdemo.llm.thinking.ThinkingStreamingChatModel;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用 Mock LLM 服务商策略实现（CR-002 Task-24 扩展性验证）
 * <p>
 * 业务含义：模拟"新增第三个厂商"的场景，验证 ModelFactory 通过注册表路由
 * 即可接入新厂商，核心代码零修改（对应 AC-018）。
 * </p>
 * <p>
 * 设计要点：
 * <ul>
 *   <li>仅实现 {@link LlmServiceProvider} 4 个核心能力接口，故意不实现 {@link VisionChatModelProvider}
 *       （CR-002 Task-24 ISP 修正后视觉能力为可选实现），用于验证能力缺失时抛出
 *       {@link UnsupportedCapabilityException}（AC-021）</li>
 *   <li>各能力方法返回简单 Mock 对象或抛异常，仅用于路由验证，不进行真实 LLM 调用</li>
 *   <li>通过计数器记录方法调用次数，便于测试断言</li>
 * </ul>
 * </p>
 */
public class MockLlmServiceProvider implements LlmServiceProvider {

    /**
     * 厂商代码
     */
    public static final String PROVIDER_CODE = "mock";

    /**
     * getChatModel 调用次数计数器（验证路由命中）
     */
    private final AtomicInteger chatModelCallCount = new AtomicInteger(0);

    /**
     * getThinkingStreamingChatModel 调用次数计数器
     */
    private final AtomicInteger thinkingModelCallCount = new AtomicInteger(0);

    @Override
    public String getProviderCode() {
        return PROVIDER_CODE;
    }

    @Override
    public ChatModel getChatModel(String scene) {
        chatModelCallCount.incrementAndGet();
        // 返回 null 不便于断言，抛出特征异常标识路由命中（测试用）
        throw new UnsupportedOperationException("MOCK_CHAT_MODEL_ROUTED scene=" + scene);
    }

    @Override
    public StreamingChatModel getStreamingChatModel(String scene) {
        throw new UnsupportedOperationException("MOCK_STREAMING_MODEL_ROUTED scene=" + scene);
    }

    @Override
    public ThinkingStreamingChatModel getThinkingStreamingChatModel(String scene) {
        thinkingModelCallCount.incrementAndGet();
        // 返回一个简单的 Mock 实例，便于 instanceof 断言
        return new ThinkingStreamingChatModel() {
            @Override
            public void stream(List<ChatMessage> messages, ThinkingStreamHandler handler) {
                // 简单空实现，仅用于路由验证
            }

            @Override
            public void stream(List<ChatMessage> messages, String toolsJson, ThinkingStreamHandler handler) {
                // 简单空实现，仅用于路由验证
            }
        };
    }

    @Override
    public EmbeddingModel getEmbeddingModel() {
        throw new UnsupportedOperationException("MOCK_EMBEDDING_MODEL_ROUTED");
    }

    // 注意：故意不实现 getVisionChatModel()，用于验证能力缺失场景

    /**
     * 获取 getChatModel 调用次数
     */
    public int getChatModelCallCount() {
        return chatModelCallCount.get();
    }

    /**
     * 获取 getThinkingStreamingChatModel 调用次数
     */
    public int getThinkingModelCallCount() {
        return thinkingModelCallCount.get();
    }
}
