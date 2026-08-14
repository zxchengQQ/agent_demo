<script setup lang="ts">
import { ref } from 'vue';
import LlmConfigPage from '@/components/LlmConfigPage.vue';
import McpServicePage from '@/components/McpServicePage.vue';
import ToolManagementPage from '@/components/ToolManagementPage.vue';

/**
 * 设置页面（Task-08）
 * 业务含义：统一设置入口，以标签页形式整合 LLM 配置、MCP 服务管理与工具管理。
 * LLM 配置标签页迁移自原 LlmConfigPage（功能行为不变，AC-035）。
 * MCP 服务标签页为新增的 MCP Server 管理（AC-002）。
 * 工具管理标签页为新增的工具清单展示（工具按需加载，AC-006）。
 * 切换标签页时保持各自页面状态（AC-003）。
 */

/** 当前激活标签页 */
const activeTab = ref<'llm' | 'mcp' | 'tools'>('llm');

/** 标签页列表 */
const tabs = [
  { key: 'llm' as const, label: 'LLM 配置' },
  { key: 'mcp' as const, label: 'MCP 服务' },
  { key: 'tools' as const, label: '工具管理' },
];

/** 切换标签页 */
function switchTab(key: 'llm' | 'mcp' | 'tools') {
  activeTab.value = key;
}
</script>

<template>
  <div class="settings-page">
    <!-- 标签页导航 -->
    <div class="settings-tabs">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        class="settings-tab"
        :class="{ active: activeTab === tab.key }"
        @click="switchTab(tab.key)"
      >
        {{ tab.label }}
      </button>
    </div>

    <!-- 标签页内容区（KeepAlive 保持各标签页状态，AC-003） -->
    <div class="settings-content">
      <KeepAlive>
        <LlmConfigPage v-if="activeTab === 'llm'" />
        <McpServicePage v-else-if="activeTab === 'mcp'" />
        <ToolManagementPage v-else />
      </KeepAlive>
    </div>
  </div>
</template>

<style scoped>
.settings-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--bg-primary);
}

.settings-tabs {
  display: flex;
  gap: var(--spacing-xs);
  padding: var(--spacing-md) var(--spacing-lg) 0;
  border-bottom: 1px solid var(--border);
}

.settings-tab {
  padding: var(--spacing-sm) var(--spacing-lg);
  border: none;
  background: transparent;
  font-family: var(--font-display);
  font-size: 14px;
  color: var(--text-muted);
  cursor: pointer;
  border-bottom: 2px solid transparent;
  transition: all 0.2s;
}

.settings-tab:hover {
  color: var(--text-primary);
}

.settings-tab.active {
  color: var(--accent);
  border-bottom-color: var(--accent);
}

.settings-content {
  flex: 1;
  overflow: hidden;
  display: flex;
}
</style>
