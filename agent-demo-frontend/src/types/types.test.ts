import { describe, it, expect } from 'vitest';
import type {
  Message,
  SessionRecord,
  StreamCallbacks,
  MessageRole,
  MessageStatus,
  SubTaskStatus,
  SubTask,
  SubTaskReactStep,
  KnowledgeBase,
  DocumentInfo,
  DocumentStatus,
  DocumentStatusResponse,
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

  // ========== CR-002 新增：复杂任务拆解类型定义 ==========

  it('SubTaskStatus 包含五种状态值（AC-016）', () => {
    const statuses: SubTaskStatus[] = ['pending', 'in-progress', 'completed', 'failed', 'cancelled'];
    expect(statuses).toHaveLength(5);
    expect(statuses).toContain('pending');
    expect(statuses).toContain('in-progress');
    expect(statuses).toContain('completed');
    expect(statuses).toContain('failed');
    expect(statuses).toContain('cancelled');
  });

  it('SubTaskReactStep 包含 iteration/thought/toolCalls 字段（AC-016）', () => {
    const step: SubTaskReactStep = {
      iteration: 1,
      thought: '需要查询信息',
      toolCalls: [],
    };
    expect(step.iteration).toBe(1);
    expect(step.thought).toBe('需要查询信息');
    expect(step.toolCalls).toHaveLength(0);
  });

  it('SubTask 包含 index/title/status 及可选字段（AC-016）', () => {
    const task: SubTask = {
      index: 1,
      title: '分析需求',
      status: 'pending',
    };
    expect(task.index).toBe(1);
    expect(task.title).toBe('分析需求');
    expect(task.status).toBe('pending');
    expect(task.content).toBeUndefined();
    expect(task.reasoning).toBeUndefined();
    expect(task.reactSteps).toBeUndefined();
    expect(task.error).toBeUndefined();
  });

  it('SubTask 可包含完整执行详情（AC-016）', () => {
    const task: SubTask = {
      index: 2,
      title: '调研方案',
      status: 'completed',
      content: '调研结果',
      reasoning: '推理过程',
      reactSteps: [
        { iteration: 1, thought: '思考', toolCalls: [] },
      ],
    };
    expect(task.content).toBe('调研结果');
    expect(task.reasoning).toBe('推理过程');
    expect(task.reactSteps).toHaveLength(1);
  });

  it('SubTask failed 状态可包含 error 字段（AC-016）', () => {
    const task: SubTask = {
      index: 3,
      title: '执行任务',
      status: 'failed',
      error: '工具调用超时',
    };
    expect(task.status).toBe('failed');
    expect(task.error).toBe('工具调用超时');
  });

  it('Message 接口新增 subTasks 可选字段（AC-016）', () => {
    const msg: Message = {
      id: 'msg-st-1',
      role: 'assistant',
      content: '总结',
      createdAt: Date.now(),
      status: 'complete',
      subTasks: [
        { index: 1, title: '步骤1', status: 'completed' },
        { index: 2, title: '步骤2', status: 'pending' },
      ],
    };
    expect(msg.subTasks).toHaveLength(2);
    expect(msg.subTasks![0].status).toBe('completed');
  });

  it('Message 的 subTasks 未设置时为 undefined（向前兼容）', () => {
    const msg: Message = {
      id: 'msg-st-2',
      role: 'user',
      content: '你好',
      createdAt: Date.now(),
      status: 'complete',
    };
    expect(msg.subTasks).toBeUndefined();
  });

  it('StreamCallbacks 新增 10 个 task 回调（AC-001, AC-003, AC-005, AC-006）', () => {
    const callbacks: StreamCallbacks = {
      onSession: () => {},
      onToken: () => {},
      onDone: () => {},
      onError: () => {},
      onTaskPlan: () => {},
      onTaskStart: () => {},
      onTaskToken: () => {},
      onTaskReasoning: () => {},
      onTaskThought: () => {},
      onTaskAction: () => {},
      onTaskObservation: () => {},
      onTaskComplete: () => {},
      onTaskFailed: () => {},
      onTaskCancelled: () => {},
    };
    expect(typeof callbacks.onTaskPlan).toBe('function');
    expect(typeof callbacks.onTaskStart).toBe('function');
    expect(typeof callbacks.onTaskToken).toBe('function');
    expect(typeof callbacks.onTaskReasoning).toBe('function');
    expect(typeof callbacks.onTaskThought).toBe('function');
    expect(typeof callbacks.onTaskAction).toBe('function');
    expect(typeof callbacks.onTaskObservation).toBe('function');
    expect(typeof callbacks.onTaskComplete).toBe('function');
    expect(typeof callbacks.onTaskFailed).toBe('function');
    expect(typeof callbacks.onTaskCancelled).toBe('function');
  });

  it('StreamCallbacks 的 task 回调均为可选（向前兼容）', () => {
    const callbacks: StreamCallbacks = {
      onSession: () => {},
      onToken: () => {},
      onDone: () => {},
      onError: () => {},
    };
    expect(callbacks.onTaskPlan).toBeUndefined();
    expect(callbacks.onTaskStart).toBeUndefined();
    expect(callbacks.onTaskComplete).toBeUndefined();
  });
});

/**
 * RAG 知识库类型定义测试
 * 验证标准来源：Task-01 验证标准
 * 关联 AC：AC-003, AC-005, AC-009
 */
describe('RAG 类型定义', () => {
  it('KnowledgeBase 类型包含所有必需字段', () => {
    const kb: KnowledgeBase = {
      id: 'kb-001',
      name: '产品手册',
      description: '产品使用说明',
      documentCount: 3,
      createTime: '2026-07-27T10:00:00',
    };
    expect(kb.id).toBe('kb-001');
    expect(kb.name).toBe('产品手册');
    expect(kb.documentCount).toBe(3);
  });

  it('DocumentInfo 类型包含所有必需字段', () => {
    const doc: DocumentInfo = {
      documentId: 'doc-001',
      fileName: 'guide.pdf',
      fileSize: 1024,
      format: 'pdf',
      status: 'COMPLETED',
      chunkCount: 5,
      failReason: null,
      uploadTime: '2026-07-27T10:00:00',
    };
    expect(doc.documentId).toBe('doc-001');
    expect(doc.status).toBe('COMPLETED');
    expect(doc.chunkCount).toBe(5);
  });

  it('DocumentStatus 类型为四种状态联合类型', () => {
    const pending: DocumentStatus = 'PENDING';
    const processing: DocumentStatus = 'PROCESSING';
    const completed: DocumentStatus = 'COMPLETED';
    const failed: DocumentStatus = 'FAILED';
    expect(pending).toBe('PENDING');
    expect(processing).toBe('PROCESSING');
    expect(completed).toBe('COMPLETED');
    expect(failed).toBe('FAILED');
  });

  it('DocumentStatusResponse 类型包含所有必需字段', () => {
    const status: DocumentStatusResponse = {
      documentId: 'doc-001',
      status: 'PROCESSING',
      chunkCount: 0,
      failReason: null,
    };
    expect(status.documentId).toBe('doc-001');
    expect(status.status).toBe('PROCESSING');
  });
});
