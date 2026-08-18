import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import WorkflowExecuteView from './WorkflowExecuteView.vue';
import { streamExecute, streamResume, terminate } from '@/api/workflow';
import type { WorkflowTemplateDetail, WorkflowStreamCallbacks } from '@/types';

/**
 * WorkflowExecuteView 组件测试（P2 Task-19，AC-004/005/006/008/009/010/022）
 * P3 扩展：暂停 UI 与恢复流程（Task-20，AC-016/AC-017）
 */

vi.mock('@/api/workflow', () => ({
  streamExecute: vi.fn(),
  streamResume: vi.fn(),
  terminate: vi.fn(),
}));

function makeDetail(overrides: Partial<WorkflowTemplateDetail> = {}): WorkflowTemplateDetail {
  return {
    id: 'tpl-loop',
    name: '质量评分-修订',
    description: '循环',
    mode: 'LOOP',
    maxRetries: 5,
    agents: [],
    parameters: [{ name: 'content', type: 'string', required: true, description: '初稿' }],
    loop: {
      maxIterations: 5,
      exitConditionDescription: '评分≥90退出',
      agents: [{ name: '评分 Agent', description: 'd', modelId: null, tools: [] }],
    },
    ...overrides,
  };
}

/** 捕获 streamExecute 传入的 callbacks */
function captureCallbacks(): WorkflowStreamCallbacks {
  const mock = streamExecute as ReturnType<typeof vi.fn>;
  return mock.mock.calls[0][3] as WorkflowStreamCallbacks;
}

beforeEach(() => {
  vi.restoreAllMocks();
});

describe('WorkflowExecuteView', () => {
  it('应渲染参数表单与执行按钮', () => {
    const wrapper = mount(WorkflowExecuteView, { props: { template: makeDetail() } });
    expect(wrapper.find('.param-input').exists()).toBe(true);
    expect(wrapper.text()).toContain('执行');
  });

  it('必填参数为空时点击执行不发起请求并提示', async () => {
    const wrapper = mount(WorkflowExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.btn-execute').trigger('click');
    expect(streamExecute).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('请填写必填参数');
  });

  it('填写参数后点击执行调用 streamExecute', async () => {
    (streamExecute as ReturnType<typeof vi.fn>).mockResolvedValue(undefined);
    const wrapper = mount(WorkflowExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.param-input').setValue('初稿内容');
    await wrapper.find('.btn-execute').trigger('click');
    expect(streamExecute).toHaveBeenCalledTimes(1);
    // 参数正确传递
    expect(streamExecute).toHaveBeenCalledWith('tpl-loop', { content: '初稿内容' }, '', expect.any(Object), expect.any(AbortSignal));
  });

  it('执行中按钮变为"停止"', async () => {
    (streamExecute as ReturnType<typeof vi.fn>).mockImplementation(async (_id, _p, _m, callbacks, _s) => {
      callbacks.onWorkflowStart({ executionId: 'e1', templateName: '质量评分-修订', mode: 'LOOP', agentCount: 1 });
    });
    const wrapper = mount(WorkflowExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.param-input').setValue('初稿');
    await wrapper.find('.btn-execute').trigger('click');
    await vi.waitFor(() => {
      expect(wrapper.find('.btn-stop').exists()).toBe(true);
    });
  });

  it('token 事件实时追加 Agent 输出（AC-009）', async () => {
    (streamExecute as ReturnType<typeof vi.fn>).mockImplementation(async (_id, _p, _m, callbacks) => {
      callbacks.onStepStart({ agentIndex: 0, agentName: '评分 Agent', totalAgents: 1 });
      callbacks.onToken({ agentIndex: 0, content: '评分' });
      callbacks.onToken({ agentIndex: 0, content: '：85' });
      callbacks.onStepComplete({ agentIndex: 0, agentName: '评分 Agent', durationMs: 100, outputLength: 5 });
      callbacks.onWorkflowComplete({ executionId: 'e1', finalResult: '最终结果', mode: 'LOOP', totalDurationMs: 200, iterationCount: 2 });
    });
    const wrapper = mount(WorkflowExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.param-input').setValue('初稿');
    await wrapper.find('.btn-execute').trigger('click');
    await vi.waitFor(() => {
      expect(wrapper.find('.panel-output').text()).toContain('评分：85');
    });
    // 最终结果显示
    expect(wrapper.find('.final-content').text()).toContain('最终结果');
    // 状态 COMPLETED
    expect(wrapper.text()).toContain('COMPLETED');
  });

  it('loop_iteration 事件更新模式进度提示（AC-006）', async () => {
    (streamExecute as ReturnType<typeof vi.fn>).mockImplementation(async (_id, _p, _m, callbacks) => {
      callbacks.onLoopIteration({ iteration: 2, maxIterations: 5, agentCount: 1 });
    });
    const wrapper = mount(WorkflowExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.param-input').setValue('初稿');
    await wrapper.find('.btn-execute').trigger('click');
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('第 2 轮');
    });
  });

  it('workflow_failed 事件显示失败状态（AC-010 异常）', async () => {
    (streamExecute as ReturnType<typeof vi.fn>).mockImplementation(async (_id, _p, _m, callbacks) => {
      callbacks.onWorkflowFailed({ executionId: 'e1', status: 'FAILED', error: 'Agent 执行失败' });
    });
    const wrapper = mount(WorkflowExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.param-input').setValue('初稿');
    await wrapper.find('.btn-execute').trigger('click');
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('FAILED');
    });
    expect(wrapper.text()).toContain('Agent 执行失败');
  });

  it('点击"停止"调用 abort（AC-022）', async () => {
    (streamExecute as ReturnType<typeof vi.fn>).mockImplementation(async () => {
      // 永不完成，保持执行中
    });
    const wrapper = mount(WorkflowExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.param-input').setValue('初稿');
    await wrapper.find('.btn-execute').trigger('click');
    await vi.waitFor(() => {
      expect(wrapper.find('.btn-stop').exists()).toBe(true);
    });
    // 捕获传入的 signal 并 abort
    const signal = (streamExecute as ReturnType<typeof vi.fn>).mock.calls[0][4] as AbortSignal;
    await wrapper.find('.btn-stop').trigger('click');
    expect(signal.aborted).toBe(true);
  });
});

describe('WorkflowExecuteView 暂停 UI 与恢复流程（P3 Task-20，AC-016/AC-017）', () => {
  /** 挂载并执行至暂停：workflow_start + step0 完成 + 步骤 1 失败暂停 */
  async function mountUntilPaused() {
    (streamExecute as ReturnType<typeof vi.fn>).mockImplementation(async (_id, _p, _m, callbacks) => {
      callbacks.onWorkflowStart({ executionId: 'exec-1', templateName: '质量评分-修订', mode: 'LOOP', agentCount: 2 });
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
    const wrapper = mount(WorkflowExecuteView, { props: { template: makeDetail() } });
    await wrapper.find('.param-input').setValue('初稿');
    await wrapper.find('.btn-execute').trigger('click');
    await vi.waitFor(() => {
      expect(wrapper.find('.paused-banner').exists()).toBe(true);
    });
    return wrapper;
  }

  /** 捕获 streamResume 传入的 callbacks（第 2 个参数） */
  function captureResumeCallbacks(): WorkflowStreamCallbacks {
    const mock = streamResume as ReturnType<typeof vi.fn>;
    return mock.mock.calls[0][1] as WorkflowStreamCallbacks;
  }

  it('workflow_paused 事件：状态栏显示"已暂停"，失败步骤面板红色高亮并显示错误信息（AC-016）', async () => {
    const wrapper = await mountUntilPaused();

    // 状态栏显示已暂停
    expect(wrapper.find('.paused-banner').text()).toContain('已暂停');
    // 显示失败 Agent 与错误信息
    expect(wrapper.find('.paused-banner').text()).toContain('修订 Agent');
    expect(wrapper.text()).toContain('Agent 执行失败: 修订 Agent: 超时');
    // failedIndex=1 的面板为 error 状态（红色高亮 class）
    const errorPanel = wrapper.findAll('.agent-panel')[1];
    expect(errorPanel.find('.panel-status').classes()).toContain('ps-error');
  });

  it('暂停态显示"恢复执行"与"终止"按钮，点击恢复调用 streamResume 且恢复中按钮禁用（AC-017）', async () => {
    const wrapper = await mountUntilPaused();

    expect(wrapper.find('.btn-resume').exists()).toBe(true);
    expect(wrapper.find('.btn-terminate').exists()).toBe(true);

    // streamResume 挂起（模拟恢复流进行中）
    let releaseResume!: () => void;
    (streamResume as ReturnType<typeof vi.fn>).mockImplementation(
      () => new Promise<void>((resolve) => { releaseResume = resolve; }),
    );
    await wrapper.find('.btn-resume').trigger('click');
    expect(streamResume).toHaveBeenCalledTimes(1);
    expect(streamResume).toHaveBeenCalledWith('exec-1', expect.any(Object), expect.any(AbortSignal));
    // 恢复中按钮禁用（防连点）
    expect((wrapper.find('.btn-resume').element as HTMLButtonElement).disabled).toBe(true);
    releaseResume();
    await vi.waitFor(() => {
      expect((wrapper.find('.btn-resume').element as HTMLButtonElement).disabled).toBe(false);
    });
  });

  it('恢复流 step_skipped：对应面板显示"已跳过（断点恢复）"标签且不进入运行态（AC-017）', async () => {
    const wrapper = await mountUntilPaused();

    (streamResume as ReturnType<typeof vi.fn>).mockImplementation(async (_id, callbacks) => {
      callbacks.onStepSkipped?.({ agentIndex: 0, agentName: '评分 Agent', reason: '断点恢复' });
    });
    await wrapper.find('.btn-resume').trigger('click');
    await vi.waitFor(() => {
      const panel = wrapper.findAll('.agent-panel')[0];
      expect(panel.text()).toContain('已跳过（断点恢复）');
    });
    // 跳过面板不进入运行态
    const statusEl = wrapper.findAll('.agent-panel')[0].find('.panel-status');
    expect(statusEl.classes()).toContain('ps-skipped');
    expect(statusEl.classes()).not.toContain('ps-running');
  });

  it('恢复流 step_start/step_complete：面板状态正常流转（AC-017）', async () => {
    const wrapper = await mountUntilPaused();

    (streamResume as ReturnType<typeof vi.fn>).mockImplementation(async (_id, callbacks) => {
      callbacks.onStepSkipped?.({ agentIndex: 0, agentName: '评分 Agent', reason: '断点恢复' });
      callbacks.onStepStart({ agentIndex: 1, agentName: '修订 Agent', totalAgents: 2 });
      callbacks.onToken({ agentIndex: 1, content: '修订输出' });
      callbacks.onStepComplete({ agentIndex: 1, agentName: '修订 Agent', durationMs: 50, outputLength: 4 });
    });
    await wrapper.find('.btn-resume').trigger('click');
    await vi.waitFor(() => {
      const panel = wrapper.findAll('.agent-panel')[1];
      expect(panel.find('.panel-status').classes()).toContain('ps-completed');
      expect(panel.find('.panel-output').text()).toContain('修订输出');
    });
  });

  it('恢复成功 workflow_complete：暂停态退出并显示完成（AC-017）', async () => {
    const wrapper = await mountUntilPaused();

    (streamResume as ReturnType<typeof vi.fn>).mockImplementation(async (_id, callbacks) => {
      callbacks.onStepSkipped?.({ agentIndex: 0, agentName: '评分 Agent', reason: '断点恢复' });
      callbacks.onWorkflowComplete({ executionId: 'exec-1', finalResult: '恢复后结果', mode: 'LOOP', totalDurationMs: 300 });
    });
    await wrapper.find('.btn-resume').trigger('click');
    await vi.waitFor(() => {
      expect(wrapper.find('.paused-banner').exists()).toBe(false);
      expect(wrapper.text()).toContain('COMPLETED');
    });
    expect(wrapper.find('.final-content').text()).toContain('恢复后结果');
  });

  it('恢复后再次 workflow_paused：再次进入暂停态（循环恢复 UI，AC-016）', async () => {
    const wrapper = await mountUntilPaused();

    (streamResume as ReturnType<typeof vi.fn>).mockImplementation(async (_id, callbacks) => {
      callbacks.onWorkflowPaused({
        executionId: 'exec-1',
        failedAgent: '修订 Agent',
        failedIndex: 1,
        error: 'Agent 执行失败: 修订 Agent: 再次超时',
        resumable: true,
      });
    });
    await wrapper.find('.btn-resume').trigger('click');
    await vi.waitFor(() => {
      expect(wrapper.find('.paused-banner').exists()).toBe(true);
      expect(wrapper.find('.paused-banner').text()).toContain('再次超时');
    });
  });

  it('传入 resumeExecutionId prop 时挂载自动发起 streamResume（历史页跳转恢复场景）', async () => {
    (streamResume as ReturnType<typeof vi.fn>).mockResolvedValue(undefined);
    mount(WorkflowExecuteView, {
      props: { template: makeDetail(), resumeExecutionId: 'exec-9' },
    });
    await vi.waitFor(() => {
      expect(streamResume).toHaveBeenCalledTimes(1);
    });
    expect(streamResume).toHaveBeenCalledWith('exec-9', expect.any(Object), expect.any(AbortSignal));
  });

  it('点击"终止"调用 terminate API 且暂停态退出（AC-022/AC-016）', async () => {
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
});

describe('WorkflowExecuteView Supervisor 子任务可视化（P3 Task-21，AC-007）', () => {
  function makeSupervisorDetail(): WorkflowTemplateDetail {
    const agent = (name: string) => ({ name, description: 'd', modelId: null, tools: [] });
    return {
      id: 'task-breakdown-supervisor',
      name: '任务拆解-执行-汇总',
      description: '层级编排',
      mode: 'SUPERVISOR',
      maxRetries: 3,
      agents: [],
      parameters: [{ name: 'task', type: 'string', required: true, description: '任务' }],
      supervisor: {
        maxSubtasks: 5,
        planAgent: agent('任务拆解(主控)'),
        workers: [agent('研究'), agent('分析'), agent('总结')],
        summarizeAgent: agent('综合汇总(主控)'),
      },
    };
  }

  /** 挂载 SUPERVISOR 模板并执行至主控拆解完成（supervisor_plan 已推送） */
  async function mountSupervisorWithPlan() {
    (streamExecute as ReturnType<typeof vi.fn>).mockImplementation(async (_id, _p, _m, callbacks) => {
      callbacks.onWorkflowStart({ executionId: 'exec-2', templateName: '任务拆解-执行-汇总', mode: 'SUPERVISOR', agentCount: 2 });
      // 主控拆解 token 流（agentIndex=0，独立面板）
      callbacks.onStepStart({ agentIndex: 0, agentName: '任务拆解(主控)', totalAgents: 2 });
      callbacks.onToken({ agentIndex: 0, content: '[{"id":1,...}]' });
      callbacks.onStepComplete({ agentIndex: 0, agentName: '任务拆解(主控)', durationMs: 100, outputLength: 20 });
      callbacks.onSupervisorPlan?.({
        subtasks: [
          { id: 1, description: '调研分类算法', agent: '研究', routedAgent: '研究', routed: true },
          { id: 2, description: '归纳报告', agent: '神秘角色', routedAgent: '研究', routed: false },
        ],
        totalSubtasks: 2,
      });
    });
    const wrapper = mount(WorkflowExecuteView, { props: { template: makeSupervisorDetail() } });
    await wrapper.find('.param-input').setValue('调研 Agent 分类算法');
    await wrapper.find('.btn-execute').trigger('click');
    await vi.waitFor(() => {
      expect(wrapper.findAll('.subtask-card')).toHaveLength(2);
    });
    return wrapper;
  }

  it('supervisor_plan 渲染 2 张子任务卡片，各含编号/描述/目标 Worker 名（AC-007）', async () => {
    const wrapper = await mountSupervisorWithPlan();

    const cards = wrapper.findAll('.subtask-card');
    expect(cards).toHaveLength(2);
    expect(cards[0].text()).toContain('调研分类算法');
    expect(cards[0].text()).toContain('研究');
    expect(cards[1].text()).toContain('归纳报告');
  });

  it('routed=false 的子任务卡片显示兜底标记（AC-007）', async () => {
    const wrapper = await mountSupervisorWithPlan();

    const cards = wrapper.findAll('.subtask-card');
    expect(cards[0].find('.fallback-badge').exists()).toBe(false);
    expect(cards[1].find('.fallback-badge').exists()).toBe(true);
  });

  it('supervisor_dispatch 高亮当前子任务，step_complete 后打勾并推进下一张（AC-007）', async () => {
    (streamExecute as ReturnType<typeof vi.fn>).mockImplementation(async (_id, _p, _m, callbacks) => {
      callbacks.onWorkflowStart({ executionId: 'exec-3', templateName: '任务拆解-执行-汇总', mode: 'SUPERVISOR', agentCount: 2 });
      callbacks.onSupervisorPlan?.({
        subtasks: [
          { id: 1, description: '调研分类算法', agent: '研究', routedAgent: '研究', routed: true },
          { id: 2, description: '归纳报告', agent: '总结', routedAgent: '总结', routed: true },
        ],
        totalSubtasks: 2,
      });
      callbacks.onSupervisorDispatch?.({ subtaskIndex: 1, totalSubtasks: 2, description: '调研分类算法', agentName: '研究', routed: true });
      callbacks.onStepStart({ agentIndex: 1, agentName: '研究', totalAgents: 2 });
      callbacks.onStepComplete({ agentIndex: 1, agentName: '研究', durationMs: 50, outputLength: 10 });
      callbacks.onSupervisorDispatch?.({ subtaskIndex: 2, totalSubtasks: 2, description: '归纳报告', agentName: '总结', routed: true });
    });
    const wrapper = mount(WorkflowExecuteView, { props: { template: makeSupervisorDetail() } });
    await wrapper.find('.param-input').setValue('调研分类算法');
    await wrapper.find('.btn-execute').trigger('click');
    await vi.waitFor(() => {
      const cards = wrapper.findAll('.subtask-card');
      // 第 1 张已完成打勾
      expect(cards[0].classes()).toContain('done');
      // dispatch 2 时第 2 张高亮进行中
      expect(cards[1].classes()).toContain('running');
    });
  });

  it('supervisor_dispatch(1) 使第 1 张卡片高亮进行中样式（AC-007）', async () => {
    (streamExecute as ReturnType<typeof vi.fn>).mockImplementation(async (_id, _p, _m, callbacks) => {
      callbacks.onWorkflowStart({ executionId: 'exec-4', templateName: '任务拆解-执行-汇总', mode: 'SUPERVISOR', agentCount: 2 });
      callbacks.onSupervisorPlan?.({
        subtasks: [
          { id: 1, description: '调研分类算法', agent: '研究', routedAgent: '研究', routed: true },
          { id: 2, description: '归纳报告', agent: '总结', routedAgent: '总结', routed: true },
        ],
        totalSubtasks: 2,
      });
      callbacks.onSupervisorDispatch?.({ subtaskIndex: 1, totalSubtasks: 2, description: '调研分类算法', agentName: '研究', routed: true });
    });
    const wrapper = mount(WorkflowExecuteView, { props: { template: makeSupervisorDetail() } });
    await wrapper.find('.param-input').setValue('调研');
    await wrapper.find('.btn-execute').trigger('click');
    await vi.waitFor(() => {
      const cards = wrapper.findAll('.subtask-card');
      expect(cards[0].classes()).toContain('running');
      expect(cards[1].classes()).not.toContain('running');
    });
  });

  it('supervisor_summary 显示"主控汇总中"状态（AC-007）', async () => {
    (streamExecute as ReturnType<typeof vi.fn>).mockImplementation(async (_id, _p, _m, callbacks) => {
      callbacks.onWorkflowStart({ executionId: 'exec-5', templateName: '任务拆解-执行-汇总', mode: 'SUPERVISOR', agentCount: 2 });
      callbacks.onSupervisorPlan?.({
        subtasks: [{ id: 1, description: '调研', agent: '研究', routedAgent: '研究', routed: true }],
        totalSubtasks: 1,
      });
      callbacks.onSupervisorSummary?.({ subtaskCount: 1 });
    });
    const wrapper = mount(WorkflowExecuteView, { props: { template: makeSupervisorDetail() } });
    await wrapper.find('.param-input').setValue('调研');
    await wrapper.find('.btn-execute').trigger('click');
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('主控汇总中');
    });
  });

  it('主控拆解（agentIndex=0）与汇总（agentIndex=N+1）token 输出渲染在独立面板（AC-007）', async () => {
    (streamExecute as ReturnType<typeof vi.fn>).mockImplementation(async (_id, _p, _m, callbacks) => {
      callbacks.onWorkflowStart({ executionId: 'exec-6', templateName: '任务拆解-执行-汇总', mode: 'SUPERVISOR', agentCount: 2 });
      // 主控拆解：agentIndex=0
      callbacks.onStepStart({ agentIndex: 0, agentName: '任务拆解(主控)', totalAgents: 2 });
      callbacks.onToken({ agentIndex: 0, content: '拆解思考' });
      callbacks.onStepComplete({ agentIndex: 0, agentName: '任务拆解(主控)', durationMs: 100, outputLength: 4 });
      callbacks.onSupervisorPlan?.({
        subtasks: [
          { id: 1, description: '调研', agent: '研究', routedAgent: '研究', routed: true },
          { id: 2, description: '归纳', agent: '总结', routedAgent: '总结', routed: true },
        ],
        totalSubtasks: 2,
      });
      // 子任务 1/2：agentIndex=1/2
      callbacks.onSupervisorDispatch?.({ subtaskIndex: 1, totalSubtasks: 2, description: '调研', agentName: '研究', routed: true });
      callbacks.onStepStart({ agentIndex: 1, agentName: '研究', totalAgents: 2 });
      callbacks.onStepComplete({ agentIndex: 1, agentName: '研究', durationMs: 50, outputLength: 10 });
      callbacks.onSupervisorDispatch?.({ subtaskIndex: 2, totalSubtasks: 2, description: '归纳', agentName: '总结', routed: true });
      callbacks.onStepStart({ agentIndex: 2, agentName: '总结', totalAgents: 2 });
      callbacks.onStepComplete({ agentIndex: 2, agentName: '总结', durationMs: 50, outputLength: 10 });
      // 主控汇总：agentIndex=N+1=3
      callbacks.onSupervisorSummary?.({ subtaskCount: 2 });
      callbacks.onStepStart({ agentIndex: 3, agentName: '综合汇总(主控)', totalAgents: 2 });
      callbacks.onToken({ agentIndex: 3, content: '汇总输出' });
    });
    const wrapper = mount(WorkflowExecuteView, { props: { template: makeSupervisorDetail() } });
    await wrapper.find('.param-input').setValue('调研');
    await wrapper.find('.btn-execute').trigger('click');
    await vi.waitFor(() => {
      const panels = wrapper.findAll('.agent-panel');
      // 面板 0 = 拆解主控，1/2 = Worker 子任务，3 = 汇总主控，互不混淆
      expect(panels[0].text()).toContain('任务拆解(主控)');
      expect(panels[0].text()).toContain('拆解思考');
      expect(panels[1].text()).toContain('研究');
      expect(panels[2].text()).toContain('总结');
      expect(panels[3].text()).toContain('综合汇总(主控)');
      expect(panels[3].text()).toContain('汇总输出');
    });
    // 子任务卡片与主控面板分离（卡片区独立于 agent-panel）
    expect(wrapper.findAll('.subtask-card')).toHaveLength(2);
  });
});
