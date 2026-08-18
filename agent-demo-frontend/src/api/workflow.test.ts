// @vitest-environment node
import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  listTemplates,
  getTemplate,
  listExecutions,
  getExecution,
  terminate,
  streamExecute,
  streamResume,
} from './workflow';
import type { WorkflowTemplateSummary, WorkflowStreamCallbacks } from '@/types';

/**
 * workflow API 封装测试（P2 Task-16）
 * 验证标准来源：任务规划 Task-16 验证标准
 * 关联 AC：AC-001, AC-002, AC-004, AC-005, AC-006, AC-008, AC-009, AC-010, AC-022, AC-027
 */

/** mock fetch 的辅助方法，构造 Result<T> 响应 */
function mockFetchSuccess<T>(data: T): void {
  vi.mocked(global.fetch).mockResolvedValueOnce({
    ok: true,
    json: async () => ({ success: true, code: 200, message: '成功', data, traceId: 'test' }),
  } as Response);
}

beforeEach(() => {
  vi.restoreAllMocks();
  global.fetch = vi.fn();
});

/** 构造 SSE 响应体（ReadableStream），供各 describe 共用 */
function mockSseStream(events: { event: string; data: string }[]): void {
  const encoder = new TextEncoder();
  const chunks = events.map((e) => encoder.encode(`event: ${e.event}\ndata: ${e.data}\n\n`));
  const stream = new ReadableStream({
    start(controller) {
      chunks.forEach((chunk) => controller.enqueue(chunk));
      controller.close();
    },
  });
  vi.mocked(global.fetch).mockResolvedValueOnce({
    ok: true,
    body: stream,
  } as unknown as Response);
}

describe('workflow REST API', () => {
  it('listTemplates 发送 GET /api/app/workflows 并返回模板列表（AC-001）', async () => {
    const templates: WorkflowTemplateSummary[] = [
      { id: 'tpl-1', name: '研究-分析-总结', description: '串行', mode: 'SEQUENTIAL', agentCount: 3, parameters: [] },
    ];
    mockFetchSuccess(templates);

    const result = await listTemplates();
    expect(global.fetch).toHaveBeenCalledWith('/api/app/workflows');
    expect(result).toHaveLength(1);
    expect(result[0].id).toBe('tpl-1');
    expect(result[0].mode).toBe('SEQUENTIAL');
  });

  it('getTemplate 发送 GET 并返回模板详情（AC-002）', async () => {
    mockFetchSuccess({ id: 'tpl-1', name: '多角度审查', mode: 'PARALLEL', maxRetries: 3, agents: [], parameters: [], parallelGroups: [] });
    const result = await getTemplate('tpl-1');
    expect(global.fetch).toHaveBeenCalledWith('/api/app/workflows/tpl-1');
    expect(result.mode).toBe('PARALLEL');
  });

  it('listExecutions 发送 GET /executions 并返回历史列表（AC-027）', async () => {
    mockFetchSuccess([{ executionId: 'e1', templateId: 't1', templateName: '质量评分-修订', mode: 'LOOP', status: 'COMPLETED', startTime: null, endTime: null, finalResult: 'r', iterationCount: 3 }]);
    const result = await listExecutions();
    expect(global.fetch).toHaveBeenCalledWith('/api/app/workflows/executions');
    expect(result[0].mode).toBe('LOOP');
    expect(result[0].iterationCount).toBe(3);
  });

  it('terminate 发送 DELETE /executions/{id}（AC-022）', async () => {
    vi.mocked(global.fetch).mockResolvedValueOnce({ ok: true, json: async () => ({ success: true }) } as Response);
    await terminate('exec-1');
    expect(global.fetch).toHaveBeenCalledWith('/api/app/workflows/executions/exec-1', { method: 'DELETE' });
  });

  it('listExecutions 失败时抛出异常', async () => {
    vi.mocked(global.fetch).mockResolvedValueOnce({ ok: false } as Response);
    await expect(listExecutions()).rejects.toThrow('获取执行历史失败');
  });
});

describe('streamExecute SSE 解析', () => {
  it('应分发 workflow_start / token / workflow_complete 事件', async () => {
    mockSseStream([
      { event: 'workflow_start', data: JSON.stringify({ executionId: 'e1', templateName: '研究-分析-总结', mode: 'SEQUENTIAL', agentCount: 3 }) },
      { event: 'step_start', data: JSON.stringify({ agentIndex: 0, agentName: '研究 Agent', totalAgents: 3 }) },
      { event: 'token', data: JSON.stringify({ agentIndex: 0, content: '研究片段' }) },
      { event: 'workflow_complete', data: JSON.stringify({ executionId: 'e1', finalResult: '总结', mode: 'SEQUENTIAL', totalDurationMs: 100 }) },
    ]);

    const calls: string[] = [];
    await streamExecute('tpl-1', { topic: 'AI' }, '', {
      onWorkflowStart: () => { calls.push('workflow_start'); },
      onStepStart: () => { calls.push('step_start'); },
      onToken: () => { calls.push('token'); },
      onStepComplete: () => { calls.push('step_complete'); },
      onStepRetry: () => {},
      onStepError: () => {},
      onBranchSelected: () => {},
      onLoopIteration: () => {},
      onGroupStart: () => {},
      onGroupComplete: () => {},
      onWorkflowComplete: () => { calls.push('workflow_complete'); },
      onWorkflowFailed: () => {},
      onError: () => {},
    }, new AbortController().signal);

    expect(calls).toEqual(['workflow_start', 'step_start', 'token', 'workflow_complete']);
  });

  it('应分发并行/条件/循环模式事件', async () => {
    mockSseStream([
      { event: 'group_start', data: JSON.stringify({ groupIndex: 0, groupName: '安全审查', agentCount: 1 }) },
      { event: 'branch_selected', data: JSON.stringify({ branchIndex: 1, branchName: '复杂拆解', agentCount: 3 }) },
      { event: 'loop_iteration', data: JSON.stringify({ iteration: 1, maxIterations: 5, agentCount: 2 }) },
      { event: 'workflow_failed', data: JSON.stringify({ executionId: 'e1', status: 'FAILED', error: '失败' }) },
    ]);

    const calls: string[] = [];
    await streamExecute('tpl-1', {}, '', {
      onWorkflowStart: () => {},
      onStepStart: () => {},
      onToken: () => {},
      onStepComplete: () => {},
      onStepRetry: () => {},
      onStepError: () => {},
      onBranchSelected: () => { calls.push('branch_selected'); },
      onLoopIteration: () => { calls.push('loop_iteration'); },
      onGroupStart: () => { calls.push('group_start'); },
      onGroupComplete: () => {},
      onWorkflowComplete: () => {},
      onWorkflowFailed: () => { calls.push('workflow_failed'); },
      onError: () => {},
    }, new AbortController().signal);

    expect(calls).toEqual(['group_start', 'branch_selected', 'loop_iteration', 'workflow_failed']);
  });

  it('网络错误时应调用 onError', async () => {
    vi.mocked(global.fetch).mockResolvedValueOnce({ ok: false } as Response);
    const onError = vi.fn();
    await streamExecute('tpl-1', {}, '', {
      onWorkflowStart: () => {},
      onStepStart: () => {},
      onToken: () => {},
      onStepComplete: () => {},
      onStepRetry: () => {},
      onStepError: () => {},
      onBranchSelected: () => {},
      onLoopIteration: () => {},
      onGroupStart: () => {},
      onGroupComplete: () => {},
      onWorkflowComplete: () => {},
      onWorkflowFailed: () => {},
      onError,
    }, new AbortController().signal);
    expect(onError).toHaveBeenCalled();
  });
});

describe('P3 新增 SSE 事件分发（Task-19，AC-016/AC-017/AC-007）', () => {
  /** 构造含 P3 新回调的完整回调集（未关注的回调为空实现） */
  function makeCallbacks(overrides: Partial<WorkflowStreamCallbacks> = {}): WorkflowStreamCallbacks {
    return {
      onWorkflowStart: () => {},
      onStepStart: () => {},
      onToken: () => {},
      onStepComplete: () => {},
      onStepRetry: () => {},
      onStepError: () => {},
      onBranchSelected: () => {},
      onLoopIteration: () => {},
      onGroupStart: () => {},
      onGroupComplete: () => {},
      onWorkflowComplete: () => {},
      onWorkflowFailed: () => {},
      onError: () => {},
      ...overrides,
    };
  }

  it('workflow_paused 事件应分发到 onWorkflowPaused，data 字段完整（AC-016）', async () => {
    mockSseStream([
      { event: 'workflow_paused', data: JSON.stringify({ executionId: 'exec-1', failedAgent: '研究 Agent', failedIndex: 1, error: 'Agent 执行失败: 研究 Agent: 超时', resumable: true }) },
    ]);
    const onWorkflowPaused = vi.fn();
    await streamExecute('tpl-1', {}, '', makeCallbacks({ onWorkflowPaused }), new AbortController().signal);

    expect(onWorkflowPaused).toHaveBeenCalledTimes(1);
    expect(onWorkflowPaused).toHaveBeenCalledWith({
      executionId: 'exec-1',
      failedAgent: '研究 Agent',
      failedIndex: 1,
      error: 'Agent 执行失败: 研究 Agent: 超时',
      resumable: true,
    });
  });

  it('step_skipped 事件应分发到 onStepSkipped（AC-017）', async () => {
    mockSseStream([
      { event: 'step_skipped', data: JSON.stringify({ agentIndex: 0, agentName: '研究 Agent', reason: '断点恢复' }) },
    ]);
    const onStepSkipped = vi.fn();
    await streamExecute('tpl-1', {}, '', makeCallbacks({ onStepSkipped }), new AbortController().signal);

    expect(onStepSkipped).toHaveBeenCalledWith({ agentIndex: 0, agentName: '研究 Agent', reason: '断点恢复' });
  });

  it('supervisor_plan 事件应分发到 onSupervisorPlan（subtasks 数组 + totalSubtasks，AC-007）', async () => {
    mockSseStream([
      {
        event: 'supervisor_plan',
        data: JSON.stringify({
          subtasks: [
            { id: 1, description: '调研分类算法', agent: '研究', routedAgent: '研究', routed: true },
            { id: 2, description: '归纳报告', agent: '神秘角色', routedAgent: '研究', routed: false },
          ],
          totalSubtasks: 2,
        }),
      },
    ]);
    const onSupervisorPlan = vi.fn();
    await streamExecute('tpl-1', {}, '', makeCallbacks({ onSupervisorPlan }), new AbortController().signal);

    expect(onSupervisorPlan).toHaveBeenCalledTimes(1);
    const arg = onSupervisorPlan.mock.calls[0][0];
    expect(arg.totalSubtasks).toBe(2);
    expect(arg.subtasks).toHaveLength(2);
    expect(arg.subtasks[0]).toMatchObject({ id: 1, description: '调研分类算法', routed: true });
    expect(arg.subtasks[1]).toMatchObject({ routedAgent: '研究', routed: false });
  });

  it('supervisor_dispatch/supervisor_summary 事件应分发到对应回调（AC-007）', async () => {
    mockSseStream([
      { event: 'supervisor_dispatch', data: JSON.stringify({ subtaskIndex: 1, totalSubtasks: 3, description: '调研', agentName: '研究', routed: true }) },
      { event: 'supervisor_summary', data: JSON.stringify({ subtaskCount: 3 }) },
    ]);
    const onSupervisorDispatch = vi.fn();
    const onSupervisorSummary = vi.fn();
    await streamExecute('tpl-1', {}, '', makeCallbacks({ onSupervisorDispatch, onSupervisorSummary }), new AbortController().signal);

    expect(onSupervisorDispatch).toHaveBeenCalledWith({ subtaskIndex: 1, totalSubtasks: 3, description: '调研', agentName: '研究', routed: true });
    expect(onSupervisorSummary).toHaveBeenCalledWith({ subtaskCount: 3 });
  });

  it('未知事件应静默跳过（向后兼容）', async () => {
    mockSseStream([
      { event: 'future_unknown_event', data: JSON.stringify({ foo: 'bar' }) },
      { event: 'step_start', data: JSON.stringify({ agentIndex: 0, agentName: 'A', totalAgents: 1 }) },
      // 正常收尾的终态事件（断流兜底上线后，无终态事件的流会触发 onError）
      { event: 'workflow_complete', data: JSON.stringify({ executionId: 'e1', finalResult: 'ok', mode: 'SEQUENTIAL', totalDurationMs: 1 }) },
    ]);
    const onStepStart = vi.fn();
    const onError = vi.fn();
    await streamExecute('tpl-1', {}, '', makeCallbacks({ onStepStart, onError }), new AbortController().signal);

    expect(onStepStart).toHaveBeenCalledTimes(1);
    expect(onError).not.toHaveBeenCalled();
  });
});

describe('streamResume 恢复流（P3 Task-19，AC-017）', () => {
  it('应 POST 到 /executions/{id}/resume 且无请求体，事件正常分发', async () => {
    mockSseStream([
      { event: 'step_skipped', data: JSON.stringify({ agentIndex: 0, agentName: '研究 Agent', reason: '断点恢复' }) },
      { event: 'workflow_complete', data: JSON.stringify({ executionId: 'exec-1', finalResult: 'ok', mode: 'SEQUENTIAL', totalDurationMs: 100 }) },
    ]);
    const onStepSkipped = vi.fn();
    const onWorkflowComplete = vi.fn();
    await streamResume('exec-1', makeResumeCallbacks({ onStepSkipped, onWorkflowComplete }), new AbortController().signal);

    expect(global.fetch).toHaveBeenCalledTimes(1);
    const [url, init] = (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe('/api/app/workflows/executions/exec-1/resume');
    expect(init?.method).toBe('POST');
    expect(init?.body).toBeUndefined();
    expect(onStepSkipped).toHaveBeenCalledTimes(1);
    expect(onWorkflowComplete).toHaveBeenCalledTimes(1);
  });

  it('主动 abort 时静默返回（不触发 onError），与 streamExecute 行为一致', async () => {
    const controller = new AbortController();
    (global.fetch as ReturnType<typeof vi.fn>).mockImplementation(() => {
      controller.abort();
      return Promise.reject(new DOMException('Aborted', 'AbortError'));
    });
    const onError = vi.fn();
    await expect(streamResume('exec-1', makeResumeCallbacks({ onError }), controller.signal)).resolves.toBeUndefined();
    expect(onError).not.toHaveBeenCalled();
  });

  it('非 abort 网络错误时调用 onError 提示', async () => {
    (global.fetch as ReturnType<typeof vi.fn>).mockRejectedValueOnce(new TypeError('network down'));
    const onError = vi.fn();
    await streamResume('exec-1', makeResumeCallbacks({ onError }), new AbortController().signal);
    expect(onError).toHaveBeenCalledTimes(1);
  });

  /** 构造恢复流回调（含 P3 新回调，未关注的为空实现） */
  function makeResumeCallbacks(overrides: Partial<WorkflowStreamCallbacks> = {}): WorkflowStreamCallbacks {
    return {
      onWorkflowStart: () => {},
      onStepStart: () => {},
      onToken: () => {},
      onStepComplete: () => {},
      onStepRetry: () => {},
      onStepError: () => {},
      onBranchSelected: () => {},
      onLoopIteration: () => {},
      onGroupStart: () => {},
      onGroupComplete: () => {},
      onWorkflowComplete: () => {},
      onWorkflowFailed: () => {},
      onError: () => {},
      ...overrides,
    };
  }
});

describe('SSE 流异常中断兜底（BUG：emitter 超时/断流后页面卡执行中）', () => {
  /** 构造完整回调集（复用 P3 describe 的模式） */
  function makeCb(overrides: Partial<WorkflowStreamCallbacks> = {}): WorkflowStreamCallbacks {
    return {
      onWorkflowStart: () => {},
      onStepStart: () => {},
      onToken: () => {},
      onStepComplete: () => {},
      onStepRetry: () => {},
      onStepError: () => {},
      onBranchSelected: () => {},
      onLoopIteration: () => {},
      onGroupStart: () => {},
      onGroupComplete: () => {},
      onWorkflowComplete: () => {},
      onWorkflowFailed: () => {},
      onError: () => {},
      ...overrides,
    };
  }

  it('流结束但未收到终态事件时应触发 onError（服务端提前关闭流）', async () => {
    // 复现：SseEmitter 5 分钟超时被服务端完成连接 → 前端 reader done 但无终态事件 → 页面卡执行中
    mockSseStream([
      { event: 'workflow_start', data: JSON.stringify({ executionId: 'e1', templateName: '任务拆解', mode: 'SUPERVISOR', agentCount: 5 }) },
      { event: 'token', data: JSON.stringify({ agentIndex: 0, content: '部分输出后连接被服务端关闭' }) },
    ]);
    const onError = vi.fn();
    await streamExecute('tpl-1', {}, '', makeCb({ onError }), new AbortController().signal);
    expect(onError).toHaveBeenCalledTimes(1);
  });

  it('流结束且收到 workflow_complete 终态事件时不应触发 onError', async () => {
    mockSseStream([
      { event: 'workflow_start', data: JSON.stringify({ executionId: 'e1', templateName: '任务拆解', mode: 'SUPERVISOR', agentCount: 5 }) },
      { event: 'workflow_complete', data: JSON.stringify({ executionId: 'e1', finalResult: 'ok', mode: 'SUPERVISOR', totalDurationMs: 100 }) },
    ]);
    const onError = vi.fn();
    await streamExecute('tpl-1', {}, '', makeCb({ onError }), new AbortController().signal);
    expect(onError).not.toHaveBeenCalled();
  });

  it('流结束且收到 workflow_failed 终态事件时不应触发 onError', async () => {
    mockSseStream([
      { event: 'workflow_failed', data: JSON.stringify({ executionId: 'e1', status: 'FAILED', error: '执行失败' }) },
    ]);
    const onError = vi.fn();
    await streamExecute('tpl-1', {}, '', makeCb({ onError }), new AbortController().signal);
    expect(onError).not.toHaveBeenCalled();
  });

  it('恢复流（streamResume）异常结束同样触发 onError', async () => {
    mockSseStream([
      { event: 'step_skipped', data: JSON.stringify({ agentIndex: 0, agentName: '研究 Agent', reason: '断点恢复' }) },
    ]);
    const onError = vi.fn();
    await streamResume('exec-1', makeCb({ onError }), new AbortController().signal);
    expect(onError).toHaveBeenCalledTimes(1);
  });
});
