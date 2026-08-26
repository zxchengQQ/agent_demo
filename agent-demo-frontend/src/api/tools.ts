import type { ToolsResponse } from '@/types';
import type { ToolPermissionLevel } from '@/types';

/**
 * 工具 API 封装
 * 业务含义：调用后端 GET /api/agent/tools 获取可用工具列表，
 * 供前端工具选择器和设置页面使用；PUT /tools/{id}/permission 调整工具权限等级。
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

/**
 * 更新工具权限等级（AC-N01）
 * 业务含义：管理页调整指定工具的权限（allow/ask/deny），调用 PUT 接口持久化。
 * 成功后重启服务仍生效；askUser 工具由后端豁免（固定 allow），前端禁用编辑。
 *
 * @param toolId 工具标识（category:name）
 * @param permission 目标权限等级（allow/ask/deny）
 */
export async function updateToolPermission(
  toolId: string,
  permission: ToolPermissionLevel,
): Promise<void> {
  const response = await fetch(`${API_BASE}/tools/${encodeURIComponent(toolId)}/permission`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ permission }),
  });
  const result = await response.json().catch(() => null);
  if (!response.ok || !result?.success) {
    throw new Error(result?.message || '更新工具权限失败');
  }
}