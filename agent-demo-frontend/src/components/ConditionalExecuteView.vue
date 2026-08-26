<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import type { WorkflowTemplateDetail } from '@/types';
import { useWorkflowStream } from '@/composables/useWorkflowStream';
import AskUserCard from '@/components/AskUserCard.vue';
import ConfirmCard from '@/components/ConfirmCard.vue';

/**
 * 条件分支模式执行视图组件（Task-28，CR-001）
 * 业务含义：条件分支模式专属执行视图，以分岔路径图展示所有可能的分支
 * （简单问题->QuickAnswer / 复杂问题->研究/分析/总结），执行时高亮实际走的分支，
 * 灰色显示未走分支，并在高亮分支下方展示该分支内各 Agent 的流式输出。
 * 分支选择由后端 onBranchSelected 事件驱动，composable 将其写入 modeProgress
 * （"已选择分支：{branchName}"），组件解析后高亮对应分支。
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
 * 从所有分支收集 Agent 列表（按分支顺序连续编号 agentIndex）
 * 业务含义：条件分支模式下分支是互斥的（只走一条），但 composable 的
 * agentOutputs 面板按全局 agentIndex 管理。需要将所有分支的 Agent
 * 按顺序展平，使后端发送的 agentIndex 能正确对应到面板。
 * 分支0的 agents 从 0 开始，分支1从分支0的 agents.length 开始，以此类推。
 */
function collectAgents(): { name: string }[] {
  const allAgents: { name: string }[] = [];
  for (const branch of props.template.branches ?? []) {
    for (const agent of branch.agents) {
      allAgents.push({ name: agent.name });
    }
  }
  return allAgents;
}

/**
 * 各分支的 agentIndex 起始位置和数量
 * 业务含义：用于在高亮分支下方正确截取该分支的 Agent 输出面板。
 * 分支互斥，只有被选中的分支才有实际输出，但全局 agentIndex 是连续分配的。
 */
const branchAgentRanges = computed(() => {
  const ranges: { start: number; count: number }[] = [];
  let offset = 0;
  for (const branch of props.template.branches ?? []) {
    ranges.push({ start: offset, count: branch.agents.length });
    offset += branch.agents.length;
  }
  return ranges;
});

/**
 * 已选分支名（由 onBranchSelected 事件驱动）
 * 业务含义：composable 的 onBranchSelected 回调将 modeProgress 置为
 * "已选择分支：{branchName}"，组件解析后高亮对应分支、灰色其余分支。
 */
const selectedBranchName = ref('');

watch(modeProgress, (v) => {
  const m = v.match(/已选择分支：(.+)/);
  if (m) {
    selectedBranchName.value = m[1];
  }
}, { flush: 'sync' });

/** 获取指定分支的 Agent 输出面板列表（按全局 agentIndex 截取） */
function branchAgentOutputs(branchIndex: number) {
  const range = branchAgentRanges.value[branchIndex];
  if (!range) return [];
  return agentOutputs.value.slice(range.start, range.start + range.count);
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
        <button v-if="!isExecuting && !isWaitingUser" class="btn-execute" @click="startExecution" :disabled="!template.id">
          执行
        </button>
        <button v-else-if="isExecuting" class="btn-stop" @click="stopExecution">停止</button>
        <button v-if="isPaused || isWaitingUser" class="btn-resume" :disabled="isResuming" @click="resumeExecution">
          {{ isResuming ? '恢复中…' : '恢复执行' }}
        </button>
        <button v-if="isPaused || isWaitingUser" class="btn-terminate" :disabled="isResuming" @click="terminateExecution">
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

    <!-- 分岔路径图：展示所有分支，高亮实际走的分支 -->
    <div class="branch-map">
      <div
        v-for="(branch, idx) in template.branches"
        :key="idx"
        class="branch-path"
        :class="{
          active: selectedBranchName === branch.name,
          inactive: selectedBranchName && selectedBranchName !== branch.name,
        }"
      >
        <div class="branch-header">
          <span class="branch-name">{{ branch.name }}</span>
          <span class="branch-condition">{{ branch.conditionDescription }}</span>
        </div>
        <div class="branch-agents">
          <span v-for="agent in branch.agents" :key="agent.name" class="agent-chip">{{ agent.name }}</span>
        </div>
        <!-- 高亮分支内 Agent 输出面板 -->
        <div v-if="selectedBranchName === branch.name" class="branch-output">
          <div v-for="output in branchAgentOutputs(idx)" :key="output.agentIndex" class="agent-output-panel">
            <div class="agent-output-name">{{ output.agentName }}</div>
            <pre class="agent-output-text">{{ output.output || '（等待输出…）' }}</pre>
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

/* ===== 条件分支模式分岔路径图 ===== */
.branch-map {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.branch-path {
  padding: 12px;
  background: var(--bg-panel, #12182b);
  border: 1px solid var(--border, #2a3350);
  border-radius: 6px;
  transition: all 0.3s ease;
}

/* 高亮分支：实际走的分支 */
.branch-path.active {
  border-color: var(--accent, #00d4b8);
  background: rgba(0, 212, 184, 0.06);
  box-shadow: 0 0 0 1px var(--accent, #00d4b8);
}

/* 灰色分支：未走的分支 */
.branch-path.inactive {
  opacity: 0.4;
  filter: grayscale(1);
}

.branch-header {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 8px;
}

.branch-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary, #e6edf3);
}

.branch-condition {
  font-size: 12px;
  color: var(--text-secondary, #b8c0cc);
}

.branch-agents {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.agent-chip {
  padding: 2px 8px;
  font-size: 12px;
  color: var(--text-secondary, #b8c0cc);
  background: var(--bg-input, #0d1220);
  border: 1px solid var(--border, #2a3350);
  border-radius: 10px;
}

/* 分支内 Agent 输出面板 */
.branch-output {
  margin-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.agent-output-panel {
  padding: 8px;
  background: var(--bg-input, #0d1220);
  border: 1px solid var(--border, #2a3350);
  border-left: 3px solid var(--accent, #00d4b8);
  border-radius: 4px;
}

.agent-output-name {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-secondary, #b8c0cc);
  margin-bottom: 4px;
}

.agent-output-text {
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
