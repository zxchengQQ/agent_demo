<script setup lang="ts">
import { ref, watch, nextTick } from 'vue';
import type { Message } from '@/types';
import MessageItem from './MessageItem.vue';

const props = defineProps<{
  messages: Message[];
}>();

const listRef = ref<HTMLDivElement | null>(null);

/** 自动滚动到底部（AC-020） */
function scrollToBottom() {
  nextTick(() => {
    const el = listRef.value;
    if (el) el.scrollTop = el.scrollHeight;
  });
}

// 消息变化时滚动
watch(
  () => props.messages.length,
  () => scrollToBottom(),
);

// 消息内容变化时滚动（流式追加）
watch(
  () => props.messages.map((m) => m.content).join(''),
  () => scrollToBottom(),
);
</script>

<template>
  <div ref="listRef" class="message-list">
    <!-- 空会话欢迎语 -->
    <div v-if="props.messages.length === 0" class="empty-hint">
      <div class="empty-icon">AI</div>
      <p class="empty-title">开始新的对话</p>
      <p class="empty-desc">输入消息，与 AI Agent 交互</p>
    </div>

    <!-- 消息列表 -->
    <MessageItem
      v-for="msg in props.messages"
      :key="msg.id"
      :message="msg"
    />
  </div>
</template>

<style scoped>
.message-list {
  flex: 1;
  overflow-y: auto;
  padding: var(--spacing-lg);
}

.empty-hint {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: var(--text-muted);
}

.empty-icon {
  width: 64px;
  height: 64px;
  border-radius: var(--radius-lg);
  background: var(--accent-dim);
  color: var(--accent);
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: var(--font-display);
  font-size: 20px;
  font-weight: 700;
  margin-bottom: var(--spacing-md);
}

.empty-title {
  font-size: 16px;
  color: var(--text-secondary);
  margin-bottom: var(--spacing-xs);
}

.empty-desc {
  font-size: 13px;
  color: var(--text-muted);
}
</style>
