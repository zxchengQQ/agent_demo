package com.agentdemo.llm.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BailianProperties 配置类测试
 * <p>
 * 验证标准来源：Task-03 验证标准
 * 关联 AC：AC-005, AC-010, AC-012
 * </p>
 */
class BailianPropertiesTest {

    private BailianProperties properties;

    @BeforeEach
    void setUp() {
        properties = new BailianProperties();
    }

    @Test
    void shouldHaveConfigurationPropertiesAnnotation() {
        // 验证 @ConfigurationProperties 注解存在
        ConfigurationProperties annotation = BailianProperties.class.getAnnotation(ConfigurationProperties.class);
        assertNotNull(annotation, "BailianProperties 应标注 @ConfigurationProperties");
        assertEquals("bailian", annotation.prefix(), "prefix 应为 bailian");
    }

    @Test
    void shouldHaveDefaultBaseUrl() {
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1",
                properties.getBaseUrl(), "默认 baseUrl 应为阿里百炼 OpenAI 兼容协议地址");
    }

    @Test
    void shouldHaveDefaultModel() {
        assertEquals("deepseek-v4-flash", properties.getDefaultModel(), "默认 defaultModel 应为 deepseek-v4-flash");
    }

    @Test
    void shouldHaveDefaultEmbeddingModel() {
        assertEquals("text-embedding-v4", properties.getEmbeddingModel(), "默认 embeddingModel 应为 text-embedding-v4");
    }

    @Test
    void shouldHaveDefaultTimeout() {
        assertEquals(Duration.ofSeconds(60), properties.getTimeout(), "默认 timeout 应为 60s");
    }

    @Test
    void shouldHaveDefaultMaxRetries() {
        assertEquals(3, properties.getMaxRetries(), "默认 maxRetries 应为 3");
    }

    @Test
    void shouldHaveDefaultTemperature() {
        assertEquals(0.7, properties.getTemperature(), 0.001, "默认 temperature 应为 0.7");
    }

    @Test
    void shouldReturnDefaultModelWhenSceneIsNull() {
        assertEquals("deepseek-v4-flash", properties.getModelName(null),
                "scene 为 null 时应返回 defaultModel");
    }

    @Test
    void shouldReturnDefaultModelWhenSceneIsEmpty() {
        assertEquals("deepseek-v4-flash", properties.getModelName(""),
                "scene 为空字符串时应返回 defaultModel");
    }

    @Test
    void shouldReturnDefaultModelWhenSceneNotConfigured() {
        assertEquals("deepseek-v4-flash", properties.getModelName("chat"),
                "未配置 models Map 时应回退到 defaultModel");
    }

    @Test
    void shouldReturnSceneModelWhenConfigured() {
        properties.getModels().put("chat", "deepseek-v4-flash");
        assertEquals("deepseek-v4-flash", properties.getModelName("chat"),
                "配置了 models Map 时应返回对应场景的模型名");
    }

    @Test
    void shouldReturnDefaultModelForUnconfiguredScene() {
        properties.getModels().put("chat", "deepseek-v4-flash");
        assertEquals("deepseek-v4-flash", properties.getModelName("code"),
                "未配置的场景应回退到 defaultModel");
    }

    @Test
    void apiKeyShouldBeNullByDefault() {
        assertNull(properties.getApiKey(), "apiKey 默认应为 null（从环境变量注入）");
    }
}