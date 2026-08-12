<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useMcpStore } from '@/stores/mcp';
import type { McpToolInfo } from '@/types';
import McpServerCard from '@/components/McpServerCard.vue';
import McpJsonConfigEditor from '@/components/McpJsonConfigEditor.vue';

/**
 * MCP 服务管理页面（Task-07）
 * 业务含义：MCP 服务标签页的主页面，展示 Server 卡片列表，提供添加/删除/重连/查看工具操作。
 * 页面加载时拉取 Server 列表（AC-004）。
 * 空列表显示引导（AC-006），模块禁用显示提示并禁用操作（AC-026）。
 */
const mcpStore = useMcpStore();

/** 添加编辑器弹窗显示状态 */
const showEditor = ref(false);
/** 当前展开工具列表的 Server 名（null 表示未展开） */
const expandedServer = ref<string | null>(null);
/** 全局错误信息（网络异常等） */
const errorMessage = ref('');
/** MCP 模块是否禁用 */
const moduleDisabled = ref(false);
/** 当前展开 Server 的工具列表 */
const expandedTools = ref<McpToolInfo[] | null>(null);
/** 正在加载工具列表 */
const loadingTools = ref(false);

/** Server 列表 */
const servers = computed(() => mcpStore.servers);

/** 初始化：加载 Server 列表（AC-004） */
onMounted(async () => {
  await loadServers();
});

/**
 * 加载 Server 列表
 * 业务含义：调用 store 拉取列表；模块禁用（AC-026）或网络异常（AC-027）时设置对应状态。
 */
async function loadServers() {
  try {
    await mcpStore.loadServers();
    moduleDisabled.value = false;
    errorMessage.value = '';
  } catch (e) {
    const msg = (e as Error).message;
    // 后端返回 MCP_MODULE_DISABLED（5405）时禁用操作（AC-026）
    if (msg.includes('禁用') || msg.includes('MCP_MODULE_DISABLED')) {
      moduleDisabled.value = true;
      errorMessage.value = 'MCP 模块已禁用，请在配置中启用';
    } else if (msg.includes('网络') || msg.includes('fetch') || msg.includes('Failed to fetch')) {
      errorMessage.value = '网络异常，请检查后端服务是否运行';
    } else {
      errorMessage.value = msg;
    }
  }
}

/** 打开添加编辑器 */
function handleAdd() {
  showEditor.value = true;
}

/** 编辑器保存成功：关闭 + 刷新列表 */
async function handleEditorSaved() {
  showEditor.value = false;
  await loadServers();
}

/**
 * 删除 Server（AC-012, AC-025）
 * 业务含义：卡片已做二次确认，此处调用 store 删除并刷新。
 */
async function handleDelete(name: string) {
  try {
    await mcpStore.deleteServer(name);
    // 若删除的是已展开的 Server，关闭展开
    if (expandedServer.value === name) {
      collapseTools();
    }
  } catch (e) {
    handleOperationError(e);
  }
}

/**
 * 重连 Server（AC-014, AC-016）
 * 业务含义：调用 store 重连并刷新列表。
 */
async function handleReconnect(name: string) {
  try {
    await mcpStore.reconnectServer(name);
  } catch (e) {
    handleOperationError(e);
  }
}

/**
 * 切换工具列表展开/折叠（AC-018）
 * 业务含义：首次点击加载工具列表并缓存；再次点击折叠。
 */
async function toggleTools(name: string) {
  if (expandedServer.value === name) {
    collapseTools();
    return;
  }
  // 展开新 Server：优先使用缓存，未缓存则加载
  expandedServer.value = name;
  const cached = mcpStore.toolsCache[name];
  if (cached) {
    expandedTools.value = cached;
    return;
  }
  loadingTools.value = true;
  expandedTools.value = null;
  try {
    expandedTools.value = await mcpStore.loadServerTools(name);
  } catch {
    expandedTools.value = null;
  } finally {
    loadingTools.value = false;
  }
}

/** 折叠工具列表 */
function collapseTools() {
  expandedServer.value = null;
  expandedTools.value = null;
  loadingTools.value = false;
}

/** 操作错误处理（网络异常、模块禁用等） */
function handleOperationError(e: unknown) {
  const msg = (e as Error).message;
  if (msg.includes('禁用') || msg.includes('MCP_MODULE_DISABLED')) {
    moduleDisabled.value = true;
    errorMessage.value = 'MCP 模块已禁用，请在配置中启用';
  } else if (msg.includes('网络') || msg.includes('Failed to fetch')) {
    errorMessage.value = '网络异常，请检查后端服务是否运行';
  } else {
    errorMessage.value = msg;
  }
}
</script>

<template>
  <div class="mcp-page">
    <!-- 页面头部：标题 + 添加服务按钮 -->
    <div class="page-header">
      <h2 class="page-title">MCP 服务</h2>
      <button class="btn-add" :disabled="moduleDisabled" @click="handleAdd">添加服务</button>
    </div>

    <!-- 全局错误信息 -->
    <div v-if="errorMessage" class="page-error">{{ errorMessage }}</div>

    <!-- Server 卡片网格 -->
    <div v-if="servers.length > 0" class="mcp-grid">
      <McpServerCard
        v-for="server in servers"
        :key="server.name"
        :server="server"
        :tools="expandedServer === server.name ? expandedTools : null"
        :loading-tools="expandedServer === server.name && loadingTools"
        @delete="handleDelete"
        @reconnect="handleReconnect"
        @toggle-tools="toggleTools"
      />
    </div>

    <!-- 空状态引导（AC-006） -->
    <div v-else-if="!moduleDisabled" class="empty-state">
      <p class="empty-text">暂无 MCP 服务</p>
      <button class="btn-add-empty" @click="handleAdd">添加服务</button>
    </div>

    <!-- 添加编辑器弹窗 -->
    <McpJsonConfigEditor
      :visible="showEditor"
      @close="showEditor = false"
      @saved="handleEditorSaved"
    />
  </div>
</template>

<style scoped>
.mcp-page {
  flex: 1;
  overflow-y: auto;
  padding: var(--spacing-lg);
  background: var(--bg-primary);
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--spacing-lg);
}

.page-title {
  font-family: var(--font-display);
  font-size: 18px;
  font-weight: 700;
  color: var(--text-primary);
}

.btn-add {
  padding: var(--spacing-sm) var(--spacing-lg);
  border: none;
  border-radius: var(--radius-sm);
  background: var(--accent);
  color: var(--bg-primary);
  font-family: var(--font-display);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-add:hover:not(:disabled) {
  box-shadow: var(--shadow-glow);
}

.btn-add:disabled {
  background: var(--bg-input);
  color: var(--text-muted);
  cursor: not-allowed;
}

.page-error {
  color: var(--danger);
  font-size: 12px;
  margin-bottom: var(--spacing-md);
  padding: var(--spacing-sm);
  background: rgba(255, 77, 77, 0.08);
  border-radius: var(--radius-sm);
}

.mcp-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: var(--spacing-md);
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--spacing-md);
  padding: var(--spacing-xl);
  color: var(--text-muted);
}

.empty-text {
  font-size: 14px;
}

.btn-add-empty {
  padding: var(--spacing-sm) var(--spacing-lg);
  border: 1px solid var(--accent-dim);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--accent);
  font-family: var(--font-display);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-add-empty:hover {
  background: var(--accent-dim);
}
</style>
