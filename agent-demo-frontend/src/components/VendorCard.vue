<script setup lang="ts">
import { computed } from 'vue';
import type { LlmVendor } from '@/types';

/**
 * 厂商卡片组件（Task-19）
 * 业务含义：展示单个已配置厂商的摘要信息卡片。
 * 展示厂商名称、类型标签、Base URL、模型数量摘要、API Key 状态。
 * 提供"编辑"/"删除"操作，删除需二次确认。
 */
const props = defineProps<{
  /** 厂商配置 */
  vendor: LlmVendor;
}>();

const emit = defineEmits<{
  /** 点击编辑 */
  edit: [vendor: LlmVendor];
  /** 点击删除（已确认） */
  delete: [vendor: LlmVendor];
}>();

/** 各类型模型数量统计 */
const modelSummary = computed(() => {
  const models = props.vendor.models ?? [];
  const chat = models.filter((m) => m.type === 'chat').length;
  const embedding = models.filter((m) => m.type === 'embedding').length;
  const rerank = models.filter((m) => m.type === 'rerank').length;
  const multimodal = models.filter((m) => m.type === 'multimodal').length;
  const parts: string[] = [];
  if (chat > 0) parts.push(`${chat} 个对话`);
  if (embedding > 0) parts.push(`${embedding} 个向量化`);
  if (rerank > 0) parts.push(`${rerank} 个重排`);
  if (multimodal > 0) parts.push(`${multimodal} 个多模态`);
  return parts.length > 0 ? parts.join(' · ') : '未配置模型';
});

/** 触发编辑 */
function handleEdit() {
  emit('edit', props.vendor);
}

/**
 * 触发删除（二次确认）
 * 业务含义：删除将移除该厂商及旗下所有模型配置，需用户确认。
 */
function handleDelete() {
  if (window.confirm('将删除该厂商及旗下所有模型配置')) {
    emit('delete', props.vendor);
  }
}
</script>

<template>
  <div class="vendor-card">
    <!-- 卡片头部：厂商名称 + 类型标签 -->
    <div class="vendor-header">
      <span class="vendor-name">{{ vendor.name }}</span>
      <span
        class="vendor-type"
        :class="vendor.type === 'predefined' ? 'type-predefined' : 'type-custom'"
      >
        {{ vendor.type === 'predefined' ? '预定义' : '自定义' }}
      </span>
    </div>

    <!-- Base URL（可能较长，截断显示） -->
    <div class="vendor-url" :title="vendor.baseUrl">{{ vendor.baseUrl }}</div>

    <!-- 模型数量及类型摘要 -->
    <div class="vendor-models">{{ modelSummary }}</div>

    <!-- API Key 状态 -->
    <div class="vendor-api-key" :class="{ configured: vendor.apiKeyConfigured }">
      {{ vendor.apiKeyConfigured ? '已配置' : '未配置' }}
    </div>

    <!-- 操作按钮 -->
    <div class="vendor-actions">
      <button class="btn-action btn-edit" @click="handleEdit">编辑</button>
      <button class="btn-action btn-delete" @click="handleDelete">删除</button>
    </div>
  </div>
</template>

<style scoped>
.vendor-card {
  background: var(--bg-sidebar);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: var(--spacing-md);
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
  transition: border-color 0.2s;
}

.vendor-card:hover {
  border-color: var(--accent-dim);
}

.vendor-header {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
}

.vendor-name {
  font-family: var(--font-display);
  font-size: 14px;
  font-weight: 700;
  color: var(--text-primary);
}

.vendor-type {
  padding: 0 6px;
  font-size: 11px;
  border-radius: 3px;
}

.type-predefined {
  color: var(--accent);
  background: var(--accent-dim);
}

.type-custom {
  color: var(--warning);
  background: rgba(255, 170, 0, 0.15);
}

.vendor-url {
  font-family: var(--font-body);
  font-size: 12px;
  color: var(--text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.vendor-models {
  font-size: 12px;
  color: var(--text-secondary);
}

.vendor-api-key {
  align-self: flex-start;
  padding: 0 6px;
  font-size: 11px;
  border-radius: 3px;
  color: var(--text-muted);
  background: var(--bg-input);
}

.vendor-api-key.configured {
  color: var(--success);
  background: rgba(0, 255, 136, 0.12);
}

.vendor-actions {
  display: flex;
  gap: var(--spacing-sm);
  margin-top: var(--spacing-sm);
}

.btn-action {
  padding: var(--spacing-xs) var(--spacing-md);
  border: none;
  border-radius: var(--radius-sm);
  font-family: var(--font-display);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-edit {
  background: var(--accent);
  color: var(--bg-primary);
}

.btn-edit:hover {
  box-shadow: var(--shadow-glow);
}

.btn-delete {
  background: transparent;
  border: 1px solid var(--border);
  color: var(--text-secondary);
}

.btn-delete:hover {
  border-color: var(--danger);
  color: var(--danger);
}
</style>
