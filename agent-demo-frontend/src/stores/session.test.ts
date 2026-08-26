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

  // ========== BUG 修复：moveThoughtToContent 保留推理过程 ==========

  /**
   * 验证 moveThoughtToContent 后 step.thought 保留（Bug2）
   * <p>
   * 场景：收到 final-answer 事件时，最终答案移入 content，
   * 但不应清空 reactSteps 中的 thought，否则回答结束后推理过程信息消失。
   * </p>
   */
  it('moveThoughtToContent 保留 thought 不清空（Bug2）', () => {
    const store = useSessionStore();
    store.init();
    const sessionId = store.sessions[0].sessionId;
    store.addMessage(sessionId, {
      id: 'msg-react-1',
      role: 'assistant',
      content: '',
      createdAt: Date.now(),
      status: 'incomplete',
    });
    // 模拟流式推理过程
    store.appendThought('msg-react-1', '第一步推理', 1);
    store.appendThought('msg-react-1', '第二步推理', 2);
    // 最终答案对应 iteration 2
    store.moveThoughtToContent('msg-react-1', 2);

    const found = store.sessions[0].messages.find((m) => m.id === 'msg-react-1');
    // 最终答案追加到 content
    expect(found?.content).toContain('第二步推理');
    // BUG 修复：thought 保留，推理过程信息不消失
    expect(found?.reactSteps?.[1].thought).toBe('第二步推理');
    expect(found?.reactSteps?.[0].thought).toBe('第一步推理');
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

  /**
   * 知识库选择器会话级状态测试（Task-05）
   * 关联 AC：AC-015, AC-037
   */
  describe('知识库选择器会话级状态', () => {
    it('getKnowledgeBases 无记录时返回空数组（自动模式）', () => {
      const store = useSessionStore();
      expect(store.getKnowledgeBases('session-X')).toEqual([]);
    });

    it('setKnowledgeBases 后 getKnowledgeBases 返回设置的值', () => {
      const store = useSessionStore();
      store.setKnowledgeBases('session-A', ['产品手册']);
      expect(store.getKnowledgeBases('session-A')).toEqual(['产品手册']);
    });

    it('不同会话的知识库选择互不影响（AC-015 会话隔离）', () => {
      const store = useSessionStore();
      store.setKnowledgeBases('session-A', ['产品手册']);
      expect(store.getKnowledgeBases('session-B')).toEqual([]);
    });

    it('setKnowledgeBases 传空数组可重置为自动模式', () => {
      const store = useSessionStore();
      store.setKnowledgeBases('session-A', ['产品手册']);
      store.setKnowledgeBases('session-A', []);
      expect(store.getKnowledgeBases('session-A')).toEqual([]);
    });
  });

  /**
   * 模型选择器会话级状态测试（Task-17）
   */
  describe('模型选择器会话级状态', () => {
    it('getModel 无记录时返回空字符串', () => {
      const store = useSessionStore();
      expect(store.getModel('session-X')).toBe('');
    });

    it('setModel 后 getModel 返回已设置的 modelId', () => {
      const store = useSessionStore();
      store.setModel('session-A', 'doubao-seed-2.0-pro');
      expect(store.getModel('session-A')).toBe('doubao-seed-2.0-pro');
    });

    it('不同会话的 modelId 互不影响（会话隔离）', () => {
      const store = useSessionStore();
      store.setModel('session-A', 'model-a');
      store.setModel('session-B', 'model-b');
      expect(store.getModel('session-A')).toBe('model-a');
      expect(store.getModel('session-B')).toBe('model-b');
      expect(store.getModel('session-C')).toBe('');
    });

    it('deleteSession 时清理 modelBySession 记录', () => {
      const store = useSessionStore();
      store.init();
      const sessionId = store.sessions[0].sessionId;
      store.setModel(sessionId, 'doubao-seed-2.0-pro');
      expect(store.getModel(sessionId)).toBe('doubao-seed-2.0-pro');

      store.deleteSession(sessionId);
      // 删除后 sessionId 已变更（切换到新会话或新建），验证旧记录已清理
      expect(store.modelBySession[sessionId]).toBeUndefined();
    });

    it('modelBySession 不被持久化到 localStorage', () => {
      const store = useSessionStore();
      store.init();
      const sessionId = store.sessions[0].sessionId;
      store.setModel(sessionId, 'doubao-seed-2.0-pro');

      // localStorage 中不应包含 modelBySession 数据
      const raw = localStorage.getItem('agent-demo:sessions');
      expect(raw).not.toContain('modelBySession');
      expect(raw).not.toContain('doubao-seed-2.0-pro');
    });
  });

  // ========== HITL 人机交互状态管理（Task-07/08，BUG 修复）==========

  /** 辅助：创建带助手消息的会话并返回 sessionId */
  function setupAssistantMessage(store: ReturnType<typeof useSessionStore>, msgId: string): string {
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

  describe('HITL 人机交互状态管理', () => {
    it('setAskUserData 将交互数据写入指定消息（ask_user 事件触发）', () => {
      const store = useSessionStore();
      store.init();
      setupAssistantMessage(store, 'msg-hitl-1');
      store.setAskUserData('msg-hitl-1', {
        type: 'confirm',
        question: '确认删除文件 test.txt？',
        options: ['确认删除', '取消'],
        retryCount: 0,
      });
      const found = store.sessions[0].messages.find((m) => m.id === 'msg-hitl-1');
      expect(found?.askUserData?.type).toBe('confirm');
      expect(found?.askUserData?.question).toBe('确认删除文件 test.txt？');
      expect(found?.askUserData?.options).toEqual(['确认删除', '取消']);
    });

    /**
     * BUG 修复核心测试：ask_user 事件后后端立即发送 done，消息 status 为 complete，
     * 但 HITL 场景下消息仍处于"等待用户输入"状态。
     * 修复前 isWaitingForUserInput 要求 status === 'incomplete'，此处返回 false 导致输入框不进入等待态。
     */
    it('isWaitingForUserInput 在 askUserData 存在且 status=complete 时返回 true（HITL BUG 修复）', () => {
      const store = useSessionStore();
      store.init();
      setupAssistantMessage(store, 'msg-hitl-2');
      store.setAskUserData('msg-hitl-2', {
        type: 'confirm',
        question: '确认删除文件？',
        options: ['是', '否'],
        retryCount: 0,
      });
      // 模拟 ask_user 事件后 done 事件触发 markComplete（后端行为）
      store.markComplete('msg-hitl-2');
      expect(store.sessions[0].messages[0].status).toBe('complete');
      // 即使 status=complete，仍应视为等待用户输入
      expect(store.isWaitingForUserInput).toBe(true);
    });

    it('isWaitingForUserInput 无 askUserData 时返回 false', () => {
      const store = useSessionStore();
      store.init();
      setupAssistantMessage(store, 'msg-hitl-3');
      expect(store.isWaitingForUserInput).toBe(false);
    });

    it('clearAskUser 清除 askUserData 并标记消息 complete（用户回复后）', () => {
      const store = useSessionStore();
      store.init();
      setupAssistantMessage(store, 'msg-hitl-4');
      store.setAskUserData('msg-hitl-4', {
        type: 'text',
        question: '请提供订单号',
        options: [],
        retryCount: 0,
      });
      expect(store.isWaitingForUserInput).toBe(true);
      store.clearAskUser(store.sessions[0].sessionId);
      const found = store.sessions[0].messages.find((m) => m.id === 'msg-hitl-4');
      expect(found?.askUserData).toBeUndefined();
      expect(found?.status).toBe('complete');
      expect(store.isWaitingForUserInput).toBe(false);
    });

    // ===== unified-chat-mode Task-15 新增：决策 7 锁定保留 + 持久化 =====

    it('setAskUserData 持久化 askUserData 到 localStorage（决策 7：刷新后卡片可回看）', () => {
      const store = useSessionStore();
      store.init();
      setupAssistantMessage(store, 'msg-hitl-5');
      store.setAskUserData('msg-hitl-5', {
        type: 'confirm',
        question: '确认？',
        options: ['是', '否'],
        retryCount: 0,
      });
      const raw = localStorage.getItem('agent-demo:sessions');
      expect(raw).not.toBeNull();
      expect(raw).toContain('askUserData');
    });

    it('setAskUserAnswer 写入 answer 字段并持久化，isWaitingForUserInput 返回 false', () => {
      const store = useSessionStore();
      store.init();
      setupAssistantMessage(store, 'msg-hitl-6');
      store.setAskUserData('msg-hitl-6', {
        type: 'confirm',
        question: '确认删除？',
        options: ['确认', '取消'],
        retryCount: 0,
      });
      expect(store.isWaitingForUserInput).toBe(true);

      store.setAskUserAnswer(store.sessions[0].sessionId, '确认');

      const found = store.sessions[0].messages.find((m) => m.id === 'msg-hitl-6');
      expect(found?.askUserData?.answer).toBe('确认');
      expect(found?.status).toBe('complete');
      expect(store.isWaitingForUserInput).toBe(false);
      // 持久化（决策 7）
      const raw = localStorage.getItem('agent-demo:sessions');
      expect(raw).toContain('"answer":"确认"');
    });

    it('setAskUserAnswer 保留 askUserData（区别于 clearAskUser，卡片可回看）', () => {
      const store = useSessionStore();
      store.init();
      setupAssistantMessage(store, 'msg-hitl-7');
      store.setAskUserData('msg-hitl-7', {
        type: 'text',
        question: '请提供订单号',
        options: [],
        retryCount: 0,
      });
      store.setAskUserAnswer(store.sessions[0].sessionId, 'ORD-12345');
      const found = store.sessions[0].messages.find((m) => m.id === 'msg-hitl-7');
      expect(found?.askUserData).toBeDefined();
      expect(found?.askUserData?.answer).toBe('ORD-12345');
    });

    it('isWaitingForUserInput 在 askUserData 有 answer 时返回 false（已回答不等待）', () => {
      const store = useSessionStore();
      store.init();
      setupAssistantMessage(store, 'msg-hitl-8');
      store.setAskUserData('msg-hitl-8', {
        type: 'confirm',
        question: '确认？',
        options: ['是', '否'],
        retryCount: 0,
      });
      store.setAskUserAnswer(store.sessions[0].sessionId, '是');
      expect(store.isWaitingForUserInput).toBe(false);
    });

    it('旧 localStorage 数据兼容：无 answer 字段的 askUserData 视为未回答', () => {
      // 模拟旧数据（无 answer 字段）
      const oldData = { type: 'confirm', question: '旧问题', options: ['A'], retryCount: 0 };
      const store = useSessionStore();
      store.init();
      setupAssistantMessage(store, 'msg-old');
      store.setAskUserData('msg-old', oldData);
      const found = store.sessions[0].messages.find((m) => m.id === 'msg-old');
      expect(found?.askUserData?.answer).toBeUndefined();
      // 无 answer -> 视为未回答（等待输入）
      expect(store.isWaitingForUserInput).toBe(true);
    });
  });
});
