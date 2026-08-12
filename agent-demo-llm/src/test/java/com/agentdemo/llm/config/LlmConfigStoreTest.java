package com.agentdemo.llm.config;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LlmConfigStore 内存配置存储测试
 * <p>
 * 验证来源：Task-03 验证标准
 * 测试 LlmConfigStore 的增删改查、参数校验、唯一性校验和辅助查询方法。
 * </p>
 */
@DisplayName("LlmConfigStore 内存配置存储测试")
class LlmConfigStoreTest {

    private LlmConfigStore store;

    @BeforeEach
    void setUp() {
        store = new LlmConfigStore();
    }

    // ========== 辅助方法 ==========

    private LlmVendorConfig createValidVendor(String name) {
        LlmVendorConfig vendor = new LlmVendorConfig();
        vendor.setName(name);
        vendor.setType("custom");
        vendor.setBaseUrl("https://api.example.com/v1");
        vendor.setApiKey("test-api-key");
        vendor.setThinkingTrigger("none");

        LlmModelConfig chatModel = new LlmModelConfig();
        chatModel.setModelName("chat-model");
        chatModel.setDisplayName("对话模型");
        chatModel.setType("chat");
        chatModel.setSupportsVision(false);

        LlmModelConfig embeddingModel = new LlmModelConfig();
        embeddingModel.setModelName("embedding-model");
        embeddingModel.setDisplayName("向量化模型");
        embeddingModel.setType("embedding");
        embeddingModel.setSupportsVision(false);

        vendor.setModels(Arrays.asList(chatModel, embeddingModel));
        return vendor;
    }

    // ========== addVendor 测试 ==========

    @Nested
    @DisplayName("addVendor - 添加厂商")
    class AddVendorTest {

        @Test
        @DisplayName("成功添加厂商，自动生成 id，返回完整 vendor")
        void addVendorSuccess() {
            LlmVendorConfig vendor = createValidVendor("测试厂商");

            LlmVendorConfig result = store.addVendor(vendor);

            assertNotNull(result.getId(), "添加后应自动生成 id");
            assertTrue(result.getId().length() > 0, "id 不应为空字符串");
            assertEquals("测试厂商", result.getName());
            assertEquals(2, result.getModels().size());
            // 模型应自动生成 id 和 vendorId
            for (LlmModelConfig model : result.getModels()) {
                assertNotNull(model.getId(), "模型应自动生成 id");
                assertEquals(result.getId(), model.getVendorId(), "模型 vendorId 应等于厂商 id");
            }
        }

        @Test
        @DisplayName("重复 name 抛出 BusinessException(LLM_VENDOR_NAME_EXISTS)")
        void addVendorDuplicateName() {
            store.addVendor(createValidVendor("测试厂商"));

            BusinessException ex = assertThrows(BusinessException.class,
                () -> store.addVendor(createValidVendor("测试厂商")));
            assertEquals(ErrorCode.LLM_VENDOR_NAME_EXISTS, ex.getErrorCode());
        }

        @Test
        @DisplayName("同厂商同 type 下重复 modelName 抛出 BusinessException(LLM_MODEL_NAME_EXISTS)")
        void addVendorDuplicateModelName() {
            LlmVendorConfig vendor = createValidVendor("测试厂商");
            // 两个 chat 类型同名的模型
            LlmModelConfig model1 = new LlmModelConfig();
            model1.setModelName("same-model");
            model1.setType("chat");
            LlmModelConfig model2 = new LlmModelConfig();
            model2.setModelName("same-model");
            model2.setType("chat");
            vendor.setModels(Arrays.asList(model1, model2));

            BusinessException ex = assertThrows(BusinessException.class,
                () -> store.addVendor(vendor));
            assertEquals(ErrorCode.LLM_MODEL_NAME_EXISTS, ex.getErrorCode());
        }

        @Test
        @DisplayName("name 为空抛出 BusinessException(PARAM_INVALID)")
        void addVendorNameBlank() {
            LlmVendorConfig vendor = createValidVendor("测试厂商");
            vendor.setName("");

            BusinessException ex = assertThrows(BusinessException.class,
                () -> store.addVendor(vendor));
            assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
        }

        @Test
        @DisplayName("baseUrl 为空抛出 BusinessException(PARAM_INVALID)")
        void addVendorBaseUrlBlank() {
            LlmVendorConfig vendor = createValidVendor("测试厂商");
            vendor.setBaseUrl("");

            BusinessException ex = assertThrows(BusinessException.class,
                () -> store.addVendor(vendor));
            assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
        }

        @Test
        @DisplayName("apiKey 为空抛出 BusinessException(PARAM_INVALID)")
        void addVendorApiKeyBlank() {
            LlmVendorConfig vendor = createValidVendor("测试厂商");
            vendor.setApiKey("");

            BusinessException ex = assertThrows(BusinessException.class,
                () -> store.addVendor(vendor));
            assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
        }

        @Test
        @DisplayName("models 中 modelName 为空抛出 BusinessException(PARAM_INVALID)")
        void addVendorModelNameBlank() {
            LlmVendorConfig vendor = createValidVendor("测试厂商");
            LlmModelConfig model = new LlmModelConfig();
            model.setModelName("");
            model.setType("chat");
            vendor.setModels(Collections.singletonList(model));

            BusinessException ex = assertThrows(BusinessException.class,
                () -> store.addVendor(vendor));
            assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
        }
    }

    // ========== updateVendor 测试 ==========

    @Nested
    @DisplayName("updateVendor - 更新厂商")
    class UpdateVendorTest {

        @Test
        @DisplayName("更新成功")
        void updateVendorSuccess() {
            LlmVendorConfig added = store.addVendor(createValidVendor("测试厂商"));

            LlmVendorConfig update = new LlmVendorConfig();
            update.setName("更新后厂商");
            update.setType("custom");
            update.setBaseUrl("https://api.updated.com/v1");
            update.setApiKey("new-api-key");
            update.setThinkingTrigger("enabled");
            update.setTimeout(Duration.ofSeconds(120));
            update.setMaxRetries(5);
            update.setTemperature(0.5);

            LlmVendorConfig result = store.updateVendor(added.getId(), update);

            assertEquals("更新后厂商", result.getName());
            assertEquals("https://api.updated.com/v1", result.getBaseUrl());
            assertEquals("new-api-key", result.getApiKey());
            assertEquals("enabled", result.getThinkingTrigger());
            assertEquals(Duration.ofSeconds(120), result.getTimeout());
            assertEquals(5, result.getMaxRetries());
            assertEquals(0.5, result.getTemperature());
        }

        @Test
        @DisplayName("apiKey 为空时保留原值")
        void updateVendorKeepApiKeyWhenBlank() {
            LlmVendorConfig added = store.addVendor(createValidVendor("测试厂商"));
            String originalApiKey = added.getApiKey();

            LlmVendorConfig update = new LlmVendorConfig();
            update.setName("测试厂商");
            update.setBaseUrl("https://api.example.com/v1");
            update.setApiKey(""); // 空值

            LlmVendorConfig result = store.updateVendor(added.getId(), update);

            assertEquals(originalApiKey, result.getApiKey(), "apiKey 为空时应保留原值");
        }

        @Test
        @DisplayName("不存在 id 抛出 BusinessException(LLM_VENDOR_NOT_FOUND)")
        void updateVendorNotFound() {
            LlmVendorConfig update = new LlmVendorConfig();
            update.setName("测试厂商");
            update.setBaseUrl("https://api.example.com/v1");

            BusinessException ex = assertThrows(BusinessException.class,
                () -> store.updateVendor("non-existent-id", update));
            assertEquals(ErrorCode.LLM_VENDOR_NOT_FOUND, ex.getErrorCode());
        }

        @Test
        @DisplayName("name 与其他厂商重复抛出 BusinessException(LLM_VENDOR_NAME_EXISTS)")
        void updateVendorNameConflict() {
            store.addVendor(createValidVendor("厂商A"));
            LlmVendorConfig vendorB = store.addVendor(createValidVendor("厂商B"));

            LlmVendorConfig update = new LlmVendorConfig();
            update.setName("厂商A"); // 与厂商A重复
            update.setBaseUrl("https://api.example.com/v1");

            BusinessException ex = assertThrows(BusinessException.class,
                () -> store.updateVendor(vendorB.getId(), update));
            assertEquals(ErrorCode.LLM_VENDOR_NAME_EXISTS, ex.getErrorCode());
        }
    }

    // ========== deleteVendor 测试 ==========

    @Nested
    @DisplayName("deleteVendor - 删除厂商")
    class DeleteVendorTest {

        @Test
        @DisplayName("删除成功，连带删除所有模型")
        void deleteVendorSuccess() {
            LlmVendorConfig added = store.addVendor(createValidVendor("测试厂商"));
            String vendorId = added.getId();
            String modelId = added.getModels().get(0).getId();

            store.deleteVendor(vendorId);

            assertNull(store.getVendor(vendorId), "删除后厂商应为 null");
            assertNull(store.getModel(modelId), "删除后关联模型也应为 null");
        }

        @Test
        @DisplayName("不存在 id 抛出 BusinessException(LLM_VENDOR_NOT_FOUND)")
        void deleteVendorNotFound() {
            BusinessException ex = assertThrows(BusinessException.class,
                () -> store.deleteVendor("non-existent-id"));
            assertEquals(ErrorCode.LLM_VENDOR_NOT_FOUND, ex.getErrorCode());
        }
    }

    // ========== 查询方法测试 ==========

    @Nested
    @DisplayName("查询方法")
    class QueryTest {

        @Test
        @DisplayName("getVendor 返回厂商，不存在返回 null")
        void getVendor() {
            LlmVendorConfig added = store.addVendor(createValidVendor("测试厂商"));

            assertNotNull(store.getVendor(added.getId()));
            assertNull(store.getVendor("non-existent-id"));
        }

        @Test
        @DisplayName("getAllVendors 返回所有厂商列表")
        void getAllVendors() {
            store.addVendor(createValidVendor("厂商A"));
            store.addVendor(createValidVendor("厂商B"));

            List<LlmVendorConfig> all = store.getAllVendors();
            assertEquals(2, all.size());
        }

        @Test
        @DisplayName("getModel 返回模型配置，不存在返回 null")
        void getModel() {
            LlmVendorConfig added = store.addVendor(createValidVendor("测试厂商"));
            String modelId = added.getModels().get(0).getId();

            assertNotNull(store.getModel(modelId));
            assertNull(store.getModel("non-existent-model-id"));
        }

        @Test
        @DisplayName("getFirstChatModel 返回第一个 chat 模型，无则返回 null")
        void getFirstChatModel() {
            LlmVendorConfig added = store.addVendor(createValidVendor("测试厂商"));
            LlmModelConfig firstChat = store.getFirstChatModel();

            assertNotNull(firstChat, "存在 chat 模型时应返回");
            assertEquals("chat", firstChat.getType());

            // 清空后应返回 null
            store.clear();
            assertNull(store.getFirstChatModel());
        }

        @Test
        @DisplayName("getFirstEmbeddingModel 返回第一个 embedding 模型，无则返回 null")
        void getFirstEmbeddingModel() {
            LlmVendorConfig added = store.addVendor(createValidVendor("测试厂商"));
            LlmModelConfig firstEmbedding = store.getFirstEmbeddingModel();

            assertNotNull(firstEmbedding, "存在 embedding 模型时应返回");
            assertEquals("embedding", firstEmbedding.getType());

            // 清空后应返回 null
            store.clear();
            assertNull(store.getFirstEmbeddingModel());
        }
    }

    // ========== 状态方法测试 ==========

    @Nested
    @DisplayName("状态方法")
    class StateTest {

        @Test
        @DisplayName("hasConfig 无厂商返回 false，有厂商返回 true")
        void hasConfig() {
            assertFalse(store.hasConfig(), "初始状态应返回 false");

            store.addVendor(createValidVendor("测试厂商"));
            assertTrue(store.hasConfig(), "添加厂商后应返回 true");
        }

        @Test
        @DisplayName("clear 清空所有配置")
        void clear() {
            store.addVendor(createValidVendor("测试厂商"));
            assertTrue(store.hasConfig());

            store.clear();
            assertFalse(store.hasConfig(), "clear 后应无配置");
            assertTrue(store.getAllVendors().isEmpty());
        }

        @Test
        @DisplayName("replaceAll 批量替换所有配置")
        void replaceAll() {
            store.addVendor(createValidVendor("原有厂商"));

            LlmVendorConfig newVendor = createValidVendor("新厂商A");
            LlmVendorConfig newVendor2 = createValidVendor("新厂商B");
            store.replaceAll(Arrays.asList(newVendor, newVendor2));

            List<LlmVendorConfig> all = store.getAllVendors();
            assertEquals(2, all.size(), "替换后应有 2 个厂商");
            // 原有厂商应被清除
            assertNull(store.getVendor("原有厂商"));
        }

        @Test
        @DisplayName("replaceAll 传入 null 等同于 clear")
        void replaceAllWithNull() {
            store.addVendor(createValidVendor("测试厂商"));
            assertTrue(store.hasConfig());

            store.replaceAll(null);
            assertFalse(store.hasConfig(), "replaceAll(null) 应清空所有配置");
        }

        @Test
        @DisplayName("replaceAll 为无 id 的厂商和模型自动生成 id")
        void replaceAllGeneratesIds() {
            LlmVendorConfig vendor = createValidVendor("新厂商");
            // 确保无 id
            vendor.setId(null);
            for (LlmModelConfig model : vendor.getModels()) {
                model.setId(null);
            }

            store.replaceAll(Collections.singletonList(vendor));

            LlmVendorConfig stored = store.getAllVendors().get(0);
            assertNotNull(stored.getId(), "厂商应自动生成 id");
            for (LlmModelConfig model : stored.getModels()) {
                assertNotNull(model.getId(), "模型应自动生成 id");
                assertEquals(stored.getId(), model.getVendorId(), "模型 vendorId 应等于厂商 id");
            }
        }
    }
}
