<script setup lang="ts">
import { ref, computed, nextTick } from 'vue';

const props = withDefaults(defineProps<{
  isStreaming: boolean;
  /** 是否开启深度思考（CR-001，AC-021），可选，默认 false 向前兼容 */
  enableThinking?: boolean;
}>(), {
  enableThinking: false,
});

const emit = defineEmits<{
  send: [message: string];
  stop: [];
  /** 切换深度思考开关（CR-001，AC-021） */
  toggleThinking: [];
}>();

const inputText = ref('');
const textareaRef = ref<HTMLTextAreaElement | null>(null);

/** 消息长度上限（AC-015） */
const MAX_LENGTH = 4000;

/** 是否可发送（非空且未超长且未在流式中） */
const canSend = computed(
  () => inputText.value.trim().length > 0 && inputText.value.length <= MAX_LENGTH && !props.isStreaming,
);

/** 是否超长 */
const isOverLimit = computed(() => inputText.value.length > MAX_LENGTH);

/** 字符计数 */
const charCount = computed(() => inputText.value.length);

/** 自适应高度 */
function autoResize() {
  const el = textareaRef.value;
  if (!el) return;
  el.style.height = 'auto';
  el.style.height = Math.min(el.scrollHeight, 200) + 'px';
}

/** 发送消息 */
function handleSend() {
  if (!canSend.value) return;
  const msg = inputText.value.trim();
  inputText.value = '';
  nextTick(autoResize);
  emit('send', msg);
}

/** Enter 发送，Shift+Enter 换行 */
function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    handleSend();
  }
}
</script>

<template>
  <div class="message-input">
    <div class="input-wrapper" :class="{ disabled: isStreaming }">
      <textarea
        ref="textareaRef"
        v-model="inputText"
        class="textarea"
        :placeholder="isStreaming ? '生成中...' : '输入消息，Enter 发送，Shift+Enter 换行'"
        :disabled="isStreaming"
        rows="1"
        @input="autoResize"
        @keydown="handleKeydown"
      ></textarea>

      <!-- 流式时显示停止按钮，否则显示发送按钮 -->
      <button
        v-if="isStreaming"
        class="btn btn-stop"
        @click="emit('stop')"
      >
        停止生成
      </button>
      <button
        v-else
        class="btn btn-send"
        :disabled="!canSend"
        @click="handleSend"
      >
        发送
      </button>
    </div>

    <!-- 字符计数 + 超长提示 -->
    <div class="input-footer">
      <!-- 深度思考 toggle（CR-001，AC-021）：开启时高亮，点击切换状态 -->
      <button
        class="btn-thinking"
        :class="{ active: props.enableThinking }"
        @click="emit('toggleThinking')"
      >
        🧠 深度思考
      </button>
      <span v-if="isOverLimit" class="char-warn">
        消息长度不能超过 {{ MAX_LENGTH }} 字符
      </span>
      <span class="char-count" :class="{ over: isOverLimit }">
        {{ charCount }} / {{ MAX_LENGTH }}
      </span>
    </div>
  </div>
</template>

<style scoped>
.message-input {
  padding: var(--spacing-md);
  border-top: 1px solid var(--border);
  background: var(--bg-sidebar);
}

.input-wrapper {
  display: flex;
  gap: var(--spacing-sm);
  align-items: flex-end;
  background: var(--bg-input);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: var(--spacing-sm);
  transition: border-color 0.2s;
}

.input-wrapper:focus-within {
  border-color: var(--accent-dim);
}

.input-wrapper.disabled {
  opacity: 0.6;
}

.textarea {
  flex: 1;
  background: transparent;
  border: none;
  outline: none;
  color: var(--text-primary);
  font-family: var(--font-body);
  font-size: 14px;
  line-height: 1.6;
  resize: none;
  max-height: 200px;
}

.textarea::placeholder {
  color: var(--text-muted);
}

.btn {
  flex-shrink: 0;
  padding: var(--spacing-sm) var(--spacing-md);
  border: none;
  border-radius: var(--radius-sm);
  font-family: var(--font-display);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-send {
  background: var(--accent);
  color: var(--bg-primary);
}

.btn-send:hover:not(:disabled) {
  box-shadow: var(--shadow-glow);
}

.btn-send:disabled {
  background: var(--border);
  color: var(--text-muted);
  cursor: not-allowed;
}

.btn-stop {
  background: var(--danger);
  color: white;
  animation: pulse 1.5s infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.7; }
}

.input-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--spacing-md);
  margin-top: var(--spacing-xs);
  min-height: 18px;
}

/* 深度思考 toggle 按钮（CR-001，AC-021） */
.btn-thinking {
  padding: 2px var(--spacing-sm);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-muted);
  font-family: var(--font-display);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-thinking:hover {
  border-color: var(--accent-dim);
  color: var(--accent);
}

/* 开启时高亮 */
.btn-thinking.active {
  border-color: var(--accent);
  background: var(--accent-dim);
  color: var(--accent);
}

.char-warn {
  color: var(--danger);
  font-size: 11px;
}

.char-count {
  font-family: var(--font-display);
  font-size: 11px;
  color: var(--text-muted);
}

.char-count.over {
  color: var(--danger);
}
</style>
