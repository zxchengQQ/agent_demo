import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import ConditionalExecuteView from '../ConditionalExecuteView.vue';
import { streamExecute, streamResume, terminate } from '@/api/workflow';
import type { WorkflowTemplateDetail } from '@/types';

/**
 * ConditionalExecuteView 组件测试（Task-28，条件分支模式执行视图）
 * 验证标准：
 * 1. 接收 CONDITIONAL 模板后渲染分岔路径图
 * 2. 执行开始后实际走的分支高亮，未走分支灰色
 * 3. 分支内 Agent 输出在高亮分支下方展示
 * 4. 布局与 LoopExecuteView 有显著差异（断言分岔路径元素存在，无时间线）
 * 5. 参数表单按模板定义动态生成
 * 6. 必填参数校验
 * 7. 执行/停止/暂停/恢复按钮
 * 8. 传入 resumeExecutionId prop 时挂载自动发起 streamResume
 */

vi.mock('@/api/workflow', () => ({
  streamExecute: vi.fn(),
  streamResume: vi.fn(),
  terminate: vi.fn(),
}));

/** 构造 CONDITIONAL 模式模板详情（2 个分支：快速回答 / 复杂拆解） */
function makeDetail(overrides: Partial<WorkflowTemplateDetail> = {}): WorkflowTemplateDetail {
  const agent = (name: string) => ({ name, description: 'd', modelId: null, tools: [] });
  return {
    id: 'tpl-cond',
    name: '智能路由条件分支',
    description: '条件编排',
    mode: 'CONDITIONAL',
    maxRetries: 3,
    agents: [],
    parameters: [{ name: 'question', type: 'string', required: true, description: '问题' }],
    branches: [
      { name: '快速回答', conditionDescription: '简单问题', agents: [agent('QuickAnswer')] },
      { name: '复杂拆解', conditionDescription: '复杂问题', agents: [agent('研究'), agent('分析'), agent('总结')] },
    ],
    ...overrides,
  };
}

beforeEach(() => {
  vi.restoreAllMocks();
});

describe('ConditionalExecuteView 分岔路径与布局', () => {
  it('接收 CONDITIONAL 模板后渲染分岔路径图容器', () => {
    const wrapper = mount(ConditionalExecuteView, { props: { template: makeDetail() } });
    expect(wrapper.find('.branch-map').exists()).toBe(true);
  });

  it('布局与 LoopExecuteView 有显著差异（分岔路径存在，无时间线）', () => {
    const wrapper = mount(ConditionalExecuteView, { props: { template: makeDetail() } });
    // 条件分支视图标志性布局元素：分岔路径图
    expect(wrapper.find('.branch-map').exists()).toBe(true);
    // 不存在循环视图的垂直时间线
    expect(wrapper.find('.loop-timeline').exists()).toBe(false);
    expect(wrapper.find('.timeline-node').exists()).toBe(false);
    // 不存在串行视图的流水线进度条
    expect(wrapper.find('.pipeline-bar').exists()).toBe(false);
    expect(wrapper.find('.pipeline-node').exists()).toBe(false);
    // 不存在并行视图的多列 Grid
    expect(wrapper.find('.parallel-grid').exists()).toBe(false);
    expect(wrapper.find('.group-column').exists()).toBe(false);
  });

  it('参数表单按模板定义动态生成', () => {
    const wrapper = mount(ConditionalExecuteView, { props: { template: makeDetail() } });
    const inputs = wrapper.findAll('.param-input');
    expect(inputs).toHaveLength(1);
    expect(wrapper.text()).toContain('问题');
  });

  it('渲染所有分支路径项', () => {
    const wrapper = mount(ConditionalExecuteView, { props: { template: makeDetail() } });
    expect(wrapper.findAll('.branch-path')).toHaveLength(2);
  });
});

describe('ConditionalExecuteView 分支高亮', () => {
  it('执行开始后收到 branch_selected 事件，实际走的分支高亮，未走分支灰色', async () => {
    (streamExecute as ReturnType<typeof vi.fn>).mockImplementation(async (_id, _p, _m, callbacks) => {
      callbacks.onWorkflowStart({ executionId: 'e1', templateName: '智能路由条件分支', mode: 'CONDITIONAL', agentCount: 4 });
      callbacks.onBranchSelected({ branchIndex: 1, branchName: '复杂拆解', agentCount: 3 });
    });
    const wrapper = mount(ConditionalExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.param-input').setValue('测试问题');
    await wrapper.find('.btn-execute').trigger('click');
    await vi.waitFor(() => {
      const paths = wrapper.findAll('.branch-path');
      expect(paths).toHaveLength(2);
      // 复杂拆解（分支1）高亮
      expect(paths[1].classes()).toContain('active');
      expect(paths[1].classes()).not.toContain('inactive');
      // 快速回答（分支0）灰色
      expect(paths[0].classes()).toContain('inactive');
      expect(paths[0].classes()).not.toContain('active');
    });
  });

  it('分支内 Agent 输出在高亮分支下方展示', async () => {
    (streamExecute as ReturnType<typeof vi.fn>).mockImplementation(async (_id, _p, _m, callbacks) => {
      callbacks.onWorkflowStart({ executionId: 'e1', templateName: '智能路由条件分支', mode: 'CONDITIONAL', agentCount: 4 });
      callbacks.onBranchSelected({ branchIndex: 1, branchName: '复杂拆解', agentCount: 3 });
      // 研究 Agent（全局 agentIndex=1，分支1的第 0 个 Agent）
      callbacks.onStepStart({ agentIndex: 1, agentName: '研究', totalAgents: 4 });
      callbacks.onToken({ agentIndex: 1, content: '研究结果' });
      callbacks.onStepComplete({ agentIndex: 1, agentName: '研究', durationMs: 100, outputLength: 4 });
    });
    const wrapper = mount(ConditionalExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.param-input').setValue('测试问题');
    await wrapper.find('.btn-execute').trigger('click');
    await vi.waitFor(() => {
      const activePath = wrapper.find('.branch-path.active');
      expect(activePath.find('.branch-output').exists()).toBe(true);
      expect(activePath.find('.branch-output').text()).toContain('研究结果');
    });
  });
});

describe('ConditionalExecuteView 执行流程', () => {
  it('必填参数为空时点击执行不发起请求并提示', async () => {
    const wrapper = mount(ConditionalExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.btn-execute').trigger('click');
    expect(streamExecute).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('请填写必填参数');
  });

  it('填写参数后点击执行调用 streamExecute', async () => {
    (streamExecute as ReturnType<typeof vi.fn>).mockResolvedValue(undefined);
    const wrapper = mount(ConditionalExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.param-input').setValue('测试问题');
    await wrapper.find('.btn-execute').trigger('click');
    expect(streamExecute).toHaveBeenCalledTimes(1);
    expect(streamExecute).toHaveBeenCalledWith('tpl-cond', { question: '测试问题' }, '', expect.any(Object), expect.any(AbortSignal));
  });

  it('点击"停止"调用 abort', async () => {
    (streamExecute as ReturnType<typeof vi.fn>).mockImplementation(async () => {
      // 永不完成，保持执行中
    });
    const wrapper = mount(ConditionalExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.param-input').setValue('测试问题');
    await wrapper.find('.btn-execute').trigger('click');
    await vi.waitFor(() => {
      expect(wrapper.find('.btn-stop').exists()).toBe(true);
    });
    const signal = (streamExecute as ReturnType<typeof vi.fn>).mock.calls[0][4] as AbortSignal;
    await wrapper.find('.btn-stop').trigger('click');
    expect(signal.aborted).toBe(true);
  });
});

describe('ConditionalExecuteView 暂停与恢复', () => {
  /** 挂载并执行至暂停：选择复杂拆解分支后研究 Agent 失败暂停 */
  async function mountUntilPaused() {
    (streamExecute as ReturnType<typeof vi.fn>).mockImplementation(async (_id, _p, _m, callbacks) => {
      callbacks.onWorkflowStart({ executionId: 'exec-1', templateName: '智能路由条件分支', mode: 'CONDITIONAL', agentCount: 4 });
      callbacks.onBranchSelected({ branchIndex: 1, branchName: '复杂拆解', agentCount: 3 });
      callbacks.onStepStart({ agentIndex: 1, agentName: '研究', totalAgents: 4 });
      callbacks.onWorkflowPaused({
        executionId: 'exec-1',
        failedAgent: '研究',
        failedIndex: 1,
        error: 'Agent 执行失败: 研究: 超时',
        resumable: true,
      });
    });
    const wrapper = mount(ConditionalExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.param-input').setValue('测试问题');
    await wrapper.find('.btn-execute').trigger('click');
    await vi.waitFor(() => {
      expect(wrapper.find('.paused-banner').exists()).toBe(true);
    });
    return wrapper;
  }

  it('暂停时显示恢复/终止按钮 + 暂停横幅', async () => {
    const wrapper = await mountUntilPaused();
    expect(wrapper.find('.paused-banner').text()).toContain('已暂停');
    expect(wrapper.find('.paused-banner').text()).toContain('研究');
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
    mount(ConditionalExecuteView, {
      props: { template: makeDetail(), resumeExecutionId: 'exec-9' },
    });
    await vi.waitFor(() => {
      expect(streamResume).toHaveBeenCalledTimes(1);
    });
    expect(streamResume).toHaveBeenCalledWith('exec-9', expect.any(Object), expect.any(AbortSignal));
  });
});
