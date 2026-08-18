import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import WorkflowPage from './WorkflowPage.vue';
import SequentialExecuteView from './SequentialExecuteView.vue';
import { listTemplates, getTemplate, getExecution } from '@/api/workflow';
import type { WorkflowTemplateDetail, WorkflowTemplateSummary } from '@/types';

/**
 * WorkflowPage 主页面测试（P2 Task-18，AC-032）
 * P3 扩展：历史暂停记录恢复入口（Task-22，AC-017）
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

function makeSummary(overrides: Partial<WorkflowTemplateSummary> = {}): WorkflowTemplateSummary {
  return {
    id: 'tpl-1',
    name: '研究-分析-总结',
    description: '串行',
    mode: 'SEQUENTIAL',
    agentCount: 3,
    parameters: [{ name: 'topic', type: 'string', required: true, description: '主题' }],
    ...overrides,
  };
}

function makeDetail(overrides: Partial<WorkflowTemplateDetail> = {}): WorkflowTemplateDetail {
  return {
    id: 'tpl-1',
    name: '研究-分析-总结',
    description: '串行',
    mode: 'SEQUENTIAL',
    maxRetries: 3,
    agents: [{ name: '研究 Agent', description: 'd', modelId: null, tools: [] }],
    parameters: [{ name: 'topic', type: 'string', required: true, description: '主题' }],
    ...overrides,
  };
}

beforeEach(() => {
  vi.restoreAllMocks();
});

describe('WorkflowPage', () => {
  it('挂载后调用 listTemplates 并渲染模板卡片网格（AC-032）', async () => {
    (listTemplates as ReturnType<typeof vi.fn>).mockResolvedValue([makeSummary()]);
    const wrapper = mount(WorkflowPage);
    await vi.waitFor(() => {
      expect(wrapper.find('.template-card').exists()).toBe(true);
    });
    expect(wrapper.text()).toContain('研究-分析-总结');
    expect(wrapper.text()).toContain('模板列表');
    expect(wrapper.text()).toContain('执行历史');
  });

  it('空列表显示"暂无可用模板"', async () => {
    (listTemplates as ReturnType<typeof vi.fn>).mockResolvedValue([]);
    const wrapper = mount(WorkflowPage);
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('暂无可用模板');
    });
  });

  it('点击模板卡片加载详情并切换到执行视图', async () => {
    (listTemplates as ReturnType<typeof vi.fn>).mockResolvedValue([makeSummary()]);
    (getTemplate as ReturnType<typeof vi.fn>).mockResolvedValue(makeDetail());
    const wrapper = mount(WorkflowPage);
    await vi.waitFor(() => {
      expect(wrapper.find('.template-card').exists()).toBe(true);
    });
    await wrapper.find('.template-card').trigger('click');
    await vi.waitFor(() => {
      expect(getTemplate).toHaveBeenCalledWith('tpl-1');
      // 执行视图渲染参数表单
      expect(wrapper.find('.param-form').exists()).toBe(true);
    });
  });

  it('切换到历史视图渲染 WorkflowHistoryList', async () => {
    (listTemplates as ReturnType<typeof vi.fn>).mockResolvedValue([makeSummary()]);
    const wrapper = mount(WorkflowPage);
    await vi.waitFor(() => {
      expect(wrapper.find('.tab').exists()).toBe(true);
    });
    // mock 执行历史为空列表（避免 WorkflowHistoryList 渲染 undefined 报错）
    const listExecutionsMock = (await import('@/api/workflow')).listExecutions as ReturnType<typeof vi.fn>;
    listExecutionsMock.mockResolvedValue([]);
    const tabs = wrapper.findAll('.tab');
    // tabs[0]=模板列表, tabs[1]=执行历史
    await tabs[1].trigger('click');
    await vi.waitFor(() => {
      expect(wrapper.find('.history-list').exists()).toBe(true);
    });
  });
});

describe('WorkflowPage 历史暂停记录恢复入口（P3 Task-22，AC-017）', () => {
  it('点击历史恢复按钮后加载执行详情与模板，切换执行视图并传入 resumeExecutionId', async () => {
    (listTemplates as ReturnType<typeof vi.fn>).mockResolvedValue([makeSummary()]);
    (getExecution as ReturnType<typeof vi.fn>).mockResolvedValue({
      executionId: 'exec-9',
      templateId: 'tpl-1',
      templateName: '研究-分析-总结',
      mode: 'SEQUENTIAL',
      status: 'PAUSED',
      startTime: null,
      endTime: null,
      finalResult: '',
      iterationCount: 0,
      steps: [],
    });
    (getTemplate as ReturnType<typeof vi.fn>).mockResolvedValue(makeDetail());
    const listExecutionsMock = (await import('@/api/workflow')).listExecutions as ReturnType<typeof vi.fn>;
    listExecutionsMock.mockResolvedValue([
      {
        executionId: 'exec-9',
        templateId: 'tpl-1',
        templateName: '研究-分析-总结',
        mode: 'SEQUENTIAL',
        status: 'PAUSED',
        startTime: '2026-08-17T10:00:00',
        endTime: null,
        finalResult: '',
        iterationCount: 0,
      },
    ]);

    const wrapper = mount(WorkflowPage);
    await vi.waitFor(() => {
      expect(wrapper.find('.tab').exists()).toBe(true);
    });
    // 切换到历史视图 → 渲染 PAUSED 记录 → 点击恢复按钮
    const tabs = wrapper.findAll('.tab');
    await tabs[1].trigger('click');
    await vi.waitFor(() => {
      expect(wrapper.find('.btn-resume').exists()).toBe(true);
    });
    await wrapper.find('.btn-resume').trigger('click');

    await vi.waitFor(() => {
      // 按执行 ID 查详情，再按详情中的 templateId 加载模板
      expect(getExecution).toHaveBeenCalledWith('exec-9');
      expect(getTemplate).toHaveBeenCalledWith('tpl-1');
      // 切换到执行视图并传入 resumeExecutionId
      const executeView = wrapper.findComponent(SequentialExecuteView);
      expect(executeView.exists()).toBe(true);
      expect(executeView.props('resumeExecutionId')).toBe('exec-9');
    });
  });

  it('从模板列表正常进入执行视图时不携带 resumeExecutionId（回归）', async () => {
    (listTemplates as ReturnType<typeof vi.fn>).mockResolvedValue([makeSummary()]);
    (getTemplate as ReturnType<typeof vi.fn>).mockResolvedValue(makeDetail());
    const wrapper = mount(WorkflowPage);
    await vi.waitFor(() => {
      expect(wrapper.find('.template-card').exists()).toBe(true);
    });
    await wrapper.find('.template-card').trigger('click');
    await vi.waitFor(() => {
      const executeView = wrapper.findComponent(SequentialExecuteView);
      expect(executeView.exists()).toBe(true);
      expect(executeView.props('resumeExecutionId')).toBeUndefined();
    });
  });
});
