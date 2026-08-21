<script setup lang="ts">
import { ref } from 'vue';

/**
 * HITL 确认卡片（Task-09 新增）
 * 业务含义：Agent 发起 confirm 类型人机交互时，展示问题文本和选项按钮列表。
 * 用户点击选项后禁用所有按钮并高亮选中项，通过 select 事件通知父组件。
 */

const props = withDefaults(defineProps<{
  /** 问题文本 */
  question: string;
  /** 可选项列表 */
  options: string[];
  /** 是否禁用（外部控制，如已回复后禁止再次点击） */
  disabled?: boolean;
}>(), {
  disabled: false,
});

const emit = defineEmits<{
  /** 用户选中某个选项时触发 */
  select: [optionValue: string];
}>();

/** 当前选中的选项值（点击后锁定，用于高亮） */
const selectedValue = ref<string | null>(null);

/** 是否已选中（内部状态，选中后禁用所有按钮） */
const isSelected = ref(false);

/**
 * 点击选项处理
 * 业务含义：选中后立即禁用所有按钮并高亮选中项，防止重复点击。
 */
function handleSelect(option: string) {
  if (props.disabled || isSelected.value) return;
  selectedValue.value = option;
  isSelected.value = true;
  emit('select', option);
}
</script>

<template>
  <div class="confirm-card">
    <div class="confirm-question">{{ props.question }}</div>
    <div class="confirm-options">
      <button
        v-for="option in props.options"
        :key="option"
        class="confirm-option"
        :class="{
          selected: selectedValue === option,
          disabled: props.disabled || isSelected,
        }"
        :disabled="props.disabled || isSelected"
        @click="handleSelect(option)"
      >
        {{ option }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.confirm-card {
  margin-top: var(--spacing-sm);
  padding: var(--spacing-sm) var(--spacing-md);
  background: var(--bg-sidebar);
  border: 1px solid var(--accent-dim);
  border-radius: var(--radius-md);
}

.confirm-question {
  font-size: 14px;
  line-height: 1.6;
  color: var(--text-primary);
  margin-bottom: var(--spacing-sm);
}

.confirm-options {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-xs);
}

.confirm-option {
  padding: var(--spacing-xs) var(--spacing-md);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-input);
  color: var(--text-secondary);
  font-family: var(--font-display);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s;
}

.confirm-option:hover:not(.disabled) {
  border-color: var(--accent-dim);
  color: var(--accent);
}

/* 选中项高亮 */
.confirm-option.selected {
  border-color: var(--accent);
  background: var(--accent-dim);
  color: var(--accent);
  font-weight: 600;
}

/* 禁用状态（已选中或外部控制） */
.confirm-option.disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.confirm-option.disabled:not(.selected) {
  opacity: 0.4;
}
</style>
