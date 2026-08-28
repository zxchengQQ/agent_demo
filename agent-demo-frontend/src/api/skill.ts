import type { SkillInfo } from '@/types';

/**
 * 技能管理 API 封装
 *
 * 业务含义：封装后端 /api/skill/* 接口，统一处理 Result<T> 返回结构。
 * 成功时返回 data 字段，失败时抛出 Error（含后端错误消息或网络异常提示）。
 * 复用 rag.ts 的 request<T>() 统一请求模式。
 */

const API_BASE = '/api/skill';

/** 后端 Result<T> 返回结构 */
interface Result<T> {
  success: boolean;
  code: number;
  message: string;
  data: T;
  traceId: string;
}

/** 统一请求封装：解析 Result<T> 结构，失败抛异常 */
async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const response = await fetch(url, options);

  if (!response.ok) {
    const errorResult = await response.json().catch(() => null);
    throw new Error(errorResult?.message || '网络异常，请稍后重试');
  }

  const result: Result<T> = await response.json();

  if (!result.success) {
    throw new Error(result.message || '操作失败');
  }

  return result.data;
}

/** 技能脚本声明（CR-001） */
export interface SkillScriptPayload {
  name: string;
  language: string;
  description: string;
  params?: { name: string; type: string; required: boolean; description: string }[];
  content: string;
}

/** 技能请求体（创建/编辑共用；创建时 id 必填，编辑时经路径传 id） */
export interface SkillRequestPayload {
  id?: string;
  name: string;
  description: string;
  instruction: string;
  resources?: { name: string; content: string }[];
  scripts?: SkillScriptPayload[];
}

/** 查询技能列表 */
export async function listSkills(): Promise<SkillInfo[]> {
  return request<SkillInfo[]>(`${API_BASE}/list`);
}

/** 创建技能（恶意指令内容被后端拦截，返回错误含命中类别） */
export async function createSkill(payload: SkillRequestPayload): Promise<SkillInfo> {
  return request<SkillInfo>(`${API_BASE}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

/** 更新技能 */
export async function updateSkill(skillId: string, payload: SkillRequestPayload): Promise<SkillInfo> {
  return request<SkillInfo>(`${API_BASE}/${skillId}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

/** 启用/禁用技能 */
export async function setSkillEnabled(skillId: string, enabled: boolean): Promise<void> {
  await request<void>(`${API_BASE}/${skillId}/enabled`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ enabled }),
  });
}

/** 删除技能 */
export async function deleteSkill(skillId: string): Promise<void> {
  await request<void>(`${API_BASE}/${skillId}`, {
    method: 'DELETE',
  });
}
