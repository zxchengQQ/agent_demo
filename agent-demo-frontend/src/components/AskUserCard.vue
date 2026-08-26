<script setup lang="ts">
import { computed, ref } from 'vue';
import type { AskUserData } from '@/types';

/**
 * 统一人机交互卡片（unified-chat-mode Task-16 新增，替代 ConfirmCard）
 * 业务含义：text 与 confirm 两种交互形态统一为"问题区 + 交互区"卡片结构：
 * - confirm 型：垂直整行选项列表（序号徽标 + 文字 + hover 高亮 + 选中锁定）+ "其他"自由输入兜底
 * - text 型：内嵌输入框 + 提交按钮
 * - 回答锁定态：answer 存在时交互禁用，展示"已回答：{answer}"（决策 7）
 * 统一 reply 事件出口（选项值与输入文本等效回传，AC-T04）。
 */

const props = withDefaults(defineProps<{
  /** askUser 交互数据（type/question/options/retryCount/answer） */
  askUserData: AskUserData;
  /** 是否禁用（外部控制） */
  disabled?: boolean;
}>(), {
  disabled: false,
});

const emit = defineEmits<{
  /** 用户回复（选项值或自由输入文本，统一通道） */
  reply: [value: string];
}>();

/** 已选中的选项值（本地锁定，用于高亮与防重复） */
const selectedValue = ref<string | null>(null);
/** 是否已提交/选中（本地锁定） */
const isLocked = ref(false);
/** "其他"输入展开状态 */
const otherExpanded = ref(false);
/** "其他"输入框内容 */
const otherInput = ref('');
/** text 型输入框内容 */
const textInput = ref('');

/** 是否已回答（answer 存在，决策 7 锁定态） */
const answered = computed(() => !!props.askUserData.answer);

/** 是否处于可交互状态（未回答、未本地锁定、未外部禁用） */
const interactive = computed(() => !answered.value && !isLocked.value && !props.disabled);

/** 类型图标 */
const typeIcon = computed(() => (props.askUserData.type === 'confirm' ? '✅' : '❓'));

/** 追问轮次提示（retryCount > 0 时显示） */
const retryHint = computed(() =>
  props.askUserData.retryCount > 0 ? `第 ${props.askUserData.retryCount + 1} 次追问` : '',
);

/** 提交按钮是否禁用（空值禁用） */
const submitDisabled = computed(() => textInput.value.trim() === '');

/**
 * 选择选项处理（confirm 型）
 * 业务含义：选中后锁定并发出 reply（选项值）。
 */
function handleSelect(option: string) {
  if (!interactive.value) return;
  selectedValue.value = option;
  isLocked.value = true;
  emit('reply', option);
}

/**
 * 展开"其他"自由输入（confirm 型兜底，AC-T04）
 */
function expandOther() {
  if (!interactive.value) return;
  otherExpanded.value = true;
}

/**
 * 提交"其他"输入（confirm 型兜底）
 */
function submitOther() {
  const value = otherInput.value.trim();
  if (!interactive.value || value === '') return;
  isLocked.value = true;
  emit('reply', value);
}

/**
 * 提交 text 型输入（内嵌输入框，AC-T02）
 */
function submitText() {
  const value = textInput.value.trim();
  if (!interactive.value || value === '') return;
  isLocked.value = true;
  emit('reply', value);
}

/** 判断选项是否为已选（锁定态下高亮） */
function isOptionSelected(option: string): boolean {
  if (props.askUserData.answer) {
    return props.askUserData.answer === option;
  }
  return selectedValue.value === option;
}

/** 判断选项是否禁用 */
function isOptionDisabled(): boolean {
  return !interactive.value;
}

/** "已回答"展示文本：confirm 型 answer 不在选项时、text 型均展示 */
function answeredText(): string {
  return props.askUserData.answer || '';
}
</script>

<template>
  <div class="ask-user-card">
    <!-- 问题区：类型图标 + 问题文本 + 追问轮次提示 -->
    <div class="ask-question">
      <span class="type-icon">{{ typeIcon }}</span>
      <span class="question-text">{{ props.askUserData.question }}</span>
      <span v-if="retryHint" class="retry-hint">{{ retryHint }}</span>
    </div>

    <!-- confirm 型交互区：垂直整行选项 + "其他"兜底 -->
    <div v-if="props.askUserData.type === 'confirm'" class="interact-area confirm-area">
      <button
        v-for="(option, idx) in props.askUserData.options || []"
        :key="option"
        class="option-row"
        :class="{
          selected: isOptionSelected(option),
          disabled: isOptionDisabled(),
        }"
        :disabled="isOptionDisabled()"
        @click="handleSelect(option)"
      >
        <span class="option-index">{{ idx + 1 }}</span>
        <span class="option-label">{{ option }}</span>
        <span v-if="isOptionSelected(option)" class="option-check">✓</span>
      </button>

      <!-- "其他"入口：展开输入框自由填写（AC-T04） -->
      <div v-if="!answered" class="other-entry">
        <button v-if="!otherExpanded" class="other-toggle" :disabled="!interactive" @click="expandOther">
          <span class="other-plus">＋</span> 其他（手动输入）
        </button>
        <div v-else class="other-input-row">
          <input
            v-model="otherInput"
            class="ask-input"
            :placeholder="props.askUserData.options?.length ? '输入其他回答...' : '请输入...'"
            @keyup.enter="submitOther"
          />
          <button class="submit-btn" :disabled="!interactive || otherInput.trim() === ''" @click="submitOther">
            提交
          </button>
        </div>
      </div>

      <!-- 回答锁定态：answer 不在选项中时展示已回答文本 -->
      <div
        v-if="answered && props.askUserData.options && !props.askUserData.options.includes(props.askUserData.answer!)"
        class="answered-line"
      >
        已回答：{{ answeredText() }}
      </div>
    </div>

    <!-- text 型交互区：内嵌输入框 + 提交按钮（AC-T02） -->
    <div v-else class="interact-area text-area">
      <div v-if="!answered" class="text-input-row">
        <input
          v-model="textInput"
          class="ask-input"
          placeholder="请输入..."
          @keyup.enter="submitText"
        />
        <button class="submit-btn" :disabled="!interactive || submitDisabled" @click="submitText">
          提交
        </button>
      </div>
      <!-- 回答锁定态：只读展示已回答文本 -->
      <div v-else class="answered-line">已回答：{{ answeredText() }}</div>
    </div>
  </div>
</template>

<style scoped>
.ask-user-card {
  margin-top: var(--spacing-sm);
  padding: var(--spacing-sm) var(--spacing-md);
  background: var(--bg-sidebar);
  border: 1px solid var(--accent-dim);
  border-radius: var(--radius-md);
}

/* 问题区 */
.ask-question {
  display: flex;
  align-items: baseline;
  gap: var(--spacing-xs);
  font-size: 14px;
  line-height: 1.6;
  color: var(--text-primary);
  margin-bottom: var(--spacing-sm);
}

.type-icon {
  flex-shrink: 0;
}

.question-text {
  flex: 1;
}

.retry-hint {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--accent);
  background: var(--accent-dim);
  padding: 1px 8px;
  border-radius: var(--radius-sm);
}

/* confirm 型：垂直整行选项 */
.option-row {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  width: 100%;
  padding: var(--spacing-xs) var(--spacing-sm);
  margin-bottom: var(--spacing-xs);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-input);
  color: var(--text-secondary);
  font-family: var(--font-display);
  font-size: 13px;
  text-align: left;
  cursor: pointer;
  transition: all 0.2s;
}

.option-row:hover:not(.disabled) {
  border-color: var(--accent-dim);
  color: var(--accent);
  transform: translateY(-1px);
  box-shadow: 0 2px 8px rgba(0, 212, 184, 0.12);
}

.option-index {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  flex-shrink: 0;
  border-radius: 50%;
  background: var(--accent-dim);
  color: var(--accent);
  font-size: 12px;
  font-weight: 600;
}

.option-label {
  flex: 1;
}

.option-check {
  color: var(--accent);
  font-weight: 700;
}

/* 选中项高亮（锁定态保持） */
.option-row.selected {
  border-color: var(--accent);
  background: var(--accent-dim);
  color: var(--accent);
  font-weight: 600;
}

.option-row.selected .option-index {
  background: var(--accent);
  color: #fff;
}

.option-row.disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

.option-row.disabled:not(.selected) {
  opacity: 0.35;
}

/* "其他"入口 */
.other-entry {
  margin-top: var(--spacing-xs);
}

.other-toggle {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: var(--spacing-xs) var(--spacing-sm);
  border: 1px dashed var(--border);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-secondary);
  font-family: var(--font-display);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s;
}

.other-toggle:hover:not(:disabled) {
  border-color: var(--accent-dim);
  color: var(--accent);
}

.other-plus {
  color: var(--accent);
}

/* 输入行（text 型与"其他"共用） */
.text-input-row,
.other-input-row {
  display: flex;
  gap: var(--spacing-xs);
  margin-top: var(--spacing-xs);
}

.ask-input {
  flex: 1;
  padding: var(--spacing-xs) var(--spacing-sm);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-input);
  color: var(--text-primary);
  font-family: var(--font-display);
  font-size: 13px;
  outline: none;
  transition: border-color 0.2s;
}

.ask-input:focus {
  border-color: var(--accent-dim);
}

.submit-btn {
  padding: var(--spacing-xs) var(--spacing-md);
  border: 1px solid var(--accent);
  border-radius: var(--radius-sm);
  background: var(--accent-dim);
  color: var(--accent);
  font-family: var(--font-display);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
  white-space: nowrap;
}

.submit-btn:hover:not(:disabled) {
  background: var(--accent);
  color: #fff;
}

.submit-btn:disabled {
  cursor: not-allowed;
  opacity: 0.4;
}

/* 回答锁定态 */
.answered-line {
  margin-top: var(--spacing-xs);
  padding: var(--spacing-xs) var(--spacing-sm);
  border: 1px solid var(--accent-dim);
  border-radius: var(--radius-sm);
  background: var(--accent-dim);
  color: var(--accent);
  font-size: 13px;
}
</style>
