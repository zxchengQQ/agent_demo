import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import SupervisorExecuteView from '../SupervisorExecuteView.vue';
import { streamExecute, streamResume, terminate } from '@/api/workflow';
import type { WorkflowTemplateDetail } from '@/types';

/**
 * SupervisorExecuteView 组件测试（CR-001 Task-29，Supervisor 模式专属执行视图）
 * 验证标准（AC-007/AC-031）：
 * 1. 收到 supervisor_plan 事件后渲染子任务卡片列表（id/description/agent 名）
 * 2. routed=false 的子任务卡片显示兜底标记
 * 3. 收到 supervisor_dispatch 后对应子任务卡片高亮进行中
 * 4. 子任务 step_complete 后卡片打勾
 * 5. 收到 supervisor_summary 后显示"主控汇总中"状态
 * 6. 主控拆解（agentIndex=0）与汇总（agentIndex=N+1）token 输出在独立面板
 * 7. 布局与 ConditionalExecuteView 有显著差异（断言子任务卡片元素存在，无分岔路径）
 * 8. 暂停时失败步骤红色高亮 + 恢复/终止按钮（复用 useWorkflowStream）
 * 9. 参数表单按模板定义动态生成
 * 10. 必填参数校验
 * 11. 执行/停止按钮
 * 12. 传入 resumeExecutionId prop 时挂载自动发起 streamResume
 */

vi.mock('@/api/workflow', () => ({
  streamExecute: vi.fn(),
  streamResume: vi.fn(),
  terminate: vi.fn(),
}));

/** 构造 SUPERVISOR 模式模板详情（主控拆解 + 3 个 Worker + 主控汇总） */
function makeDetail(overrides: Partial<WorkflowTemplateDetail> = {}): WorkflowTemplateDetail {
  const agent = (name: string) => ({ name, description: 'd', modelId: null, tools: [] });
  return {
    id: 'tpl-sup',
    name: '任务拆解-执行-汇总',
    description: 'Supervisor 编排',
    mode: 'SUPERVISOR',
    maxRetries: 3,
    agents: [],
    parameters: [{ name: 'task', type: 'string', required: true, description: '任务描述' }],
    supervisor: {
      maxSubtasks: 5,
      planAgent: agent('主控拆解'),
      workers: [agent('Worker-A'), agent('Worker-B'), agent('Worker-C')],
      summarizeAgent: agent('主控汇总'),
    },
    ...overrides,
  };
}

/** 构造两子任务的 supervisor_plan 事件数据 */
const planData = {
  subtasks: [
    { id: 1, description: '子任务1', agent: 'Worker-A', routedAgent: 'Worker-A', routed: true },
    { id: 2, description: '子任务2', agent: 'Worker-X', routedAgent: 'Worker-B', routed: false },
  ],
  totalSubtasks: 2,
};

beforeEach(() => {
  vi.restoreAllMocks();
});

describe('SupervisorExecuteView 布局', () => {
  it('接收 SUPERVISOR 模板后渲染子任务卡片列表容器', () => {
    const wrapper = mount(SupervisorExecuteView, { props: { template: makeDetail() } });
    expect(wrapper.find('.subtask-list').exists()).toBe(true);
  });

  it('布局与 ConditionalExecuteView 有显著差异（子任务卡片元素存在，无分岔路径）', () => {
    const wrapper = mount(SupervisorExecuteView, { props: { template: makeDetail() } });
    // Supervisor 视图标志性布局元素：子任务卡片列表
    expect(wrapper.find('.subtask-list').exists()).toBe(true);
    // 不存在条件分支视图的分岔路径
    expect(wrapper.find('.branch-map').exists()).toBe(false);
    expect(wrapper.find('.branch-path').exists()).toBe(false);
    // 不存在循环视图的时间线
    expect(wrapper.find('.loop-timeline').exists()).toBe(false);
    expect(wrapper.find('.timeline-node').exists()).toBe(false);
  });

  it('参数表单按模板定义动态生成', () => {
    const wrapper = mount(SupervisorExecuteView, { props: { template: makeDetail() } });
    const inputs = wrapper.findAll('.param-input');
    expect(inputs).toHaveLength(1);
    expect(wrapper.text()).toContain('任务描述');
  });

  it('渲染主控拆解面板和主控汇总面板', () => {
    const wrapper = mount(SupervisorExecuteView, { props: { template: makeDetail() } });
    expect(wrapper.find('.supervisor-plan-panel').exists()).toBe(true);
    expect(wrapper.find('.supervisor-summary-panel').exists()).toBe(true);
    expect(wrapper.find('.supervisor-plan-panel').text()).toContain('主控拆解');
    expect(wrapper.find('.supervisor-summary-panel').text()).toContain('主控汇总');
  });
});

describe('SupervisorExecuteView 子任务卡片', () => {
  it('收到 supervisor_plan 事件后渲染子任务卡片列表（id/description/agent 名）', async () => {
    (streamExecute as ReturnType<typeof vi.fn>).mockImplementation(async (_id, _p, _m, callbacks) => {
      callbacks.onSupervisorPlan(planData);
    });
    const wrapper = mount(SupervisorExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.param-input').setValue('任务内容');
    await wrapper.find('.btn-execute').trigger('click');
    await vi.waitFor(() => {
      const cards = wrapper.findAll('.subtask-card');
      expect(cards).toHaveLength(2);
      // 每张卡片含 id / description / agent 名（实际路由的 Worker）
      expect(cards[0].text()).toContain('#1');
      expect(cards[0].text()).toContain('子任务1');
      expect(cards[0].text()).toContain('Worker-A');
      expect(cards[1].text()).toContain('#2');
      expect(cards[1].text()).toContain('子任务2');
      expect(cards[1].text()).toContain('Worker-B');
    });
  });

  it('routed=false 的子任务卡片显示兜底标记', async () => {
    (streamExecute as ReturnType<typeof vi.fn>).mockImplementation(async (_id, _p, _m, callbacks) => {
      callbacks.onSupervisorPlan(planData);
    });
    const wrapper = mount(SupervisorExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.param-input').setValue('任务内容');
    await wrapper.find('.btn-execute').trigger('click');
    await vi.waitFor(() => {
      const badges = wrapper.findAll('.fallback-badge');
      expect(badges).toHaveLength(1);
      // 兜底标记位于第二张卡片（routed=false）
      expect(wrapper.findAll('.subtask-card')[1].find('.fallback-badge').exists()).toBe(true);
      expect(wrapper.findAll('.subtask-card')[0].find('.fallback-badge').exists()).toBe(false);
    });
  });

  it('收到 supervisor_dispatch 后对应子任务卡片高亮进行中', async () => {
    (streamExecute as ReturnType<typeof vi.fn>).mockImplementation(async (_id, _p, _m, callbacks) => {
      callbacks.onSupervisorPlan(planData);
      callbacks.onSupervisorDispatch({
        subtaskIndex: 1,
        totalSubtasks: 2,
        description: '子任务1',
        agentName: 'Worker-A',
        routed: true,
      });
    });
    const wrapper = mount(SupervisorExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.param-input').setValue('任务内容');
    await wrapper.find('.btn-execute').trigger('click');
    await vi.waitFor(() => {
      const cards = wrapper.findAll('.subtask-card');
      // 第 1 张卡片（subtaskIndex=1）高亮进行中
      expect(cards[0].classes()).toContain('running');
      expect(cards[1].classes()).toContain('pending');
    });
  });

  it('子任务 step_complete 后卡片打勾', async () => {
    (streamExecute as ReturnType<typeof vi.fn>).mockImplementation(async (_id, _p, _m, callbacks) => {
      callbacks.onSupervisorPlan(planData);
      callbacks.onSupervisorDispatch({
        subtaskIndex: 1,
        totalSubtasks: 2,
        description: '子任务1',
        agentName: 'Worker-A',
        routed: true,
      });
      callbacks.onStepComplete({ agentIndex: 1, agentName: 'Worker-A', durationMs: 100, outputLength: 50 });
    });
    const wrapper = mount(SupervisorExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.param-input').setValue('任务内容');
    await wrapper.find('.btn-execute').trigger('click');
    await vi.waitFor(() => {
      const cards = wrapper.findAll('.subtask-card');
      expect(cards[0].classes()).toContain('completed');
      expect(cards[0].text()).toContain('✓');
    });
  });

  it('收到 supervisor_summary 后显示"主控汇总中"状态', async () => {
    (streamExecute as ReturnType<typeof vi.fn>).mockImplementation(async (_id, _p, _m, callbacks) => {
      callbacks.onSupervisorPlan(planData);
      callbacks.onSupervisorSummary({ subtaskCount: 2 });
    });
    const wrapper = mount(SupervisorExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.param-input').setValue('任务内容');
    await wrapper.find('.btn-execute').trigger('click');
    await vi.waitFor(() => {
      expect(wrapper.find('.summarizing-status').exists()).toBe(true);
      expect(wrapper.find('.summarizing-status').text()).toContain('主控汇总中');
    });
  });
});

describe('SupervisorExecuteView 主控面板', () => {
  it('主控拆解（agentIndex=0）token 输出在独立面板', async () => {
    (streamExecute as ReturnType<typeof vi.fn>).mockImplementation(async (_id, _p, _m, callbacks) => {
      callbacks.onSupervisorPlan(planData);
      callbacks.onToken({ agentIndex: 0, content: '拆解计划...' });
    });
    const wrapper = mount(SupervisorExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.param-input').setValue('任务内容');
    await wrapper.find('.btn-execute').trigger('click');
    await vi.waitFor(() => {
      expect(wrapper.find('.supervisor-plan-panel').text()).toContain('拆解计划...');
    });
  });

  it('主控汇总（agentIndex=最后一个）token 输出在独立面板', async () => {
    (streamExecute as ReturnType<typeof vi.fn>).mockImplementation(async (_id, _p, _m, callbacks) => {
      callbacks.onSupervisorPlan(planData);
      callbacks.onToken({ agentIndex: 4, content: '最终汇总...' });
    });
    const wrapper = mount(SupervisorExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.param-input').setValue('任务内容');
    await wrapper.find('.btn-execute').trigger('click');
    await vi.waitFor(() => {
      expect(wrapper.find('.supervisor-summary-panel').text()).toContain('最终汇总...');
    });
  });
});

describe('SupervisorExecuteView 执行流程', () => {
  it('必填参数为空时点击执行不发起请求并提示', async () => {
    const wrapper = mount(SupervisorExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.btn-execute').trigger('click');
    expect(streamExecute).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('请填写必填参数');
  });

  it('填写参数后点击执行调用 streamExecute', async () => {
    (streamExecute as ReturnType<typeof vi.fn>).mockResolvedValue(undefined);
    const wrapper = mount(SupervisorExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.param-input').setValue('任务内容');
    await wrapper.find('.btn-execute').trigger('click');
    expect(streamExecute).toHaveBeenCalledTimes(1);
    expect(streamExecute).toHaveBeenCalledWith('tpl-sup', { task: '任务内容' }, '', expect.any(Object), expect.any(AbortSignal));
  });

  it('点击"停止"调用 abort', async () => {
    (streamExecute as ReturnType<typeof vi.fn>).mockImplementation(async () => {
      // 永不完成，保持执行中
    });
    const wrapper = mount(SupervisorExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.param-input').setValue('任务内容');
    await wrapper.find('.btn-execute').trigger('click');
    await vi.waitFor(() => {
      expect(wrapper.find('.btn-stop').exists()).toBe(true);
    });
    const signal = (streamExecute as ReturnType<typeof vi.fn>).mock.calls[0][4] as AbortSignal;
    await wrapper.find('.btn-stop').trigger('click');
    expect(signal.aborted).toBe(true);
  });
});

describe('SupervisorExecuteView 暂停与恢复', () => {
  /** 挂载并执行至暂停：supervisor_plan 后 Worker-B 子任务失败暂停 */
  async function mountUntilPaused() {
    (streamExecute as ReturnType<typeof vi.fn>).mockImplementation(async (_id, _p, _m, callbacks) => {
      callbacks.onWorkflowStart({ executionId: 'exec-1', templateName: '任务拆解-执行-汇总', mode: 'SUPERVISOR', agentCount: 5 });
      callbacks.onSupervisorPlan(planData);
      callbacks.onWorkflowPaused({
        executionId: 'exec-1',
        failedAgent: 'Worker-B',
        failedIndex: 2,
        error: 'Agent 执行失败: Worker-B: 超时',
        resumable: true,
      });
    });
    const wrapper = mount(SupervisorExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.param-input').setValue('任务内容');
    await wrapper.find('.btn-execute').trigger('click');
    await vi.waitFor(() => {
      expect(wrapper.find('.paused-banner').exists()).toBe(true);
    });
    return wrapper;
  }

  it('暂停时显示恢复/终止按钮 + 暂停横幅（失败步骤红色高亮）', async () => {
    const wrapper = await mountUntilPaused();
    expect(wrapper.find('.paused-banner').text()).toContain('已暂停');
    expect(wrapper.find('.paused-banner').text()).toContain('Worker-B');
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
    mount(SupervisorExecuteView, {
      props: { template: makeDetail(), resumeExecutionId: 'exec-9' },
    });
    await vi.waitFor(() => {
      expect(streamResume).toHaveBeenCalledTimes(1);
    });
    expect(streamResume).toHaveBeenCalledWith('exec-9', expect.any(Object), expect.any(AbortSignal));
  });
});
