import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import WorkflowPage from '../WorkflowPage.vue';
import SequentialExecuteView from '../SequentialExecuteView.vue';
import WorkflowExecuteView from '../WorkflowExecuteView.vue';
import { listTemplates, getTemplate, getExecution } from '@/api/workflow';
import type { WorkflowTemplateSummary, WorkflowTemplateDetail, OrchestrationMode } from '@/types';

/**
 * WorkflowPage 模式分发测试（CR-001 Task-30，AC-033）
 * 业务含义：主页面按 template.mode 动态分发执行视图组件（Sequential/Parallel/Loop/
 * Conditional/Supervisor），未识别的 mode 回退到 WorkflowExecuteView。
 * 通过各模式组件独有的布局元素 class 断言"渲染了对应模式组件"，
 * 并验证历史恢复场景 resumeExecutionId 正确透传到子组件。
 */

vi.mock('@/api/workflow', () => ({
  listTemplates: vi.fn(),
  getTemplate: vi.fn(),
  getExecution: vi.fn(),
  listExecutions: vi.fn(),
  streamExecute: vi.fn(),
  streamResume: vi.fn(),
  terminate: vi.fn(),
}));

// mock 子组件：聚焦 WorkflowPage 的"模式分发"逻辑，避免依赖卡片/历史内部 DOM
vi.mock('../WorkflowTemplateCard.vue', () => ({
  default: {
    name: 'MockTemplateCard',
    template: '<div class="mock-card" @click="$emit(\'select\', template)"></div>',
    props: ['template'],
    emits: ['select'],
  },
}));

vi.mock('../WorkflowHistoryList.vue', () => ({
  default: {
    name: 'MockHistoryList',
    template: '<div class="mock-history" @click="$emit(\'resume\', \'exec-1\')"></div>',
    props: {},
    emits: ['resume'],
  },
}));

/** 构造模板摘要 */
function makeSummary(mode: string, id: string): WorkflowTemplateSummary {
  return {
    id,
    name: `${mode} 模板`,
    description: 'd',
    mode: mode as OrchestrationMode,
    agentCount: 2,
    parameters: [],
  };
}

/** 构造模板详情（按模式填充专属字段，保证各模式布局元素可渲染） */
function makeDetail(mode: string): WorkflowTemplateDetail {
  const agent = (name: string) => ({ name, description: 'd', modelId: null, tools: [] });
  const base = {
    id: `tpl-${mode}`,
    name: `${mode} 模板`,
    description: 'd',
    mode: mode as OrchestrationMode,
    maxRetries: 3,
    agents: [agent('A'), agent('B')],
    parameters: [],
  };
  switch (mode) {
    case 'PARALLEL':
      return {
        ...base,
        parallelGroups: [
          { name: '组1', agents: [agent('A')] },
          { name: '组2', agents: [agent('B')] },
        ],
      } as WorkflowTemplateDetail;
    case 'LOOP':
      return {
        ...base,
        loop: { maxIterations: 3, exitConditionDescription: '评分≥90', agents: [agent('评分'), agent('修订')] },
      } as WorkflowTemplateDetail;
    case 'CONDITIONAL':
      return {
        ...base,
        branches: [
          { name: '简单', conditionDescription: '简单问题', agents: [agent('快速')] },
          { name: '复杂', conditionDescription: '复杂问题', agents: [agent('研究'), agent('总结')] },
        ],
      } as WorkflowTemplateDetail;
    case 'SUPERVISOR':
      return {
        ...base,
        supervisor: { maxSubtasks: 4, planAgent: agent('主控'), workers: [agent('W1'), agent('W2')], summarizeAgent: agent('汇总') },
      } as WorkflowTemplateDetail;
    default:
      return base as WorkflowTemplateDetail;
  }
}

beforeEach(() => {
  vi.restoreAllMocks();
});

/** 挂载页面并点击模板卡片进入执行视图（返回 wrapper，执行视图已渲染） */
async function mountAndSelect(mode: string, summaryId: string) {
  (listTemplates as ReturnType<typeof vi.fn>).mockResolvedValue([makeSummary(mode, summaryId)]);
  (getTemplate as ReturnType<typeof vi.fn>).mockResolvedValue(makeDetail(mode));
  const wrapper = mount(WorkflowPage);
  await flushPromises();
  await wrapper.find('.mock-card').trigger('click');
  await flushPromises();
  return wrapper;
}

describe('WorkflowPage 按编排模式动态分发执行视图（CR-001 Task-30，AC-033）', () => {
  it('选择 mode=SEQUENTIAL 模板渲染 SequentialExecuteView（.pipeline-bar）', async () => {
    const wrapper = await mountAndSelect('SEQUENTIAL', 'tpl-1');
    expect(wrapper.find('.pipeline-bar').exists()).toBe(true);
    // 不是其它模式布局
    expect(wrapper.find('.loop-timeline').exists()).toBe(false);
    expect(wrapper.find('.parallel-grid').exists()).toBe(false);
  });

  it('选择 mode=PARALLEL 模板渲染 ParallelExecuteView（.parallel-grid）', async () => {
    const wrapper = await mountAndSelect('PARALLEL', 'tpl-2');
    expect(wrapper.find('.parallel-grid').exists()).toBe(true);
    expect(wrapper.find('.pipeline-bar').exists()).toBe(false);
  });

  it('选择 mode=LOOP 模板渲染 LoopExecuteView（.loop-timeline）', async () => {
    const wrapper = await mountAndSelect('LOOP', 'tpl-3');
    expect(wrapper.find('.loop-timeline').exists()).toBe(true);
    expect(wrapper.find('.pipeline-bar').exists()).toBe(false);
  });

  it('选择 mode=CONDITIONAL 模板渲染 ConditionalExecuteView（.branch-map）', async () => {
    const wrapper = await mountAndSelect('CONDITIONAL', 'tpl-4');
    expect(wrapper.find('.branch-map').exists()).toBe(true);
    expect(wrapper.find('.pipeline-bar').exists()).toBe(false);
  });

  it('选择 mode=SUPERVISOR 模板渲染 SupervisorExecuteView（.subtask-list）', async () => {
    const wrapper = await mountAndSelect('SUPERVISOR', 'tpl-5');
    expect(wrapper.find('.subtask-list').exists()).toBe(true);
    expect(wrapper.find('.pipeline-bar').exists()).toBe(false);
  });

  it('未识别的 mode 回退到 WorkflowExecuteView（默认回退）', async () => {
    const unknownMode = 'UNKNOWN' as OrchestrationMode;
    (listTemplates as ReturnType<typeof vi.fn>).mockResolvedValue([{ ...makeSummary('SEQUENTIAL', 'tpl-x'), mode: unknownMode }]);
    (getTemplate as ReturnType<typeof vi.fn>).mockResolvedValue({ ...makeDetail('SEQUENTIAL'), mode: unknownMode });
    const wrapper = mount(WorkflowPage);
    await flushPromises();
    await wrapper.find('.mock-card').trigger('click');
    await flushPromises();
    expect(wrapper.findComponent(WorkflowExecuteView).exists()).toBe(true);
    expect(wrapper.findComponent(SequentialExecuteView).exists()).toBe(false);
  });
});

describe('WorkflowPage resumeExecutionId 传递（CR-001 Task-30，AC-033 恢复场景）', () => {
  it('历史页恢复：resumeExecutionId 正确传递到模式执行视图', async () => {
    (listTemplates as ReturnType<typeof vi.fn>).mockResolvedValue([makeSummary('SEQUENTIAL', 'tpl-SEQUENTIAL')]);
    (getExecution as ReturnType<typeof vi.fn>).mockResolvedValue({
      executionId: 'exec-1',
      templateId: 'tpl-SEQUENTIAL',
      templateName: 'SEQUENTIAL 模板',
      mode: 'SEQUENTIAL',
      status: 'PAUSED',
      startTime: null,
      endTime: null,
      finalResult: '',
      iterationCount: 0,
      steps: [],
    });
    (getTemplate as ReturnType<typeof vi.fn>).mockResolvedValue(makeDetail('SEQUENTIAL'));
    const wrapper = mount(WorkflowPage);
    await flushPromises();
    // 切换到执行历史 tab（index 1）
    const tabs = wrapper.findAll('.tab');
    await tabs[1].trigger('click');
    await flushPromises();
    // mock 历史列表点击 → emit('resume', 'exec-1')
    await wrapper.find('.mock-history').trigger('click');
    await flushPromises();
    // 断言按执行 ID 查详情并进入执行视图，resumeExecutionId 透传
    expect(getExecution).toHaveBeenCalledWith('exec-1');
    const seqView = wrapper.findComponent(SequentialExecuteView);
    expect(seqView.exists()).toBe(true);
    expect(seqView.props('resumeExecutionId')).toBe('exec-1');
    expect(wrapper.find('.pipeline-bar').exists()).toBe(true);
  });

  it('从模板列表正常进入执行视图时不携带 resumeExecutionId（回归）', async () => {
    const wrapper = await mountAndSelect('SEQUENTIAL', 'tpl-1');
    const seqView = wrapper.findComponent(SequentialExecuteView);
    expect(seqView.exists()).toBe(true);
    expect(seqView.props('resumeExecutionId')).toBeUndefined();
  });
});

describe('WorkflowPage 模板列表/历史切换回归（CR-001 Task-30）', () => {
  it('挂载后渲染模板列表子视图（模板卡片 + 顶部 tabs）', async () => {
    (listTemplates as ReturnType<typeof vi.fn>).mockResolvedValue([makeSummary('SEQUENTIAL', 'tpl-1')]);
    const wrapper = mount(WorkflowPage);
    await flushPromises();
    expect(wrapper.find('.page-tabs').exists()).toBe(true);
    expect(wrapper.find('.mock-card').exists()).toBe(true);
  });

  it('切换到执行历史子视图渲染历史列表', async () => {
    (listTemplates as ReturnType<typeof vi.fn>).mockResolvedValue([makeSummary('SEQUENTIAL', 'tpl-1')]);
    const wrapper = mount(WorkflowPage);
    await flushPromises();
    const tabs = wrapper.findAll('.tab');
    await tabs[1].trigger('click');
    await flushPromises();
    expect(wrapper.find('.mock-history').exists()).toBe(true);
    expect(wrapper.find('.mock-card').exists()).toBe(false);
  });

  it('从执行视图点击返回回到模板列表', async () => {
    const wrapper = await mountAndSelect('SEQUENTIAL', 'tpl-1');
    expect(wrapper.find('.pipeline-bar').exists()).toBe(true);
    await wrapper.find('.btn-back').trigger('click');
    await flushPromises();
    expect(wrapper.find('.mock-card').exists()).toBe(true);
    expect(wrapper.find('.pipeline-bar').exists()).toBe(false);
  });
});
