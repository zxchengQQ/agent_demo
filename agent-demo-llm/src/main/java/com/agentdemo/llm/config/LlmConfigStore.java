package com.agentdemo.llm.config;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM 配置内存存储
 * <p>
 * 业务含义：作为 LLM 厂商和模型配置的唯一事实来源（Single Source of Truth），
 * 所有配置 CRUD 操作通过此类进行，ModelFactory 从此处读取配置创建模型实例。
 * 使用 ConcurrentHashMap 保证线程安全。
 * </p>
 */
@Component
public class LlmConfigStore {
    private final ConcurrentHashMap<String, LlmVendorConfig> vendors = new ConcurrentHashMap<>();

    public LlmVendorConfig addVendor(LlmVendorConfig vendor) {
        // 参数校验
        validateVendorFields(vendor);
        // 名称唯一性校验
        checkVendorNameUnique(vendor.getName(), null);
        // 模型校验
        validateModels(vendor.getModels());
        checkModelNamesUnique(vendor.getModels());

        // 生成 ID
        vendor.setId(generateId());
        // 为模型生成 ID 并设置 vendorId
        if (vendor.getModels() != null) {
            for (LlmModelConfig model : vendor.getModels()) {
                model.setId(generateId());
                model.setVendorId(vendor.getId());
            }
        }

        vendors.put(vendor.getId(), vendor);
        return vendor;
    }

    public LlmVendorConfig updateVendor(String id, LlmVendorConfig update) {
        LlmVendorConfig existing = vendors.get(id);
        if (existing == null) {
            throw new BusinessException(ErrorCode.LLM_VENDOR_NOT_FOUND, "厂商不存在: " + id);
        }
        // 名称唯一性校验（排除自身）
        checkVendorNameUnique(update.getName(), id);
        // 字段校验
        if (update.getName() == null || update.getName().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "厂商名称不能为空");
        }
        if (update.getBaseUrl() == null || update.getBaseUrl().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "Base URL 不能为空");
        }
        // 模型校验
        if (update.getModels() != null) {
            validateModels(update.getModels());
            checkModelNamesUnique(update.getModels());
        }

        // 更新字段
        existing.setName(update.getName());
        existing.setType(update.getType());
        existing.setBaseUrl(update.getBaseUrl());
        existing.setThinkingTrigger(update.getThinkingTrigger());
        existing.setTimeout(update.getTimeout());
        existing.setMaxRetries(update.getMaxRetries());
        existing.setTemperature(update.getTemperature());
        // API Key 为空时保留原值
        if (update.getApiKey() != null && !update.getApiKey().isBlank()) {
            existing.setApiKey(update.getApiKey());
        }
        // 更新模型列表
        if (update.getModels() != null) {
            for (LlmModelConfig model : update.getModels()) {
                if (model.getId() == null) {
                    model.setId(generateId());
                }
                model.setVendorId(existing.getId());
            }
            existing.setModels(update.getModels());
        }

        return existing;
    }

    public void deleteVendor(String id) {
        LlmVendorConfig removed = vendors.remove(id);
        if (removed == null) {
            throw new BusinessException(ErrorCode.LLM_VENDOR_NOT_FOUND, "厂商不存在: " + id);
        }
    }

    public LlmVendorConfig getVendor(String id) {
        return vendors.get(id);
    }

    public List<LlmVendorConfig> getAllVendors() {
        return new ArrayList<>(vendors.values());
    }

    public LlmModelConfig getModel(String modelId) {
        for (LlmVendorConfig vendor : vendors.values()) {
            if (vendor.getModels() != null) {
                for (LlmModelConfig model : vendor.getModels()) {
                    if (model.getId().equals(modelId)) {
                        return model;
                    }
                }
            }
        }
        return null;
    }

    public LlmModelConfig getFirstChatModel() {
        for (LlmVendorConfig vendor : vendors.values()) {
            if (vendor.getModels() != null) {
                for (LlmModelConfig model : vendor.getModels()) {
                    if ("chat".equals(model.getType())) {
                        return model;
                    }
                }
            }
        }
        return null;
    }

    public LlmModelConfig getFirstEmbeddingModel() {
        for (LlmVendorConfig vendor : vendors.values()) {
            if (vendor.getModels() != null) {
                for (LlmModelConfig model : vendor.getModels()) {
                    if ("embedding".equals(model.getType())) {
                        return model;
                    }
                }
            }
        }
        return null;
    }

    public boolean hasConfig() {
        return !vendors.isEmpty();
    }

    public void clear() {
        vendors.clear();
    }

    public void replaceAll(List<LlmVendorConfig> newVendors) {
        clear();
        if (newVendors != null) {
            for (LlmVendorConfig vendor : newVendors) {
                if (vendor.getId() == null) {
                    vendor.setId(generateId());
                }
                if (vendor.getModels() != null) {
                    for (LlmModelConfig model : vendor.getModels()) {
                        if (model.getId() == null) {
                            model.setId(generateId());
                        }
                        model.setVendorId(vendor.getId());
                    }
                }
                vendors.put(vendor.getId(), vendor);
            }
        }
    }

    // ========== 私有校验方法 ==========

    private void validateVendorFields(LlmVendorConfig vendor) {
        if (vendor.getName() == null || vendor.getName().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "厂商名称不能为空");
        }
        if (vendor.getBaseUrl() == null || vendor.getBaseUrl().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "Base URL 不能为空");
        }
        if (vendor.getApiKey() == null || vendor.getApiKey().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "API Key 不能为空");
        }
    }

    private void checkVendorNameUnique(String name, String excludeId) {
        for (LlmVendorConfig vendor : vendors.values()) {
            if (!vendor.getId().equals(excludeId) && vendor.getName().equals(name)) {
                throw new BusinessException(ErrorCode.LLM_VENDOR_NAME_EXISTS, "厂商名称已存在: " + name);
            }
        }
    }

    private void validateModels(List<LlmModelConfig> models) {
        if (models == null) return;
        for (LlmModelConfig model : models) {
            if (model.getModelName() == null || model.getModelName().isBlank()) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "模型名称不能为空");
            }
        }
    }

    private void checkModelNamesUnique(List<LlmModelConfig> models) {
        if (models == null) return;
        for (int i = 0; i < models.size(); i++) {
            for (int j = i + 1; j < models.size(); j++) {
                if (models.get(i).getType().equals(models.get(j).getType())
                        && models.get(i).getModelName().equals(models.get(j).getModelName())) {
                    throw new BusinessException(ErrorCode.LLM_MODEL_NAME_EXISTS,
                        "同类型同名模型已存在: " + models.get(i).getModelName());
                }
            }
        }
    }

    private String generateId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
