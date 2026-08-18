import type {
  WorkflowExecutionDetail,
  WorkflowExecutionSummary,
  WorkflowStreamCallbacks,
  WorkflowTemplateDetail,
  WorkflowTemplateSummary,
} from '@/types';

/**
 * 工作流编排 API 封装（P2 新增，AC-027/AC-032）
 * 业务含义：封装工作流模板查询、执行（SSE）、历史查询与终止接口。
 * streamExecute 复用 chat.ts 的 fetch + ReadableStream 手动解析 SSE 模式。
 */

const API_BASE = '/api/app/workflows';

/** 查询模板列表（AC-001） */
export async function listTemplates(): Promise<WorkflowTemplateSummary[]> {
  const response = await fetch(`${API_BASE}`);
  if (!response.ok) throw new Error('获取模板列表失败');
  const json = await response.json();
  return json.data ?? [];
}

/** 查询模板详情（AC-002） */
export async function getTemplate(id: string): Promise<WorkflowTemplateDetail> {
  const response = await fetch(`${API_BASE}/${id}`);
  if (!response.ok) throw new Error('获取模板详情失败');
  const json = await response.json();
  return json.data;
}

/** 查询执行历史列表（AC-027） */
export async function listExecutions(): Promise<WorkflowExecutionSummary[]> {
  const response = await fetch(`${API_BASE}/executions`);
  if (!response.ok) throw new Error('获取执行历史失败');
  const json = await response.json();
  return json.data ?? [];
}

/** 查询执行详情（AC-011） */
export async function getExecution(id: string): Promise<WorkflowExecutionDetail> {
  const response = await fetch(`${API_BASE}/executions/${id}`);
  if (!response.ok) throw new Error('获取执行详情失败');
  const json = await response.json();
  return json.data;
}

/** 终止执行（AC-022） */
export async function terminate(id: string): Promise<void> {
  const response = await fetch(`${API_BASE}/executions/${id}`, { method: 'DELETE' });
  if (!response.ok) throw new Error('终止执行失败');
}

/**
 * 流式执行工作流（SSE，AC-004/005/006/008/009/010）
 * 业务含义：调用后端 SSE 接口实时接收执行进度与 Agent 流式输出，
 * 事件 data 为 JSON，按事件名分发到 WorkflowStreamCallbacks。
 * 配合 AbortController 实现"停止"（AC-022）。
 *
 * @param templateId 模板 ID
 * @param parameters 执行参数
 * @param modelId 模型 ID（可选）
 * @param callbacks SSE 事件回调
 * @param signal AbortController.signal，用于停止
 */
export async function streamExecute(
  templateId: string,
  parameters: Record<string, unknown>,
  modelId: string,
  callbacks: WorkflowStreamCallbacks,
  signal: AbortSignal,
): Promise<void> {
  let response: Response;
  try {
    response = await fetch(`${API_BASE}/${templateId}/execute`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ parameters, modelId }),
      signal,
    });
  } catch {
    // 网络错误（主动 abort 不触发 onError）
    if (signal.aborted) return;
    callbacks.onError('服务暂时不可用，请稍后重试');
    return;
  }

  if (!response.ok) {
    callbacks.onError('服务暂时不可用，请稍后重试');
    return;
  }

  await parseSseStream(response, callbacks, signal);
}

/**
 * 恢复执行工作流（SSE，P3 新增，AC-017）
 * 业务含义：对 PAUSED 状态的执行从断点恢复：后端跳过已完成步骤（step_skipped）
 * 并重放事件到新 SSE 流；前端复用同一套事件回调，输出面板继续追加。
 * 与 streamExecute 共用 SSE 解析循环，abort 行为一致。
 *
 * @param executionId 待恢复的执行 ID
 * @param callbacks SSE 事件回调（与 streamExecute 相同）
 * @param signal AbortController.signal，用于停止
 */
export async function streamResume(
  executionId: string,
  callbacks: WorkflowStreamCallbacks,
  signal: AbortSignal,
): Promise<void> {
  let response: Response;
  try {
    response = await fetch(`${API_BASE}/executions/${executionId}/resume`, {
      method: 'POST',
      signal,
    });
  } catch {
    if (signal.aborted) return;
    callbacks.onError('恢复执行失败，请稍后重试');
    return;
  }

  if (!response.ok) {
    callbacks.onError('恢复执行失败，请稍后重试');
    return;
  }

  await parseSseStream(response, callbacks, signal);
}

/**
 * 解析 SSE 流并分发事件（streamExecute/streamResume 共用）
 * 按 SSE 规范：事件以空行分隔，event: 为事件名，data: 为 JSON。
 *
 * 业务含义（断流兜底）：服务端提前关闭流（如 emitter 超时/后端异常）时
 * reader 会正常收到 done 而不抛异常--若未收到任何终态事件
 * （workflow_complete/workflow_failed/workflow_paused），说明执行结果未知，
 * 必须回调 onError 让视图退出"执行中"状态，避免页面永久卡住。
 */
async function parseSseStream(
  response: Response,
  callbacks: WorkflowStreamCallbacks,
  signal: AbortSignal,
): Promise<void> {
  const reader = response.body!.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let currentEvent = '';
  let dataLines: string[] = [];
  let sawTerminal = false;

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() || '';

      for (const rawLine of lines) {
        const line = rawLine.endsWith('\r') ? rawLine.slice(0, -1) : rawLine;
        if (line === '') {
          // 空行 = 事件分隔符，派发累积的事件
          if (currentEvent && dataLines.length > 0) {
            if (handleWorkflowEvent(currentEvent, dataLines.join('\n'), callbacks)) {
              sawTerminal = true;
            }
          }
          currentEvent = '';
          dataLines = [];
        } else if (line.startsWith('event:')) {
          currentEvent = line.slice(6).trim();
        } else if (line.startsWith('data:')) {
          dataLines.push(line.slice(5));
        }
      }
    }
    // 流结束后派发最后一个未分隔的事件
    if (currentEvent && dataLines.length > 0) {
      if (handleWorkflowEvent(currentEvent, dataLines.join('\n'), callbacks)) {
        sawTerminal = true;
      }
    }
    // 断流兜底：流结束但从未收到终态事件 = 服务端提前关闭，执行结果未知
    if (!sawTerminal && !signal.aborted) {
      callbacks.onError('连接已中断，执行结果未知，请在历史记录中查看执行状态');
    }
  } catch {
    // 流式过程网络断开（非主动 abort）
    if (!signal.aborted) {
      callbacks.onError('网络中断，执行信息不完整');
    }
  }
}

/** 终态事件集合：收到任一事件表示流会正常收尾（done 不再视为异常断流） */
const TERMINAL_EVENTS = new Set(['workflow_complete', 'workflow_failed', 'workflow_paused']);

/**
 * 处理工作流 SSE 事件
 * @returns 是否为终态事件（供 parseSseStream 判断断流兜底）
 */
function handleWorkflowEvent(event: string, data: string, callbacks: WorkflowStreamCallbacks): boolean {
  // 事件 data 为 JSON 对象，解析后分发到对应回调
  let parsed: Record<string, unknown>;
  try {
    parsed = JSON.parse(data);
  } catch {
    // JSON 解析失败静默跳过（容错），但事件名到达即计为终态（避免误报断流）
    return TERMINAL_EVENTS.has(event);
  }

  switch (event) {
    case 'workflow_start':
      callbacks.onWorkflowStart(parsed as never);
      break;
    case 'step_start':
      callbacks.onStepStart(parsed as never);
      break;
    case 'token':
      callbacks.onToken(parsed as never);
      break;
    case 'step_complete':
      callbacks.onStepComplete(parsed as never);
      break;
    case 'step_retry':
      callbacks.onStepRetry(parsed as never);
      break;
    case 'step_error':
      callbacks.onStepError(parsed as never);
      break;
    case 'branch_selected':
      callbacks.onBranchSelected(parsed as never);
      break;
    case 'loop_iteration':
      callbacks.onLoopIteration(parsed as never);
      break;
    case 'group_start':
      callbacks.onGroupStart(parsed as never);
      break;
    case 'group_complete':
      callbacks.onGroupComplete(parsed as never);
      break;
    case 'workflow_complete':
      callbacks.onWorkflowComplete(parsed as never);
      break;
    case 'workflow_failed':
      callbacks.onWorkflowFailed(parsed as never);
      break;
    // ===== P3 新增事件（可选回调，未注册时静默跳过）=====
    case 'workflow_paused':
      callbacks.onWorkflowPaused?.(parsed as never);
      break;
    case 'step_skipped':
      callbacks.onStepSkipped?.(parsed as never);
      break;
    case 'supervisor_plan':
      callbacks.onSupervisorPlan?.(parsed as never);
      break;
    case 'supervisor_dispatch':
      callbacks.onSupervisorDispatch?.(parsed as never);
      break;
    case 'supervisor_summary':
      callbacks.onSupervisorSummary?.(parsed as never);
      break;
    default:
      break; // 未知事件静默跳过
  }
  return TERMINAL_EVENTS.has(event);
}
