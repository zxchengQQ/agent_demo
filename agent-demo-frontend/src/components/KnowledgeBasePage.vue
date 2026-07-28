<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useRagStore } from '@/stores/rag';
import KnowledgeBaseList from '@/components/KnowledgeBaseList.vue';
import DocumentList from '@/components/DocumentList.vue';
import CreateKnowledgeBaseDialog from '@/components/CreateKnowledgeBaseDialog.vue';

/**
 * 知识库页面容器（Task-11，AC-002）
 * 业务含义：知识库管理主页面，左右分栏布局。
 * 左侧为知识库列表（固定宽度 280px），右侧为文档列表（自适应）。
 * 页面加载时自动拉取知识库列表数据。
 * 点击"新建知识库"时弹出创建对话框。
 */

const ragStore = useRagStore();

/** 创建知识库弹窗显示状态 */
const showCreateDialog = ref(false);

// 初始化：加载知识库列表（AC-003）
onMounted(() => {
  ragStore.loadKnowledgeBases();
});
</script>

<template>
  <div class="knowledge-base-page">
    <!-- 左侧：知识库列表区域（固定宽度 280px） -->
    <aside class="kb-list-panel">
      <KnowledgeBaseList @create="showCreateDialog = true" />
    </aside>

    <!-- 右侧：文档列表区域（自适应） -->
    <main class="doc-list-panel">
      <DocumentList />
    </main>

    <!-- 创建知识库弹窗（AC-004） -->
    <CreateKnowledgeBaseDialog
      v-model:visible="showCreateDialog"
    />
  </div>
</template>

<style scoped>
.knowledge-base-page {
  display: flex;
  flex: 1;
  overflow: hidden;
}

/* 左侧固定宽度 280px */
.kb-list-panel {
  width: 280px;
  flex-shrink: 0;
  border-right: 1px solid var(--border);
  overflow-y: auto;
}

/* 右侧自适应 */
.doc-list-panel {
  flex: 1;
  min-width: 0;
  overflow-y: auto;
}
</style>
