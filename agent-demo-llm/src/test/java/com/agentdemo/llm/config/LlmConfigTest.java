package com.agentdemo.llm.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LlmConfig 配置类测试
 * <p>
 * 验证标准来源：Task-05 验证标准
 * 关联 AC：AC-009, AC-010, AC-012
 * </p>
 */
class LlmConfigTest {

    @Test
    void shouldEnableArkProperties() {
        EnableConfigurationProperties annotation = LlmConfig.class.getAnnotation(EnableConfigurationProperties.class);
        assertNotNull(annotation, "LlmConfig 应标注 @EnableConfigurationProperties");

        Class<?>[] value = annotation.value();
        assertTrue(containsClass(value, ArkProperties.class),
                "应注册 ArkProperties 配置绑定");
    }

    @Test
    void shouldEnableLlmProperties() {
        EnableConfigurationProperties annotation = LlmConfig.class.getAnnotation(EnableConfigurationProperties.class);
        assertNotNull(annotation, "LlmConfig 应标注 @EnableConfigurationProperties");

        Class<?>[] value = annotation.value();
        assertTrue(containsClass(value, LlmProperties.class),
                "应注册 LlmProperties 配置绑定");
    }

    @Test
    void shouldEnableBailianProperties() {
        EnableConfigurationProperties annotation = LlmConfig.class.getAnnotation(EnableConfigurationProperties.class);
        assertNotNull(annotation, "LlmConfig 应标注 @EnableConfigurationProperties");

        Class<?>[] value = annotation.value();
        assertTrue(containsClass(value, BailianProperties.class),
                "应注册 BailianProperties 配置绑定");
    }

    private boolean containsClass(Class<?>[] classes, Class<?> target) {
        for (Class<?> clazz : classes) {
            if (clazz.equals(target)) {
                return true;
            }
        }
        return false;
    }
}