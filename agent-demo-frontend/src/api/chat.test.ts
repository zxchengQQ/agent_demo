// @vitest-environment node
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { streamChat } from './chat';
import type { StreamCallbacks } from '@/types';

/**
 * SSE 流式调用封装测试
 * 验证标准来源：T-08 验证标准
 * 关联 AC：AC-002、AC-011、AC-012、AC-013、AC-020
 */

/** 构造 mock SSE 响应（ReadableStream） */
function createSseResponse(chunks: string[], status = 200): Response {
  const encoder = new TextEncoder();
  const stream = new ReadableStream({
    start(controller) {
      chunks.forEach((chunk) => controller.enqueue(encoder.encode(chunk)));
      controller.close();
    },
  });
  return new Response(stream, {
    status,
    headers: { 'content-type': 'text/event-stream' },
  });
}

/** 构造回调对象 */
function createCallbacks(): StreamCallbacks & { calls: Record<string, string[]> } {
  const calls: Record<string, string[]> = { session: [], token: [], done: [], error: [] };
  return {
    onSession: (id: string) => calls.session.push(id),
    onToken: (token: string) => calls.token.push(token),
    onDone: () => {},
    onError: (msg: string) => calls.error.push(msg),
    calls,
  };
}

describe('SSE 流式调用', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('正确解析 SSE 事件序列（AC-002, AC-020）', async () => {
    const chunks = [
      'event: session\ndata: session-123\n\n',
      'event: token\ndata: 你\n\n',
      'event: token\ndata: 好\n\n',
      'event: done\ndata: 2340\n\n',
    ];
    global.fetch = vi.fn().mockResolvedValue(createSseResponse(chunks));

    const callbacks = createCallbacks();
    await streamChat('', '你好', callbacks, new AbortController().signal);

    expect(callbacks.calls.session).toEqual(['session-123']);
    expect(callbacks.calls.token).toEqual(['你', '好']);
    expect(callbacks.calls.error).toEqual([]);
  });

  it('error 事件触发 onError（AC-013）', async () => {
    const chunks = ['event: error\ndata: 生成回复时发生错误\n\n'];
    global.fetch = vi.fn().mockResolvedValue(createSseResponse(chunks));

    const callbacks = createCallbacks();
    await streamChat('', '你好', callbacks, new AbortController().signal);

    expect(callbacks.calls.error).toEqual(['生成回复时发生错误']);
  });

  it('非 200 状态码触发 onError（AC-013）', async () => {
    global.fetch = vi.fn().mockResolvedValue(createSseResponse([], 500));

    const callbacks = createCallbacks();
    await streamChat('', '你好', callbacks, new AbortController().signal);

    expect(callbacks.calls.error).toEqual(['服务暂时不可用，请稍后重试']);
  });

  it('fetch 网络错误触发 onError（AC-013）', async () => {
    global.fetch = vi.fn().mockRejectedValue(new Error('network error'));

    const callbacks = createCallbacks();
    await streamChat('', '你好', callbacks, new AbortController().signal);

    expect(callbacks.calls.error).toEqual(['服务暂时不可用，请稍后重试']);
  });

  it('跨 chunk 的 SSE 事件能正确拼接解析', async () => {
    // 一个事件被拆分到两个 chunk
    const chunks = [
      'event: ses', // 半行
      'sion\ndata: session-456\n\n', // 拼接后: event: session\ndata: session-456\n\n
      'event: token\ndata: 测', // 半行
      '试\n\n', // 拼接后: event: token\ndata: 测试\n\n
    ];
    global.fetch = vi.fn().mockResolvedValue(createSseResponse(chunks));

    const callbacks = createCallbacks();
    await streamChat('', '测试', callbacks, new AbortController().signal);

    expect(callbacks.calls.session).toEqual(['session-456']);
    expect(callbacks.calls.token).toEqual(['测试']);
  });
});
