<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useSessionStore } from '@/stores/session';
import SessionList from '@/components/SessionList.vue';
import ChatWindow from '@/components/ChatWindow.vue';
import NavBar from '@/components/NavBar.vue';
import KnowledgeBasePage from '@/components/KnowledgeBasePage.vue';

const store = useSessionStore();

/** 当前激活的视图（AC-001：对话/知识库导航切换） */
const currentView = ref<'chat' | 'knowledge'>('chat');

// 初始化：从 localStorage 加载会话（AC-001）
onMounted(() => {
  store.init();
});
</script>

<template>
  <div class="app-container">
    <!-- 顶部导航栏（AC-001） -->
    <NavBar v-model:currentView="currentView" />

    <!-- 对话页面：左右分栏布局 -->
    <div v-if="currentView === 'chat'" class="app-layout">
      <!-- 左侧：会话列表（280px 固定宽度） -->
      <aside class="sidebar">
        <SessionList />
      </aside>

      <!-- 右侧：对话框（自适应） -->
      <main class="main">
        <ChatWindow />
      </main>
    </div>

    <!-- 知识库页面（Task-11 完整实现） -->
    <KnowledgeBasePage v-else />
  </div>
</template>

<style scoped>
.app-container {
  display: flex;
  flex-direction: column;
  height: 100vh;
  overflow: hidden;
}

.app-layout {
  display: flex;
  flex: 1;
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
