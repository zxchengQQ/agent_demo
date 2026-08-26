<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import type { WorkflowTemplateDetail } from '@/types';
import { useWorkflowStream } from '@/composables/useWorkflowStream';
import type { AgentOutput } from '@/composables/useWorkflowStream';
import AskUserCard from '@/components/AskUserCard.vue';
import ConfirmCard from '@/components/ConfirmCard.vue';

/**
 * 并行模式执行视图组件（Task-26，技术方案 Sec 4.5.5）
 * 业务含义：并行模式专属执行视图，CSS Grid 多列并排布局——每列对应一个分组
 * （如"安全审查"/"性能审查"/"风格审查"），各列独立流式输出 + 状态标签，
 * 底部汇总区展示最终综合结果。token 事件通过 agentOutputs 中的 agentIndex
 * 关联到对应列（扁平化分组 agents 后按序分配 agentIndex）。
 */

const props = defineProps<{
  /** 选中的模板详情（mode=PARALLEL） */
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
  isWaitingUser,
  waitingHitlMode,
  waitingAgentName,
  askUserData,
  toolConfirmData,
  initAgents,
  startExecution: startStream,
  resumeExecution: resumeStream,
  terminateExecution,
  replyToHitl,
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
 * 收集 Agent 列表（并行模式从 template.parallelGroups 扁平化所有分组的 agents）
 * 业务含义：并行模式下各分组 Agent 并行执行，扁平化后按序分配 agentIndex，
 * composable 的 token 事件按 agentIndex 追加输出，组件再按分组范围映射回各列。
 */
function collectAgents(): { name: string }[] {
  const list: { name: string }[] = [];
  for (const g of props.template.parallelGroups ?? []) {
    for (const a of g.agents) {
      list.push({ name: a.name });
    }
  }
  return list;
}

/**
 * 分组列信息：每个分组的名称 + 该分组 Agent 面板列表（从 agentOutputs 按 agentIndex 范围过滤）
 * 业务含义：扁平化分组后 agentIndex 连续分配，按各分组 agents 数量划分区间，
 * 将 agentOutputs 中对应区间的面板归入该列渲染，确保 token 追加到正确列。
 */
const groupColumns = computed(() => {
  const cols: { name: string; panels: AgentOutput[] }[] = [];
  let idx = 0;
  for (const g of props.template.parallelGroups ?? []) {
    const count = g.agents.length;
    const panels = agentOutputs.value.filter(
      (p) => p.agentIndex >= idx && p.agentIndex < idx + count,
    );
    cols.push({ name: g.name, panels });
    idx += count;
  }
  return cols;
});

/** Grid 列模板样式（列数 = 分组数） */
const gridStyle = computed(() => ({
  gridTemplateColumns: `repeat(${groupColumns.value.length}, 1fr)`,
}));

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
        <button v-if="!isExecuting && !isWaitingUser" class="btn-execute" @click="startExecution" :disabled="!template.id">
          执行
        </button>
        <button v-else-if="isExecuting" class="btn-stop" @click="stopExecution">停止</button>
        <!-- 暂停态：恢复执行 / 终止 -->
        <button v-if="isPaused" class="btn-resume" :disabled="isResuming" @click="resumeExecution">
          {{ isResuming ? '恢复中…' : '恢复执行' }}
        </button>
        <button v-if="isPaused || isWaitingUser" class="btn-terminate" :disabled="isResuming" @click="terminateExecution">
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
      <!-- HITL 等待用户横幅 -->
      <div v-if="isWaitingUser" class="waiting-banner">
        <div class="waiting-header">
          ⏳ 等待用户输入 · 步骤：{{ waitingAgentName }}
          <span class="waiting-mode">
            {{ waitingHitlMode === 'checkpoint' ? '（检查点确认）' : waitingHitlMode === 'toolConfirm' ? '（工具确认）' : '（Agent 提问）' }}
          </span>
        </div>
        <AskUserCard
          v-if="waitingHitlMode === 'askUser' && askUserData"
          :ask-user-data="askUserData"
          @reply="(v: string) => replyToHitl(v, null)"
        />
        <ConfirmCard
          v-else-if="waitingHitlMode === 'checkpoint'"
          :data="{ toolName: waitingAgentName, toolDescription: '工作流检查点：确认是否执行该步骤', arguments: '' }"
          @approve="replyToHitl(null, true)"
          @deny="replyToHitl(null, false)"
        />
        <!-- toolConfirm 模式：复用 ConfirmCard 渲染真实工具确认数据（Task-18 AC-H01） -->
        <ConfirmCard
          v-else-if="waitingHitlMode === 'toolConfirm' && toolConfirmData"
          :data="{ toolName: toolConfirmData.toolName, toolDescription: toolConfirmData.toolDescription, arguments: toolConfirmData.arguments }"
          @approve="replyToHitl(null, true)"
          @deny="replyToHitl(null, false)"
        />
      </div>
      <div v-if="error" class="error-banner">{{ error }}</div>
    </div>

    <!-- 并行多列布局（CSS Grid：每列一个分组的 Agent 输出） -->
    <div v-if="groupColumns.length > 0" class="parallel-grid" :style="gridStyle">
      <div v-for="col in groupColumns" :key="col.name" class="group-column">
        <!-- 列标题：分组名 -->
        <div class="group-header">{{ col.name }}</div>
        <!-- 该分组各 Agent 的流式输出 + 状态标签 -->
        <div class="group-agents">
          <details
            v-for="panel in col.panels"
            :key="panel.agentIndex"
            class="agent-panel"
            :open="panel.status === 'running' || panel.status === 'completed'"
          >
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
          <div v-if="col.panels.length === 0" class="empty-hint">等待执行…</div>
        </div>
      </div>
    </div>

    <!-- 底部汇总结果区 -->
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

.waiting-banner {
  padding: 10px 12px;
  background: rgba(255, 166, 61, 0.08);
  border: 1px solid rgba(255, 166, 61, 0.4);
  border-radius: 4px;
}
.waiting-header {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 13px;
  color: #ffa63d;
}
.waiting-mode {
  font-size: 11px;
  color: var(--text-muted, #8b94a7);
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

/* ===== 并行多列 Grid 布局（并行模式专属）===== */
.parallel-grid {
  display: grid;
  gap: 12px;
}

.group-column {
  display: flex;
  flex-direction: column;
  gap: 8px;
  background: var(--bg-panel, #12182b);
  border: 1px solid var(--border, #2a3350);
  border-radius: 6px;
  padding: 10px;
  min-width: 0;
}

.group-header {
  font-size: 14px;
  font-weight: 600;
  color: var(--accent, #00d4b8);
  padding-bottom: 6px;
  border-bottom: 1px solid var(--border, #2a3350);
}

.group-agents {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.empty-hint {
  font-size: 12px;
  color: var(--text-muted, #8b94a7);
  padding: 8px 0;
}

/* ===== Agent 输出面板 ===== */
.agent-panel {
  background: var(--bg-input, #0d1220);
  border: 1px solid var(--border, #2a3350);
  border-radius: 4px;
}

.panel-summary {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
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
  padding: 8px 10px 10px;
  font-family: var(--font-mono, monospace);
  font-size: 12px;
  color: var(--text-secondary, #b8c0cc);
  white-space: pre-wrap;
  word-break: break-word;
  border-top: 1px solid var(--border, #2a3350);
  max-height: 320px;
  overflow-y: auto;
}

/* ===== 底部汇总结果区 ===== */
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
