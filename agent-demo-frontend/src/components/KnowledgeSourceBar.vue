<script setup lang="ts">
import { ref, computed } from 'vue';
import type { KnowledgeSource } from '@/types';

const props = defineProps<{
  sources: KnowledgeSource[];
}>();

/** 引用条展开状态（默认折叠，AC-043） */
const isExpanded = ref(false);

/** 引用条标题：显示引用数量（AC-043） */
const title = computed(() => `引用来源 (${props.sources.length})`);

/** 切换展开/折叠（AC-044） */
function toggle() {
  isExpanded.value = !isExpanded.value;
}
</script>

<template>
  <div v-if="props.sources.length > 0" class="source-bar">
    <div class="source-header" @click="toggle">
      <span class="source-icon">{{ isExpanded ? '▼' : '▶' }}</span>
      <span class="source-title">{{ title }}</span>
    </div>
    <div v-if="isExpanded" class="source-list">
      <div
        v-for="(source, idx) in props.sources"
        :key="idx"
        class="source-item"
      >
        <span class="source-kb">{{ source.knowledgeBaseName }}</span>
        <span class="source-sep">/</span>
        <span class="source-file">{{ source.fileName }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.source-bar {
  margin-top: var(--spacing-sm);
  background: var(--bg-sidebar);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  overflow: hidden;
}

.source-header {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  padding: var(--spacing-xs) var(--spacing-sm);
  cursor: pointer;
  user-select: none;
  font-family: var(--font-display);
  font-size: 12px;
  color: var(--text-muted);
  transition: background 0.2s;
}

.source-header:hover {
  background: var(--bg-input);
}

.source-icon {
  font-size: 10px;
}

.source-title {
  font-weight: 500;
}

.source-list {
  padding: var(--spacing-xs) var(--spacing-sm);
  border-top: 1px solid var(--border);
}

.source-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  padding: var(--spacing-xs) 0;
  font-size: 13px;
}

.source-item:not(:last-child) {
  border-bottom: 1px solid var(--border);
}

.source-kb {
  color: var(--accent);
  font-weight: 500;
}

.source-sep {
  color: var(--text-muted);
}

.source-file {
  color: var(--text-secondary);
}
</style>
