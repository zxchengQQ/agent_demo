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
 * @param callbacks SSE 事件回调
 * @param signal AbortController.signal，用于停止生成（AC-011）
 */
export async function streamChat(
  sessionId: string,
  message: string,
  callbacks: StreamCallbacks,
  signal: AbortSignal,
): Promise<void> {
  let response: Response;
  try {
    response = await fetch(`${API_BASE}/chat/stream`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sessionId, message }),
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

  // 解析 SSE 流
  const reader = response.body!.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let currentEvent = '';

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      // 累积到 buffer，按行处理（保留最后不完整行）
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() || '';

      for (const line of lines) {
        if (line.startsWith('event:')) {
          currentEvent = line.slice(6).trim();
        } else if (line.startsWith('data:')) {
          const data = line.slice(5).trim();
          handleSseEvent(currentEvent, data, callbacks);
          currentEvent = '';
        }
      }
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
    case 'done':
      callbacks.onDone(Number(data));
      break;
    case 'error':
      // AC-012/AC-013: 错误提示
      callbacks.onError(data);
      break;
  }
}
