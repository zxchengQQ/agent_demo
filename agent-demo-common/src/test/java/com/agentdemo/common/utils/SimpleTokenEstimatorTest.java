package com.agentdemo.common.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SimpleTokenEstimator 单元测试
 * 验证 Token 估算算法的正确性（中文≈1.5字/token，英文≈4字符/token）
 */
@DisplayName("SimpleTokenEstimator - Token 估算工具")
class SimpleTokenEstimatorTest {

    @Test
    @DisplayName("传入 null 应返回 0")
    void estimate_null_returnsZero() {
        assertEquals(0, SimpleTokenEstimator.estimate(null));
    }

    @Test
    @DisplayName("传入空字符串应返回 0")
    void estimate_emptyString_returnsZero() {
        assertEquals(0, SimpleTokenEstimator.estimate(""));
    }

    @Test
    @DisplayName("纯中文文本：4个中文字符应返回 3（ceil(4/1.5)）")
    void estimate_chineseOnly_returnsCorrectTokens() {
        // 4 / 1.5 = 2.667, ceil = 3
        assertEquals(3, SimpleTokenEstimator.estimate("你好世界"));
    }

    @Test
    @DisplayName("纯英文文本：5个英文字符应返回 2（ceil(5/4)）")
    void estimate_englishOnly_returnsCorrectTokens() {
        // 5 / 4.0 = 1.25, ceil = 2
        assertEquals(2, SimpleTokenEstimator.estimate("hello"));
    }

    @Test
    @DisplayName("中英混合文本：2中文+5英文应返回 3（ceil(2/1.5 + 5/4.0) = ceil(2.583) = 3）")
    void estimate_mixedText_returnsCorrectTokens() {
        // 2/1.5 = 1.333, 5/4.0 = 1.25, sum = 2.583, ceil = 3
        assertEquals(3, SimpleTokenEstimator.estimate("你好hello"));
    }

    @Test
    @DisplayName("长文本应返回正值且执行时间小于 10ms")
    void estimate_longText_returnsPositiveAndFast() {
        // 构建 10000 字符的长文本
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            sb.append("a");
        }
        String longText = sb.toString();

        long start = System.nanoTime();
        int result = SimpleTokenEstimator.estimate(longText);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(result > 0, "长文本 Token 估算应返回正值");
        assertTrue(elapsedMs < 10, "10000 字符文本估算应小于 10ms，实际: " + elapsedMs + "ms");
    }

    @Test
    @DisplayName("纯数字文本应按英文规则估算")
    void estimate_digitsOnly_returnsCorrectTokens() {
        // 8 / 4.0 = 2.0, ceil = 2
        assertEquals(2, SimpleTokenEstimator.estimate("12345678"));
    }

    @Test
    @DisplayName("含空格和标点的文本应正确估算")
    void estimate_textWithSpaces_returnsCorrectTokens() {
        // "hello world" = 11 个非中文字符
        // 11 / 4.0 = 2.75, ceil = 3
        assertEquals(3, SimpleTokenEstimator.estimate("hello world"));
    }
}
