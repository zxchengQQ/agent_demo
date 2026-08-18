<script setup lang="ts">
import type { OrchestrationMode, WorkflowTemplateSummary } from '@/types';

/**
 * 工作流模板卡片组件（P2 Task-18，AC-032）
 * 业务含义：展示单个工作流模板的摘要卡片（名称、描述、模式标签、Agent 数量），
 * 点击后进入该模板的执行视图。
 */

const props = defineProps<{
  /** 模板摘要 */
  template: WorkflowTemplateSummary;
}>();

const emit = defineEmits<{
  /** 点击卡片选择模板 */
  select: [template: WorkflowTemplateSummary];
}>();

/** 编排模式 → 中文标签 */
const MODE_LABELS: Record<OrchestrationMode, string> = {
  SEQUENTIAL: '串行',
  PARALLEL: '并行',
  CONDITIONAL: '条件',
  LOOP: '循环',
  SUPERVISOR: '层级',
};

/** 模式对应的 CSS 类（用于标签配色） */
function modeClass(mode: OrchestrationMode): string {
  return `mode-${mode.toLowerCase()}`;
}

/** 参数摘要（如"参数：topic"） */
function paramSummary(): string {
  const params = props.template.parameters ?? [];
  if (params.length === 0) return '无需参数';
  return `参数：${params.map((p) => p.name).join(', ')}`;
}

function handleSelect() {
  emit('select', props.template);
}
</script>

<template>
  <div class="template-card" @click="handleSelect">
    <div class="card-header">
      <span class="template-name">{{ template.name }}</span>
      <span class="mode-tag" :class="modeClass(template.mode)">
        {{ MODE_LABELS[template.mode] ?? template.mode }}
      </span>
    </div>
    <div class="template-desc">{{ template.description }}</div>
    <div class="template-meta">
      <span>{{ template.agentCount }} 个 Agent</span>
      <span class="dot">·</span>
      <span>{{ paramSummary() }}</span>
    </div>
  </div>
</template>

<style scoped>
.template-card {
  background: var(--bg-panel, #12182b);
  border: 1px solid var(--border, #2a3350);
  border-radius: var(--radius-md, 8px);
  padding: 16px;
  cursor: pointer;
  transition: all 0.2s;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.template-card:hover {
  border-color: var(--accent, #00d4b8);
  transform: translateY(-2px);
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.template-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary, #e6edf3);
}

.mode-tag {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 4px;
  color: var(--accent, #00d4b8);
  background: var(--accent-dim, rgba(0, 212, 184, 0.12));
  flex-shrink: 0;
}

.template-desc {
  font-size: 12px;
  color: var(--text-muted, #8b94a7);
  line-height: 1.5;
  min-height: 36px;
}

.template-meta {
  font-size: 11px;
  color: var(--text-muted, #8b94a7);
  display: flex;
  align-items: center;
  gap: 6px;
}

.dot {
  opacity: 0.5;
}
</style>
