<script setup lang="ts">
import { ref, computed } from 'vue';
import { useRagStore } from '@/stores/rag';
import type { KnowledgeBase } from '@/types';

/**
 * 知识库列表组件（Task-12，AC-003/AC-005/AC-006/AC-016/AC-026/AC-036）
 * 业务含义：展示所有知识库，支持选中切换、删除（含级联确认）和新建入口。
 * 列表按创建时间倒序排列，选中项高亮，删除时需二次确认。
 */

const emit = defineEmits<{
  /** 点击"新建知识库"按钮 */
  create: [];
  /** 点击知识库项 */
  select: [id: string];
}>();

const ragStore = useRagStore();

/** 待删除的知识库（null 表示确认框关闭） */
const deleteTarget = ref<KnowledgeBase | null>(null);

/** 知识库列表（按创建时间倒序，AC-003） */
const sortedKnowledgeBases = computed(() => {
  return [...ragStore.knowledgeBases].sort((a, b) => {
    return new Date(b.createTime).getTime() - new Date(a.createTime).getTime();
  });
});

/** 格式化日期为 YYYY-MM-DD */
function formatDate(iso: string): string {
  const d = new Date(iso);
  const year = d.getFullYear();
  const month = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

/** 选中知识库（AC-005） */
function selectKnowledgeBase(id: string) {
  ragStore.selectKnowledgeBase(id);
  emit('select', id);
}

/** 打开删除确认框 */
function requestDelete(kb: KnowledgeBase) {
  deleteTarget.value = kb;
}

/** 确认删除（AC-006：级联删除文档和向量数据） */
async function confirmDelete() {
  if (deleteTarget.value) {
    await ragStore.deleteKnowledgeBase(deleteTarget.value.id);
  }
  deleteTarget.value = null;
}

/** 取消删除 */
function cancelDelete() {
  deleteTarget.value = null;
}
</script>

<template>
  <div class="knowledge-base-list">
    <!-- 顶部：新建知识库按钮 -->
    <div class="header">
      <button class="btn-create" @click="emit('create')">
        + 新建知识库
      </button>
    </div>

    <!-- 知识库列表 -->
    <div class="list">
      <div
        v-for="kb in sortedKnowledgeBases"
        :key="kb.id"
        class="kb-item"
        :class="{ active: ragStore.currentKnowledgeBaseId === kb.id }"
        @click="selectKnowledgeBase(kb.id)"
      >
        <div class="kb-info">
          <span class="kb-name" :title="kb.name">{{ kb.name }}</span>
          <span class="kb-meta">
            <span class="kb-doc-count">{{ kb.documentCount }} 文档</span>
            <span class="kb-time">{{ formatDate(kb.createTime) }}</span>
          </span>
        </div>
        <button class="icon-btn btn-delete" title="删除" @click.stop="requestDelete(kb)">
          ✕
        </button>
      </div>

      <!-- 空状态（AC-026） -->
      <div v-if="sortedKnowledgeBases.length === 0" class="empty-state">
        <p class="empty-text">暂无知识库</p>
        <p class="empty-hint">创建知识库后可上传文档供 Agent 检索</p>
        <button class="btn-create" @click="emit('create')">
          + 新建知识库
        </button>
      </div>
    </div>

    <!-- 删除确认框（AC-006） -->
    <div v-if="deleteTarget" class="confirm-overlay" @click="cancelDelete">
      <div class="confirm-dialog" @click.stop>
        <p class="confirm-text">
          将连带删除 {{ deleteTarget.documentCount }} 个文档及向量数据，不可恢复
        </p>
        <div class="confirm-actions">
          <button class="btn-cancel" @click="cancelDelete">取消</button>
          <button class="btn-confirm" @click="confirmDelete">确定删除</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.knowledge-base-list {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--bg-sidebar);
}

.header {
  padding: var(--spacing-md);
}

.btn-create {
  width: 100%;
  padding: var(--spacing-sm) var(--spacing-md);
  background: var(--accent-dim);
  color: var(--accent);
  border: 1px dashed var(--accent);
  border-radius: var(--radius-md);
  font-family: var(--font-display);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-create:hover {
  background: var(--accent);
  color: var(--bg-primary);
}

.list {
  flex: 1;
  overflow-y: auto;
  padding: 0 var(--spacing-sm);
}

.kb-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--spacing-sm) var(--spacing-md);
  margin-bottom: var(--spacing-xs);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: background 0.15s;
}

.kb-item:hover {
  background: var(--bg-hover);
}

/* 选中项高亮（AC-016） */
.kb-item.active {
  background: var(--bg-active);
  border-left: 2px solid var(--accent);
}

.kb-info {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
  overflow: hidden;
}

.kb-name {
  font-size: 13px;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kb-meta {
  display: flex;
  gap: var(--spacing-sm);
  font-family: var(--font-display);
  font-size: 11px;
  color: var(--text-muted);
}

.kb-doc-count {
  color: var(--text-secondary);
}

.btn-delete {
  background: none;
  border: none;
  color: var(--text-muted);
  cursor: pointer;
  padding: 2px 6px;
  font-size: 14px;
  border-radius: var(--radius-sm);
  flex-shrink: 0;
}

.btn-delete:hover {
  background: var(--bg-input);
  color: var(--danger);
}

/* 空状态（AC-026） */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-xl) var(--spacing-md);
  text-align: center;
}

.empty-text {
  font-size: 14px;
  color: var(--text-secondary);
}

.empty-hint {
  font-size: 12px;
  color: var(--text-muted);
}

.empty-state .btn-create {
  margin-top: var(--spacing-sm);
}

/* 删除确认框（AC-006） */
.confirm-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
}

.confirm-dialog {
  background: var(--bg-sidebar);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: var(--spacing-lg);
  width: 360px;
}

.confirm-text {
  font-size: 14px;
  color: var(--text-primary);
  margin-bottom: var(--spacing-md);
  line-height: 1.6;
}

.confirm-actions {
  display: flex;
  gap: var(--spacing-sm);
  justify-content: flex-end;
}

.btn-cancel,
.btn-confirm {
  padding: var(--spacing-sm) var(--spacing-md);
  border-radius: var(--radius-sm);
  font-size: 13px;
  cursor: pointer;
  border: none;
}

.btn-cancel {
  background: var(--bg-input);
  color: var(--text-secondary);
}

.btn-confirm {
  background: var(--danger);
  color: white;
}
</style>
