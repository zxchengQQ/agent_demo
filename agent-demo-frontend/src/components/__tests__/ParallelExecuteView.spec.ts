import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import ParallelExecuteView from '../ParallelExecuteView.vue';
import { streamExecute, streamResume, terminate } from '@/api/workflow';
import type { WorkflowTemplateDetail } from '@/types';

/**
 * ParallelExecuteView 组件测试（Task-26，并行模式执行视图）
 * 验证标准：
 * 1. 接收 PARALLEL 模板（3 个分组）后渲染 3 列并排布局（CSS Grid）
 * 2. 每列标题为分组名（安全审查/性能审查/风格审查）
 * 3. 各列独立显示对应 Agent 的流式输出（token 追加到正确列）
 * 4. 汇总区在底部，显示最终综合结果
 * 5. 布局与 SequentialExecuteView 有显著差异（断言多列 Grid 元素存在，无流水线进度条）
 */

vi.mock('@/api/workflow', () => ({
  streamExecute: vi.fn(),
  streamResume: vi.fn(),
  terminate: vi.fn(),
}));

/** 构造 PARALLEL 模式模板详情（3 个分组，每分组 1 个 Agent） */
function makeDetail(overrides: Partial<WorkflowTemplateDetail> = {}): WorkflowTemplateDetail {
  const agent = (name: string) => ({ name, description: 'd', modelId: null, tools: [] });
  return {
    id: 'tpl-par',
    name: '并行审查',
    description: '并行编排',
    mode: 'PARALLEL',
    maxRetries: 3,
    agents: [],
    parameters: [{ name: 'code', type: 'string', required: true, description: '代码' }],
    parallelGroups: [
      { name: '安全审查', agents: [agent('安全审查 Agent')] },
      { name: '性能审查', agents: [agent('性能审查 Agent')] },
      { name: '风格审查', agents: [agent('风格审查 Agent')] },
    ],
    ...overrides,
  };
}

beforeEach(() => {
  vi.restoreAllMocks();
});

describe('ParallelExecuteView 多列布局', () => {
  it('接收 PARALLEL 模板（3 个分组）后渲染 3 列并排布局（CSS Grid）', () => {
    const wrapper = mount(ParallelExecuteView, { props: { template: makeDetail() } });
    // CSS Grid 容器存在
    expect(wrapper.find('.parallel-grid').exists()).toBe(true);
    // 3 个分组列
    const cols = wrapper.findAll('.group-column');
    expect(cols).toHaveLength(3);
  });

  it('每列标题为分组名（安全审查/性能审查/风格审查）', () => {
    const wrapper = mount(ParallelExecuteView, { props: { template: makeDetail() } });
    const headers = wrapper.findAll('.group-header');
    expect(headers).toHaveLength(3);
    expect(headers[0].text()).toContain('安全审查');
    expect(headers[1].text()).toContain('性能审查');
    expect(headers[2].text()).toContain('风格审查');
  });

  it('布局与 SequentialExecuteView 有显著差异（多列 Grid 存在，无流水线进度条）', () => {
    const wrapper = mount(ParallelExecuteView, { props: { template: makeDetail() } });
    // 并行视图标志性布局元素：多列 Grid
    expect(wrapper.find('.parallel-grid').exists()).toBe(true);
    expect(wrapper.findAll('.group-column').length).toBeGreaterThan(1);
    // 不存在串行视图的流水线进度条
    expect(wrapper.find('.pipeline-bar').exists()).toBe(false);
    expect(wrapper.find('.pipeline-node').exists()).toBe(false);
  });

  it('参数表单按模板定义动态生成', () => {
    const wrapper = mount(ParallelExecuteView, { props: { template: makeDetail() } });
    const inputs = wrapper.findAll('.param-input');
    expect(inputs).toHaveLength(1);
    expect(wrapper.text()).toContain('代码');
  });
});

describe('ParallelExecuteView 执行流程', () => {
  it('必填参数为空时点击执行不发起请求并提示', async () => {
    const wrapper = mount(ParallelExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.btn-execute').trigger('click');
    expect(streamExecute).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('请填写必填参数');
  });

  it('填写参数后点击执行调用 streamExecute', async () => {
    (streamExecute as ReturnType<typeof vi.fn>).mockResolvedValue(undefined);
    const wrapper = mount(ParallelExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.param-input').setValue('console.log(1)');
    await wrapper.find('.btn-execute').trigger('click');
    expect(streamExecute).toHaveBeenCalledTimes(1);
    expect(streamExecute).toHaveBeenCalledWith('tpl-par', { code: 'console.log(1)' }, '', expect.any(Object), expect.any(AbortSignal));
  });

  it('各列独立显示对应 Agent 的流式输出（token 追加到正确列）', async () => {
    (streamExecute as ReturnType<typeof vi.fn>).mockImplementation(async (_id, _p, _m, callbacks) => {
      callbacks.onWorkflowStart({ executionId: 'e1', templateName: '并行审查', mode: 'PARALLEL', agentCount: 3 });
      // 分组 1：安全审查（agentIndex=0）
      callbacks.onStepStart({ agentIndex: 0, agentName: '安全审查 Agent', totalAgents: 3 });
      callbacks.onToken({ agentIndex: 0, content: '安全' });
      callbacks.onToken({ agentIndex: 0, content: '通过' });
      // 分组 2：性能审查（agentIndex=1）
      callbacks.onStepStart({ agentIndex: 1, agentName: '性能审查 Agent', totalAgents: 3 });
      callbacks.onToken({ agentIndex: 1, content: '性能' });
      callbacks.onToken({ agentIndex: 1, content: '良好' });
      // 分组 3：风格审查（agentIndex=2）
      callbacks.onStepStart({ agentIndex: 2, agentName: '风格审查 Agent', totalAgents: 3 });
      callbacks.onToken({ agentIndex: 2, content: '风格' });
      callbacks.onToken({ agentIndex: 2, content: '一致' });
    });
    const wrapper = mount(ParallelExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.param-input').setValue('代码');
    await wrapper.find('.btn-execute').trigger('click');
    await vi.waitFor(() => {
      const cols = wrapper.findAll('.group-column');
      expect(cols).toHaveLength(3);
      // 第 1 列（安全审查）：token 追加到 agentIndex=0
      expect(cols[0].find('.panel-output').text()).toContain('安全通过');
      // 第 2 列（性能审查）：token 追加到 agentIndex=1
      expect(cols[1].find('.panel-output').text()).toContain('性能良好');
      // 第 3 列（风格审查）：token 追加到 agentIndex=2
      expect(cols[2].find('.panel-output').text()).toContain('风格一致');
    });
  });

  it('汇总区在底部显示最终综合结果', async () => {
    (streamExecute as ReturnType<typeof vi.fn>).mockImplementation(async (_id, _p, _m, callbacks) => {
      callbacks.onWorkflowStart({ executionId: 'e1', templateName: '并行审查', mode: 'PARALLEL', agentCount: 3 });
      callbacks.onStepStart({ agentIndex: 0, agentName: '安全审查 Agent', totalAgents: 3 });
      callbacks.onToken({ agentIndex: 0, content: '安全通过' });
      callbacks.onStepComplete({ agentIndex: 0, agentName: '安全审查 Agent', durationMs: 100, outputLength: 4 });
      callbacks.onWorkflowComplete({ executionId: 'e1', finalResult: '综合结论：代码质量合格', mode: 'PARALLEL', totalDurationMs: 300 });
    });
    const wrapper = mount(ParallelExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.param-input').setValue('代码');
    await wrapper.find('.btn-execute').trigger('click');
    await vi.waitFor(() => {
      // 底部汇总区显示最终综合结果
      expect(wrapper.find('.final-result').exists()).toBe(true);
      expect(wrapper.find('.final-content').text()).toContain('综合结论：代码质量合格');
    });
    // 状态 COMPLETED
    expect(wrapper.text()).toContain('COMPLETED');
  });

  it('点击"停止"调用 abort', async () => {
    (streamExecute as ReturnType<typeof vi.fn>).mockImplementation(async () => {
      // 永不完成，保持执行中
    });
    const wrapper = mount(ParallelExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.param-input').setValue('代码');
    await wrapper.find('.btn-execute').trigger('click');
    await vi.waitFor(() => {
      expect(wrapper.find('.btn-stop').exists()).toBe(true);
    });
    const signal = (streamExecute as ReturnType<typeof vi.fn>).mock.calls[0][4] as AbortSignal;
    await wrapper.find('.btn-stop').trigger('click');
    expect(signal.aborted).toBe(true);
  });
});

describe('ParallelExecuteView 暂停与恢复', () => {
  /** 挂载并执行至暂停：步骤 0 完成 + 步骤 1 失败暂停 */
  async function mountUntilPaused() {
    (streamExecute as ReturnType<typeof vi.fn>).mockImplementation(async (_id, _p, _m, callbacks) => {
      callbacks.onWorkflowStart({ executionId: 'exec-1', templateName: '并行审查', mode: 'PARALLEL', agentCount: 3 });
      callbacks.onStepStart({ agentIndex: 0, agentName: '安全审查 Agent', totalAgents: 3 });
      callbacks.onStepComplete({ agentIndex: 0, agentName: '安全审查 Agent', durationMs: 100, outputLength: 5 });
      callbacks.onWorkflowPaused({
        executionId: 'exec-1',
        failedAgent: '性能审查 Agent',
        failedIndex: 1,
        error: 'Agent 执行失败: 性能审查 Agent: 超时',
        resumable: true,
      });
    });
    const wrapper = mount(ParallelExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.param-input').setValue('代码');
    await wrapper.find('.btn-execute').trigger('click');
    await vi.waitFor(() => {
      expect(wrapper.find('.paused-banner').exists()).toBe(true);
    });
    return wrapper;
  }

  it('暂停时显示恢复/终止按钮', async () => {
    const wrapper = await mountUntilPaused();
    // 暂停状态栏
    expect(wrapper.find('.paused-banner').text()).toContain('已暂停');
    expect(wrapper.find('.paused-banner').text()).toContain('性能审查 Agent');
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
    mount(ParallelExecuteView, {
      props: { template: makeDetail(), resumeExecutionId: 'exec-9' },
    });
    await vi.waitFor(() => {
      expect(streamResume).toHaveBeenCalledTimes(1);
    });
    expect(streamResume).toHaveBeenCalledWith('exec-9', expect.any(Object), expect.any(AbortSignal));
  });
});
