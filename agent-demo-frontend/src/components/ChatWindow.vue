<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useSessionStore } from '@/stores/session';
import { useRagStore } from '@/stores/rag';
import { useLlmStore } from '@/stores/llm';
import { streamChat } from '@/api/chat';
import type { Message } from '@/types';
import MessageList from './MessageList.vue';
import MessageInput from './MessageInput.vue';

const store = useSessionStore();
const ragStore = useRagStore();
const llmStore = useLlmStore();
const isStreaming = ref(false);

/** 跳转到 LLM 配置视图（Task-22 空状态引导） */
const emit = defineEmits<{
  'navigate-to-config': [];
}>();

// 初始化：加载 chat 模型列表、配置状态、恢复上次使用的模型（Task-22）
onMounted(() => {
  llmStore.loadChatModels();
  llmStore.loadConfigStatus();
  llmStore.initLastUsedModelId();
});

/** 是否具备可用的 chat 模型（Task-22 空状态判断） */
const hasChatModels = computed(
  () => !!(llmStore.configStatus?.hasConfig) && llmStore.chatModels.length > 0,
);

/**
 * 当前会话选中的模型 ID（Task-22）
 * 业务含义：优先会话级选择；会话未选则回退到上次使用；仍无则回退到第一个可用模型。
 * 当选中的模型被删除时（不再存在于 chatModels），自动回退到第一个可用模型。
 */
const selectedModel = computed(() => {
  const sessionId = store.currentSessionId;
  const sessionModel = store.getModel(sessionId);
  if (sessionModel && llmStore.chatModels.some((m) => m.id === sessionModel)) {
    return sessionModel;
  }
  if (llmStore.lastUsedModelId && llmStore.chatModels.some((m) => m.id === llmStore.lastUsedModelId)) {
    return llmStore.lastUsedModelId;
  }
  return llmStore.chatModels[0]?.id ?? '';
});

/** 模型选择变更处理（Task-22）：更新会话级状态 + 持久化 lastUsedModelId */
function handleModelChange(modelId: string) {
  if (!modelId) return;
  store.setModel(store.currentSessionId, modelId);
  llmStore.setLastUsedModelId(modelId);
}

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
  // Task-22: 无可用 chat 模型时禁止发送（含未配置）
  if (!hasChatModels.value) return;
  // Task-22: 获取当前选中的模型 ID（可能为空，后端使用默认模型）
  const modelId = selectedModel.value;

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
      modelId,
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
        // ReAct: 最终答案标记。
        // BUG 修复：正式内容（最终答案）已通过 onToken 流式追加到 content，
        // 不再调用 moveThoughtToContent（避免 content 重复追加）。
        onFinalAnswer: (_iteration: number) => {
          // 无操作：最终答案已由 token 事件流式显示
        },
        // Task-17: Token 消耗统计，累加到当前会话
        onUsage: (usage) => {
          store.addTokenUsage(store.currentSessionId, usage);
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

        // CR-002: 知识库来源信息累积写入消息（AC-043）
        onSources: (sources) => {
          store.addKnowledgeSources(assistantMsgId, sources);
        },
      },
      abortController.signal,
    );
  } finally {
    isStreaming.value = false;
    abortController = null;
  }
  // Task-22: 发送后记录上次使用的模型（持久化到 localStorage，刷新后恢复）
  if (modelId) {
    llmStore.setLastUsedModelId(modelId);
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

    <!-- 无可用 chat 模型/未配置 LLM 空状态引导（Task-22）：禁用发送 -->
    <div v-if="!hasChatModels" class="config-guide">
      <p class="guide-text">请先配置 LLM 模型</p>
      <button class="btn-go-config" @click="emit('navigate-to-config')">去配置</button>
    </div>

    <!-- 输入区（Task-22 集成模型选择） -->
    <MessageInput
      v-else
      :is-streaming="isStreaming"
      :enable-thinking="enableThinking"
      :enable-task-breakdown="enableTaskBreakdown"
      :knowledge-bases="ragStore.knowledgeBases"
      :selected-knowledge-bases="selectedKnowledgeBases"
      :selected-model="selectedModel"
      :models="llmStore.chatModels"
      :has-config="!!llmStore.configStatus?.hasConfig"
      @send="sendMessage"
      @stop="stopGeneration"
      @toggle-thinking="enableThinking = !enableThinking"
      @toggle-task-breakdown="enableTaskBreakdown = !enableTaskBreakdown"
      @update:selected-knowledge-bases="handleKnowledgeBasesChange"
      @update:selected-model="handleModelChange"
      @navigate-to-config="emit('navigate-to-config')"
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

/* 未配置模型空状态引导（Task-22） */
.config-guide {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-md);
  border-top: 1px solid var(--border);
  background: var(--bg-sidebar);
}

.guide-text {
  font-size: 13px;
  color: var(--text-secondary);
}

.btn-go-config {
  padding: var(--spacing-xs) var(--spacing-md);
  border: 1px solid var(--accent-dim);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--accent);
  font-family: var(--font-display);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-go-config:hover {
  background: var(--accent-dim);
}
</style>
