<script setup lang="ts">
import { onMounted, ref } from 'vue';
import type { WorkflowExecutionSummary } from '@/types';
import { listExecutions } from '@/api/workflow';
import ExecutionDetailPanel from './ExecutionDetailPanel.vue';

/**
 * 工作流执行历史列表组件（P2 Task-20，AC-027）
 * 业务含义：展示所有工作流执行记录（模板名、模式、状态、时间、结果摘要），
 * 状态用不同颜色标签区分。
 * P3 扩展：PAUSED（已暂停）记录带恢复入口，点击后通知父页面跳转执行视图断点续跑。
 * P4 扩展（CR-001 Task-31，AC-034）：点击记录手风琴式展开 ExecutionDetailPanel，
 * 展示完整执行步骤与最终结果全文（而非 50 字截断摘要）。
 */

const emit = defineEmits<{
  /** 点击恢复按钮，携带执行 ID（父页面据此加载详情并进入执行视图） */
  resume: [executionId: string];
}>();

/** 历史记录列表 */
const executions = ref<WorkflowExecutionSummary[]>([]);
/** 加载状态 */
const loading = ref(false);
/** 错误提示 */
const errorMessage = ref('');
/**
 * 当前展开详情的执行 ID（手风琴式：同一时间只展开一条；null=全部收起）
 * 业务含义：再次点击已展开记录时置回 null 实现收起。
 */
const expandedId = ref<string | null>(null);

/** 状态标签配色 */
const STATUS_CLASS: Record<string, string> = {
  COMPLETED: 'status-completed',
  FAILED: 'status-failed',
  TERMINATED: 'status-terminated',
  TIMEOUT: 'status-timeout',
  RUNNING: 'status-running',
  PENDING: 'status-pending',
  PAUSED: 'status-paused',
};

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

/** 结果摘要（截断） */
function resultSummary(result: string): string {
  if (!result) return '（无结果）';
  return result.length > 50 ? result.slice(0, 50) + '…' : result;
}

/**
 * 点击记录展开/收起详情（手风琴）
 * 业务含义：同一时间只展开一条；点击已展开记录时收起（置 null）。
 */
function toggleExpand(executionId: string) {
  expandedId.value = expandedId.value === executionId ? null : executionId;
}

/** 加载历史记录 */
async function loadExecutions() {
  loading.value = true;
  errorMessage.value = '';
  try {
    executions.value = await listExecutions();
  } catch (e) {
    errorMessage.value = '加载执行历史失败';
  } finally {
    loading.value = false;
  }
}

onMounted(loadExecutions);

defineExpose({ loadExecutions });
</script>

<template>
  <div class="history-list">
    <div class="history-header">
      <h3>执行历史</h3>
      <button class="btn-refresh" @click="loadExecutions" :disabled="loading">刷新</button>
    </div>

    <div v-if="errorMessage" class="error-banner">{{ errorMessage }}</div>

    <div v-else-if="loading" class="empty-state">加载中…</div>

    <div v-else-if="executions.length === 0" class="empty-state">
      暂无执行记录
    </div>

    <div v-else class="history-items">
      <div
        v-for="exec in executions"
        :key="exec.executionId"
        class="history-item"
        :class="{ expanded: expandedId === exec.executionId }"
        @click="toggleExpand(exec.executionId)"
      >
        <div class="item-row">
          <span class="expand-indicator">{{ expandedId === exec.executionId ? '▾' : '▸' }}</span>
          <span class="item-name">{{ exec.templateName }}</span>
          <span class="item-mode">{{ MODE_LABELS[exec.mode ?? ''] ?? exec.mode ?? '未知' }}</span>
          <span class="status-tag" :class="STATUS_CLASS[exec.status] ?? ''">
            {{ STATUS_LABELS[exec.status] ?? exec.status }}
          </span>
          <button
            v-if="exec.status === 'PAUSED'"
            class="btn-resume"
            @click.stop="emit('resume', exec.executionId)"
          >
            恢复
          </button>
        </div>
        <div class="item-meta">
          <span>{{ formatTime(exec.startTime) }}</span>
          <template v-if="exec.iterationCount > 0">
            <span class="dot">·</span>
            <span>{{ exec.iterationCount }} 轮迭代</span>
          </template>
        </div>
        <div class="item-result" :title="exec.finalResult">{{ resultSummary(exec.finalResult) }}</div>

        <!-- 展开详情面板：完整执行步骤 + 最终结果全文（AC-034） -->
        <ExecutionDetailPanel
          v-if="expandedId === exec.executionId"
          :execution-id="exec.executionId"
          :summary="exec"
          @resume="(id) => emit('resume', id)"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.history-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  height: 100%;
  padding: 16px;
  overflow-y: auto;
}

.history-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.history-header h3 {
  margin: 0;
  font-size: 16px;
  color: var(--text-primary, #e6edf3);
}

.btn-refresh {
  padding: 4px 12px;
  font-size: 12px;
  color: var(--accent, #00d4b8);
  background: transparent;
  border: 1px solid var(--border, #2a3350);
  border-radius: 4px;
  cursor: pointer;
}

.btn-refresh:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.empty-state {
  color: var(--text-muted, #8b94a7);
  font-size: 13px;
  text-align: center;
  padding: 40px 0;
}

.error-banner {
  color: #ff6b6b;
  font-size: 13px;
  padding: 8px;
  background: rgba(255, 107, 107, 0.1);
  border-radius: 4px;
}

.history-items {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.history-item {
  background: var(--bg-panel, #12182b);
  border: 1px solid var(--border, #2a3350);
  border-radius: 6px;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  cursor: pointer;
  transition: border-color 0.2s;
}

.history-item:hover {
  border-color: rgba(0, 212, 184, 0.5);
}

/* 展开态：详情面板展示时高亮边框 */
.history-item.expanded {
  border-color: var(--accent, #00d4b8);
}

.expand-indicator {
  font-size: 12px;
  color: var(--text-muted, #8b94a7);
  width: 14px;
  text-align: center;
}

.item-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.item-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary, #e6edf3);
}

.item-mode {
  font-size: 11px;
  color: var(--accent, #00d4b8);
}

.status-tag {
  font-size: 11px;
  padding: 1px 8px;
  border-radius: 4px;
  margin-left: auto;
}

.status-completed { color: #3fb68b; background: rgba(63, 182, 139, 0.12); }
.status-failed { color: #ff6b6b; background: rgba(255, 107, 107, 0.12); }
.status-terminated, .status-timeout { color: #8b94a7; background: rgba(139, 148, 167, 0.12); }
.status-running { color: #4aa8ff; background: rgba(74, 168, 255, 0.12); }
.status-pending { color: #c9a05c; background: rgba(201, 160, 92, 0.12); }
.status-paused { color: #e6a23c; background: rgba(230, 162, 60, 0.12); }

.btn-resume {
  padding: 1px 10px;
  font-size: 11px;
  color: #e6a23c;
  background: rgba(230, 162, 60, 0.1);
  border: 1px solid rgba(230, 162, 60, 0.5);
  border-radius: 4px;
  cursor: pointer;
}

.btn-resume:hover {
  background: rgba(230, 162, 60, 0.2);
}

.item-meta {
  font-size: 11px;
  color: var(--text-muted, #8b94a7);
  display: flex;
  gap: 6px;
}

.dot { opacity: 0.5; }

.item-result {
  font-size: 12px;
  color: var(--text-secondary, #b8c0cc);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
