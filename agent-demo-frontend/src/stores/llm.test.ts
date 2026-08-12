import { describe, it, expect, beforeEach, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';

/**
 * LLM 配置状态管理测试
 *
 * 测试策略：mock '../api/llm' 和 '../utils/llm-storage' 模块，
 * 仅验证 store 的 state 流转，不依赖真实网络请求。
 */

// mock API 模块
vi.mock('../api/llm', () => ({
  getVendors: vi.fn(),
  getModels: vi.fn(),
  getConfigStatus: vi.fn(),
  addVendor: vi.fn(),
  updateVendor: vi.fn(),
  deleteVendor: vi.fn(),
  syncConfig: vi.fn(),
}));

// mock localStorage 工具模块
vi.mock('../utils/llm-storage', () => ({
  saveLlmConfig: vi.fn(),
  loadLlmConfig: vi.fn(),
  clearLlmConfig: vi.fn(),
  saveLastUsedModelId: vi.fn(),
  loadLastUsedModelId: vi.fn(),
}));

import { useLlmStore } from './llm';
import {
  getVendors,
  getModels,
  getConfigStatus,
  addVendor,
  updateVendor,
  deleteVendor,
  syncConfig,
} from '../api/llm';
import {
  saveLlmConfig,
  loadLlmConfig,
  saveLastUsedModelId,
  loadLastUsedModelId,
} from '../utils/llm-storage';
import type { LlmVendor, LlmModel, ConfigStatus, VendorRequest } from '@/types';

/** 厂商 mock 数据 */
const mockVendor: LlmVendor = {
  id: 'vendor-001',
  name: '火山引擎',
  type: 'predefined',
  baseUrl: 'https://ark.cn-beijing.volces.com/api/v3',
  apiKeyMasked: 'sk-****key',
  apiKeyConfigured: true,
  thinkingTrigger: 'enabled',
  timeout: 60000,
  maxRetries: 3,
  temperature: 0.7,
  models: [],
};

/** 模型 mock 数据 */
const mockChatModel: LlmModel = {
  id: 'model-001',
  vendorId: 'vendor-001',
  vendorName: '火山引擎',
  modelName: 'doubao-seed-2.0-pro',
  displayName: '豆包Seed 2.0 Pro',
  type: 'chat',
  supportsVision: true,
};

/** 配置状态 mock 数据 */
const mockConfigStatus: ConfigStatus = {
  hasConfig: true,
  hasChatModel: true,
  hasEmbeddingModel: false,
  vendorCount: 1,
  chatModelCount: 1,
};

/** 构造测试用 VendorRequest */
function makeVendorRequest(): VendorRequest {
  return {
    name: '火山引擎',
    type: 'predefined',
    baseUrl: 'https://ark.cn-beijing.volces.com/api/v3',
    apiKey: 'sk-test-key',
    thinkingTrigger: 'enabled',
    timeout: 60000,
    maxRetries: 3,
    temperature: 0.7,
    models: [
      { modelName: 'doubao-seed-2.0-pro', displayName: '豆包Seed 2.0 Pro', type: 'chat', supportsVision: true },
    ],
  };
}

describe('Llm Store', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    localStorage.clear();
  });

  // ===== loadVendors =====
  it('loadVendors 调用后 vendors 填充为数组', async () => {
    vi.mocked(getVendors).mockResolvedValue([mockVendor]);
    const store = useLlmStore();

    await store.loadVendors();

    expect(getVendors).toHaveBeenCalledOnce();
    expect(store.vendors).toHaveLength(1);
    expect(store.vendors[0].id).toBe('vendor-001');
  });

  // ===== loadChatModels =====
  it('loadChatModels 调用后 chatModels 填充为 chat 类型模型', async () => {
    vi.mocked(getModels).mockResolvedValue([mockChatModel]);
    const store = useLlmStore();

    await store.loadChatModels();

    expect(getModels).toHaveBeenCalledWith('chat');
    expect(store.chatModels).toHaveLength(1);
    expect(store.chatModels[0].type).toBe('chat');
  });

  // ===== loadConfigStatus =====
  it('loadConfigStatus 调用后 configStatus 填充', async () => {
    vi.mocked(getConfigStatus).mockResolvedValue(mockConfigStatus);
    const store = useLlmStore();

    await store.loadConfigStatus();

    expect(store.configStatus).toEqual(mockConfigStatus);
    expect(store.configStatus?.hasConfig).toBe(true);
  });

  // ===== addVendor =====
  it('addVendor 调用 API 后刷新 vendors 和 chatModels', async () => {
    vi.mocked(addVendor).mockResolvedValue(mockVendor);
    vi.mocked(getVendors).mockResolvedValue([mockVendor]);
    vi.mocked(getModels).mockResolvedValue([mockChatModel]);
    const store = useLlmStore();
    const data = makeVendorRequest();

    await store.addVendor(data);

    expect(addVendor).toHaveBeenCalledWith(data);
    expect(store.vendors).toHaveLength(1);
    expect(store.chatModels).toHaveLength(1);
  });

  // ===== updateVendor =====
  it('updateVendor 调用 API 后刷新 vendors 和 chatModels', async () => {
    vi.mocked(updateVendor).mockResolvedValue(mockVendor);
    vi.mocked(getVendors).mockResolvedValue([mockVendor]);
    vi.mocked(getModels).mockResolvedValue([mockChatModel]);
    const store = useLlmStore();
    const data = makeVendorRequest();

    await store.updateVendor('vendor-001', data);

    expect(updateVendor).toHaveBeenCalledWith('vendor-001', data);
    expect(store.vendors).toHaveLength(1);
    expect(store.chatModels).toHaveLength(1);
  });

  // ===== deleteVendor =====
  it('deleteVendor 调用 API 后刷新 vendors 和 chatModels', async () => {
    vi.mocked(deleteVendor).mockResolvedValue(undefined);
    vi.mocked(getVendors).mockResolvedValue([]);
    vi.mocked(getModels).mockResolvedValue([]);
    const store = useLlmStore();
    store.vendors = [mockVendor];

    await store.deleteVendor('vendor-001');

    expect(deleteVendor).toHaveBeenCalledWith('vendor-001');
    expect(store.vendors).toHaveLength(0);
    expect(store.chatModels).toHaveLength(0);
  });

  // ===== setLastUsedModelId =====
  it('setLastUsedModelId 更新 state 并持久化到 localStorage', () => {
    const store = useLlmStore();

    store.setLastUsedModelId('doubao-seed-2.0-pro');

    expect(store.lastUsedModelId).toBe('doubao-seed-2.0-pro');
    expect(saveLastUsedModelId).toHaveBeenCalledWith('doubao-seed-2.0-pro');
  });

  // ===== syncConfigFromStorage =====
  it('syncConfigFromStorage 从 localStorage 读取配置并推送到后端', async () => {
    const vendors = [makeVendorRequest()];
    vi.mocked(loadLlmConfig).mockReturnValue(vendors);
    vi.mocked(syncConfig).mockResolvedValue({ vendorCount: 1, modelCount: 1 });
    vi.mocked(getVendors).mockResolvedValue([mockVendor]);
    vi.mocked(getModels).mockResolvedValue([mockChatModel]);
    vi.mocked(getConfigStatus).mockResolvedValue(mockConfigStatus);
    const store = useLlmStore();

    await store.syncConfigFromStorage();

    expect(loadLlmConfig).toHaveBeenCalledOnce();
    expect(syncConfig).toHaveBeenCalledWith(vendors);
    expect(store.vendors).toHaveLength(1);
    expect(store.chatModels).toHaveLength(1);
    expect(store.configStatus).toEqual(mockConfigStatus);
  });

  it('syncConfigFromStorage 无本地配置时不调用 syncConfig', async () => {
    vi.mocked(loadLlmConfig).mockReturnValue(null);
    const store = useLlmStore();

    await store.syncConfigFromStorage();

    expect(syncConfig).not.toHaveBeenCalled();
  });

  it('syncConfigFromStorage 空配置数组时不调用 syncConfig', async () => {
    vi.mocked(loadLlmConfig).mockReturnValue([]);
    const store = useLlmStore();

    await store.syncConfigFromStorage();

    expect(syncConfig).not.toHaveBeenCalled();
  });

  // ===== saveConfigToStorage =====
  it('saveConfigToStorage 将配置保存到 localStorage', () => {
    const store = useLlmStore();
    const vendors = [makeVendorRequest()];

    store.saveConfigToStorage(vendors);

    expect(saveLlmConfig).toHaveBeenCalledWith(vendors);
  });

  // ===== initLastUsedModelId =====
  it('initLastUsedModelId 从 localStorage 恢复上次使用的 modelId', () => {
    vi.mocked(loadLastUsedModelId).mockReturnValue('model-restored');
    const store = useLlmStore();

    store.initLastUsedModelId();

    expect(store.lastUsedModelId).toBe('model-restored');
  });

  it('initLastUsedModelId 无历史记录时保持空字符串', () => {
    vi.mocked(loadLastUsedModelId).mockReturnValue(null);
    const store = useLlmStore();

    store.initLastUsedModelId();

    expect(store.lastUsedModelId).toBe('');
  });

  // ===== initConfigSync（AC-009 配置同步）=====
  it('initConfigSync: hasConfig=true 时拉取后端配置并更新 localStorage', async () => {
    vi.mocked(getConfigStatus).mockResolvedValue({ ...mockConfigStatus, hasConfig: true });
    vi.mocked(getVendors).mockResolvedValue([mockVendor]);
    vi.mocked(getModels).mockResolvedValue([mockChatModel]);
    const store = useLlmStore();

    await store.initConfigSync();

    // 拉取后端配置状态 + 厂商列表
    expect(getConfigStatus).toHaveBeenCalledOnce();
    expect(getVendors).toHaveBeenCalledOnce();
    // 后端配置作为权威源，写入 localStorage 缓存
    expect(saveLlmConfig).toHaveBeenCalled();
    // 同步后刷新 chat 模型列表
    expect(store.vendors).toHaveLength(1);
    expect(store.chatModels).toHaveLength(1);
  });

  it('initConfigSync: hasConfig=false 且有本地配置时调用 syncConfig', async () => {
    vi.mocked(getConfigStatus).mockResolvedValue({ ...mockConfigStatus, hasConfig: false });
    vi.mocked(loadLlmConfig).mockReturnValue([makeVendorRequest()]);
    vi.mocked(syncConfig).mockResolvedValue({ vendorCount: 1, modelCount: 1 });
    vi.mocked(getVendors).mockResolvedValue([mockVendor]);
    vi.mocked(getModels).mockResolvedValue([mockChatModel]);
    const store = useLlmStore();

    await store.initConfigSync();

    // 后端无配置时，将本地配置推送到后端
    expect(syncConfig).toHaveBeenCalledOnce();
    // 同步后刷新 vendors 和 chatModels
    expect(store.vendors).toHaveLength(1);
    expect(store.chatModels).toHaveLength(1);
  });

  it('initConfigSync: hasConfig=false 且无本地配置时不调用 syncConfig', async () => {
    vi.mocked(getConfigStatus).mockResolvedValue({ ...mockConfigStatus, hasConfig: false });
    vi.mocked(loadLlmConfig).mockReturnValue(null);
    const store = useLlmStore();

    await store.initConfigSync();

    expect(syncConfig).not.toHaveBeenCalled();
  });
});
