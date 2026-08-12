// @vitest-environment node
import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  getPredefinedVendors,
  getVendors,
  addVendor,
  updateVendor,
  deleteVendor,
  testConnection,
  getModels,
  getConfigStatus,
  syncConfig,
} from './llm';
import type {
  LlmVendor,
  LlmModel,
  PredefinedVendor,
  TestConnectionResult,
  ConfigStatus,
  VendorRequest,
} from '@/types';

/**
 * LLM 配置 API 封装测试
 * 验证每个函数的请求路径、方法和响应解析。
 */

/** mock fetch 的辅助方法，构造 Result<T> 响应 */
function mockFetchSuccess<T>(data: T): void {
  vi.mocked(global.fetch).mockResolvedValueOnce({
    ok: true,
    json: async () => ({ success: true, code: 200, message: '成功', data, traceId: 'test' }),
  } as Response);
}

/** mock fetch 失败响应（后端返回业务错误） */
function mockFetchBusinessError(message: string): void {
  vi.mocked(global.fetch).mockResolvedValueOnce({
    ok: true,
    json: async () => ({ success: false, code: 5307, message, data: null, traceId: 'test' }),
  } as Response);
}

/** mock fetch 网络错误（response.ok=false，无 JSON body） */
function mockFetchNetworkError(): void {
  vi.mocked(global.fetch).mockResolvedValueOnce({
    ok: false,
    json: async () => { throw new Error('parse error'); },
  } as Response);
}

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

/** 构造测试用 LlmVendor */
function makeLlmVendor(): LlmVendor {
  return {
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
    models: [
      { id: 'model-001', vendorId: 'vendor-001', vendorName: '火山引擎', modelName: 'doubao-seed-2.0-pro', displayName: '豆包Seed 2.0 Pro', type: 'chat', supportsVision: true },
    ],
  };
}

beforeEach(() => {
  vi.restoreAllMocks();
  global.fetch = vi.fn();
});

describe('LLM 配置 API 封装', () => {
  it('getPredefinedVendors 发送 GET 请求并返回预定义厂商列表', async () => {
    const mockList: PredefinedVendor[] = [
      {
        code: 'volcengine',
        name: '火山引擎',
        baseUrl: 'https://ark.cn-beijing.volces.com/api/v3',
        thinkingTrigger: 'enabled',
        models: [
          { modelName: 'doubao-seed-2.0-pro', displayName: '豆包Seed 2.0 Pro', type: 'chat', supportsVision: true },
        ],
      },
    ];
    mockFetchSuccess(mockList);

    const result = await getPredefinedVendors();
    expect(global.fetch).toHaveBeenCalledWith('/api/llm/config/predefined', undefined);
    expect(result).toEqual(mockList);
    expect(result).toHaveLength(1);
  });

  it('getVendors 发送 GET 请求并返回已配置厂商列表', async () => {
    const mockVendors: LlmVendor[] = [makeLlmVendor()];
    mockFetchSuccess(mockVendors);

    const result = await getVendors();
    expect(global.fetch).toHaveBeenCalledWith('/api/llm/config/vendors', undefined);
    expect(result).toEqual(mockVendors);
  });

  it('addVendor 发送 POST 请求并返回新创建的厂商', async () => {
    const mockVendor = makeLlmVendor();
    mockFetchSuccess(mockVendor);
    const data = makeVendorRequest();

    const result = await addVendor(data);
    expect(global.fetch).toHaveBeenCalledWith(
      '/api/llm/config/vendors',
      expect.objectContaining({
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
      }),
    );
    expect(result).toEqual(mockVendor);
  });

  it('updateVendor 发送 PUT 请求到 /vendors/{id} 并返回更新后的厂商', async () => {
    const mockVendor = makeLlmVendor();
    mockFetchSuccess(mockVendor);
    const data = makeVendorRequest();

    const result = await updateVendor('vendor-001', data);
    expect(global.fetch).toHaveBeenCalledWith(
      '/api/llm/config/vendors/vendor-001',
      expect.objectContaining({
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
      }),
    );
    expect(result).toEqual(mockVendor);
  });

  it('deleteVendor 发送 DELETE 请求到 /vendors/{id}', async () => {
    mockFetchSuccess(null);

    await deleteVendor('vendor-001');
    expect(global.fetch).toHaveBeenCalledWith(
      '/api/llm/config/vendors/vendor-001',
      expect.objectContaining({ method: 'DELETE' }),
    );
  });

  it('testConnection 发送 POST 请求并返回测试结果', async () => {
    const mockResult: TestConnectionResult = {
      success: true,
      message: '连接成功',
      latency: 120,
    };
    mockFetchSuccess(mockResult);

    const result = await testConnection('https://ark.cn-beijing.volces.com/api/v3', 'sk-test-key');
    expect(global.fetch).toHaveBeenCalledWith(
      '/api/llm/config/test',
      expect.objectContaining({
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ baseUrl: 'https://ark.cn-beijing.volces.com/api/v3', apiKey: 'sk-test-key' }),
      }),
    );
    expect(result).toEqual(mockResult);
  });

  it('getModels 无 type 参数时发送 GET /models', async () => {
    const mockModels: LlmModel[] = [
      { id: 'm1', vendorId: 'v1', vendorName: '厂商A', modelName: 'model-a', displayName: '模型A', type: 'chat', supportsVision: false },
    ];
    mockFetchSuccess(mockModels);

    const result = await getModels();
    expect(global.fetch).toHaveBeenCalledWith('/api/llm/config/models', undefined);
    expect(result).toEqual(mockModels);
  });

  it('getModels 带 type 参数时发送 GET /models?type={type}', async () => {
    const mockModels: LlmModel[] = [
      { id: 'm1', vendorId: 'v1', vendorName: '厂商A', modelName: 'model-a', displayName: '模型A', type: 'chat', supportsVision: false },
    ];
    mockFetchSuccess(mockModels);

    const result = await getModels('chat');
    expect(global.fetch).toHaveBeenCalledWith('/api/llm/config/models?type=chat', undefined);
    expect(result).toEqual(mockModels);
  });

  it('getConfigStatus 发送 GET 请求并返回配置状态', async () => {
    const mockStatus: ConfigStatus = {
      hasConfig: true,
      hasChatModel: true,
      hasEmbeddingModel: false,
      vendorCount: 1,
      chatModelCount: 2,
    };
    mockFetchSuccess(mockStatus);

    const result = await getConfigStatus();
    expect(global.fetch).toHaveBeenCalledWith('/api/llm/config/status', undefined);
    expect(result).toEqual(mockStatus);
  });

  it('syncConfig 发送 POST 请求并返回同步结果', async () => {
    const mockResult = { vendorCount: 2, modelCount: 5 };
    mockFetchSuccess(mockResult);
    const vendors = [makeVendorRequest()];

    const result = await syncConfig(vendors);
    expect(global.fetch).toHaveBeenCalledWith(
      '/api/llm/config/sync',
      expect.objectContaining({
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ vendors }),
      }),
    );
    expect(result).toEqual(mockResult);
    expect(result.vendorCount).toBe(2);
    expect(result.modelCount).toBe(5);
  });

  it('后端返回业务错误时抛出异常', async () => {
    mockFetchBusinessError('API Key 无效');
    await expect(addVendor(makeVendorRequest())).rejects.toThrow('API Key 无效');
  });

  it('网络请求失败时抛出异常', async () => {
    mockFetchNetworkError();
    await expect(getVendors()).rejects.toThrow('网络异常，请稍后重试');
  });
});
