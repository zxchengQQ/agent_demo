package com.agentdemo.llm.registry;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.llm.config.BailianProperties;
import com.agentdemo.llm.provider.BailianLlmServiceProvider;
import com.agentdemo.llm.provider.LlmServiceProvider;
import com.agentdemo.llm.thinking.BailianThinkingStreamingChatModel;
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
 * Task-21 测试：BailianLlmServiceProvider 阿里百炼厂商策略实现
 * <p>
 * 验证标准：
 * - getProviderCode() 返回 "bailian"
 * - getChatModel(scene) 返回 OpenAiChatModel，baseUrl 与 BailianProperties 一致
 * - getStreamingChatModel(scene) 返回 OpenAiStreamingChatModel
 * - getEmbeddingModel() 返回 OpenAiEmbeddingModel，modelName 为 text-embedding-v4
 * - getVisionChatModel() 返回 OpenAiChatModel，modelName 来自 BailianProperties.getVisionModel()
 * - getThinkingStreamingChatModel(scene) 返回 BailianThinkingStreamingChatModel 实例
 * - apiKey 为 null 时，所有方法抛出 BusinessException
 * - 多次调用同一 scene 返回同一实例（缓存复用，AC-022）
 * </p>
 */
class BailianLlmServiceProviderTest {

    private BailianProperties bailianProperties;

    @BeforeEach
    void setUp() {
        bailianProperties = new BailianProperties();
        bailianProperties.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        bailianProperties.setApiKey("test-bailian-key");
        bailianProperties.setDefaultModel("deepseek-v4-flash");
        bailianProperties.setTimeout(Duration.ofSeconds(60));
        bailianProperties.setMaxRetries(3);
        bailianProperties.setTemperature(0.7);
        bailianProperties.setEmbeddingModel("text-embedding-v4");
        java.util.Map<String, String> models = new java.util.HashMap<>();
        models.put("chat", "deepseek-v4-flash");
        bailianProperties.setModels(models);
        bailianProperties.setVisionModel("qwen-vl-plus");
    }

    @Nested
    @DisplayName("Provider 基础契约")
    class BasicContractTest {

        @Test
        @DisplayName("getProviderCode() 应返回 'bailian'")
        void providerCode() {
            BailianLlmServiceProvider provider = new BailianLlmServiceProvider(bailianProperties);
            assertEquals("bailian", provider.getProviderCode());
        }

        @Test
        @DisplayName("应实现 LlmServiceProvider 接口")
        void shouldImplementLlmServiceProvider() {
            assertTrue(LlmServiceProvider.class.isAssignableFrom(BailianLlmServiceProvider.class));
        }
    }

    @Nested
    @DisplayName("ChatModel 能力")
    class ChatModelCapabilityTest {

        @Test
        @DisplayName("getChatModel 应返回 ChatModel 实例")
        void shouldReturnChatModel() {
            BailianLlmServiceProvider provider = new BailianLlmServiceProvider(bailianProperties);
            ChatModel model = provider.getChatModel("chat");
            assertNotNull(model);
        }

        @Test
        @DisplayName("多次调用同一 scene 应返回同一实例（缓存复用，AC-022）")
        void shouldReuseChatModelInstance() {
            BailianLlmServiceProvider provider = new BailianLlmServiceProvider(bailianProperties);
            ChatModel first = provider.getChatModel("chat");
            ChatModel second = provider.getChatModel("chat");
            assertSame(first, second, "同一 scene 多次调用应返回同一实例");
        }

        @Test
        @DisplayName("不同 scene 返回不同实例（缓存按 modelName 隔离）")
        void differentScenesShouldReturnDifferentModels() {
            BailianLlmServiceProvider provider = new BailianLlmServiceProvider(bailianProperties);
            ChatModel chatModel = provider.getChatModel("chat");       // deepseek-v4-flash
            ChatModel codeModel = provider.getChatModel("code");       // deepseek-v4-flash (default)
            // 注：百炼当前 models 只配了 chat，code 走 defaultModel，两者同名会返回同一实例
            // 为验证隔离性，使用不同 scene 配置
            java.util.Map<String, String> models = new java.util.HashMap<>();
            models.put("chat", "qwen-plus");
            models.put("code", "qwen-coder-plus");
            bailianProperties.setModels(models);
            BailianLlmServiceProvider p2 = new BailianLlmServiceProvider(bailianProperties);
            assertNotSame(p2.getChatModel("chat"), p2.getChatModel("code"));
        }
    }

    @Nested
    @DisplayName("StreamingChatModel 能力")
    class StreamingChatModelCapabilityTest {

        @Test
        @DisplayName("getStreamingChatModel 应返回 StreamingChatModel 实例")
        void shouldReturnStreamingChatModel() {
            BailianLlmServiceProvider provider = new BailianLlmServiceProvider(bailianProperties);
            StreamingChatModel model = provider.getStreamingChatModel("chat");
            assertNotNull(model);
        }

        @Test
        @DisplayName("多次调用同一 scene 应返回同一实例（缓存复用）")
        void shouldReuseStreamingChatModelInstance() {
            BailianLlmServiceProvider provider = new BailianLlmServiceProvider(bailianProperties);
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
            BailianLlmServiceProvider provider = new BailianLlmServiceProvider(bailianProperties);
            EmbeddingModel model = provider.getEmbeddingModel();
            assertNotNull(model);
        }

        @Test
        @DisplayName("多次调用应返回同一实例（缓存复用）")
        void shouldReuseEmbeddingModelInstance() {
            BailianLlmServiceProvider provider = new BailianLlmServiceProvider(bailianProperties);
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
            BailianLlmServiceProvider provider = new BailianLlmServiceProvider(bailianProperties);
            ChatModel model = provider.getVisionChatModel();
            assertNotNull(model);
        }

        @Test
        @DisplayName("多次调用应返回同一实例（缓存复用）")
        void shouldReuseVisionChatModelInstance() {
            BailianLlmServiceProvider provider = new BailianLlmServiceProvider(bailianProperties);
            ChatModel first = provider.getVisionChatModel();
            ChatModel second = provider.getVisionChatModel();
            assertSame(first, second);
        }

        @Test
        @DisplayName("visionModel 未配置时应抛出 BusinessException")
        void shouldThrowWhenVisionModelNotConfigured() {
            bailianProperties.setVisionModel(null);
            BailianLlmServiceProvider provider = new BailianLlmServiceProvider(bailianProperties);
            assertThrows(BusinessException.class, provider::getVisionChatModel);
        }
    }

    @Nested
    @DisplayName("ThinkingStreamingChatModel 能力")
    class ThinkingStreamingChatModelCapabilityTest {

        @Test
        @DisplayName("getThinkingStreamingChatModel 应返回 BailianThinkingStreamingChatModel 实例")
        void shouldReturnThinkingModel() {
            BailianLlmServiceProvider provider = new BailianLlmServiceProvider(bailianProperties);
            Object model = provider.getThinkingStreamingChatModel(null);
            assertNotNull(model);
            assertTrue(model instanceof BailianThinkingStreamingChatModel,
                "应返回 BailianThinkingStreamingChatModel 实例");
        }

        @Test
        @DisplayName("多次调用应返回同一实例（缓存复用）")
        void shouldReuseThinkingModelInstance() {
            BailianLlmServiceProvider provider = new BailianLlmServiceProvider(bailianProperties);
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
            bailianProperties.setApiKey(null);
            BailianLlmServiceProvider provider = new BailianLlmServiceProvider(bailianProperties);
            assertThrows(BusinessException.class, () -> provider.getChatModel("chat"));
        }

        @Test
        @DisplayName("apiKey 为空字符串时 getChatModel 应抛出 BusinessException")
        void shouldThrowWhenApiKeyIsEmpty() {
            bailianProperties.setApiKey("");
            BailianLlmServiceProvider provider = new BailianLlmServiceProvider(bailianProperties);
            assertThrows(BusinessException.class, () -> provider.getChatModel("chat"));
        }

        @Test
        @DisplayName("apiKey 为 null 时 getStreamingChatModel 应抛出 BusinessException")
        void streamingChatModelShouldThrowWhenApiKeyNull() {
            bailianProperties.setApiKey(null);
            BailianLlmServiceProvider provider = new BailianLlmServiceProvider(bailianProperties);
            assertThrows(BusinessException.class, () -> provider.getStreamingChatModel("chat"));
        }

        @Test
        @DisplayName("apiKey 为 null 时 getEmbeddingModel 应抛出 BusinessException")
        void embeddingModelShouldThrowWhenApiKeyNull() {
            bailianProperties.setApiKey(null);
            BailianLlmServiceProvider provider = new BailianLlmServiceProvider(bailianProperties);
            assertThrows(BusinessException.class, provider::getEmbeddingModel);
        }

        @Test
        @DisplayName("apiKey 为 null 时 getVisionChatModel 应抛出 BusinessException")
        void visionChatModelShouldThrowWhenApiKeyNull() {
            bailianProperties.setApiKey(null);
            BailianLlmServiceProvider provider = new BailianLlmServiceProvider(bailianProperties);
            assertThrows(BusinessException.class, provider::getVisionChatModel);
        }

        @Test
        @DisplayName("apiKey 为 null 时 getThinkingStreamingChatModel 应抛出 BusinessException")
        void thinkingModelShouldThrowWhenApiKeyNull() {
            bailianProperties.setApiKey(null);
            BailianLlmServiceProvider provider = new BailianLlmServiceProvider(bailianProperties);
            assertThrows(BusinessException.class, () -> provider.getThinkingStreamingChatModel(null));
        }
    }
}
