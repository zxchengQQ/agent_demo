<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { fetchAvailableTools } from '@/api/tools';
import type { ToolInfo } from '@/types';

/**
 * 工具管理页面（工具按需加载）
 * 业务含义：展示系统所有可用工具清单，按类别分组，
 * 标注每个工具的标识、描述、默认/可选状态。
 */
const tools = ref<ToolInfo[]>([]);
const loading = ref(false);
const error = ref('');

/** 按类别分组 */
const grouped = computed(() => {
  const groups: { category: string; label: string; items: ToolInfo[] }[] = [];
  const order = ['builtin', 'mcp', 'rag'];
  for (const cat of order) {
    const items = tools.value.filter((t) => t.category === cat);
    if (items.length > 0) {
      const label = cat === 'builtin' ? '内置工具' : cat === 'mcp' ? 'MCP 工具' : '知识库工具';
      groups.push({ category: cat, label, items });
    }
  }
  return groups;
});

async function loadTools() {
  loading.value = true;
  error.value = '';
  try {
    const res = await fetchAvailableTools();
    tools.value = res.tools;
  } catch (e: any) {
    error.value = e?.message || '加载失败';
  } finally {
    loading.value = false;
  }
}

onMounted(loadTools);
</script>

<template>
  <div class="tool-management">
    <div class="tool-management-header">
      <h3 class="tool-management-title">工具管理</h3>
      <span class="tool-total">共 {{ tools.length }} 个工具</span>
    </div>

    <div v-if="loading" class="tool-loading">加载中...</div>
    <div v-else-if="error" class="tool-error">
      {{ error }}
      <button class="btn-retry" @click="loadTools">重试</button>
    </div>

    <div v-else-if="tools.length === 0" class="tool-empty">暂无可用工具</div>

    <div v-else class="tool-groups">
      <!-- 按类别分组展示 -->
      <div v-for="group in grouped" :key="group.category" class="tool-group">
        <div class="tool-group-title">{{ group.label }}</div>
        <div class="tool-group-list">
          <div v-for="tool in group.items" :key="tool.id" class="tool-item">
            <div class="tool-item-main">
              <span class="tool-item-id">{{ tool.id }}</span>
              <span
                class="tool-item-badge"
                :class="tool.isDefault ? 'badge-default' : 'badge-optional'"
              >
                {{ tool.isDefault ? '默认加载' : '需指定' }}
              </span>
            </div>
            <div class="tool-item-desc">{{ tool.description }}</div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tool-management {
  padding: var(--spacing-lg);
  overflow-y: auto;
  height: 100%;
}

.tool-management-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--spacing-md);
}

.tool-management-title {
  font-family: var(--font-display);
  font-size: 16px;
  color: var(--text-primary);
}

.tool-total {
  font-size: 12px;
  color: var(--text-muted);
}

.tool-loading,
.tool-empty {
  padding: var(--spacing-lg);
  text-align: center;
  color: var(--text-muted);
  font-size: 13px;
}

.tool-error {
  padding: var(--spacing-md);
  text-align: center;
  color: var(--danger);
  font-size: 13px;
}

.btn-retry {
  margin-left: var(--spacing-sm);
  padding: 2px var(--spacing-sm);
  border: 1px solid var(--danger);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--danger);
  cursor: pointer;
}

.tool-groups {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-lg);
}

.tool-group-title {
  font-family: var(--font-display);
  font-size: 14px;
  font-weight: 600;
  color: var(--accent);
  margin-bottom: var(--spacing-sm);
}

.tool-group-list {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
}

.tool-item {
  padding: var(--spacing-sm) var(--spacing-md);
  background: var(--bg-sidebar);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
}

.tool-item-main {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-sm);
}

.tool-item-id {
  font-family: var(--font-display);
  font-size: 13px;
  color: var(--text-primary);
}

.tool-item-badge {
  padding: 1px var(--spacing-sm);
  font-size: 11px;
  border-radius: var(--radius-sm);
  flex-shrink: 0;
}

.badge-default {
  background: var(--accent-dim);
  color: var(--accent);
}

.badge-optional {
  background: rgba(255, 165, 0, 0.15);
  color: rgba(255, 165, 0, 0.85);
}

.tool-item-desc {
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-muted);
}
</style>
