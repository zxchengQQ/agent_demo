import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import LoopExecuteView from '../LoopExecuteView.vue';
import { streamExecute, streamResume, terminate } from '@/api/workflow';
import type { WorkflowTemplateDetail } from '@/types';

/**
 * LoopExecuteView 组件测试（Task-27，循环模式执行视图）
 * 验证标准：
 * 1. 收到 loop_iteration 事件后渲染新一轮时间线节点（含轮次编号）
 * 2. 每轮节点内评分 Agent 与修订 Agent 输出左右对比展示
 * 3. 当前执行轮次高亮，历史轮次灰色
 * 4. 退出条件达标（或达到 maxIterations）时显示退出提示
 * 5. 布局与 SequentialExecuteView/ParallelExecuteView 有显著差异（断言时间线元素存在）
 */

vi.mock('@/api/workflow', () => ({
  streamExecute: vi.fn(),
  streamResume: vi.fn(),
  terminate: vi.fn(),
}));

/** 构造 LOOP 模式模板详情（评分 Agent + 修订 Agent 两步循环） */
function makeDetail(overrides: Partial<WorkflowTemplateDetail> = {}): WorkflowTemplateDetail {
  const agent = (name: string) => ({ name, description: 'd', modelId: null, tools: [] });
  return {
    id: 'tpl-loop',
    name: '质量评分-修订循环',
    description: '循环编排',
    mode: 'LOOP',
    maxRetries: 3,
    agents: [],
    parameters: [{ name: 'draft', type: 'string', required: true, description: '初稿' }],
    loop: {
      maxIterations: 3,
      exitConditionDescription: '评分≥90退出',
      agents: [agent('评分 Agent'), agent('修订 Agent')],
    },
    ...overrides,
  };
}

beforeEach(() => {
  vi.restoreAllMocks();
});

describe('LoopExecuteView 时间线与布局', () => {
  it('接收 LOOP 模板后渲染垂直时间线容器', () => {
    const wrapper = mount(LoopExecuteView, { props: { template: makeDetail() } });
    expect(wrapper.find('.loop-timeline').exists()).toBe(true);
  });

  it('布局与 SequentialExecuteView/ParallelExecuteView 有显著差异（时间线元素存在）', () => {
    const wrapper = mount(LoopExecuteView, { props: { template: makeDetail() } });
    // 循环视图标志性布局元素：垂直时间线
    expect(wrapper.find('.loop-timeline').exists()).toBe(true);
    // 不存在串行视图的流水线进度条
    expect(wrapper.find('.pipeline-bar').exists()).toBe(false);
    expect(wrapper.find('.pipeline-node').exists()).toBe(false);
    // 不存在并行视图的多列 Grid
    expect(wrapper.find('.parallel-grid').exists()).toBe(false);
    expect(wrapper.find('.group-column').exists()).toBe(false);
  });

  it('参数表单按模板定义动态生成', () => {
    const wrapper = mount(LoopExecuteView, { props: { template: makeDetail() } });
    const inputs = wrapper.findAll('.param-input');
    expect(inputs).toHaveLength(1);
    expect(wrapper.text()).toContain('初稿');
  });
});

describe('LoopExecuteView 循环时间线节点', () => {
  it('收到 loop_iteration 事件后渲染新一轮时间线节点（含轮次编号）', async () => {
    (streamExecute as ReturnType<typeof vi.fn>).mockImplementation(async (_id, _p, _m, callbacks) => {
      callbacks.onWorkflowStart({ executionId: 'e1', templateName: '质量评分-修订循环', mode: 'LOOP', agentCount: 2 });
      callbacks.onLoopIteration({ iteration: 1, maxIterations: 3, agentCount: 2 });
    });
    const wrapper = mount(LoopExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.param-input').setValue('初稿内容');
    await wrapper.find('.btn-execute').trigger('click');
    await vi.waitFor(() => {
      // 渲染 1 个时间线节点
      expect(wrapper.findAll('.timeline-node')).toHaveLength(1);
      // 节点含轮次编号
      expect(wrapper.find('.iteration-badge').text()).toContain('1');
    });
  });

  it('多轮 loop_iteration 渲染多个时间线节点（含各自轮次编号）', async () => {
    (streamExecute as ReturnType<typeof vi.fn>).mockImplementation(async (_id, _p, _m, callbacks) => {
      callbacks.onWorkflowStart({ executionId: 'e1', templateName: '质量评分-修订循环', mode: 'LOOP', agentCount: 2 });
      callbacks.onLoopIteration({ iteration: 1, maxIterations: 3, agentCount: 2 });
      callbacks.onLoopIteration({ iteration: 2, maxIterations: 3, agentCount: 2 });
    });
    const wrapper = mount(LoopExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.param-input').setValue('初稿');
    await wrapper.find('.btn-execute').trigger('click');
    await vi.waitFor(() => {
      const nodes = wrapper.findAll('.timeline-node');
      expect(nodes).toHaveLength(2);
      expect(nodes[0].find('.iteration-badge').text()).toContain('1');
      expect(nodes[1].find('.iteration-badge').text()).toContain('2');
    });
  });

  it('每轮节点内评分 Agent 与修订 Agent 输出左右对比展示', async () => {
    (streamExecute as ReturnType<typeof vi.fn>).mockImplementation(async (_id, _p, _m, callbacks) => {
      callbacks.onWorkflowStart({ executionId: 'e1', templateName: '质量评分-修订循环', mode: 'LOOP', agentCount: 2 });
      callbacks.onLoopIteration({ iteration: 1, maxIterations: 3, agentCount: 2 });
      // 评分 Agent（agentIndex=0）
      callbacks.onStepStart({ agentIndex: 0, agentName: '评分 Agent', totalAgents: 2 });
      callbacks.onToken({ agentIndex: 0, content: '评分85' });
      callbacks.onStepComplete({ agentIndex: 0, agentName: '评分 Agent', durationMs: 100, outputLength: 5 });
      // 修订 Agent（agentIndex=1）
      callbacks.onStepStart({ agentIndex: 1, agentName: '修订 Agent', totalAgents: 2 });
      callbacks.onToken({ agentIndex: 1, content: '修订内容' });
      callbacks.onStepComplete({ agentIndex: 1, agentName: '修订 Agent', durationMs: 80, outputLength: 4 });
    });
    const wrapper = mount(LoopExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.param-input').setValue('初稿');
    await wrapper.find('.btn-execute').trigger('click');
    await vi.waitFor(() => {
      const node = wrapper.find('.timeline-node');
      // 左侧评分输出
      expect(node.find('.compare-left').text()).toContain('评分85');
      expect(node.find('.compare-left').text()).toContain('评分 Agent');
      // 右侧修订输出
      expect(node.find('.compare-right').text()).toContain('修订内容');
      expect(node.find('.compare-right').text()).toContain('修订 Agent');
    });
  });

  it('当前执行轮次高亮，历史轮次灰色', async () => {
    (streamExecute as ReturnType<typeof vi.fn>).mockImplementation(async (_id, _p, _m, callbacks) => {
      callbacks.onWorkflowStart({ executionId: 'e1', templateName: '质量评分-修订循环', mode: 'LOOP', agentCount: 2 });
      // 第 1 轮
      callbacks.onLoopIteration({ iteration: 1, maxIterations: 3, agentCount: 2 });
      callbacks.onStepStart({ agentIndex: 0, agentName: '评分 Agent', totalAgents: 2 });
      callbacks.onStepComplete({ agentIndex: 0, agentName: '评分 Agent', durationMs: 100, outputLength: 5 });
      callbacks.onStepStart({ agentIndex: 1, agentName: '修订 Agent', totalAgents: 2 });
      callbacks.onStepComplete({ agentIndex: 1, agentName: '修订 Agent', durationMs: 80, outputLength: 4 });
      // 第 2 轮（当前进行中）
      callbacks.onLoopIteration({ iteration: 2, maxIterations: 3, agentCount: 2 });
      callbacks.onStepStart({ agentIndex: 2, agentName: '评分 Agent', totalAgents: 2 });
    });
    const wrapper = mount(LoopExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.param-input').setValue('初稿');
    await wrapper.find('.btn-execute').trigger('click');
    await vi.waitFor(() => {
      const nodes = wrapper.findAll('.timeline-node');
      expect(nodes).toHaveLength(2);
      // 第 1 轮历史：灰色，非当前
      expect(nodes[0].classes()).toContain('history');
      expect(nodes[0].classes()).not.toContain('current');
      // 第 2 轮当前：高亮
      expect(nodes[1].classes()).toContain('current');
      expect(nodes[1].classes()).not.toContain('history');
    });
  });
});

describe('LoopExecuteView 退出条件与执行流程', () => {
  it('退出条件达标（或达到 maxIterations）时显示退出提示', async () => {
    (streamExecute as ReturnType<typeof vi.fn>).mockImplementation(async (_id, _p, _m, callbacks) => {
      callbacks.onWorkflowStart({ executionId: 'e1', templateName: '质量评分-修订循环', mode: 'LOOP', agentCount: 2 });
      callbacks.onLoopIteration({ iteration: 1, maxIterations: 3, agentCount: 2 });
      callbacks.onStepStart({ agentIndex: 0, agentName: '评分 Agent', totalAgents: 2 });
      callbacks.onStepComplete({ agentIndex: 0, agentName: '评分 Agent', durationMs: 100, outputLength: 5 });
      callbacks.onWorkflowComplete({
        executionId: 'e1',
        finalResult: '最终结果',
        mode: 'LOOP',
        totalDurationMs: 300,
        exitReason: '评分达标退出',
      });
    });
    const wrapper = mount(LoopExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.param-input').setValue('初稿');
    await wrapper.find('.btn-execute').trigger('click');
    await vi.waitFor(() => {
      // 退出提示存在
      expect(wrapper.find('.exit-notice').exists()).toBe(true);
      // 显示退出原因
      expect(wrapper.text()).toContain('评分达标退出');
    });
    // 状态 COMPLETED
    expect(wrapper.text()).toContain('COMPLETED');
  });

  it('必填参数为空时点击执行不发起请求并提示', async () => {
    const wrapper = mount(LoopExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.btn-execute').trigger('click');
    expect(streamExecute).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('请填写必填参数');
  });

  it('填写参数后点击执行调用 streamExecute', async () => {
    (streamExecute as ReturnType<typeof vi.fn>).mockResolvedValue(undefined);
    const wrapper = mount(LoopExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.param-input').setValue('初稿内容');
    await wrapper.find('.btn-execute').trigger('click');
    expect(streamExecute).toHaveBeenCalledTimes(1);
    expect(streamExecute).toHaveBeenCalledWith('tpl-loop', { draft: '初稿内容' }, '', expect.any(Object), expect.any(AbortSignal));
  });

  it('点击"停止"调用 abort（AC-022）', async () => {
    (streamExecute as ReturnType<typeof vi.fn>).mockImplementation(async () => {
      // 永不完成，保持执行中
    });
    const wrapper = mount(LoopExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.param-input').setValue('初稿');
    await wrapper.find('.btn-execute').trigger('click');
    await vi.waitFor(() => {
      expect(wrapper.find('.btn-stop').exists()).toBe(true);
    });
    const signal = (streamExecute as ReturnType<typeof vi.fn>).mock.calls[0][4] as AbortSignal;
    await wrapper.find('.btn-stop').trigger('click');
    expect(signal.aborted).toBe(true);
  });
});

describe('LoopExecuteView 暂停与恢复', () => {
  /** 挂载并执行至暂停：第 1 轮评分完成 + 修订失败暂停 */
  async function mountUntilPaused() {
    (streamExecute as ReturnType<typeof vi.fn>).mockImplementation(async (_id, _p, _m, callbacks) => {
      callbacks.onWorkflowStart({ executionId: 'exec-1', templateName: '质量评分-修订循环', mode: 'LOOP', agentCount: 2 });
      callbacks.onLoopIteration({ iteration: 1, maxIterations: 3, agentCount: 2 });
      callbacks.onStepStart({ agentIndex: 0, agentName: '评分 Agent', totalAgents: 2 });
      callbacks.onStepComplete({ agentIndex: 0, agentName: '评分 Agent', durationMs: 100, outputLength: 5 });
      callbacks.onWorkflowPaused({
        executionId: 'exec-1',
        failedAgent: '修订 Agent',
        failedIndex: 1,
        error: 'Agent 执行失败: 修订 Agent: 超时',
        resumable: true,
      });
    });
    const wrapper = mount(LoopExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.param-input').setValue('初稿');
    await wrapper.find('.btn-execute').trigger('click');
    await vi.waitFor(() => {
      expect(wrapper.find('.paused-banner').exists()).toBe(true);
    });
    return wrapper;
  }

  it('暂停时显示恢复/终止按钮', async () => {
    const wrapper = await mountUntilPaused();
    expect(wrapper.find('.paused-banner').text()).toContain('已暂停');
    expect(wrapper.find('.paused-banner').text()).toContain('修订 Agent');
    expect(wrapper.find('.btn-resume').exists()).toBe(true);
    expect(wrapper.find('.btn-terminate').exists()).toBe(true);
  });

  it('点击"恢复执行"调用 streamResume', async () => {
    const wrapper = await mountUntilPaused();
    (streamResume as ReturnType<typeof vi.fn>).mockResolvedValue(undefined);
    await wrapper.find('.btn-resume').trigger('click');
    expect(streamResume).toHaveBeenCalledTimes(1);
    expect(streamResume).toHaveBeenCalledWith('exec-1', expect.any(Object), expect.any(AbortSignal));
  });

  it('点击"终止"调用 terminate API 且暂停态退出', async () => {
    const wrapper = await mountUntilPaused();
    (terminate as ReturnType<typeof vi.fn>).mockResolvedValue(undefined);
    await wrapper.find('.btn-terminate').trigger('click');
    await vi.waitFor(() => {
      expect(terminate).toHaveBeenCalledWith('exec-1');
    });
    await vi.waitFor(() => {
      expect(wrapper.find('.paused-banner').exists()).toBe(false);
      expect(wrapper.text()).toContain('TERMINATED');
    });
  });

  it('传入 resumeExecutionId prop 时挂载自动发起 streamResume', async () => {
    (streamResume as ReturnType<typeof vi.fn>).mockResolvedValue(undefined);
    mount(LoopExecuteView, {
      props: { template: makeDetail(), resumeExecutionId: 'exec-9' },
    });
    await vi.waitFor(() => {
      expect(streamResume).toHaveBeenCalledTimes(1);
    });
    expect(streamResume).toHaveBeenCalledWith('exec-9', expect.any(Object), expect.any(AbortSignal));
  });
});
