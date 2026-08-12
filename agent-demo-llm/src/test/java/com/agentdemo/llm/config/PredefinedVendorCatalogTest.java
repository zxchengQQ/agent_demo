package com.agentdemo.llm.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PredefinedVendorCatalog 预定义厂商目录测试
 * <p>
 * 验证来源：Task-04 验证标准
 * 验证预定义厂商列表的完整性和各厂商配置的正确性。
 * </p>
 */
@DisplayName("PredefinedVendorCatalog 预定义厂商目录测试")
class PredefinedVendorCatalogTest {

    private PredefinedVendorCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new PredefinedVendorCatalog();
    }

    @Test
    @DisplayName("getPredefinedVendors 返回至少 5 个预定义厂商")
    void getPredefinedVendorsShouldReturnAtLeast5() {
        List<PredefinedVendor> vendors = catalog.getPredefinedVendors();
        assertNotNull(vendors);
        assertTrue(vendors.size() >= 5, "预定义厂商数量应至少为 5");
    }

    @Test
    @DisplayName("包含 code=ark name=火山引擎方舟 且配置正确")
    void shouldContainArk() {
        PredefinedVendor ark = findByCode("ark");
        assertNotNull(ark, "应包含 code=ark 的预定义厂商");
        assertEquals("火山引擎方舟", ark.getName());
        assertTrue(ark.getBaseUrl().contains("ark.cn-beijing.volces.com"), "ark baseUrl 应包含 ark.cn-beijing.volces.com");
        assertEquals("enabled", ark.getThinkingTrigger());
    }

    @Test
    @DisplayName("包含 code=bailian name=阿里百炼 且配置正确")
    void shouldContainBailian() {
        PredefinedVendor bailian = findByCode("bailian");
        assertNotNull(bailian, "应包含 code=bailian 的预定义厂商");
        assertEquals("阿里百炼", bailian.getName());
        assertTrue(bailian.getBaseUrl().contains("dashscope.aliyuncs.com"), "bailian baseUrl 应包含 dashscope.aliyuncs.com");
        assertEquals("none", bailian.getThinkingTrigger());
    }

    @Test
    @DisplayName("包含 code=openai name=OpenAI 且配置正确")
    void shouldContainOpenai() {
        PredefinedVendor openai = findByCode("openai");
        assertNotNull(openai, "应包含 code=openai 的预定义厂商");
        assertEquals("OpenAI", openai.getName());
        assertTrue(openai.getBaseUrl().contains("api.openai.com"), "openai baseUrl 应包含 api.openai.com");
    }

    @Test
    @DisplayName("包含 code=deepseek name=DeepSeek")
    void shouldContainDeepseek() {
        PredefinedVendor deepseek = findByCode("deepseek");
        assertNotNull(deepseek, "应包含 code=deepseek 的预定义厂商");
        assertEquals("DeepSeek", deepseek.getName());
    }

    @Test
    @DisplayName("包含 code=ollama 且 name 含 Ollama，models 为空列表")
    void shouldContainOllamaWithEmptyModels() {
        PredefinedVendor ollama = findByCode("ollama");
        assertNotNull(ollama, "应包含 code=ollama 的预定义厂商");
        assertTrue(ollama.getName().contains("Ollama"), "ollama name 应包含 Ollama");
        assertNotNull(ollama.getModels());
        assertTrue(ollama.getModels().isEmpty(), "ollama models 应为空列表");
    }

    @Test
    @DisplayName("火山引擎模型列表包含 chat 类型模型（含 displayName）")
    void arkShouldContainChatModelsWithDisplayName() {
        PredefinedVendor ark = findByCode("ark");
        assertNotNull(ark);

        boolean hasChat = ark.getModels().stream()
            .anyMatch(m -> "chat".equals(m.getType()) && m.getDisplayName() != null && !m.getDisplayName().isBlank());
        assertTrue(hasChat, "火山引擎应包含带 displayName 的 chat 类型模型");
    }

    @Test
    @DisplayName("火山引擎 chat 模型中 doubao-vision-pro 的 supportsVision=true")
    void arkDoubaoVisionProShouldSupportVision() {
        PredefinedVendor ark = findByCode("ark");
        assertNotNull(ark);

        PredefinedModel visionModel = ark.getModels().stream()
            .filter(m -> "doubao-vision-pro".equals(m.getModelName()))
            .findFirst()
            .orElse(null);
        assertNotNull(visionModel, "火山引擎应包含 doubao-vision-pro 模型");
        assertTrue(visionModel.isSupportsVision(), "doubao-vision-pro 的 supportsVision 应为 true");
        assertEquals("chat", visionModel.getType(), "doubao-vision-pro 应为 chat 类型");
    }

    @Test
    @DisplayName("阿里百炼模型列表包含 embedding 类型模型")
    void bailianShouldContainEmbeddingModel() {
        PredefinedVendor bailian = findByCode("bailian");
        assertNotNull(bailian);

        boolean hasEmbedding = bailian.getModels().stream()
            .anyMatch(m -> "embedding".equals(m.getType()));
        assertTrue(hasEmbedding, "阿里百炼应包含 embedding 类型模型");
    }

    // ========== 辅助方法 ==========

    private PredefinedVendor findByCode(String code) {
        return catalog.getPredefinedVendors().stream()
            .filter(v -> code.equals(v.getCode()))
            .findFirst()
            .orElse(null);
    }
}
