<script setup lang="ts">
/**
 * 顶部导航栏组件（Task-10，AC-001）
 * 业务含义：全局视图切换入口，在"对话"与"知识库"两个主功能间导航。
 * 通过 v-model:currentView 双向绑定当前视图，保持 App.vue 状态简洁。
 */

defineProps<{
  /** 当前激活的视图 */
  currentView: 'chat' | 'knowledge';
}>();

const emit = defineEmits<{
  'update:currentView': [value: 'chat' | 'knowledge'];
}>();

/** 导航项定义 */
const navItems = [
  { key: 'chat' as const, label: '对话' },
  { key: 'knowledge' as const, label: '知识库' },
];

/** 切换视图 */
function switchView(view: 'chat' | 'knowledge') {
  emit('update:currentView', view);
}
</script>

<template>
  <nav class="navbar">
    <div class="navbar-brand">
      <span class="brand-text">Agent Demo</span>
    </div>
    <div class="navbar-items">
      <button
        v-for="item in navItems"
        :key="item.key"
        class="nav-item"
        :class="{ active: currentView === item.key }"
        @click="switchView(item.key)"
      >
        {{ item.label }}
      </button>
    </div>
  </nav>
</template>

<style scoped>
.navbar {
  display: flex;
  align-items: center;
  gap: var(--spacing-lg);
  padding: 0 var(--spacing-md);
  height: 48px;
  flex-shrink: 0;
  background: var(--bg-sidebar);
  border-bottom: 1px solid var(--border);
}

.navbar-brand {
  font-family: var(--font-display);
  font-size: 14px;
  font-weight: 700;
  color: var(--accent);
  letter-spacing: 0.5px;
}

.navbar-items {
  display: flex;
  gap: var(--spacing-xs);
}

.nav-item {
  padding: var(--spacing-xs) var(--spacing-md);
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  font-family: var(--font-body);
  font-size: 13px;
  color: var(--text-muted);
  cursor: pointer;
  transition: all 0.2s;
}

.nav-item:hover {
  color: var(--text-primary);
  background: var(--bg-hover);
}

/* 当前激活项高亮（accent 色，AC-001） */
.nav-item.active {
  color: var(--accent);
  background: var(--accent-dim);
}
</style>
