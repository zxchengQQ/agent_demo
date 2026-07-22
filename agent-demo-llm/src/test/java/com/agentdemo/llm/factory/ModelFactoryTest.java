package com.agentdemo.llm.factory;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.llm.config.ArkProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ModelFactory 测试（CR-001 新增）
 * <p>
 * 验证标准来源：T-18 验证标准
 * 关联 AC：AC-022（推理过程流式展示）
 * </p>
 */
class ModelFactoryTest {

    private ArkProperties properties;

    @BeforeEach
    void setUp() {
        properties = mock(ArkProperties.class);
        when(properties.getBaseUrl()).thenReturn("https://ark.cn-beijing.volces.com/api/coding/v3");
        when(properties.getApiKey()).thenReturn("test-api-key");
        when(properties.getModelName(null)).thenReturn("doubao-seed-2.0-code");
        when(properties.getTimeout()).thenReturn(Duration.ofSeconds(60));
    }

    /**
     * 验证标准 1：getThinkingStreamingChatModel() 返回非 null 的 ArkThinkingStreamingChatModel 实例
     * 业务含义：思考流式模型工厂方法能正确构造实例
     */
    @Test
    void getThinkingStreamingChatModelShouldReturnNonNullInstance() {
        ModelFactory factory = new ModelFactory(properties);

        ArkThinkingStreamingChatModel model = factory.getThinkingStreamingChatModel();

        assertNotNull(model, "getThinkingStreamingChatModel 应返回非 null 实例");
    }

    /**
     * 验证标准 2：多次调用返回同一实例（缓存复用，BR-LLM-004）
     * 业务含义：模型实例创建成本高，按 modelName 缓存复用避免重复构建
     */
    @Test
    void getThinkingStreamingChatModelShouldReturnSameInstanceOnMultipleCalls() {
        ModelFactory factory = new ModelFactory(properties);

        ArkThinkingStreamingChatModel first = factory.getThinkingStreamingChatModel();
        ArkThinkingStreamingChatModel second = factory.getThinkingStreamingChatModel();

        assertSame(first, second, "多次调用应返回同一实例（缓存复用，BR-LLM-004）");
    }

    /**
     * 验证标准 3：API Key 未配置时抛出 BusinessException（LLM_API_KEY_INVALID）
     * 业务含义：API Key 必须通过环境变量注入，禁止为空（BR-LLM-001）
     */
    @Test
    void getThinkingStreamingChatModelShouldThrowWhenApiKeyIsNull() {
        when(properties.getApiKey()).thenReturn(null);
        ModelFactory factory = new ModelFactory(properties);

        assertThrows(BusinessException.class, factory::getThinkingStreamingChatModel,
                "API Key 为 null 时应抛出 BusinessException");
    }

    /**
     * 验证标准 3 补充：API Key 为空字符串时抛出 BusinessException
     */
    @Test
    void getThinkingStreamingChatModelShouldThrowWhenApiKeyIsEmpty() {
        when(properties.getApiKey()).thenReturn("");
        ModelFactory factory = new ModelFactory(properties);

        assertThrows(BusinessException.class, factory::getThinkingStreamingChatModel,
                "API Key 为空字符串时应抛出 BusinessException");
    }
}
