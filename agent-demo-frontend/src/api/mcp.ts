import type {
  McpServerInfo,
  McpToolInfo,
  CreateMcpServerRequest,
} from '@/types';

/**
 * MCP Server 管理 API 封装
 *
 * 业务含义：封装后端 /api/mcp/* 接口，统一处理 Result<T> 返回结构。
 * 成功时返回 data 字段，失败时抛出 Error（含后端错误消息或网络异常提示）。
 * 复用 llm.ts 的 request<T>() 统一请求模式。
 */

const API_BASE = '/api/mcp';

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
 * 业务含义：所有 MCP 管理 API 共用的请求方法，统一处理 HTTP 错误和业务错误。
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

/** 获取所有 MCP Server 列表 */
export async function getServers(): Promise<McpServerInfo[]> {
  return request<McpServerInfo[]>(`${API_BASE}/servers`);
}

/** 动态添加 MCP Server 并立即连接 */
export async function addServer(data: CreateMcpServerRequest): Promise<McpServerInfo> {
  return request<McpServerInfo>(`${API_BASE}/servers`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
}

/** 删除 MCP Server（断开连接并注销工具） */
export async function deleteServer(name: string): Promise<void> {
  await request<void>(`${API_BASE}/servers/${encodeURIComponent(name)}`, { method: 'DELETE' });
}

/** 重连断线的 MCP Server */
export async function reconnectServer(name: string): Promise<McpServerInfo> {
  return request<McpServerInfo>(`${API_BASE}/servers/${encodeURIComponent(name)}/reconnect`, {
    method: 'POST',
  });
}

/** 获取指定 Server 的工具列表 */
export async function getServerTools(name: string): Promise<McpToolInfo[]> {
  return request<McpToolInfo[]>(`${API_BASE}/servers/${encodeURIComponent(name)}/tools`);
}
