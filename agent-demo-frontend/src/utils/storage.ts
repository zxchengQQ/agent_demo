import type { SessionRecord, Message } from '@/types';

/**
 * localStorage 会话纪录封装
 * 关联 AC：AC-016（缓存溢出淘汰）、AC-019（持久化）
 */

/** localStorage 存储 key */
const STORAGE_KEY = 'agent-demo:sessions';

/** 最大保留会话数（超出按 updatedAt 淘汰最旧，AC-016） */
const MAX_SESSIONS = 50;

/**
 * 序列化前剥离非持久化字段（内部辅助方法）
 * 业务含义：ReAct 推理过程（reactSteps）仅用于当前会话实时展示，不持久化。
 * unified-chat-mode（决策 7）：askUserData 改为持久化（含 answer），刷新后卡片可回看问题与选择。
 * 使用解构复制避免修改内存中的原始对象。
 */
function stripNonPersistentFields(messages: Message[]): Omit<Message, 'reactSteps'>[] {
  return messages.map(({ reactSteps: _reactSteps, ...rest }) => rest);
}

/**
 * 读取全部会话（按 updatedAt 倒序）
 * 业务含义：最近活跃的会话排在最前（AC-018）
 */
export function loadSessions(): SessionRecord[] {
  const raw = localStorage.getItem(STORAGE_KEY);
  if (!raw) return [];
  try {
    const sessions: SessionRecord[] = JSON.parse(raw);
    return sessions.sort((a, b) => b.updatedAt - a.updatedAt);
  } catch {
    // JSON 解析失败（数据损坏），返回空数组避免阻塞
    return [];
  }
}

/**
 * 保存会话列表（自动淘汰超限）
 * 业务含义：按 updatedAt 倒序后截断保留前 50 个（AC-016）
 */
export function saveSessions(sessions: SessionRecord[]): void {
  // 序列化时剥离 reactSteps（非持久化字段）；askUserData 持久化（决策 7：刷新后卡片可回看）
  const stripped = sessions.map((s) => ({
    ...s,
    messages: stripNonPersistentFields(s.messages),
  }));
  const sorted = [...stripped].sort((a, b) => b.updatedAt - a.updatedAt);
  const trimmed = sorted.slice(0, MAX_SESSIONS);
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(trimmed));
  } catch (e) {
    // 容量超限等异常，降级处理不抛出
    console.error('localStorage 写入失败:', e);
  }
}

/**
 * 新增或更新单个会话
 * 业务含义：已存在 sessionId 则更新，否则新增到头部
 */
export function upsertSession(session: SessionRecord): SessionRecord[] {
  const sessions = loadSessions();
  const idx = sessions.findIndex((s) => s.sessionId === session.sessionId);
  if (idx >= 0) {
    sessions[idx] = session;
  } else {
    sessions.unshift(session);
  }
  saveSessions(sessions);
  return loadSessions();
}

/**
 * 删除指定会话
 */
export function deleteSession(sessionId: string): SessionRecord[] {
  const sessions = loadSessions().filter((s) => s.sessionId !== sessionId);
  saveSessions(sessions);
  return sessions;
}

/**
 * 清空所有会话
 */
export function clearAllSessions(): void {
  localStorage.removeItem(STORAGE_KEY);
}
