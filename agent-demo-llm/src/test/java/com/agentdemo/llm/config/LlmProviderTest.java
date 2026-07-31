package com.agentdemo.llm.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * LlmProvider 枚举测试
 * <p>
 * 验证标准来源：Task-01 验证标准
 * 关联 AC：AC-009
 * </p>
 */
class LlmProviderTest {

    @Test
    void shouldHaveArkValue() {
        // 验证 ARK 枚举值存在
        LlmProvider provider = LlmProvider.ARK;
        assertNotNull(provider, "LlmProvider.ARK 应存在");
        assertEquals("ARK", provider.name(), "枚举名应为 ARK");
    }

    @Test
    void shouldHaveBailianValue() {
        // 验证 BAILIAN 枚举值存在
        LlmProvider provider = LlmProvider.BAILIAN;
        assertNotNull(provider, "LlmProvider.BAILIAN 应存在");
        assertEquals("BAILIAN", provider.name(), "枚举名应为 BAILIAN");
    }

    @Test
    void shouldHaveTwoValues() {
        // 验证枚举值数量
        LlmProvider[] values = LlmProvider.values();
        assertEquals(2, values.length, "应恰好有 2 个枚举值");
    }
}