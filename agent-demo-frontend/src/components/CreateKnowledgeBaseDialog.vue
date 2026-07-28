<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { useRagStore } from '@/stores/rag';

/**
 * 创建知识库弹窗组件（Task-13，AC-004/AC-018~AC-021/AC-030~AC-032）
 * 业务含义：知识库创建表单弹窗，含名称和描述字段的实时校验。
 * 名称支持中英文/数字/下划线/连字符（1-50 字符），描述最长 200 字符。
 * 提交成功后关闭弹窗并通知父组件；失败时弹窗内显示错误，不关闭。
 */

const props = defineProps<{
  /** 弹窗是否可见 */
  visible: boolean;
}>();

const emit = defineEmits<{
  /** 关闭弹窗 */
  'update:visible': [value: boolean];
  /** 创建成功 */
  created: [];
}>();

const ragStore = useRagStore();

/** 表单字段 */
const name = ref('');
const description = ref('');

/** 提交错误信息（后端返回，如名称重复） */
const submitError = ref('');

/** 名称正则：仅中英文、数字、下划线、连字符（AC-019） */
const NAME_REGEX = /^[\u4e00-\u9fa5a-zA-Z0-9_-]+$/;
/** 名称最大长度（AC-020） */
const NAME_MAX = 50;
/** 描述最大长度（AC-021） */
const DESC_MAX = 200;

/** 名称校验错误信息（实时计算） */
const nameError = computed(() => {
  if (name.value.length === 0) return '';
  if (name.value.length > NAME_MAX) return '名称不能超过 50 个字符';
  if (!NAME_REGEX.test(name.value)) return '仅允许中英文、数字、下划线和连字符';
  return '';
});

/** 描述校验错误信息（实时计算） */
const descError = computed(() => {
  if (description.value.length > DESC_MAX) return '描述不能超过 200 个字符';
  return '';
});

/** 名称是否合法 */
const isNameValid = computed(() => {
  return name.value.length > 0 && name.value.length <= NAME_MAX && NAME_REGEX.test(name.value);
});

/** 描述是否合法 */
const isDescValid = computed(() => {
  return description.value.length <= DESC_MAX;
});

/** 是否可以提交 */
const canSubmit = computed(() => {
  return isNameValid.value && isDescValid.value;
});

/** 关闭弹窗 */
function closeDialog() {
  emit('update:visible', false);
}

/** 提交创建知识库（AC-004） */
async function handleSubmit() {
  if (!canSubmit.value) return;
  submitError.value = '';
  try {
    await ragStore.createKnowledgeBase(name.value, description.value);
    emit('created');
    emit('update:visible', false);
  } catch (err) {
    // 后端返回错误（如名称重复，AC-030）：弹窗内显示错误，不关闭
    submitError.value = (err as Error).message;
  }
}

/** 弹窗打开时重置表单 */
watch(() => props.visible, (newVal) => {
  if (newVal) {
    name.value = '';
    description.value = '';
    submitError.value = '';
  }
});
</script>

<template>
  <div v-if="visible" class="dialog-overlay" @click="closeDialog">
    <div class="dialog" @click.stop>
      <h2 class="dialog-title">新建知识库</h2>

      <!-- 名称字段 -->
      <div class="form-field">
        <label class="field-label">
          名称 <span class="required">*</span>
        </label>
        <input
          v-model="name"
          class="name-input"
          placeholder="请输入知识库名称"
          :maxlength="NAME_MAX + 10"
        />
        <p v-if="nameError" class="field-error name-error">{{ nameError }}</p>
      </div>

      <!-- 描述字段 -->
      <div class="form-field">
        <label class="field-label">描述（可选）</label>
        <textarea
          v-model="description"
          class="desc-input"
          placeholder="请输入知识库描述"
          rows="3"
        />
        <div class="desc-footer">
          <span v-if="descError" class="field-error desc-error">{{ descError }}</span>
          <span class="char-count">{{ description.length }}/{{ DESC_MAX }}</span>
        </div>
      </div>

      <!-- 后端错误提示（AC-030） -->
      <p v-if="submitError" class="dialog-error">{{ submitError }}</p>

      <!-- 操作按钮 -->
      <div class="dialog-actions">
        <button class="btn-cancel" @click="closeDialog">取消</button>
        <button
          class="btn-submit"
          :disabled="!canSubmit"
          @click="handleSubmit"
        >
          确定
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dialog-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 200;
}

.dialog {
  background: var(--bg-sidebar);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: var(--spacing-lg);
  width: 420px;
  box-shadow: var(--shadow-sm);
}

.dialog-title {
  font-family: var(--font-display);
  font-size: 16px;
  font-weight: 700;
  color: var(--text-primary);
  margin-bottom: var(--spacing-md);
}

.form-field {
  margin-bottom: var(--spacing-md);
}

.field-label {
  display: block;
  font-size: 13px;
  color: var(--text-secondary);
  margin-bottom: var(--spacing-xs);
}

.required {
  color: var(--danger);
}

.name-input,
.desc-input {
  width: 100%;
  padding: var(--spacing-sm) var(--spacing-md);
  background: var(--bg-input);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  color: var(--text-primary);
  font-size: 13px;
  font-family: var(--font-body);
  outline: none;
  transition: border-color 0.2s;
}

.name-input:focus,
.desc-input:focus {
  border-color: var(--accent);
}

.desc-input {
  resize: vertical;
  min-height: 60px;
}

.field-error {
  font-size: 12px;
  color: var(--danger);
  margin-top: var(--spacing-xs);
}

.desc-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: var(--spacing-xs);
}

.char-count {
  font-family: var(--font-display);
  font-size: 11px;
  color: var(--text-muted);
  margin-left: auto;
}

.dialog-error {
  font-size: 13px;
  color: var(--danger);
  padding: var(--spacing-sm) var(--spacing-md);
  background: rgba(255, 68, 102, 0.1);
  border-radius: var(--radius-sm);
  margin-bottom: var(--spacing-md);
}

.dialog-actions {
  display: flex;
  gap: var(--spacing-sm);
  justify-content: flex-end;
}

.btn-cancel,
.btn-submit {
  padding: var(--spacing-sm) var(--spacing-lg);
  border-radius: var(--radius-sm);
  font-size: 13px;
  cursor: pointer;
  border: none;
  transition: all 0.2s;
}

.btn-cancel {
  background: var(--bg-input);
  color: var(--text-secondary);
}

.btn-cancel:hover {
  background: var(--bg-hover);
}

.btn-submit {
  background: var(--accent);
  color: var(--bg-primary);
}

.btn-submit:hover:not(:disabled) {
  background: var(--accent-glow);
}

.btn-submit:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
</style>
