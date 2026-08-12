import type { VendorRequest } from '@/types';

/**
 * LLM 配置 localStorage 封装
 *
 * 业务含义：前端保存完整的厂商配置（含 API Key 明文）到 localStorage，
 * 作为本地备份和同步到后端的数据源。与 session 存储分离，避免混淆。
 */

/** localStorage 存储 key */
const LLM_CONFIG_KEY = 'agent-demo:llm-config';

/** 上次使用的 modelId 存储 key */
const LAST_USED_MODEL_KEY = 'agent-demo:last-used-model';

/**
 * 保存完整配置（含 apiKey 明文）到 localStorage
 * 业务含义：配置页面编辑完成后保存到本地，供后续同步到后端使用。
 */
export function saveLlmConfig(vendors: VendorRequest[]): void {
  try {
    localStorage.setItem(LLM_CONFIG_KEY, JSON.stringify(vendors));
  } catch (e) {
    // 容量超限等异常，降级处理不抛出
    console.error('localStorage 写入失败:', e);
  }
}

/**
 * 从 localStorage 读取完整配置
 * 业务含义：返回 null 表示无本地配置（首次使用或已被清除）。
 */
export function loadLlmConfig(): VendorRequest[] | null {
  const raw = localStorage.getItem(LLM_CONFIG_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as VendorRequest[];
  } catch {
    // JSON 解析失败（数据损坏），返回 null 避免阻塞
    return null;
  }
}

/**
 * 清除 localStorage 中的配置
 */
export function clearLlmConfig(): void {
  localStorage.removeItem(LLM_CONFIG_KEY);
}

/**
 * 保存上次使用的 modelId
 * 业务含义：用户选择模型后持久化，刷新页面后恢复上次选择。
 */
export function saveLastUsedModelId(modelId: string): void {
  try {
    localStorage.setItem(LAST_USED_MODEL_KEY, modelId);
  } catch (e) {
    console.error('localStorage 写入失败:', e);
  }
}

/**
 * 读取上次使用的 modelId
 * 业务含义：返回 null 表示无历史记录。
 */
export function loadLastUsedModelId(): string | null {
  return localStorage.getItem(LAST_USED_MODEL_KEY);
}
