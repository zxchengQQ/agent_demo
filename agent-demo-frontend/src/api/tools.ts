import type { ToolsResponse } from '@/types';

/**
 * 工具 API 封装
 * 业务含义：调用后端 GET /api/agent/tools 获取可用工具列表，
 * 供前端工具选择器和设置页面使用。
 */

const API_BASE = '/api/agent';

/** 获取可用工具列表 */
export async function fetchAvailableTools(): Promise<ToolsResponse> {
  const response = await fetch(`${API_BASE}/tools`);
  if (!response.ok) {
    throw new Error('获取工具列表失败');
  }
  const result = await response.json();
  if (!result.success) {
    throw new Error(result.message || '获取工具列表失败');
  }
  return result.data as ToolsResponse;
}