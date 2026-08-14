<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { fetchAvailableTools } from '@/api/tools';
import type { ToolInfo } from '@/types';

/**
 * 工具选择器组件（工具按需加载）
 * 业务含义：持久展示当前会话的工具标签，并可通过下拉面板选择/取消可选工具。
 * - 默认工具（isDefault=true）：始终展示，蓝色标签 + 锁定图标，不可取消
 * - 可选工具：用户可勾选/取消，按类别显示不同颜色
 * - 标签栏持久展示在输入框上方，切换会话自动切换
 */
const props = withDefaults(defineProps<{
  /** 用户选中的可选工具 ID 列表（v-model 双向绑定） */
  modelValue: string[];
  /** 是否禁用（流式生成时置灰） */
  disabled?: boolean;
}>(), {
  disabled: false,
});

const emit = defineEmits<{
  'update:modelValue': [value: string[]];
}>();

/** 所有可用工具列表（从后端获取） */
const allTools = ref<ToolInfo[]>([]);
/** 默认工具列表 */
const defaultTools = computed(() => allTools.value.filter((t) => t.isDefault));
/** 可选工具列表（非默认） */
const optionalTools = computed(() => allTools.value.filter((t) => !t.isDefault));
/** 下拉展开状态 */
const dropdownOpen = ref(false);

/** 加载工具列表 */
async function loadTools() {
  try {
    const res = await fetchAvailableTools();
    allTools.value = res.tools;
  } catch {
    allTools.value = [];
  }
}

onMounted(loadTools);

/** 切换下拉展开/收起 */
function toggleDropdown() {
  if (props.disabled) return;
  dropdownOpen.value = !dropdownOpen.value;
}

/** 切换可选工具选中状态 */
function toggleTool(toolId: string) {
  if (props.modelValue.includes(toolId)) {
    emit('update:modelValue', props.modelValue.filter((id) => id !== toolId));
  } else {
    emit('update:modelValue', [...props.modelValue, toolId]);
  }
}

/** 移除已选工具（点击标签 ×） */
function removeTool(toolId: string) {
  if (props.disabled) return;
  emit('update:modelValue', props.modelValue.filter((id) => id !== toolId));
}

/** 按类别获取工具的颜色 */
function categoryColor(category: string): string {
  switch (category) {
    case 'mcp': return '#00d4b8';   // 绿色
    case 'rag': return '#ff8c42';   // 橙色
    default: return '#4a9eff';      // 内置：蓝色
  }
}

/** 根据 id 查找工具 */
function findTool(id: string): ToolInfo | undefined {
  return allTools.value.find((t) => t.id === id);
}

/**
 * 格式化工具显示名
 * 业务含义：MCP 工具 id 为 mcp:{serverName}（同一 server 下多个工具 id 相同，无法区分），
 * 因此 MCP 类别需展示完整方法名 tool.name（mcp_{serverName}_{toolName}）以区分具体工具；
 * 其他类别直接展示 id 去掉 category: 前缀的名称。
 */
function formatToolName(tool?: ToolInfo): string {
  if (!tool) return '';
  if (tool.category === 'mcp') {
    return tool.name;
  }
  const idx = tool.id.indexOf(':');
  return idx >= 0 ? tool.id.slice(idx + 1) : tool.id;
}

/** 根据 id 获取格式化后的展示名（标签栏用，modelValue 仅存 id） */
function displayToolName(id: string): string {
  return formatToolName(findTool(id));
}
</script>

<template>
  <div class="tool-selector" :class="{ disabled }">
    <!-- 工具标签栏（独立一行，持久展示在输入框上方） -->
    <div class="tool-tags">
      <span class="tool-tags-label">工具</span>
      <!-- 默认工具标签：不可删除 -->
      <span
        v-for="tool in defaultTools"
        :key="tool.id"
        class="tool-tag tool-tag-default"
        :style="{ '--tc': categoryColor(tool.category) }"
        :title="tool.description"
      >
        <i class="tool-dot"></i>{{ formatToolName(tool) }}
      </span>
      <!-- 用户选中的可选工具标签：可删除 -->
      <span
        v-for="toolId in modelValue"
        :key="toolId"
        class="tool-tag tool-tag-optional"
        :style="{ '--tc': categoryColor(findTool(toolId)?.category || 'builtin') }"
        :title="findTool(toolId)?.description"
      >
        <i class="tool-dot"></i>{{ displayToolName(toolId) }}
        <span class="tool-tag-remove" @click.stop="removeTool(toolId)" title="移除工具">×</span>
      </span>

      <!-- 添加工具触发按钮 -->
      <button class="tool-add" @click="toggleDropdown">
        <span class="tool-add-icon">＋</span>
        <span>{{ modelValue.length > 0 ? `工具(${modelValue.length})` : '添加' }}</span>
      </button>
    </div>

    <!-- 下拉面板：按类别分组展示可选工具 -->
    <div v-if="dropdownOpen" class="tool-dropdown" @click.stop>
      <div class="tool-dropdown-header">
        <span>选择要加载的工具</span>
        <button class="tool-dropdown-close" @click="dropdownOpen = false">×</button>
      </div>
      <div v-if="optionalTools.length === 0" class="tool-empty">
        暂无可选工具
      </div>
      <div v-else class="tool-groups">
        <!-- 内置工具组 -->
        <div v-if="optionalTools.filter(t => t.category === 'builtin').length > 0" class="tool-group">
          <div class="tool-group-title">内置工具</div>
          <div
            v-for="tool in optionalTools.filter(t => t.category === 'builtin')"
            :key="tool.id"
            class="tool-option"
            :class="{ selected: modelValue.includes(tool.id) }"
            @click="toggleTool(tool.id)"
          >
            <span class="tool-option-name">{{ formatToolName(tool) }}</span>
            <span class="tool-option-check">{{ modelValue.includes(tool.id) ? '✓' : '' }}</span>
          </div>
        </div>
        <!-- MCP 工具组 -->
        <div v-if="optionalTools.filter(t => t.category === 'mcp').length > 0" class="tool-group">
          <div class="tool-group-title">MCP 工具</div>
          <div
            v-for="tool in optionalTools.filter(t => t.category === 'mcp')"
            :key="tool.id"
            class="tool-option"
            :class="{ selected: modelValue.includes(tool.id) }"
            @click="toggleTool(tool.id)"
          >
            <span class="tool-option-name">{{ formatToolName(tool) }}</span>
            <span class="tool-option-check">{{ modelValue.includes(tool.id) ? '✓' : '' }}</span>
          </div>
        </div>
        <!-- 知识库工具组 -->
        <div v-if="optionalTools.filter(t => t.category === 'rag').length > 0" class="tool-group">
          <div class="tool-group-title">知识库工具</div>
          <div
            v-for="tool in optionalTools.filter(t => t.category === 'rag')"
            :key="tool.id"
            class="tool-option"
            :class="{ selected: modelValue.includes(tool.id) }"
            @click="toggleTool(tool.id)"
          >
            <span class="tool-option-name">{{ formatToolName(tool) }}</span>
            <span class="tool-option-check">{{ modelValue.includes(tool.id) ? '✓' : '' }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tool-selector {
  position: relative;
  display: block;
}

/* 禁用态置灰 */
.tool-selector.disabled {
  opacity: 0.5;
  pointer-events: none;
}

/* 工具标签栏：独立一行，横向滚动，紧凑美观 */
.tool-tags {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 2px;
  overflow-x: auto;
  flex-wrap: nowrap;
}

.tool-tags-label {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--text-muted);
  font-family: var(--font-display);
}

/* 胶囊标签 */
.tool-tag {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  flex-shrink: 0;
  padding: 2px 9px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--tc) 12%, transparent);
  border: 1px solid color-mix(in srgb, var(--tc) 40%, transparent);
  color: var(--tc);
  font-family: var(--font-display);
  font-size: 11px;
  line-height: 1.5;
  white-space: nowrap;
}

.tool-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--tc);
  flex-shrink: 0;
}

.tool-tag-default {
  opacity: 0.85;
}

.tool-tag-optional {
  cursor: default;
}

.tool-tag-remove {
  cursor: pointer;
  font-size: 13px;
  line-height: 1;
  opacity: 0.7;
  margin-left: 2px;
  transition: opacity 0.2s;
}

.tool-tag-remove:hover {
  opacity: 1;
}

/* 添加按钮 */
.tool-add {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  flex-shrink: 0;
  padding: 2px 9px;
  border-radius: 999px;
  border: 1px dashed var(--border);
  background: transparent;
  color: var(--text-muted);
  font-family: var(--font-display);
  font-size: 11px;
  cursor: pointer;
  transition: all 0.2s;
}

.tool-add:hover {
  border-color: var(--accent-dim);
  color: var(--accent);
}

.tool-add-icon {
  font-size: 12px;
}

/* 下拉面板：工具栏位于输入框上方（接近页面底部），需向上展开避免超出页面 */
.tool-dropdown {
  position: absolute;
  bottom: calc(100% + 6px);
  left: 0;
  width: 300px;
  max-height: 320px;
  overflow-y: auto;
  background: var(--bg-sidebar);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-md);
  z-index: 20;
  padding: var(--spacing-xs);
}

.tool-dropdown-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 8px;
  font-size: 12px;
  color: var(--text-secondary);
  border-bottom: 1px solid var(--border);
  margin-bottom: 4px;
}

.tool-dropdown-close {
  border: none;
  background: transparent;
  color: var(--text-muted);
  font-size: 16px;
  cursor: pointer;
  line-height: 1;
}

.tool-dropdown-close:hover {
  color: var(--text-primary);
}

.tool-empty {
  padding: var(--spacing-md);
  font-size: 12px;
  color: var(--text-muted);
  text-align: center;
}

.tool-group {
  margin-bottom: 2px;
}

.tool-group-title {
  padding: 4px 8px;
  font-size: 11px;
  font-weight: 600;
  color: var(--text-muted);
}

.tool-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 10px;
  font-family: var(--font-body);
  font-size: 12px;
  color: var(--text-secondary);
  cursor: pointer;
  border-radius: var(--radius-sm);
  transition: background 0.2s;
}

.tool-option:hover {
  background: var(--bg-hover);
}

.tool-option.selected {
  color: var(--accent);
  background: var(--accent-dim);
}

.tool-option-name {
  font-weight: 500;
}

.tool-option-check {
  color: var(--accent);
  font-weight: 700;
}
</style>
