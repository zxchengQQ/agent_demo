package com.agentdemo.common.constant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ModelConstants 常量测试
 * <p>
 * 验证标准来源：Task-02 验证标准
 * 关联 AC：AC-013
 * </p>
 */
class ModelConstantsTest {

    @Test
    void shouldHaveBailianDeepseekFlashConstant() {
        assertEquals("deepseek-v4-flash", ModelConstants.MODEL_BAILIAN_DEEPSEEK_V4_FLASH,
                "阿里百炼默认模型常量值应为 deepseek-v4-flash");
    }

    @Test
    void shouldHaveBailianEmbeddingConstant() {
        assertEquals("text-embedding-v4", ModelConstants.MODEL_BAILIAN_EMBEDDING,
                "阿里百炼 Embedding 模型常量值应为 text-embedding-v4");
    }

    @Test
    void shouldNotAffectExistingVolcanoConstants() {
        // 验证现有火山引擎常量不受影响
        assertEquals("doubao-seed-2.0-code", ModelConstants.MODEL_DOUBAO_SEED_2_CODE);
        assertEquals("doubao-seed-2.0-pro", ModelConstants.MODEL_DOUBAO_SEED_2_PRO);
        assertEquals("doubao-seed-2.0-lite", ModelConstants.MODEL_DOUBAO_SEED_2_LITE);
        assertEquals("doubao-embedding-vision", ModelConstants.MODEL_DOUBAO_EMBEDDING);
    }
}