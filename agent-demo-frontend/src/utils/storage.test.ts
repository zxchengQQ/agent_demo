import { describe, it, expect, beforeEach } from 'vitest';
import {
  loadSessions,
  saveSessions,
  upsertSession,
  deleteSession,
  clearAllSessions,
} from './storage';
import type { SessionRecord } from '@/types';

/**
 * localStorage 封装测试
 * 验证标准来源：T-07 验证标准
 * 关联 AC：AC-016（缓存溢出淘汰）、AC-019（持久化）
 */
describe('localStorage 封装', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  describe('loadSessions', () => {
    it('无数据时返回空数组', () => {
      expect(loadSessions()).toEqual([]);
    });

    it('返回按 updatedAt 倒序的数组（AC-018）', () => {
      const sessions: SessionRecord[] = [
        { sessionId: 's1', title: '会话1', createdAt: 100, updatedAt: 200, messages: [] },
        { sessionId: 's2', title: '会话2', createdAt: 100, updatedAt: 300, messages: [] },
        { sessionId: 's3', title: '会话3', createdAt: 100, updatedAt: 100, messages: [] },
      ];
      localStorage.setItem('agent-demo:sessions', JSON.stringify(sessions));

      const result = loadSessions();
      expect(result[0].sessionId).toBe('s2'); // updatedAt 300 最大
      expect(result[1].sessionId).toBe('s1'); // updatedAt 200
      expect(result[2].sessionId).toBe('s3'); // updatedAt 100 最小
    });
  });

  describe('saveSessions', () => {
    it('超 50 个时截断保留最新 50（AC-016）', () => {
      const sessions: SessionRecord[] = Array.from({ length: 51 }, (_, i) => ({
        sessionId: `s${i}`,
        title: `会话${i}`,
        createdAt: i,
        updatedAt: i, // i 越大越新
        messages: [],
      }));
      saveSessions(sessions);

      const result = loadSessions();
      expect(result).toHaveLength(50);
      // 最旧的 s0 被淘汰
      expect(result.find((s) => s.sessionId === 's0')).toBeUndefined();
      // 最新的 s50 保留
      expect(result.find((s) => s.sessionId === 's50')).toBeDefined();
    });
  });

  describe('upsertSession', () => {
    it('新会话插入到列表头部', () => {
      const existing: SessionRecord = {
        sessionId: 's1',
        title: '旧会话',
        createdAt: 100,
        updatedAt: 200,
        messages: [],
      };
      saveSessions([existing]);

      const newSession: SessionRecord = {
        sessionId: 's2',
        title: '新会话',
        createdAt: 300,
        updatedAt: 300,
        messages: [],
      };
      upsertSession(newSession);

      const result = loadSessions();
      expect(result[0].sessionId).toBe('s2'); // 新会话在头部
    });

    it('已存在 sessionId 则更新', () => {
      const session: SessionRecord = {
        sessionId: 's1',
        title: '原标题',
        createdAt: 100,
        updatedAt: 200,
        messages: [],
      };
      saveSessions([session]);

      const updated: SessionRecord = {
        sessionId: 's1',
        title: '新标题',
        createdAt: 100,
        updatedAt: 300,
        messages: [],
      };
      upsertSession(updated);

      const result = loadSessions();
      expect(result).toHaveLength(1);
      expect(result[0].title).toBe('新标题');
    });
  });

  describe('deleteSession', () => {
    it('删除指定 sessionId', () => {
      const sessions: SessionRecord[] = [
        { sessionId: 's1', title: '会话1', createdAt: 100, updatedAt: 200, messages: [] },
        { sessionId: 's2', title: '会话2', createdAt: 100, updatedAt: 300, messages: [] },
      ];
      saveSessions(sessions);

      deleteSession('s1');

      const result = loadSessions();
      expect(result).toHaveLength(1);
      expect(result[0].sessionId).toBe('s2');
    });
  });

  describe('clearAllSessions', () => {
    it('清空所有会话', () => {
      const sessions: SessionRecord[] = [
        { sessionId: 's1', title: '会话1', createdAt: 100, updatedAt: 200, messages: [] },
      ];
      saveSessions(sessions);

      clearAllSessions();

      expect(loadSessions()).toEqual([]);
    });
  });
});
