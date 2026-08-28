<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import type { Message, SubTaskStatus, ToolConfirmData, AskUserData } from '@/types';
import { renderMarkdown } from '@/utils/markdown';
import { useMermaid } from '@/composables/useMermaid';
import KnowledgeSourceBar from './KnowledgeSourceBar.vue';
import AskUserCard from './AskUserCard.vue';
import ConfirmCard from './ConfirmCard.vue';

const props = defineProps<{ message: Message }>();

/**
 * 向上传递 AskUserCard 的回复事件（unified-chat-mode Task-17）
 * 业务含义：用户在统一交互卡片中回复（选项值或输入文本）后，逐层传递到 ChatWindow 发送回复消息。
 * 值语义扩展为"用户回复"（选项值与输入文本统一，AC-T04）。
 */
const emit = defineEmits<{
  reply: [value: string];
  /** 工具权限确认：批准（Task-17，AC-N03） */
  approve: [];
  /** 工具权限确认：拒绝（Task-17，AC-S02） */
  deny: [];
}>();

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
      reactManualExpanded.value = false;
      isTaskExpanded.value = false;
    } else if (newStatus === 'incomplete') {
      isThinkingExpanded.value = true;
      reactManualExpanded.value = true;
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
 * ReAct 推理区块手动展开状态（非 HITL 消息）
 * 业务含义：普通消息流式中展开、完成后折叠，可手动切换回看。
 * CR-002：含 HITL 卡片的消息不由此状态控制（恒展开，见 isReactExpanded）。
 */
const reactManualExpanded = ref(props.message.status === 'incomplete');

/**
 * ReAct 推理区块展开状态
 * 业务含义：流式中保持展开让用户实时看到 ReAct 推理过程；完成后默认折叠，用户可手动展开回看。
 * CR-002（AC-N05）：含 HITL 交互卡片的消息恒展开，保证交互卡片始终可见（等待态可交互、完成后可回看锁定态）。
 * CR-003（AC-N06）：含交互历史记录（askUserHistory）的消息同样恒展开。
 */
const isReactExpanded = computed(() => {
  if (props.message.askUserData || props.message.askUserHistory?.length) return true;
  return reactManualExpanded.value;
});

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
 * 业务含义：流式中保持展开不可切换，完成后允许手动切换；CR-002：含 HITL 卡片的消息不可折叠（恒展开）。
 */
function toggleReact() {
  if (props.message.status === 'incomplete') return;
  if (props.message.askUserData || props.message.askUserHistory?.length) return;
  reactManualExpanded.value = !reactManualExpanded.value;
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

/** Markdown 容器 ref（用于 Mermaid 渲染增强） */
const markdownBodyRef = ref<HTMLElement | null>(null);

/** Mermaid 渲染 composable：消息非流式时自动增强 Mermaid 代码块 */
const { triggerEnhance } = useMermaid(
  markdownBodyRef,
  () => props.message.status === 'incomplete'
);

/** 监听内容变化和状态变化，触发 Mermaid 渲染 */
watch(
  [renderedContent, () => props.message.status],
  () => {
    triggerEnhance();
  }
);

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

// ===== Task-17 新增：工具权限确认渲染数据 =====

/**
 * 权限确认数据（kind=permission 时从记录构造，供 ConfirmCard 渲染）
 * 业务含义：tool_confirm 四要素还原为 ConfirmCard 所需的 ToolConfirmData；非 permission 形态返回 null。
 */
function recordToolConfirmData(rec: AskUserData): ToolConfirmData | null {
  if (rec.kind !== 'permission') return null;
  return {
    toolName: rec.toolName ?? '',
    toolDescription: rec.toolDescription ?? '',
    arguments: rec.toolArguments ?? '',
  };
}

// ===== CR-002/CR-003：HITL 卡片内嵌位置关联（AC-N04/AC-N05/AC-N06）=====

/**
 * 需渲染的交互记录列表
 * 业务含义：优先取 askUserHistory（CR-003：多次审批/追问互不覆盖），
 * 无历史时回退单条 askUserData（旧数据向后兼容）。
 */
const hitlRecords = computed<AskUserData[]>(() => {
  const history = props.message.askUserHistory;
  if (history && history.length > 0) return history;
  return props.message.askUserData ? [props.message.askUserData] : [];
});

/**
 * 内嵌卡片位置映射：key `${stepIndex}:${callIndex}` -> 记录索引
 * 业务含义：HITL 交互本质是一次工具动作（askUser 追问 / 权限确认工具），
 * 将卡片内嵌到 ReAct 推理过程对应工具步骤处，语义更清晰。
 * 匹配规则：
 * - 单记录（无 askUserHistory，CR-002 行为）：匹配最后一个对应工具调用（当前轮追问）
 * - 多记录（CR-003，AC-N06）：逐条"首个未占用匹配"——askUser 类匹配 toolName==='askUser'，
 *   权限类匹配 toolName===记录工具名；已被更早记录占用的工具调用不再复用（支持同工具重复审批）
 * 无匹配的记录回退底部 ask-user-block 兜底渲染（不丢记录）。
 */
const inlineTargetByPos = computed<Map<string, number>>(() => {
  const map = new Map<string, number>();
  const records = hitlRecords.value;
  const reactSteps = props.message.reactSteps;
  if (records.length === 0 || !reactSteps) return map;
  // 单记录（无 askUserHistory，CR-002 行为）：匹配最后一个对应工具调用（当前轮追问）；
  // 多记录（CR-003，AC-N06）：逐条"首个未占用匹配"，支持同工具重复审批。
  const isSingle = records.length === 1 && !props.message.askUserHistory;
  const claimed = new Set<string>();
  records.forEach((entry, entryIndex) => {
    const targetTool = entry.kind === 'permission' ? entry.toolName : 'askUser';
    const matches: { stepIndex: number; callIndex: number }[] = [];
    reactSteps.forEach((step, stepIndex) => {
      step.toolCalls.forEach((call, callIndex) => {
        if (call.toolName === targetTool) matches.push({ stepIndex, callIndex });
      });
    });
    // 单记录取最后一个（当前轮）；多记录取第一个未占用的匹配
    let target: { stepIndex: number; callIndex: number } | null = null;
    for (const m of matches) {
      const key = `${m.stepIndex}:${m.callIndex}`;
      if (isSingle) {
        target = m;
      } else if (!claimed.has(key)) {
        target = m;
        claimed.add(key);
        break;
      }
    }
    if (target) map.set(`${target.stepIndex}:${target.callIndex}`, entryIndex);
  });
  return map;
});

/** 给定工具调用位置，返回该处应内嵌渲染的记录；无则 null */
function inlineRecordAt(stepIndex: number, callIndex: number): AskUserData | null {
  const idx = inlineTargetByPos.value.get(`${stepIndex}:${callIndex}`);
  return idx === undefined ? null : hitlRecords.value[idx];
}

/** 无内嵌匹配、需兜底渲染于底部 ask-user-block 的记录（CR-003：不丢记录） */
const fallbackRecords = computed<AskUserData[]>(() => {
  const matched = new Set(inlineTargetByPos.value.values());
  return hitlRecords.value.filter((_, index) => !matched.has(index));
});
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
            v-for="(step, stepIndex) in props.message.reactSteps"
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
              <!-- CR-002/CR-003（AC-N04/AC-N06）：HITL 交互卡片内嵌于对应工具调用步骤 -->
              <div
                v-if="inlineRecordAt(stepIndex, idx)"
                class="inline-hitl-card"
              >
                <ConfirmCard
                  v-if="inlineRecordAt(stepIndex, idx)!.kind === 'permission'"
                  :data="recordToolConfirmData(inlineRecordAt(stepIndex, idx)!)!"
                  :answered="inlineRecordAt(stepIndex, idx)!.approved !== undefined || !!inlineRecordAt(stepIndex, idx)!.answer"
                  :approved="!!inlineRecordAt(stepIndex, idx)!.approved"
                  @approve="emit('approve')"
                  @deny="emit('deny')"
                />
                <AskUserCard
                  v-else
                  :ask-user-data="inlineRecordAt(stepIndex, idx)!"
                  @reply="emit('reply', $event)"
                />
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
          ref="markdownBodyRef"
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

      <!-- HITL 人机交互区块（unified-chat-mode Task-17）：助手消息含交互记录时渲染 -->
      <!-- 权限确认形态（kind=permission）走 ConfirmCard；其余（含存量无 kind 数据）走 AskUserCard（AC-H01） -->
      <!-- CR-002/CR-003：已内嵌于 react-block 对应工具步骤的记录不在此重复渲染；
           无内嵌匹配的记录（fallbackRecords）兜底渲染于此（多条可堆叠，不丢记录） -->
      <div
        v-if="props.message.role === 'assistant' && fallbackRecords.length > 0"
        class="ask-user-block"
      >
        <template v-for="(record, index) in fallbackRecords" :key="index">
          <!-- 业务含义：权限决策语义为三态——true=已批准，false=已拒绝，undefined=待决策。
               BUG 修复：原判定 !!approved 在拒绝时（false）恒为假，卡片不锁定、按钮可重复点击；
               正确判定是 approved !== undefined（answer 兜底兼容旧会话数据） -->
          <ConfirmCard
            v-if="record.kind === 'permission'"
            :data="recordToolConfirmData(record)!"
            :answered="record.approved !== undefined || !!record.answer"
            :approved="!!record.approved"
            @approve="emit('approve')"
            @deny="emit('deny')"
          />
          <AskUserCard
            v-else
            :ask-user-data="record"
            @reply="emit('reply', $event)"
          />
        </template>
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
  /* BUG 修复：限制推理内容高度，超长内容可滚动查看，避免撑开整个页面 */
  max-height: 300px;
  overflow-y: auto;
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
  /* BUG 修复：限制 ReAct 推理过程高度，超长内容可滚动查看，避免撑开整个页面 */
  max-height: 300px;
  overflow-y: auto;
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

/* ===== unified-chat-mode Task-17 HITL 人机交互区块样式（统一交互卡片由 AskUserCard 内部渲染） ===== */
.ask-user-block {
  margin-top: var(--spacing-sm);
}

/* ===== CR-002：内嵌于 react-block 工具步骤的 HITL 卡片（AC-N04/N05） ===== */
.inline-hitl-card {
  margin-top: var(--spacing-xs);
  border-top: 1px dashed var(--border);
  padding-top: var(--spacing-xs);
}

/* 内嵌上下文抵消卡片自带 margin-top，避免工具卡片内双倍间距（Task-18 样式适配） */
.inline-hitl-card .ask-user-card,
.inline-hitl-card .confirm-card {
  margin-top: 0;
}

/* CR-001: 图片渲染样式约束（AC-039）*/
.markdown-body :deep(img) {
  max-width: 100%;
  height: auto;
  border-radius: var(--radius-sm);
  margin: var(--spacing-xs) 0;
}

/* Mermaid 图表渲染区块 */
.markdown-body :deep(.mermaid-block) {
  margin: var(--spacing-sm) 0;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  overflow: hidden;
}

.markdown-body :deep(.mermaid-toolbar) {
  display: flex;
  gap: var(--spacing-xs);
  padding: var(--spacing-xs) var(--spacing-sm);
  background: var(--bg-sidebar);
  border-bottom: 1px solid var(--border);
}

.markdown-body :deep(.mermaid-btn) {
  padding: 2px 10px;
  font-size: 0.8em;
  font-family: var(--font-body);
  color: var(--text-secondary);
  background: transparent;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all 0.15s ease;
}

.markdown-body :deep(.mermaid-btn:hover) {
  color: var(--text-primary);
  border-color: var(--accent);
}

.markdown-body :deep(.mermaid-btn--active) {
  color: var(--accent);
  background: var(--accent-dim);
  border-color: var(--accent);
}

.markdown-body :deep(.mermaid-content) {
  padding: var(--spacing-md);
  background: var(--bg-input);
  overflow-x: auto;
}

.markdown-body :deep(.mermaid-diagram) {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 40px;
}

.markdown-body :deep(.mermaid-diagram svg) {
  max-width: 100%;
  height: auto;
}

.markdown-body :deep(.mermaid-loading) {
  color: var(--text-muted);
  font-size: 0.85em;
  padding: var(--spacing-sm);
}

.markdown-body :deep(.mermaid-error) {
  color: var(--danger);
  font-size: 0.85em;
  padding: var(--spacing-sm);
  text-align: center;
}

.markdown-body :deep(.mermaid-source) {
  margin: 0;
  background: var(--bg-sidebar);
  padding: var(--spacing-sm);
  border-radius: var(--radius-sm);
  overflow-x: auto;
}

.markdown-body :deep(.mermaid-source code) {
  font-family: var(--font-display, monospace);
  font-size: 0.85em;
  color: var(--text-primary);
}

</style>
