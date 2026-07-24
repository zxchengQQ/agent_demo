import { describe, it, expect, beforeEach } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { useSessionStore } from './session';
import type { Message, SubTask } from '@/types';

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

  // ========== CR-002 新增：子任务状态管理（AC-003, AC-005, AC-014, AC-016）==========

  /** 辅助：创建带助手消息的会话 */
  function setupSubTaskMessage(store: ReturnType<typeof useSessionStore>, msgId: string) {
    const sessionId = store.sessions[0].sessionId;
    store.addMessage(sessionId, {
      id: msgId,
      role: 'assistant',
      content: '',
      createdAt: Date.now(),
      status: 'incomplete',
    });
    return sessionId;
  }

  describe('子任务状态管理（CR-002）', () => {
    it('initSubTasks 初始化子任务列表，全部 status=pending（AC-016）', () => {
      const store = useSessionStore();
      store.init();
      setupSubTaskMessage(store, 'msg-st-1');
      store.initSubTasks('msg-st-1', [
        { index: 1, title: '分析' },
        { index: 2, title: '调研' },
      ]);
      const found = store.sessions[0].messages.find((m) => m.id === 'msg-st-1');
      expect(found?.subTasks).toHaveLength(2);
      expect(found?.subTasks?.[0].status).toBe('pending');
      expect(found?.subTasks?.[1].status).toBe('pending');
      expect(found?.subTasks?.[0].title).toBe('分析');
    });

    it('updateSubTaskStatus 更新子任务状态为 in-progress（AC-003）', () => {
      const store = useSessionStore();
      store.init();
      setupSubTaskMessage(store, 'msg-st-2');
      store.initSubTasks('msg-st-2', [{ index: 1, title: '任务' }]);
      store.updateSubTaskStatus('msg-st-2', 1, 'in-progress');
      const found = store.sessions[0].messages.find((m) => m.id === 'msg-st-2');
      expect(found?.subTasks?.[0].status).toBe('in-progress');
    });

    it('updateSubTaskStatus 更新子任务状态为 failed 并设置 error（AC-006）', () => {
      const store = useSessionStore();
      store.init();
      setupSubTaskMessage(store, 'msg-st-3');
      store.initSubTasks('msg-st-3', [{ index: 2, title: '失败任务' }]);
      store.updateSubTaskStatus('msg-st-3', 2, 'failed', '超时');
      const found = store.sessions[0].messages.find((m) => m.id === 'msg-st-3');
      expect(found?.subTasks?.[0].status).toBe('failed');
      expect(found?.subTasks?.[0].error).toBe('超时');
    });

    it('updateSubTaskStatus 更新子任务状态为 cancelled（AC-007）', () => {
      const store = useSessionStore();
      store.init();
      setupSubTaskMessage(store, 'msg-st-4');
      store.initSubTasks('msg-st-4', [{ index: 3, title: '取消任务' }]);
      store.updateSubTaskStatus('msg-st-4', 3, 'cancelled');
      const found = store.sessions[0].messages.find((m) => m.id === 'msg-st-4');
      expect(found?.subTasks?.[0].status).toBe('cancelled');
    });

    it('appendSubTaskContent 多次追加拼接内容（AC-005）', () => {
      const store = useSessionStore();
      store.init();
      setupSubTaskMessage(store, 'msg-st-5');
      store.initSubTasks('msg-st-5', [{ index: 1, title: '任务' }]);
      store.appendSubTaskContent('msg-st-5', 1, '首先');
      store.appendSubTaskContent('msg-st-5', 1, '分析');
      const found = store.sessions[0].messages.find((m) => m.id === 'msg-st-5');
      expect(found?.subTasks?.[0].content).toBe('首先分析');
    });

    it('appendSubTaskReasoning 追加推理内容（AC-011）', () => {
      const store = useSessionStore();
      store.init();
      setupSubTaskMessage(store, 'msg-st-6');
      store.initSubTasks('msg-st-6', [{ index: 1, title: '任务' }]);
      store.appendSubTaskReasoning('msg-st-6', 1, '思考');
      const found = store.sessions[0].messages.find((m) => m.id === 'msg-st-6');
      expect(found?.subTasks?.[0].reasoning).toBe('思考');
    });

    it('appendSubTaskThought 追加 ReAct 思考到 reactSteps（AC-005）', () => {
      const store = useSessionStore();
      store.init();
      setupSubTaskMessage(store, 'msg-st-7');
      store.initSubTasks('msg-st-7', [{ index: 1, title: '任务' }]);
      store.appendSubTaskThought('msg-st-7', 1, '需要查询', 1);
      const found = store.sessions[0].messages.find((m) => m.id === 'msg-st-7');
      expect(found?.subTasks?.[0].reactSteps).toHaveLength(1);
      expect(found?.subTasks?.[0].reactSteps?.[0].thought).toBe('需要查询');
      expect(found?.subTasks?.[0].reactSteps?.[0].iteration).toBe(1);
    });

    it('appendSubTaskAction 追加工具调用到 reactSteps（AC-005）', () => {
      const store = useSessionStore();
      store.init();
      setupSubTaskMessage(store, 'msg-st-8');
      store.initSubTasks('msg-st-8', [{ index: 1, title: '任务' }]);
      store.appendSubTaskThought('msg-st-8', 1, '思考', 1);
      store.appendSubTaskAction('msg-st-8', 1, 'http', '{"url":"..."}', 1);
      const found = store.sessions[0].messages.find((m) => m.id === 'msg-st-8');
      expect(found?.subTasks?.[0].reactSteps?.[0].toolCalls).toHaveLength(1);
      expect(found?.subTasks?.[0].reactSteps?.[0].toolCalls[0].toolName).toBe('http');
      expect(found?.subTasks?.[0].reactSteps?.[0].toolCalls[0].arguments).toBe('{"url":"..."}');
    });

    it('appendSubTaskObservation 追加工具结果到对应 toolCall（AC-005）', () => {
      const store = useSessionStore();
      store.init();
      setupSubTaskMessage(store, 'msg-st-9');
      store.initSubTasks('msg-st-9', [{ index: 1, title: '任务' }]);
      store.appendSubTaskThought('msg-st-9', 1, '思考', 1);
      store.appendSubTaskAction('msg-st-9', 1, 'http', '{"url":"..."}', 1);
      store.appendSubTaskObservation('msg-st-9', 1, '查询结果', 1);
      const found = store.sessions[0].messages.find((m) => m.id === 'msg-st-9');
      expect(found?.subTasks?.[0].reactSteps?.[0].toolCalls[0].result).toBe('查询结果');
    });

    it('子任务变更同步写入 localStorage（AC-014 持久化）', () => {
      const store = useSessionStore();
      store.init();
      setupSubTaskMessage(store, 'msg-st-10');
      store.initSubTasks('msg-st-10', [{ index: 1, title: '持久化任务' }]);
      store.updateSubTaskStatus('msg-st-10', 1, 'completed');
      store.appendSubTaskContent('msg-st-10', 1, '结果');

      const raw = localStorage.getItem('agent-demo:sessions');
      expect(raw).not.toBeNull();
      const sessions = JSON.parse(raw!);
      const found = sessions[0].messages.find((m: Message) => m.id === 'msg-st-10');
      expect(found.subTasks).toHaveLength(1);
      expect(found.subTasks[0].status).toBe('completed');
      expect(found.subTasks[0].content).toBe('结果');
    });

    it('消息不存在时 initSubTasks 不抛异常（静默跳过）', () => {
      const store = useSessionStore();
      store.init();
      expect(() => {
        store.initSubTasks('non-existent', [{ index: 1, title: '任务' }]);
      }).not.toThrow();
    });

    it('subTasks 为空时 updateSubTaskStatus 不抛异常（静默跳过）', () => {
      const store = useSessionStore();
      store.init();
      setupSubTaskMessage(store, 'msg-st-11');
      expect(() => {
        store.updateSubTaskStatus('msg-st-11', 1, 'in-progress');
      }).not.toThrow();
    });

    it('subTasks 为空时 appendSubTaskContent 不抛异常（静默跳过）', () => {
      const store = useSessionStore();
      store.init();
      setupSubTaskMessage(store, 'msg-st-12');
      expect(() => {
        store.appendSubTaskContent('msg-st-12', 1, '内容');
      }).not.toThrow();
    });

    it('多迭代 ReAct 步骤正确分组（iteration 隔离）', () => {
      const store = useSessionStore();
      store.init();
      setupSubTaskMessage(store, 'msg-st-13');
      store.initSubTasks('msg-st-13', [{ index: 1, title: '多轮任务' }]);
      // iteration 1
      store.appendSubTaskThought('msg-st-13', 1, '第一次思考', 1);
      store.appendSubTaskAction('msg-st-13', 1, 'http', '{}', 1);
      store.appendSubTaskObservation('msg-st-13', 1, '结果1', 1);
      // iteration 2
      store.appendSubTaskThought('msg-st-13', 1, '第二次思考', 2);
      store.appendSubTaskAction('msg-st-13', 1, 'calc', '{}', 2);
      store.appendSubTaskObservation('msg-st-13', 1, '结果2', 2);

      const found = store.sessions[0].messages.find((m) => m.id === 'msg-st-13');
      expect(found?.subTasks?.[0].reactSteps).toHaveLength(2);
      expect(found?.subTasks?.[0].reactSteps?.[0].thought).toBe('第一次思考');
      expect(found?.subTasks?.[0].reactSteps?.[1].thought).toBe('第二次思考');
      expect(found?.subTasks?.[0].reactSteps?.[1].toolCalls[0].toolName).toBe('calc');
    });
  });
});
