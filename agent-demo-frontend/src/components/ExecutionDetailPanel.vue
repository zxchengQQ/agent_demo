<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
import type { WorkflowExecutionDetail, WorkflowExecutionSummary } from '@/types';
import { getExecution } from '@/api/workflow';

/**
 * 工作流执行详情面板组件（CR-001 Task-31，AC-034）
 * 业务含义：点击历史记录后展开，展示完整执行步骤（每步 Agent 名称、状态、耗时、输出内容）
 * 与最终结果全文（不截断、可滚动），替代历史列表 50 字截断摘要。
 * PAUSED 记录详情内仍显示"恢复"按钮，点击后通知父组件跳转执行视图断点续跑。
 */

const props = defineProps<{
  /** 执行 ID（变化时重新加载详情） */
  executionId: string;
  /** 摘要信息（可选，供面板顶部概要展示，未传时以 detail 为准） */
  summary?: WorkflowExecutionSummary;
}>();

const emit = defineEmits<{
  /** PAUSED 记录点击恢复（通知父组件跳转执行视图断点续跑） */
  resume: [executionId: string];
}>();

/** 执行详情数据 */
const detail = ref<WorkflowExecutionDetail | null>(null);
/** 加载状态 */
const loading = ref(false);
/** 错误提示 */
const errorMessage = ref('');

/** 状态中文标签 */
const STATUS_LABELS: Record<string, string> = {
  COMPLETED: '已完成',
  FAILED: '失败',
  TERMINATED: '已终止',
  TIMEOUT: '超时',
  RUNNING: '执行中',
  PENDING: '等待中',
  PAUSED: '已暂停',
};

/** 编排模式中文标签 */
const MODE_LABELS: Record<string, string> = {
  SEQUENTIAL: '串行',
  PARALLEL: '并行',
  CONDITIONAL: '条件',
  LOOP: '循环',
  SUPERVISOR: '层级',
};

/** 格式化时间（去掉毫秒部分，便于展示） */
function formatTime(time: string | null): string {
  if (!time) return '-';
  return time.replace('T', ' ').slice(0, 19);
}

/** 加载执行详情 */
async function loadDetail() {
  loading.value = true;
  errorMessage.value = '';
  try {
    detail.value = await getExecution(props.executionId);
  } catch {
    errorMessage.value = '加载执行详情失败';
  } finally {
    loading.value = false;
  }
}

onMounted(loadDetail);
// executionId 变化时重新加载（父组件切换展开记录时复用面板实例）
watch(() => props.executionId, loadDetail);
</script>

<template>
  <div class="execution-detail">
    <!-- 加载中 -->
    <div v-if="loading" class="detail-loading">加载中…</div>
    <!-- 错误（API 404/500 等） -->
    <div v-else-if="errorMessage" class="detail-error">{{ errorMessage }}</div>
    <!-- 详情 -->
    <div v-else-if="detail" class="detail-body">
      <!-- 执行概要 -->
      <div class="detail-summary">
        <span class="summary-name">{{ detail.templateName }}</span>
        <span class="summary-mode">{{ MODE_LABELS[detail.mode ?? ''] ?? detail.mode }}</span>
        <span class="summary-status">{{ STATUS_LABELS[detail.status] ?? detail.status }}</span>
      </div>
      <div class="detail-time">{{ formatTime(detail.startTime) }} → {{ formatTime(detail.endTime) }}</div>

      <!-- 步骤列表 -->
      <div class="step-list">
        <div v-for="(step, i) in detail.steps" :key="i" class="step-item">
          <span class="step-index">{{ i + 1 }}</span>
          <span class="step-agent">{{ step.agentName }}</span>
          <span class="step-status">{{ step.status }}</span>
          <span class="step-duration">{{ (step.durationMs / 1000).toFixed(1) }}s</span>
          <!-- 输出内容折叠区（后端返回 output 时展示） -->
          <details v-if="step.output" class="step-output">
            <summary>输出内容</summary>
            <pre>{{ step.output }}</pre>
          </details>
        </div>
      </div>

      <!-- 最终结果全文（不截断，可滚动） -->
      <div class="detail-final">
        <h4>最终结果</h4>
        <pre class="final-text">{{ detail.finalResult }}</pre>
      </div>

      <!-- PAUSED 恢复按钮 -->
      <button
        v-if="detail.status === 'PAUSED'"
        class="btn-resume"
        @click="emit('resume', detail.executionId)"
      >
        恢复执行
      </button>
    </div>
  </div>
</template>

<style scoped>
.execution-detail {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 8px;
  padding: 10px;
  background: var(--bg-deep, #0d1224);
  border: 1px solid var(--border, #2a3350);
  border-radius: 6px;
}

.detail-loading {
  font-size: 12px;
  color: var(--text-muted, #8b94a7);
  padding: 8px 0;
}

.detail-error {
  font-size: 12px;
  color: #ff6b6b;
  padding: 8px 0;
}

.detail-summary {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.summary-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary, #e6edf3);
}

.summary-mode {
  font-size: 11px;
  color: var(--accent, #00d4b8);
}

.summary-status {
  font-size: 11px;
  padding: 1px 8px;
  border-radius: 4px;
  color: #3fb68b;
  background: rgba(63, 182, 139, 0.12);
  margin-left: auto;
}

.detail-time {
  font-size: 11px;
  color: var(--text-muted, #8b94a7);
}

.step-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.step-item {
  display: grid;
  grid-template-columns: 20px 1fr auto auto;
  gap: 8px;
  align-items: center;
  padding: 8px 10px;
  background: var(--bg-panel, #12182b);
  border: 1px solid var(--border, #2a3350);
  border-radius: 6px;
}

.step-index {
  font-size: 12px;
  color: var(--text-muted, #8b94a7);
}

.step-agent {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary, #e6edf3);
}

.step-status {
  font-size: 11px;
  color: var(--accent, #00d4b8);
}

.step-duration {
  font-size: 11px;
  color: var(--text-muted, #8b94a7);
}

.step-output {
  grid-column: 1 / -1;
  font-size: 12px;
  color: var(--text-secondary, #b8c0cc);
}

.step-output summary {
  cursor: pointer;
  font-size: 11px;
  color: var(--accent, #00d4b8);
}

.step-output pre {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-secondary, #b8c0cc);
  white-space: pre-wrap;
  word-break: break-word;
}

.detail-final h4 {
  margin: 0 0 4px;
  font-size: 12px;
  color: var(--text-secondary, #b8c0cc);
}

.final-text {
  margin: 0;
  max-height: 240px;
  overflow-y: auto;
  padding: 8px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-primary, #e6edf3);
  white-space: pre-wrap;
  word-break: break-word;
  background: var(--bg-panel, #12182b);
  border: 1px solid var(--border, #2a3350);
  border-radius: 4px;
}

.btn-resume {
  align-self: flex-start;
  padding: 4px 14px;
  font-size: 12px;
  color: #e6a23c;
  background: rgba(230, 162, 60, 0.1);
  border: 1px solid rgba(230, 162, 60, 0.5);
  border-radius: 4px;
  cursor: pointer;
}

.btn-resume:hover {
  background: rgba(230, 162, 60, 0.2);
}
</style>
