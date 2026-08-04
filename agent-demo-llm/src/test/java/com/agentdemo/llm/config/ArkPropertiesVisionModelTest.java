package com.agentdemo.llm.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ArkProperties 视觉模型配置单元测试（CR-002 新增）
 * <p>
 * 验证 visionModel 配置字段的默认值和读写。
 */
class ArkPropertiesVisionModelTest {

    @Test
    void visionModel默认值为空() {
        ArkProperties properties = new ArkProperties();
        assertNull(properties.getVisionModel(), "visionModel 默认应为 null（未配置时不启用图片描述）");
    }

    @Test
    void visionModel可设置和读取() {
        ArkProperties properties = new ArkProperties();
        properties.setVisionModel("doubao-vision-pro");
        assertEquals("doubao-vision-pro", properties.getVisionModel());
    }

    @Test
    void visionModel独立于defaultModel() {
        ArkProperties properties = new ArkProperties();
        properties.setVisionModel("doubao-vision-pro");
        // defaultModel 不受影响
        assertEquals("doubao-seed-2.0-code", properties.getDefaultModel());
        assertEquals("doubao-vision-pro", properties.getVisionModel());
    }
}
