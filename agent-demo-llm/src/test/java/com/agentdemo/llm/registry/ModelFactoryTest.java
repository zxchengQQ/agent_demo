package com.agentdemo.llm.registry;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.llm.config.ArkProperties;
import com.agentdemo.llm.config.BailianProperties;
import com.agentdemo.llm.config.LlmProperties;
import com.agentdemo.llm.config.LlmProvider;
import com.agentdemo.llm.exception.UnsupportedCapabilityException;
import com.agentdemo.llm.provider.ArkLlmServiceProvider;
import com.agentdemo.llm.provider.BailianLlmServiceProvider;
import com.agentdemo.llm.provider.LlmServiceProvider;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ModelFactory 注册表路由模式测试（CR-002 Task-24 重写）
 * <p>
 * 验证标准来源：Task-24 验证标准
 * 关联 AC：AC-018（新增厂商零核心改动）、AC-019（无厂商硬编码分支）、
 *         AC-021（能力缺失明确报错）、AC-022（缓存复用语义不变）、AC-014（缓存复用）
 * </p>
 * <p>
 * 测试策略：
 * <ul>
 *   <li>构造真实 Provider 实例（ArkLlmServiceProvider、BailianLlmServiceProvider）注入 ModelFactory</li>
 *   <li>扩展性验证使用 {@link MockLlmServiceProvider} 模拟新增厂商，并通过 LlmProperties 匿名子类
 *       覆盖 getProviderCode() 返回 "mock"，绕过 LlmProvider 枚举不可扩展的限制</li>
 *   <li>所有断言基于"路由命中"而非"模型实例内部字段"，避免对 langchain4j 实现细节的耦合</li>
 * </ul>
 * </p>
 */
class ModelFactoryTest {

    private ArkProperties arkProperties;
    private BailianProperties bailianProperties;
    private LlmProperties llmProperties;
    private ArkLlmServiceProvider arkProvider;
    private BailianLlmServiceProvider bailianProvider;

    @BeforeEach
    void setUp() {
        arkProperties = new ArkProperties();
        arkProperties.setBaseUrl("https://ark.cn-beijing.volces.com/api/coding/v3");
        arkProperties.setApiKey("test-ark-api-key");
        arkProperties.setDefaultModel("doubao-seed-2.0-code");
        arkProperties.setTimeout(Duration.ofSeconds(60));
        arkProperties.setMaxRetries(3);
        arkProperties.setTemperature(0.7);

        bailianProperties = new BailianProperties();
        bailianProperties.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        bailianProperties.setApiKey("test-bailian-api-key");
        bailianProperties.setDefaultModel("deepseek-v4-flash");
        bailianProperties.getModels().put("chat", "deepseek-v4-flash");
        bailianProperties.setTimeout(Duration.ofSeconds(60));
        bailianProperties.setMaxRetries(3);
        bailianProperties.setTemperature(0.7);
        bailianProperties.setEmbeddingModel("text-embedding-v4");

        llmProperties = new LlmProperties();

        arkProvider = new ArkLlmServiceProvider(arkProperties);
        bailianProvider = new BailianLlmServiceProvider(bailianProperties);
    }

    /**
     * 构造 ModelFactory 实例（统一工厂方法）
     *
     * @param providers 注册的厂商策略列表
     * @return ModelFactory 实例
     */
    private ModelFactory createFactory(LlmServiceProvider... providers) {
        List<LlmServiceProvider> providerList = new ArrayList<>(Arrays.asList(providers));
        return new ModelFactory(llmProperties, providerList);
    }

    /**
     * 创建一个 LlmProperties 匿名子类，覆盖 getProviderCode() 返回指定 code
     * <p>
     * 业务含义：LlmProvider 枚举仅含 ARK / BAILIAN，新增厂商（如 mock）在测试场景下
     * 通过子类覆盖 getProviderCode() 模拟。生产环境中应通过新增 LlmProvider 枚举值实现。
     * </p>
     *
     * @param code 厂商代码（如 "mock"）
     * @return 覆盖了 getProviderCode() 的 LlmProperties 实例
     */
    private LlmProperties createLlmPropertiesWithCode(String code) {
        return new LlmProperties() {
            @Override
            public String getProviderCode() {
                return code;
            }
        };
    }

    /**
     * 使用指定的 llmProperties 和 providers 构造 ModelFactory（用于扩展性测试）
     */
    private ModelFactory createFactoryWithProperties(LlmProperties properties,
                                                     LlmServiceProvider... providers) {
        List<LlmServiceProvider> providerList = new ArrayList<>(Arrays.asList(providers));
        return new ModelFactory(properties, providerList);
    }

    // ========== 构造器签名验证 ==========

    @Nested
    @DisplayName("构造器签名：ModelFactory(LlmProperties, List<LlmServiceProvider>)")
    class ConstructorTest {

        @Test
        @DisplayName("应接受 LlmProperties + List<LlmServiceProvider> 构造参数")
        void shouldAcceptNewConstructorSignature() {
            ModelFactory factory = createFactory(arkProvider, bailianProvider);
            assertNotNull(factory, "新构造器签名应能正常实例化 ModelFactory");
        }
    }

    // ========== 正常流程（Happy Path）==========

    @Nested
    @DisplayName("正常路由：按 providerCode 委托给对应 Provider")
    class RoutingTest {

        @Test
        @DisplayName("provider = ARK 时，getChatModel('code') 返回非 null 实例")
        void shouldRouteToArkProviderForChatModel() {
            llmProperties.setProvider(LlmProvider.ARK);
            ModelFactory factory = createFactory(arkProvider, bailianProvider);

            ChatModel model = factory.getChatModel("code");

            assertNotNull(model, "provider=ARK 时 getChatModel 应委托给 ArkLlmServiceProvider 返回非 null 实例");
        }

        @Test
        @DisplayName("provider = BAILIAN 时，getChatModel('chat') 返回非 null 实例")
        void shouldRouteToBailianProviderForChatModel() {
            llmProperties.setProvider(LlmProvider.BAILIAN);
            ModelFactory factory = createFactory(arkProvider, bailianProvider);

            ChatModel model = factory.getChatModel("chat");

            assertNotNull(model, "provider=BAILIAN 时 getChatModel 应委托给 BailianLlmServiceProvider");
        }

        @Test
        @DisplayName("provider = ARK 时，getThinkingStreamingChatModel 返回 ArkThinkingStreamingChatModel 实例")
        void shouldReturnArkThinkingStreamingChatModel() {
            llmProperties.setProvider(LlmProvider.ARK);
            ModelFactory factory = createFactory(arkProvider, bailianProvider);

            ThinkingStreamingChatModel model = factory.getThinkingStreamingChatModel();

            assertNotNull(model);
            assertTrue(model instanceof ArkThinkingStreamingChatModel,
                    "provider=ARK 时应返回 ArkThinkingStreamingChatModel 实例");
        }

        @Test
        @DisplayName("provider = BAILIAN 时，getThinkingStreamingChatModel 返回 BailianThinkingStreamingChatModel 实例")
        void shouldReturnBailianThinkingStreamingChatModel() {
            llmProperties.setProvider(LlmProvider.BAILIAN);
            ModelFactory factory = createFactory(arkProvider, bailianProvider);

            ThinkingStreamingChatModel model = factory.getThinkingStreamingChatModel();

            assertNotNull(model);
            assertTrue(model instanceof BailianThinkingStreamingChatModel,
                    "provider=BAILIAN 时应返回 BailianThinkingStreamingChatModel 实例");
        }

        @Test
        @DisplayName("provider = ARK 时，getEmbeddingModel 返回非 null 实例")
        void shouldReturnArkEmbeddingModel() {
            llmProperties.setProvider(LlmProvider.ARK);
            ModelFactory factory = createFactory(arkProvider, bailianProvider);

            EmbeddingModel model = factory.getEmbeddingModel();

            assertNotNull(model, "provider=ARK 时 getEmbeddingModel 应返回火山引擎 Embedding 模型");
        }

        @Test
        @DisplayName("provider = BAILIAN 时，getEmbeddingModel 返回非 null 实例")
        void shouldReturnBailianEmbeddingModel() {
            llmProperties.setProvider(LlmProvider.BAILIAN);
            ModelFactory factory = createFactory(arkProvider, bailianProvider);

            EmbeddingModel model = factory.getEmbeddingModel();

            assertNotNull(model, "provider=BAILIAN 时 getEmbeddingModel 应返回阿里百炼 Embedding 模型");
        }

        @Test
        @DisplayName("provider = ARK 且 visionModel 已配置时，getVisionChatModel 返回非 null 实例")
        void shouldReturnArkVisionChatModel() {
            arkProperties.setVisionModel("doubao-vision-pro");
            llmProperties.setProvider(LlmProvider.ARK);
            ModelFactory factory = createFactory(arkProvider, bailianProvider);

            ChatModel model = factory.getVisionChatModel();

            assertNotNull(model, "ARK 模式且 visionModel 已配置时应返回视觉模型实例");
        }

        @Test
        @DisplayName("provider = BAILIAN 且 visionModel 已配置时，getVisionChatModel 返回非 null 实例")
        void shouldReturnBailianVisionChatModel() {
            bailianProperties.setVisionModel("qwen-vl-plus");
            llmProperties.setProvider(LlmProvider.BAILIAN);
            ModelFactory factory = createFactory(arkProvider, bailianProvider);

            ChatModel model = factory.getVisionChatModel();

            assertNotNull(model, "BAILIAN 模式且 visionModel 已配置时应返回视觉模型实例");
        }

        @Test
        @DisplayName("provider = ARK 时，getDefaultChatModel 正常返回")
        void shouldReturnArkDefaultChatModel() {
            llmProperties.setProvider(LlmProvider.ARK);
            ModelFactory factory = createFactory(arkProvider, bailianProvider);

            ChatModel model = factory.getDefaultChatModel();

            assertNotNull(model);
        }

        @Test
        @DisplayName("provider = BAILIAN 时，getDefaultChatModel 正常返回")
        void shouldReturnBailianDefaultChatModel() {
            llmProperties.setProvider(LlmProvider.BAILIAN);
            ModelFactory factory = createFactory(arkProvider, bailianProvider);

            ChatModel model = factory.getDefaultChatModel();

            assertNotNull(model);
        }

        @Test
        @DisplayName("provider = BAILIAN 时，getDefaultStreamingChatModel 正常返回")
        void shouldReturnBailianDefaultStreamingChatModel() {
            llmProperties.setProvider(LlmProvider.BAILIAN);
            ModelFactory factory = createFactory(arkProvider, bailianProvider);

            StreamingChatModel model = factory.getDefaultStreamingChatModel();

            assertNotNull(model);
        }

        @Test
        @DisplayName("provider = BAILIAN 时，getStreamingChatModel 返回非 null 实例")
        void shouldReturnBailianStreamingChatModel() {
            llmProperties.setProvider(LlmProvider.BAILIAN);
            ModelFactory factory = createFactory(arkProvider, bailianProvider);

            StreamingChatModel model = factory.getStreamingChatModel("chat");

            assertNotNull(model);
        }
    }

    // ========== 缓存复用语义（AC-022 / AC-014）==========

    @Nested
    @DisplayName("缓存复用语义（AC-022）：缓存委托给 Provider，多次调用返回同一实例")
    class CacheReuseTest {

        @Test
        @DisplayName("多次调用 getChatModel('chat') 返回同一实例（缓存复用）")
        void shouldReturnSameChatModelInstance() {
            llmProperties.setProvider(LlmProvider.BAILIAN);
            ModelFactory factory = createFactory(arkProvider, bailianProvider);

            ChatModel first = factory.getChatModel("chat");
            ChatModel second = factory.getChatModel("chat");

            assertSame(first, second, "多次调用 getChatModel 应返回同一缓存实例（AC-022）");
        }

        @Test
        @DisplayName("多次调用 getVisionChatModel 返回同一实例")
        void shouldReturnSameVisionModelInstance() {
            arkProperties.setVisionModel("doubao-vision-pro");
            llmProperties.setProvider(LlmProvider.ARK);
            ModelFactory factory = createFactory(arkProvider, bailianProvider);

            ChatModel first = factory.getVisionChatModel();
            ChatModel second = factory.getVisionChatModel();

            assertSame(first, second, "多次调用 getVisionChatModel 应返回同一缓存实例");
        }

        @Test
        @DisplayName("多次调用 getThinkingStreamingChatModel 返回同一实例")
        void shouldReturnSameThinkingModelInstance() {
            llmProperties.setProvider(LlmProvider.ARK);
            ModelFactory factory = createFactory(arkProvider, bailianProvider);

            ThinkingStreamingChatModel first = factory.getThinkingStreamingChatModel();
            ThinkingStreamingChatModel second = factory.getThinkingStreamingChatModel();

            assertSame(first, second, "多次调用 getThinkingStreamingChatModel 应返回同一缓存实例");
        }

        @Test
        @DisplayName("多次调用 getEmbeddingModel 返回同一实例")
        void shouldReturnSameEmbeddingModelInstance() {
            llmProperties.setProvider(LlmProvider.ARK);
            ModelFactory factory = createFactory(arkProvider, bailianProvider);

            EmbeddingModel first = factory.getEmbeddingModel();
            EmbeddingModel second = factory.getEmbeddingModel();

            assertSame(first, second);
        }
    }

    // ========== 异常流程（Error Cases）==========

    @Nested
    @DisplayName("异常处理：未注册厂商 / 能力缺失 / API Key 校验")
    class ExceptionHandlingTest {

        @Test
        @DisplayName("provider 配置为未注册的 code 时，抛 BusinessException(LLM_PROVIDER_NOT_FOUND)")
        void shouldThrowWhenProviderNotRegistered() {
            // 业务含义：注册表中只有 mock 厂商，但配置 llm.provider=ark
            // 应抛 LLM_PROVIDER_NOT_FOUND
            MockLlmServiceProvider mockProvider = new MockLlmServiceProvider();
            llmProperties.setProvider(LlmProvider.ARK);
            ModelFactory factory = createFactory(mockProvider);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> factory.getChatModel("code"),
                    "未注册的厂商 code 应抛 BusinessException");
            assertEquals(ErrorCode.LLM_PROVIDER_NOT_FOUND, ex.getErrorCode(),
                    "错误码应为 LLM_PROVIDER_NOT_FOUND");
            assertTrue(ex.getMessage().contains("ark"),
                    "错误信息应包含未注册的厂商代码");
        }

        @Test
        @DisplayName("厂商未实现 VisionChatModelProvider 时，getVisionChatModel 抛 UnsupportedCapabilityException（AC-021）")
        void shouldThrowUnsupportedCapabilityWhenVisionNotImplemented() {
            // 业务含义：MockLlmServiceProvider 故意不实现 VisionChatModelProvider 接口
            // 通过 LlmProperties 匿名子类覆盖 getProviderCode() 返回 "mock"
            MockLlmServiceProvider mockProvider = new MockLlmServiceProvider();
            LlmProperties mockLlmProperties = createLlmPropertiesWithCode(MockLlmServiceProvider.PROVIDER_CODE);
            ModelFactory factory = createFactoryWithProperties(mockLlmProperties, mockProvider);

            UnsupportedCapabilityException ex = assertThrows(UnsupportedCapabilityException.class,
                    factory::getVisionChatModel,
                    "MockLlmServiceProvider 未实现 VisionChatModelProvider，应抛 UnsupportedCapabilityException");
            assertEquals(MockLlmServiceProvider.PROVIDER_CODE, ex.getProviderCode(),
                    "异常信息应包含厂商代码 mock");
            assertEquals("vision", ex.getCapabilityName(),
                    "异常信息应包含缺失的能力名 vision");
        }

        @Test
        @DisplayName("provider = BAILIAN 且 apiKey 为 null 时，getChatModel 抛 BusinessException(LLM_API_KEY_INVALID)")
        void shouldThrowWhenBailianApiKeyIsNull() {
            bailianProperties.setApiKey(null);
            llmProperties.setProvider(LlmProvider.BAILIAN);
            ModelFactory factory = createFactory(arkProvider, bailianProvider);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> factory.getChatModel("chat"),
                    "BAILIAN API Key 为 null 时应抛 BusinessException");
            assertEquals(ErrorCode.LLM_API_KEY_INVALID, ex.getErrorCode(),
                    "错误码应为 LLM_API_KEY_INVALID");
            assertTrue(ex.getMessage().contains("BAILIAN_API_KEY"),
                    "错误信息应提示 BAILIAN_API_KEY 未配置");
        }

        @Test
        @DisplayName("provider = ARK 且 apiKey 为 null 时，getChatModel 抛 BusinessException")
        void shouldThrowWhenArkApiKeyIsNull() {
            arkProperties.setApiKey(null);
            llmProperties.setProvider(LlmProvider.ARK);
            ModelFactory factory = createFactory(arkProvider, bailianProvider);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> factory.getChatModel("code"),
                    "ARK API Key 为 null 时应抛 BusinessException");
            assertEquals(ErrorCode.LLM_API_KEY_INVALID, ex.getErrorCode(),
                    "错误码应为 LLM_API_KEY_INVALID");
        }

        @Test
        @DisplayName("provider = ARK 且 visionModel 未配置时，getVisionChatModel 抛 BusinessException(LLM_MODEL_NOT_CONFIGURED)")
        void shouldThrowWhenArkVisionModelNotConfigured() {
            arkProperties.setVisionModel(null);
            llmProperties.setProvider(LlmProvider.ARK);
            ModelFactory factory = createFactory(arkProvider, bailianProvider);

            BusinessException ex = assertThrows(BusinessException.class,
                    factory::getVisionChatModel,
                    "ARK 模式下 visionModel 未配置时应抛 BusinessException");
            assertEquals(ErrorCode.LLM_MODEL_NOT_CONFIGURED, ex.getErrorCode());
        }

        @Test
        @DisplayName("provider = ARK 且 BAILIAN_API_KEY 缺失时，ARK 模式正常工作（AC-011 不回归）")
        void shouldNotBeAffectedByBailianApiKeyMissingWhenProviderIsArk() {
            bailianProperties.setApiKey(null);
            llmProperties.setProvider(LlmProvider.ARK);
            ModelFactory factory = createFactory(arkProvider, bailianProvider);

            ChatModel model = factory.getChatModel("code");

            assertNotNull(model, "ARK 模式下不应因 BAILIAN_API_KEY 缺失而报错（CR-002 缓存迁移后语义保持）");
        }
    }

    // ========== 扩展性验证（AC-018）==========

    @Nested
    @DisplayName("扩展性验证（AC-018）：新增厂商零核心改动")
    class ExtensibilityTest {

        @Test
        @DisplayName("新增 MockLlmServiceProvider 后，配置 llm.provider=mock 可路由到 Mock 厂商")
        void shouldRouteToNewlyAddedMockProvider() {
            // 业务含义：模拟新增第三个厂商，ModelFactory 代码无任何修改即可路由
            MockLlmServiceProvider mockProvider = new MockLlmServiceProvider();
            LlmProperties mockLlmProperties = createLlmPropertiesWithCode(MockLlmServiceProvider.PROVIDER_CODE);
            // 仅注册 mock 厂商，验证 ModelFactory 能路由到 mock
            ModelFactory factory = createFactoryWithProperties(mockLlmProperties, mockProvider);

            // 调用 getChatModel，应命中 MockLlmServiceProvider 抛出特征异常
            UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
                    () -> factory.getChatModel("anyScene"),
                    "应路由到 MockLlmServiceProvider 并抛出 Mock 标识异常");
            assertTrue(ex.getMessage().contains("MOCK_CHAT_MODEL_ROUTED"),
                    "异常信息应包含 Mock 厂商路由标识");

            assertEquals(1, mockProvider.getChatModelCallCount(),
                    "Mock 厂商的 getChatModel 应被调用 1 次");
        }

        @Test
        @DisplayName("新增 MockLlmServiceProvider 后，getThinkingStreamingChatModel 也能路由到 Mock 厂商")
        void shouldRouteThinkingModelToMockProvider() {
            MockLlmServiceProvider mockProvider = new MockLlmServiceProvider();
            LlmProperties mockLlmProperties = createLlmPropertiesWithCode(MockLlmServiceProvider.PROVIDER_CODE);
            ModelFactory factory = createFactoryWithProperties(mockLlmProperties, mockProvider);

            ThinkingStreamingChatModel model = factory.getThinkingStreamingChatModel();

            assertNotNull(model, "应路由到 MockLlmServiceProvider 并返回 Mock 实例");
            assertEquals(1, mockProvider.getThinkingModelCallCount(),
                    "Mock 厂商的 getThinkingStreamingChatModel 应被调用 1 次");
        }

        @Test
        @DisplayName("多厂商并存场景：注册表同时含 ark/bailian/mock，按 providerCode 精确路由")
        void shouldRouteToCorrectProviderInMultiProviderRegistry() {
            // 业务含义：验证注册表能同时持有多个厂商策略并精确路由
            MockLlmServiceProvider mockProvider = new MockLlmServiceProvider();
            LlmProperties mockLlmProperties = createLlmPropertiesWithCode(MockLlmServiceProvider.PROVIDER_CODE);
            ModelFactory factory = createFactoryWithProperties(mockLlmProperties,
                    arkProvider, bailianProvider, mockProvider);

            // 路由到 mock
            assertThrows(UnsupportedOperationException.class,
                    () -> factory.getChatModel("any"),
                    "应命中 mock 厂商抛出特征异常");
            assertEquals(1, mockProvider.getChatModelCallCount());
        }
    }

    // ========== 回归测试（Regression）==========

    @Nested
    @DisplayName("回归测试：火山引擎模式行为与改造前一致")
    class RegressionTest {

        @Test
        @DisplayName("provider = ARK 时，所有模型获取行为与改造前完全一致")
        void arkModeBehaviorShouldBeConsistent() {
            llmProperties.setProvider(LlmProvider.ARK);
            ModelFactory factory = createFactory(arkProvider, bailianProvider);

            // 验证各能力方法均能正常返回
            assertNotNull(factory.getChatModel("code"), "ARK 模式 getChatModel 应正常返回");
            assertNotNull(factory.getDefaultChatModel(), "ARK 模式 getDefaultChatModel 应正常返回");
            assertNotNull(factory.getStreamingChatModel("code"), "ARK 模式 getStreamingChatModel 应正常返回");
            assertNotNull(factory.getDefaultStreamingChatModel(), "ARK 模式 getDefaultStreamingChatModel 应正常返回");
            assertNotNull(factory.getThinkingStreamingChatModel(), "ARK 模式 getThinkingStreamingChatModel 应正常返回");
            assertNotNull(factory.getEmbeddingModel(), "ARK 模式 getEmbeddingModel 应正常返回");
        }

        @Test
        @DisplayName("provider = BAILIAN 时，所有模型获取行为与改造前完全一致")
        void bailianModeBehaviorShouldBeConsistent() {
            llmProperties.setProvider(LlmProvider.BAILIAN);
            ModelFactory factory = createFactory(arkProvider, bailianProvider);

            assertNotNull(factory.getChatModel("chat"), "BAILIAN 模式 getChatModel 应正常返回");
            assertNotNull(factory.getDefaultChatModel(), "BAILIAN 模式 getDefaultChatModel 应正常返回");
            assertNotNull(factory.getStreamingChatModel("chat"), "BAILIAN 模式 getStreamingChatModel 应正常返回");
            assertNotNull(factory.getDefaultStreamingChatModel(), "BAILIAN 模式 getDefaultStreamingChatModel 应正常返回");
            assertNotNull(factory.getThinkingStreamingChatModel(), "BAILIAN 模式 getThinkingStreamingChatModel 应正常返回");
            assertNotNull(factory.getEmbeddingModel(), "BAILIAN 模式 getEmbeddingModel 应正常返回");
        }
    }
}
