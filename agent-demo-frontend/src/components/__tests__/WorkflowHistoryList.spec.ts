import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import WorkflowHistoryList from '../WorkflowHistoryList.vue';
import ExecutionDetailPanel from '../ExecutionDetailPanel.vue';
import { listExecutions, getExecution } from '@/api/workflow';
import type { WorkflowExecutionDetail, WorkflowExecutionSummary } from '@/types';

/**
 * WorkflowHistoryList 组件测试（CR-001 Task-31，AC-034 展开详情）
 * 验证标准：
 * 1. 历史记录项有 @click 事件，点击后展开 ExecutionDetailPanel
 * 2. 同一时间只展开一条记录（手风琴：点击另一条时前一条收起）
 * 3. 再次点击已展开的记录 -> 收起详情面板
 * 4. PAUSED 记录恢复按钮点击触发 emit('resume') 且不触发展开（@click.stop）
 * 5. 既有功能回归：PAUSED 记录显示恢复按钮
 */

vi.mock('@/api/workflow', () => ({
  listExecutions: vi.fn(),
  getExecution: vi.fn(),
}));

/** 构造执行摘要 */
function makeSummary(overrides: Partial<WorkflowExecutionSummary> = {}): WorkflowExecutionSummary {
  return {
    executionId: 'exec-1',
    templateId: 'tpl-1',
    templateName: '研究-分析-总结',
    mode: 'SEQUENTIAL',
    status: 'COMPLETED',
    startTime: '2026-08-14T10:00:00',
    endTime: '2026-08-14T10:05:30',
    finalResult: '这是最终结果摘要',
    iterationCount: 0,
    ...overrides,
  };
}

/** 构造执行详情（详情面板内部 getExecution 返回） */
function makeDetail(executionId: string): WorkflowExecutionDetail {
  return {
    ...makeSummary({ executionId }),
    steps: [{ agentName: '研究 Agent', status: 'COMPLETED', durationMs: 1000, output: '输出内容' }],
  };
}

/** 挂载历史列表并完成首次加载 */
async function mountList(summaries: WorkflowExecutionSummary[]) {
  (listExecutions as ReturnType<typeof vi.fn>).mockResolvedValue(summaries);
  (getExecution as ReturnType<typeof vi.fn>).mockResolvedValue(makeDetail('exec-1'));
  const wrapper = mount(WorkflowHistoryList);
  await flushPromises();
  return wrapper;
}

beforeEach(() => {
  vi.restoreAllMocks();
  (listExecutions as ReturnType<typeof vi.fn>).mockReset();
  (getExecution as ReturnType<typeof vi.fn>).mockReset();
});

describe('WorkflowHistoryList 加载与渲染', () => {
  it('onMounted 加载历史列表并渲染', async () => {
    const wrapper = await mountList([makeSummary()]);
    expect(listExecutions).toHaveBeenCalledTimes(1);
    expect(wrapper.text()).toContain('研究-分析-总结');
    expect(wrapper.text()).toContain('已完成');
    expect(wrapper.text()).toContain('这是最终结果摘要');
  });

  it('既有功能回归：PAUSED 记录显示恢复按钮', async () => {
    const wrapper = await mountList([makeSummary({ status: 'PAUSED' })]);
    const btn = wrapper.find('.btn-resume');
    expect(btn.exists()).toBe(true);
    expect(btn.text()).toContain('恢复');
  });
});

describe('WorkflowHistoryList 展开详情（手风琴）', () => {
  it('点击历史记录展开 ExecutionDetailPanel', async () => {
    const wrapper = await mountList([makeSummary()]);
    // 初始未展开
    expect(wrapper.findComponent(ExecutionDetailPanel).exists()).toBe(false);
    await wrapper.find('.history-item').trigger('click');
    await flushPromises();
    expect(wrapper.findComponent(ExecutionDetailPanel).exists()).toBe(true);
    // 展开后详情面板已通过 getExecution 加载数据
    expect(getExecution).toHaveBeenCalledWith('exec-1');
  });

  it('点击另一条记录时前一条收起（手风琴）', async () => {
    const wrapper = await mountList([
      makeSummary(),
      makeSummary({ executionId: 'exec-2', templateName: '模板 B' }),
    ]);
    const items = wrapper.findAll('.history-item');
    expect(items).toHaveLength(2);

    await items[0].trigger('click');
    await flushPromises();
    expect(wrapper.findAllComponents(ExecutionDetailPanel)).toHaveLength(1);

    // 点击第二条：前一条收起，后一条展开，同一时间仅一个详情面板
    await items[1].trigger('click');
    await flushPromises();
    expect(wrapper.findAllComponents(ExecutionDetailPanel)).toHaveLength(1);
  });

  it('再次点击已展开的记录 -> 收起详情面板', async () => {
    const wrapper = await mountList([makeSummary()]);
    const item = wrapper.find('.history-item');

    await item.trigger('click');
    await flushPromises();
    expect(wrapper.findComponent(ExecutionDetailPanel).exists()).toBe(true);

    await item.trigger('click');
    await flushPromises();
    expect(wrapper.findComponent(ExecutionDetailPanel).exists()).toBe(false);
  });

  it('PAUSED 记录恢复按钮点击 emit("resume") 且不触发展开（@click.stop）', async () => {
    const wrapper = await mountList([makeSummary({ status: 'PAUSED' })]);
    const btn = wrapper.find('.btn-resume');
    expect(btn.exists()).toBe(true);

    await btn.trigger('click');
    expect(wrapper.emitted('resume')).toBeTruthy();
    expect(wrapper.emitted('resume')![0]).toEqual(['exec-1']);
    // @click.stop 阻止冒泡：点击恢复不触发展开
    expect(wrapper.findComponent(ExecutionDetailPanel).exists()).toBe(false);
  });
});
