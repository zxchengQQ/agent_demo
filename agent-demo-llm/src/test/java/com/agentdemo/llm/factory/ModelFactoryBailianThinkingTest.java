package com.agentdemo.llm.factory;

import com.agentdemo.llm.config.ArkProperties;
import com.agentdemo.llm.config.BailianProperties;
import com.agentdemo.llm.config.LlmProperties;
import com.agentdemo.llm.config.LlmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ModelFactory 百炼模式路由集成测试（CR-001 Task-15 端到端验证）
 * <p>
 * 验证标准来源：Task-15 验证标准
 * 关联 AC：AC-015, AC-017
 * </p>
 * <p>
 * 验证目标：百炼模式下 getThinkingStreamingChatModel 返回的实例能正确路由到 BailianThinkingStreamingChatModel，
 * 缓存复用机制有效。本测试为集成级别的端到端验证（不依赖真实 HTTP 调用，依赖单元测试已覆盖 HTTP 解析）。
 * </p>
 */
class ModelFactoryBailianThinkingTest {

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
        bailianProperties.setTimeout(Duration.ofSeconds(60));
        bailianProperties.setMaxRetries(3);
        bailianProperties.setTemperature(0.7);

        llmProperties = new LlmProperties();
        llmProperties.setProvider(LlmProvider.BAILIAN);
    }

    /**
     * 验证标准 1：provider=BAILIAN 时，getThinkingStreamingChatModel 返回 BailianThinkingStreamingChatModel 实例
     * 业务含义：CR-001 核心——百炼模式不再抛异常
     */
    @Test
    void shouldReturnBailianThinkingModel() {
        ModelFactory factory = new ModelFactory(arkProperties, llmProperties, bailianProperties);

        ThinkingStreamingChatModel model = factory.getThinkingStreamingChatModel();

        assertNotNull(model, "BAILIAN 模式下应返回非 null 实例");
        assertTrue(model instanceof BailianThinkingStreamingChatModel,
                "应返回 BailianThinkingStreamingChatModel 实例，实际: " + model.getClass().getName());
    }

    /**
     * 验证标准 2：缓存复用——多次调用返回同一实例
     * 业务含义：遵循 BR-LLM-004 模型实例缓存复用
     */
    @Test
    void shouldCacheBailianThinkingModel() {
        ModelFactory factory = new ModelFactory(arkProperties, llmProperties, bailianProperties);

        ThinkingStreamingChatModel first = factory.getThinkingStreamingChatModel();
        ThinkingStreamingChatModel second = factory.getThinkingStreamingChatModel();

        assertSame(first, second, "多次调用应返回同一缓存实例");
    }

    /**
     * 验证标准 3：百炼实现的 Base URL 正确
     * 业务含义：连接阿里百炼的 OpenAI 兼容端点（BR-LLM-010）
     */
    @Test
    void shouldUseBailianBaseUrl() {
        ModelFactory factory = new ModelFactory(arkProperties, llmProperties, bailianProperties);

        BailianThinkingStreamingChatModel model = (BailianThinkingStreamingChatModel) factory.getThinkingStreamingChatModel();

        assertNotNull(model);
        assertTrue(model.getBaseUrl().contains("dashscope.aliyuncs.com"),
                "百炼模型应使用 dashscope Base URL，实际: " + model.getBaseUrl());
    }
}
