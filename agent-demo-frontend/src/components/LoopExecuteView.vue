<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import type { WorkflowTemplateDetail } from '@/types';
import { useWorkflowStream } from '@/composables/useWorkflowStream';

/**
 * 循环模式执行视图组件（Task-27，Sec 4.5.5）
 * 业务含义：循环模式专属执行视图，以垂直时间线展示每一轮迭代节点（含轮次编号），
 * 节点内评分 Agent 与修订 Agent 输出左右对比布局；退出条件达标（或达到 maxIterations）
 * 时显示退出提示。
 * 轮次追踪基于 composable 的 modeProgress（loop_iteration 事件将其更新为
 * "第 X 轮 / 共 Y 轮"）；每轮 Agent 输出按 agentIndex 全局递增分块归组
 * （每轮占用 n=loop.agents.length 个连续面板）。
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

/** 从 loop.agents 收集 Agent 列表（循环模式仅 loop.agents 参与迭代） */
function collectAgents(): { name: string }[] {
  return (props.template.loop?.agents ?? []).map((a) => ({ name: a.name }));
}

/** 每轮 Agent 数量（用于按 agentIndex 分块归组各轮输出） */
const agentsPerIteration = computed(() => props.template.loop?.agents.length ?? 1);

/** 时间线轮次节点列表（由 loop_iteration 事件驱动累积） */
const iterations = ref<{ iteration: number; maxIterations: number }[]>([]);
/** 当前执行轮次编号 */
const currentIteration = ref(0);

/**
 * 监听 modeProgress 推进轮次
 * 业务含义：loop_iteration 事件将 modeProgress 置为 "第 X 轮 / 共 Y 轮"，
 * 据此累积时间线节点并标记当前轮次。
 * 注：使用 flush:'sync' 同步触发--同一轮内后端可能连续推送多个事件，
 * 默认批处理会合并多次 modeProgress 修改导致中间轮次节点丢失。
 */
watch(modeProgress, (v) => {
  const m = v.match(/第 (\d+) 轮 \/ 共 (\d+) 轮/);
  if (m) {
    const it = Number(m[1]);
    const max = Number(m[2]);
    if (!iterations.value.some((x) => x.iteration === it)) {
      iterations.value.push({ iteration: it, maxIterations: max });
    }
    currentIteration.value = it;
  }
}, { flush: 'sync' });

/** 按轮次分组的输出（agentIndex 全局递增，每轮占用 n 个连续面板） */
const groupedOutputs = computed(() => {
  const n = agentsPerIteration.value;
  return iterations.value.map((it, idx) => ({
    iteration: it.iteration,
    maxIterations: it.maxIterations,
    isCurrent: it.iteration === currentIteration.value && isExecuting.value,
    outputs: agentOutputs.value.slice(idx * n, idx * n + n),
  }));
});

/** 退出条件达标 / 执行完成 */
const exitReached = computed(() => statusLabel.value === 'COMPLETED');
/** 退出原因（workflow_complete 的 exitReason 由 composable 写入 modeProgress） */
const exitReason = computed(() => {
  const m = modeProgress.value.match(/退出原因：(.+)/);
  return m ? m[1] : '';
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
      <div v-if="isPaused" class="paused-banner">
        ⏸ 已暂停 · 失败步骤：{{ pausedAgentName }}（步骤 {{ pausedAgentIndex + 1 }}）· 可恢复
        <div class="paused-error">{{ pausedError }}</div>
      </div>
      <div v-if="error" class="error-banner">{{ error }}</div>
    </div>

    <!-- 退出条件达标提示 -->
    <div v-if="exitReached" class="exit-notice">
      ✓ 循环已退出
      <span v-if="exitReason" class="exit-reason">{{ exitReason }}</span>
    </div>

    <!-- 垂直时间线：每轮一个节点（含轮次编号），节点内评分/修订左右对比 -->
    <div class="loop-timeline">
      <div
        v-for="group in groupedOutputs"
        :key="group.iteration"
        class="timeline-node"
        :class="group.isCurrent ? 'current' : 'history'"
      >
        <div class="iteration-badge">第 {{ group.iteration }} 轮</div>
        <div class="node-body">
          <!-- 评分 Agent / 修订 Agent 左右对比布局 -->
          <div class="compare-row">
            <div class="compare-side compare-left">
              <div class="compare-label">{{ group.outputs[0]?.agentName ?? '评分' }}</div>
              <pre class="compare-output">{{ group.outputs[0]?.output || '（等待输出…）' }}</pre>
            </div>
            <div class="compare-side compare-right">
              <div class="compare-label">{{ group.outputs[1]?.agentName ?? '修订' }}</div>
              <pre class="compare-output">{{ group.outputs[1]?.output || '（等待输出…）' }}</pre>
            </div>
          </div>
        </div>
      </div>
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

/* ===== 退出条件达标提示 ===== */
.exit-notice {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #3fb68b;
  padding: 8px 12px;
  background: rgba(63, 182, 139, 0.1);
  border: 1px solid rgba(63, 182, 139, 0.4);
  border-radius: 6px;
}

.exit-reason {
  font-weight: 600;
}

/* ===== 循环模式垂直时间线 ===== */
.loop-timeline {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding-left: 8px;
}

/* 时间线竖线 */
.loop-timeline::before {
  content: '';
  position: absolute;
  left: 11px;
  top: 8px;
  bottom: 8px;
  width: 2px;
  background: var(--border, #2a3350);
}

.timeline-node {
  position: relative;
  display: flex;
  gap: 12px;
  padding: 10px 12px 10px 32px;
  background: var(--bg-panel, #12182b);
  border: 1px solid var(--border, #2a3350);
  border-radius: 6px;
}

/* 节点圆点（连接时间线竖线） */
.timeline-node::before {
  content: '';
  position: absolute;
  left: 5px;
  top: 14px;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--bg-panel, #12182b);
  border: 2px solid var(--text-muted, #8b94a7);
}

/* 当前执行轮次：高亮 */
.timeline-node.current {
  border-color: var(--accent, #00d4b8);
  background: rgba(0, 212, 184, 0.06);
}

.timeline-node.current::before {
  background: var(--accent, #00d4b8);
  border-color: var(--accent, #00d4b8);
  box-shadow: 0 0 0 3px rgba(0, 212, 184, 0.2);
}

/* 历史轮次：灰色 */
.timeline-node.history {
  opacity: 0.6;
}

.timeline-node.history::before {
  background: var(--text-muted, #8b94a7);
  border-color: var(--text-muted, #8b94a7);
}

.iteration-badge {
  flex-shrink: 0;
  align-self: flex-start;
  padding: 2px 10px;
  font-size: 12px;
  font-weight: 600;
  color: var(--accent, #00d4b8);
  background: rgba(0, 212, 184, 0.1);
  border: 1px solid rgba(0, 212, 184, 0.35);
  border-radius: 4px;
}

.timeline-node.history .iteration-badge {
  color: var(--text-muted, #8b94a7);
  background: rgba(139, 148, 167, 0.1);
  border-color: rgba(139, 148, 167, 0.3);
}

.node-body {
  flex: 1;
  min-width: 0;
}

/* 评分 / 修订 左右对比布局 */
.compare-row {
  display: flex;
  gap: 12px;
}

.compare-side {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 8px;
  background: var(--bg-input, #0d1220);
  border: 1px solid var(--border, #2a3350);
  border-radius: 4px;
}

.compare-left {
  border-left: 3px solid #4aa8ff;
}

.compare-right {
  border-left: 3px solid #c9a05c;
}

.compare-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-secondary, #b8c0cc);
}

.compare-output {
  margin: 0;
  font-family: var(--font-mono, monospace);
  font-size: 12px;
  color: var(--text-primary, #e6edf3);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 200px;
  overflow-y: auto;
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
