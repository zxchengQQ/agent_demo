package com.agentdemo.llm.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LLM 配置实体类测试
 * <p>
 * 验证来源：Task-02 验证标准
 * 验证 LlmVendorConfig 和 LlmModelConfig 的字段、默认值、类型约束和 Lombok 生成的 getter/setter。
 * </p>
 */
@DisplayName("LLM 配置实体类测试")
class LlmConfigEntityTest {

    // ========== LlmModelConfig 字段测试 ==========

    @Test
    @DisplayName("LlmModelConfig 包含所有必需字段且 getter/setter 正常工作")
    void llmModelConfigShouldHaveAllRequiredFields() {
        LlmModelConfig model = new LlmModelConfig();
        model.setId("model-001");
        model.setVendorId("vendor-001");
        model.setModelName("doubao-seed-2.0-pro");
        model.setDisplayName("豆包Seed 2.0 Pro");
        model.setType("chat");
        model.setSupportsVision(false);

        assertEquals("model-001", model.getId());
        assertEquals("vendor-001", model.getVendorId());
        assertEquals("doubao-seed-2.0-pro", model.getModelName());
        assertEquals("豆包Seed 2.0 Pro", model.getDisplayName());
        assertEquals("chat", model.getType());
        assertFalse(model.isSupportsVision());
    }

    @Test
    @DisplayName("LlmModelConfig supportsVision 默认值为 false")
    void llmModelConfigSupportsVisionDefaultShouldBeFalse() {
        LlmModelConfig model = new LlmModelConfig();
        assertFalse(model.isSupportsVision(), "supportsVision 默认值应为 false");
    }

    @Test
    @DisplayName("LlmModelConfig.type 接受 chat/embedding/rerank/multimodal")
    void llmModelConfigTypeShouldAcceptValidTypes() {
        List<String> validTypes = Arrays.asList("chat", "embedding", "rerank", "multimodal");
        for (String type : validTypes) {
            LlmModelConfig model = new LlmModelConfig();
            model.setType(type);
            assertEquals(type, model.getType(), "type 应接受: " + type);
        }
    }

    // ========== LlmVendorConfig 字段测试 ==========

    @Test
    @DisplayName("LlmVendorConfig 包含所有必需字段且 getter/setter 正常工作")
    void llmVendorConfigShouldHaveAllRequiredFields() {
        LlmVendorConfig vendor = new LlmVendorConfig();
        vendor.setId("vendor-001");
        vendor.setName("火山引擎方舟");
        vendor.setType("predefined");
        vendor.setBaseUrl("https://ark.cn-beijing.volces.com/api/v3");
        vendor.setApiKey("ark-test-key");
        vendor.setThinkingTrigger("enabled");

        assertEquals("vendor-001", vendor.getId());
        assertEquals("火山引擎方舟", vendor.getName());
        assertEquals("predefined", vendor.getType());
        assertEquals("https://ark.cn-beijing.volces.com/api/v3", vendor.getBaseUrl());
        assertEquals("ark-test-key", vendor.getApiKey());
        assertEquals("enabled", vendor.getThinkingTrigger());
    }

    @Test
    @DisplayName("LlmVendorConfig timeout 默认值为 60 秒")
    void llmVendorConfigTimeoutDefaultShouldBe60Seconds() {
        LlmVendorConfig vendor = new LlmVendorConfig();
        assertEquals(Duration.ofSeconds(60), vendor.getTimeout(), "timeout 默认值应为 60 秒");
    }

    @Test
    @DisplayName("LlmVendorConfig maxRetries 默认值为 3")
    void llmVendorConfigMaxRetriesDefaultShouldBe3() {
        LlmVendorConfig vendor = new LlmVendorConfig();
        assertEquals(3, vendor.getMaxRetries(), "maxRetries 默认值应为 3");
    }

    @Test
    @DisplayName("LlmVendorConfig temperature 默认值为 0.7")
    void llmVendorConfigTemperatureDefaultShouldBe07() {
        LlmVendorConfig vendor = new LlmVendorConfig();
        assertEquals(0.7, vendor.getTemperature(), 0.001, "temperature 默认值应为 0.7");
    }

    @Test
    @DisplayName("LlmVendorConfig models 默认为空列表（非 null）")
    void llmVendorConfigModelsDefaultShouldBeEmptyList() {
        LlmVendorConfig vendor = new LlmVendorConfig();
        assertNotNull(vendor.getModels(), "models 默认应为空列表，不应为 null");
        assertTrue(vendor.getModels().isEmpty(), "models 默认应为空列表");
    }

    @Test
    @DisplayName("LlmVendorConfig.thinkingTrigger 接受 enabled/none")
    void llmVendorConfigThinkingTriggerShouldAcceptValidValues() {
        List<String> validValues = Arrays.asList("enabled", "none");
        for (String trigger : validValues) {
            LlmVendorConfig vendor = new LlmVendorConfig();
            vendor.setThinkingTrigger(trigger);
            assertEquals(trigger, vendor.getThinkingTrigger(), "thinkingTrigger 应接受: " + trigger);
        }
    }

    @Test
    @DisplayName("LlmVendorConfig 可以设置和获取 models 列表")
    void llmVendorConfigCanSetAndGetModels() {
        LlmVendorConfig vendor = new LlmVendorConfig();
        LlmModelConfig model1 = new LlmModelConfig();
        model1.setModelName("model-1");
        model1.setType("chat");
        LlmModelConfig model2 = new LlmModelConfig();
        model2.setModelName("model-2");
        model2.setType("embedding");

        vendor.setModels(Arrays.asList(model1, model2));
        assertEquals(2, vendor.getModels().size());
        assertEquals("model-1", vendor.getModels().get(0).getModelName());
        assertEquals("model-2", vendor.getModels().get(1).getModelName());
    }

    @Test
    @DisplayName("LlmVendorConfig 可以设置和获取 timeout/maxRetries/temperature")
    void llmVendorConfigCanSetTimeoutMaxRetriesTemperature() {
        LlmVendorConfig vendor = new LlmVendorConfig();
        vendor.setTimeout(Duration.ofSeconds(120));
        vendor.setMaxRetries(5);
        vendor.setTemperature(0.5);

        assertEquals(Duration.ofSeconds(120), vendor.getTimeout());
        assertEquals(5, vendor.getMaxRetries());
        assertEquals(0.5, vendor.getTemperature(), 0.001);
    }

    @Test
    @DisplayName("LlmModelConfig 新建实例时 id 和 vendorId 默认为 null")
    void llmModelConfigNewInstanceIdAndVendorIdShouldBeNull() {
        LlmModelConfig model = new LlmModelConfig();
        assertNull(model.getId());
        assertNull(model.getVendorId());
        assertNull(model.getModelName());
        assertNull(model.getDisplayName());
        assertNull(model.getType());
    }
}
