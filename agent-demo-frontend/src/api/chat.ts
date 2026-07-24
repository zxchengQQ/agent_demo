import type { StreamCallbacks } from '@/types';

/**
 * SSE 流式调用封装
 * 关联 AC：AC-002、AC-011、AC-012、AC-013、AC-020
 *
 * 业务含义：调用后端 SSE 接口，逐字接收大模型回复。
 * 使用 fetch + ReadableStream 手动解析 SSE 协议（EventSource 不支持 POST）。
 * 配合 AbortController 实现"停止生成"（AC-011）。
 */

const API_BASE = '/api/agent';

/**
 * 流式对话
 *
 * @param sessionId 会话 ID（首次为空字符串）
 * @param message 用户消息
 * @param enableThinking 是否开启深度思考（CR-001，AC-021），后端据此分流是否推送 reasoning 事件
 * @param enableTaskBreakdown 是否开启复杂任务拆解（CR-002，AC-001），后端据此分流是否执行任务拆解
 * @param callbacks SSE 事件回调
 * @param signal AbortController.signal，用于停止生成（AC-011）
 */
export async function streamChat(
  sessionId: string,
  message: string,
  enableThinking: boolean,
  enableTaskBreakdown: boolean,
  callbacks: StreamCallbacks,
  signal: AbortSignal,
): Promise<void> {
  let response: Response;
  try {
    response = await fetch(`${API_BASE}/chat/stream`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sessionId, message, enableThinking, enableTaskBreakdown }),
      signal,
    });
  } catch {
    // AC-013: 后端服务不可用 / 网络错误（主动 abort 不触发 onError）
    if (signal.aborted) return;
    callbacks.onError('服务暂时不可用，请稍后重试');
    return;
  }

  // AC-013: 后端返回错误状态码
  if (!response.ok) {
    callbacks.onError('服务暂时不可用，请稍后重试');
    return;
  }

  // 解析 SSE 流（按 SSE 规范：事件以空行分隔，多行 data: 用 \n 拼接）
  // 不 trim 内容以保留 Markdown 语法所需的空格（AC-023 回归修复）
  const reader = response.body!.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let currentEvent = '';
  let dataLines: string[] = [];

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
            handleSseEvent(currentEvent, dataLines.join('\n'), callbacks);
          }
          currentEvent = '';
          dataLines = [];
        } else if (line.startsWith('event:')) {
          currentEvent = line.slice(6).trim();
        } else if (line.startsWith('data:')) {
          // Spring SseEmitter 输出 data: 后不加空格，直接取内容（保留 Markdown 所需空格）
          dataLines.push(line.slice(5));
        }
      }
    }
    // 流结束后派发最后一个未分隔的事件
    if (currentEvent && dataLines.length > 0) {
      handleSseEvent(currentEvent, dataLines.join('\n'), callbacks);
    }
  } catch {
    // AC-012: 流式过程网络断开（非主动 abort）
    if (!signal.aborted) {
      callbacks.onError('网络中断，回复不完整');
    }
  }
}

/**
 * 处理 SSE 事件
 */
function handleSseEvent(event: string, data: string, callbacks: StreamCallbacks): void {
  switch (event) {
    case 'session':
      // AC-010: 透明续聊，后端通知新 sessionId
      callbacks.onSession(data);
      break;
    case 'token':
      // AC-020: 逐字显示
      callbacks.onToken(data);
      break;
    case 'reasoning':
      // CR-001: 推理过程流式展示（AC-022），可选链兼容未注册 onReasoning 的旧调用方
      callbacks.onReasoning?.(data);
      break;
    case 'thought': {
      // ReAct: 思考内容，data 为 JSON（含 content 和 iteration）
      try {
        const parsed = JSON.parse(data);
        callbacks.onThought?.(parsed.content, parsed.iteration);
      } catch {
        // JSON 解析失败时静默跳过（容错）
      }
      break;
    }
    case 'action': {
      // ReAct: 工具调用，data 为 JSON（含 toolName、arguments、iteration）
      try {
        const parsed = JSON.parse(data);
        callbacks.onAction?.(parsed.toolName, parsed.arguments, parsed.iteration);
      } catch {
        // JSON 解析失败时静默跳过（容错）
      }
      break;
    }
    case 'observation': {
      // ReAct: 工具结果，data 为 JSON（含 result 和 iteration）
      try {
        const parsed = JSON.parse(data);
        callbacks.onObservation?.(parsed.result, parsed.iteration);
      } catch {
        // JSON 解析失败时静默跳过（容错）
      }
      break;
    }
    case 'final-answer': {
      // ReAct: 最终答案，data 为 JSON（含 iteration）
      try {
        const parsed = JSON.parse(data);
        callbacks.onFinalAnswer?.(parsed.iteration);
      } catch {
        // JSON 解析失败时静默跳过（容错）
      }
      break;
    }

    // ===== CR-002 新增：任务拆解事件（10 个，均用可选链 ?. 兼容未注册回调）=====

    case 'task_plan': {
      // 任务拆解规划完成，data 为 JSON（含 tasks 数组）
      try {
        const parsed = JSON.parse(data);
        callbacks.onTaskPlan?.(parsed.tasks);
      } catch {
        // JSON 解析失败时静默跳过（容错）
      }
      break;
    }
    case 'task_start': {
      // 子任务开始执行，data 为 JSON（含 index 和 title）
      try {
        const parsed = JSON.parse(data);
        callbacks.onTaskStart?.(parsed.index, parsed.title);
      } catch {
        // JSON 解析失败时静默跳过（容错）
      }
      break;
    }
    case 'task_token': {
      // 子任务执行内容片段，data 为 JSON（含 index 和 content）
      try {
        const parsed = JSON.parse(data);
        callbacks.onTaskToken?.(parsed.index, parsed.content);
      } catch {
        // JSON 解析失败时静默跳过（容错）
      }
      break;
    }
    case 'task_reasoning': {
      // 子任务推理片段，data 为 JSON（含 index 和 content）
      try {
        const parsed = JSON.parse(data);
        callbacks.onTaskReasoning?.(parsed.index, parsed.content);
      } catch {
        // JSON 解析失败时静默跳过（容错）
      }
      break;
    }
    case 'task_thought': {
      // 子任务 ReAct 思考，data 为 JSON（含 index、content 和 iteration）
      try {
        const parsed = JSON.parse(data);
        callbacks.onTaskThought?.(parsed.index, parsed.content, parsed.iteration);
      } catch {
        // JSON 解析失败时静默跳过（容错）
      }
      break;
    }
    case 'task_action': {
      // 子任务工具调用，data 为 JSON（含 index、toolName、args 和 iteration）
      // 注：字段名用 args 而非 arguments（arguments 是 JS 保留字）
      try {
        const parsed = JSON.parse(data);
        callbacks.onTaskAction?.(parsed.index, parsed.toolName, parsed.args, parsed.iteration);
      } catch {
        // JSON 解析失败时静默跳过（容错）
      }
      break;
    }
    case 'task_observation': {
      // 子任务工具结果，data 为 JSON（含 index、result 和 iteration）
      try {
        const parsed = JSON.parse(data);
        callbacks.onTaskObservation?.(parsed.index, parsed.result, parsed.iteration);
      } catch {
        // JSON 解析失败时静默跳过（容错）
      }
      break;
    }
    case 'task_complete': {
      // 子任务执行完成，data 为 JSON（含 index）
      try {
        const parsed = JSON.parse(data);
        callbacks.onTaskComplete?.(parsed.index);
      } catch {
        // JSON 解析失败时静默跳过（容错）
      }
      break;
    }
    case 'task_failed': {
      // 子任务执行失败，data 为 JSON（含 index 和 error）
      try {
        const parsed = JSON.parse(data);
        callbacks.onTaskFailed?.(parsed.index, parsed.error);
      } catch {
        // JSON 解析失败时静默跳过（容错）
      }
      break;
    }
    case 'task_cancelled': {
      // 子任务被取消，data 为 JSON（含 index）
      try {
        const parsed = JSON.parse(data);
        callbacks.onTaskCancelled?.(parsed.index);
      } catch {
        // JSON 解析失败时静默跳过（容错）
      }
      break;
    }

    case 'done':
      callbacks.onDone(Number(data));
      break;
    case 'error':
      // AC-012/AC-013: 错误提示
      callbacks.onError(data);
      break;
  }
}
