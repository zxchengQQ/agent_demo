<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import type { WorkflowTemplateDetail } from '@/types';
import { useWorkflowStream } from '@/composables/useWorkflowStream';

/**
 * Supervisor 模式专属执行视图（CR-001 Task-29，整合 P3 Task-21，AC-007）
 * 业务含义：以"主控拆解 → 子任务卡片列表 → 主控汇总"三段式布局展示层级编排：
 * - 主控拆解面板（agentIndex=0）：planAgent 流式输出拆解计划
 * - 子任务卡片列表：supervisor_plan 初始化卡片，supervisor_dispatch 高亮进行中，
 *   step_complete 打勾，routed=false 显示兜底标记
 * - 主控汇总面板（agentIndex=N+1，最后一个 agentOutput）：summarizeAgent 输出最终汇总
 * 暂停/恢复/终止、参数表单与执行逻辑复用 useWorkflowStream composable。
 */

const props = defineProps<{
  /** 选中的模板详情 */
  template: WorkflowTemplateDetail;
  /** 待恢复的执行 ID（历史页跳转传入，挂载自动发起恢复） */
  resumeExecutionId?: string;
}>();

const emit = defineEmits<{
  /** 返回模板列表 */
  back: [];
}>();

const {
  isExecuting,
  isPaused: isPausedRaw,
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
  initAgents,
  startExecution: startStream,
  resumeExecution: resumeStream,
  terminateExecution,
  stopExecution: stopStream,
} = useWorkflowStream();

/**
 * 暂停态视图粘性标志
 * 业务含义：composable 在发起恢复时会将 isPaused 置 false，但视图需保留
 * "恢复/终止"入口直至终态事件到达。故组件本地维护粘性标志：
 * 收到暂停事件置 true，收到终态状态置 false。
 */
const pausedUI = ref(false);
watch(isPausedRaw, (v) => {
  if (v) pausedUI.value = true;
});
watch(statusLabel, (v) => {
  if (v === 'COMPLETED' || v === 'FAILED' || v === 'TERMINATED' || v === 'TIMEOUT') {
    pausedUI.value = false;
  }
});
/** 暂停态（含恢复期间粘性保留） */
const isPaused = computed(() => isPausedRaw.value || pausedUI.value);

/** Supervisor 编排定义（模板详情模式专属字段） */
const supervisor = computed(() => props.template.supervisor);

/** 参数表单值 */
const params = ref<Record<string, string>>({});

/** 初始化表单默认值 */
function initForm() {
  params.value = {};
  for (const p of props.template.parameters ?? []) {
    params.value[p.name] = '';
  }
}
initForm();

/** 必填参数校验 */
function validateForm(): string {
  for (const p of props.template.parameters ?? []) {
    if (p.required && !(params.value[p.name] ?? '').trim()) {
      return `请填写必填参数：${p.name}`;
    }
  }
  return '';
}

/**
 * 收集 Supervisor Agent 面板列表
 * 业务含义：主控拆解（agentIndex=0）+ Worker 池（agentIndex=1..N）+ 主控汇总（最后一个）。
 * 实际子任务数由 supervisor_plan 事件决定，因此初始面板按"最大子任务数"展开
 * （planAgent + workers + summarizeAgent），子任务 token 输出按 agentIndex 命中对应面板。
 */
function collectAgents(): { name: string }[] {
  const sup = props.template.supervisor;
  if (!sup) return [];
  const list: { name: string }[] = [{ name: sup.planAgent.name }];
  for (const w of sup.workers) list.push({ name: w.name });
  list.push({ name: sup.summarizeAgent.name });
  return list;
}

/** 主控汇总面板对应的 agentIndex（最后一个 agentOutput，即 summarizeAgent） */
const summarizeIndex = computed(() => agentOutputs.value.length - 1);

/** 主控拆解面板输出（agentIndex=0 累积的 token） */
const planOutput = computed(() => agentOutputs.value[0]?.output || '（等待拆解…）');

/** 主控汇总面板输出（最后一个 agentOutput 累积的 token） */
const summarizeOutput = computed(() => {
  const idx = summarizeIndex.value;
  return idx >= 0 ? (agentOutputs.value[idx]?.output || '（等待汇总…）') : '（等待汇总…）';
});

/** 开始执行：表单校验通过后初始化面板并发起流式执行 */
async function startExecution() {
  const err = validateForm();
  if (err) {
    error.value = err;
    return;
  }
  initAgents(collectAgents());
  const parameters: Record<string, unknown> = { ...params.value };
  await startStream(props.template.id, parameters, '');
}

/** 恢复执行（对 PAUSED 执行发起新 SSE 流） */
async function resumeExecution() {
  await resumeStream();
}

/** 停止执行（主动 abort） */
function stopExecution() {
  stopStream();
}

/** 挂载自动恢复（历史页跳转场景） */
onMounted(() => {
  if (props.resumeExecutionId) {
    currentExecutionId.value = props.resumeExecutionId;
    resumeStream();
  }
});

/** 状态标签 CSS 类 */
const statusClass = computed(() => {
  switch (statusLabel.value) {
    case 'COMPLETED': return 'status-completed';
    case 'FAILED': return 'status-failed';
    case 'TERMINATED': return 'status-terminated';
    case 'TIMEOUT': return 'status-timeout';
    default: return '';
  }
});
</script>

<template>
  <div class="execute-view">
    <!-- 返回按钮 + 模板信息 -->
    <div class="view-header">
      <button class="btn-back" @click="emit('back')">← 返回</button>
      <div class="header-info">
        <span class="template-title">{{ template.name }}</span>
        <span v-if="modeProgress" class="mode-progress">{{ modeProgress }}</span>
      </div>
    </div>

    <!-- 参数表单 -->
    <div class="param-form">
      <label v-for="p in template.parameters" :key="p.name" class="param-field">
        <span class="param-label">
          {{ p.description || p.name }}
          <span v-if="p.required" class="required-mark">*</span>
        </span>
        <input
          v-model="params[p.name]"
          class="param-input"
          :placeholder="p.name"
          :disabled="isExecuting"
        />
      </label>

      <div class="action-row">
        <button v-if="!isExecuting" class="btn-execute" @click="startExecution" :disabled="!template.id">
          执行
        </button>
        <button v-else class="btn-stop" @click="stopExecution">停止</button>
        <button v-if="isPaused" class="btn-resume" :disabled="isResuming" @click="resumeExecution">
          {{ isResuming ? '恢复中…' : '恢复执行' }}
        </button>
        <button v-if="isPaused" class="btn-terminate" :disabled="isResuming" @click="terminateExecution">
          终止
        </button>
        <span v-if="statusLabel" class="status-tag" :class="statusClass">
          {{ statusLabel }}
        </span>
      </div>
      <!-- 暂停态：失败步骤红色高亮 + 可恢复提示（P3 AC-016） -->
      <div v-if="isPaused" class="paused-banner">
        ⏸ 已暂停 · 失败步骤：<span class="failed-step">{{ pausedAgentName }}</span>
        （步骤 {{ pausedAgentIndex + 1 }}）· 可恢复
        <div class="paused-error">{{ pausedError }}</div>
      </div>
      <div v-if="error" class="error-banner">{{ error }}</div>
    </div>

    <!-- 主控拆解面板（agentIndex=0，planAgent 流式输出拆解计划） -->
    <div v-if="supervisor" class="supervisor-plan-panel">
      <div class="panel-header">
        <span class="panel-title">主控拆解</span>
        <span class="panel-agent">{{ supervisor.planAgent.name }}</span>
      </div>
      <pre class="panel-output">{{ planOutput }}</pre>
    </div>

    <!-- 子任务卡片列表（supervisor_plan 初始化，dispatch 高亮，complete 打勾） -->
    <!-- 容器随 supervisor 定义常驻渲染（布局标志元素），卡片由 supervisor_plan 事件填充 -->
    <div v-if="supervisor" class="subtask-list">
      <div
        v-for="card in supervisorCards"
        :key="card.id"
        class="subtask-card"
        :class="card.status"
      >
        <span class="st-index">#{{ card.id }}</span>
        <div class="st-body">
          <div class="st-desc">{{ card.description }}</div>
          <div class="st-agent">
            <span class="st-agent-name">{{ card.routedAgent }}</span>
            <!-- routed=false：主控指定的 Worker 未精确命中，按兜底规则派发 -->
            <span v-if="!card.routed" class="fallback-badge" title="主控指定的 Worker 未命中，按兜底规则派发">兜底</span>
          </div>
        </div>
        <span class="st-status">
          {{ card.status === 'completed' ? '✓' : card.status === 'running' ? '⟳' : '○' }}
        </span>
      </div>
      <div v-if="supervisorCards.length === 0" class="subtask-empty">（等待主控拆解…）</div>
    </div>

    <!-- 主控汇总面板（最后一个 agentOutput，summarizeAgent 输出最终汇总） -->
    <div v-if="supervisor" class="supervisor-summary-panel">
      <div class="panel-header">
        <span class="panel-title">主控汇总</span>
        <span class="panel-agent">{{ supervisor.summarizeAgent.name }}</span>
        <span v-if="isSummarizing" class="summarizing-status">主控汇总中…</span>
      </div>
      <pre class="panel-output">{{ summarizeOutput }}</pre>
    </div>

    <!-- 最终结果 -->
    <div v-if="finalResult" class="final-result">
      <h4>最终结果</h4>
      <pre class="final-content">{{ finalResult }}</pre>
    </div>
  </div>
</template>

<style scoped>
.execute-view {
  display: flex;
  flex-direction: column;
  gap: 12px;
  height: 100%;
  padding: 16px;
  overflow-y: auto;
}

.view-header {
  display: flex;
  align-items: center;
  gap: 12px;
}

.btn-back {
  padding: 4px 10px;
  font-size: 12px;
  color: var(--text-secondary, #b8c0cc);
  background: transparent;
  border: 1px solid var(--border, #2a3350);
  border-radius: 4px;
  cursor: pointer;
}

.header-info {
  display: flex;
  align-items: baseline;
  gap: 12px;
}

.template-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary, #e6edf3);
}

.mode-progress {
  font-size: 12px;
  color: var(--accent, #00d4b8);
}

.param-form {
  display: flex;
  flex-direction: column;
  gap: 8px;
  background: var(--bg-panel, #12182b);
  border: 1px solid var(--border, #2a3350);
  border-radius: 6px;
  padding: 12px;
}

.param-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.param-label {
  font-size: 12px;
  color: var(--text-secondary, #b8c0cc);
}

.required-mark {
  color: #ff6b6b;
}

.param-input {
  padding: 6px 8px;
  font-size: 13px;
  color: var(--text-primary, #e6edf3);
  background: var(--bg-input, #0d1220);
  border: 1px solid var(--border, #2a3350);
  border-radius: 4px;
  outline: none;
}

.param-input:focus {
  border-color: var(--accent, #00d4b8);
}

.action-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.btn-execute {
  padding: 6px 20px;
  font-size: 13px;
  font-weight: 600;
  color: #062a24;
  background: var(--accent, #00d4b8);
  border: none;
  border-radius: 4px;
  cursor: pointer;
}

.btn-execute:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-stop {
  padding: 6px 20px;
  font-size: 13px;
  color: #ff6b6b;
  background: rgba(255, 107, 107, 0.12);
  border: 1px solid rgba(255, 107, 107, 0.4);
  border-radius: 4px;
  cursor: pointer;
}

.btn-resume {
  padding: 6px 20px;
  font-size: 13px;
  font-weight: 600;
  color: #c9a05c;
  background: rgba(201, 160, 92, 0.12);
  border: 1px solid rgba(201, 160, 92, 0.5);
  border-radius: 4px;
  cursor: pointer;
}

.btn-resume:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-terminate {
  padding: 6px 16px;
  font-size: 13px;
  color: #ff6b6b;
  background: rgba(255, 107, 107, 0.12);
  border: 1px solid rgba(255, 107, 107, 0.4);
  border-radius: 4px;
  cursor: pointer;
}

.btn-terminate:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.paused-banner {
  font-size: 13px;
  color: #c9a05c;
  padding: 8px 10px;
  background: rgba(201, 160, 92, 0.1);
  border: 1px solid rgba(201, 160, 92, 0.35);
  border-radius: 4px;
}

/* 暂停时失败步骤红色高亮（P3 AC-016） */
.failed-step {
  color: #ff6b6b;
  font-weight: 600;
}

.paused-error {
  margin-top: 4px;
  font-size: 12px;
  color: #ff8f8f;
  word-break: break-word;
}

.status-tag {
  font-size: 12px;
  font-weight: 600;
  padding: 2px 10px;
  border-radius: 4px;
}

.status-completed { color: #3fb68b; background: rgba(63, 182, 139, 0.12); }
.status-failed { color: #ff6b6b; background: rgba(255, 107, 107, 0.12); }
.status-terminated, .status-timeout { color: #8b94a7; background: rgba(139, 148, 167, 0.12); }

.error-banner {
  font-size: 12px;
  color: #ff6b6b;
  padding: 6px;
  background: rgba(255, 107, 107, 0.08);
  border-radius: 4px;
}

/* ===== 主控拆解 / 主控汇总 面板 ===== */
.supervisor-plan-panel,
.supervisor-summary-panel {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
  background: var(--bg-panel, #12182b);
  border: 1px solid var(--border, #2a3350);
  border-radius: 6px;
}

.panel-header {
  display: flex;
  align-items: center;
  gap: 10px;
}

.panel-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--accent, #00d4b8);
}

.panel-agent {
  font-size: 12px;
  color: var(--text-secondary, #b8c0cc);
}

.panel-output {
  margin: 0;
  font-family: var(--font-mono, monospace);
  font-size: 12px;
  color: var(--text-primary, #e6edf3);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 200px;
  overflow-y: auto;
  padding: 8px;
  background: var(--bg-input, #0d1220);
  border: 1px solid var(--border, #2a3350);
  border-radius: 4px;
}

/* ===== 子任务卡片列表 ===== */
.subtask-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.subtask-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  background: var(--bg-panel, #12182b);
  border: 1px solid var(--border, #2a3350);
  border-radius: 6px;
  transition: border-color 0.2s, background 0.2s;
}

.st-index {
  flex-shrink: 0;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-muted, #8b94a7);
}

.st-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.st-desc {
  font-size: 13px;
  color: var(--text-primary, #e6edf3);
}

.st-agent {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--text-secondary, #b8c0cc);
}

.st-agent-name {
  color: #4aa8ff;
}

/* 兜底派发标记（routed=false） */
.fallback-badge {
  padding: 1px 8px;
  font-size: 11px;
  color: #c9a05c;
  background: rgba(201, 160, 92, 0.12);
  border: 1px solid rgba(201, 160, 92, 0.5);
  border-radius: 3px;
}

/* 子任务尚未拆解时的占位提示 */
.subtask-empty {
  font-size: 12px;
  color: var(--text-muted, #8b94a7);
  padding: 12px;
  text-align: center;
  background: var(--bg-panel, #12182b);
  border: 1px dashed var(--border, #2a3350);
  border-radius: 6px;
}

.st-status {
  flex-shrink: 0;
  font-size: 16px;
  color: var(--text-muted, #8b94a7);
}

/* 派发进行中：高亮 */
.subtask-card.running {
  border-color: var(--accent, #00d4b8);
  background: rgba(0, 212, 184, 0.06);
}

.subtask-card.running .st-status {
  color: var(--accent, #00d4b8);
}

/* 已完成：打勾绿色 */
.subtask-card.completed {
  border-color: rgba(63, 182, 139, 0.5);
  background: rgba(63, 182, 139, 0.06);
}

.subtask-card.completed .st-status {
  color: #3fb68b;
}

/* 主控汇总中状态 */
.summarizing-status {
  font-size: 12px;
  font-weight: 600;
  color: #c9a05c;
  animation: summarize-pulse 1.2s ease-in-out infinite;
}

@keyframes summarize-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

.final-result {
  background: var(--bg-panel, #12182b);
  border: 1px solid var(--border, #2a3350);
  border-radius: 6px;
  padding: 12px;
}

.final-result h4 {
  margin: 0 0 8px;
  font-size: 14px;
  color: var(--accent, #00d4b8);
}

.final-content {
  margin: 0;
  font-size: 13px;
  color: var(--text-primary, #e6edf3);
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
