<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import type { WorkflowTemplateDetail } from '@/types';
import { useWorkflowStream } from '@/composables/useWorkflowStream';

/**
 * 串行模式执行视图组件（Task-25，技术方案 Sec 4.5.5）
 * 业务含义：串行模式专属执行视图，顶部水平流水线进度条展示
 * Agent 1->2->3 的执行顺序（当前步骤高亮、已完成步骤打勾），
 * 下方按顺序展开折叠面板复用 useWorkflowStream 的 agentOutputs 实时追加流式输出。
 * 支持参数表单 + 执行/停止/暂停恢复/终止，暂停时失败步骤红色高亮。
 */

const props = defineProps<{
  /** 选中的模板详情（mode=SEQUENTIAL） */
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
 * 收集 Agent 列表（串行模式仅从 template.agents 收集）
 * 业务含义：串行模式按 agents 数组顺序依次执行，无 loop/branches/parallelGroups。
 */
function collectAgents(): { name: string }[] {
  return (props.template.agents ?? []).map((a) => ({ name: a.name }));
}

/** 流水线 Agent 节点（响应式，模板 agents 变化时更新） */
const pipelineAgents = computed(() => collectAgents());

/** 查找节点状态：从 agentOutputs 中按 agentIndex 匹配，未找到则为 pending */
function nodeStatus(idx: number): 'pending' | 'running' | 'completed' | 'error' | 'skipped' {
  const panel = agentOutputs.value.find((p) => p.agentIndex === idx);
  return panel?.status ?? 'pending';
}

/** 节点 CSS 类 */
function nodeClass(idx: number): string {
  return 'pn-' + nodeStatus(idx);
}

/** 节点图标：pending○ / running▶ / completed✓ / error✗ / skipped⏭ */
function nodeIcon(idx: number): string {
  switch (nodeStatus(idx)) {
    case 'running': return '▶';
    case 'completed': return '✓';
    case 'error': return '✗';
    case 'skipped': return '⏭';
    default: return '○';
  }
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
        <!-- 暂停态：恢复执行 / 终止 -->
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
      <!-- 暂停状态条：失败步骤 + 原因 + 可恢复提示 -->
      <div v-if="isPaused" class="paused-banner">
        ⏸ 已暂停 · 失败步骤：{{ pausedAgentName }}（步骤 {{ pausedAgentIndex + 1 }}）· 可恢复
        <div class="paused-error">{{ pausedError }}</div>
      </div>
      <div v-if="error" class="error-banner">{{ error }}</div>
    </div>

    <!-- 顶部水平流水线进度条（串行模式专属：Agent 1->2->3） -->
    <div v-if="pipelineAgents.length > 0" class="pipeline-bar">
      <template v-for="(agent, idx) in pipelineAgents" :key="idx">
        <div class="pipeline-node" :class="nodeClass(idx)">
          <span class="node-icon">{{ nodeIcon(idx) }}</span>
          <span class="node-index">{{ idx + 1 }}</span>
          <span class="node-name">{{ agent.name }}</span>
        </div>
        <span v-if="idx < pipelineAgents.length - 1" class="pipeline-arrow">→</span>
      </template>
    </div>

    <!-- Agent 输出折叠面板（按顺序展开，复用 agentOutputs） -->
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

/* ===== 暂停与恢复样式 ===== */
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

/* ===== 顶部水平流水线进度条（串行模式专属）===== */
.pipeline-bar {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
  padding: 12px;
  background: var(--bg-panel, #12182b);
  border: 1px solid var(--border, #2a3350);
  border-radius: 6px;
}

.pipeline-node {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  background: var(--bg-input, #0d1220);
  border: 1px solid var(--border, #2a3350);
  border-radius: 4px;
  font-size: 13px;
  color: var(--text-muted, #8b94a7);
  transition: border-color 0.2s, background 0.2s, color 0.2s;
}

.pipeline-node.pn-pending {
  color: var(--text-muted, #8b94a7);
}

.pipeline-node.pn-running {
  border-color: rgba(74, 168, 255, 0.6);
  background: rgba(74, 168, 255, 0.08);
  color: #4aa8ff;
}

.pipeline-node.pn-completed {
  border-color: rgba(63, 182, 139, 0.5);
  color: #3fb68b;
}

.pipeline-node.pn-error {
  border-color: rgba(255, 107, 107, 0.6);
  background: rgba(255, 107, 107, 0.08);
  color: #ff6b6b;
}

.pipeline-node.pn-skipped {
  color: var(--text-muted, #8b94a7);
}

.node-icon {
  width: 14px;
  text-align: center;
  font-size: 14px;
}

.node-index {
  font-size: 11px;
  font-weight: 600;
  opacity: 0.7;
}

.node-name {
  font-weight: 500;
}

.pipeline-arrow {
  color: var(--text-muted, #8b94a7);
  font-size: 14px;
}

/* ===== Agent 输出折叠面板 ===== */
.panels {
  display: flex;
  flex-direction: column;
  gap: 8px;
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
