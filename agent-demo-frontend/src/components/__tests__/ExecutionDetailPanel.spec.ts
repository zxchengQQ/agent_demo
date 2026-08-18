import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import ExecutionDetailPanel from '../ExecutionDetailPanel.vue';
import { getExecution } from '@/api/workflow';
import type { WorkflowExecutionDetail } from '@/types';

/**
 * ExecutionDetailPanel 组件测试（CR-001 Task-31，AC-034）
 * 验证标准：
 * 1. 接收 executionId prop，调用 getExecution(executionId) API 获取数据
 * 2. API 加载期间显示 loading 状态
 * 3. 详情面板顶部显示执行概要（模板名/模式/状态）
 * 4. 详情面板中部显示步骤列表（每步 Agent 名称/状态/耗时/输出内容折叠区）
 * 5. 详情面板底部显示最终结果全文（不截断）
 * 6. PAUSED 记录详情面板仍显示"恢复"按钮（点击后通知父组件）
 * 7. API 返回 404/500 时的错误提示展示
 * 8. executionId 变化时重新加载
 */

vi.mock('@/api/workflow', () => ({
  getExecution: vi.fn(),
}));

/** 构造执行详情 */
function makeDetail(overrides: Partial<WorkflowExecutionDetail> = {}): WorkflowExecutionDetail {
  return {
    executionId: 'exec-1',
    templateId: 'tpl-1',
    templateName: '研究-分析-总结',
    mode: 'SEQUENTIAL',
    status: 'COMPLETED',
    startTime: '2026-08-14T10:00:00',
    endTime: '2026-08-14T10:05:30',
    finalResult: '这是最终结果全文，内容足够长用于验证不截断展示。',
    iterationCount: 0,
    steps: [
      { agentName: '研究 Agent', status: 'COMPLETED', durationMs: 120000, output: '研究输出内容' },
      { agentName: '分析 Agent', status: 'COMPLETED', durationMs: 90000, output: '分析输出内容' },
    ],
    ...overrides,
  };
}

beforeEach(() => {
  vi.restoreAllMocks();
  (getExecution as ReturnType<typeof vi.fn>).mockReset();
});

describe('ExecutionDetailPanel 数据加载', () => {
  it('挂载时调用 getExecution(executionId)', async () => {
    (getExecution as ReturnType<typeof vi.fn>).mockResolvedValue(makeDetail());
    const wrapper = mount(ExecutionDetailPanel, { props: { executionId: 'exec-1' } });
    await flushPromises();
    expect(getExecution).toHaveBeenCalledTimes(1);
    expect(getExecution).toHaveBeenCalledWith('exec-1');
    expect(wrapper.find('.detail-body').exists()).toBe(true);
  });

  it('API 加载期间显示 loading 状态', async () => {
    // 返回 pending promise：加载永不结束，loading 保持 true
    (getExecution as ReturnType<typeof vi.fn>).mockReturnValue(new Promise(() => {}));
    const wrapper = mount(ExecutionDetailPanel, { props: { executionId: 'exec-1' } });
    await flushPromises();
    expect(wrapper.find('.detail-loading').exists()).toBe(true);
    expect(wrapper.find('.detail-body').exists()).toBe(false);
  });

  it('executionId 变化时重新加载', async () => {
    const mock = getExecution as ReturnType<typeof vi.fn>;
    mock.mockResolvedValue(makeDetail());
    const wrapper = mount(ExecutionDetailPanel, { props: { executionId: 'exec-1' } });
    await flushPromises();
    expect(mock).toHaveBeenCalledTimes(1);

    await wrapper.setProps({ executionId: 'exec-2' });
    await flushPromises();
    expect(mock).toHaveBeenCalledTimes(2);
    expect(mock).toHaveBeenLastCalledWith('exec-2');
  });
});

describe('ExecutionDetailPanel 渲染', () => {
  it('加载完成后渲染执行概要（模板名/模式/状态）', async () => {
    (getExecution as ReturnType<typeof vi.fn>).mockResolvedValue(makeDetail());
    const wrapper = mount(ExecutionDetailPanel, { props: { executionId: 'exec-1' } });
    await flushPromises();
    const summary = wrapper.find('.detail-summary');
    expect(summary.exists()).toBe(true);
    expect(summary.text()).toContain('研究-分析-总结');
    expect(summary.text()).toContain('串行');
    expect(summary.text()).toContain('已完成');
  });

  it('渲染步骤列表（每步 Agent 名称/状态/耗时）', async () => {
    (getExecution as ReturnType<typeof vi.fn>).mockResolvedValue(makeDetail());
    const wrapper = mount(ExecutionDetailPanel, { props: { executionId: 'exec-1' } });
    await flushPromises();
    const steps = wrapper.findAll('.step-item');
    expect(steps).toHaveLength(2);
    // 第 1 步：Agent 名称/状态/耗时（120000ms = 120.0s）
    expect(steps[0].text()).toContain('研究 Agent');
    expect(steps[0].text()).toContain('COMPLETED');
    expect(steps[0].text()).toContain('120.0s');
    // 第 2 步：Agent 名称/耗时（90000ms = 90.0s）
    expect(steps[1].text()).toContain('分析 Agent');
    expect(steps[1].text()).toContain('90.0s');
  });

  it('步骤输出内容折叠区展示（v-if step.output）', async () => {
    (getExecution as ReturnType<typeof vi.fn>).mockResolvedValue(makeDetail());
    const wrapper = mount(ExecutionDetailPanel, { props: { executionId: 'exec-1' } });
    await flushPromises();
    const outputs = wrapper.findAll('.step-output');
    expect(outputs).toHaveLength(2);
    expect(outputs[0].text()).toContain('研究输出内容');
    expect(outputs[1].text()).toContain('分析输出内容');
  });

  it('底部显示最终结果全文（不截断）', async () => {
    // 构造远超 50 字的结果，验证详情面板不截断
    const longResult = '这是最终结果全文，用于验证不截断展示。'.repeat(20);
    (getExecution as ReturnType<typeof vi.fn>).mockResolvedValue(makeDetail({ finalResult: longResult }));
    const wrapper = mount(ExecutionDetailPanel, { props: { executionId: 'exec-1' } });
    await flushPromises();
    const finalText = wrapper.find('.final-text');
    expect(finalText.exists()).toBe(true);
    expect(finalText.text()).toBe(longResult);
  });
});

describe('ExecutionDetailPanel PAUSED 与错误处理', () => {
  it('status=PAUSED 时显示恢复按钮，点击 emit("resume", executionId)', async () => {
    (getExecution as ReturnType<typeof vi.fn>).mockResolvedValue(makeDetail({ status: 'PAUSED' }));
    const wrapper = mount(ExecutionDetailPanel, { props: { executionId: 'exec-1' } });
    await flushPromises();
    const btn = wrapper.find('.btn-resume');
    expect(btn.exists()).toBe(true);
    expect(btn.text()).toContain('恢复');
    await btn.trigger('click');
    expect(wrapper.emitted('resume')).toBeTruthy();
    expect(wrapper.emitted('resume')![0]).toEqual(['exec-1']);
  });

  it('API 报错（404/500）时显示错误提示', async () => {
    (getExecution as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('获取执行详情失败'));
    const wrapper = mount(ExecutionDetailPanel, { props: { executionId: 'exec-1' } });
    await flushPromises();
    expect(wrapper.find('.detail-error').exists()).toBe(true);
    expect(wrapper.text()).toContain('加载执行详情失败');
  });
});
