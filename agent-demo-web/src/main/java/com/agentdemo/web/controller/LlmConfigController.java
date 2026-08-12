package com.agentdemo.web.controller;

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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * LLM 配置管理接口
 * <p>
 * 业务含义：提供 LLM 厂商和模型的动态配置管理 REST API，
 * 支持厂商 CRUD、预定义厂商查询、API Key 连接测试、模型列表查询、配置状态查询和批量同步。
 * 所有接口路径统一 /api/llm/config 前缀，返回值用 Result 包装。
 * </p>
 */
@Tag(name = "LLM 配置管理", description = "LLM 厂商和模型的动态配置管理")
@RestController
@RequestMapping("/api/llm/config")
public class LlmConfigController {

    private static final Logger log = LoggerFactory.getLogger(LlmConfigController.class);

    private final LlmConfigStore configStore;
    private final PredefinedVendorCatalog predefinedCatalog;
    private final ModelFactory modelFactory;

    public LlmConfigController(LlmConfigStore configStore,
                               PredefinedVendorCatalog predefinedCatalog,
                               ModelFactory modelFactory) {
        this.configStore = configStore;
        this.predefinedCatalog = predefinedCatalog;
        this.modelFactory = modelFactory;
    }

    // ==================== 厂商 CRUD ====================

    /**
     * 获取预定义厂商列表
     * <p>
     * 业务含义：返回系统内置的厂商配置模板，前端"添加厂商"页面据此展示选项。
     * </p>
     *
     * @return 预定义厂商列表
     */
    @Operation(summary = "获取预定义厂商列表", description = "返回系统内置的 LLM 厂商配置模板")
    @GetMapping("/predefined")
    public Result<List<PredefinedVendorResponse>> getPredefinedVendors() {
        List<PredefinedVendorResponse> responses = predefinedCatalog.getPredefinedVendors().stream()
                .map(this::toPredefinedVendorResponse)
                .toList();
        return Result.success(responses);
    }

    /**
     * 获取已配置厂商列表
     * <p>
     * 业务含义：返回所有已配置的厂商，API Key 脱敏处理，避免明文泄露。
     * </p>
     *
     * @return 厂商列表（API Key 脱敏）
     */
    @Operation(summary = "获取已配置厂商列表", description = "返回所有已配置厂商，API Key 脱敏")
    @GetMapping("/vendors")
    public Result<List<VendorResponse>> listVendors() {
        List<VendorResponse> responses = configStore.getAllVendors().stream()
                .map(this::toVendorResponse)
                .toList();
        return Result.success(responses);
    }

    /**
     * 添加厂商
     * <p>
     * 业务含义：新增一个 LLM 厂商配置，包括基本信息和模型列表。
     * 添加后需清除模型工厂缓存，确保新厂商的模型实例能被正确创建。
     * </p>
     *
     * @param request 厂商配置请求
     * @return 添加后的厂商信息（API Key 脱敏）
     */
    @Operation(summary = "添加厂商", description = "新增 LLM 厂商配置")
    @PostMapping("/vendors")
    public Result<VendorResponse> addVendor(@Valid @RequestBody VendorRequest request) {
        LlmVendorConfig vendor = toLlmVendorConfig(request);
        LlmVendorConfig saved = configStore.addVendor(vendor);
        // 添加厂商后清除所有缓存，确保新模型实例能被创建
        modelFactory.clearAllCache();
        return Result.success(toVendorResponse(saved));
    }

    /**
     * 编辑厂商
     * <p>
     * 业务含义：更新已存在的厂商配置，API Key 为空时保留原值。
     * 更新后需清除模型工厂缓存，确保使用新配置创建模型实例。
     * </p>
     *
     * @param vendorId 厂商 ID
     * @param request  厂商配置请求
     * @return 更新后的厂商信息（API Key 脱敏）
     */
    @Operation(summary = "编辑厂商", description = "更新已存在的厂商配置")
    @PutMapping("/vendors/{vendorId}")
    public Result<VendorResponse> updateVendor(@PathVariable String vendorId,
                                                @Valid @RequestBody VendorRequest request) {
        LlmVendorConfig vendor = toLlmVendorConfig(request);
        LlmVendorConfig updated = configStore.updateVendor(vendorId, vendor);
        // 更新厂商后清除该厂商的模型缓存
        modelFactory.clearCacheForVendor(updated.getId());
        return Result.success(toVendorResponse(updated));
    }

    /**
     * 删除厂商
     * <p>
     * 业务含义：删除指定厂商及其所有模型配置。
     * 删除后需清除模型工厂缓存，避免使用已删除厂商的缓存实例。
     * </p>
     *
     * @param vendorId 厂商 ID
     * @return 操作结果
     */
    @Operation(summary = "删除厂商", description = "删除指定厂商及其所有模型配置")
    @DeleteMapping("/vendors/{vendorId}")
    public Result<Void> deleteVendor(@PathVariable String vendorId) {
        configStore.deleteVendor(vendorId);
        // 删除厂商后清除该厂商的模型缓存
        modelFactory.clearCacheForVendor(vendorId);
        return Result.success();
    }

    // ==================== 测试连接 ====================

    /**
     * 测试 API Key 连接
     * <p>
     * 业务含义：向 LLM 厂商的 /models 端点发送 GET 请求，验证 API Key 和连通性。
     * 根据响应状态码返回不同的错误信息，便于前端展示具体原因。
     * </p>
     *
     * @param request 测试连接请求（baseUrl + apiKey）
     * @return 连接测试结果（success/message/latency）
     */
    @Operation(summary = "测试连接", description = "验证 API Key 和服务端连通性")
    @PostMapping("/test")
    public Result<TestConnectionResponse> testConnection(@Valid @RequestBody TestConnectionRequest request) {
        TestConnectionResponse response = doTestConnection(request.getBaseUrl(), request.getApiKey());
        return Result.success(response);
    }

    /**
     * 执行 HTTP 连接测试（protected 便于测试 spy 覆盖）
     * <p>
     * 业务含义：使用 Java HttpClient 向 {baseUrl}/models 发送 GET 请求，
     * 通过 Authorization: Bearer {apiKey} 头进行认证。
     * </p>
     *
     * @param baseUrl API Base URL
     * @param apiKey  API Key
     * @return 测试结果
     */
    protected TestConnectionResponse doTestConnection(String baseUrl, String apiKey) {
        long startTime = System.currentTimeMillis();
        try {
            // 拼接 /models 端点 URL
            String url = baseUrl.endsWith("/") ? baseUrl + "models" : baseUrl + "/models";

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> httpResponse = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - startTime;

            // 根据响应状态码判断结果
            int statusCode = httpResponse.statusCode();
            if (statusCode == 200) {
                TestConnectionResponse response = new TestConnectionResponse();
                response.setSuccess(true);
                response.setMessage("连接成功");
                response.setLatency(latency);
                return response;
            } else if (statusCode == 401) {
                TestConnectionResponse response = new TestConnectionResponse();
                response.setSuccess(false);
                response.setMessage("API Key 无效（认证失败）");
                response.setLatency(latency);
                return response;
            } else if (statusCode == 404) {
                TestConnectionResponse response = new TestConnectionResponse();
                response.setSuccess(false);
                response.setMessage("服务端点不存在");
                response.setLatency(latency);
                return response;
            } else {
                TestConnectionResponse response = new TestConnectionResponse();
                response.setSuccess(false);
                response.setMessage("服务端返回状态码: " + statusCode);
                response.setLatency(latency);
                return response;
            }
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - startTime;
            log.warn("连接测试失败: baseUrl={}, error={}", baseUrl, e.getMessage());
            TestConnectionResponse response = new TestConnectionResponse();
            response.setSuccess(false);
            response.setMessage("连接失败: " + e.getMessage());
            response.setLatency(latency);
            return response;
        }
    }

    // ==================== 模型查询 ====================

    /**
     * 获取模型列表
     * <p>
     * 业务含义：遍历所有已配置厂商的模型，可选按 type 过滤。
     * 返回的每个模型包含所属厂商信息，便于前端展示模型归属。
     * </p>
     *
     * @param type 模型类型过滤（可选，如 chat/embedding）
     * @return 模型列表
     */
    @Operation(summary = "获取模型列表", description = "返回所有已配置模型，可选按类型过滤")
    @GetMapping("/models")
    public Result<List<ModelResponse>> listModels(@RequestParam(required = false) String type) {
        List<ModelResponse> result = new ArrayList<>();
        for (LlmVendorConfig vendor : configStore.getAllVendors()) {
            if (vendor.getModels() != null) {
                for (LlmModelConfig model : vendor.getModels()) {
                    // type 为空时返回所有模型，否则按 type 过滤
                    if (type == null || type.isEmpty() || type.equals(model.getType())) {
                        result.add(toModelResponse(model, vendor));
                    }
                }
            }
        }
        return Result.success(result);
    }

    // ==================== 配置状态 ====================

    /**
     * 获取配置状态
     * <p>
     * 业务含义：返回当前 LLM 配置的整体状态，前端据此判断系统是否可用。
     * </p>
     *
     * @return 配置状态
     */
    @Operation(summary = "获取配置状态", description = "返回当前 LLM 配置的整体状态")
    @GetMapping("/status")
    public Result<ConfigStatusResponse> getStatus() {
        ConfigStatusResponse response = new ConfigStatusResponse();
        List<LlmVendorConfig> vendors = configStore.getAllVendors();
        response.setHasConfig(!vendors.isEmpty());
        response.setVendorCount(vendors.size());

        int chatModelCount = 0;
        boolean hasEmbedding = false;
        for (LlmVendorConfig vendor : vendors) {
            if (vendor.getModels() != null) {
                for (LlmModelConfig model : vendor.getModels()) {
                    if ("chat".equals(model.getType())) {
                        chatModelCount++;
                    }
                    if ("embedding".equals(model.getType())) {
                        hasEmbedding = true;
                    }
                }
            }
        }
        response.setHasChatModel(chatModelCount > 0);
        response.setHasEmbeddingModel(hasEmbedding);
        response.setChatModelCount(chatModelCount);
        return Result.success(response);
    }

    // ==================== 同步配置 ====================

    /**
     * 同步配置
     * <p>
     * 业务含义：接收完整的厂商配置列表，替换所有现有配置。
     * 适用于配置导入、批量更新等场景。同步后需清除模型工厂全部缓存。
     * </p>
     *
     * @param request 同步配置请求
     * @return 操作结果
     */
    @Operation(summary = "同步配置", description = "批量替换所有厂商配置")
    @PostMapping("/sync")
    public Result<Void> syncConfig(@Valid @RequestBody SyncConfigRequest request) {
        List<LlmVendorConfig> vendors = new ArrayList<>();
        if (request.getVendors() != null) {
            for (VendorRequest vendorRequest : request.getVendors()) {
                vendors.add(toLlmVendorConfig(vendorRequest));
            }
        }
        configStore.replaceAll(vendors);
        // 替换所有配置后清除全部缓存
        modelFactory.clearAllCache();
        return Result.success();
    }

    // ==================== 实体转 DTO ====================

    /**
     * LlmVendorConfig 转 VendorResponse（API Key 脱敏）
     */
    private VendorResponse toVendorResponse(LlmVendorConfig vendor) {
        VendorResponse response = new VendorResponse();
        response.setId(vendor.getId());
        response.setName(vendor.getName());
        response.setType(vendor.getType());
        response.setBaseUrl(vendor.getBaseUrl());
        response.setApiKeyMasked(maskApiKey(vendor.getApiKey()));
        response.setApiKeyConfigured(vendor.getApiKey() != null && !vendor.getApiKey().isBlank());
        response.setThinkingTrigger(vendor.getThinkingTrigger());
        response.setTimeout(vendor.getTimeout() != null ? vendor.getTimeout().getSeconds() : 60);
        response.setMaxRetries(vendor.getMaxRetries());
        response.setTemperature(vendor.getTemperature());
        if (vendor.getModels() != null) {
            response.setModels(vendor.getModels().stream()
                    .map(model -> toModelResponse(model, vendor))
                    .toList());
        }
        return response;
    }

    /**
     * LlmModelConfig 转 ModelResponse
     */
    private ModelResponse toModelResponse(LlmModelConfig model, LlmVendorConfig vendor) {
        ModelResponse response = new ModelResponse();
        response.setId(model.getId());
        response.setVendorId(vendor.getId());
        response.setVendorName(vendor.getName());
        response.setModelName(model.getModelName());
        response.setDisplayName(model.getDisplayName());
        response.setType(model.getType());
        response.setSupportsVision(model.isSupportsVision());
        return response;
    }

    /**
     * PredefinedVendor 转 PredefinedVendorResponse
     */
    private PredefinedVendorResponse toPredefinedVendorResponse(PredefinedVendor vendor) {
        PredefinedVendorResponse response = new PredefinedVendorResponse();
        response.setCode(vendor.getCode());
        response.setName(vendor.getName());
        response.setBaseUrl(vendor.getBaseUrl());
        response.setThinkingTrigger(vendor.getThinkingTrigger());
        if (vendor.getModels() != null) {
            response.setModels(vendor.getModels().stream()
                    .map(this::toPredefinedModelItem)
                    .toList());
        }
        return response;
    }

    /**
     * PredefinedModel 转 PredefinedModelItem
     */
    private PredefinedVendorResponse.PredefinedModelItem toPredefinedModelItem(PredefinedModel model) {
        PredefinedVendorResponse.PredefinedModelItem item = new PredefinedVendorResponse.PredefinedModelItem();
        item.setModelName(model.getModelName());
        item.setDisplayName(model.getDisplayName());
        item.setType(model.getType());
        item.setSupportsVision(model.isSupportsVision());
        return item;
    }

    /**
     * VendorRequest 转 LlmVendorConfig
     */
    private LlmVendorConfig toLlmVendorConfig(VendorRequest request) {
        LlmVendorConfig vendor = new LlmVendorConfig();
        vendor.setName(request.getName());
        vendor.setType(request.getType());
        vendor.setBaseUrl(request.getBaseUrl());
        vendor.setApiKey(request.getApiKey());
        vendor.setThinkingTrigger(request.getThinkingTrigger());
        vendor.setTimeout(Duration.ofSeconds(request.getTimeout()));
        vendor.setMaxRetries(request.getMaxRetries());
        vendor.setTemperature(request.getTemperature());
        if (request.getModels() != null) {
            List<LlmModelConfig> models = new ArrayList<>();
            for (VendorRequest.ModelItem item : request.getModels()) {
                LlmModelConfig model = new LlmModelConfig();
                model.setModelName(item.getModelName());
                model.setDisplayName(item.getDisplayName());
                model.setType(item.getType());
                model.setSupportsVision(item.isSupportsVision());
                models.add(model);
            }
            vendor.setModels(models);
        }
        return vendor;
    }

    /**
     * API Key 脱敏处理
     * <p>
     * 业务含义：保留前 3 位和后 4 位，中间用 **** 替代，避免明文泄露。
     * Key 过短时直接返回 ****。
     * </p>
     *
     * @param apiKey 原始 API Key
     * @return 脱敏后的 API Key
     */
    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() <= 8) {
            return "****";
        }
        return apiKey.substring(0, 3) + "****" + apiKey.substring(apiKey.length() - 4);
    }
}
