<script setup lang="ts">
import { ref, watch } from 'vue';
import { getDocumentChunks } from '@/api/rag';
import type { DocumentChunk } from '@/types';

/**
 * 文档分块详情抽屉（CR-001 新增，AC-038~AC-042）
 * 业务含义：右侧滑出抽屉面板，展示文档切分后的分块列表。
 * 每个分块显示索引、文本内容（截断显示，可展开）和字符数。
 */

const props = defineProps<{
  /** 抽屉显示状态 */
  visible: boolean;
  /** 当前查看的文档 ID */
  documentId: string;
  /** 分块总数（用于标题展示） */
  chunkCount: number;
}>();

const emit = defineEmits<{
  'update:visible': [value: boolean];
}>();

/** 分块列表 */
const chunks = ref<DocumentChunk[]>([]);
/** 加载状态 */
const loading = ref(false);
/** 展开的分块索引集合 */
const expandedChunks = ref<Set<number>>(new Set());

/** 截断显示阈值 */
const TRUNCATE_LENGTH = 200;

/** 加载分块数据 */
async function loadChunks() {
  loading.value = true;
  try {
    chunks.value = await getDocumentChunks(props.documentId);
  } catch {
    // AC-042: 接口异常时关闭抽屉
    chunks.value = [];
    emit('update:visible', false);
  } finally {
    loading.value = false;
  }
}

/** 展开/收起分块 */
function toggleExpand(index: number) {
  if (expandedChunks.value.has(index)) {
    expandedChunks.value.delete(index);
  } else {
    expandedChunks.value.add(index);
  }
}

/** 判断分块内容是否需要截断 */
function isTruncated(chunk: DocumentChunk): boolean {
  return chunk.content.length > TRUNCATE_LENGTH;
}

/** 获取截断后的内容 */
function getTruncatedContent(content: string): string {
  return content.slice(0, TRUNCATE_LENGTH) + '...';
}

/** 判断分块是否展开 */
function isExpanded(index: number): boolean {
  return expandedChunks.value.has(index);
}

/** 关闭抽屉 */
function closeDrawer() {
  emit('update:visible', false);
}

/** 监听 visible 变化 */
watch(
  () => props.visible,
  (val) => {
    if (val && props.documentId) {
      chunks.value = [];
      expandedChunks.value.clear();
      loadChunks();
    } else {
      chunks.value = [];
      expandedChunks.value.clear();
      loading.value = false;
    }
  },
  { immediate: true },
);
</script>

<template>
  <div v-if="visible" class="drawer-overlay" @click="closeDrawer">
    <div class="chunk-drawer" @click.stop>
      <!-- 头部 -->
      <div class="drawer-header">
        <h2 class="drawer-title">分块详情（{{ chunkCount }} 个）</h2>
        <button class="btn-close" @click="closeDrawer">✕</button>
      </div>

      <!-- 加载中 -->
      <div v-if="loading" class="loading-state">
        <span class="loading-text">加载中...</span>
      </div>

      <!-- 空状态 -->
      <div v-else-if="chunks.length === 0" class="empty-state">
        <span>该文档无分块数据</span>
      </div>

      <!-- 分块列表 -->
      <div v-else class="chunk-list">
        <div
          v-for="chunk in chunks"
          :key="chunk.chunkIndex"
          class="chunk-item"
        >
          <!-- 分块头部：索引 + 字符数 -->
          <div class="chunk-header">
            <span class="chunk-index">分块 {{ chunk.chunkIndex + 1 }}/{{ chunks.length }}</span>
            <span class="chunk-char-count">{{ chunk.charCount }} 字符</span>
          </div>

          <!-- 分块内容 -->
          <div v-if="isTruncated(chunk) && !isExpanded(chunk.chunkIndex)" class="chunk-content-wrapper">
            <p class="chunk-content btn-content-truncated">{{ getTruncatedContent(chunk.content) }}</p>
            <button class="btn-expand" @click="toggleExpand(chunk.chunkIndex)">展开</button>
          </div>
          <div v-else-if="isTruncated(chunk) && isExpanded(chunk.chunkIndex)" class="chunk-content-wrapper">
            <p class="chunk-content">{{ chunk.content }}</p>
            <button class="btn-expand" @click="toggleExpand(chunk.chunkIndex)">收起</button>
          </div>
          <div v-else class="chunk-content-wrapper">
            <p class="chunk-content">{{ chunk.content }}</p>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.drawer-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  justify-content: flex-end;
  z-index: 200;
}

.chunk-drawer {
  background: var(--bg-sidebar);
  border-left: 1px solid var(--border);
  width: 480px;
  max-width: 90vw;
  display: flex;
  flex-direction: column;
  box-shadow: var(--shadow-sm);
}

.drawer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--spacing-md) var(--spacing-lg);
  border-bottom: 1px solid var(--border);
}

.drawer-title {
  font-family: var(--font-display);
  font-size: 15px;
  font-weight: 700;
  color: var(--text-primary);
}

.btn-close {
  background: none;
  border: none;
  color: var(--text-muted);
  font-size: 16px;
  cursor: pointer;
  padding: var(--spacing-xs);
  border-radius: var(--radius-sm);
  transition: all 0.2s;
}

.btn-close:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.loading-state {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--spacing-xl);
}

.loading-text {
  font-size: 13px;
  color: var(--text-muted);
}

.empty-state {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--spacing-xl);
  font-size: 13px;
  color: var(--text-muted);
}

.chunk-list {
  flex: 1;
  overflow-y: auto;
  padding: var(--spacing-md) var(--spacing-lg);
}

.chunk-item {
  margin-bottom: var(--spacing-md);
  padding: var(--spacing-md);
  background: var(--bg-input);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
}

.chunk-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--spacing-sm);
}

.chunk-index {
  font-family: var(--font-display);
  font-size: 12px;
  font-weight: 600;
  color: var(--accent);
}

.chunk-char-count {
  font-family: var(--font-display);
  font-size: 11px;
  color: var(--text-muted);
}

.chunk-content-wrapper {
  position: relative;
}

.chunk-content {
  font-size: 13px;
  color: var(--text-primary);
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  margin: 0;
}

.btn-expand {
  background: none;
  border: none;
  color: var(--accent);
  font-size: 12px;
  cursor: pointer;
  padding: var(--spacing-xs) 0;
  margin-top: var(--spacing-xs);
}

.btn-expand:hover {
  text-decoration: underline;
}
</style>
