<script setup lang="ts">
import { onMounted } from 'vue';
import { useSessionStore } from '@/stores/session';
import SessionList from '@/components/SessionList.vue';
import ChatWindow from '@/components/ChatWindow.vue';

const store = useSessionStore();

// 初始化：从 localStorage 加载会话（AC-001）
onMounted(() => {
  store.init();
});
</script>

<template>
  <div class="app-layout">
    <!-- 左侧：会话列表（280px 固定宽度） -->
    <aside class="sidebar">
      <SessionList />
    </aside>

    <!-- 右侧：对话框（自适应） -->
    <main class="main">
      <ChatWindow />
    </main>
  </div>
</template>

<style scoped>
.app-layout {
  display: flex;
  height: 100vh;
  overflow: hidden;
}

.sidebar {
  width: 280px;
  flex-shrink: 0;
  border-right: 1px solid var(--border);
}

.main {
  flex: 1;
  min-width: 0;
}
</style>
