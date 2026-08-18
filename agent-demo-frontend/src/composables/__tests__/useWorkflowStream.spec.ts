import { describe, it, expect, vi, beforeEach } from 'vitest';
import { useWorkflowStream } from '../useWorkflowStream';
import { streamExecute, streamResume, terminate } from '@/api/workflow';
import type { WorkflowStreamCallbacks } from '@/types';

/**
 * useWorkflowStream composable 单元测试（CR-001 Task-24，AC-033 前置）
 * 验证 SSE 事件处理、状态管理与暂停/恢复逻辑的正确性。
 * 测试策略：mock @/api/workflow 模块，捕获 composable 内部构建的 callbacks，
 * 模拟 SSE 事件触发，断言状态变化。
 */

vi.mock('@/api/workflow', () => ({
  streamExecute: vi.fn(),
  streamResume: vi.fn(),
  terminate: vi.fn(),
}));

/** 捕获 streamExecute 传入的 callbacks（第 4 个参数） */
function captureExecuteCallbacks(): WorkflowStreamCallbacks {
  const mock = streamExecute as ReturnType<typeof vi.fn>;
  return mock.mock.calls[0][3] as WorkflowStreamCallbacks;
}

/** 捕获 streamResume 传入的 callbacks（第 2 个参数） */
function captureResumeCallbacks(): WorkflowStreamCallbacks {
  const mock = streamResume as ReturnType<typeof vi.fn>;
  return mock.mock.calls[0][1] as WorkflowStreamCallbacks;
}

beforeEach(() => {
  vi.restoreAllMocks();
});

describe('useWorkflowStream', () => {
  // ===== 验证标准 1：初始状态 =====
  it('初始状态正确：isExecuting=false, isPaused=false, agentOutputs=空, finalResult=空', () => {
    const { isExecuting, isPaused, agentOutputs, finalResult, error } = useWorkflowStream();
    expect(isExecuting.value).toBe(false);
    expect(isPaused.value).toBe(false);
    expect(agentOutputs.value).toEqual([]);
    expect(finalResult.value).toBe('');
    expect(error.value).toBe('');
  });

  // ===== 验证标准 2：startExecution 调用 streamExecute 并设置 isExecuting=true =====
  it('调用 startExecution 后 isExecuting=true，且 streamExecute 被正确调用', async () => {
    const mockFn = streamExecute as ReturnType<typeof vi.fn>;
    mockFn.mockResolvedValue(undefined);

    const { startExecution, isExecuting } = useWorkflowStream();
    await startExecution('tpl-001', { content: 'test' }, '');

    expect(mockFn).toHaveBeenCalledTimes(1);
    expect(mockFn.mock.calls[0][0]).toBe('tpl-001');
    expect(mockFn.mock.calls[0][1]).toEqual({ content: 'test' });
    expect(isExecuting.value).toBe(true);
  });

  // ===== 验证标准 2 扩展：SSE 事件正确触发状态更新 =====
  it('workflow_start 事件设置 currentExecutionId 和 modeProgress', async () => {
    const mockFn = streamExecute as ReturnType<typeof vi.fn>;
    mockFn.mockResolvedValue(undefined);

    const { startExecution, currentExecutionId, modeProgress } = useWorkflowStream();
    await startExecution('tpl-001', {}, '');

    const callbacks = captureExecuteCallbacks();
    callbacks.onWorkflowStart({
      executionId: 'exec-001',
      templateName: '测试模板',
      mode: 'SEQUENTIAL',
      agentCount: 3,
    });

    expect(currentExecutionId.value).toBe('exec-001');
    expect(modeProgress.value).toContain('SEQUENTIAL');
  });

  it('step_start 事件创建面板并设置为 running', async () => {
    const mockFn = streamExecute as ReturnType<typeof vi.fn>;
    mockFn.mockResolvedValue(undefined);

    const { startExecution, agentOutputs } = useWorkflowStream();
    await startExecution('tpl-001', {}, '');

    const callbacks = captureExecuteCallbacks();
    callbacks.onStepStart({
      agentIndex: 0,
      agentName: '研究 Agent',
      totalAgents: 3,
    });

    expect(agentOutputs.value).toHaveLength(1);
    expect(agentOutputs.value[0].agentIndex).toBe(0);
    expect(agentOutputs.value[0].agentName).toBe('研究 Agent');
    expect(agentOutputs.value[0].status).toBe('running');
  });

  it('token 事件追加输出到对应面板', async () => {
    const mockFn = streamExecute as ReturnType<typeof vi.fn>;
    mockFn.mockResolvedValue(undefined);

    const { startExecution, agentOutputs } = useWorkflowStream();
    await startExecution('tpl-001', {}, '');

    const callbacks = captureExecuteCallbacks();
    callbacks.onStepStart({ agentIndex: 0, agentName: 'Agent', totalAgents: 1 });
    callbacks.onToken({ agentIndex: 0, content: 'Hello ' });
    callbacks.onToken({ agentIndex: 0, content: 'World' });

    expect(agentOutputs.value[0].output).toBe('Hello World');
  });

  it('step_complete 事件设置面板为 completed 并记录耗时', async () => {
    const mockFn = streamExecute as ReturnType<typeof vi.fn>;
    mockFn.mockResolvedValue(undefined);

    const { startExecution, agentOutputs } = useWorkflowStream();
    await startExecution('tpl-001', {}, '');

    const callbacks = captureExecuteCallbacks();
    callbacks.onStepStart({ agentIndex: 0, agentName: 'Agent', totalAgents: 1 });
    callbacks.onStepComplete({ agentIndex: 0, agentName: 'Agent', durationMs: 500, outputLength: 100 });

    expect(agentOutputs.value[0].status).toBe('completed');
    expect(agentOutputs.value[0].durationMs).toBe(500);
  });

  it('workflow_complete 事件设置 finalResult 和 statusLabel，isExecuting=false', async () => {
    const mockFn = streamExecute as ReturnType<typeof vi.fn>;
    mockFn.mockResolvedValue(undefined);

    const { startExecution, isExecuting, finalResult, statusLabel } = useWorkflowStream();
    await startExecution('tpl-001', {}, '');

    const callbacks = captureExecuteCallbacks();
    callbacks.onWorkflowComplete({
      executionId: 'exec-001',
      finalResult: '执行完成结果',
      mode: 'SEQUENTIAL',
      totalDurationMs: 1000,
    });

    expect(finalResult.value).toBe('执行完成结果');
    expect(statusLabel.value).toBe('COMPLETED');
    expect(isExecuting.value).toBe(false);
  });

  it('workflow_failed 事件设置 error 和 statusLabel，isExecuting=false', async () => {
    const mockFn = streamExecute as ReturnType<typeof vi.fn>;
    mockFn.mockResolvedValue(undefined);

    const { startExecution, isExecuting, error, statusLabel } = useWorkflowStream();
    await startExecution('tpl-001', {}, '');

    const callbacks = captureExecuteCallbacks();
    callbacks.onWorkflowFailed({
      executionId: 'exec-001',
      status: 'FAILED',
      error: 'Agent 执行超时',
    });

    expect(error.value).toBe('Agent 执行超时');
    expect(statusLabel.value).toBe('FAILED');
    expect(isExecuting.value).toBe(false);
  });

  // ===== 验证标准 3：workflow_paused 事件 =====
  it('收到 workflow_paused 事件后 isPaused=true, isExecuting=false', async () => {
    const mockFn = streamExecute as ReturnType<typeof vi.fn>;
    mockFn.mockResolvedValue(undefined);

    const { startExecution, isPaused, isExecuting } = useWorkflowStream();
    await startExecution('tpl-001', {}, '');

    const callbacks = captureExecuteCallbacks();
    callbacks.onWorkflowPaused!({
      executionId: 'exec-001',
      failedAgent: '分析 Agent',
      failedIndex: 1,
      error: '重试耗尽',
      resumable: true,
    });

    expect(isPaused.value).toBe(true);
    expect(isExecuting.value).toBe(false);
  });

  // ===== 验证标准 4：streamResume 恢复执行 =====
  it('调用 resumeExecution 后 isPaused=false, isExecuting=true，且 streamResume 被调用', async () => {
    const mockExecute = streamExecute as ReturnType<typeof vi.fn>;
    const mockResume = streamResume as ReturnType<typeof vi.fn>;
    mockExecute.mockResolvedValue(undefined);
    mockResume.mockResolvedValue(undefined);

    const { startExecution, resumeExecution, isPaused, isExecuting } = useWorkflowStream();
    await startExecution('tpl-001', {}, '');

    // 触发暂停
    const execCallbacks = captureExecuteCallbacks();
    execCallbacks.onWorkflowPaused!({
      executionId: 'exec-001',
      failedAgent: 'Agent',
      failedIndex: 0,
      error: '超时',
      resumable: true,
    });
    expect(isPaused.value).toBe(true);

    // 恢复
    await resumeExecution();

    expect(mockResume).toHaveBeenCalledTimes(1);
    expect(mockResume.mock.calls[0][0]).toBe('exec-001');
    expect(isPaused.value).toBe(false);
    expect(isExecuting.value).toBe(true);
  });

  // ===== 验证标准 5：step_skipped 事件 =====
  it('收到 step_skipped 事件后对应 agentOutput 标记为 skipped', async () => {
    const mockResume = streamResume as ReturnType<typeof vi.fn>;
    mockResume.mockResolvedValue(undefined);

    const { resumeExecution, agentOutputs, currentExecutionId } = useWorkflowStream();
    currentExecutionId.value = 'exec-001';
    await resumeExecution();

    const callbacks = captureResumeCallbacks();
    callbacks.onStepSkipped!({
      agentIndex: 0,
      agentName: '研究 Agent',
      reason: '已完成',
    });

    expect(agentOutputs.value).toHaveLength(1);
    expect(agentOutputs.value[0].status).toBe('skipped');
    expect(agentOutputs.value[0].agentName).toBe('研究 Agent');
  });

  // ===== 验证标准 6：terminate =====
  it('调用 terminateExecution 后 isExecuting=false, isPaused=false', async () => {
    const mockTerminate = terminate as ReturnType<typeof vi.fn>;
    mockTerminate.mockResolvedValue(undefined);

    const { terminateExecution, isPaused, isExecuting, statusLabel, currentExecutionId } = useWorkflowStream();
    currentExecutionId.value = 'exec-001';
    isPaused.value = true;

    await terminateExecution();

    expect(mockTerminate).toHaveBeenCalledTimes(1);
    expect(mockTerminate.mock.calls[0][0]).toBe('exec-001');
    expect(isPaused.value).toBe(false);
    expect(isExecuting.value).toBe(false);
    expect(statusLabel.value).toBe('TERMINATED');
  });

  // ===== 补充：Supervisor 事件 =====
  it('supervisor_plan 事件初始化子任务卡片列表', async () => {
    const mockFn = streamExecute as ReturnType<typeof vi.fn>;
    mockFn.mockResolvedValue(undefined);

    const { startExecution, supervisorCards } = useWorkflowStream();
    await startExecution('tpl-001', {}, '');

    const callbacks = captureExecuteCallbacks();
    callbacks.onSupervisorPlan!({
      subtasks: [
        { id: 1, description: '任务一', agent: 'Worker-A', routedAgent: 'Worker-A', routed: true },
        { id: 2, description: '任务二', agent: '', routedAgent: 'Worker-B', routed: false },
      ],
      totalSubtasks: 2,
    });

    expect(supervisorCards.value).toHaveLength(2);
    expect(supervisorCards.value[0].status).toBe('pending');
    expect(supervisorCards.value[1].routed).toBe(false);
  });

  it('supervisor_dispatch 事件高亮对应子任务为 running', async () => {
    const mockFn = streamExecute as ReturnType<typeof vi.fn>;
    mockFn.mockResolvedValue(undefined);

    const { startExecution, supervisorCards } = useWorkflowStream();
    await startExecution('tpl-001', {}, '');

    const callbacks = captureExecuteCallbacks();
    callbacks.onSupervisorPlan!({
      subtasks: [
        { id: 1, description: '任务一', agent: 'A', routedAgent: 'A', routed: true },
      ],
      totalSubtasks: 1,
    });
    callbacks.onSupervisorDispatch!({
      subtaskIndex: 1,
      totalSubtasks: 1,
      description: '任务一',
      agentName: 'A',
      routed: true,
    });

    expect(supervisorCards.value[0].status).toBe('running');
  });

  it('supervisor_summary 事件设置 isSummarizing=true', async () => {
    const mockFn = streamExecute as ReturnType<typeof vi.fn>;
    mockFn.mockResolvedValue(undefined);

    const { startExecution, isSummarizing } = useWorkflowStream();
    await startExecution('tpl-001', {}, '');

    const callbacks = captureExecuteCallbacks();
    callbacks.onSupervisorSummary!({ subtaskCount: 3 });

    expect(isSummarizing.value).toBe(true);
  });

  // ===== 补充：stopExecution（abort）=====
  it('stopExecution 设置 isExecuting=false', async () => {
    const mockFn = streamExecute as ReturnType<typeof vi.fn>;
    mockFn.mockResolvedValue(undefined);

    const { startExecution, stopExecution, isExecuting } = useWorkflowStream();
    await startExecution('tpl-001', {}, '');

    expect(isExecuting.value).toBe(true);
    stopExecution();
    expect(isExecuting.value).toBe(false);
  });

  // ===== 补充：onError 回调 =====
  it('onError 回调设置 error 且 isExecuting=false', async () => {
    const mockFn = streamExecute as ReturnType<typeof vi.fn>;
    mockFn.mockResolvedValue(undefined);

    const { startExecution, error, isExecuting } = useWorkflowStream();
    await startExecution('tpl-001', {}, '');

    const callbacks = captureExecuteCallbacks();
    callbacks.onError('网络中断');

    expect(error.value).toBe('网络中断');
    expect(isExecuting.value).toBe(false);
  });

  // ===== 补充：initAgents 初始化面板 =====
  it('initAgents 初始化 agentOutputs 面板列表', () => {
    const { initAgents, agentOutputs } = useWorkflowStream();
    initAgents([{ name: 'Agent-A' }, { name: 'Agent-B' }]);

    expect(agentOutputs.value).toHaveLength(2);
    expect(agentOutputs.value[0].agentName).toBe('Agent-A');
    expect(agentOutputs.value[0].status).toBe('pending');
    expect(agentOutputs.value[1].agentName).toBe('Agent-B');
    expect(agentOutputs.value[1].status).toBe('pending');
  });
});
