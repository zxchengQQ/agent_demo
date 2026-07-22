// @vitest-environment node
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { streamChat } from './chat';
import type { StreamCallbacks } from '@/types';

/**
 * SSE 流式调用封装测试
 * 验证标准来源：T-08 验证标准、T-23 验证标准
 * 关联 AC：AC-002、AC-011、AC-012、AC-013、AC-020、AC-022
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
  const calls: Record<string, string[]> = { session: [], token: [], reasoning: [], done: [], error: [] };
  return {
    onSession: (id: string) => calls.session.push(id),
    onToken: (token: string) => calls.token.push(token),
    onReasoning: (reasoning: string) => calls.reasoning.push(reasoning),
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
    // Spring SseEmitter 输出格式：data: 后不加空格
    const chunks = [
      'event:session\ndata:session-123\n\n',
      'event:token\ndata:你\n\n',
      'event:token\ndata:好\n\n',
      'event:done\ndata:2340\n\n',
    ];
    global.fetch = vi.fn().mockResolvedValue(createSseResponse(chunks));

    const callbacks = createCallbacks();
    await streamChat('', '你好', false, callbacks, new AbortController().signal);

    expect(callbacks.calls.session).toEqual(['session-123']);
    expect(callbacks.calls.token).toEqual(['你', '好']);
    expect(callbacks.calls.error).toEqual([]);
  });

  it('error 事件触发 onError（AC-013）', async () => {
    const chunks = ['event:error\ndata:生成回复时发生错误\n\n'];
    global.fetch = vi.fn().mockResolvedValue(createSseResponse(chunks));

    const callbacks = createCallbacks();
    await streamChat('', '你好', false, callbacks, new AbortController().signal);

    expect(callbacks.calls.error).toEqual(['生成回复时发生错误']);
  });

  it('非 200 状态码触发 onError（AC-013）', async () => {
    global.fetch = vi.fn().mockResolvedValue(createSseResponse([], 500));

    const callbacks = createCallbacks();
    await streamChat('', '你好', false, callbacks, new AbortController().signal);

    expect(callbacks.calls.error).toEqual(['服务暂时不可用，请稍后重试']);
  });

  it('fetch 网络错误触发 onError（AC-013）', async () => {
    global.fetch = vi.fn().mockRejectedValue(new Error('network error'));

    const callbacks = createCallbacks();
    await streamChat('', '你好', false, callbacks, new AbortController().signal);

    expect(callbacks.calls.error).toEqual(['服务暂时不可用，请稍后重试']);
  });

  it('跨 chunk 的 SSE 事件能正确拼接解析', async () => {
    // 一个事件被拆分到两个 chunk
    const chunks = [
      'event:ses', // 半行
      'sion\ndata:session-456\n\n', // 拼接后: event:session\ndata:session-456\n\n
      'event:token\ndata:测', // 半行
      '试\n\n', // 拼接后: event:token\ndata:测试\n\n
    ];
    global.fetch = vi.fn().mockResolvedValue(createSseResponse(chunks));

    const callbacks = createCallbacks();
    await streamChat('', '测试', false, callbacks, new AbortController().signal);

    expect(callbacks.calls.session).toEqual(['session-456']);
    expect(callbacks.calls.token).toEqual(['测试']);
  });

  // ========== CR-001 新增：reasoning 事件 + enableThinking 参数 ==========

  it('reasoning 事件触发 onReasoning 回调（AC-022, CR-001）', async () => {
    const chunks = [
      'event:reasoning\ndata:用户\n\n',
      'event:reasoning\ndata:问的是\n\n',
      'event:token\ndata:正式回复\n\n',
      'event:done\ndata:1000\n\n',
    ];
    global.fetch = vi.fn().mockResolvedValue(createSseResponse(chunks));

    const callbacks = createCallbacks();
    await streamChat('', '你好', true, callbacks, new AbortController().signal);

    expect(callbacks.calls.reasoning).toEqual(['用户', '问的是']);
    expect(callbacks.calls.token).toEqual(['正式回复']);
  });

  it('请求体包含 enableThinking 字段（CR-001）', async () => {
    global.fetch = vi.fn().mockResolvedValue(createSseResponse([]));

    await streamChat('session-1', '你好', true, createCallbacks(), new AbortController().signal);

    const fetchCall = (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    const body = JSON.parse(fetchCall[1].body);
    expect(body.enableThinking).toBe(true);
    expect(body.sessionId).toBe('session-1');
    expect(body.message).toBe('你好');
  });

  it('enableThinking=false 时请求体对应字段为 false', async () => {
    global.fetch = vi.fn().mockResolvedValue(createSseResponse([]));

    await streamChat('', '你好', false, createCallbacks(), new AbortController().signal);

    const fetchCall = (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    const body = JSON.parse(fetchCall[1].body);
    expect(body.enableThinking).toBe(false);
  });

  // ========== AC-023 回归修复：空格和多行内容保留 ==========

  it('token 中的空格被完整保留（AC-023 回归修复）', async () => {
    // 模拟 Markdown 标题 "# Python" 被拆分为 "#"、" "、"Python" 三个 token
    const chunks = [
      'event:token\ndata:# \n\n',
      'event:token\ndata:Python\n\n',
      'event:done\ndata:500\n\n',
    ];
    global.fetch = vi.fn().mockResolvedValue(createSseResponse(chunks));

    const callbacks = createCallbacks();
    await streamChat('', '测试', false, callbacks, new AbortController().signal);

    expect(callbacks.calls.token).toEqual(['# ', 'Python']);
  });

  it('多行 data: 按规范拼接为含换行符的内容', async () => {
    // 模拟 token 包含换行符，Spring 会拆分为多行 data:
    const chunks = [
      'event:token\ndata:line1\ndata:line2\n\n',
      'event:done\ndata:100\n\n',
    ];
    global.fetch = vi.fn().mockResolvedValue(createSseResponse(chunks));

    const callbacks = createCallbacks();
    await streamChat('', '测试', false, callbacks, new AbortController().signal);

    expect(callbacks.calls.token).toEqual(['line1\nline2']);
  });
});
