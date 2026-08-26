<script setup lang="ts">
import { computed } from 'vue';
import type { ToolConfirmData } from '@/types';

/**
 * 工具权限确认卡片（Task-17 新增，AC-H01）
 * 业务含义：ask 级工具被 Agent 调用时展示确认卡片四要素
 * （工具名/用途描述/参数摘要/批准-拒绝按钮），用户决策后由 ChatWindow
 * 携带 toolApproved 重新发起流式请求恢复执行。
 * 复用现有 HITL 卡片范式（AskUserCard 同款边框/配色，AC-H01 视觉一致）。
 */

const props = withDefaults(defineProps<{
  /** 权限确认数据（tool_confirm 事件四要素） */
  data: ToolConfirmData;
  /** 是否已决策（锁定态：批准/拒绝后禁用按钮，可回看） */
  answered?: boolean;
  /** 决策结果（answered=true 时：true=已批准，false=已拒绝） */
  approved?: boolean;
}>(), {
  answered: false,
  approved: false,
});

const emit = defineEmits<{
  approve: [];
  deny: [];
}>();

/** 参数摘要格式化展示（JSON 格式化缩进，解析失败原样展示） */
const formattedArgs = computed(() => {
  if (!props.data.arguments) return '';
  try {
    return JSON.stringify(JSON.parse(props.data.arguments), null, 2);
  } catch {
    return props.data.arguments;
  }
});

/** 决策结果文案（锁定态展示） */
const resultLabel = computed(() => (props.approved ? '已批准' : '已拒绝'));
</script>

<template>
  <div class="confirm-card">
    <!-- 头部：图标 + 标题 -->
    <div class="confirm-question">
      <span class="type-icon">🔐</span>
      <span class="question-text">请求使用工具「{{ props.data.toolName }}」</span>
    </div>

    <!-- 用途描述 -->
    <div v-if="props.data.toolDescription" class="tool-desc">
      <span class="tool-desc-label">用途：</span>
      <span class="tool-desc-text">{{ props.data.toolDescription }}</span>
    </div>

    <!-- 参数摘要（JSON 格式化） -->
    <div v-if="formattedArgs" class="tool-args">
      <span class="tool-args-label">参数：</span>
      <pre class="tool-args-code">{{ formattedArgs }}</pre>
    </div>

    <!-- 操作区：批准/拒绝两按钮（决策后锁定展示结果） -->
    <div v-if="!answered" class="action-row">
      <button
        class="action-btn approve"
        data-testid="confirm-approve"
        @click="emit('approve')"
      >
        批准
      </button>
      <button
        class="action-btn deny"
        data-testid="confirm-deny"
        @click="emit('deny')"
      >
        拒绝
      </button>
    </div>
    <div v-else class="answered-line">{{ resultLabel }}</div>
  </div>
</template>

<style scoped>
/* 复用 AskUserCard 卡片范式（AC-H01 视觉一致） */
.confirm-card {
  margin-top: var(--spacing-sm);
  padding: var(--spacing-sm) var(--spacing-md);
  background: var(--bg-sidebar);
  border: 1px solid var(--accent-dim);
  border-radius: var(--radius-md);
}

.confirm-question {
  display: flex;
  align-items: baseline;
  gap: var(--spacing-xs);
  font-size: 14px;
  line-height: 1.6;
  color: var(--text-primary);
  margin-bottom: var(--spacing-xs);
}

.type-icon {
  flex-shrink: 0;
}

.question-text {
  flex: 1;
}

/* 用途描述 */
.tool-desc {
  display: flex;
  gap: var(--spacing-xs);
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-secondary);
  margin-bottom: var(--spacing-xs);
}

.tool-desc-label {
  color: var(--text-muted);
  flex-shrink: 0;
}

.tool-desc-text {
  flex: 1;
  word-break: break-word;
}

/* 参数摘要 */
.tool-args {
  display: flex;
  gap: var(--spacing-xs);
  font-size: 12px;
  margin-bottom: var(--spacing-xs);
}

.tool-args-label {
  color: var(--text-muted);
  flex-shrink: 0;
  padding-top: 2px;
}

.tool-args-code {
  flex: 1;
  margin: 0;
  padding: var(--spacing-xs) var(--spacing-sm);
  background: var(--bg-input);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  font-family: var(--font-mono, monospace);
  font-size: 0.9em;
  color: var(--text-primary);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 150px;
  overflow-y: auto;
}

/* 操作区 */
.action-row {
  display: flex;
  gap: var(--spacing-sm);
  margin-top: var(--spacing-xs);
}

.action-btn {
  padding: var(--spacing-xs) var(--spacing-lg);
  border-radius: var(--radius-sm);
  font-family: var(--font-display);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.action-btn.approve {
  border: 1px solid var(--accent);
  background: var(--accent-dim);
  color: var(--accent);
}

.action-btn.approve:hover {
  background: var(--accent);
  color: #fff;
}

.action-btn.deny {
  border: 1px solid var(--danger);
  background: transparent;
  color: var(--danger);
}

.action-btn.deny:hover {
  background: var(--danger);
  color: #fff;
}

/* 决策锁定态 */
.answered-line {
  margin-top: var(--spacing-xs);
  padding: var(--spacing-xs) var(--spacing-sm);
  border: 1px solid var(--accent-dim);
  border-radius: var(--radius-sm);
  background: var(--accent-dim);
  color: var(--accent);
  font-size: 13px;
}
</style>
