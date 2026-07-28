<script setup lang="ts">
import { ref, computed } from 'vue';
import { useSessionStore } from '@/stores/session';
import { useRagStore } from '@/stores/rag';
import { streamChat } from '@/api/chat';
import type { Message } from '@/types';
import MessageList from './MessageList.vue';
import MessageInput from './MessageInput.vue';

const store = useSessionStore();
const ragStore = useRagStore();
const isStreaming = ref(false);

/**
 * 深度思考开关状态（CR-001，AC-021）
 * 业务含义：用户通过 MessageInput 的 toggle 按钮控制，影响下一条消息的 streamChat 调用参数。
 * 状态在当前会话内保持，切换会话时不影响其他会话。
 */
const enableThinking = ref(false);

/**
 * 复杂任务拆解开关状态（CR-002，AC-012）
 * 业务含义：用户通过 MessageInput 的 toggle 按钮控制，影响下一条消息的 streamChat 调用参数。
 * 与深度思考独立共存，可同时开启。状态在当前会话内保持。
 */
const enableTaskBreakdown = ref(false);

/**
 * 当前会话的知识库选择（Task-09，AC-012/AC-014）
 * 业务含义：从 session store 读取，按会话隔离。空数组表示"自动"模式（Agent 自主检索）。
 */
const selectedKnowledgeBases = computed(() =>
  store.getKnowledgeBases(store.currentSessionId),
);

/**
 * 知识库选择变更处理（Task-09，AC-014）
 * 业务含义：用户通过 KnowledgeBaseSelector 切换选择时，更新 session store 中的会话级状态。
 */
function handleKnowledgeBasesChange(bases: string[]) {
  store.setKnowledgeBases(store.currentSessionId, bases);
}

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
    reasoning: '',
  });

  // 创建助手消息占位（流式追加内容）
  const assistantMsgId = generateId();
  store.addMessage(sessionId, {
    id: assistantMsgId,
    role: 'assistant',
    content: '',
    createdAt: Date.now(),
    status: 'incomplete',
    reasoning: '',
  });

  // 流式调用
  isStreaming.value = true;
  abortController = new AbortController();

  try {
    await streamChat(
      sessionId,
      message,
      enableThinking.value,
      enableTaskBreakdown.value,
      selectedKnowledgeBases.value,
      {
        // AC-010: 透明续聊 - 后端返回新 sessionId 时更新关联
        onSession: (newSessionId: string) => {
          store.updateSessionId(sessionId, newSessionId);
        },
        // AC-020: 逐字追加
        onToken: (token: string) => {
          store.appendContent(assistantMsgId, token);
        },
        // CR-001: 推理过程流式展示（AC-022），追加到助手消息 reasoning 字段
        onReasoning: (reasoning: string) => {
          store.appendReasoning(assistantMsgId, reasoning);
        },
        // ReAct: 思考内容，追加到对应 iteration 的 reactStep
        onThought: (thought: string, iteration: number) => {
          store.appendThought(assistantMsgId, thought, iteration);
        },
        // ReAct: 工具调用，追加工具调用信息
        onAction: (toolName: string, args: string, iteration: number) => {
          store.appendAction(assistantMsgId, toolName, args, iteration);
        },
        // ReAct: 工具结果，追加到对应工具调用
        onObservation: (result: string, iteration: number) => {
          store.appendObservation(assistantMsgId, result, iteration);
        },
        // ReAct: 最终答案，将 thought 移入 content
        onFinalAnswer: (iteration: number) => {
          store.moveThoughtToContent(assistantMsgId, iteration);
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

        // ===== CR-002 任务拆解回调（AC-001, AC-003, AC-005, AC-006）=====

        // 任务规划完成，初始化子任务列表（AC-001）
        onTaskPlan: (tasks) => {
          store.initSubTasks(assistantMsgId, tasks);
        },
        // 子任务开始执行，状态变为 in-progress（AC-003）
        onTaskStart: (index, _title) => {
          store.updateSubTaskStatus(assistantMsgId, index, 'in-progress');
        },
        // 子任务执行内容片段（AC-005）
        onTaskToken: (index, content) => {
          store.appendSubTaskContent(assistantMsgId, index, content);
        },
        // 子任务推理片段（AC-011）
        onTaskReasoning: (index, content) => {
          store.appendSubTaskReasoning(assistantMsgId, index, content);
        },
        // 子任务 ReAct 思考（AC-005）
        onTaskThought: (index, content, iteration) => {
          store.appendSubTaskThought(assistantMsgId, index, content, iteration);
        },
        // 子任务工具调用（AC-005）
        onTaskAction: (index, toolName, args, iteration) => {
          store.appendSubTaskAction(assistantMsgId, index, toolName, args, iteration);
        },
        // 子任务工具结果（AC-005）
        onTaskObservation: (index, result, iteration) => {
          store.appendSubTaskObservation(assistantMsgId, index, result, iteration);
        },
        // 子任务完成，状态变为 completed（AC-003）
        onTaskComplete: (index) => {
          store.updateSubTaskStatus(assistantMsgId, index, 'completed');
        },
        // 子任务失败，状态变为 failed（AC-006）
        onTaskFailed: (index, error) => {
          store.updateSubTaskStatus(assistantMsgId, index, 'failed', error);
        },
        // 子任务取消，状态变为 cancelled（AC-006, AC-007）
        onTaskCancelled: (index) => {
          store.updateSubTaskStatus(assistantMsgId, index, 'cancelled');
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
      :enable-thinking="enableThinking"
      :enable-task-breakdown="enableTaskBreakdown"
      :knowledge-bases="ragStore.knowledgeBases"
      :selected-knowledge-bases="selectedKnowledgeBases"
      @send="sendMessage"
      @stop="stopGeneration"
      @toggle-thinking="enableThinking = !enableThinking"
      @toggle-task-breakdown="enableTaskBreakdown = !enableTaskBreakdown"
      @update:selected-knowledge-bases="handleKnowledgeBasesChange"
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
