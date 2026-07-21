<script setup lang="ts">
import type { Message } from '@/types';

const props = defineProps<{ message: Message }>();
</script>

<template>
  <div
    class="message-item fade-in"
    :class="props.message.role"
  >
    <!-- 助手头像 -->
    <div v-if="props.message.role === 'assistant'" class="avatar">AI</div>

    <div class="message-content">
      <!-- 消息气泡 -->
      <div
        class="bubble"
        :class="{
          'streaming': props.message.status === 'incomplete' && props.message.content,
          'error': props.message.status === 'error',
        }"
      >
        <span class="text">{{ props.message.content }}</span>
        <span
          v-if="props.message.status === 'incomplete' && props.message.content"
          class="stream-cursor"
        ></span>
      </div>

      <!-- 状态标记 -->
      <div v-if="props.message.status === 'incomplete' && !props.message.content" class="status-hint">
        生成中...
      </div>
      <div v-if="props.message.status === 'incomplete' && props.message.content" class="status-hint incomplete">
        回复不完整
      </div>
      <div v-if="props.message.status === 'error'" class="status-hint error">
        发生错误
      </div>
    </div>
  </div>
</template>

<style scoped>
.message-item {
  display: flex;
  gap: var(--spacing-sm);
  margin-bottom: var(--spacing-md);
}

/* 用户消息：右对齐 */
.message-item.user {
  flex-direction: row-reverse;
}

.message-item.user .bubble {
  background: var(--bg-msg-user);
  border-right: 2px solid var(--accent);
  border-radius: var(--radius-md) var(--radius-sm) var(--radius-md) var(--radius-md);
}

/* 助手消息：左对齐 */
.message-item.assistant .bubble {
  background: var(--bg-msg-assistant);
  border-left: 2px solid var(--accent);
  border-radius: var(--radius-sm) var(--radius-md) var(--radius-md) var(--radius-md);
}

.avatar {
  flex-shrink: 0;
  width: 32px;
  height: 32px;
  border-radius: var(--radius-sm);
  background: var(--accent-dim);
  color: var(--accent);
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: var(--font-display);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 1px;
}

.message-content {
  max-width: 75%;
  display: flex;
  flex-direction: column;
}

.message-item.user .message-content {
  align-items: flex-end;
}

.bubble {
  padding: var(--spacing-sm) var(--spacing-md);
  font-size: 14px;
  line-height: 1.6;
  word-break: break-word;
  transition: box-shadow 0.2s;
}

.bubble.streaming {
  box-shadow: var(--shadow-glow);
}

.bubble.error {
  border-color: var(--danger) !important;
  color: var(--danger);
}

.status-hint {
  font-size: 11px;
  color: var(--text-muted);
  margin-top: var(--spacing-xs);
  font-family: var(--font-display);
}

.status-hint.incomplete {
  color: var(--warning);
}

.status-hint.error {
  color: var(--danger);
}
</style>
