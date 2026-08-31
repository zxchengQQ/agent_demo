package com.agentdemo.llm.registry;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.llm.config.LlmConfigStore;
import com.agentdemo.llm.config.LlmModelConfig;
import com.agentdemo.llm.config.LlmVendorConfig;
import com.agentdemo.llm.thinking.ArkThinkingStreamingChatModel;
import com.agentdemo.llm.thinking.BailianThinkingStreamingChatModel;
import com.agentdemo.llm.thinking.ThinkingStreamingChatModel;
import com.agentdemo.llm.thinking.TracingThinkingStreamingChatModel;
import com.agentdemo.observability.NoopTraceCollector;
import com.agentdemo.observability.TraceCollector;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

        factory = new ModelFactory(configStore, new NoopTraceCollector());
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
        @DisplayName("getChatModelByModelId(modelName) 兜底按 modelName 解析（预置工作流模板场景）")
        void shouldResolveByModelNameWhenIdMiss() {
            // 预置工作流模板的 AgentDefinition.modelId 引用 API 模型名（如 glm-5.2/doubao-seed-2.0-pro）
            // 而非记录 UUID，id 未命中时应按 modelName 兜底解析，否则模板 Agent 构建必然报"模型不存在"
            ChatModel model = factory.getChatModelByModelId("doubao-seed-2.0-pro");
            assertNotNull(model, "按 modelName 查找应返回非 null ChatModel");
        }

        @Test
        @DisplayName("getChatModelByModelId(embedding modelName) 兜底不匹配非 chat 类型")
        void shouldNotResolveEmbeddingModelNameForChat() {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> factory.getChatModelByModelId("doubao-embedding-large-text-240915"),
                    "embedding 模型名查 chat 不应命中");
            assertEquals(ErrorCode.LLM_MODEL_NOT_FOUND, ex.getErrorCode());
        }

        @Test
        @DisplayName("getDefaultChatModel() 无 chat 模型时抛出 BusinessException(LLM_NO_CHAT_MODEL)")
        void shouldThrowWhenNoChatModel() {
            LlmConfigStore emptyStore = new LlmConfigStore();
            ModelFactory emptyFactory = new ModelFactory(emptyStore, new NoopTraceCollector());

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
        @DisplayName("getStreamingChatModelByModelId(modelName) 兜底按 modelName 解析（预置工作流模板场景）")
        void shouldResolveByModelNameWhenIdMiss() {
            // 复现 BUG：预置模板 modelId="glm-5.2"（modelName 语义）按 UUID 查找必失败
            StreamingChatModel model = factory.getStreamingChatModelByModelId("doubao-seed-2.0-pro");
            assertNotNull(model, "按 modelName 查找应返回非 null StreamingChatModel");
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
        @DisplayName("getThinkingStreamingChatModelByModelId(modelName) 兜底按 modelName 解析（预置工作流模板场景）")
        void shouldResolveByModelNameWhenIdMiss() {
            ThinkingStreamingChatModel model = factory.getThinkingStreamingChatModelByModelId("doubao-seed-2.0-pro");
            assertNotNull(model, "按 modelName 查找应返回非 null ThinkingStreamingChatModel");
        }

        @Test
        @DisplayName("默认关（Noop）时 thinkingTrigger=enabled 返回裸 Ark 实现（零开销门控）")
        void shouldReturnArkThinkingModelWhenTriggerEnabled() {
            ThinkingStreamingChatModel model = factory.getThinkingStreamingChatModelByModelId(arkChatModelId);
            assertTrue(model instanceof ArkThinkingStreamingChatModel,
                    "Noop（默认关）时应返回裸 Ark 实现，不包装装饰器（AC-S02 零开销）");
        }

        @Test
        @DisplayName("默认关（Noop）时 thinkingTrigger=none 返回裸 Bailian 实现（零开销门控）")
        void shouldReturnBailianThinkingModelWhenTriggerNone() {
            ThinkingStreamingChatModel model = factory.getThinkingStreamingChatModelByModelId(bailianChatModelId);
            assertTrue(model instanceof BailianThinkingStreamingChatModel,
                    "Noop（默认关）时应返回裸 Bailian 实现，不包装装饰器（AC-S02 零开销）");
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
            ModelFactory emptyFactory = new ModelFactory(emptyStore, new NoopTraceCollector());

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

            ModelFactory noVisionFactory = new ModelFactory(noVisionStore, new NoopTraceCollector());

            BusinessException ex = assertThrows(BusinessException.class,
                    noVisionFactory::getVisionChatModel,
                    "无视觉模型时应抛出 BusinessException");
            assertEquals(ErrorCode.LLM_MODEL_NOT_CONFIGURED, ex.getErrorCode(),
                    "错误码应为 LLM_MODEL_NOT_CONFIGURED");
        }
    }

    // ========== 可观测埋点挂载（langsmith-observability 审查修复） ==========

    @Nested
    @DisplayName("LangSmith 启用/关闭时埋点挂载")
    class ObservabilityEnabledTest {

        private final TraceCollector enabledCollector = mock(TraceCollector.class);

        @BeforeEach
        void setUpEnabled() {
            // 业务含义：模拟 LangSmith 启用（isEnabled=true），验证 listener/装饰器挂载
            when(enabledCollector.isEnabled()).thenReturn(true);
        }

        private ModelFactory enabledFactory() {
            return new ModelFactory(configStore, enabledCollector);
        }

        @Test
        @DisplayName("启用时 OpenAiChatModel 挂载 listener（listeners 非空）")
        void enabledChatModel_hasListenerAttached() {
            ChatModel model = enabledFactory().getChatModelByModelId(arkChatModelId);
            assertTrue(model instanceof OpenAiChatModel, "应返回 OpenAiChatModel");
            assertTrue(!((OpenAiChatModel) model).listeners().isEmpty(),
                    "启用时 OpenAI 系 chat 模型应挂载 TraceChatModelListener");
        }

        @Test
        @DisplayName("启用时 OpenAiStreamingChatModel 挂载 listener")
        void enabledStreamingModel_hasListenerAttached() {
            StreamingChatModel model = enabledFactory().getStreamingChatModelByModelId(arkChatModelId);
            assertTrue(model instanceof OpenAiStreamingChatModel, "应返回 OpenAiStreamingChatModel");
            assertTrue(!((OpenAiStreamingChatModel) model).listeners().isEmpty(),
                    "启用时 streaming 模型应挂载 TraceChatModelListener");
        }

        @Test
        @DisplayName("缓存重建后 listener 仍挂载（clearCacheForVendor 后重新构建）")
        void cacheRebuild_keepsListenerAttached() {
            ModelFactory f = enabledFactory();
            OpenAiChatModel before = (OpenAiChatModel) f.getChatModelByModelId(arkChatModelId);
            assertTrue(!before.listeners().isEmpty());

            // 清空该厂商缓存后重建：listener 应随构建再次挂载
            f.clearCacheForVendor(arkVendorId);
            OpenAiChatModel after = (OpenAiChatModel) f.getChatModelByModelId(arkChatModelId);
            assertTrue(!after.listeners().isEmpty(), "缓存重建后 listener 应仍在");
            assertTrue(before != after, "缓存重建应返回新实例");
        }

        @Test
        @DisplayName("启用时 Thinking 模型包装追踪装饰器")
        void enabledThinkingModel_wrappedInDecorator() {
            ThinkingStreamingChatModel model = enabledFactory().getThinkingStreamingChatModelByModelId(arkChatModelId);
            assertTrue(model instanceof TracingThinkingStreamingChatModel,
                    "启用时应返回 TracingThinkingStreamingChatModel（包装 Ark 实现）");
        }

        @Test
        @DisplayName("默认关（Noop）时 chat 模型无 listener（零开销门控）")
        void disabledChatModel_noListenerAttached() {
            ChatModel model = factory.getChatModelByModelId(arkChatModelId);
            assertTrue(model instanceof OpenAiChatModel, "应返回 OpenAiChatModel");
            assertTrue(((OpenAiChatModel) model).listeners().isEmpty(),
                    "默认关（Noop）时不应挂载 listener（AC-S02 零开销）");
        }
    }
}
