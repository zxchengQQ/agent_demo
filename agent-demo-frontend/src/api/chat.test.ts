// @vitest-environment node
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { streamChat } from './chat';
import type { StreamCallbacks } from '@/types';

/**
 * SSE 流式调用封装测试
 * 验证标准来源：T-08 验证标准、Task-08 验证标准
 * 关联 AC：AC-002、AC-011、AC-012、AC-013、AC-020、AC-022、AC-001、AC-003、AC-005、AC-006
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
function createCallbacks(): StreamCallbacks & { calls: Record<string, unknown[]> } {
  const calls: Record<string, unknown[]> = {
    session: [], token: [], reasoning: [], done: [], error: [],
    taskPlan: [], taskStart: [], taskToken: [], taskReasoning: [],
    taskThought: [], taskAction: [], taskObservation: [],
    taskComplete: [], taskFailed: [], taskCancelled: [],
  };
  return {
    onSession: (id: string) => calls.session.push(id),
    onToken: (token: string) => calls.token.push(token),
    onReasoning: (reasoning: string) => calls.reasoning.push(reasoning),
    onDone: () => {},
    onError: (msg: string) => calls.error.push(msg),
    onTaskPlan: (tasks) => calls.taskPlan.push(tasks),
    onTaskStart: (index, title) => calls.taskStart.push([index, title]),
    onTaskToken: (index, content) => calls.taskToken.push([index, content]),
    onTaskReasoning: (index, content) => calls.taskReasoning.push([index, content]),
    onTaskThought: (index, content, iteration) => calls.taskThought.push([index, content, iteration]),
    onTaskAction: (index, toolName, args, iteration) => calls.taskAction.push([index, toolName, args, iteration]),
    onTaskObservation: (index, result, iteration) => calls.taskObservation.push([index, result, iteration]),
    onTaskComplete: (index) => calls.taskComplete.push(index),
    onTaskFailed: (index, error) => calls.taskFailed.push([index, error]),
    onTaskCancelled: (index) => calls.taskCancelled.push(index),
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
    await streamChat('', '你好', false, false, [], callbacks, new AbortController().signal);

    expect(callbacks.calls.session).toEqual(['session-123']);
    expect(callbacks.calls.token).toEqual(['你', '好']);
    expect(callbacks.calls.error).toEqual([]);
  });

  it('error 事件触发 onError（AC-013）', async () => {
    const chunks = ['event:error\ndata:生成回复时发生错误\n\n'];
    global.fetch = vi.fn().mockResolvedValue(createSseResponse(chunks));

    const callbacks = createCallbacks();
    await streamChat('', '你好', false, false, [], callbacks, new AbortController().signal);

    expect(callbacks.calls.error).toEqual(['生成回复时发生错误']);
  });

  it('非 200 状态码触发 onError（AC-013）', async () => {
    global.fetch = vi.fn().mockResolvedValue(createSseResponse([], 500));

    const callbacks = createCallbacks();
    await streamChat('', '你好', false, false, [], callbacks, new AbortController().signal);

    expect(callbacks.calls.error).toEqual(['服务暂时不可用，请稍后重试']);
  });

  it('fetch 网络错误触发 onError（AC-013）', async () => {
    global.fetch = vi.fn().mockRejectedValue(new Error('network error'));

    const callbacks = createCallbacks();
    await streamChat('', '你好', false, false, [], callbacks, new AbortController().signal);

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
    await streamChat('', '测试', false, false, [], callbacks, new AbortController().signal);

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
    await streamChat('', '你好', true, false, [], callbacks, new AbortController().signal);

    expect(callbacks.calls.reasoning).toEqual(['用户', '问的是']);
    expect(callbacks.calls.token).toEqual(['正式回复']);
  });

  it('请求体包含 enableThinking 字段（CR-001）', async () => {
    global.fetch = vi.fn().mockResolvedValue(createSseResponse([]));

    await streamChat('session-1', '你好', true, false, [], createCallbacks(), new AbortController().signal);

    const fetchCall = (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    const body = JSON.parse(fetchCall[1].body);
    expect(body.enableThinking).toBe(true);
    expect(body.sessionId).toBe('session-1');
    expect(body.message).toBe('你好');
  });

  it('enableThinking=false 时请求体对应字段为 false', async () => {
    global.fetch = vi.fn().mockResolvedValue(createSseResponse([]));

    await streamChat('', '你好', false, false, [], createCallbacks(), new AbortController().signal);

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
    await streamChat('', '测试', false, false, [], callbacks, new AbortController().signal);

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
    await streamChat('', '测试', false, false, [], callbacks, new AbortController().signal);

    expect(callbacks.calls.token).toEqual(['line1\nline2']);
  });

  // ========== CR-002 新增：task_* 事件解析 + enableTaskBreakdown 参数 ==========

  it('请求体包含 enableTaskBreakdown 字段（CR-002, AC-001）', async () => {
    global.fetch = vi.fn().mockResolvedValue(createSseResponse([]));

    await streamChat('session-1', '你好', false, true, [], createCallbacks(), new AbortController().signal);

    const fetchCall = (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    const body = JSON.parse(fetchCall[1].body);
    expect(body.enableTaskBreakdown).toBe(true);
  });

  it('enableTaskBreakdown=false 时请求体对应字段为 false', async () => {
    global.fetch = vi.fn().mockResolvedValue(createSseResponse([]));

    await streamChat('', '你好', false, false, [], createCallbacks(), new AbortController().signal);

    const fetchCall = (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    const body = JSON.parse(fetchCall[1].body);
    expect(body.enableTaskBreakdown).toBe(false);
  });

  it('task_plan 事件触发 onTaskPlan 回调（AC-001）', async () => {
    const chunks = [
      'event:task_plan\ndata:{"tasks":[{"index":1,"title":"分析"},{"index":2,"title":"调研"}]}\n\n',
      'event:done\ndata:1000\n\n',
    ];
    global.fetch = vi.fn().mockResolvedValue(createSseResponse(chunks));

    const callbacks = createCallbacks();
    await streamChat('', '复杂任务', false, true, [], callbacks, new AbortController().signal);

    expect(callbacks.calls.taskPlan).toHaveLength(1);
    expect(callbacks.calls.taskPlan[0]).toEqual([
      { index: 1, title: '分析' },
      { index: 2, title: '调研' },
    ]);
  });

  it('task_start 事件触发 onTaskStart 回调（AC-003）', async () => {
    const chunks = [
      'event:task_start\ndata:{"index":1,"title":"分析需求"}\n\n',
      'event:done\ndata:500\n\n',
    ];
    global.fetch = vi.fn().mockResolvedValue(createSseResponse(chunks));

    const callbacks = createCallbacks();
    await streamChat('', '任务', false, true, [], callbacks, new AbortController().signal);

    expect(callbacks.calls.taskStart).toEqual([[1, '分析需求']]);
  });

  it('task_token 事件触发 onTaskToken 回调（AC-005）', async () => {
    const chunks = [
      'event:task_token\ndata:{"index":1,"content":"首先"}\n\n',
      'event:task_token\ndata:{"index":1,"content":"分析"}\n\n',
      'event:done\ndata:500\n\n',
    ];
    global.fetch = vi.fn().mockResolvedValue(createSseResponse(chunks));

    const callbacks = createCallbacks();
    await streamChat('', '任务', false, true, [], callbacks, new AbortController().signal);

    expect(callbacks.calls.taskToken).toEqual([[1, '首先'], [1, '分析']]);
  });

  it('task_reasoning 事件触发 onTaskReasoning 回调（AC-011）', async () => {
    const chunks = [
      'event:task_reasoning\ndata:{"index":1,"content":"让我思考"}\n\n',
      'event:done\ndata:500\n\n',
    ];
    global.fetch = vi.fn().mockResolvedValue(createSseResponse(chunks));

    const callbacks = createCallbacks();
    await streamChat('', '任务', true, true, [], callbacks, new AbortController().signal);

    expect(callbacks.calls.taskReasoning).toEqual([[1, '让我思考']]);
  });

  it('task_thought 事件触发 onTaskThought 回调（AC-005）', async () => {
    const chunks = [
      'event:task_thought\ndata:{"index":1,"content":"需要查询","iteration":1}\n\n',
      'event:done\ndata:500\n\n',
    ];
    global.fetch = vi.fn().mockResolvedValue(createSseResponse(chunks));

    const callbacks = createCallbacks();
    await streamChat('', '任务', false, true, [], callbacks, new AbortController().signal);

    expect(callbacks.calls.taskThought).toEqual([[1, '需要查询', 1]]);
  });

  it('task_action 事件触发 onTaskAction 回调（AC-005）', async () => {
    const chunks = [
      'event:task_action\ndata:{"index":1,"toolName":"http","args":"{\\"url\\":\\"...\\"}","iteration":1}\n\n',
      'event:done\ndata:500\n\n',
    ];
    global.fetch = vi.fn().mockResolvedValue(createSseResponse(chunks));

    const callbacks = createCallbacks();
    await streamChat('', '任务', false, true, [], callbacks, new AbortController().signal);

    expect(callbacks.calls.taskAction).toEqual([[1, 'http', '{"url":"..."}', 1]]);
  });

  it('task_observation 事件触发 onTaskObservation 回调（AC-005）', async () => {
    const chunks = [
      'event:task_observation\ndata:{"index":1,"result":"查询结果","iteration":1}\n\n',
      'event:done\ndata:500\n\n',
    ];
    global.fetch = vi.fn().mockResolvedValue(createSseResponse(chunks));

    const callbacks = createCallbacks();
    await streamChat('', '任务', false, true, [], callbacks, new AbortController().signal);

    expect(callbacks.calls.taskObservation).toEqual([[1, '查询结果', 1]]);
  });

  it('task_complete 事件触发 onTaskComplete 回调（AC-003）', async () => {
    const chunks = [
      'event:task_complete\ndata:{"index":1}\n\n',
      'event:done\ndata:500\n\n',
    ];
    global.fetch = vi.fn().mockResolvedValue(createSseResponse(chunks));

    const callbacks = createCallbacks();
    await streamChat('', '任务', false, true, [], callbacks, new AbortController().signal);

    expect(callbacks.calls.taskComplete).toEqual([1]);
  });

  it('task_failed 事件触发 onTaskFailed 回调（AC-006）', async () => {
    const chunks = [
      'event:task_failed\ndata:{"index":2,"error":"超时"}\n\n',
      'event:done\ndata:500\n\n',
    ];
    global.fetch = vi.fn().mockResolvedValue(createSseResponse(chunks));

    const callbacks = createCallbacks();
    await streamChat('', '任务', false, true, [], callbacks, new AbortController().signal);

    expect(callbacks.calls.taskFailed).toEqual([[2, '超时']]);
  });

  it('task_cancelled 事件触发 onTaskCancelled 回调（AC-006, AC-007）', async () => {
    const chunks = [
      'event:task_cancelled\ndata:{"index":3}\n\n',
      'event:done\ndata:500\n\n',
    ];
    global.fetch = vi.fn().mockResolvedValue(createSseResponse(chunks));

    const callbacks = createCallbacks();
    await streamChat('', '任务', false, true, [], callbacks, new AbortController().signal);

    expect(callbacks.calls.taskCancelled).toEqual([3]);
  });

  it('task_* 事件 JSON 解析失败时静默跳过（容错）', async () => {
    const chunks = [
      'event:task_plan\ndata:invalid json\n\n',
      'event:task_start\ndata:{bad json}\n\n',
      'event:done\ndata:500\n\n',
    ];
    global.fetch = vi.fn().mockResolvedValue(createSseResponse(chunks));

    const callbacks = createCallbacks();
    await streamChat('', '任务', false, true, [], callbacks, new AbortController().signal);

    // 解析失败不触发回调，也不抛异常
    expect(callbacks.calls.taskPlan).toHaveLength(0);
    expect(callbacks.calls.taskStart).toHaveLength(0);
    expect(callbacks.calls.error).toEqual([]);
  });

  it('未注册 task 回调时不报错（向前兼容）', async () => {
    const chunks = [
      'event:task_plan\ndata:{"tasks":[{"index":1,"title":"分析"}]}\n\n',
      'event:task_complete\ndata:{"index":1}\n\n',
      'event:done\ndata:500\n\n',
    ];
    global.fetch = vi.fn().mockResolvedValue(createSseResponse(chunks));

    // 只注册必需回调，不注册 task 回调
    const minimalCallbacks: StreamCallbacks = {
      onSession: () => {},
      onToken: () => {},
      onDone: () => {},
      onError: () => {},
    };

    await streamChat('', '任务', false, true, [], minimalCallbacks, new AbortController().signal);
    // 不抛异常即为通过
  });

  it('knowledgeBases 空数组出现在请求体中（AC-012 自动模式）', async () => {
    const chunks = ['event:done\ndata:100\n\n'];
    global.fetch = vi.fn().mockResolvedValue(createSseResponse(chunks));
    const callbacks = createCallbacks();
    await streamChat('', '你好', false, false, [], callbacks, new AbortController().signal);
    const callArgs = (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    const body = JSON.parse(callArgs[1].body);
    expect(body.knowledgeBases).toEqual([]);
  });

  it('knowledgeBases 非空数组出现在请求体中（AC-013 手动指定）', async () => {
    const chunks = ['event:done\ndata:100\n\n'];
    global.fetch = vi.fn().mockResolvedValue(createSseResponse(chunks));
    const callbacks = createCallbacks();
    await streamChat('', '你好', false, false, ['产品手册'], callbacks, new AbortController().signal);
    const callArgs = (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    const body = JSON.parse(callArgs[1].body);
    expect(body.knowledgeBases).toEqual(['产品手册']);
  });
});
