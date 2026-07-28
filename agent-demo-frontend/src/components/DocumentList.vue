<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch } from 'vue';
import { useRagStore } from '@/stores/rag';
import { getDocumentStatus } from '@/api/rag';
import DocumentUploader from './DocumentUploader.vue';
import DocumentChunkDrawer from './DocumentChunkDrawer.vue';
import type { DocumentInfo, DocumentStatus } from '@/types';

/**
 * 文档列表组件（Task-15，AC-005/AC-009/AC-010/AC-017/AC-024/AC-035）
 * CR-001 新增：查看分块按钮 + DocumentChunkDrawer 集成（AC-038/AC-040）
 *
 * 业务含义：展示当前选中知识库下的所有文档，包含文件信息和处理状态。
 * 对 PENDING/PROCESSING 状态的文档自动轮询（每 3 秒），状态变为终态后停止。
 * 支持删除文档（直接执行 + Toast 提示）。
 * COMPLETED 文档可点击"查看分块"打开抽屉面板查看分块详情。
 * 无文档时展示空状态引导和上传区域。
 */

const ragStore = useRagStore();

const emit = defineEmits<{
  /** Toast 通知事件 */
  notify: [message: string];
}>();

/** 轮询间隔：3 秒 */
const POLL_INTERVAL = 3000;
/** 轮询定时器 */
let pollTimer: ReturnType<typeof setInterval> | null = null;

// ===== CR-001 新增：分块抽屉状态 =====
/** 分块抽屉是否可见 */
const showChunkDrawer = ref(false);
/** 当前查看分块的文档 ID */
const chunkDocumentId = ref('');
/** 当前查看分块的文档名 */
const chunkDocumentName = ref('');
/** 当前查看分块的文档分块数 */
const chunkCount = ref(0);

/**
 * 格式化文件大小为人类可读格式
 */
function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/**
 * 格式化时间为简短显示
 */
function formatTime(iso: string): string {
  const d = new Date(iso);
  return `${d.getMonth() + 1}/${d.getDate()} ${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}`;
}

/**
 * 状态标签文本映射
 */
function statusLabel(status: DocumentStatus): string {
  switch (status) {
    case 'PENDING': return '待处理';
    case 'PROCESSING': return '处理中';
    case 'COMPLETED': return '已完成';
    case 'FAILED': return '失败';
  }
}

/**
 * 状态标签 CSS 类名映射
 */
function statusClass(status: DocumentStatus): string {
  switch (status) {
    case 'PENDING': return 'status-pending';
    case 'PROCESSING': return 'status-processing';
    case 'COMPLETED': return 'status-completed';
    case 'FAILED': return 'status-failed';
  }
}

/**
 * 启动轮询：每 3 秒查询 PENDING/PROCESSING 文档的状态
 * 业务含义：文档异步处理需要时间，前端轮询获取最新状态直到终态。
 */
function startPolling() {
  stopPolling();
  pollTimer = setInterval(pollPendingDocuments, POLL_INTERVAL);
  // 立即执行一次
  pollPendingDocuments();
}

/** 停止轮询 */
function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
}

/**
 * 轮询待处理文档
 * 业务含义：筛选 PENDING/PROCESSING 文档逐个查询状态，终态后更新 store。
 * 所有文档到达终态后自动停止轮询。
 */
async function pollPendingDocuments() {
  const pending = ragStore.currentDocuments.filter(
    (d) => d.status === 'PENDING' || d.status === 'PROCESSING',
  );

  // 没有待处理文档时停止轮询
  if (pending.length === 0) {
    stopPolling();
    return;
  }

  for (const doc of pending) {
    try {
      const result = await getDocumentStatus(doc.documentId);
      ragStore.updateDocumentStatus(
        doc.documentId,
        result.status,
        result.chunkCount,
        result.failReason,
      );
    } catch {
      // 单个文档轮询失败不影响其他文档，下次轮询继续
    }
  }
}

/**
 * 删除文档
 * 业务含义：直接执行删除（无二次确认），成功后 Toast 提示。
 */
async function handleDelete(doc: DocumentInfo) {
  try {
    await ragStore.deleteDocument(doc.documentId);
    emit('notify', '删除成功');
  } catch (err) {
    emit('notify', err instanceof Error ? err.message : '删除失败');
  }
}

/** 处理上传组件的 notify 事件，透传给父组件 */
function handleNotify(message: string) {
  emit('notify', message);
}

/** 查看文档分块（CR-001 新增，AC-038/AC-040） */
function handleViewChunks(doc: DocumentInfo) {
  if (doc.status !== 'COMPLETED') return;
  chunkDocumentId.value = doc.documentId;
  chunkDocumentName.value = doc.fileName;
  chunkCount.value = doc.chunkCount;
  showChunkDrawer.value = true;
}

onMounted(() => {
  startPolling();
});

onUnmounted(() => {
  stopPolling();
});

// 监听当前知识库变化：停止旧轮询，新文档加载后重启轮询
watch(
  () => ragStore.currentKnowledgeBaseId,
  () => {
    stopPolling();
    // 新文档加载后自动启动轮询（如果有待处理文档）
    startPolling();
  },
);
</script>

<template>
  <div class="document-list">
    <!-- 空状态：无文档时展示引导 + 上传区域 -->
    <div v-if="ragStore.currentDocuments.length === 0" class="empty-state">
      <p class="empty-hint">还没有文档，拖拽文件到此处上传</p>
      <DocumentUploader @notify="handleNotify" />
    </div>

    <!-- 文档列表 -->
    <div v-else class="doc-items">
      <!-- 上传区域（始终展示在列表顶部） -->
      <DocumentUploader @notify="handleNotify" />

      <div
        v-for="doc in ragStore.currentDocuments"
        :key="doc.documentId"
        class="doc-item"
      >
        <div class="doc-info">
          <span class="doc-name">{{ doc.fileName }}</span>
          <span class="doc-meta">
            {{ doc.format.toUpperCase() }} · {{ formatFileSize(doc.fileSize) }} · {{ formatTime(doc.uploadTime) }}
          </span>
        </div>

        <div class="doc-right">
          <!-- 状态标签 -->
          <span
            class="status-tag"
            :class="statusClass(doc.status)"
            :title="doc.failReason ?? ''"
          >
            <span v-if="doc.status === 'PROCESSING'" class="status-spinner" />
            {{ statusLabel(doc.status) }}
            <span v-if="doc.status === 'COMPLETED'" class="chunk-count">{{ doc.chunkCount }} 块</span>
          </span>

          <!-- 失败原因（FAILED 时展示，hover 显示完整内容） -->
          <span
            v-if="doc.status === 'FAILED' && doc.failReason"
            class="fail-reason"
            :title="doc.failReason"
          >
            {{ doc.failReason }}
          </span>

          <!-- 删除按钮 -->
          <button class="doc-delete-btn" @click="handleDelete(doc)">删除</button>

          <!-- 查看分块按钮（CR-001 新增，AC-040：仅 COMPLETED 文档显示） -->
          <button
            v-if="doc.status === 'COMPLETED'"
            class="btn-view-chunks"
            @click="handleViewChunks(doc)"
          >
            查看分块
          </button>
        </div>
      </div>
    </div>

    <!-- 分块详情抽屉（CR-001 新增，AC-038） -->
    <DocumentChunkDrawer
      v-model:visible="showChunkDrawer"
      :document-id="chunkDocumentId"
      :chunk-count="chunkCount"
    />
  </div>
</template>

<style scoped>
.document-list {
  flex: 1;
  overflow-y: auto;
  padding: var(--spacing-md);
}

/* 空状态 */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--spacing-md);
  height: 100%;
}

.empty-hint {
  color: var(--text-muted);
  font-size: 14px;
}

/* 文档列表 */
.doc-items {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.doc-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--spacing-sm) var(--spacing-md);
  background: var(--bg-input);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  transition: border-color 0.2s;
}

.doc-item:hover {
  border-color: var(--accent-dim);
}

.doc-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  flex: 1;
}

.doc-name {
  font-size: 14px;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.doc-meta {
  font-size: 12px;
  color: var(--text-muted);
  font-family: var(--font-display);
}

.doc-right {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  flex-shrink: 0;
}

/* 状态标签 */
.status-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border-radius: var(--radius-sm);
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;
}

.status-pending {
  background: rgba(128, 128, 128, 0.2);
  color: var(--text-muted);
}

.status-processing {
  background: rgba(0, 116, 255, 0.15);
  color: #5b9cf5;
}

.status-completed {
  background: rgba(0, 212, 184, 0.15);
  color: var(--accent);
}

.status-failed {
  background: rgba(255, 77, 79, 0.15);
  color: var(--danger);
}

.chunk-count {
  font-size: 11px;
  opacity: 0.8;
}

/* 处理中加载动画 */
.status-spinner {
  width: 10px;
  height: 10px;
  border: 2px solid currentColor;
  border-top-color: transparent;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* 失败原因 */
.fail-reason {
  font-size: 12px;
  color: var(--danger);
  max-width: 200px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 删除按钮 */
.doc-delete-btn {
  padding: 4px 8px;
  background: transparent;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  color: var(--text-muted);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
}

.doc-delete-btn:hover {
  border-color: var(--danger);
  color: var(--danger);
}

/* 查看分块按钮（CR-001 新增） */
.btn-view-chunks {
  padding: 4px 8px;
  background: transparent;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  color: var(--accent);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-view-chunks:hover {
  border-color: var(--accent);
  background: rgba(0, 212, 184, 0.1);
}
</style>
