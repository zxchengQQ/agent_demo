<script setup lang="ts">
import { ref, computed } from 'vue';
import type { LlmModel } from '@/types';

/**
 * 模型选择器组件（Task-18）
 * 业务含义：下拉单选组件，用户可为当前会话选择 chat 类型模型。
 * 模型按 vendorName 分组展示，每个模型显示 displayName，
 * supportsVision=true 的模型额外标记"支持视图"。
 * 空模型列表时显示"暂无可用模型"引导。
 */
const props = withDefaults(defineProps<{
  /** 当前选中的 modelId（v-model 双向绑定） */
  modelValue: string;
  /** chat 模型列表 */
  models: LlmModel[];
  /** 是否禁用（流式生成时置灰） */
  disabled?: boolean;
}>(), {
  disabled: false,
});

const emit = defineEmits<{
  'update:modelValue': [value: string];
}>();

/** 下拉展开状态 */
const dropdownOpen = ref(false);

/** 切换下拉展开/收起（disabled 时不响应） */
function toggleDropdown() {
  if (props.disabled) return;
  dropdownOpen.value = !dropdownOpen.value;
}

/** 选中模型（触发 update:modelValue 并收起下拉） */
function selectModel(model: LlmModel) {
  emit('update:modelValue', model.id);
  dropdownOpen.value = false;
}

/** 当前选中模型对象（用于触发器展示 displayName） */
const selectedModel = computed<LlmModel | undefined>(() =>
  props.models.find((m) => m.id === props.modelValue),
);

/** 按厂商名称分组后的模型列表 */
const groupedModels = computed(() => {
  const groups: { vendorName: string; models: LlmModel[] }[] = [];
  for (const model of props.models) {
    let group = groups.find((g) => g.vendorName === model.vendorName);
    if (!group) {
      group = { vendorName: model.vendorName, models: [] };
      groups.push(group);
    }
    group.models.push(model);
  }
  return groups;
});
</script>

<template>
  <div class="model-selector" :class="{ disabled }">
    <!-- 触发器：显示当前选中模型 displayName -->
    <div class="model-trigger" @click="toggleDropdown">
      <span v-if="selectedModel" class="model-trigger-name">
        {{ selectedModel.displayName }}
        <span v-if="selectedModel.supportsVision" class="model-vision-tag">支持视图</span>
      </span>
      <span v-else class="model-trigger-placeholder">选择模型</span>
      <span class="model-caret">▾</span>
    </div>

    <!-- 下拉列表（向上展开） -->
    <div v-if="dropdownOpen" class="model-dropdown">
      <!-- 空模型列表提示 -->
      <div v-if="models.length === 0" class="model-empty">
        暂无可用模型
      </div>
      <!-- 按厂商分组展示 -->
      <template v-for="group in groupedModels" :key="group.vendorName">
        <div class="model-group-header">{{ group.vendorName }}</div>
        <div
          v-for="model in group.models"
          :key="model.id"
          class="model-option"
          :class="{ selected: model.id === modelValue }"
          @click="selectModel(model)"
        >
          <span class="model-option-name">{{ model.displayName }}</span>
          <span v-if="model.supportsVision" class="model-option-vision">支持视图</span>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.model-selector {
  position: relative;
  display: inline-flex;
  align-items: center;
}

/* 禁用态置灰 */
.model-selector.disabled {
  opacity: 0.5;
  pointer-events: none;
}

.model-trigger {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-xs);
  cursor: pointer;
  padding: 2px var(--spacing-sm);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-muted);
  font-family: var(--font-display);
  font-size: 12px;
  transition: all 0.2s;
}

.model-trigger:hover {
  border-color: var(--accent-dim);
  color: var(--accent);
}

.model-trigger-name {
  color: var(--text-secondary);
}

.model-trigger-placeholder {
  color: var(--text-muted);
}

.model-caret {
  font-size: 10px;
  color: var(--text-muted);
}

/* 触发器中"支持视图"小标记 */
.model-vision-tag {
  margin-left: 4px;
  padding: 0 4px;
  font-size: 10px;
  color: var(--accent);
  background: var(--accent-dim);
  border-radius: 3px;
}

.model-dropdown {
  position: absolute;
  bottom: calc(100% + var(--spacing-xs));
  left: 0;
  min-width: 220px;
  max-height: 280px;
  overflow-y: auto;
  background: var(--bg-sidebar);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow-sm);
  z-index: 10;
}

/* 厂商分组标题 */
.model-group-header {
  padding: var(--spacing-xs) var(--spacing-sm);
  font-family: var(--font-display);
  font-size: 11px;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  border-bottom: 1px solid var(--border);
}

.model-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-sm);
  padding: var(--spacing-xs) var(--spacing-sm);
  font-family: var(--font-body);
  font-size: 13px;
  color: var(--text-secondary);
  cursor: pointer;
  transition: background 0.2s;
}

.model-option:hover {
  background: var(--bg-hover);
}

/* 已选中项高亮 */
.model-option.selected {
  color: var(--accent);
  background: var(--accent-dim);
}

.model-option-vision {
  flex-shrink: 0;
  padding: 0 4px;
  font-size: 10px;
  color: var(--accent);
  background: var(--accent-dim);
  border-radius: 3px;
}

.model-empty {
  padding: var(--spacing-sm);
  font-size: 12px;
  color: var(--text-muted);
  text-align: center;
}
</style>
