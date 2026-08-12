<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useLlmStore } from '@/stores/llm';
import { getPredefinedVendors } from '@/api/llm';
import type { PredefinedVendor, LlmVendor } from '@/types';
import VendorCard from '@/components/VendorCard.vue';
import VendorEditDialog from '@/components/VendorEditDialog.vue';

/**
 * LLM 厂商配置管理页面（Task-19）
 * 业务含义：配置管理主页面，展示已配置厂商卡片列表。
 * 页面加载时拉取厂商列表和预定义厂商目录。
 * 支持"添加厂商"（打开 VendorEditDialog 新增模式）、
 * 编辑、删除（删除后刷新列表）。
 */
const llmStore = useLlmStore();

/** 预定义厂商目录（供 VendorEditDialog 使用） */
const predefinedVendors = ref<PredefinedVendor[]>([]);

/** VendorEditDialog 显示状态 */
const showEditDialog = ref(false);

/** 当前编辑的厂商（null 表示新增模式） */
const editingVendor = ref<LlmVendor | null>(null);

// 初始化：加载厂商列表 + 预定义厂商目录（AC-00x）
onMounted(async () => {
  await llmStore.loadVendors();
  try {
    predefinedVendors.value = await getPredefinedVendors();
  } catch {
    // 预定义目录加载失败不阻断页面（厂商列表已展示）
    predefinedVendors.value = [];
  }
});

/** 打开新增厂商弹窗 */
function openAdd() {
  editingVendor.value = null;
  showEditDialog.value = true;
}

/** 打开编辑厂商弹窗 */
function openEdit(vendor: LlmVendor) {
  editingVendor.value = vendor;
  showEditDialog.value = true;
}

/** 删除厂商 */
async function handleDelete(vendor: LlmVendor) {
  try {
    await llmStore.deleteVendor(vendor.id);
  } catch {
    // 删除失败：保持现状，由用户重试
  }
}

/** 保存成功后关闭弹窗并刷新 */
function handleSaved() {
  showEditDialog.value = false;
  llmStore.loadVendors();
}
</script>

<template>
  <div class="llm-config-page">
    <!-- 页面头部：标题 + 添加厂商按钮 -->
    <div class="page-header">
      <h2 class="page-title">LLM 配置</h2>
      <button class="btn-add" @click="openAdd">添加厂商</button>
    </div>

    <!-- 厂商卡片网格 -->
    <div v-if="llmStore.vendors.length > 0" class="vendor-grid">
      <VendorCard
        v-for="vendor in llmStore.vendors"
        :key="vendor.id"
        :vendor="vendor"
        @edit="openEdit"
        @delete="handleDelete"
      />
    </div>

    <!-- 空状态引导 -->
    <div v-else class="empty-state">
      <p class="empty-text">暂无已配置厂商</p>
      <button class="btn-add-empty" @click="openAdd">添加厂商</button>
    </div>

    <!-- 添加/编辑弹窗 -->
    <VendorEditDialog
      :visible="showEditDialog"
      :vendor="editingVendor"
      :predefined-vendors="predefinedVendors"
      @close="showEditDialog = false"
      @saved="handleSaved"
    />
  </div>
</template>

<style scoped>
.llm-config-page {
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

.btn-add:hover {
  box-shadow: var(--shadow-glow);
}

.vendor-grid {
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
