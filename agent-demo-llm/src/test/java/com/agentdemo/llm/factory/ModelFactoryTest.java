package com.agentdemo.llm.factory;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.llm.config.ArkProperties;
import com.agentdemo.llm.config.BailianProperties;
import com.agentdemo.llm.config.LlmProperties;
import com.agentdemo.llm.config.LlmProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ModelFactory 测试（Task-07 新增）
 * <p>
 * 验证标准来源：Task-07 验证标准（12 项）
 * 关联 AC：AC-001 ~ AC-008, AC-011, AC-014
 * </p>
 */
class ModelFactoryTest {

    private ArkProperties arkProperties;
    private LlmProperties llmProperties;
    private BailianProperties bailianProperties;

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
        bailianProperties.getModels().put("code", "deepseek-v4-flash");
        bailianProperties.setTimeout(Duration.ofSeconds(60));
        bailianProperties.setMaxRetries(3);
        bailianProperties.setTemperature(0.7);
        bailianProperties.setEmbeddingModel("text-embedding-v4");

        llmProperties = new LlmProperties();
    }

    // ========== 正常流程（Happy Path）==========

    /** 验证标准 1：provider = ARK 时，getChatModel("code") 返回火山引擎模型 */
    @Test
    void shouldReturnArkChatModelWhenProviderIsArk() {
        llmProperties.setProvider(LlmProvider.ARK);
        ModelFactory factory = new ModelFactory(arkProperties, llmProperties, bailianProperties);

        ChatModel model = factory.getChatModel("code");

        assertNotNull(model, "provider=ARK 时 getChatModel 应返回非 null 实例");
    }

    /** 验证标准 2：provider = BAILIAN 时，getChatModel("chat") 返回阿里百炼模型 */
    @Test
    void shouldReturnBailianChatModelWhenProviderIsBailian() {
        llmProperties.setProvider(LlmProvider.BAILIAN);
        ModelFactory factory = new ModelFactory(arkProperties, llmProperties, bailianProperties);

        ChatModel model = factory.getChatModel("chat");

        assertNotNull(model, "provider=BAILIAN 时 getChatModel 应返回非 null 实例");
    }

    /** 验证标准 3：provider = BAILIAN 时，getStreamingChatModel 返回阿里百炼流式模型 */
    @Test
    void shouldReturnBailianStreamingChatModelWhenProviderIsBailian() {
        llmProperties.setProvider(LlmProvider.BAILIAN);
        ModelFactory factory = new ModelFactory(arkProperties, llmProperties, bailianProperties);

        StreamingChatModel model = factory.getStreamingChatModel("chat");

        assertNotNull(model, "provider=BAILIAN 时 getStreamingChatModel 应返回非 null 实例");
    }

    /** 验证标准 4：provider = BAILIAN 时，getEmbeddingModel 返回阿里百炼 Embedding 模型 */
    @Test
    void shouldReturnBailianEmbeddingModelWhenProviderIsBailian() {
        llmProperties.setProvider(LlmProvider.BAILIAN);
        ModelFactory factory = new ModelFactory(arkProperties, llmProperties, bailianProperties);

        EmbeddingModel model = factory.getEmbeddingModel();

        assertNotNull(model, "provider=BAILIAN 时 getEmbeddingModel 应返回非 null 实例");
    }

    /** 验证标准 5：provider = ARK 时，getThinkingStreamingChatModel 正常返回 ArkThinkingStreamingChatModel */
    @Test
    void shouldReturnArkThinkingStreamingModelWhenProviderIsArk() {
        llmProperties.setProvider(LlmProvider.ARK);
        ModelFactory factory = new ModelFactory(arkProperties, llmProperties, bailianProperties);

        ThinkingStreamingChatModel model = factory.getThinkingStreamingChatModel();

        assertNotNull(model, "provider=ARK 时 getThinkingStreamingChatModel 应返回非 null 实例");
        assertTrue(model instanceof ArkThinkingStreamingChatModel,
                "provider=ARK 时应返回 ArkThinkingStreamingChatModel 实例");
    }

    /** 验证标准 6：多次调用 getChatModel 返回同一实例（缓存复用，AC-014） */
    @Test
    void shouldReturnSameInstanceOnMultipleCalls() {
        llmProperties.setProvider(LlmProvider.BAILIAN);
        ModelFactory factory = new ModelFactory(arkProperties, llmProperties, bailianProperties);

        ChatModel first = factory.getChatModel("chat");
        ChatModel second = factory.getChatModel("chat");

        assertSame(first, second, "多次调用应返回同一实例（缓存复用，AC-014）");
    }

    // ========== 异常流程（Error Cases）==========

    /** 验证标准 7：provider = BAILIAN 且 apiKey 为 null 时抛出 BusinessException（AC-006） */
    @Test
    void shouldThrowWhenBailianApiKeyIsNull() {
        bailianProperties.setApiKey(null);
        llmProperties.setProvider(LlmProvider.BAILIAN);
        ModelFactory factory = new ModelFactory(arkProperties, llmProperties, bailianProperties);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> factory.getChatModel("chat"),
                "BAILIAN API Key 为 null 时应抛出 BusinessException");
        assertTrue(ex.getMessage().contains("BAILIAN_API_KEY"),
                "错误信息应提示 BAILIAN_API_KEY 未配置");
    }

    /** 验证标准 8：provider = BAILIAN 且 apiKey 为空字符串时抛出 BusinessException（AC-006） */
    @Test
    void shouldThrowWhenBailianApiKeyIsEmpty() {
        bailianProperties.setApiKey("");
        llmProperties.setProvider(LlmProvider.BAILIAN);
        ModelFactory factory = new ModelFactory(arkProperties, llmProperties, bailianProperties);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> factory.getChatModel("chat"),
                "BAILIAN API Key 为空时应抛出 BusinessException");
        assertTrue(ex.getMessage().contains("BAILIAN_API_KEY"),
                "错误信息应提示 BAILIAN_API_KEY 未配置");
    }

    /** 验证标准 9（CR-001 改造）：provider = BAILIAN 时，getThinkingStreamingChatModel 返回 BailianThinkingStreamingChatModel（不再抛异常） */
    @Test
    void shouldReturnBailianThinkingStreamingModelWhenProviderIsBailian() {
        llmProperties.setProvider(LlmProvider.BAILIAN);
        ModelFactory factory = new ModelFactory(arkProperties, llmProperties, bailianProperties);

        ThinkingStreamingChatModel model = factory.getThinkingStreamingChatModel();

        assertNotNull(model, "provider=BAILIAN 时 getThinkingStreamingChatModel 应返回非 null 实例（CR-001）");
        assertTrue(model instanceof BailianThinkingStreamingChatModel,
                "provider=BAILIAN 时应返回 BailianThinkingStreamingChatModel 实例");
    }

    /** 验证标准 9b（CR-001 新增）：provider = BAILIAN 且 apiKey 为 null 时，getThinkingStreamingChatModel 抛出 BusinessException */
    @Test
    void shouldThrowWhenBailianApiKeyIsNullForThinkingModel() {
        bailianProperties.setApiKey(null);
        llmProperties.setProvider(LlmProvider.BAILIAN);
        ModelFactory factory = new ModelFactory(arkProperties, llmProperties, bailianProperties);

        BusinessException ex = assertThrows(BusinessException.class,
                factory::getThinkingStreamingChatModel,
                "BAILIAN API Key 为 null 时 getThinkingStreamingChatModel 应抛出 BusinessException");
        assertTrue(ex.getMessage().contains("BAILIAN_API_KEY"),
                "错误信息应提示 BAILIAN_API_KEY 未配置");
    }

    /** 验证标准 10：provider = BAILIAN 且 ARK_API_KEY 未配置时，正常调用阿里百炼（AC-011） */
    @Test
    void shouldWorkWhenArkApiKeyIsMissingAndProviderIsBailian() {
        arkProperties.setApiKey(null);
        llmProperties.setProvider(LlmProvider.BAILIAN);
        ModelFactory factory = new ModelFactory(arkProperties, llmProperties, bailianProperties);

        // 不应因 ARK_API_KEY 缺失而报错
        ChatModel model = factory.getChatModel("chat");
        assertNotNull(model, "BAILIAN 模式下不应因 ARK_API_KEY 缺失而报错");
    }

    // ========== 回归测试（Regression）==========

    /** 验证标准 11：provider = ARK 时，getChatModel 行为与改动前一致 */
    @Test
    void shouldPreserveArkBehaviorWhenProviderIsArk() {
        llmProperties.setProvider(LlmProvider.ARK);
        ModelFactory factory = new ModelFactory(arkProperties, llmProperties, bailianProperties);

        ChatModel model = factory.getChatModel("code");
        assertNotNull(model, "ARK 模式下 getChatModel 应正常返回");
    }

    /** 验证标准 12：provider = ARK 时，getEmbeddingModel 使用豆包 Embedding 模型 */
    @Test
    void shouldUseDoubaoEmbeddingWhenProviderIsArk() {
        llmProperties.setProvider(LlmProvider.ARK);
        ModelFactory factory = new ModelFactory(arkProperties, llmProperties, bailianProperties);

        EmbeddingModel model = factory.getEmbeddingModel();
        assertNotNull(model, "ARK 模式下 getEmbeddingModel 应返回非 null 实例");
    }

    /** 验证标准 12 补充：provider = ARK 时，API Key 为空抛出 BusinessException */
    @Test
    void shouldThrowWhenArkApiKeyIsNullAndProviderIsArk() {
        arkProperties.setApiKey(null);
        llmProperties.setProvider(LlmProvider.ARK);
        ModelFactory factory = new ModelFactory(arkProperties, llmProperties, bailianProperties);

        assertThrows(BusinessException.class,
                () -> factory.getChatModel("code"),
                "ARK 模式下 API Key 为空时应抛出 BusinessException");
    }

    /** 验证标准 12 补充：provider = ARK 时，getDefaultChatModel 正常返回 */
    @Test
    void shouldReturnDefaultChatModelWhenProviderIsArk() {
        llmProperties.setProvider(LlmProvider.ARK);
        ModelFactory factory = new ModelFactory(arkProperties, llmProperties, bailianProperties);

        ChatModel model = factory.getDefaultChatModel();
        assertNotNull(model, "ARK 模式下 getDefaultChatModel 应返回非 null 实例");
    }

    /** 验证标准 12 补充：provider = BAILIAN 时，getDefaultChatModel 正常返回 */
    @Test
    void shouldReturnDefaultChatModelWhenProviderIsBailian() {
        llmProperties.setProvider(LlmProvider.BAILIAN);
        ModelFactory factory = new ModelFactory(arkProperties, llmProperties, bailianProperties);

        ChatModel model = factory.getDefaultChatModel();
        assertNotNull(model, "BAILIAN 模式下 getDefaultChatModel 应返回非 null 实例");
    }

    /** 验证标准 12 补充：provider = BAILIAN 时，getDefaultStreamingChatModel 正常返回 */
    @Test
    void shouldReturnDefaultStreamingChatModelWhenProviderIsBailian() {
        llmProperties.setProvider(LlmProvider.BAILIAN);
        ModelFactory factory = new ModelFactory(arkProperties, llmProperties, bailianProperties);

        StreamingChatModel model = factory.getDefaultStreamingChatModel();
        assertNotNull(model, "BAILIAN 模式下 getDefaultStreamingChatModel 应返回非 null 实例");
    }
}