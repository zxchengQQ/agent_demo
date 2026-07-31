package com.agentdemo.llm.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LlmProperties 配置类测试
 * <p>
 * 验证标准来源：Task-04 验证标准
 * 关联 AC：AC-009
 * </p>
 */
class LlmPropertiesTest {

    private LlmProperties properties;

    @BeforeEach
    void setUp() {
        properties = new LlmProperties();
    }

    @Test
    void shouldHaveConfigurationPropertiesAnnotation() {
        ConfigurationProperties annotation = LlmProperties.class.getAnnotation(ConfigurationProperties.class);
        assertNotNull(annotation, "LlmProperties 应标注 @ConfigurationProperties");
        assertEquals("llm", annotation.prefix(), "prefix 应为 llm");
    }

    @Test
    void shouldDefaultToArkProvider() {
        assertEquals(LlmProvider.ARK, properties.getProvider(), "默认 provider 应为 ARK");
    }

    @Test
    void shouldSetProviderToBailian() {
        properties.setProvider(LlmProvider.BAILIAN);
        assertEquals(LlmProvider.BAILIAN, properties.getProvider(), "设置 provider 为 BAILIAN 后应返回 BAILIAN");
    }

    @Test
    void shouldHaveTwoProviderValues() {
        // 验证枚举值完整性
        assertEquals(2, LlmProvider.values().length, "LlMProvider 应恰好有 2 个枚举值");
    }
}