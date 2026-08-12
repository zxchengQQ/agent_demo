package com.agentdemo.llm.registry;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.llm.config.LlmConfigStore;
import com.agentdemo.llm.config.LlmModelConfig;
import com.agentdemo.llm.config.LlmVendorConfig;
import com.agentdemo.llm.thinking.ArkThinkingStreamingChatModel;
import com.agentdemo.llm.thinking.BailianThinkingStreamingChatModel;
import com.agentdemo.llm.thinking.ThinkingStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ModelFactory 动态配置版测试（Phase 2 重写）
 * <p>
 * 验证标准：ModelFactory 从 LlmConfigStore 读取配置，按 modelId 创建/缓存模型实例。
 * 测试策略：使用真实 LlmConfigStore 设置测试数据，验证路由、缓存、异常等行为。
 * </p>
 */
class ModelFactoryTest {

    private LlmConfigStore configStore;
    private ModelFactory factory;

    /** 火山引擎 chat 模型 ID（setUp 后赋值） */
    private String arkChatModelId;
    /** 火山引擎 embedding 模型 ID */
    private String arkEmbeddingModelId;
    /** 火山引擎厂商 ID */
    private String arkVendorId;
    /** 百炼 chat 模型 ID */
    private String bailianChatModelId;
    /** 百炼厂商 ID */
    private String bailianVendorId;

    @BeforeEach
    void setUp() {
        configStore = new LlmConfigStore();

        // 添加火山引擎厂商（thinkingTrigger=enabled）
        LlmVendorConfig arkVendor = new LlmVendorConfig();
        arkVendor.setName("火山引擎方舟");
        arkVendor.setType("predefined");
        arkVendor.setBaseUrl("https://ark.cn-beijing.volces.com/api/coding/v3");
        arkVendor.setApiKey("test-ark-key");
        arkVendor.setThinkingTrigger("enabled");
        arkVendor.setTimeout(Duration.ofSeconds(60));
        arkVendor.setMaxRetries(3);
        arkVendor.setTemperature(0.7);

        LlmModelConfig arkChatModel = new LlmModelConfig();
        arkChatModel.setModelName("doubao-seed-2.0-pro");
        arkChatModel.setDisplayName("豆包Seed 2.0 Pro");
        arkChatModel.setType("chat");
        arkChatModel.setSupportsVision(false);

        LlmModelConfig arkVisionModel = new LlmModelConfig();
        arkVisionModel.setModelName("doubao-vision-pro");
        arkVisionModel.setDisplayName("豆包Vision Pro");
        arkVisionModel.setType("chat");
        arkVisionModel.setSupportsVision(true);

        LlmModelConfig arkEmbeddingModel = new LlmModelConfig();
        arkEmbeddingModel.setModelName("doubao-embedding-large-text-240915");
        arkEmbeddingModel.setDisplayName("豆包Embedding");
        arkEmbeddingModel.setType("embedding");

        arkVendor.setModels(List.of(arkChatModel, arkVisionModel, arkEmbeddingModel));
        configStore.addVendor(arkVendor);

        // 记录 ark 厂商和模型 ID（addVendor 后自动生成）
        arkVendorId = arkVendor.getId();
        arkChatModelId = arkChatModel.getId();
        arkEmbeddingModelId = arkEmbeddingModel.getId();

        // 添加阿里百炼厂商（thinkingTrigger=none）
        LlmVendorConfig bailianVendor = new LlmVendorConfig();
        bailianVendor.setName("阿里百炼");
        bailianVendor.setType("predefined");
        bailianVendor.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        bailianVendor.setApiKey("test-bailian-key");
        bailianVendor.setThinkingTrigger("none");
        bailianVendor.setTimeout(Duration.ofSeconds(60));
        bailianVendor.setMaxRetries(3);
        bailianVendor.setTemperature(0.7);

        LlmModelConfig bailianChatModel = new LlmModelConfig();
        bailianChatModel.setModelName("deepseek-v4-flash");
        bailianChatModel.setDisplayName("DeepSeek V4 Flash");
        bailianChatModel.setType("chat");
        bailianChatModel.setSupportsVision(false);

        bailianVendor.setModels(List.of(bailianChatModel));
        configStore.addVendor(bailianVendor);

        bailianVendorId = bailianVendor.getId();
        bailianChatModelId = bailianChatModel.getId();

        factory = new ModelFactory(configStore);
    }

    // ========== 构造器验证 ==========

    @Nested
    @DisplayName("构造器注入 LlmConfigStore")
    class ConstructorTest {

        @Test
        @DisplayName("构造器注入 LlmConfigStore 能正常实例化")
        void shouldInstantiateWithConfigStore() {
            assertNotNull(factory, "ModelFactory(LlmConfigStore) 应能正常实例化");
        }
    }

    // ========== ChatModel 获取 ==========

    @Nested
    @DisplayName("ChatModel 获取")
    class ChatModelTest {

        @Test
        @DisplayName("getChatModelByModelId(validChatModelId) 返回非 null ChatModel")
        void shouldReturnChatModelForValidId() {
            ChatModel model = factory.getChatModelByModelId(arkChatModelId);
            assertNotNull(model, "有效的 chat modelId 应返回非 null ChatModel");
        }

        @Test
        @DisplayName("getChatModelByModelId(不存在id) 抛出 BusinessException(LLM_MODEL_NOT_FOUND)")
        void shouldThrowWhenModelIdNotFound() {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> factory.getChatModelByModelId("non-existent-id"),
                    "不存在的 modelId 应抛出 BusinessException");
            assertEquals(ErrorCode.LLM_MODEL_NOT_FOUND, ex.getErrorCode(),
                    "错误码应为 LLM_MODEL_NOT_FOUND");
        }

        @Test
        @DisplayName("getChatModelByModelId(embeddingModelId) 抛出 BusinessException(LLM_MODEL_NOT_FOUND)（非 chat 类型）")
        void shouldThrowWhenModelTypeIsNotChat() {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> factory.getChatModelByModelId(arkEmbeddingModelId),
                    "embedding 类型的 modelId 应抛出 BusinessException");
            assertEquals(ErrorCode.LLM_MODEL_NOT_FOUND, ex.getErrorCode(),
                    "非 chat 类型应报 LLM_MODEL_NOT_FOUND");
        }

        @Test
        @DisplayName("getDefaultChatModel() 返回第一个 chat 模型")
        void shouldReturnDefaultChatModel() {
            ChatModel model = factory.getDefaultChatModel();
            assertNotNull(model, "应有可用的默认 chat 模型");
        }

        @Test
        @DisplayName("getDefaultChatModel() 无 chat 模型时抛出 BusinessException(LLM_NO_CHAT_MODEL)")
        void shouldThrowWhenNoChatModel() {
            LlmConfigStore emptyStore = new LlmConfigStore();
            ModelFactory emptyFactory = new ModelFactory(emptyStore);

            BusinessException ex = assertThrows(BusinessException.class,
                    emptyFactory::getDefaultChatModel,
                    "无 chat 模型时应抛出 BusinessException");
            assertEquals(ErrorCode.LLM_NO_CHAT_MODEL, ex.getErrorCode(),
                    "错误码应为 LLM_NO_CHAT_MODEL");
        }
    }

    // ========== StreamingChatModel 获取 ==========

    @Nested
    @DisplayName("StreamingChatModel 获取")
    class StreamingChatModelTest {

        @Test
        @DisplayName("getStreamingChatModelByModelId(validChatModelId) 返回非 null")
        void shouldReturnStreamingChatModelForValidId() {
            StreamingChatModel model = factory.getStreamingChatModelByModelId(arkChatModelId);
            assertNotNull(model, "有效的 chat modelId 应返回非 null StreamingChatModel");
        }

        @Test
        @DisplayName("getDefaultStreamingChatModel() 返回非 null")
        void shouldReturnDefaultStreamingChatModel() {
            StreamingChatModel model = factory.getDefaultStreamingChatModel();
            assertNotNull(model, "应有可用的默认 StreamingChatModel");
        }
    }

    // ========== ThinkingStreamingChatModel 获取 ==========

    @Nested
    @DisplayName("ThinkingStreamingChatModel 获取")
    class ThinkingStreamingChatModelTest {

        @Test
        @DisplayName("getThinkingStreamingChatModelByModelId(validChatModelId) 按 thinkingTrigger 选择实现类")
        void shouldReturnThinkingModelForValidId() {
            ThinkingStreamingChatModel model = factory.getThinkingStreamingChatModelByModelId(arkChatModelId);
            assertNotNull(model, "有效的 chat modelId 应返回非 null ThinkingStreamingChatModel");
        }

        @Test
        @DisplayName("thinkingTrigger=enabled 时返回 ArkThinkingStreamingChatModel 实例")
        void shouldReturnArkThinkingModelWhenTriggerEnabled() {
            ThinkingStreamingChatModel model = factory.getThinkingStreamingChatModelByModelId(arkChatModelId);
            assertTrue(model instanceof ArkThinkingStreamingChatModel,
                    "thinkingTrigger=enabled 时应返回 ArkThinkingStreamingChatModel 实例");
        }

        @Test
        @DisplayName("thinkingTrigger=none 时返回 BailianThinkingStreamingChatModel 实例")
        void shouldReturnBailianThinkingModelWhenTriggerNone() {
            ThinkingStreamingChatModel model = factory.getThinkingStreamingChatModelByModelId(bailianChatModelId);
            assertTrue(model instanceof BailianThinkingStreamingChatModel,
                    "thinkingTrigger=none 时应返回 BailianThinkingStreamingChatModel 实例");
        }

        @Test
        @DisplayName("getDefaultThinkingStreamingChatModel() 返回非 null")
        void shouldReturnDefaultThinkingStreamingChatModel() {
            ThinkingStreamingChatModel model = factory.getDefaultThinkingStreamingChatModel();
            assertNotNull(model, "应有可用的默认 ThinkingStreamingChatModel");
        }
    }

    // ========== EmbeddingModel 获取 ==========

    @Nested
    @DisplayName("EmbeddingModel 获取")
    class EmbeddingModelTest {

        @Test
        @DisplayName("getEmbeddingModel() 返回第一个 embedding 模型")
        void shouldReturnEmbeddingModel() {
            EmbeddingModel model = factory.getEmbeddingModel();
            assertNotNull(model, "应有可用的 EmbeddingModel");
        }

        @Test
        @DisplayName("getEmbeddingModel() 无 embedding 模型时抛出 BusinessException(LLM_NO_EMBEDDING_MODEL)")
        void shouldThrowWhenNoEmbeddingModel() {
            LlmConfigStore emptyStore = new LlmConfigStore();
            ModelFactory emptyFactory = new ModelFactory(emptyStore);

            BusinessException ex = assertThrows(BusinessException.class,
                    emptyFactory::getEmbeddingModel,
                    "无 embedding 模型时应抛出 BusinessException");
            assertEquals(ErrorCode.LLM_NO_EMBEDDING_MODEL, ex.getErrorCode(),
                    "错误码应为 LLM_NO_EMBEDDING_MODEL");
        }
    }

    // ========== 缓存复用 ==========

    @Nested
    @DisplayName("缓存复用")
    class CacheReuseTest {

        @Test
        @DisplayName("相同 modelId 第二次调用返回同一缓存实例（assertSame）")
        void shouldReturnSameCachedInstance() {
            ChatModel first = factory.getChatModelByModelId(arkChatModelId);
            ChatModel second = factory.getChatModelByModelId(arkChatModelId);
            assertSame(first, second, "相同 modelId 多次调用应返回同一缓存实例");
        }

        @Test
        @DisplayName("clearCacheForVendor(vendorId) 清除指定厂商缓存")
        void shouldClearCacheForVendor() {
            // 先创建缓存
            ChatModel arkModel = factory.getChatModelByModelId(arkChatModelId);
            assertNotNull(arkModel);

            // 清除 ark 厂商缓存
            factory.clearCacheForVendor(arkVendorId);

            // 再次获取应创建新实例
            ChatModel arkModelAfterClear = factory.getChatModelByModelId(arkChatModelId);
            // 新实例不应与旧实例相同（缓存已清除）
            assertTrue(arkModel != arkModelAfterClear, "清除缓存后应创建新实例");
        }

        @Test
        @DisplayName("clearAllCache() 清除全部缓存")
        void shouldClearAllCache() {
            // 先创建缓存
            ChatModel arkModel = factory.getChatModelByModelId(arkChatModelId);
            ChatModel bailianModel = factory.getChatModelByModelId(bailianChatModelId);
            assertNotNull(arkModel);
            assertNotNull(bailianModel);

            // 清除全部缓存
            factory.clearAllCache();

            // 再次获取应创建新实例
            ChatModel arkModelAfterClear = factory.getChatModelByModelId(arkChatModelId);
            ChatModel bailianModelAfterClear = factory.getChatModelByModelId(bailianChatModelId);
            assertTrue(arkModel != arkModelAfterClear, "清除全部缓存后 ark 应创建新实例");
            assertTrue(bailianModel != bailianModelAfterClear, "清除全部缓存后 bailian 应创建新实例");
        }
    }

    // ========== VisionChatModel 获取 ==========

    @Nested
    @DisplayName("VisionChatModel 获取")
    class VisionChatModelTest {

        @Test
        @DisplayName("getVisionChatModel() 返回第一个 supportsVision=true 的 chat 模型")
        void shouldReturnVisionChatModel() {
            ChatModel model = factory.getVisionChatModel();
            assertNotNull(model, "应有可用的 VisionChatModel");
        }

        @Test
        @DisplayName("getVisionChatModel() 无视觉模型时抛出 BusinessException(LLM_MODEL_NOT_CONFIGURED)")
        void shouldThrowWhenNoVisionModel() {
            // 创建仅含百炼厂商（无 vision 模型）的配置
            LlmConfigStore noVisionStore = new LlmConfigStore();
            LlmVendorConfig vendor = new LlmVendorConfig();
            vendor.setName("无视觉厂商");
            vendor.setType("custom");
            vendor.setBaseUrl("https://example.com/v1");
            vendor.setApiKey("test-key");
            vendor.setThinkingTrigger("none");

            LlmModelConfig chatModel = new LlmModelConfig();
            chatModel.setModelName("test-chat-model");
            chatModel.setDisplayName("Test Chat");
            chatModel.setType("chat");
            chatModel.setSupportsVision(false);

            vendor.setModels(List.of(chatModel));
            noVisionStore.addVendor(vendor);

            ModelFactory noVisionFactory = new ModelFactory(noVisionStore);

            BusinessException ex = assertThrows(BusinessException.class,
                    noVisionFactory::getVisionChatModel,
                    "无视觉模型时应抛出 BusinessException");
            assertEquals(ErrorCode.LLM_MODEL_NOT_CONFIGURED, ex.getErrorCode(),
                    "错误码应为 LLM_MODEL_NOT_CONFIGURED");
        }
    }
}
