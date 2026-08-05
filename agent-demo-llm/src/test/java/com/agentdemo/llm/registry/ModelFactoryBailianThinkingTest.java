package com.agentdemo.llm.registry;

import com.agentdemo.llm.config.ArkProperties;
import com.agentdemo.llm.config.BailianProperties;
import com.agentdemo.llm.config.LlmProperties;
import com.agentdemo.llm.config.LlmProvider;
import com.agentdemo.llm.provider.ArkLlmServiceProvider;
import com.agentdemo.llm.provider.BailianLlmServiceProvider;
import com.agentdemo.llm.thinking.BailianThinkingStreamingChatModel;
import com.agentdemo.llm.thinking.ThinkingStreamingChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ModelFactory 百炼模式路由集成测试（CR-002 Task-24 适配新构造器）
 * <p>
 * 验证标准来源：Task-24 验证标准（CR-001 Task-15 端到端验证的回归）
 * 关联 AC：AC-015, AC-017, AC-022
 * </p>
 * <p>
 * 验证目标：CR-002 重构后，百炼模式下 getThinkingStreamingChatModel 仍能正确路由到
 * BailianThinkingStreamingChatModel，且缓存复用机制有效（缓存委托给 BailianLlmServiceProvider）。
 * </p>
 */
class ModelFactoryBailianThinkingTest {

    private ArkProperties arkProperties;
    private LlmProperties llmProperties;
    private BailianProperties bailianProperties;
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

        llmProperties = new LlmProperties();
        llmProperties.setProvider(LlmProvider.BAILIAN);

        arkProvider = new ArkLlmServiceProvider(arkProperties);
        bailianProvider = new BailianLlmServiceProvider(bailianProperties);
    }

    /**
     * 验证标准 1：provider=BAILIAN 时，getThinkingStreamingChatModel 返回 BailianThinkingStreamingChatModel 实例
     * 业务含义：CR-002 重构后路由行为应保持一致（委托给 BailianLlmServiceProvider）
     */
    @Test
    void shouldReturnBailianThinkingModel() {
        ModelFactory factory = new ModelFactory(llmProperties, List.of(arkProvider, bailianProvider));

        ThinkingStreamingChatModel model = factory.getThinkingStreamingChatModel();

        assertNotNull(model, "BAILIAN 模式下应返回非 null 实例");
        assertTrue(model instanceof BailianThinkingStreamingChatModel,
                "应返回 BailianThinkingStreamingChatModel 实例，实际: " + model.getClass().getName());
    }

    /**
     * 验证标准 2：缓存复用——多次调用返回同一实例（AC-022）
     * 业务含义：缓存迁移到 BailianLlmServiceProvider 内部后，缓存语义保持不变
     */
    @Test
    void shouldCacheBailianThinkingModel() {
        ModelFactory factory = new ModelFactory(llmProperties, List.of(arkProvider, bailianProvider));

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
        ModelFactory factory = new ModelFactory(llmProperties, List.of(arkProvider, bailianProvider));

        BailianThinkingStreamingChatModel model =
                (BailianThinkingStreamingChatModel) factory.getThinkingStreamingChatModel();

        assertNotNull(model);
        assertTrue(model.getBaseUrl().contains("dashscope.aliyuncs.com"),
                "百炼模型应使用 dashscope Base URL，实际: " + model.getBaseUrl());
    }
}
