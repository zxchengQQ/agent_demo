import { defineStore } from 'pinia';
import type { LlmVendor, LlmModel, ConfigStatus, VendorRequest } from '@/types';
import {
  getVendors as apiGetVendors,
  getModels as apiGetModels,
  getConfigStatus as apiGetConfigStatus,
  addVendor as apiAddVendor,
  updateVendor as apiUpdateVendor,
  deleteVendor as apiDeleteVendor,
  syncConfig as apiSyncConfig,
} from '@/api/llm';
import {
  saveLlmConfig,
  loadLlmConfig,
  saveLastUsedModelId,
  loadLastUsedModelId,
} from '@/utils/llm-storage';

/**
 * LLM 厂商模型配置状态管理
 *
 * 业务含义：作为 LLM 配置在各组件间共享的唯一数据源，
 * 封装与后端 /api/llm/config/* 的交互。
 * chatModels 缓存 chat 类型模型列表，供模型选择器使用。
 * lastUsedModelId 持久化到 localStorage，刷新页面后恢复上次选择。
 */

export const useLlmStore = defineStore('llm', {
  state: () => ({
    /** 已配置厂商列表（API Key 脱敏） */
    vendors: [] as LlmVendor[],
    /** 缓存 chat 类型模型列表（用于选择器） */
    chatModels: [] as LlmModel[],
    /** 配置状态 */
    configStatus: null as ConfigStatus | null,
    /** 上次使用的 modelId（持久化到 localStorage） */
    lastUsedModelId: '' as string,
  }),

  actions: {
    /**
     * 从后端获取厂商列表
     */
    async loadVendors() {
      this.vendors = await apiGetVendors();
    },

    /**
     * 从后端获取 chat 模型列表（GET /api/llm/config/models?type=chat）
     * 业务含义：模型选择器只需要 chat 类型模型，单独缓存避免重复请求。
     */
    async loadChatModels() {
      this.chatModels = await apiGetModels('chat');
    },

    /**
     * 从后端获取配置状态
     */
    async loadConfigStatus() {
      this.configStatus = await apiGetConfigStatus();
    },

    /**
     * 添加厂商后刷新 vendors + chatModels
     */
    async addVendor(data: VendorRequest) {
      await apiAddVendor(data);
      // 刷新厂商列表和模型列表，确保 UI 同步
      await Promise.all([this.loadVendors(), this.loadChatModels()]);
    },

    /**
     * 更新厂商后刷新 vendors + chatModels
     */
    async updateVendor(id: string, data: VendorRequest) {
      await apiUpdateVendor(id, data);
      await Promise.all([this.loadVendors(), this.loadChatModels()]);
    },

    /**
     * 删除厂商后刷新 vendors + chatModels
     */
    async deleteVendor(id: string) {
      await apiDeleteVendor(id);
      await Promise.all([this.loadVendors(), this.loadChatModels()]);
    },

    /**
     * 设置上次使用的 modelId，同时持久化到 localStorage
     */
    setLastUsedModelId(modelId: string) {
      this.lastUsedModelId = modelId;
      saveLastUsedModelId(modelId);
    },

    /**
     * 从 localStorage 读取配置推送到后端
     * 业务含义：将本地保存的完整配置（含 API Key 明文）同步到后端生效。
     */
    async syncConfigFromStorage() {
      const vendors = loadLlmConfig();
      if (!vendors || vendors.length === 0) return;
      await apiSyncConfig(vendors);
      // 同步后刷新本地状态
      await Promise.all([this.loadVendors(), this.loadChatModels(), this.loadConfigStatus()]);
    },

    /**
     * 将厂商配置保存到 localStorage
     * 业务含义：配置页面编辑完成后，将完整配置（含 API Key 明文）持久化到本地。
     */
    saveConfigToStorage(vendors: VendorRequest[]) {
      saveLlmConfig(vendors);
    },

    /**
     * 初始化 lastUsedModelId（从 localStorage 恢复）
     */
    initLastUsedModelId() {
      const saved = loadLastUsedModelId();
      if (saved) {
        this.lastUsedModelId = saved;
      }
    },

    /**
     * 页面加载时的配置同步（AC-009）
     * 业务含义：协调后端与本地 localStorage 的配置一致性，避免用户重复配置。
     * - hasConfig=true：后端已有配置，拉取最新 vendors 并写入 localStorage 缓存
     * - hasConfig=false 且 localStorage 有配置：将本地配置推送到后端生效
     * - 均无配置：不操作，由页面引导用户配置
     */
    async initConfigSync() {
      // 1. 获取后端配置状态，作为分支依据
      await this.loadConfigStatus();

      if (this.configStatus?.hasConfig) {
        // 后端已有配置：以后端为权威源，拉取最新列表并更新本地缓存
        await this.loadVendors();
        this.saveConfigToStorage(this.vendors as unknown as VendorRequest[]);
      } else {
        // 后端无配置：尝试将本地配置同步到后端（无本地配置时内部为空操作）
        await this.syncConfigFromStorage();
      }

      // 2. 同步后刷新 chat 模型列表（模型选择器依赖）
      await this.loadChatModels();
    },
  },
});
