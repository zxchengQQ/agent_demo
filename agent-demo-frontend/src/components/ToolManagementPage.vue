<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { fetchAvailableTools, updateToolPermission } from '@/api/tools';
import type { ToolInfo, ToolPermissionLevel } from '@/types';

/**
 * 工具管理页面（工具按需加载 + 权限配置）
 * 业务含义：展示系统所有可用工具清单，按类别分组，标注每个工具的标识、描述、默认/可选状态，
 * 并提供权限等级切换（放行/需确认/禁止）。变更即调 updateToolPermission 持久化（AC-N01）。
 * 交互优化：原生 select 替换为分段式选择器（三色编码），卡片左侧色条随权限联动。
 */

/** askUser 工具固定放行（AC-S03 防确认死锁），由后端豁免，前端禁用编辑 */
const ASK_USER_TOOL_ID = 'builtin:askUser';

/** 权限选项（中文标签 + 色彩语义） */
const PERMISSION_OPTIONS: { value: ToolPermissionLevel; label: string; icon: string }[] = [
  { value: 'allow', label: '放行', icon: '✓' },
  { value: 'ask', label: '需确认', icon: '?' },
  { value: 'deny', label: '禁止', icon: '✕' },
];

/** 类别图标与标签 */
const CATEGORY_META: Record<string, { label: string; icon: string }> = {
  builtin: { label: '内置工具', icon: '⚙' },
  mcp: { label: 'MCP 工具', icon: '🔌' },
  rag: { label: '知识库工具', icon: '📚' },
};

const tools = ref<ToolInfo[]>([]);
const loading = ref(false);
const error = ref('');
/** 正在提交权限的 toolId（防重复点击） */
const savingToolId = ref<string | null>(null);
/** 权限变更失败提示 */
const saveError = ref('');

/** 按类别分组 */
const grouped = computed(() => {
  const groups: { category: string; label: string; icon: string; items: ToolInfo[] }[] = [];
  const order = ['builtin', 'mcp', 'rag'];
  for (const cat of order) {
    const items = tools.value.filter((t) => t.category === cat);
    if (items.length > 0) {
      groups.push({ category: cat, label: CATEGORY_META[cat].label, icon: CATEGORY_META[cat].icon, items });
    }
  }
  return groups;
});

/** 判断工具是否允许编辑权限（askUser 固定放行，不可修改） */
function isPermissionEditable(toolId: string): boolean {
  return toolId !== ASK_USER_TOOL_ID;
}

/**
 * 切换工具权限
 * 业务含义：点击权限选项即调 PUT 接口持久化；成功后更新本地状态即时生效，
 * 失败则回滚到原值并提示错误（避免界面与后端状态不一致）。
 */
async function changePermission(tool: ToolInfo, newPermission: ToolPermissionLevel) {
  const oldPermission = tool.permission ?? 'allow';
  if (newPermission === oldPermission || savingToolId.value) return;
  savingToolId.value = tool.id;
  saveError.value = '';
  try {
    await updateToolPermission(tool.id, newPermission);
    tool.permission = newPermission;
  } catch (e: any) {
    // 业务含义：变更失败回滚显示并提示错误（AC-N01 失败路径）
    tool.permission = oldPermission;
    saveError.value = `${tool.id} 权限更新失败：${e?.message || '请稍后重试'}`;
  } finally {
    savingToolId.value = null;
  }
}

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
      <div class="tool-management-title">
        <span class="title-icon">🧰</span>
        <h3>工具管理</h3>
      </div>
      <span class="tool-total">共 {{ tools.length }} 个工具</span>
    </div>

    <!-- 权限图例说明 -->
    <div class="permission-legend">
      <span class="legend-item"><span class="legend-dot legend-allow"></span>放行：Agent 可直接执行</span>
      <span class="legend-item"><span class="legend-dot legend-ask"></span>需确认：执行前弹出确认卡片</span>
      <span class="legend-item"><span class="legend-dot legend-deny"></span>禁止：不可见不可调用</span>
    </div>

    <div v-if="loading" class="tool-loading">加载中...</div>
    <div v-else-if="error" class="tool-error">
      {{ error }}
      <button class="btn-retry" @click="loadTools">重试</button>
    </div>

    <div v-else-if="saveError" class="tool-error">
      {{ saveError }}
      <button class="btn-retry" @click="saveError = ''">关闭</button>
    </div>

    <div v-else-if="tools.length === 0" class="tool-empty">暂无可用工具</div>

    <div v-else class="tool-groups">
      <!-- 按类别分组展示 -->
      <div v-for="group in grouped" :key="group.category" class="tool-group">
        <div class="tool-group-title">
          <span class="group-icon">{{ group.icon }}</span>
          <span class="group-label">{{ group.label }}</span>
          <span class="group-count">{{ group.items.length }}</span>
        </div>
        <div class="tool-group-list">
          <div
            v-for="tool in group.items"
            :key="tool.id"
            class="tool-item"
            :class="`perm-border-${tool.permission ?? 'allow'}`"
          >
            <div class="tool-item-body">
              <div class="tool-item-head">
                <span class="tool-item-name">{{ tool.name }}</span>
                <span
                  class="tool-item-badge"
                  :class="tool.isDefault ? 'badge-default' : 'badge-optional'"
                >
                  {{ tool.isDefault ? '默认加载' : '需指定' }}
                </span>
              </div>
              <div class="tool-item-id">{{ tool.id }}</div>
              <div v-if="tool.description" class="tool-item-desc">{{ tool.description }}</div>
            </div>

            <!-- 权限选择：askUser 固定放行禁用编辑（豁免可视化，AC-S03） -->
            <div class="tool-item-control">
              <div
                v-if="isPermissionEditable(tool.id)"
                class="permission-segmented"
                :class="{ saving: savingToolId === tool.id }"
                data-testid="permission-select"
              >
                <button
                  v-for="opt in PERMISSION_OPTIONS"
                  :key="opt.value"
                  class="perm-btn"
                  :class="[
                    `perm-btn-${opt.value}`,
                    { active: (tool.permission ?? 'allow') === opt.value },
                  ]"
                  :data-testid="`permission-option-${opt.value}`"
                  :disabled="savingToolId === tool.id"
                  :title="`切换为${opt.label}`"
                  @click="changePermission(tool, opt.value)"
                >
                  <span class="perm-btn-icon">{{ opt.icon }}</span>
                  <span class="perm-btn-label">{{ opt.label }}</span>
                </button>
              </div>
              <div v-else class="permission-fixed" data-testid="permission-fixed">
                <span class="perm-btn perm-btn-allow active">
                  <span class="perm-btn-icon">✓</span>
                  <span class="perm-btn-label">放行</span>
                </span>
                <span class="fixed-mark" title="askUser 为人机交互入口，固定放行防止确认死锁">🔒 固定</span>
              </div>
            </div>
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
  margin-bottom: var(--spacing-sm);
}

.tool-management-title {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.title-icon {
  font-size: 18px;
}

.tool-management-title h3 {
  font-family: var(--font-display);
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
}

.tool-total {
  font-size: 12px;
  color: var(--text-muted);
  font-family: var(--font-display);
}

/* ===== 权限图例 ===== */
.permission-legend {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-md);
  padding: var(--spacing-sm) var(--spacing-md);
  margin-bottom: var(--spacing-md);
  background: var(--bg-sidebar);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  font-size: 12px;
  color: var(--text-secondary);
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.legend-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.legend-allow { background: var(--success); }
.legend-ask { background: var(--warning); }
.legend-deny { background: var(--danger); }

.tool-loading,
.tool-empty {
  padding: var(--spacing-lg);
  text-align: center;
  color: var(--text-muted);
  font-size: 13px;
}

.tool-error {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-md);
  text-align: center;
  color: var(--danger);
  font-size: 13px;
  background: rgba(255, 68, 102, 0.08);
  border: 1px solid rgba(255, 68, 102, 0.25);
  border-radius: var(--radius-sm);
  margin-bottom: var(--spacing-md);
}

.btn-retry {
  padding: 2px var(--spacing-sm);
  border: 1px solid var(--danger);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--danger);
  cursor: pointer;
  font-size: 12px;
  transition: all 0.2s;
}

.btn-retry:hover {
  background: rgba(255, 68, 102, 0.15);
}

/* ===== 分组 ===== */
.tool-groups {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-lg);
}

.tool-group-title {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  margin-bottom: var(--spacing-sm);
}

.group-icon {
  font-size: 14px;
  width: 24px;
  height: 24px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--accent-dim);
  border-radius: var(--radius-sm);
}

.group-label {
  font-family: var(--font-display);
  font-size: 13px;
  font-weight: 600;
  color: var(--accent);
  letter-spacing: 0.5px;
}

.group-count {
  font-family: var(--font-display);
  font-size: 11px;
  color: var(--text-muted);
  background: var(--bg-input);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 0 8px;
  line-height: 18px;
}

.tool-group-list {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

/* ===== 工具卡片 ===== */
.tool-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
  padding: var(--spacing-sm) var(--spacing-md);
  background: var(--bg-sidebar);
  border: 1px solid var(--border);
  border-left: 3px solid var(--border);
  border-radius: var(--radius-sm);
  transition: border-color 0.2s, background 0.2s, box-shadow 0.2s;
}

.tool-item:hover {
  background: var(--bg-hover);
  box-shadow: var(--shadow-sm);
}

/* 左侧色条随权限等级联动（绿/橙/红） */
.perm-border-allow { border-left-color: var(--success); }
.perm-border-ask { border-left-color: var(--warning); }
.perm-border-deny { border-left-color: var(--danger); }

.tool-item-body {
  min-width: 0; /* 允许长文本截断 */
  flex: 1;
}

.tool-item-head {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.tool-item-name {
  font-family: var(--font-display);
  font-size: 13px;
  font-weight: 500;
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

.tool-item-id {
  margin-top: 2px;
  font-family: var(--font-display);
  font-size: 11px;
  color: var(--text-muted);
}

.tool-item-desc {
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

/* ===== 权限分段选择器 ===== */
.tool-item-control {
  flex-shrink: 0;
}

.permission-segmented {
  display: inline-flex;
  padding: 2px;
  background: var(--bg-primary);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  gap: 2px;
}

.permission-segmented.saving {
  opacity: 0.55;
  pointer-events: none;
}

.perm-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  font-size: 12px;
  font-family: var(--font-body);
  border: none;
  border-radius: 4px;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  transition: all 0.15s ease;
  white-space: nowrap;
}

.perm-btn:disabled {
  cursor: not-allowed;
}

.perm-btn-icon {
  font-size: 11px;
  font-family: var(--font-display);
}

.perm-btn-label {
  font-size: 12px;
}

/* 未选中：幽灵态 hover 时显色 */
.perm-btn-allow:hover:not(.active) { color: var(--success); background: rgba(0, 255, 136, 0.08); }
.perm-btn-ask:hover:not(.active) { color: var(--warning); background: rgba(255, 170, 0, 0.08); }
.perm-btn-deny:hover:not(.active) { color: var(--danger); background: rgba(255, 68, 102, 0.08); }

/* 选中：色块高亮（文字用深色保证对比度） */
.perm-btn-allow.active {
  background: var(--success);
  color: #052214;
  font-weight: 600;
}

.perm-btn-ask.active {
  background: var(--warning);
  color: #2b1c00;
  font-weight: 600;
}

.perm-btn-deny.active {
  background: var(--danger);
  color: #2b000d;
  font-weight: 600;
}

/* askUser 固定放行展示 */
.permission-fixed {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.permission-fixed .perm-btn {
  cursor: default;
}

.fixed-mark {
  font-size: 11px;
  color: var(--text-muted);
  white-space: nowrap;
}
</style>
