import { ref } from 'vue';
import type { AskUserData, SupervisorSubtaskItem, WorkflowStreamCallbacks } from '@/types';
import { replyToWorkflow, streamExecute, streamResume, terminate } from '@/api/workflow';

/**
 * 工作流流式执行 composable（CR-001 Task-24，AC-033 前置）
 * 业务含义：从 WorkflowExecuteView 提取的 SSE 事件处理 + 状态管理 + 暂停/恢复逻辑，
 * 供 5 个模式执行视图组件共享调用，避免各组件重复实现。
 */

/** Agent 输出面板状态（P3 新增 skipped：断点恢复跳过态） */
export interface AgentOutput {
  agentIndex: number;
  agentName: string;
  output: string;
  status: 'pending' | 'running' | 'completed' | 'error' | 'skipped';
  durationMs: number;
}

/** Supervisor 子任务卡片（supervisor_plan 初始化，dispatch/complete 推进状态） */
export interface SupervisorCard extends SupervisorSubtaskItem {
  status: 'pending' | 'running' | 'completed';
}

export function useWorkflowStream() {
  /** 是否正在执行 */
  const isExecuting = ref(false);
  /** 是否处于暂停待恢复态 */
  const isPaused = ref(false);
  /** Agent 输出面板列表 */
  const agentOutputs = ref<AgentOutput[]>([]);
  /** 最终结果 */
  const finalResult = ref('');
  /** 错误信息 */
  const error = ref('');
  /** 模式进度提示（如"第 2 轮/共 5 轮"） */
  const modeProgress = ref('');
  /** 状态标签（COMPLETED/FAILED/TERMINATED/TIMEOUT） */
  const statusLabel = ref('');
  /** 当前执行 ID（workflow_start/paused 事件获取，恢复与终止依赖） */
  const currentExecutionId = ref('');
  /** 恢复流进行中（防连点） */
  const isResuming = ref(false);
  /** 子任务卡片列表 */
  const supervisorCards = ref<SupervisorCard[]>([]);
  /** 主控汇总阶段进行中 */
  const isSummarizing = ref(false);
  /** 暂停时的失败 Agent 名 */
  const pausedAgentName = ref('');
  /** 暂停时的失败步骤下标 */
  const pausedAgentIndex = ref(-1);
  /** 暂停原因 */
  const pausedError = ref('');
  /** 是否等待用户回复（HITL，Task-12） */
  const isWaitingUser = ref(false);
  /** HITL 暂停模式（askUser=Agent 追问；checkpoint=预设检查点；toolConfirm=ask 级工具确认，Task-17） */
  const waitingHitlMode = ref<'askUser' | 'checkpoint' | 'toolConfirm'>('askUser');
  /** 等待回复的 Agent 名（workflow_waiting 事件） */
  const waitingAgentName = ref('');
  /** askUser 提问数据（ask_user 事件；供 AskUserCard 渲染） */
  const askUserData = ref<AskUserData | null>(null);
  /** ask 级工具确认数据（tool_confirm 事件；供 ConfirmCard 渲染，Task-17） */
  const toolConfirmData = ref<{
    agentIndex: number;
    agentName: string;
    toolName: string;
    toolDescription: string;
    arguments: string;
  } | null>(null);

  let abortController: AbortController | null = null;

  /** 初始化 Agent 面板（按传入的 agent 名列表创建 pending 面板） */
  function initAgents(agents: { name: string }[]): void {
    agentOutputs.value = agents.map((a, idx) => ({
      agentIndex: idx,
      agentName: a.name,
      output: '',
      status: 'pending' as const,
      durationMs: 0,
    }));
  }

  /**
   * 查找面板；不存在时动态创建（恢复场景事件先于面板初始化到达，
   * 历史页跳转恢复时 panels 为空，由事件流重建进度，AC-017）
   */
  function ensurePanel(agentIndex: number, agentName?: string): AgentOutput {
    let panel = agentOutputs.value.find((p) => p.agentIndex === agentIndex);
    if (!panel) {
      panel = {
        agentIndex,
        agentName: agentName ?? `步骤 ${agentIndex + 1}`,
        output: '',
        status: 'pending',
        durationMs: 0,
      };
      agentOutputs.value.push(panel);
    }
    return panel;
  }

  /** 构建事件回调（streamExecute/streamResume 共用：恢复流继续追加输出，进度由事件重建） */
  function buildCallbacks(): WorkflowStreamCallbacks {
    return {
      onWorkflowStart: (data) => {
        currentExecutionId.value = data.executionId;
        modeProgress.value = `${data.mode} 模式 · ${data.agentCount} 个 Agent`;
      },
      onStepStart: (data) => {
        const panel = ensurePanel(data.agentIndex, data.agentName);
        panel.status = 'running';
        panel.agentName = data.agentName;
      },
      onToken: (data) => {
        const panel = ensurePanel(data.agentIndex, '');
        panel.output += data.content;
      },
      onStepComplete: (data) => {
        const panel = ensurePanel(data.agentIndex, data.agentName);
        panel.status = 'completed';
        panel.durationMs = data.durationMs;
        // Supervisor 子任务完成打勾（agentIndex 与 subtaskIndex 一致，AC-007）
        const card = supervisorCards.value[data.agentIndex - 1];
        if (card && card.status === 'running') card.status = 'completed';
      },
      onStepRetry: () => {},
      onStepError: (data) => {
        const panel = ensurePanel(data.agentIndex, `步骤 ${data.agentIndex + 1}`);
        panel.status = 'error';
        panel.output += `\n[执行失败] ${data.error}`;
      },
      onBranchSelected: (data) => {
        modeProgress.value = `已选择分支：${data.branchName}`;
      },
      onLoopIteration: (data) => {
        modeProgress.value = `第 ${data.iteration} 轮 / 共 ${data.maxIterations} 轮`;
      },
      onGroupStart: (data) => {
        modeProgress.value = `分组 ${data.groupName} 开始`;
      },
      onGroupComplete: () => {},
      onWorkflowComplete: (data) => {
        finalResult.value = data.finalResult;
        statusLabel.value = 'COMPLETED';
        isExecuting.value = false;
        isPaused.value = false;
        isResuming.value = false;
        isSummarizing.value = false;
        isWaitingUser.value = false;
        modeProgress.value = data.exitReason ? `退出原因：${data.exitReason}` : '';
      },
      onWorkflowFailed: (data) => {
        statusLabel.value = data.status;
        error.value = data.error || '执行失败';
        isExecuting.value = false;
        isResuming.value = false;
        isWaitingUser.value = false;
      },
      onError: (message) => {
        error.value = message;
        isExecuting.value = false;
        isResuming.value = false;
        isWaitingUser.value = false;
      },
      // ===== P3 新增回调 =====
      onWorkflowPaused: (data) => {
        isPaused.value = true;
        currentExecutionId.value = data.executionId;
        pausedAgentName.value = data.failedAgent;
        pausedAgentIndex.value = data.failedIndex;
        pausedError.value = data.error;
        const panel = ensurePanel(data.failedIndex, data.failedAgent);
        panel.status = 'error';
        panel.output += `\n[暂停] ${data.error}`;
        isExecuting.value = false;
        isResuming.value = false;
      },
      onStepSkipped: (data) => {
        const panel = ensurePanel(data.agentIndex, data.agentName);
        panel.status = 'skipped';
        panel.agentName = data.agentName;
      },
      onSupervisorPlan: (data) => {
        supervisorCards.value = data.subtasks.map((s) => ({ ...s, status: 'pending' as const }));
      },
      onSupervisorDispatch: (data) => {
        const card = supervisorCards.value[data.subtaskIndex - 1];
        if (card) card.status = 'running';
      },
      onSupervisorSummary: () => {
        isSummarizing.value = true;
      },
      // ===== 工作流 HITL 新增回调（Task-12）=====
      onAskUser: (data) => {
        // 业务含义：Agent 暂停前推送提问数据，组装为 AskUserCard 可渲染的形态
        // （后端 type 为 "text"/"confirm"，options 为空列表时确认型选项不展示）
        askUserData.value = {
          type: (data.type === 'confirm' ? 'confirm' : 'text') as 'text' | 'confirm',
          kind: 'askUser',
          question: data.question || '',
          options: data.options ?? [],
          retryCount: data.retryCount ?? 0,
        };
      },
      onWorkflowWaiting: (data) => {
        // 业务含义：工作流进入 WAITING_USER 等待用户回复，SSE 流随之结束；
        // 置等待态（执行结束 + 横幅显示），等待用户通过 replyToHitl 回复
        isWaitingUser.value = true;
        waitingHitlMode.value = data.hitlMode;
        waitingAgentName.value = data.agentName;
        currentExecutionId.value = data.executionId;
        isExecuting.value = false;
        isResuming.value = false;
      },
      onToolConfirm: (data) => {
        // 业务含义：ask 级工具被 Agent 调用触发权限拦截，后端推送工具四要素 +
        // 暂停步骤，填充 ConfirmCard 渲染数据；与 workflow_waiting(hitlMode=toolConfirm) 成对出现
        toolConfirmData.value = {
          agentIndex: data.agentIndex,
          agentName: data.agentName,
          toolName: data.toolName,
          toolDescription: data.toolDescription,
          arguments: data.arguments,
        };
      },
      onWorkflowResumed: () => {
        // 业务含义：用户回复后执行恢复，隐藏等待横幅（isExecuting 由回复时置 true）
        isWaitingUser.value = false;
        askUserData.value = null;
        toolConfirmData.value = null;
      },
    };
  }

  /** 开始执行 */
  async function startExecution(
    templateId: string,
    params: Record<string, unknown>,
    modelId: string = '',
  ): Promise<void> {
    error.value = '';
    finalResult.value = '';
    statusLabel.value = '';
    modeProgress.value = '';
    isExecuting.value = true;
    isPaused.value = false;
    // 业务含义：重置 HITL 等待状态，避免上一次执行的等待横幅残留导致新执行卡住
    isWaitingUser.value = false;
    waitingHitlMode.value = 'askUser';
    waitingAgentName.value = '';
    askUserData.value = null;
    toolConfirmData.value = null;

    abortController = new AbortController();
    await streamExecute(templateId, params, modelId, buildCallbacks(), abortController.signal);
  }

  /**
   * 恢复执行（P3 AC-017）
   * 业务含义：对 PAUSED 执行发起新 SSE 流，后端跳过已完成步骤（step_skipped）
   * 并从断点继续；恢复中禁用按钮防连点；再次暂停由 onWorkflowPaused 处理（循环恢复）。
   */
  async function resumeExecution(): Promise<void> {
    if (!currentExecutionId.value || isResuming.value) return;
    isResuming.value = true;
    isPaused.value = false;
    isExecuting.value = true;
    error.value = '';
    abortController = new AbortController();
    await streamResume(currentExecutionId.value, buildCallbacks(), abortController.signal);
    isResuming.value = false;
  }

  /**
   * 暂停态终止（P3 AC-022/AC-016）
   * 业务含义：调用后端 terminate 将 PAUSED 执行终态化并清理恢复快照。
   */
  async function terminateExecution(): Promise<void> {
    if (!currentExecutionId.value || isResuming.value) return;
    try {
      await terminate(currentExecutionId.value);
    } catch {
      error.value = '终止执行失败';
      return;
    }
    isPaused.value = false;
    isExecuting.value = false;
    isWaitingUser.value = false;
    statusLabel.value = 'TERMINATED';
  }

  /**
   * 回复 HITL 暂停并恢复执行（工作流 HITL，Task-12，AC-N01/AC-N03）
   * 业务含义：等待态下用户通过交互卡片回复（askUser 传 message / checkpoint 传 approved），
   * 发起 hitl-reply 新 SSE 流；若存在并行排队 HITL，后端会先推送下一条
   * workflow_waiting 使横幅重新出现，否则推送 workflow_resumed 恢复正常执行。
   * 回复发起即置 isExecuting 防连点；等待态 isExecuting 为 false 才可进入。
   */
  async function replyToHitl(message: string | null, approved: boolean | null): Promise<void> {
    if (!currentExecutionId.value || isExecuting.value) return;
    isExecuting.value = true;
    isWaitingUser.value = false;
    error.value = '';
    abortController = new AbortController();
    await replyToWorkflow(currentExecutionId.value, message, approved, buildCallbacks(), abortController.signal);
  }

  /** 停止执行（AC-022，主动 abort） */
  function stopExecution(): void {
    if (abortController) {
      abortController.abort();
      abortController = null;
      isExecuting.value = false;
      error.value = '已停止执行';
    }
  }

  return {
    // 状态
    isExecuting,
    isPaused,
    agentOutputs,
    finalResult,
    error,
    modeProgress,
    statusLabel,
    currentExecutionId,
    isResuming,
    supervisorCards,
    isSummarizing,
    pausedAgentName,
    pausedAgentIndex,
    pausedError,
    isWaitingUser,
    waitingHitlMode,
    waitingAgentName,
    askUserData,
    toolConfirmData,
    // 方法
    initAgents,
    startExecution,
    resumeExecution,
    terminateExecution,
    replyToHitl,
    stopExecution,
  };
}
