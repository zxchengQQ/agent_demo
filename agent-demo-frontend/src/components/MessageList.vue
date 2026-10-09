<script setup lang="ts">
import { ref, watch, nextTick } from 'vue';
import type { Message } from '@/types';
import MessageItem from './MessageItem.vue';

const props = defineProps<{
  messages: Message[];
}>();

/** 向上传递 AskUserCard 回复事件（unified-chat-mode Task-17：值语义=用户回复，选项值与输入文本统一） */
const emit = defineEmits<{
  reply: [value: string];
  /** 工具权限确认：批准（Task-17，AC-N03） */
  approve: [];
  /** 工具权限确认：拒绝（Task-17，AC-S02） */
  deny: [];
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

// 消息内容或状态变化时滚动（流式追加 + 任务完成时显示总结，Bug1 修复）
watch(
  () => props.messages.map((m) => `${m.content}:${m.status}`).join(''),
  () => scrollToBottom(),
);

// 人机交互卡片出现时滚动到底部（BUG 修复：ask_user/tool_confirm 事件写入 askUserData
// 不改变 content/status，上述 watch 不触发，卡片渲染在视口外，用户需手动拖动滚动条
// 才能感知需要回复/审核。监听卡片数据指纹（形态 + 问题/工具名 + 历史条数），
// 覆盖首次触发、多轮同气泡镜像更新与历史增长；用户回答（answer）/决策（approved）
// 写入不改变指纹，避免锁定态回看时多余滚动）
watch(
  () => props.messages
    .map((m) => {
      const hitl = m.askUserData;
      if (!hitl) return '';
      const identity = hitl.kind === 'permission' ? (hitl.toolName ?? '') : hitl.question;
      return `${hitl.kind ?? 'askUser'}:${identity}:${m.askUserHistory?.length ?? 0}`;
    })
    .join('|'),
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
      @reply="emit('reply', $event)"
      @approve="emit('approve')"
      @deny="emit('deny')"
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
