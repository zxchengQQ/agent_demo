<script setup lang="ts">
import { ref, computed } from 'vue';
import { useSessionStore } from '@/stores/session';
import { streamChat } from '@/api/chat';
import type { Message } from '@/types';
import MessageList from './MessageList.vue';
import MessageInput from './MessageInput.vue';

const store = useSessionStore();
const isStreaming = ref(false);
let abortController: AbortController | null = null;

/** 当前会话的消息列表 */
const currentMessages = computed<Message[]>(() => {
  const session = store.sessions.find((s) => s.sessionId === store.currentSessionId);
  return session?.messages ?? [];
});

/** 生成消息 ID */
function generateId(): string {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
}

/**
 * 发送消息（AC-002: 流式输出）
 * 业务含义：用户发送消息 -> 创建助手占位 -> 流式接收 -> 完成/中断/错误
 */
async function sendMessage(message: string) {
  // AC-014: 空消息拦截
  if (!message.trim()) return;

  // 确保有当前会话
  if (!store.currentSessionId) {
    store.createNewSession();
  }
  const sessionId = store.currentSessionId;

  // 乐观添加用户消息
  store.addMessage(sessionId, {
    id: generateId(),
    role: 'user',
    content: message,
    createdAt: Date.now(),
    status: 'complete',
  });

  // 创建助手消息占位（流式追加内容）
  const assistantMsgId = generateId();
  store.addMessage(sessionId, {
    id: assistantMsgId,
    role: 'assistant',
    content: '',
    createdAt: Date.now(),
    status: 'incomplete',
  });

  // 流式调用
  isStreaming.value = true;
  abortController = new AbortController();

  try {
    await streamChat(
      sessionId,
      message,
      {
        // AC-010: 透明续聊 - 后端返回新 sessionId 时更新关联
        onSession: (newSessionId: string) => {
          store.updateSessionId(sessionId, newSessionId);
        },
        // AC-020: 逐字追加
        onToken: (token: string) => {
          store.appendContent(assistantMsgId, token);
        },
        // 流式完成
        onDone: () => {
          store.markComplete(assistantMsgId);
          store.touchSession(store.currentSessionId);
          // AC-006: 首条消息生成标题
          const session = store.sessions.find(
            (s) => s.sessionId === store.currentSessionId,
          );
          if (session && session.messages.length === 2) {
            store.generateTitle(store.currentSessionId, message);
          }
        },
        // AC-012/AC-013: 错误处理
        onError: (msg: string) => {
          store.markError(assistantMsgId, msg);
        },
      },
      abortController.signal,
    );
  } finally {
    isStreaming.value = false;
    abortController = null;
  }
}

/**
 * 停止生成（AC-011）
 * 业务含义：中断流式，已接收内容保留并标记 incomplete
 */
function stopGeneration() {
  abortController?.abort();
  isStreaming.value = false;
}
</script>

<template>
  <div class="chat-window">
    <!-- 消息列表区 -->
    <MessageList :messages="currentMessages" />

    <!-- 输入区 -->
    <MessageInput
      :is-streaming="isStreaming"
      @send="sendMessage"
      @stop="stopGeneration"
    />
  </div>
</template>

<style scoped>
.chat-window {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--bg-primary);
}
</style>
