import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import WorkflowHistoryList from './WorkflowHistoryList.vue';
import { listExecutions } from '@/api/workflow';
import type { WorkflowExecutionSummary } from '@/types';

/**
 * WorkflowHistoryList 组件测试（P2 Task-20，AC-027）
 */

vi.mock('@/api/workflow', () => ({
  listExecutions: vi.fn(),
}));

function makeExecution(overrides: Partial<WorkflowExecutionSummary> = {}): WorkflowExecutionSummary {
  return {
    executionId: 'e1',
    templateId: 't1',
    templateName: '质量评分-修订',
    mode: 'LOOP',
    status: 'COMPLETED',
    startTime: '2026-08-13T10:00:00',
    endTime: '2026-08-13T10:05:00',
    finalResult: '最终修订稿',
    iterationCount: 3,
    ...overrides,
  };
}

beforeEach(() => {
  vi.restoreAllMocks();
});

describe('WorkflowHistoryList', () => {
  it('应渲染执行历史列表（模板名/模式/状态/结果）', async () => {
    (listExecutions as ReturnType<typeof vi.fn>).mockResolvedValue([makeExecution()]);
    const wrapper = mount(WorkflowHistoryList);
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('质量评分-修订');
    });
    expect(wrapper.text()).toContain('已完成');
    expect(wrapper.text()).toContain('循环');
    expect(wrapper.text()).toContain('3 轮迭代');
    expect(wrapper.text()).toContain('最终修订稿');
  });

  it('COMPLETED 状态标签使用绿色样式', async () => {
    (listExecutions as ReturnType<typeof vi.fn>).mockResolvedValue([makeExecution()]);
    const wrapper = mount(WorkflowHistoryList);
    await vi.waitFor(() => {
      expect(wrapper.find('.status-tag').exists()).toBe(true);
    });
    expect(wrapper.find('.status-tag').classes()).toContain('status-completed');
  });

  it('空历史显示"暂无执行记录"', async () => {
    (listExecutions as ReturnType<typeof vi.fn>).mockResolvedValue([]);
    const wrapper = mount(WorkflowHistoryList);
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('暂无执行记录');
    });
  });

  it('加载失败显示错误提示', async () => {
    (listExecutions as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('失败'));
    const wrapper = mount(WorkflowHistoryList);
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('加载执行历史失败');
    });
  });
});

describe('WorkflowHistoryList 暂停标签与恢复入口（P3 Task-22，AC-016/AC-017/AC-027）', () => {
  it('PAUSED 记录显示橙色"已暂停"标签', async () => {
    (listExecutions as ReturnType<typeof vi.fn>).mockResolvedValue([makeExecution({ status: 'PAUSED' })]);
    const wrapper = mount(WorkflowHistoryList);
    await vi.waitFor(() => {
      expect(wrapper.find('.status-tag').classes()).toContain('status-paused');
    });
    expect(wrapper.find('.status-tag').text()).toBe('已暂停');
  });

  it('PAUSED 记录显示恢复按钮，其他状态记录无恢复按钮', async () => {
    (listExecutions as ReturnType<typeof vi.fn>).mockResolvedValue([
      makeExecution({ executionId: 'e-paused', status: 'PAUSED' }),
      makeExecution({ executionId: 'e-done', status: 'COMPLETED' }),
      makeExecution({ executionId: 'e-failed', status: 'FAILED' }),
      makeExecution({ executionId: 'e-term', status: 'TERMINATED' }),
      makeExecution({ executionId: 'e-timeout', status: 'TIMEOUT' }),
      makeExecution({ executionId: 'e-run', status: 'RUNNING' }),
    ]);
    const wrapper = mount(WorkflowHistoryList);
    await vi.waitFor(() => {
      expect(wrapper.findAll('.history-item')).toHaveLength(6);
    });
    const resumeBtns = wrapper.findAll('.btn-resume');
    expect(resumeBtns).toHaveLength(1);
    expect(resumeBtns[0].text()).toBe('恢复');
  });

  it('点击恢复按钮 emit("resume", executionId)（AC-017）', async () => {
    (listExecutions as ReturnType<typeof vi.fn>).mockResolvedValue([makeExecution({ executionId: 'exec-9', status: 'PAUSED' })]);
    const wrapper = mount(WorkflowHistoryList);
    await vi.waitFor(() => {
      expect(wrapper.find('.btn-resume').exists()).toBe(true);
    });
    await wrapper.find('.btn-resume').trigger('click');
    expect(wrapper.emitted('resume')).toHaveLength(1);
    expect(wrapper.emitted('resume')![0]).toEqual(['exec-9']);
  });

  it('FAILED 状态标签使用红色样式（既有回归，AC-027）', async () => {
    (listExecutions as ReturnType<typeof vi.fn>).mockResolvedValue([makeExecution({ status: 'FAILED' })]);
    const wrapper = mount(WorkflowHistoryList);
    await vi.waitFor(() => {
      expect(wrapper.find('.status-tag').classes()).toContain('status-failed');
    });
  });
});
