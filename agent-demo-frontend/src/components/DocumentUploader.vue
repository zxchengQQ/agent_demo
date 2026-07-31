<script setup lang="ts">
import { ref } from 'vue';
import { useRagStore } from '@/stores/rag';

/**
 * 文档上传组件（Task-14，AC-007/AC-008/AC-022/AC-023/AC-025/AC-033/AC-034）
 * 业务含义：支持拖拽和点击选择文件，校验格式与大小后调用 store 上传文档到当前知识库。
 * 校验失败的文件跳过，不影响其他文件上传；上传成功后文档出现在列表中（store 自动更新）。
 */

const ragStore = useRagStore();

const emit = defineEmits<{
  /** Toast 通知事件，父组件接收后展示提示 */
  notify: [message: string];
}>();

/** 拖拽高亮状态 */
const isDragover = ref(false);
/** 隐藏的文件输入 ref */
const fileInput = ref<HTMLInputElement | null>(null);

/** 支持的文件格式 */
const SUPPORTED_FORMATS = ['txt', 'md', 'pdf'];
/** 最大文件大小 10MB（AC-025） */
const MAX_FILE_SIZE = 10 * 1024 * 1024;

/**
 * 校验单个文件：格式和大小（AC-022/AC-023）
 * 业务含义：格式不在 txt/md/pdf 范围或超过 10MB 的文件跳过，Toast 提示原因。
 */
function validateFile(file: File): boolean {
  // 格式校验：扩展名必须在 txt/md/pdf 范围内
  const ext = file.name.split('.').pop()?.toLowerCase() || '';
  if (!SUPPORTED_FORMATS.includes(ext)) {
    emit('notify', '仅支持 txt、md、pdf 格式');
    return false;
  }
  // 大小校验：单文件不超过 10MB
  if (file.size > MAX_FILE_SIZE) {
    emit('notify', '文件大小不能超过 10MB');
    return false;
  }
  return true;
}

/**
 * 处理文件列表：逐个校验并上传（AC-033/AC-034）
 * 业务含义：批量上传时逐个处理，校验失败的文件跳过不影响其他文件。
 */
async function handleFiles(files: FileList | File[]) {
  // 前置校验：未选择知识库时提示用户先创建/选择
  if (!ragStore.currentKnowledgeBaseId) {
    emit('notify', '请先选择或创建知识库后再上传文件');
    return;
  }
  const fileArray = Array.from(files);
  for (const file of fileArray) {
    if (!validateFile(file)) continue;
    try {
      await ragStore.uploadDocument(file);
    } catch (err) {
      // 上传失败时 Toast 提示错误信息
      const message = err instanceof Error ? err.message : '上传失败';
      emit('notify', message);
    }
  }
}

/** 拖拽进入：高亮反馈 */
function onDragEnter() {
  isDragover.value = true;
}

/** 拖拽经过：阻止默认行为以允许 drop，保持高亮 */
function onDragOver(e: DragEvent) {
  e.preventDefault();
  isDragover.value = true;
}

/** 拖拽离开：取消高亮 */
function onDragLeave() {
  isDragover.value = false;
}

/** 拖拽释放：处理文件 */
function onDrop(e: DragEvent) {
  e.preventDefault();
  isDragover.value = false;
  const files = e.dataTransfer?.files;
  if (files && files.length > 0) {
    handleFiles(files);
  }
}

/** 点击上传区域：触发文件选择对话框 */
function onClick() {
  fileInput.value?.click();
}

/** 文件选择 change 事件 */
function onFileChange(e: Event) {
  const input = e.target as HTMLInputElement;
  if (input.files && input.files.length > 0) {
    handleFiles(input.files);
  }
  // 重置 input value 以便重复选择同一文件
  input.value = '';
}
</script>

<template>
  <div
    class="upload-area"
    :class="{ dragover: isDragover }"
    @click="onClick"
    @dragenter="onDragEnter"
    @dragover="onDragOver"
    @dragleave="onDragLeave"
    @drop="onDrop"
  >
    <input
      ref="fileInput"
      type="file"
      multiple
      accept=".txt,.md,.pdf"
      class="file-input"
      @change="onFileChange"
    />
    <div class="upload-hint">
      <span class="upload-icon">⬆</span>
      <span class="upload-text">拖拽文件到此处或点击上传</span>
      <span class="upload-formats">支持 txt、md、pdf 格式，单文件不超过 10MB</span>
    </div>
  </div>
</template>

<style scoped>
.upload-area {
  border: 2px dashed var(--border);
  border-radius: var(--radius-md);
  padding: var(--spacing-lg);
  text-align: center;
  cursor: pointer;
  transition: all 0.2s;
  background: var(--bg-input);
}

/* 拖拽高亮反馈：border 变为 accent 色，背景微亮 */
.upload-area.dragover {
  border-color: var(--accent);
  background: var(--accent-dim);
}

.file-input {
  display: none;
}

.upload-hint {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--spacing-xs);
  color: var(--text-muted);
  font-size: 13px;
}

.upload-icon {
  font-size: 24px;
  color: var(--accent);
}

.upload-text {
  color: var(--text-secondary);
}

.upload-formats {
  font-size: 12px;
  color: var(--text-muted);
}
</style>
