import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import SequentialExecuteView from '../SequentialExecuteView.vue';
import { streamExecute, streamResume, terminate } from '@/api/workflow';
import type { WorkflowTemplateDetail } from '@/types';

/**
 * SequentialExecuteView 组件测试（Task-25，串行模式执行视图）
 * 验证标准：
 * 1. 接收 SEQUENTIAL 模板后渲染顶部水平流水线进度条（含 Agent 节点）
 * 2. 执行中当前步骤节点高亮，已完成步骤打勾
 * 3. Agent 输出在折叠面板中实时追加（token 流式）
 * 4. 暂停时失败步骤红色高亮，显示恢复/终止按钮
 * 5. 参数表单按模板定义动态生成
 * 6. 布局与 ParallelExecuteView 有显著差异（断言流水线进度条元素存在）
 */

vi.mock('@/api/workflow', () => ({
  streamExecute: vi.fn(),
  streamResume: vi.fn(),
  terminate: vi.fn(),
}));

/** 构造 SEQUENTIAL 模式模板详情（3 个 Agent 串行） */
function makeDetail(overrides: Partial<WorkflowTemplateDetail> = {}): WorkflowTemplateDetail {
  const agent = (name: string) => ({ name, description: 'd', modelId: null, tools: [] });
  return {
    id: 'tpl-seq',
    name: '串行示例',
    description: '串行编排',
    mode: 'SEQUENTIAL',
    maxRetries: 3,
    agents: [agent('调研 Agent'), agent('分析 Agent'), agent('总结 Agent')],
    parameters: [{ name: 'topic', type: 'string', required: true, description: '主题' }],
    ...overrides,
  };
}

beforeEach(() => {
  vi.restoreAllMocks();
});

describe('SequentialExecuteView 流水线进度条与布局', () => {
  it('接收 SEQUENTIAL 模板后渲染流水线进度条（含 Agent 节点）', () => {
    const wrapper = mount(SequentialExecuteView, { props: { template: makeDetail() } });
    // 流水线进度条存在
    expect(wrapper.find('.pipeline-bar').exists()).toBe(true);
    // 3 个 Agent 节点
    const nodes = wrapper.findAll('.pipeline-node');
    expect(nodes).toHaveLength(3);
    // 节点包含 Agent 名
    expect(wrapper.find('.pipeline-bar').text()).toContain('调研 Agent');
    expect(wrapper.find('.pipeline-bar').text()).toContain('分析 Agent');
    expect(wrapper.find('.pipeline-bar').text()).toContain('总结 Agent');
  });

  it('布局与 ParallelExecuteView 有显著差异（流水线进度条元素存在）', () => {
    const wrapper = mount(SequentialExecuteView, { props: { template: makeDetail() } });
    // 流水线进度条是串行视图的标志性布局元素，区别于并行视图
    expect(wrapper.find('.pipeline-bar').exists()).toBe(true);
    // 节点间有箭头连接（串行特征）
    expect(wrapper.findAll('.pipeline-arrow').length).toBeGreaterThan(0);
  });

  it('参数表单按模板定义动态生成', () => {
    const wrapper = mount(SequentialExecuteView, { props: { template: makeDetail() } });
    const inputs = wrapper.findAll('.param-input');
    expect(inputs).toHaveLength(1);
    expect(wrapper.text()).toContain('主题');
  });
});

describe('SequentialExecuteView 执行流程', () => {
  it('必填参数为空时点击执行不发起请求并提示', async () => {
    const wrapper = mount(SequentialExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.btn-execute').trigger('click');
    expect(streamExecute).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('请填写必填参数');
  });

  it('填写参数后点击执行调用 streamExecute', async () => {
    (streamExecute as ReturnType<typeof vi.fn>).mockResolvedValue(undefined);
    const wrapper = mount(SequentialExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.param-input').setValue('AI 主题');
    await wrapper.find('.btn-execute').trigger('click');
    expect(streamExecute).toHaveBeenCalledTimes(1);
    expect(streamExecute).toHaveBeenCalledWith('tpl-seq', { topic: 'AI 主题' }, '', expect.any(Object), expect.any(AbortSignal));
  });

  it('执行中当前步骤节点高亮，已完成步骤打勾', async () => {
    (streamExecute as ReturnType<typeof vi.fn>).mockImplementation(async (_id, _p, _m, callbacks) => {
      callbacks.onWorkflowStart({ executionId: 'e1', templateName: '串行示例', mode: 'SEQUENTIAL', agentCount: 3 });
      // 步骤 0 开始 -> 完成
      callbacks.onStepStart({ agentIndex: 0, agentName: '调研 Agent', totalAgents: 3 });
      callbacks.onStepComplete({ agentIndex: 0, agentName: '调研 Agent', durationMs: 100, outputLength: 5 });
      // 步骤 1 开始（当前进行中）
      callbacks.onStepStart({ agentIndex: 1, agentName: '分析 Agent', totalAgents: 3 });
    });
    const wrapper = mount(SequentialExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.param-input').setValue('主题');
    await wrapper.find('.btn-execute').trigger('click');
    await vi.waitFor(() => {
      const nodes = wrapper.findAll('.pipeline-node');
      // 步骤 0 已完成：打勾 + completed 样式
      expect(nodes[0].classes()).toContain('pn-completed');
      expect(nodes[0].text()).toContain('✓');
      // 步骤 1 进行中：高亮 + running 样式
      expect(nodes[1].classes()).toContain('pn-running');
      // 步骤 2 仍待执行
      expect(nodes[2].classes()).toContain('pn-pending');
    });
  });

  it('Agent 输出在折叠面板中实时追加（token 流式）', async () => {
    (streamExecute as ReturnType<typeof vi.fn>).mockImplementation(async (_id, _p, _m, callbacks) => {
      callbacks.onWorkflowStart({ executionId: 'e1', templateName: '串行示例', mode: 'SEQUENTIAL', agentCount: 3 });
      callbacks.onStepStart({ agentIndex: 0, agentName: '调研 Agent', totalAgents: 3 });
      callbacks.onToken({ agentIndex: 0, content: '调研' });
      callbacks.onToken({ agentIndex: 0, content: '结果' });
      callbacks.onStepComplete({ agentIndex: 0, agentName: '调研 Agent', durationMs: 100, outputLength: 4 });
    });
    const wrapper = mount(SequentialExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.param-input').setValue('主题');
    await wrapper.find('.btn-execute').trigger('click');
    await vi.waitFor(() => {
      // token 流式追加到折叠面板输出区
      expect(wrapper.find('.panel-output').text()).toContain('调研结果');
    });
  });

  it('点击"停止"调用 abort（AC-022）', async () => {
    (streamExecute as ReturnType<typeof vi.fn>).mockImplementation(async () => {
      // 永不完成，保持执行中
    });
    const wrapper = mount(SequentialExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.param-input').setValue('主题');
    await wrapper.find('.btn-execute').trigger('click');
    await vi.waitFor(() => {
      expect(wrapper.find('.btn-stop').exists()).toBe(true);
    });
    const signal = (streamExecute as ReturnType<typeof vi.fn>).mock.calls[0][4] as AbortSignal;
    await wrapper.find('.btn-stop').trigger('click');
    expect(signal.aborted).toBe(true);
  });
});

describe('SequentialExecuteView 暂停与恢复', () => {
  /** 挂载并执行至暂停：步骤 0 完成 + 步骤 1 失败暂停 */
  async function mountUntilPaused() {
    (streamExecute as ReturnType<typeof vi.fn>).mockImplementation(async (_id, _p, _m, callbacks) => {
      callbacks.onWorkflowStart({ executionId: 'exec-1', templateName: '串行示例', mode: 'SEQUENTIAL', agentCount: 3 });
      callbacks.onStepStart({ agentIndex: 0, agentName: '调研 Agent', totalAgents: 3 });
      callbacks.onStepComplete({ agentIndex: 0, agentName: '调研 Agent', durationMs: 100, outputLength: 5 });
      callbacks.onWorkflowPaused({
        executionId: 'exec-1',
        failedAgent: '分析 Agent',
        failedIndex: 1,
        error: 'Agent 执行失败: 分析 Agent: 超时',
        resumable: true,
      });
    });
    const wrapper = mount(SequentialExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.param-input').setValue('主题');
    await wrapper.find('.btn-execute').trigger('click');
    await vi.waitFor(() => {
      expect(wrapper.find('.paused-banner').exists()).toBe(true);
    });
    return wrapper;
  }

  it('暂停时失败步骤红色高亮，显示恢复/终止按钮', async () => {
    const wrapper = await mountUntilPaused();
    // 暂停状态栏
    expect(wrapper.find('.paused-banner').text()).toContain('已暂停');
    expect(wrapper.find('.paused-banner').text()).toContain('分析 Agent');
    // 失败步骤节点红色高亮
    const nodes = wrapper.findAll('.pipeline-node');
    expect(nodes[1].classes()).toContain('pn-error');
    // 恢复与终止按钮
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
    mount(SequentialExecuteView, {
      props: { template: makeDetail(), resumeExecutionId: 'exec-9' },
    });
    await vi.waitFor(() => {
      expect(streamResume).toHaveBeenCalledTimes(1);
    });
    expect(streamResume).toHaveBeenCalledWith('exec-9', expect.any(Object), expect.any(AbortSignal));
  });
});
