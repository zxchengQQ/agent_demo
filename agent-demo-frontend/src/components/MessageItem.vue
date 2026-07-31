<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import type { Message, SubTaskStatus } from '@/types';
import { renderMarkdown } from '@/utils/markdown';
import KnowledgeSourceBar from './KnowledgeSourceBar.vue';

const props = defineProps<{ message: Message }>();

/**
 * 推理区块展开状态（AC-022）
 * 业务含义：流式中保持展开让用户实时看到推理过程；完成后默认折叠，用户可手动展开回看。
 * 初始值依据当前消息状态：incomplete 展开，complete 折叠。
 */
const isThinkingExpanded = ref(props.message.status === 'incomplete');

// 监听状态变化：流式完成时自动折叠（流式 -> 完成的过渡场景）
watch(
  () => props.message.status,
  (newStatus) => {
    if (newStatus === 'complete') {
      isThinkingExpanded.value = false;
      isReactExpanded.value = false;
      isTaskExpanded.value = false;
    } else if (newStatus === 'incomplete') {
      isThinkingExpanded.value = true;
      isReactExpanded.value = true;
      isTaskExpanded.value = true;
    }
  },
);

/** 推理区块标题：流式中"思考中..."，完成后"已思考"（AC-022） */
const thinkingTitle = computed(() =>
  props.message.status === 'incomplete' ? '思考中...' : '已思考',
);

/**
 * 切换推理区块展开/折叠（AC-022）
 * 业务含义：流式中保持展开不可切换（用户需看到完整推理过程），完成后允许手动切换。
 */
function toggleThinking() {
  if (props.message.status === 'incomplete') return;
  isThinkingExpanded.value = !isThinkingExpanded.value;
}

// ===== ReAct 推理过程折叠区块 =====

/**
 * ReAct 推理区块展开状态
 * 业务含义：流式中保持展开让用户实时看到 ReAct 推理过程；完成后默认折叠，用户可手动展开回看。
 */
const isReactExpanded = ref(props.message.status === 'incomplete');

/** 是否有 ReAct 推理步骤需要展示 */
const hasReactSteps = computed(
  () => !!props.message.reactSteps && props.message.reactSteps.length > 0,
);

/** ReAct 区块标题：流式中"推理中..."，完成后"ReAct 推理过程" */
const reactTitle = computed(() =>
  props.message.status === 'incomplete' ? '推理中...' : 'ReAct 推理过程',
);

/**
 * 切换 ReAct 区块展开/折叠
 * 业务含义：流式中保持展开不可切换，完成后允许手动切换。
 */
function toggleReact() {
  if (props.message.status === 'incomplete') return;
  isReactExpanded.value = !isReactExpanded.value;
}

/**
 * 助手消息 Markdown 渲染内容（AC-023）
 * 业务含义：助手正式回复按 Markdown 格式渲染，content 为空时返回空字符串避免空渲染。
 * XSS 防护已由 renderMarkdown 内部 DOMPurify 处理。
 */
const renderedContent = computed(() => {
  if (!props.message.content) return '';
  return renderMarkdown(props.message.content);
});

// ===== CR-002 新增：任务拆解折叠区块（AC-003, AC-005, AC-015, AC-016）=====

/**
 * 任务列表区块展开状态（AC-015）
 * 业务含义：流式中保持展开让用户实时看到任务进度；完成后默认折叠，用户可手动展开回看。
 */
const isTaskExpanded = ref(props.message.status === 'incomplete');

/** 已展开子任务序号集合（in-progress/completed 状态的子任务可展开，AC-005 CR-001 更新） */
const expandedSubTasks = ref(new Set<number>());

/**
 * 监听子任务状态变化，pending->in-progress 时自动展开（CR-001 新增，AC-017）
 * 业务含义：子任务开始执行时自动展开详情，用户无需手动点击即可看到实时 ReAct 过程
 */
watch(
  () => props.message.subTasks?.map((st) => `${st.index}:${st.status}`).join(','),
  () => {
    if (!props.message.subTasks) return;
    for (const subTask of props.message.subTasks) {
      if (subTask.status === 'in-progress' && !expandedSubTasks.value.has(subTask.index)) {
        expandedSubTasks.value.add(subTask.index);
        expandedSubTasks.value = new Set(expandedSubTasks.value);
      }
    }
  },
);

/** 任务列表标题：流式中"任务拆解（X/Y 已完成）"，完成后"已完成 Y 个子任务"（AC-015） */
const taskListTitle = computed(() => {
  if (!props.message.subTasks) return '';
  const completed = props.message.subTasks.filter((t) => t.status === 'completed').length;
  const total = props.message.subTasks.length;
  if (props.message.status === 'incomplete') {
    return `任务拆解（${completed}/${total} 已完成）`;
  }
  return `已完成 ${total} 个子任务`;
});

/**
 * 切换任务列表展开/折叠（AC-015）
 * 业务含义：流式中保持展开不可切换，完成后允许手动切换。
 */
function toggleTaskList() {
  if (props.message.status === 'incomplete') return;
  isTaskExpanded.value = !isTaskExpanded.value;
}

/**
 * 切换子任务详情展开/折叠（AC-005，CR-001 更新）
 * 业务含义：in-progress 和 completed 状态的子任务可展开查看执行详情。
 */
function toggleSubTask(index: number) {
  const subTask = props.message.subTasks?.find((st) => st.index === index);
  // 业务含义：in-progress 和 completed 状态的子任务可展开（CR-001 变更：原仅 completed 可展开）
  if (!subTask || (subTask.status !== 'completed' && subTask.status !== 'in-progress')) return;
  if (expandedSubTasks.value.has(index)) {
    expandedSubTasks.value.delete(index);
  } else {
    expandedSubTasks.value.add(index);
  }
  // 触发响应式更新（Set 的 add/delete 不自动触发）
  expandedSubTasks.value = new Set(expandedSubTasks.value);
}

/**
 * 子任务状态图标映射（AC-016）
 * pending=○, in-progress=◐, completed=✓, failed=✕, cancelled=-
 */
function statusIcon(status: SubTaskStatus): string {
  switch (status) {
    case 'pending':
      return '○';
    case 'in-progress':
      return '◐';
    case 'completed':
      return '✓';
    case 'failed':
      return '✕';
    case 'cancelled':
      return '-';
    default:
      return '○';
  }
}
</script>

<template>
  <div
    class="message-item fade-in"
    :class="props.message.role"
  >
    <!-- 助手头像 -->
    <div v-if="props.message.role === 'assistant'" class="avatar">AI</div>

    <div class="message-content">
      <!-- 推理折叠区块（CR-001，AC-022）：助手消息 reasoning 非空时显示在正式回复上方 -->
      <div
        v-if="props.message.role === 'assistant' && props.message.reasoning"
        class="thinking-block"
      >
        <div class="thinking-header" @click="toggleThinking">
          <span class="thinking-icon">{{ isThinkingExpanded ? '▼' : '▶' }}</span>
          <span class="thinking-title">{{ thinkingTitle }}</span>
        </div>
        <div
          class="thinking-content"
          :style="{ display: isThinkingExpanded ? 'block' : 'none' }"
        >
          {{ props.message.reasoning }}
        </div>
      </div>

      <!-- ReAct 推理过程折叠区块：位于 thinking-block 下方、bubble 上方 -->
      <div
        v-if="props.message.role === 'assistant' && hasReactSteps"
        class="react-block"
      >
        <div class="react-header" @click="toggleReact">
          <span class="react-icon">{{ isReactExpanded ? '▼' : '▶' }}</span>
          <span class="react-title">{{ reactTitle }}</span>
        </div>
        <div
          class="react-content"
          :style="{ display: isReactExpanded ? 'block' : 'none' }"
        >
          <!-- 按 iteration 分组展示 -->
          <div
            v-for="step in props.message.reactSteps"
            :key="step.iteration"
            class="react-step"
          >
            <!-- Thought 文本 -->
            <div v-if="step.thought" class="react-thought">
              <span class="react-label">Thought</span>
              <span class="react-thought-text">{{ step.thought }}</span>
            </div>
            <!-- Action 工具调用卡片 -->
            <div
              v-for="(toolCall, idx) in step.toolCalls"
              :key="idx"
              class="tool-card"
            >
              <div class="tool-card-header">
                <span class="tool-icon">🔧</span>
                <span class="tool-name">{{ toolCall.toolName }}</span>
              </div>
              <div class="tool-args">
                <span class="tool-args-label">参数:</span>
                <code class="tool-args-code">{{ toolCall.arguments }}</code>
              </div>
              <!-- Observation 结果 -->
              <div v-if="toolCall.result" class="tool-result">
                <span class="tool-result-label">结果:</span>
                <span class="tool-result-text">{{ toolCall.result }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 任务拆解折叠区块（CR-002，AC-015）：位于 react-block 下方、bubble 上方 -->
      <div
        v-if="props.message.role === 'assistant' && props.message.subTasks && props.message.subTasks.length > 0"
        class="task-block"
      >
        <div class="task-header" @click="toggleTaskList">
          <span class="task-icon">{{ isTaskExpanded ? '▼' : '▶' }}</span>
          <span class="task-title">{{ taskListTitle }}</span>
        </div>
        <div
          class="task-content"
          :style="{ display: isTaskExpanded ? 'block' : 'none' }"
        >
          <div
            v-for="subTask in props.message.subTasks"
            :key="subTask.index"
            class="subtask-item"
            :class="subTask.status"
          >
            <!-- 子任务头部：状态图标 + 序号 + 标题 -->
            <div class="subtask-header" @click="toggleSubTask(subTask.index)">
              <span class="subtask-status-icon">{{ statusIcon(subTask.status) }}</span>
              <span class="subtask-index">{{ subTask.index }}.</span>
              <span class="subtask-title">{{ subTask.title }}</span>
            </div>
            <!-- 子任务详情（in-progress/completed 可展开，AC-005 CR-001 更新） -->
            <div
              v-if="expandedSubTasks.has(subTask.index) && (subTask.status === 'completed' || subTask.status === 'in-progress')"
              class="subtask-detail"
            >
              <!-- 推理内容（如有） -->
              <div v-if="subTask.reasoning" class="subtask-reasoning">{{ subTask.reasoning }}</div>
              <!-- ReAct 步骤 -->
              <div
                v-for="step in subTask.reactSteps"
                :key="step.iteration"
                class="react-step"
              >
                <div v-if="step.thought" class="react-thought">
                  <span class="react-label">Thought</span>
                  <span class="react-thought-text">{{ step.thought }}</span>
                </div>
                <div
                  v-for="(toolCall, tcIdx) in step.toolCalls"
                  :key="tcIdx"
                  class="tool-card"
                >
                  <div class="tool-card-header">
                    <span class="tool-icon">🔧</span>
                    <span class="tool-name">{{ toolCall.toolName }}</span>
                  </div>
                  <div class="tool-args">
                    <span class="tool-args-label">参数:</span>
                    <code class="tool-args-code">{{ toolCall.arguments }}</code>
                  </div>
                  <div v-if="toolCall.result" class="tool-result">
                    <span class="tool-result-label">结果:</span>
                    <span class="tool-result-text">{{ toolCall.result }}</span>
                  </div>
                </div>
              </div>
              <!-- 执行结果 -->
              <div v-if="subTask.content" class="subtask-result">{{ subTask.content }}</div>
            </div>
            <!-- 失败原因（AC-006） -->
            <div
              v-if="subTask.status === 'failed' && subTask.error"
              class="subtask-error"
            >
              {{ subTask.error }}
            </div>
          </div>
        </div>
      </div>

      <!-- 消息气泡 -->
      <div
        class="bubble"
        :class="{
          'streaming': props.message.status === 'incomplete' && props.message.content,
          'error': props.message.status === 'error',
        }"
      >
        <!-- 助手消息：Markdown 渲染（AC-023），content 为空时不渲染 -->
        <div
          v-if="props.message.role === 'assistant' && props.message.content"
          class="markdown-body"
          v-html="renderedContent"
        ></div>
        <!-- 用户消息：纯文本（AC-023） -->
        <span v-else class="text">{{ props.message.content }}</span>
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

      <!-- 知识库引用来源条（CR-002，AC-043）：助手消息底部，使用知识库检索时显示 -->
      <KnowledgeSourceBar
        v-if="props.message.role === 'assistant' && props.message.knowledgeSources && props.message.knowledgeSources.length > 0"
        :sources="props.message.knowledgeSources"
      />
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

/* ===== CR-001 推理折叠区块样式（AC-022）===== */
.thinking-block {
  margin-bottom: var(--spacing-sm);
  background: var(--bg-sidebar);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  overflow: hidden;
}

.thinking-header {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  padding: var(--spacing-xs) var(--spacing-sm);
  cursor: pointer;
  user-select: none;
  font-family: var(--font-display);
  font-size: 12px;
  color: var(--text-muted);
  transition: background 0.2s;
}

.thinking-header:hover {
  background: var(--bg-input);
}

.thinking-icon {
  font-size: 10px;
}

.thinking-title {
  font-weight: 500;
}

.thinking-content {
  padding: var(--spacing-sm);
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-muted);
  border-top: 1px solid var(--border);
  white-space: pre-wrap;
  word-break: break-word;
}

/* ===== ReAct 推理过程折叠区块样式 ===== */
.react-block {
  margin-bottom: var(--spacing-sm);
  background: var(--bg-sidebar);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  overflow: hidden;
}

.react-header {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  padding: var(--spacing-xs) var(--spacing-sm);
  cursor: pointer;
  user-select: none;
  font-family: var(--font-display);
  font-size: 12px;
  color: var(--text-muted);
  transition: background 0.2s;
}

.react-header:hover {
  background: var(--bg-input);
}

.react-icon {
  font-size: 10px;
}

.react-title {
  font-weight: 500;
}

.react-content {
  padding: var(--spacing-sm);
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-muted);
  border-top: 1px solid var(--border);
}

.react-step {
  margin-bottom: var(--spacing-sm);
}

.react-step:last-child {
  margin-bottom: 0;
}

.react-thought {
  margin-bottom: var(--spacing-xs);
}

.react-label {
  font-weight: 600;
  color: var(--accent);
  margin-right: var(--spacing-xs);
}

.react-thought-text {
  white-space: pre-wrap;
  word-break: break-word;
}

.tool-card {
  background: var(--bg-input);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: var(--spacing-xs) var(--spacing-sm);
  margin-top: var(--spacing-xs);
}

.tool-card-header {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  font-family: var(--font-display);
  font-size: 12px;
  color: var(--text-secondary);
}

.tool-icon {
  font-size: 12px;
}

.tool-name {
  font-weight: 600;
}

.tool-args {
  margin-top: var(--spacing-xs);
  font-size: 12px;
}

.tool-args-label {
  color: var(--text-muted);
  margin-right: var(--spacing-xs);
}

.tool-args-code {
  background: var(--bg-sidebar);
  padding: 2px 6px;
  border-radius: var(--radius-sm);
  font-family: var(--font-mono, monospace);
  font-size: 0.9em;
}

.tool-result {
  margin-top: var(--spacing-xs);
  font-size: 12px;
  color: var(--text-secondary);
}

.tool-result-label {
  color: var(--text-muted);
  margin-right: var(--spacing-xs);
}

.tool-result-text {
  white-space: pre-wrap;
  word-break: break-word;
  /* Bug2 修复：限制工具结果长度，超长内容可滚动查看 */
  max-height: 150px;
  overflow-y: auto;
}

/* ===== CR-001 Markdown 渲染样式（AC-023）===== */
.markdown-body {
  font-size: 14px;
  line-height: 1.7;
}

.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3),
.markdown-body :deep(h4),
.markdown-body :deep(h5),
.markdown-body :deep(h6) {
  margin: var(--spacing-sm) 0 var(--spacing-xs);
  font-weight: 600;
  line-height: 1.3;
}

.markdown-body :deep(h1) {
  font-size: 1.5em;
}

.markdown-body :deep(h2) {
  font-size: 1.3em;
}

.markdown-body :deep(h3) {
  font-size: 1.15em;
}

.markdown-body :deep(p) {
  margin: var(--spacing-xs) 0;
}

.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  margin: var(--spacing-xs) 0;
  padding-left: 1.5em;
}

.markdown-body :deep(li) {
  margin: 2px 0;
}

.markdown-body :deep(code) {
  background: var(--bg-input);
  padding: 2px 6px;
  border-radius: var(--radius-sm);
  font-family: var(--font-mono, monospace);
  font-size: 0.9em;
}

.markdown-body :deep(pre) {
  background: var(--bg-input);
  padding: var(--spacing-sm);
  border-radius: var(--radius-sm);
  overflow-x: auto;
  margin: var(--spacing-xs) 0;
}

.markdown-body :deep(pre code) {
  background: transparent;
  padding: 0;
}

.markdown-body :deep(table) {
  border-collapse: collapse;
  margin: var(--spacing-xs) 0;
  width: 100%;
}

.markdown-body :deep(th),
.markdown-body :deep(td) {
  border: 1px solid var(--border);
  padding: var(--spacing-xs) var(--spacing-sm);
  text-align: left;
}

.markdown-body :deep(th) {
  background: var(--bg-sidebar);
  font-weight: 600;
}

.markdown-body :deep(blockquote) {
  margin: var(--spacing-xs) 0;
  padding-left: var(--spacing-md);
  border-left: 3px solid var(--accent-dim);
  color: var(--text-muted);
}

.markdown-body :deep(a) {
  color: var(--accent);
  text-decoration: none;
}

.markdown-body :deep(a:hover) {
  text-decoration: underline;
}

.markdown-body :deep(hr) {
  border: none;
  border-top: 1px solid var(--border);
  margin: var(--spacing-sm) 0;
}
</style>
