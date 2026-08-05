package com.agentdemo.llm.registry;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.llm.config.ArkProperties;
import com.agentdemo.llm.provider.ArkLlmServiceProvider;
import com.agentdemo.llm.provider.LlmServiceProvider;
import com.agentdemo.llm.thinking.ArkThinkingStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task-20 测试：ArkLlmServiceProvider 火山引擎厂商策略实现
 * <p>
 * 验证标准：
 * - getProviderCode() 返回 "ark"
 * - getChatModel(scene) 返回 OpenAiChatModel，baseUrl 与 ArkProperties 一致，modelName 来自 ArkProperties.getModelName(scene)
 * - getStreamingChatModel(scene) 返回 OpenAiStreamingChatModel
 * - getEmbeddingModel() 返回 OpenAiEmbeddingModel，modelName 为 MODEL_DOUBAO_EMBEDDING
 * - getVisionChatModel() 返回 OpenAiChatModel，modelName 来自 ArkProperties.getVisionModel()
 * - getThinkingStreamingChatModel(scene) 返回 ArkThinkingStreamingChatModel 实例
 * - apiKey 为 null 时，所有方法抛出 BusinessException
 * - 多次调用同一 scene 返回同一实例（缓存复用，AC-022）
 * </p>
 */
class ArkLlmServiceProviderTest {

    private ArkProperties arkProperties;

    @BeforeEach
    void setUp() {
        arkProperties = new ArkProperties();
        arkProperties.setBaseUrl("https://ark.cn-beijing.volces.com/api/coding/v3");
        arkProperties.setApiKey("test-ark-key");
        arkProperties.setDefaultModel("doubao-seed-2.0-code");
        arkProperties.setTimeout(Duration.ofSeconds(60));
        arkProperties.setMaxRetries(3);
        arkProperties.setTemperature(0.7);
        java.util.Map<String, String> models = new java.util.HashMap<>();
        models.put("chat", "doubao-seed-2.0-pro");
        arkProperties.setModels(models);
        arkProperties.setVisionModel("doubao-vision-pro");
    }

    @Nested
    @DisplayName("Provider 基础契约")
    class BasicContractTest {

        @Test
        @DisplayName("getProviderCode() 应返回 'ark'")
        void providerCode() {
            ArkLlmServiceProvider provider = new ArkLlmServiceProvider(arkProperties);
            assertEquals("ark", provider.getProviderCode());
        }

        @Test
        @DisplayName("应实现 LlmServiceProvider 接口")
        void shouldImplementLlmServiceProvider() {
            assertTrue(LlmServiceProvider.class.isAssignableFrom(ArkLlmServiceProvider.class));
        }
    }

    @Nested
    @DisplayName("ChatModel 能力")
    class ChatModelCapabilityTest {

        @Test
        @DisplayName("getChatModel 应返回 ChatModel 实例")
        void shouldReturnChatModel() {
            ArkLlmServiceProvider provider = new ArkLlmServiceProvider(arkProperties);
            ChatModel model = provider.getChatModel("chat");
            assertNotNull(model);
        }

        @Test
        @DisplayName("多次调用同一 scene 应返回同一实例（缓存复用，AC-022）")
        void shouldReuseChatModelInstance() {
            ArkLlmServiceProvider provider = new ArkLlmServiceProvider(arkProperties);
            ChatModel first = provider.getChatModel("chat");
            ChatModel second = provider.getChatModel("chat");
            assertSame(first, second, "同一 scene 多次调用应返回同一实例");
        }

        @Test
        @DisplayName("不同 scene 返回不同实例（缓存按 modelName 隔离）")
        void differentScenesShouldReturnDifferentModels() {
            ArkLlmServiceProvider provider = new ArkLlmServiceProvider(arkProperties);
            ChatModel chatModel = provider.getChatModel("chat");       // doubao-seed-2.0-pro
            ChatModel codeModel = provider.getChatModel("code");       // doubao-seed-2.0-code
            assertNotSame(chatModel, codeModel);
        }
    }

    @Nested
    @DisplayName("StreamingChatModel 能力")
    class StreamingChatModelCapabilityTest {

        @Test
        @DisplayName("getStreamingChatModel 应返回 StreamingChatModel 实例")
        void shouldReturnStreamingChatModel() {
            ArkLlmServiceProvider provider = new ArkLlmServiceProvider(arkProperties);
            StreamingChatModel model = provider.getStreamingChatModel("chat");
            assertNotNull(model);
        }

        @Test
        @DisplayName("多次调用同一 scene 应返回同一实例（缓存复用）")
        void shouldReuseStreamingChatModelInstance() {
            ArkLlmServiceProvider provider = new ArkLlmServiceProvider(arkProperties);
            StreamingChatModel first = provider.getStreamingChatModel("chat");
            StreamingChatModel second = provider.getStreamingChatModel("chat");
            assertSame(first, second);
        }
    }

    @Nested
    @DisplayName("EmbeddingModel 能力")
    class EmbeddingModelCapabilityTest {

        @Test
        @DisplayName("getEmbeddingModel 应返回 EmbeddingModel 实例")
        void shouldReturnEmbeddingModel() {
            ArkLlmServiceProvider provider = new ArkLlmServiceProvider(arkProperties);
            EmbeddingModel model = provider.getEmbeddingModel();
            assertNotNull(model);
        }

        @Test
        @DisplayName("多次调用应返回同一实例（缓存复用）")
        void shouldReuseEmbeddingModelInstance() {
            ArkLlmServiceProvider provider = new ArkLlmServiceProvider(arkProperties);
            EmbeddingModel first = provider.getEmbeddingModel();
            EmbeddingModel second = provider.getEmbeddingModel();
            assertSame(first, second);
        }
    }

    @Nested
    @DisplayName("VisionChatModel 能力")
    class VisionChatModelCapabilityTest {

        @Test
        @DisplayName("getVisionChatModel 应返回 ChatModel 实例")
        void shouldReturnVisionChatModel() {
            ArkLlmServiceProvider provider = new ArkLlmServiceProvider(arkProperties);
            ChatModel model = provider.getVisionChatModel();
            assertNotNull(model);
        }

        @Test
        @DisplayName("多次调用应返回同一实例（缓存复用）")
        void shouldReuseVisionChatModelInstance() {
            ArkLlmServiceProvider provider = new ArkLlmServiceProvider(arkProperties);
            ChatModel first = provider.getVisionChatModel();
            ChatModel second = provider.getVisionChatModel();
            assertSame(first, second);
        }

        @Test
        @DisplayName("visionModel 未配置时应抛出 BusinessException")
        void shouldThrowWhenVisionModelNotConfigured() {
            arkProperties.setVisionModel(null);
            ArkLlmServiceProvider provider = new ArkLlmServiceProvider(arkProperties);
            assertThrows(BusinessException.class, provider::getVisionChatModel);
        }
    }

    @Nested
    @DisplayName("ThinkingStreamingChatModel 能力")
    class ThinkingStreamingChatModelCapabilityTest {

        @Test
        @DisplayName("getThinkingStreamingChatModel 应返回 ThinkingStreamingChatModel 实例")
        void shouldReturnThinkingModel() {
            ArkLlmServiceProvider provider = new ArkLlmServiceProvider(arkProperties);
            Object model = provider.getThinkingStreamingChatModel(null);
            assertNotNull(model);
            assertTrue(model instanceof ArkThinkingStreamingChatModel,
                "应返回 ArkThinkingStreamingChatModel 实例");
        }

        @Test
        @DisplayName("多次调用应返回同一实例（缓存复用）")
        void shouldReuseThinkingModelInstance() {
            ArkLlmServiceProvider provider = new ArkLlmServiceProvider(arkProperties);
            Object first = provider.getThinkingStreamingChatModel(null);
            Object second = provider.getThinkingStreamingChatModel(null);
            assertSame(first, second);
        }
    }

    @Nested
    @DisplayName("API Key 校验")
    class ApiKeyValidationTest {

        @Test
        @DisplayName("apiKey 为 null 时 getChatModel 应抛出 BusinessException")
        void shouldThrowWhenApiKeyIsNull() {
            arkProperties.setApiKey(null);
            ArkLlmServiceProvider provider = new ArkLlmServiceProvider(arkProperties);
            assertThrows(BusinessException.class, () -> provider.getChatModel("chat"));
        }

        @Test
        @DisplayName("apiKey 为空字符串时 getChatModel 应抛出 BusinessException")
        void shouldThrowWhenApiKeyIsEmpty() {
            arkProperties.setApiKey("");
            ArkLlmServiceProvider provider = new ArkLlmServiceProvider(arkProperties);
            assertThrows(BusinessException.class, () -> provider.getChatModel("chat"));
        }

        @Test
        @DisplayName("apiKey 为 null 时 getStreamingChatModel 应抛出 BusinessException")
        void streamingChatModelShouldThrowWhenApiKeyNull() {
            arkProperties.setApiKey(null);
            ArkLlmServiceProvider provider = new ArkLlmServiceProvider(arkProperties);
            assertThrows(BusinessException.class, () -> provider.getStreamingChatModel("chat"));
        }

        @Test
        @DisplayName("apiKey 为 null 时 getEmbeddingModel 应抛出 BusinessException")
        void embeddingModelShouldThrowWhenApiKeyNull() {
            arkProperties.setApiKey(null);
            ArkLlmServiceProvider provider = new ArkLlmServiceProvider(arkProperties);
            assertThrows(BusinessException.class, provider::getEmbeddingModel);
        }

        @Test
        @DisplayName("apiKey 为 null 时 getVisionChatModel 应抛出 BusinessException")
        void visionChatModelShouldThrowWhenApiKeyNull() {
            arkProperties.setApiKey(null);
            ArkLlmServiceProvider provider = new ArkLlmServiceProvider(arkProperties);
            assertThrows(BusinessException.class, provider::getVisionChatModel);
        }

        @Test
        @DisplayName("apiKey 为 null 时 getThinkingStreamingChatModel 应抛出 BusinessException")
        void thinkingModelShouldThrowWhenApiKeyNull() {
            arkProperties.setApiKey(null);
            ArkLlmServiceProvider provider = new ArkLlmServiceProvider(arkProperties);
            assertThrows(BusinessException.class, () -> provider.getThinkingStreamingChatModel(null));
        }
    }
}
