package com.agentdemo.web.controller;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.common.result.Result;
import com.agentdemo.llm.config.LlmConfigStore;
import com.agentdemo.llm.config.LlmModelConfig;
import com.agentdemo.llm.config.LlmVendorConfig;
import com.agentdemo.llm.config.PredefinedModel;
import com.agentdemo.llm.config.PredefinedVendor;
import com.agentdemo.llm.config.PredefinedVendorCatalog;
import com.agentdemo.llm.registry.ModelFactory;
import com.agentdemo.web.dto.ConfigStatusResponse;
import com.agentdemo.web.dto.ModelResponse;
import com.agentdemo.web.dto.PredefinedVendorResponse;
import com.agentdemo.web.dto.SyncConfigRequest;
import com.agentdemo.web.dto.TestConnectionRequest;
import com.agentdemo.web.dto.TestConnectionResponse;
import com.agentdemo.web.dto.VendorRequest;
import com.agentdemo.web.dto.VendorResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * LlmConfigController REST API 单元测试
 * <p>
 * 验证来源：Task-10 + Task-11 验证标准
 * 测试策略：直接调用 Controller 方法验证 Result 对象，依赖通过 @Mock 隔离。
 * 测试连接接口通过 spy 覆盖 doTestConnection 方法模拟 HTTP 调用。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LlmConfigController REST API 测试")
class LlmConfigControllerTest {

    @Mock
    private LlmConfigStore configStore;

    @Mock
    private PredefinedVendorCatalog predefinedCatalog;

    @Mock
    private ModelFactory modelFactory;

    private LlmConfigController controller;

    @BeforeEach
    void setUp() {
        controller = new LlmConfigController(configStore, predefinedCatalog, modelFactory);
    }

    // ==================== 辅助方法 ====================

    private LlmVendorConfig createVendor(String id, String name, String apiKey) {
        LlmVendorConfig vendor = new LlmVendorConfig();
        vendor.setId(id);
        vendor.setName(name);
        vendor.setType("custom");
        vendor.setBaseUrl("https://api.example.com/v1");
        vendor.setApiKey(apiKey);
        vendor.setThinkingTrigger("none");
        vendor.setTimeout(Duration.ofSeconds(60));
        vendor.setMaxRetries(3);
        vendor.setTemperature(0.7);

        LlmModelConfig chatModel = new LlmModelConfig();
        chatModel.setId("model-" + id + "-1");
        chatModel.setVendorId(id);
        chatModel.setModelName("chat-model");
        chatModel.setDisplayName("对话模型");
        chatModel.setType("chat");
        chatModel.setSupportsVision(false);

        LlmModelConfig embeddingModel = new LlmModelConfig();
        embeddingModel.setId("model-" + id + "-2");
        embeddingModel.setVendorId(id);
        embeddingModel.setModelName("embedding-model");
        embeddingModel.setDisplayName("向量化模型");
        embeddingModel.setType("embedding");
        embeddingModel.setSupportsVision(false);

        vendor.setModels(Arrays.asList(chatModel, embeddingModel));
        return vendor;
    }

    private VendorRequest createVendorRequest(String name) {
        VendorRequest request = new VendorRequest();
        request.setName(name);
        request.setType("custom");
        request.setBaseUrl("https://api.example.com/v1");
        request.setApiKey("sk-test-api-key-12345");
        request.setThinkingTrigger("none");
        request.setTimeout(60);
        request.setMaxRetries(3);
        request.setTemperature(0.7);

        VendorRequest.ModelItem chatItem = new VendorRequest.ModelItem();
        chatItem.setModelName("chat-model");
        chatItem.setDisplayName("对话模型");
        chatItem.setType("chat");
        chatItem.setSupportsVision(false);

        request.setModels(Collections.singletonList(chatItem));
        return request;
    }

    private PredefinedVendor createPredefinedVendor(String code, String name) {
        List<PredefinedModel> models = new ArrayList<>();
        PredefinedModel model = new PredefinedModel("test-model", "测试模型", "chat", false);
        models.add(model);
        return new PredefinedVendor(code, name, "https://api.example.com/v1", "none", models);
    }

    // ==================== Task-10: CRUD 接口测试 ====================

    @Nested
    @DisplayName("预定义厂商查询")
    class PredefinedVendorTest {

        @Test
        @DisplayName("GET /predefined - 返回预定义厂商列表")
        void getPredefinedVendors() {
            List<PredefinedVendor> vendors = Arrays.asList(
                    createPredefinedVendor("ark", "火山引擎方舟"),
                    createPredefinedVendor("bailian", "阿里百炼")
            );
            when(predefinedCatalog.getPredefinedVendors()).thenReturn(vendors);

            Result<List<PredefinedVendorResponse>> result = controller.getPredefinedVendors();

            assertTrue(result.isSuccess());
            assertEquals(2, result.getData().size());
            assertEquals("ark", result.getData().get(0).getCode());
            assertEquals("火山引擎方舟", result.getData().get(0).getName());
            assertEquals(1, result.getData().get(0).getModels().size());
            assertEquals("test-model", result.getData().get(0).getModels().get(0).getModelName());
        }

        @Test
        @DisplayName("GET /predefined - 无预定义厂商时返回空列表")
        void getPredefinedVendorsEmpty() {
            when(predefinedCatalog.getPredefinedVendors()).thenReturn(Collections.emptyList());

            Result<List<PredefinedVendorResponse>> result = controller.getPredefinedVendors();

            assertTrue(result.isSuccess());
            assertTrue(result.getData().isEmpty());
        }
    }

    @Nested
    @DisplayName("已配置厂商查询")
    class ListVendorsTest {

        @Test
        @DisplayName("GET /vendors - 返回已配置厂商列表（API Key 脱敏）")
        void listVendors() {
            LlmVendorConfig vendor = createVendor("v1", "测试厂商", "sk-abcdefghij1234567890");
            when(configStore.getAllVendors()).thenReturn(Collections.singletonList(vendor));

            Result<List<VendorResponse>> result = controller.listVendors();

            assertTrue(result.isSuccess());
            assertEquals(1, result.getData().size());
            VendorResponse response = result.getData().get(0);
            assertEquals("v1", response.getId());
            assertEquals("测试厂商", response.getName());
            // API Key 脱敏验证
            assertEquals("sk-****7890", response.getApiKeyMasked());
            assertTrue(response.isApiKeyConfigured());
            assertEquals(2, response.getModels().size());
        }

        @Test
        @DisplayName("GET /vendors - 无厂商时返回空列表")
        void listVendorsEmpty() {
            when(configStore.getAllVendors()).thenReturn(Collections.emptyList());

            Result<List<VendorResponse>> result = controller.listVendors();

            assertTrue(result.isSuccess());
            assertTrue(result.getData().isEmpty());
        }

        @Test
        @DisplayName("GET /vendors - API Key 过短时返回 ****")
        void listVendorsShortApiKey() {
            LlmVendorConfig vendor = createVendor("v1", "短Key厂商", "sk-abcd");
            when(configStore.getAllVendors()).thenReturn(Collections.singletonList(vendor));

            Result<List<VendorResponse>> result = controller.listVendors();

            assertEquals("****", result.getData().get(0).getApiKeyMasked());
        }

        @Test
        @DisplayName("GET /vendors - API Key 为 null 时 apiKeyConfigured 为 false")
        void listVendorsNullApiKey() {
            LlmVendorConfig vendor = createVendor("v1", "无Key厂商", null);
            when(configStore.getAllVendors()).thenReturn(Collections.singletonList(vendor));

            Result<List<VendorResponse>> result = controller.listVendors();

            assertEquals("****", result.getData().get(0).getApiKeyMasked());
            assertFalse(result.getData().get(0).isApiKeyConfigured());
        }
    }

    @Nested
    @DisplayName("添加厂商")
    class AddVendorTest {

        @Test
        @DisplayName("POST /vendors - 成功添加厂商")
        void addVendorSuccess() {
            VendorRequest request = createVendorRequest("新厂商");
            LlmVendorConfig saved = createVendor("new-id", "新厂商", "sk-test-api-key-12345");
            when(configStore.addVendor(any(LlmVendorConfig.class))).thenReturn(saved);

            Result<VendorResponse> result = controller.addVendor(request);

            assertTrue(result.isSuccess());
            assertEquals("new-id", result.getData().getId());
            assertEquals("新厂商", result.getData().getName());
            assertEquals("sk-****2345", result.getData().getApiKeyMasked());
            verify(modelFactory).clearAllCache();
        }

        @Test
        @DisplayName("POST /vendors - 厂商名称重复时抛出 BusinessException")
        void addVendorNameConflict() {
            VendorRequest request = createVendorRequest("已存在厂商");
            when(configStore.addVendor(any(LlmVendorConfig.class)))
                    .thenThrow(new BusinessException(ErrorCode.LLM_VENDOR_NAME_EXISTS, "厂商名称已存在"));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> controller.addVendor(request));
            assertEquals(ErrorCode.LLM_VENDOR_NAME_EXISTS, ex.getErrorCode());
        }
    }

    @Nested
    @DisplayName("编辑厂商")
    class UpdateVendorTest {

        @Test
        @DisplayName("PUT /vendors/{id} - 成功编辑厂商")
        void updateVendorSuccess() {
            VendorRequest request = createVendorRequest("更新厂商");
            LlmVendorConfig updated = createVendor("v1", "更新厂商", "sk-new-key-12345678");
            when(configStore.updateVendor(eq("v1"), any(LlmVendorConfig.class))).thenReturn(updated);

            Result<VendorResponse> result = controller.updateVendor("v1", request);

            assertTrue(result.isSuccess());
            assertEquals("v1", result.getData().getId());
            assertEquals("更新厂商", result.getData().getName());
            verify(modelFactory).clearCacheForVendor("v1");
        }

        @Test
        @DisplayName("PUT /vendors/{id} - 厂商不存在时抛出 BusinessException")
        void updateVendorNotFound() {
            VendorRequest request = createVendorRequest("厂商");
            when(configStore.updateVendor(eq("non-existent"), any(LlmVendorConfig.class)))
                    .thenThrow(new BusinessException(ErrorCode.LLM_VENDOR_NOT_FOUND, "厂商不存在"));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> controller.updateVendor("non-existent", request));
            assertEquals(ErrorCode.LLM_VENDOR_NOT_FOUND, ex.getErrorCode());
        }
    }

    @Nested
    @DisplayName("删除厂商")
    class DeleteVendorTest {

        @Test
        @DisplayName("DELETE /vendors/{id} - 成功删除厂商")
        void deleteVendorSuccess() {
            Result<Void> result = controller.deleteVendor("v1");

            assertTrue(result.isSuccess());
            verify(configStore).deleteVendor("v1");
            verify(modelFactory).clearCacheForVendor("v1");
        }

        @Test
        @DisplayName("DELETE /vendors/{id} - 厂商不存在时抛出 BusinessException")
        void deleteVendorNotFound() {
            doThrow(new BusinessException(ErrorCode.LLM_VENDOR_NOT_FOUND, "厂商不存在"))
                    .when(configStore).deleteVendor("non-existent");

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> controller.deleteVendor("non-existent"));
            assertEquals(ErrorCode.LLM_VENDOR_NOT_FOUND, ex.getErrorCode());
        }
    }

    // ==================== Task-11: 测试连接 + 模型查询 + 状态 + 同步 ====================

    @Nested
    @DisplayName("测试连接")
    class TestConnectionTest {

        @Test
        @DisplayName("POST /test - 连接成功")
        void testConnectionSuccess() {
            // 使用 spy 覆盖 doTestConnection 方法，避免真实 HTTP 调用
            LlmConfigController spyController = spy(controller);
            TestConnectionResponse mockResponse = new TestConnectionResponse();
            mockResponse.setSuccess(true);
            mockResponse.setMessage("连接成功");
            mockResponse.setLatency(150);
            doReturn(mockResponse).when(spyController).doTestConnection("https://api.example.com/v1", "sk-test-key");

            TestConnectionRequest request = new TestConnectionRequest();
            request.setBaseUrl("https://api.example.com/v1");
            request.setApiKey("sk-test-key");

            Result<TestConnectionResponse> result = spyController.testConnection(request);

            assertTrue(result.isSuccess());
            assertTrue(result.getData().isSuccess());
            assertEquals("连接成功", result.getData().getMessage());
            assertEquals(150, result.getData().getLatency());
        }

        @Test
        @DisplayName("POST /test - API Key 无效（401）")
        void testConnectionUnauthorized() {
            LlmConfigController spyController = spy(controller);
            TestConnectionResponse mockResponse = new TestConnectionResponse();
            mockResponse.setSuccess(false);
            mockResponse.setMessage("API Key 无效（认证失败）");
            mockResponse.setLatency(200);
            doReturn(mockResponse).when(spyController).doTestConnection(anyString(), anyString());

            TestConnectionRequest request = new TestConnectionRequest();
            request.setBaseUrl("https://api.example.com/v1");
            request.setApiKey("sk-invalid-key");

            Result<TestConnectionResponse> result = spyController.testConnection(request);

            assertTrue(result.isSuccess());
            assertFalse(result.getData().isSuccess());
            assertEquals("API Key 无效（认证失败）", result.getData().getMessage());
        }

        @Test
        @DisplayName("POST /test - 服务端点不存在（404）")
        void testConnectionNotFound() {
            LlmConfigController spyController = spy(controller);
            TestConnectionResponse mockResponse = new TestConnectionResponse();
            mockResponse.setSuccess(false);
            mockResponse.setMessage("服务端点不存在");
            mockResponse.setLatency(100);
            doReturn(mockResponse).when(spyController).doTestConnection(anyString(), anyString());

            TestConnectionRequest request = new TestConnectionRequest();
            request.setBaseUrl("https://api.example.com/v1");
            request.setApiKey("sk-test-key");

            Result<TestConnectionResponse> result = spyController.testConnection(request);

            assertFalse(result.getData().isSuccess());
            assertEquals("服务端点不存在", result.getData().getMessage());
        }

        @Test
        @DisplayName("POST /test - 网络异常时返回失败")
        void testConnectionNetworkError() {
            LlmConfigController spyController = spy(controller);
            TestConnectionResponse mockResponse = new TestConnectionResponse();
            mockResponse.setSuccess(false);
            mockResponse.setMessage("连接失败: Connection refused");
            mockResponse.setLatency(50);
            doReturn(mockResponse).when(spyController).doTestConnection(anyString(), anyString());

            TestConnectionRequest request = new TestConnectionRequest();
            request.setBaseUrl("https://api.example.com/v1");
            request.setApiKey("sk-test-key");

            Result<TestConnectionResponse> result = spyController.testConnection(request);

            assertFalse(result.getData().isSuccess());
            assertTrue(result.getData().getMessage().contains("连接失败"));
        }
    }

    @Nested
    @DisplayName("模型查询")
    class ListModelsTest {

        @Test
        @DisplayName("GET /models - 无 type 过滤返回所有模型")
        void listAllModels() {
            LlmVendorConfig vendor = createVendor("v1", "厂商A", "sk-key");
            when(configStore.getAllVendors()).thenReturn(Collections.singletonList(vendor));

            Result<List<ModelResponse>> result = controller.listModels(null);

            assertTrue(result.isSuccess());
            assertEquals(2, result.getData().size());
            // 验证模型包含厂商信息
            assertEquals("v1", result.getData().get(0).getVendorId());
            assertEquals("厂商A", result.getData().get(0).getVendorName());
        }

        @Test
        @DisplayName("GET /models?type=chat - 按 type 过滤返回 chat 模型")
        void listModelsByType() {
            LlmVendorConfig vendor = createVendor("v1", "厂商A", "sk-key");
            when(configStore.getAllVendors()).thenReturn(Collections.singletonList(vendor));

            Result<List<ModelResponse>> result = controller.listModels("chat");

            assertTrue(result.isSuccess());
            assertEquals(1, result.getData().size());
            assertEquals("chat", result.getData().get(0).getType());
            assertEquals("chat-model", result.getData().get(0).getModelName());
        }

        @Test
        @DisplayName("GET /models?type=embedding - 按 type 过滤返回 embedding 模型")
        void listModelsByTypeEmbedding() {
            LlmVendorConfig vendor = createVendor("v1", "厂商A", "sk-key");
            when(configStore.getAllVendors()).thenReturn(Collections.singletonList(vendor));

            Result<List<ModelResponse>> result = controller.listModels("embedding");

            assertTrue(result.isSuccess());
            assertEquals(1, result.getData().size());
            assertEquals("embedding", result.getData().get(0).getType());
        }

        @Test
        @DisplayName("GET /models - 无厂商时返回空列表")
        void listModelsEmpty() {
            when(configStore.getAllVendors()).thenReturn(Collections.emptyList());

            Result<List<ModelResponse>> result = controller.listModels(null);

            assertTrue(result.isSuccess());
            assertTrue(result.getData().isEmpty());
        }

        @Test
        @DisplayName("GET /models?type=chat - 多厂商场景返回所有 chat 模型")
        void listModelsMultipleVendors() {
            LlmVendorConfig vendor1 = createVendor("v1", "厂商A", "sk-key1");
            LlmVendorConfig vendor2 = createVendor("v2", "厂商B", "sk-key2");
            when(configStore.getAllVendors()).thenReturn(Arrays.asList(vendor1, vendor2));

            Result<List<ModelResponse>> result = controller.listModels("chat");

            assertTrue(result.isSuccess());
            assertEquals(2, result.getData().size());
        }
    }

    @Nested
    @DisplayName("配置状态")
    class StatusTest {

        @Test
        @DisplayName("GET /status - 有 chat 和 embedding 模型时状态正确")
        void statusWithConfig() {
            LlmVendorConfig vendor = createVendor("v1", "厂商A", "sk-key");
            when(configStore.getAllVendors()).thenReturn(Collections.singletonList(vendor));

            Result<ConfigStatusResponse> result = controller.getStatus();

            assertTrue(result.isSuccess());
            ConfigStatusResponse status = result.getData();
            assertTrue(status.isHasConfig());
            assertTrue(status.isHasChatModel());
            assertTrue(status.isHasEmbeddingModel());
            assertEquals(1, status.getVendorCount());
            assertEquals(1, status.getChatModelCount());
        }

        @Test
        @DisplayName("GET /status - 无配置时状态正确")
        void statusNoConfig() {
            when(configStore.getAllVendors()).thenReturn(Collections.emptyList());

            Result<ConfigStatusResponse> result = controller.getStatus();

            assertTrue(result.isSuccess());
            ConfigStatusResponse status = result.getData();
            assertFalse(status.isHasConfig());
            assertFalse(status.isHasChatModel());
            assertFalse(status.isHasEmbeddingModel());
            assertEquals(0, status.getVendorCount());
            assertEquals(0, status.getChatModelCount());
        }

        @Test
        @DisplayName("GET /status - 只有 chat 模型无 embedding 模型时状态正确")
        void statusOnlyChat() {
            LlmVendorConfig vendor = createVendor("v1", "厂商A", "sk-key");
            // 只保留 chat 模型
            vendor.setModels(Collections.singletonList(vendor.getModels().get(0)));
            when(configStore.getAllVendors()).thenReturn(Collections.singletonList(vendor));

            Result<ConfigStatusResponse> result = controller.getStatus();

            ConfigStatusResponse status = result.getData();
            assertTrue(status.isHasChatModel());
            assertFalse(status.isHasEmbeddingModel());
        }
    }

    @Nested
    @DisplayName("同步配置")
    class SyncConfigTest {

        @Test
        @DisplayName("POST /sync - 成功同步多个厂商配置")
        void syncConfigSuccess() {
            VendorRequest vendor1 = createVendorRequest("厂商A");
            VendorRequest vendor2 = createVendorRequest("厂商B");
            SyncConfigRequest request = new SyncConfigRequest();
            request.setVendors(Arrays.asList(vendor1, vendor2));

            Result<Void> result = controller.syncConfig(request);

            assertTrue(result.isSuccess());
            verify(configStore).replaceAll(anyList());
            verify(modelFactory).clearAllCache();
        }

        @Test
        @DisplayName("POST /sync - 空列表时清除所有配置")
        void syncConfigEmpty() {
            SyncConfigRequest request = new SyncConfigRequest();
            request.setVendors(Collections.emptyList());

            Result<Void> result = controller.syncConfig(request);

            assertTrue(result.isSuccess());
            verify(configStore).replaceAll(anyList());
            verify(modelFactory).clearAllCache();
        }

        @Test
        @DisplayName("POST /sync - vendors 为 null 时清除所有配置")
        void syncConfigNullVendors() {
            SyncConfigRequest request = new SyncConfigRequest();
            request.setVendors(null);

            Result<Void> result = controller.syncConfig(request);

            assertTrue(result.isSuccess());
            verify(configStore).replaceAll(anyList());
            verify(modelFactory).clearAllCache();
        }
    }
}
