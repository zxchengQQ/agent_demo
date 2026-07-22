import { describe, it, expect } from 'vitest';
import type {
  Message,
  SessionRecord,
  StreamCallbacks,
  MessageRole,
  MessageStatus,
} from './index';

/**
 * 类型定义测试
 * 验证标准来源：T-06 验证标准
 * 关联 AC：AC-006、AC-010、AC-012、AC-016、AC-019、AC-020
 */
describe('类型定义', () => {
  it('Message 类型包含所有必需字段', () => {
    const msg: Message = {
      id: 'msg-1',
      role: 'user',
      content: '你好',
      createdAt: Date.now(),
      status: 'complete',
    };
    expect(msg.content).toBe('你好');
    expect(msg.role).toBe('user');
    expect(msg.status).toBe('complete');
  });

  it('SessionRecord 类型包含所有必需字段', () => {
    const session: SessionRecord = {
      sessionId: 'session-1',
      title: '测试会话',
      createdAt: Date.now(),
      updatedAt: Date.now(),
      messages: [],
    };
    expect(session.sessionId).toBe('session-1');
    expect(session.messages).toHaveLength(0);
  });

  it('StreamCallbacks 类型包含所有回调', () => {
    const callbacks: StreamCallbacks = {
      onSession: () => {},
      onToken: () => {},
      onDone: () => {},
      onError: () => {},
    };
    expect(typeof callbacks.onSession).toBe('function');
    expect(typeof callbacks.onToken).toBe('function');
    expect(typeof callbacks.onDone).toBe('function');
    expect(typeof callbacks.onError).toBe('function');
  });

  it('MessageStatus 包含 incomplete 用于流式中断标记（AC-012）', () => {
    const status: MessageStatus = 'incomplete';
    expect(status).toBe('incomplete');
  });

  it('MessageRole 支持 user 和 assistant', () => {
    const user: MessageRole = 'user';
    const assistant: MessageRole = 'assistant';
    expect(user).toBe('user');
    expect(assistant).toBe('assistant');
  });

  // ========== CR-001 新增：推理内容字段 ==========

  it('Message 类型包含 reasoning 字段用于推理内容持久化（AC-022, AC-024）', () => {
    const msg: Message = {
      id: 'msg-2',
      role: 'assistant',
      content: '正式回复',
      reasoning: '推理过程',
      createdAt: Date.now(),
      status: 'complete',
    };
    expect(msg.reasoning).toBe('推理过程');
  });

  it('Message 的 reasoning 字段未设置时为 undefined（向前兼容旧数据）', () => {
    const msg: Message = {
      id: 'msg-3',
      role: 'user',
      content: '你好',
      createdAt: Date.now(),
      status: 'complete',
    };
    expect(msg.reasoning).toBeUndefined();
  });

  it('StreamCallbacks 类型包含 onReasoning 回调（AC-022）', () => {
    const callbacks: StreamCallbacks = {
      onSession: () => {},
      onToken: () => {},
      onReasoning: () => {},
      onDone: () => {},
      onError: () => {},
    };
    expect(typeof callbacks.onReasoning).toBe('function');
  });

  it('StreamCallbacks 的 onReasoning 为可选回调（向前兼容）', () => {
    const callbacks: StreamCallbacks = {
      onSession: () => {},
      onToken: () => {},
      onDone: () => {},
      onError: () => {},
    };
    expect(callbacks.onReasoning).toBeUndefined();
  });
});
