<script setup lang="ts">
import { ref } from 'vue';
import type { SkillInfo } from '@/types';

/**
 * 技能选择器组件（agent-skill Task-20，AC-N03 手动指定入口）
 * 业务含义：下拉多选组件，用户可选择本次会话手动指定的技能。
 * 空数组表示"自动"模式（Agent 自主匹配激活），选中后仅使用指定技能（自动匹配挂起）。
 * 与知识库选择器（KnowledgeBaseSelector）交互同构。
 */
const props = withDefaults(defineProps<{
  /** 选中的技能 id 列表（v-model 双向绑定，空=自动模式） */
  modelValue: string[];
  /** 可选技能列表（仅启用技能） */
  skills: SkillInfo[];
  /** 是否禁用（流式生成时置灰） */
  disabled?: boolean;
}>(), {
  disabled: false,
});

const emit = defineEmits<{
  'update:modelValue': [value: string[]];
}>();

/** 下拉展开状态 */
const dropdownOpen = ref(false);

/** 切换下拉展开/收起（disabled 时不响应） */
function toggleDropdown() {
  if (props.disabled) return;
  dropdownOpen.value = !dropdownOpen.value;
}

/** 切换技能选中状态：已选中则移除，未选中则添加 */
function toggleSkill(id: string) {
  if (props.modelValue.includes(id)) {
    emit('update:modelValue', props.modelValue.filter((i) => i !== id));
  } else {
    emit('update:modelValue', [...props.modelValue, id]);
  }
}
</script>

<template>
  <div class="skill-selector" :class="{ disabled }">
    <!-- 触发器 + 标签展示区 -->
    <div class="skill-trigger" @click="toggleDropdown">
      <span v-if="modelValue.length === 0" class="skill-tag skill-tag-auto">自动</span>
      <span
        v-for="id in modelValue"
        :key="id"
        class="skill-tag"
      >{{ skills.find((s) => s.id === id)?.name || id }}</span>
    </div>

    <!-- 下拉列表 -->
    <div v-if="dropdownOpen" class="skill-dropdown">
      <!-- 空技能提示 -->
      <div v-if="skills.length === 0" class="skill-empty">
        暂无可用技能，请先在设置页创建
      </div>
      <!-- 技能选项列表 -->
      <div
        v-for="skill in skills"
        :key="skill.id"
        class="skill-option"
        :class="{ selected: modelValue.includes(skill.id) }"
        @click="toggleSkill(skill.id)"
      >
        {{ skill.name }}
        <span class="skill-desc">{{ skill.description }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.skill-selector {
  position: relative;
  display: inline-flex;
  align-items: center;
}

/* 禁用态置灰 */
.skill-selector.disabled {
  opacity: 0.5;
  pointer-events: none;
}

.skill-trigger {
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

.skill-trigger:hover {
  border-color: var(--accent-dim);
  color: var(--accent);
}

.skill-tag {
  display: inline-block;
}

/* "自动"标签使用 muted 色，区别于已选中的技能标签 */
.skill-tag-auto {
  color: var(--text-muted);
}

.skill-dropdown {
  position: absolute;
  bottom: calc(100% + var(--spacing-xs));
  left: 0;
  min-width: 200px;
  max-height: 240px;
  overflow-y: auto;
  background: var(--bg-sidebar);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow-sm);
  z-index: 10;
}

.skill-option {
  display: flex;
  flex-direction: column;
  padding: var(--spacing-xs) var(--spacing-sm);
  font-family: var(--font-body);
  font-size: 13px;
  color: var(--text-secondary);
  cursor: pointer;
  transition: background 0.2s;
}

.skill-option:hover {
  background: var(--bg-hover);
}

/* 已选中项高亮 */
.skill-option.selected {
  color: var(--accent);
  background: var(--accent-dim);
}

.skill-desc {
  font-size: 11px;
  color: var(--text-muted);
  margin-top: 2px;
}

.skill-empty {
  padding: var(--spacing-sm);
  font-size: 12px;
  color: var(--text-muted);
  text-align: center;
}
</style>
