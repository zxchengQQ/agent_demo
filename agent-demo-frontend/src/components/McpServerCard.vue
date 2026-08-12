<script setup lang="ts">
import { computed } from 'vue';
import type { McpServerInfo, McpToolInfo } from '@/types';

/**
 * MCP Server 卡片组件（Task-05）
 * 业务含义：展示单个 MCP Server 的摘要信息卡片。
 * 展示名称、传输方式、状态徽章、连接地址、工具数量。
 * 提供"重连"/"删除"操作，删除需二次确认（AC-013）。
 * 重连按钮仅在 DISCONNECTED/ERROR 状态可用（AC-015）。
 * 地址显示：stdio -> command+args，sse/http -> url（AC-036）。
 */
const props = defineProps<{
  /** MCP Server 信息 */
  server: McpServerInfo;
  /** 已展开的工具列表（null 表示未加载/未展开） */
  tools: McpToolInfo[] | null;
  /** 是否正在加载工具列表 */
  loadingTools?: boolean;
}>();

const emit = defineEmits<{
  /** 点击删除（已确认） */
  delete: [name: string];
  /** 点击重连 */
  reconnect: [name: string];
  /** 点击切换工具列表展开/折叠 */
  toggleTools: [name: string];
}>();

/** 状态徽章映射 */
const STATUS_META = {
  CONNECTED: { label: '已连接', cls: 'st-connected' },
  DISCONNECTED: { label: '已断线', cls: 'st-disconnected' },
  ERROR: { label: '错误', cls: 'st-error' },
  DISABLED: { label: '已禁用', cls: 'st-disabled' },
} as const;

/** 传输方式显示文本 */
const TRANSPORT_LABEL: Record<string, string> = {
  STDIO: 'stdio',
  SSE: 'sse',
  HTTP: 'http',
};

/** 状态徽章样式类 */
const statusCls = computed(() => STATUS_META[props.server.status].cls);

/** 状态显示文本 */
const statusLabel = computed(() => STATUS_META[props.server.status].label);

/**
 * 连接地址显示（AC-036）
 * 业务含义：stdio 类型显示 command + args 拼接（如 "npx mcp-fetch-server"），sse/http 类型显示 URL。
 */
const address = computed(() => {
  if (props.server.transport === 'STDIO') {
    const cmd = props.server.command || '';
    const args = (props.server.args ?? []).join(' ');
    return args ? `${cmd} ${args}` : cmd;
  }
  return props.server.url || '';
});

/** 是否可重连（仅 DISCONNECTED/ERROR 状态，AC-015） */
const canReconnect = computed(
  () => props.server.status === 'DISCONNECTED' || props.server.status === 'ERROR',
);

/** 工具数量显示文本 */
const toolsLabel = computed(() => `${props.server.toolCount} 个工具`);

/** 是否已展开工具列表 */
const isExpanded = computed(() => props.tools !== null);

function handleDelete() {
  // 业务含义：删除将断开连接并注销所有工具，需明示确认（AC-013）
  if (window.confirm(`确认删除 ${props.server.name}？将断开连接并注销所有工具。`)) {
    emit('delete', props.server.name);
  }
}

function handleReconnect() {
  emit('reconnect', props.server.name);
}

function handleToggleTools() {
  emit('toggleTools', props.server.name);
}
</script>

<template>
  <div class="mcp-card">
    <!-- 卡片头部：名称 + 状态徽章 -->
    <div class="mcp-header">
      <span class="mcp-name">{{ server.name }}</span>
      <span class="mcp-status" :class="statusCls">{{ statusLabel }}</span>
    </div>

    <!-- 传输方式 -->
    <div class="mcp-transport">{{ TRANSPORT_LABEL[server.transport] || server.transport }}</div>

    <!-- 连接地址（stdio -> command，sse/http -> url） -->
    <div class="mcp-address" :title="address">{{ address || '—' }}</div>

    <!-- 工具数量（点击展开/折叠） -->
    <button class="mcp-tools" @click="handleToggleTools">
      {{ toolsLabel }}
      <span class="mcp-tools-caret">{{ isExpanded ? '▾' : '▸' }}</span>
    </button>

    <!-- 工具详情列表（展开时） -->
    <div v-if="isExpanded" class="mcp-tools-panel">
      <div v-if="loadingTools" class="mcp-tools-loading">加载中...</div>
      <div v-else-if="tools && tools.length > 0" class="mcp-tools-list">
        <div v-for="tool in tools" :key="tool.originalName" class="mcp-tool-item">
          <div class="mcp-tool-name">{{ tool.originalName }}</div>
          <div class="mcp-tool-registered">{{ tool.registeredName }}</div>
          <div v-if="tool.description" class="mcp-tool-desc">{{ tool.description }}</div>
          <pre v-if="tool.parametersSchema" class="mcp-tool-schema">{{ tool.parametersSchema }}</pre>
        </div>
      </div>
      <div v-else class="mcp-tools-empty">该 Server 暂无工具</div>
    </div>

    <!-- 最近错误信息（ERROR/DISCONNECTED 时显示） -->
    <div v-if="server.lastError" class="mcp-error" :title="server.lastError">{{ server.lastError }}</div>

    <!-- 操作按钮 -->
    <div class="mcp-actions">
      <button
        class="btn-action btn-reconnect"
        :disabled="!canReconnect"
        @click="handleReconnect"
      >
        {{ canReconnect ? '重连' : '已连接' }}
      </button>
      <button class="btn-action btn-delete" @click="handleDelete">删除</button>
    </div>
  </div>
</template>

<style scoped>
.mcp-card {
  background: var(--bg-sidebar);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: var(--spacing-md);
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
  transition: border-color 0.2s;
}

.mcp-card:hover {
  border-color: var(--accent-dim);
}

.mcp-header {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
}

.mcp-name {
  font-family: var(--font-display);
  font-size: 14px;
  font-weight: 700;
  color: var(--text-primary);
}

.mcp-status {
  padding: 0 6px;
  font-size: 11px;
  border-radius: 3px;
}

.st-connected {
  color: var(--success);
  background: rgba(0, 255, 136, 0.12);
}

.st-disconnected {
  color: var(--text-muted);
  background: var(--bg-input);
}

.st-error {
  color: var(--danger);
  background: rgba(255, 77, 77, 0.15);
}

.st-disabled {
  color: var(--warning);
  background: rgba(255, 170, 0, 0.15);
}

.mcp-transport {
  font-size: 11px;
  color: var(--accent);
  text-transform: uppercase;
}

.mcp-address {
  font-family: var(--font-body);
  font-size: 12px;
  color: var(--text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.mcp-tools {
  align-self: flex-start;
  border: none;
  background: none;
  padding: 0;
  font-family: var(--font-display);
  font-size: 12px;
  color: var(--accent);
  cursor: pointer;
}

.mcp-tools:hover {
  text-decoration: underline;
}

.mcp-tools-caret {
  font-size: 10px;
}

.mcp-tools-panel {
  margin-top: var(--spacing-xs);
  border-top: 1px solid var(--border);
  padding-top: var(--spacing-xs);
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
  max-height: 200px;
  overflow-y: auto;
}

.mcp-tools-loading,
.mcp-tools-empty {
  font-size: 12px;
  color: var(--text-muted);
}

.mcp-tool-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: var(--spacing-xs);
  background: var(--bg-input);
  border-radius: var(--radius-sm);
}

.mcp-tool-name {
  font-family: var(--font-display);
  font-size: 12px;
  font-weight: 700;
  color: var(--text-primary);
}

.mcp-tool-registered {
  font-size: 11px;
  color: var(--accent);
}

.mcp-tool-desc {
  font-size: 11px;
  color: var(--text-secondary);
}

.mcp-tool-schema {
  font-size: 10px;
  color: var(--text-muted);
  background: var(--bg-primary);
  padding: 4px;
  border-radius: 3px;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
}

.mcp-error {
  font-size: 11px;
  color: var(--danger);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.mcp-actions {
  display: flex;
  gap: var(--spacing-sm);
  margin-top: var(--spacing-sm);
}

.btn-action {
  padding: var(--spacing-xs) var(--spacing-md);
  border: none;
  border-radius: var(--radius-sm);
  font-family: var(--font-display);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-reconnect {
  background: var(--accent);
  color: var(--bg-primary);
}

.btn-reconnect:hover:not(:disabled) {
  box-shadow: var(--shadow-glow);
}

.btn-reconnect:disabled {
  background: var(--bg-input);
  color: var(--text-muted);
  cursor: not-allowed;
}

.btn-delete {
  background: transparent;
  border: 1px solid var(--border);
  color: var(--text-secondary);
}

.btn-delete:hover {
  border-color: var(--danger);
  color: var(--danger);
}
</style>
