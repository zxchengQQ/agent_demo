<script setup lang="ts">
import { ref } from 'vue';
import type { KnowledgeBase } from '@/types';

/**
 * 知识库选择器组件（Task-07，AC-011/AC-014/AC-028/AC-029）
 * 业务含义：下拉多选组件，用户可选择本次对话引用的知识库。
 * 空数组表示"自动"模式（Agent 自主检索），选中后仅检索指定知识库。
 */
const props = withDefaults(defineProps<{
  /** 选中的知识库名称列表（v-model 双向绑定） */
  modelValue: string[];
  /** 可选知识库列表 */
  knowledgeBases: KnowledgeBase[];
  /** 是否禁用（流式生成时置灰，AC-011） */
  disabled?: boolean;
}>(), {
  disabled: false,
});

const emit = defineEmits<{
  'update:modelValue': [value: string[]];
}>();

/** 下拉展开状态 */
const dropdownOpen = ref(false);

/** 切换下拉展开/收起（disabled 时不响应，AC-011） */
function toggleDropdown() {
  if (props.disabled) return;
  dropdownOpen.value = !dropdownOpen.value;
}

/** 切换知识库选中状态（AC-029）：已选中则移除，未选中则添加 */
function toggleKb(name: string) {
  if (props.modelValue.includes(name)) {
    emit('update:modelValue', props.modelValue.filter((n) => n !== name));
  } else {
    emit('update:modelValue', [...props.modelValue, name]);
  }
}
</script>

<template>
  <div class="kb-selector" :class="{ disabled }">
    <!-- 触发器 + 标签展示区 -->
    <div class="kb-trigger" @click="toggleDropdown">
      <span v-if="modelValue.length === 0" class="kb-tag kb-tag-auto">自动</span>
      <span v-for="name in modelValue" :key="name" class="kb-tag">{{ name }}</span>
    </div>

    <!-- 下拉列表 -->
    <div v-if="dropdownOpen" class="kb-dropdown">
      <!-- 空知识库提示（AC-028） -->
      <div v-if="knowledgeBases.length === 0" class="kb-empty">
        暂无知识库，请先在知识库页面创建
      </div>
      <!-- 知识库选项列表（AC-014） -->
      <div
        v-for="kb in knowledgeBases"
        :key="kb.id"
        class="kb-option"
        :class="{ selected: modelValue.includes(kb.name) }"
        @click="toggleKb(kb.name)"
      >
        {{ kb.name }}
      </div>
    </div>
  </div>
</template>

<style scoped>
.kb-selector {
  position: relative;
  display: inline-flex;
  align-items: center;
}

/* 禁用态置灰（AC-011） */
.kb-selector.disabled {
  opacity: 0.5;
  pointer-events: none;
}

.kb-trigger {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-xs);
  cursor: pointer;
  padding: 2px var(--spacing-sm);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-muted);
  font-family: var(--font-display);
  font-size: 12px;
  transition: all 0.2s;
}

.kb-trigger:hover {
  border-color: var(--accent-dim);
  color: var(--accent);
}

.kb-tag {
  display: inline-block;
}

/* "自动"标签使用 muted 色，区别于已选中的知识库标签 */
.kb-tag-auto {
  color: var(--text-muted);
}

.kb-dropdown {
  position: absolute;
  bottom: calc(100% + var(--spacing-xs));
  left: 0;
  min-width: 180px;
  max-height: 240px;
  overflow-y: auto;
  background: var(--bg-sidebar);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow-sm);
  z-index: 10;
}

.kb-option {
  padding: var(--spacing-xs) var(--spacing-sm);
  font-family: var(--font-body);
  font-size: 13px;
  color: var(--text-secondary);
  cursor: pointer;
  transition: background 0.2s;
}

.kb-option:hover {
  background: var(--bg-hover);
}

/* 已选中项高亮（AC-014） */
.kb-option.selected {
  color: var(--accent);
  background: var(--accent-dim);
}

.kb-empty {
  padding: var(--spacing-sm);
  font-size: 12px;
  color: var(--text-muted);
  text-align: center;
}
</style>
