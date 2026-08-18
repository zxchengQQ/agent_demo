<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import type { WorkflowTemplateDetail, WorkflowTemplateSummary } from '@/types';
import { listTemplates, getTemplate, getExecution } from '@/api/workflow';
import WorkflowTemplateCard from './WorkflowTemplateCard.vue';
import SequentialExecuteView from './SequentialExecuteView.vue';
import ParallelExecuteView from './ParallelExecuteView.vue';
import LoopExecuteView from './LoopExecuteView.vue';
import ConditionalExecuteView from './ConditionalExecuteView.vue';
import SupervisorExecuteView from './SupervisorExecuteView.vue';
import WorkflowExecuteView from './WorkflowExecuteView.vue';
import WorkflowHistoryList from './WorkflowHistoryList.vue';

/**
 * 工作流编排主页面（P2 Task-18，AC-032）
 * 业务含义：编排管理页面容器，内部分 3 个子视图（模板列表 / 详情执行 / 历史）。
 * 通过 ref + v-if 切换（与项目现有无路由架构一致）。
 * P3 扩展：历史页 PAUSED 记录的恢复入口联动（Task-22，AC-017）。
 * CR-001 Task-30 扩展：执行视图按 template.mode 动态分发（AC-033）——
 * 各编排模式（串行/并行/循环/条件/层级）渲染专属执行视图组件，
 * 未识别的 mode 回退到 WorkflowExecuteView。
 */

/** 子视图类型 */
type WorkflowView = 'list' | 'execute' | 'history';

/** 当前子视图 */
const currentView = ref<WorkflowView>('list');
/** 模板列表 */
const templates = ref<WorkflowTemplateSummary[]>([]);
/** 选中的模板详情 */
const selectedTemplate = ref<WorkflowTemplateDetail | null>(null);
/** 断点恢复的执行 ID（非空时传入执行视图，挂载即从断点续跑） */
const resumeExecutionId = ref<string | null>(null);
/** 加载状态 */
const loading = ref(false);
/** 错误提示 */
const errorMessage = ref('');

/** 加载模板列表 */
async function loadTemplates() {
  loading.value = true;
  errorMessage.value = '';
  try {
    templates.value = await listTemplates();
  } catch {
    errorMessage.value = '加载模板列表失败';
  } finally {
    loading.value = false;
  }
}

/** 选择模板 → 加载详情 → 进入执行视图（新执行，清除恢复标记） */
async function selectTemplate(template: WorkflowTemplateSummary) {
  loading.value = true;
  errorMessage.value = '';
  try {
    selectedTemplate.value = await getTemplate(template.id);
    resumeExecutionId.value = null;
    currentView.value = 'execute';
  } catch {
    errorMessage.value = '加载模板详情失败';
  } finally {
    loading.value = false;
  }
}

/** 历史页恢复入口：按执行 ID 查详情 → 加载对应模板 → 进入执行视图挂载恢复 */
async function resumeExecution(executionId: string) {
  loading.value = true;
  errorMessage.value = '';
  try {
    const detail = await getExecution(executionId);
    selectedTemplate.value = await getTemplate(detail.templateId);
    resumeExecutionId.value = executionId;
    currentView.value = 'execute';
  } catch {
    errorMessage.value = '加载执行详情失败';
  } finally {
    loading.value = false;
  }
}

/** 返回列表视图 */
function backToList() {
  currentView.value = 'list';
  selectedTemplate.value = null;
  resumeExecutionId.value = null;
}

/**
 * 编排模式 -> 执行视图组件映射（CR-001 Task-30，AC-033）
 * 业务含义：各编排模式渲染专属执行视图，与模板的 mode 一一对应。
 * 未在此表内的 mode 走回退分支（WorkflowExecuteView）。
 */
const MODE_EXECUTE_VIEW: Record<string, unknown> = {
  SEQUENTIAL: SequentialExecuteView,
  PARALLEL: ParallelExecuteView,
  LOOP: LoopExecuteView,
  CONDITIONAL: ConditionalExecuteView,
  SUPERVISOR: SupervisorExecuteView,
};

/** 当前选中的模式执行视图（未识别的 mode 回退到 WorkflowExecuteView） */
const modeExecuteView = computed(() => {
  if (!selectedTemplate.value) return null;
  return MODE_EXECUTE_VIEW[selectedTemplate.value.mode] ?? WorkflowExecuteView;
});

onMounted(loadTemplates);
</script>

<template>
  <div class="workflow-page">
    <!-- 顶部：视图切换（列表 / 历史） -->
    <div class="page-tabs">
      <button
        class="tab"
        :class="{ active: currentView === 'list' }"
        @click="currentView = 'list'"
      >
        模板列表
      </button>
      <button
        class="tab"
        :class="{ active: currentView === 'history' }"
        @click="currentView = 'history'"
      >
        执行历史
      </button>
    </div>

    <!-- 错误提示 -->
    <div v-if="errorMessage" class="error-banner">{{ errorMessage }}</div>

    <!-- 模板列表视图 -->
    <div v-if="currentView === 'list'" class="list-view">
      <div v-if="loading" class="empty-state">加载中…</div>
      <div v-else-if="templates.length === 0" class="empty-state">暂无可用模板</div>
      <div v-else class="template-grid">
        <WorkflowTemplateCard
          v-for="template in templates"
          :key="template.id"
          :template="template"
          @select="selectTemplate"
        />
      </div>
    </div>

    <!-- 执行视图（按 template.mode 动态分发模式组件，resumeExecutionId 非空时挂载即从断点续跑） -->
    <component
      v-else-if="currentView === 'execute' && selectedTemplate"
      :is="modeExecuteView"
      :template="selectedTemplate"
      :resume-execution-id="resumeExecutionId ?? undefined"
      @back="backToList"
    />

    <!-- 历史视图 -->
    <WorkflowHistoryList v-else-if="currentView === 'history'" @resume="resumeExecution" />
  </div>
</template>

<style scoped>
.workflow-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
}

.page-tabs {
  display: flex;
  gap: 4px;
  padding: 8px 16px;
  border-bottom: 1px solid var(--border, #2a3350);
  flex-shrink: 0;
}

.tab {
  padding: 6px 16px;
  font-size: 13px;
  color: var(--text-muted, #8b94a7);
  background: transparent;
  border: none;
  border-bottom: 2px solid transparent;
  cursor: pointer;
  transition: all 0.2s;
}

.tab:hover {
  color: var(--text-primary, #e6edf3);
}

.tab.active {
  color: var(--accent, #00d4b8);
  border-bottom-color: var(--accent, #00d4b8);
}

.error-banner {
  margin: 8px 16px 0;
  padding: 8px;
  font-size: 12px;
  color: #ff6b6b;
  background: rgba(255, 107, 107, 0.08);
  border-radius: 4px;
}

.list-view {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
}

.empty-state {
  color: var(--text-muted, #8b94a7);
  font-size: 13px;
  text-align: center;
  padding: 60px 0;
}

.template-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
}
</style>
