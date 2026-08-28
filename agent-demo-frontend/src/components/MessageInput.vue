<script setup lang="ts">
import { ref, computed, nextTick, onMounted } from 'vue';
import { useSessionStore } from '@/stores/session';
import { useSkillStore } from '@/stores/skill';
import KnowledgeBaseSelector from './KnowledgeBaseSelector.vue';
import ModelSelector from './ModelSelector.vue';
import ToolSelector from './ToolSelector.vue';
import type { KnowledgeBase, TokenUsage, LlmModel, SkillInfo } from '@/types';

const props = withDefaults(defineProps<{
  isStreaming: boolean;
  /** 是否正在等待用户回复 HITL 问题（Task-10），可选，默认 false 向前兼容 */
  isWaitingForUserInput?: boolean;
  /** 可选知识库列表（Task-08，AC-029），默认空数组 */
  knowledgeBases?: KnowledgeBase[];
  /** 选中的知识库名称列表（Task-08，AC-029），默认空数组 */
  selectedKnowledgeBases?: string[];
  /** 用户选中的可选工具 ID 列表（工具按需加载），默认空数组 */
  selectedTools?: string[];
  /** 当前选中的模型 ID（Task-22 模型选择集成） */
  selectedModel?: string;
  /** chat 模型列表（Task-22），默认空数组 */
  models?: LlmModel[];
  /** 是否已配置 LLM（Task-22），默认 true 向前兼容 */
  hasConfig?: boolean;
}>(), {
  isWaitingForUserInput: false,
  knowledgeBases: () => [],
  selectedKnowledgeBases: () => [],
  selectedTools: () => [],
  selectedModel: '',
  models: () => [],
  hasConfig: true,
});

const emit = defineEmits<{
  send: [message: string];
  stop: [];
  /** 知识库选择变更（Task-08，AC-029） */
  'update:selectedKnowledgeBases': [value: string[]];
  /** 工具选择变更（工具按需加载） */
  'update:selectedTools': [value: string[]];
  /** 模型选择变更（Task-22） */
  'update:selectedModel': [value: string];
  /** 跳转到 LLM 配置视图（Task-22 空状态引导） */
  'navigate-to-config': [];
}>();

const inputText = ref('');
const textareaRef = ref<HTMLTextAreaElement | null>(null);

/** 消息长度上限（AC-015） */
const MAX_LENGTH = 4000;

/**
 * 是否具备可用的 chat 模型（Task-22）
 * 业务含义：未配置 LLM 或无 chat 类型模型时，输入区禁用并展示引导。
 */
const hasModels = computed(() => props.hasConfig && props.models.length > 0);

/** 是否可发送（非空且未超长且未在流式中且具备可用模型） */
const canSend = computed(
  () => hasModels.value && inputText.value.trim().length > 0 && inputText.value.length <= MAX_LENGTH && !props.isStreaming,
);

/** 是否超长 */
const isOverLimit = computed(() => inputText.value.length > MAX_LENGTH);

/** 字符计数 */
const charCount = computed(() => inputText.value.length);

/**
 * 当前会话的累计 Token 用量（Task-19 新增）
 * 业务含义：从 session store 读取当前会话的 tokenUsage，用于输入区底部展示。
 */
const sessionStore = useSessionStore();
const sessionTokenUsage = computed<TokenUsage | undefined>(() => {
  const session = sessionStore.sessions.find((s) => s.sessionId === sessionStore.currentSessionId);
  return session?.tokenUsage;
});

/** Token 数千分位格式化（Task-19） */
function formatTokens(n: number): string {
  return n.toLocaleString('en-US');
}

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

// ===== CR-001 交互改进：/skill 技能列表选择 =====

const skillStore = useSkillStore();

// 加载技能列表（供 /skill 前缀选择）
onMounted(() => {
  skillStore.loadSkills().catch(() => {
    // 技能加载失败静默处理（对话不受影响）
  });
});

/** 是否显示技能列表（输入以 /skill 开头且非流式时） */
const showSkillSuggest = computed(
  () => !props.isStreaming && /^\/skill/.test(inputText.value.trim()),
);

/** /skill 后的输入关键字（用于过滤技能列表） */
const skillKeyword = computed(() => {
  const m = inputText.value.trim().match(/^\/skill\s*(.*)$/);
  return m ? m[1] : '';
});

/** 过滤后的可用技能（按名称或 id 匹配关键字） */
const filteredSkills = computed(() => {
  const kw = skillKeyword.value.toLowerCase();
  return skillStore.skills.filter(
    (s) => s.enabled && (!kw || s.name.toLowerCase().includes(kw) || s.id.toLowerCase().includes(kw)),
  );
});

/**
 * 选择技能：补全为 "/skill 技能id "（用户继续输入消息，发送时经 ChatWindow 解析指定技能）
 */
function selectSkill(skill: SkillInfo) {
  if (props.isStreaming) return;
  inputText.value = `/skill ${skill.id} `;
  nextTick(autoResize);
  nextTick(() => textareaRef.value?.focus());
}

// ===== 前缀命令提示条（/plan Task-19 + /skill CR-001，可发现性）=====

/** 是否显示前缀命令提示条（输入以 / 开头且非流式时） */
const showPlanHint = computed(
  () => !props.isStreaming && inputText.value.trim().startsWith('/'),
);

/**
 * 点击提示条自动补全对应前缀（/plan 强制任务拆解 /skill 指定技能）
 * 业务含义：提升 /plan 与 /skill 指令的可发现性（需求 8.1 + AC-N07）。
 */
function applyPlanHint(cmd: 'plan' | 'skill') {
  if (props.isStreaming) return;
  const trimmed = inputText.value.trim();
  if (trimmed === '' || trimmed === '/' || (cmd === 'skill' && trimmed.startsWith('/s'))) {
    inputText.value = cmd === 'plan' ? '/plan ' : '/skill ';
    nextTick(autoResize);
    nextTick(() => textareaRef.value?.focus());
  }
}
</script>

<template>
  <div class="message-input">
    <!-- 未配置 LLM 空状态引导（Task-22） -->
    <div v-if="!props.hasConfig" class="config-empty-state">
      <span class="empty-text">请先配置 LLM 模型</span>
      <button class="btn-go-config" @click="emit('navigate-to-config')">去配置</button>
    </div>

    <!-- 无 chat 类型模型引导（Task-22）：输入区禁用 -->
    <div v-else-if="props.models.length === 0" class="config-empty-state">
      <span class="empty-text">请先配置 chat 类型模型</span>
      <button class="btn-go-config" @click="emit('navigate-to-config')">去配置</button>
    </div>

    <!-- 工具标签栏（独立一行，位于输入框上方，紧凑不拥挤） -->
    <div class="tool-bar" :class="{ disabled: isStreaming }">
      <ToolSelector
        :model-value="props.selectedTools"
        :disabled="props.isStreaming"
        @update:model-value="emit('update:selectedTools', $event)"
      />
    </div>

    <div class="input-wrapper" :class="{ disabled: isStreaming }">
      <textarea
        ref="textareaRef"
        v-model="inputText"
        class="textarea"
        :placeholder="isStreaming ? '生成中...' : props.isWaitingForUserInput ? '请回复上方问题...' : '输入消息，Enter 发送，Shift+Enter 换行'"
        :disabled="isStreaming || !hasModels"
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

    <!-- 前缀命令提示条（/plan Task-19 + /skill CR-001，可发现性） -->
    <div v-if="showPlanHint" class="plan-hint">
      <span class="plan-hint-item" @click="applyPlanHint('plan')">
        <span class="plan-hint-icon">📋</span>
        <span class="plan-hint-text">/plan 强制任务拆解</span>
      </span>
      <span class="plan-hint-item" @click="applyPlanHint('skill')">
        <span class="plan-hint-icon">✦</span>
        <span class="plan-hint-text">/skill 指定技能</span>
      </span>
    </div>

    <!-- CR-001 交互改进：/skill 技能列表选择（输入 /skill 后弹出，点击补全） -->
    <div v-if="showSkillSuggest" class="skill-suggest">
      <div
        v-for="skill in filteredSkills"
        :key="skill.id"
        class="skill-suggest-item"
        @mousedown.prevent="selectSkill(skill)"
      >
        <span class="skill-suggest-name">{{ skill.name }}</span>
        <span class="skill-suggest-id">{{ skill.id }}</span>
      </div>
      <div v-if="filteredSkills.length === 0" class="skill-suggest-empty">暂无匹配技能</div>
    </div>

    <!-- 字符计数 + 超长提示 -->
    <div class="input-footer">
      <!-- 模型选择器（Task-22）：流式或无可选模型时禁用 -->
      <ModelSelector
        :model-value="props.selectedModel"
        :models="props.models"
        :disabled="props.isStreaming || !hasModels"
        @update:model-value="emit('update:selectedModel', $event)"
      />
      <!-- 知识库选择器（Task-08，AC-029）：流式时禁用 -->
      <KnowledgeBaseSelector
        :model-value="props.selectedKnowledgeBases"
        :knowledge-bases="props.knowledgeBases"
        :disabled="props.isStreaming"
        @update:model-value="emit('update:selectedKnowledgeBases', $event)"
      />
      <span v-if="isOverLimit" class="char-warn">
        消息长度不能超过 {{ MAX_LENGTH }} 字符
      </span>
      <!-- Token 消耗展示（Task-19）：有用量时显示累计 Token 数，估算值标记"估算" -->
      <div class="token-usage" v-if="sessionTokenUsage">
        <span class="token-count">{{ formatTokens(sessionTokenUsage.totalTokens) }} Tokens</span>
        <span class="token-badge" v-if="sessionTokenUsage.estimated">估算</span>
      </div>
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

/* 未配置模型/LLM 空状态引导（Task-22） */
.config-empty-state {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-sm);
  margin-bottom: var(--spacing-sm);
  border: 1px dashed var(--border);
  border-radius: var(--radius-sm);
}

.empty-text {
  font-size: 13px;
  color: var(--text-secondary);
}

.btn-go-config {
  padding: 2px var(--spacing-sm);
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

/* 工具标签栏（独立一行，位于输入框上方） */
.tool-bar {
  margin-bottom: 6px;
  padding: 0 2px;
}

.tool-bar.disabled {
  opacity: 0.5;
  pointer-events: none;
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

/* 前缀命令提示条（/plan /skill，可发现性） */
.plan-hint {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
  padding: 6px 10px;
  border: 1px dashed var(--accent-dim);
  border-radius: var(--radius-sm);
  background: var(--accent-dim);
  color: var(--accent);
  font-size: 12px;
}

.plan-hint-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  transition: all 0.2s;
}

.plan-hint-item:hover {
  color: #fff;
}

.plan-hint-icon {
  font-size: 14px;
}

.plan-hint-text {
  font-family: var(--font-display);
}

/* CR-001 交互改进：/skill 技能列表选择 */
.skill-suggest {
  display: flex;
  flex-direction: column;
  margin-top: 6px;
  max-height: 200px;
  overflow-y: auto;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-sidebar);
  box-shadow: var(--shadow-sm);
}

.skill-suggest-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--spacing-sm);
  padding: 6px 10px;
  cursor: pointer;
  transition: background 0.15s;
}

.skill-suggest-item:hover {
  background: var(--bg-hover);
}

.skill-suggest-name {
  font-size: 13px;
  color: var(--text-primary);
}

.skill-suggest-id {
  font-size: 11px;
  color: var(--text-muted);
  font-family: var(--font-display);
}

.skill-suggest-empty {
  padding: 8px 10px;
  font-size: 12px;
  color: var(--text-muted);
  text-align: center;
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

/* Token 消耗展示（Task-19）：暗色底 + 青色 accent */
.token-usage {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 8px;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.5);
}

.token-badge {
  padding: 1px 4px;
  font-size: 10px;
  background: rgba(255, 165, 0, 0.2);
  color: rgba(255, 165, 0, 0.8);
  border-radius: 3px;
}
</style>
