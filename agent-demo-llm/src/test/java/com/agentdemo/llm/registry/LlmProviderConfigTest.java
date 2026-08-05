package com.agentdemo.llm.registry;

import com.agentdemo.llm.capability.*;
import com.agentdemo.llm.config.ArkProperties;
import com.agentdemo.llm.config.BailianProperties;
import com.agentdemo.llm.config.LlmProvider;
import com.agentdemo.llm.config.LlmProviderConfig;
import com.agentdemo.llm.provider.ArkLlmServiceProvider;
import com.agentdemo.llm.provider.BailianLlmServiceProvider;
import com.agentdemo.llm.provider.LlmServiceProvider;
import com.agentdemo.llm.thinking.ThinkingStreamingChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task-17 测试：能力接口与配置访问契约的编译期/运行期契约验证
 * <p>
 * 验证标准：
 * - LlmProvider.ARK.getCode() 返回 "ark"
 * - LlmProvider.BAILIAN.getCode() 返回 "bailian"
 * - LlmServiceProvider 聚合 4 个核心能力接口 + getProviderCode()（CR-002 Task-24 ISP 修正：
 *   VisionChatModelProvider 拆出作为可选能力，厂商按需 implements）
 * - ThinkingStreamingChatModelProvider 声明 getThinkingStreamingChatModel(String) 工厂方法
 * - LlmProviderConfig 接口方法齐全
 * </p>
 */
class LlmProviderConfigTest {

    @Nested
    @DisplayName("LlmProvider 枚举 code 字段")
    class LlmProviderCodeTest {

        @Test
        @DisplayName("ARK 的 code 应为 'ark'")
        void arkCode() {
            assertEquals("ark", LlmProvider.ARK.getCode());
        }

        @Test
        @DisplayName("BAILIAN 的 code 应为 'bailian'")
        void bailianCode() {
            assertEquals("bailian", LlmProvider.BAILIAN.getCode());
        }

        @Test
        @DisplayName("LlmProvider 应有 getCode 方法")
        void hasGetCodeMethod() throws NoSuchMethodException {
            Method getCode = LlmProvider.class.getMethod("getCode");
            assertNotNull(getCode);
            assertEquals(String.class, getCode.getReturnType());
        }
    }

    @Nested
    @DisplayName("LlmProviderConfig 配置访问契约接口")
    class LlmProviderConfigContractTest {

        @Test
        @DisplayName("应声明所有必需的配置访问方法")
        void allMethodsDeclared() throws NoSuchMethodException {
            Class<?> config = LlmProviderConfig.class;
            assertNotNull(config.getMethod("getBaseUrl"));
            assertNotNull(config.getMethod("getApiKey"));
            assertNotNull(config.getMethod("getTimeout"));
            assertNotNull(config.getMethod("getMaxRetries"));
            assertNotNull(config.getMethod("getTemperature"));
            assertNotNull(config.getMethod("getModelName", String.class));
            assertNotNull(config.getMethod("getEmbeddingModel"));
            assertNotNull(config.getMethod("getVisionModel"));
        }

        @Test
        @DisplayName("getTimeout 返回类型应为 Duration")
        void timeoutReturnType() throws NoSuchMethodException {
            Method m = LlmProviderConfig.class.getMethod("getTimeout");
            assertEquals(Duration.class, m.getReturnType());
        }

        @Test
        @DisplayName("getMaxRetries 返回类型应为 int")
        void maxRetriesReturnType() throws NoSuchMethodException {
            Method m = LlmProviderConfig.class.getMethod("getMaxRetries");
            assertEquals(int.class, m.getReturnType());
        }

        @Test
        @DisplayName("getTemperature 返回类型应为 double")
        void temperatureReturnType() throws NoSuchMethodException {
            Method m = LlmProviderConfig.class.getMethod("getTemperature");
            assertEquals(double.class, m.getReturnType());
        }
    }

    @Nested
    @DisplayName("能力接口契约")
    class CapabilityInterfaceContractTest {

        @Test
        @DisplayName("ChatModelProvider 应声明 getChatModel(String)")
        void chatModelProviderContract() throws NoSuchMethodException {
            Method m = ChatModelProvider.class.getMethod("getChatModel", String.class);
            assertEquals(dev.langchain4j.model.chat.ChatModel.class, m.getReturnType());
        }

        @Test
        @DisplayName("StreamingChatModelProvider 应声明 getStreamingChatModel(String)")
        void streamingChatModelProviderContract() throws NoSuchMethodException {
            Method m = StreamingChatModelProvider.class.getMethod("getStreamingChatModel", String.class);
            assertEquals(dev.langchain4j.model.chat.StreamingChatModel.class, m.getReturnType());
        }

        @Test
        @DisplayName("ThinkingStreamingChatModelProvider 应声明 getThinkingStreamingChatModel(String)")
        void thinkingProviderDeclaresFactoryMethod() throws NoSuchMethodException {
            Method m = ThinkingStreamingChatModelProvider.class.getMethod(
                "getThinkingStreamingChatModel", String.class);
            assertEquals(ThinkingStreamingChatModel.class, m.getReturnType());
        }

        @Test
        @DisplayName("EmbeddingModelProvider 应声明 getEmbeddingModel()")
        void embeddingProviderContract() throws NoSuchMethodException {
            Method m = EmbeddingModelProvider.class.getMethod("getEmbeddingModel");
            assertEquals(dev.langchain4j.model.embedding.EmbeddingModel.class, m.getReturnType());
        }

        @Test
        @DisplayName("VisionChatModelProvider 应声明 getVisionChatModel()")
        void visionProviderContract() throws NoSuchMethodException {
            Method m = VisionChatModelProvider.class.getMethod("getVisionChatModel");
            assertEquals(dev.langchain4j.model.chat.ChatModel.class, m.getReturnType());
        }
    }

    @Nested
    @DisplayName("LlmServiceProvider 聚合接口")
    class LlmServiceProviderAggregationTest {

        @Test
        @DisplayName("LlmServiceProvider 应继承 4 个核心能力接口（CR-002 Task-24 ISP 修正：视觉能力为可选实现）")
        void extendsCoreCapabilityInterfaces() {
            // 业务含义：CR-002 Task-24 修正：LlmServiceProvider 仅聚合 4 个核心能力接口，
            // VisionChatModelProvider 作为可选能力，厂商通过显式 implements 按需实现
            List<Class<?>> expected = Arrays.asList(
                ChatModelProvider.class,
                StreamingChatModelProvider.class,
                ThinkingStreamingChatModelProvider.class,
                EmbeddingModelProvider.class
            );
            for (Class<?> iface : expected) {
                assertTrue(iface.isAssignableFrom(LlmServiceProvider.class),
                    "LlmServiceProvider 应继承 " + iface.getSimpleName());
            }
        }

        @Test
        @DisplayName("LlmServiceProvider 不应继承 VisionChatModelProvider（ISP 修正，视觉能力为可选）")
        void shouldNotExtendVisionChatModelProvider() {
            // 业务含义：CR-002 Task-24 修正：VisionChatModelProvider 拆出聚合接口，
            // 厂商按需 implements，未实现时编排层抛 UnsupportedCapabilityException（对应 AC-021）
            assertFalse(VisionChatModelProvider.class.isAssignableFrom(LlmServiceProvider.class),
                "LlmServiceProvider 不应继承 VisionChatModelProvider，视觉能力为可选实现");
        }

        @Test
        @DisplayName("ArkLlmServiceProvider 应显式实现 LlmServiceProvider + VisionChatModelProvider")
        void arkProviderImplementsBothInterfaces() {
            assertTrue(LlmServiceProvider.class.isAssignableFrom(ArkLlmServiceProvider.class),
                "ArkLlmServiceProvider 应实现 LlmServiceProvider");
            assertTrue(VisionChatModelProvider.class.isAssignableFrom(ArkLlmServiceProvider.class),
                "ArkLlmServiceProvider 应显式实现 VisionChatModelProvider（火山引擎支持视觉能力）");
        }

        @Test
        @DisplayName("BailianLlmServiceProvider 应显式实现 LlmServiceProvider + VisionChatModelProvider")
        void bailianProviderImplementsBothInterfaces() {
            assertTrue(LlmServiceProvider.class.isAssignableFrom(BailianLlmServiceProvider.class),
                "BailianLlmServiceProvider 应实现 LlmServiceProvider");
            assertTrue(VisionChatModelProvider.class.isAssignableFrom(BailianLlmServiceProvider.class),
                "BailianLlmServiceProvider 应显式实现 VisionChatModelProvider（阿里百炼支持视觉能力）");
        }

        @Test
        @DisplayName("LlmServiceProvider 应声明 getProviderCode()")
        void hasGetProviderCode() throws NoSuchMethodException {
            Method m = LlmServiceProvider.class.getMethod("getProviderCode");
            assertEquals(String.class, m.getReturnType());
        }
    }

    @Nested
    @DisplayName("配置类实现 LlmProviderConfig（Task-19）")
    class PropertiesImplementConfigTest {

        @Test
        @DisplayName("ArkProperties 应实现 LlmProviderConfig 接口")
        void arkPropertiesImplementsConfig() {
            assertTrue(LlmProviderConfig.class.isAssignableFrom(ArkProperties.class),
                "ArkProperties 应实现 LlmProviderConfig 接口");
        }

        @Test
        @DisplayName("BailianProperties 应实现 LlmProviderConfig 接口")
        void bailianPropertiesImplementsConfig() {
            assertTrue(LlmProviderConfig.class.isAssignableFrom(BailianProperties.class),
                "BailianProperties 应实现 LlmProviderConfig 接口");
        }

        @Test
        @DisplayName("ArkProperties.getEmbeddingModel() 应返回 doubao-embedding 模型常量")
        void arkPropertiesEmbeddingModel() {
            ArkProperties props = new ArkProperties();
            String embedding = props.getEmbeddingModel();
            assertNotNull(embedding);
            assertFalse(embedding.isEmpty());
            // 业务含义：火山引擎 Embedding 模型未在 ark.coding-plan 配置中独立抽字段，
            // 沿用 ModelConstants.MODEL_DOUBAO_EMBEDDING 常量
            assertEquals("doubao-embedding-vision", embedding);
        }

        @Test
        @DisplayName("BailianProperties.getEmbeddingModel() 应返回默认 text-embedding-v4")
        void bailianPropertiesEmbeddingModel() {
            BailianProperties props = new BailianProperties();
            assertEquals("text-embedding-v4", props.getEmbeddingModel());
        }

        @Test
        @DisplayName("ArkProperties 作为 LlmProviderConfig 应返回正确的 baseUrl")
        void arkPropertiesAsConfigBaseUrl() {
            ArkProperties props = new ArkProperties();
            LlmProviderConfig config = props;  // 向上转型
            assertEquals("https://ark.cn-beijing.volces.com/api/coding/v3", config.getBaseUrl());
        }

        @Test
        @DisplayName("BailianProperties 作为 LlmProviderConfig 应返回正确的 baseUrl")
        void bailianPropertiesAsConfigBaseUrl() {
            BailianProperties props = new BailianProperties();
            LlmProviderConfig config = props;
            assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", config.getBaseUrl());
        }

        @Test
        @DisplayName("ArkProperties 作为 LlmProviderConfig getModelName(scene) 应按场景路由")
        void arkPropertiesGetModelNameScene() {
            ArkProperties props = new ArkProperties();
            props.setDefaultModel("doubao-seed-2.0-code");
            java.util.Map<String, String> models = new java.util.HashMap<>();
            models.put("chat", "doubao-seed-2.0-pro");
            props.setModels(models);

            LlmProviderConfig config = props;
            assertEquals("doubao-seed-2.0-pro", config.getModelName("chat"));
            // 未命中场景应回退到 defaultModel
            assertEquals("doubao-seed-2.0-code", config.getModelName("lite"));
            // null 或空 scene 应回退到 defaultModel
            assertEquals("doubao-seed-2.0-code", config.getModelName(null));
        }

        @Test
        @DisplayName("BailianProperties 作为 LlmProviderConfig getModelName(scene) 应按场景路由")
        void bailianPropertiesGetModelNameScene() {
            BailianProperties props = new BailianProperties();
            props.setDefaultModel("deepseek-v4-flash");
            java.util.Map<String, String> models = new java.util.HashMap<>();
            models.put("chat", "deepseek-v4-chat");
            props.setModels(models);

            LlmProviderConfig config = props;
            assertEquals("deepseek-v4-chat", config.getModelName("chat"));
            assertEquals("deepseek-v4-flash", config.getModelName("lite"));
        }
    }
}
