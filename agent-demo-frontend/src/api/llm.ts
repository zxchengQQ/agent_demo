import type {
  LlmVendor,
  LlmModel,
  PredefinedVendor,
  TestConnectionResult,
  ConfigStatus,
  VendorRequest,
} from '@/types';

/**
 * LLM 厂商模型配置 API 封装
 *
 * 业务含义：封装后端 /api/llm/config/* 接口，统一处理 Result<T> 返回结构。
 * 成功时返回 data 字段，失败时抛出 Error（含后端错误消息或网络异常提示）。
 */

const API_BASE = '/api/llm/config';

/** 后端 Result<T> 返回结构 */
interface Result<T> {
  success: boolean;
  code: number;
  message: string;
  data: T;
  traceId: string;
}

/**
 * 统一请求封装：解析 Result<T> 结构，失败抛异常
 * 业务含义：所有 LLM 配置 API 共用的请求方法，统一处理 HTTP 错误和业务错误。
 */
async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const response = await fetch(url, options);

  // HTTP 状态码非 2xx（网络层错误）
  if (!response.ok) {
    const errorResult = await response.json().catch(() => null);
    throw new Error(errorResult?.message || '网络异常，请稍后重试');
  }

  // 解析 Result<T> 结构
  const result: Result<T> = await response.json();

  // 业务错误（success=false）
  if (!result.success) {
    throw new Error(result.message || '操作失败');
  }

  return result.data;
}

/** 获取预定义厂商目录 */
export async function getPredefinedVendors(): Promise<PredefinedVendor[]> {
  return request<PredefinedVendor[]>(`${API_BASE}/predefined`);
}

/** 获取已配置厂商列表（API Key 脱敏） */
export async function getVendors(): Promise<LlmVendor[]> {
  return request<LlmVendor[]>(`${API_BASE}/vendors`);
}

/** 添加厂商 */
export async function addVendor(data: VendorRequest): Promise<LlmVendor> {
  return request<LlmVendor>(`${API_BASE}/vendors`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
}

/** 编辑厂商 */
export async function updateVendor(id: string, data: VendorRequest): Promise<LlmVendor> {
  return request<LlmVendor>(`${API_BASE}/vendors/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
}

/** 删除厂商 */
export async function deleteVendor(id: string): Promise<void> {
  await request<void>(`${API_BASE}/vendors/${id}`, { method: 'DELETE' });
}

/** 测试 API Key 连接 */
export async function testConnection(baseUrl: string, apiKey: string): Promise<TestConnectionResult> {
  return request<TestConnectionResult>(`${API_BASE}/test`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ baseUrl, apiKey }),
  });
}

/** 获取模型列表（可按类型过滤） */
export async function getModels(type?: string): Promise<LlmModel[]> {
  const url = type ? `${API_BASE}/models?type=${encodeURIComponent(type)}` : `${API_BASE}/models`;
  return request<LlmModel[]>(url);
}

/** 获取配置状态 */
export async function getConfigStatus(): Promise<ConfigStatus> {
  return request<ConfigStatus>(`${API_BASE}/status`);
}

/** 同步配置（前端推送配置到后端） */
export async function syncConfig(
  vendors: VendorRequest[],
): Promise<{ vendorCount: number; modelCount: number }> {
  return request<{ vendorCount: number; modelCount: number }>(`${API_BASE}/sync`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ vendors }),
  });
}
