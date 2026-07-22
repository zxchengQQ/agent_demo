import { describe, it, expect, beforeEach } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { useSessionStore } from './session';
import type { Message } from '@/types';

/**
 * Pinia 会话状态管理测试
 * 验证标准来源：T-09 验证标准
 * 关联 AC：AC-001、AC-005、AC-006、AC-007、AC-008、AC-009、AC-010、AC-018、AC-019
 */
describe('Session Store', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    localStorage.clear();
  });

  it('init 无会话时自动新建空会话（AC-001）', () => {
    const store = useSessionStore();
    store.init();
    expect(store.sessions).toHaveLength(1);
    expect(store.sessions[0].messages).toEqual([]);
  });

  it('init 有会话时加载现有会话', () => {
    localStorage.setItem(
      'agent-demo:sessions',
      JSON.stringify([
        { sessionId: 's1', title: '会话1', createdAt: 100, updatedAt: 200, messages: [] },
      ]),
    );
    const store = useSessionStore();
    store.init();
    expect(store.sessions).toHaveLength(1);
    expect(store.sessions[0].sessionId).toBe('s1');
  });

  it('createNewSession 创建空会话插入头部', () => {
    const store = useSessionStore();
    store.init();
    const firstId = store.sessions[0].sessionId;
    store.createNewSession();
    expect(store.sessions).toHaveLength(2);
    expect(store.sessions[0].sessionId).not.toBe(firstId);
  });

  it('switchTo 设置 currentSessionId（AC-005）', () => {
    const store = useSessionStore();
    store.init();
    const sessionId = store.sessions[0].sessionId;
    store.switchTo(sessionId);
    expect(store.currentSessionId).toBe(sessionId);
  });

  it('updateSessionId 透明续聊更新关联（AC-010）', () => {
    const store = useSessionStore();
    store.init();
    const oldId = store.sessions[0].sessionId;
    store.updateSessionId(oldId, 'new-backend-session-id');
    expect(store.sessions[0].sessionId).toBe('new-backend-session-id');
  });

  it('generateTitle 超 20 字符加省略号（AC-006）', () => {
    const store = useSessionStore();
    store.init();
    const sessionId = store.sessions[0].sessionId;
    const longMessage = '这是一条超过二十个字符的测试消息用于验证标题截取功能';
    store.generateTitle(sessionId, longMessage);
    expect(store.sessions[0].title).toBe(longMessage.slice(0, 20) + '...');
  });

  it('generateTitle 不足 20 字符不加省略号', () => {
    const store = useSessionStore();
    store.init();
    const sessionId = store.sessions[0].sessionId;
    store.generateTitle(sessionId, '短消息');
    expect(store.sessions[0].title).toBe('短消息');
  });

  it('renameSession 限 50 字符（AC-007）', () => {
    const store = useSessionStore();
    store.init();
    const sessionId = store.sessions[0].sessionId;
    const longTitle = 'a'.repeat(60);
    store.renameSession(sessionId, longTitle);
    expect(store.sessions[0].title).toHaveLength(50);
  });

  it('deleteSession 删除会话（AC-008）', () => {
    const store = useSessionStore();
    store.init();
    store.createNewSession();
    const targetId = store.sessions[0].sessionId;
    store.deleteSession(targetId);
    expect(store.sessions.find((s) => s.sessionId === targetId)).toBeUndefined();
  });

  it('clearAll 清空所有会话（AC-009）', () => {
    const store = useSessionStore();
    store.init();
    store.clearAll();
    expect(store.sessions).toHaveLength(0);
  });

  it('持久化：变更后写入 localStorage（AC-019）', () => {
    const store = useSessionStore();
    store.init();
    const raw = localStorage.getItem('agent-demo:sessions');
    expect(raw).not.toBeNull();
    const sessions = JSON.parse(raw!);
    expect(sessions.length).toBeGreaterThanOrEqual(1);
  });

  // ========== CR-001 新增：appendReasoning 方法（AC-024）==========

  it('appendReasoning 将片段追加到指定消息的 reasoning 字段（AC-024, CR-001）', () => {
    const store = useSessionStore();
    store.init();
    const sessionId = store.sessions[0].sessionId;
    const msg: Message = {
      id: 'msg-r-1',
      role: 'assistant',
      content: '',
      createdAt: Date.now(),
      status: 'incomplete',
    };
    store.addMessage(sessionId, msg);
    store.appendReasoning('msg-r-1', '推理片段');
    const found = store.sessions[0].messages.find((m) => m.id === 'msg-r-1');
    expect(found?.reasoning).toBe('推理片段');
  });

  it('appendReasoning 多次调用能正确拼接（AC-024）', () => {
    const store = useSessionStore();
    store.init();
    const sessionId = store.sessions[0].sessionId;
    store.addMessage(sessionId, {
      id: 'msg-r-2',
      role: 'assistant',
      content: '',
      createdAt: Date.now(),
      status: 'incomplete',
    });
    store.appendReasoning('msg-r-2', '用户');
    store.appendReasoning('msg-r-2', '问的是');
    const found = store.sessions[0].messages.find((m) => m.id === 'msg-r-2');
    expect(found?.reasoning).toBe('用户问的是');
  });

  it('appendReasoning 变更同步写入 localStorage（AC-024 持久化）', () => {
    const store = useSessionStore();
    store.init();
    const sessionId = store.sessions[0].sessionId;
    store.addMessage(sessionId, {
      id: 'msg-r-3',
      role: 'assistant',
      content: '',
      createdAt: Date.now(),
      status: 'incomplete',
    });
    store.appendReasoning('msg-r-3', '持久化测试');
    const raw = localStorage.getItem('agent-demo:sessions');
    expect(raw).not.toBeNull();
    const sessions = JSON.parse(raw!);
    const found = sessions[0].messages.find((m: Message) => m.id === 'msg-r-3');
    expect(found.reasoning).toBe('持久化测试');
  });

  it('appendReasoning 不影响现有 appendContent 方法', () => {
    const store = useSessionStore();
    store.init();
    const sessionId = store.sessions[0].sessionId;
    store.addMessage(sessionId, {
      id: 'msg-r-4',
      role: 'assistant',
      content: '',
      createdAt: Date.now(),
      status: 'incomplete',
    });
    store.appendContent('msg-r-4', '正式回复');
    store.appendReasoning('msg-r-4', '推理内容');
    const found = store.sessions[0].messages.find((m) => m.id === 'msg-r-4');
    expect(found?.content).toBe('正式回复');
    expect(found?.reasoning).toBe('推理内容');
  });
});
