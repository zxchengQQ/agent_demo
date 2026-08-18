<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import type { WorkflowTemplateDetail } from '@/types';
import { useWorkflowStream } from '@/composables/useWorkflowStream';

/**
 * 工作流执行视图组件（P2 Task-19，AC-004/005/006/008/009/010/022）
 * P3 扩展：暂停 UI 与恢复流程（Task-20，AC-016/AC-017）
 * 业务含义：按模板动态生成参数表单，执行工作流并通过 SSE 实时展示
 * 步骤进度、Agent 流式输出（折叠面板）与最终结果，支持"停止"；
 * 重试耗尽暂停后可"恢复执行"（新 SSE 流，已完成步骤跳过）或"终止"。
 * SSE 事件处理、状态管理与暂停/恢复逻辑已抽取至 useWorkflowStream composable（CR-001 Task-24）。
 */

const props = defineProps<{
  /** 选中的模板详情 */
  template: WorkflowTemplateDetail;
  /** 待恢复的执行 ID（历史页跳转传入，挂载自动发起恢复，P3 AC-017） */
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
 * 暂停态视图粘性标志（AC-016/AC-017）
 * 业务含义：composable 在发起恢复时会将 isPaused 置 false，但视图需保留
 * "恢复/终止"入口直至终态事件到达——与原内联实现语义一致（原实现恢复时不清除 isPaused，
 * 仅 workflow_complete/failed/terminate 清除）。故组件本地维护粘性标志：
 * 收到暂停事件置 true，收到终态状态（COMPLETED/FAILED/TERMINATED/TIMEOUT）置 false。
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
/** 暂停态（含恢复期间粘性保留）：composable 原始 isPaused 或本地粘性标志 */
const isPaused = computed(() => isPausedRaw.value || pausedUI.value);

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

/** 初始化 Agent 面板（按模板 agents/loop/branches/parallelGroups 汇总） */
function collectAgents(): { name: string }[] {
  const t = props.template;
  const list: { name: string }[] = [];
  const push = (name: string) => list.push({ name });
  (t.agents ?? []).forEach((a) => push(a.name));
  if (t.loop) t.loop.agents.forEach((a) => push(a.name));
  (t.branches ?? []).forEach((b) => b.agents.forEach((a) => push(a.name)));
  (t.parallelGroups ?? []).forEach((g) => g.agents.forEach((a) => push(a.name)));
  return list;
}

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

/** 恢复执行（对 PAUSED 执行发起新 SSE 流，AC-017） */
async function resumeExecution() {
  await resumeStream();
}

/** 停止执行（主动 abort，AC-022） */
function stopExecution() {
  stopStream();
}

/** 挂载自动恢复（历史页跳转场景，P3 AC-017） */
onMounted(() => {
  if (props.resumeExecutionId) {
    currentExecutionId.value = props.resumeExecutionId;
    resumeStream();
  }
});

/** 已完成面板数（skipped 视为已完成，参与进度统计） */
const completedCount = computed(() => agentOutputs.value.filter((p) => p.status === 'completed' || p.status === 'error' || p.status === 'skipped').length);
/** 总面板数 */
const totalCount = computed(() => agentOutputs.value.length);
/** 进度百分比 */
const progressPercent = computed(() => (totalCount.value === 0 ? 0 : Math.round((completedCount.value / totalCount.value) * 100)));

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
        <!-- 暂停态：恢复执行 / 终止（P3 AC-016/AC-017，恢复中禁用防连点） -->
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
      <!-- 暂停状态条：失败步骤 + 原因 + 可恢复提示（P3 AC-016） -->
      <div v-if="isPaused" class="paused-banner">
        ⏸ 已暂停 · 失败步骤：{{ pausedAgentName }}（步骤 {{ pausedAgentIndex + 1 }}）· 可恢复
        <div class="paused-error">{{ pausedError }}</div>
      </div>
      <div v-if="error" class="error-banner">{{ error }}</div>
    </div>

    <!-- 执行进度条 -->
    <div v-if="isExecuting || agentOutputs.length > 0" class="progress-section">
      <div class="progress-text">
        进度：{{ completedCount }}/{{ totalCount }}
        <span v-if="isExecuting"> · 执行中…</span>
      </div>
      <div class="progress-bar">
        <div class="progress-fill" :style="{ width: progressPercent + '%' }"></div>
      </div>
    </div>

    <!-- Supervisor 子任务卡片（P3 AC-007：主控拆解结果 + 派发进度） -->
    <div v-if="supervisorCards.length > 0" class="subtask-section">
      <div
        v-for="(card, idx) in supervisorCards"
        :key="card.id"
        class="subtask-card"
        :class="{ running: card.status === 'running', done: card.status === 'completed' }"
      >
        <span class="st-index">{{ idx + 1 }}</span>
        <div class="st-body">
          <div class="st-desc">{{ card.description }}</div>
          <div class="st-agent">
            -> {{ card.routedAgent }}
            <span v-if="!card.routed" class="fallback-badge" title="主控指定的 Worker 未命中，按兜底规则派发">兜底</span>
          </div>
        </div>
        <span class="st-status">{{ card.status === 'completed' ? '✓' : card.status === 'running' ? '⟳' : '○' }}</span>
      </div>
      <div v-if="isSummarizing" class="summary-hint">主控汇总中…</div>
    </div>

    <!-- Agent 输出折叠面板 -->
    <div v-if="agentOutputs.length > 0" class="panels">
      <details v-for="panel in agentOutputs" :key="panel.agentIndex" class="agent-panel" :open="panel.status === 'running' || panel.status === 'completed'">
        <summary class="panel-summary">
          <span class="panel-status" :class="'ps-' + panel.status">
            {{ panel.status === 'running' ? '▶' : panel.status === 'completed' ? '✓' : panel.status === 'error' ? '✗' : panel.status === 'skipped' ? '⏭' : '○' }}
          </span>
          <span class="panel-name">{{ panel.agentName }}</span>
          <span v-if="panel.status === 'skipped'" class="skip-label">已跳过（断点恢复）</span>
          <span v-if="panel.status === 'completed'" class="panel-duration">{{ panel.durationMs }}ms</span>
        </summary>
        <pre class="panel-output">{{ panel.output || '（等待输出…）' }}</pre>
      </details>
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

/* ===== P3 暂停与恢复样式（AC-016/AC-017）===== */
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

.progress-section {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.progress-text {
  font-size: 12px;
  color: var(--text-muted, #8b94a7);
}

.progress-bar {
  height: 6px;
  background: var(--bg-input, #0d1220);
  border-radius: 3px;
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: var(--accent, #00d4b8);
  transition: width 0.3s;
}

.panels {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

/* ===== P3 Supervisor 子任务卡片样式（AC-007）===== */
.subtask-section {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.subtask-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  background: var(--bg-panel, #12182b);
  border: 1px solid var(--border, #2a3350);
  border-radius: 6px;
}

.subtask-card.running {
  border-color: rgba(74, 168, 255, 0.6);
  background: rgba(74, 168, 255, 0.06);
}

.subtask-card.done {
  border-color: rgba(63, 182, 139, 0.5);
  opacity: 0.85;
}

.st-index {
  flex-shrink: 0;
  width: 20px;
  height: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 600;
  color: var(--accent, #00d4b8);
  border: 1px solid var(--border, #2a3350);
  border-radius: 50%;
}

.subtask-card.running .st-index {
  color: #4aa8ff;
  border-color: rgba(74, 168, 255, 0.6);
}

.subtask-card.done .st-index {
  color: #3fb68b;
  border-color: rgba(63, 182, 139, 0.5);
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
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.st-agent {
  font-size: 11px;
  color: var(--text-muted, #8b94a7);
}

.fallback-badge {
  display: inline-block;
  margin-left: 6px;
  padding: 0 6px;
  font-size: 10px;
  color: #c9a05c;
  background: rgba(201, 160, 92, 0.12);
  border: 1px solid rgba(201, 160, 92, 0.4);
  border-radius: 3px;
}

.st-status {
  flex-shrink: 0;
  font-size: 14px;
  color: var(--text-muted, #8b94a7);
}

.subtask-card.running .st-status { color: #4aa8ff; }
.subtask-card.done .st-status { color: #3fb68b; }

.summary-hint {
  font-size: 12px;
  color: var(--accent, #00d4b8);
  padding: 6px 10px;
  background: rgba(0, 212, 184, 0.06);
  border: 1px dashed rgba(0, 212, 184, 0.35);
  border-radius: 4px;
}

.agent-panel {
  background: var(--bg-panel, #12182b);
  border: 1px solid var(--border, #2a3350);
  border-radius: 6px;
}

.panel-summary {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  cursor: pointer;
  font-size: 13px;
  color: var(--text-primary, #e6edf3);
}

.panel-status { width: 14px; text-align: center; }
.ps-running { color: #4aa8ff; }
.ps-completed { color: #3fb68b; }
.ps-error { color: #ff6b6b; }
.ps-pending { color: #8b94a7; }
.ps-skipped { color: #8b94a7; }

.panel-name { font-weight: 500; }

.skip-label {
  font-size: 11px;
  color: #8b94a7;
  padding: 1px 8px;
  background: rgba(139, 148, 167, 0.12);
  border-radius: 4px;
}

.panel-duration {
  margin-left: auto;
  font-size: 11px;
  color: var(--text-muted, #8b94a7);
}

.panel-output {
  margin: 0;
  padding: 8px 12px 12px;
  font-family: var(--font-mono, monospace);
  font-size: 12px;
  color: var(--text-secondary, #b8c0cc);
  white-space: pre-wrap;
  word-break: break-word;
  border-top: 1px solid var(--border, #2a3350);
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
