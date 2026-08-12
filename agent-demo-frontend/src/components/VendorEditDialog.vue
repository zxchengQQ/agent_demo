<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { useLlmStore } from '@/stores/llm';
import { testConnection as apiTestConnection } from '@/api/llm';
import type { LlmVendor, PredefinedVendor, VendorRequest } from '@/types';

/**
 * 厂商添加/编辑弹窗（Task-20）
 * 业务含义：LLM 配置表单弹窗，分四个区域：
 *  1. 厂商选择（新增：预定义/自定义；编辑：只读名称）
 *  2. 基础信息（名称、Base URL、thinkingTrigger）
 *  3. API Key（脱敏显示 + 显示/隐藏切换 + 测试连接）
 *  4. 模型配置（按 chat/embedding/rerank/multimodal 分组管理）
 * 新增模式调用 addVendor，编辑模式调用 updateVendor。
 * 测试连接失败时禁止保存。
 */

type ModelTypeTab = 'chat' | 'embedding' | 'rerank' | 'multimodal';

/** 模型类型 tab 定义 */
const MODEL_TYPE_TABS: { key: ModelTypeTab; label: string }[] = [
  { key: 'chat', label: '对话' },
  { key: 'embedding', label: '向量化' },
  { key: 'rerank', label: '重排' },
  { key: 'multimodal', label: '多模态' },
];

const props = defineProps<{
  /** 是否显示 */
  visible: boolean;
  /** 编辑模式传入厂商，新增模式为 null */
  vendor?: LlmVendor | null;
  /** 预定义厂商列表 */
  predefinedVendors: PredefinedVendor[];
}>();

const emit = defineEmits<{
  close: [];
  saved: [];
}>();

const llmStore = useLlmStore();

/** ===== 区域1：厂商选择 ===== */
/** 厂商类型（新增模式可选，编辑模式继承） */
const vendorType = ref<'predefined' | 'custom'>('predefined');
/** 预定义厂商下拉选中 code */
const selectedPredefinedCode = ref('');

/** ===== 区域2：基础信息 ===== */
const name = ref('');
const baseUrl = ref('');
const thinkingTrigger = ref<'enabled' | 'none'>('none');

/** ===== 区域3：API Key ===== */
const apiKey = ref('');
/** 是否明文显示密码 */
const apiKeyVisible = ref(false);
/** 是否正在测试连接 */
const testing = ref(false);
/** 测试连接结果 */
const testResult = ref<{ ok: boolean; message: string } | null>(null);

/** ===== 区域4：模型配置 ===== */
interface ModelRow {
  modelName: string;
  displayName: string;
  type: ModelTypeTab;
  supportsVision: boolean;
}
const modelRows = ref<ModelRow[]>([]);
/** 当前选中的模型类型 tab */
const activeTypeTab = ref<ModelTypeTab>('chat');

/** 是否编辑模式 */
const isEdit = computed(() => !!props.vendor);

/** 选中的预定义厂商对象 */
const selectedPredefinedVendor = computed<PredefinedVendor | undefined>(() =>
  props.predefinedVendors.find((p) => p.code === selectedPredefinedCode.value),
);

/** 预定义厂商的 chat 类型常用模型（供下拉添加） */
const predefinedChatModels = computed(() =>
  selectedPredefinedVendor.value
    ? selectedPredefinedVendor.value.models.filter((m) => m.type === 'chat')
    : [],
);

/** 当前类型 tab 下的模型行 */
const activeModels = computed(() =>
  modelRows.value.filter((m) => m.type === activeTypeTab.value),
);

/** 当前类型 tab 下是否有空 modelName 的行（校验提示） */
const hasEmptyModelName = computed(() =>
  activeModels.value.some((m) => m.modelName.trim() === ''),
);

/** 是否可保存：基础信息完整 + 未测试失败 + 无空模型名 */
const canSave = computed(() => {
  if (!name.value.trim() || !baseUrl.value.trim()) return false;
  if (testResult.value && !testResult.value.ok) return false;
  if (modelRows.value.some((m) => m.modelName.trim() === '')) return false;
  return true;
});

/** 关闭弹窗 */
function closeDialog() {
  emit('close');
}

/** 选择预定义厂商：自动填充 baseUrl、thinkingTrigger、模型列表 */
function handleSelectPredefined() {
  const vendor = selectedPredefinedVendor.value;
  if (!vendor) return;
  vendorType.value = 'predefined';
  name.value = vendor.name;
  baseUrl.value = vendor.baseUrl;
  thinkingTrigger.value = vendor.thinkingTrigger;
  // 预定义模型的模型行（含各类型）
  modelRows.value = vendor.models.map((m) => ({
    modelName: m.modelName,
    displayName: m.displayName,
    type: m.type as ModelTypeTab,
    supportsVision: m.supportsVision,
  }));
}

/** 下拉选择变更分发："自定义"选项走自定义逻辑，其余走预定义填充 */
function handleVendorSelectChange() {
  if (selectedPredefinedCode.value === '__custom__') {
    handleSelectCustom();
  } else {
    handleSelectPredefined();
  }
}

/** 选择"自定义"：清空基础信息与模型，thinkingTrigger 默认 none */
function handleSelectCustom() {
  vendorType.value = 'custom';
  selectedPredefinedCode.value = '';
  name.value = '';
  baseUrl.value = '';
  thinkingTrigger.value = 'none';
  modelRows.value = [];
}

/** 切换密码显示/隐藏 */
function toggleApiKeyVisible() {
  apiKeyVisible.value = !apiKeyVisible.value;
}

/** 测试连接（AC-00x） */
async function handleTestConnection() {
  testing.value = true;
  testResult.value = null;
  try {
    const result = await apiTestConnection(baseUrl.value, apiKey.value);
    testResult.value = { ok: result.success, message: result.message };
  } catch (err) {
    // 网络/接口异常按失败处理
    testResult.value = { ok: false, message: (err as Error).message || '连接失败' };
  } finally {
    testing.value = false;
  }
}

/** 切换当前模型类型 tab */
function switchTypeTab(tab: ModelTypeTab) {
  activeTypeTab.value = tab;
}

/** 添加模型行（当前类型 tab） */
function addModelRow() {
  modelRows.value.push({
    modelName: '',
    displayName: '',
    type: activeTypeTab.value,
    supportsVision: false,
  });
}

/** 添加预定义 chat 模型（下拉选择） */
function addPredefinedChatModel(modelName: string) {
  const vendor = selectedPredefinedVendor.value;
  const model = vendor?.models.find((m) => m.modelName === modelName && m.type === 'chat');
  if (!model) return;
  // 避免重复添加
  if (modelRows.value.some((m) => m.modelName === modelName)) return;
  modelRows.value.push({
    modelName: model.modelName,
    displayName: model.displayName,
    type: 'chat',
    supportsVision: model.supportsVision,
  });
}

/** 删除模型行 */
function removeModelRow(index: number) {
  // index 为在全部行中的下标（由 activeModels 映射）
  const target = modelRows.value[index];
  if (target) {
    modelRows.value.splice(index, 1);
  }
}

/** 构造 VendorRequest */
function buildVendorRequest(): VendorRequest {
  return {
    name: name.value.trim(),
    type: vendorType.value,
    baseUrl: baseUrl.value.trim(),
    apiKey: apiKey.value,
    thinkingTrigger: thinkingTrigger.value,
    timeout: props.vendor?.timeout ?? 60000,
    maxRetries: props.vendor?.maxRetries ?? 3,
    temperature: props.vendor?.temperature ?? 0.7,
    models: modelRows.value.map((m) => ({
      modelName: m.modelName,
      displayName: m.displayName,
      type: m.type,
      supportsVision: m.supportsVision,
    })),
  };
}

/** 保存：新增/编辑 + 持久化到 localStorage */
async function handleSave() {
  if (!canSave.value) return;
  const request = buildVendorRequest();
  try {
    if (isEdit.value && props.vendor) {
      await llmStore.updateVendor(props.vendor.id, request);
    } else {
      await llmStore.addVendor(request);
    }
    // 将完整配置（含 API Key 明文）保存到 localStorage 持久化
    llmStore.saveConfigToStorage([request]);
    emit('saved');
  } catch (err) {
    testResult.value = { ok: false, message: (err as Error).message || '保存失败' };
  }
}

/** 弹窗打开时初始化表单 */
watch(
  () => props.visible,
  (newVal) => {
    if (!newVal) return;
    // 重置通用状态
    apiKey.value = '';
    apiKeyVisible.value = false;
    testing.value = false;
    testResult.value = null;
    activeTypeTab.value = 'chat';

    if (props.vendor) {
      // 编辑模式：继承厂商信息
      vendorType.value = props.vendor.type;
      name.value = props.vendor.name;
      baseUrl.value = props.vendor.baseUrl;
      thinkingTrigger.value = props.vendor.thinkingTrigger;
      modelRows.value = (props.vendor.models ?? []).map((m) => ({
        modelName: m.modelName,
        displayName: m.displayName,
        type: m.type as ModelTypeTab,
        supportsVision: m.supportsVision,
      }));
      selectedPredefinedCode.value = '';
    } else {
      // 新增模式：默认选第一个预定义厂商
      vendorType.value = 'predefined';
      selectedPredefinedCode.value = props.predefinedVendors[0]?.code ?? '';
      if (props.predefinedVendors[0]) {
        handleSelectPredefined();
      } else {
        name.value = '';
        baseUrl.value = '';
        thinkingTrigger.value = 'none';
        modelRows.value = [];
      }
    }
  },
);
</script>

<template>
  <div v-if="visible" class="dialog-overlay" @click="closeDialog">
    <div class="dialog" @click.stop>
      <h2 class="dialog-title">
        {{ isEdit ? '编辑厂商' : '添加厂商' }}
      </h2>

      <!-- 区域1：厂商选择 -->
      <div class="form-section">
        <div class="section-label">厂商选择</div>
        <!-- 编辑模式：只读显示名称 -->
        <div v-if="isEdit" class="readonly-name">{{ name }}</div>
        <!-- 新增模式：预定义下拉 + 自定义选项 -->
        <template v-else>
          <div class="vendor-select-row">
            <select
              v-model="selectedPredefinedCode"
              class="input"
              @change="handleVendorSelectChange"
            >
              <option
                v-for="p in predefinedVendors"
                :key="p.code"
                :value="p.code"
              >
                {{ p.name }}
              </option>
              <option value="__custom__">自定义</option>
            </select>
          </div>
        </template>
      </div>

      <!-- 区域2：基础信息 -->
      <div class="form-section">
        <div class="section-label">基础信息</div>
        <!-- 厂商名称（自定义时需要；预定义自动填充） -->
        <div class="form-field">
          <label class="field-label">厂商名称</label>
          <input
            v-model="name"
            class="input"
            placeholder="请输入厂商名称"
            :disabled="!isEdit && vendorType === 'predefined'"
          />
        </div>
        <!-- Base URL -->
        <div class="form-field">
          <label class="field-label">Base URL</label>
          <input
            v-model="baseUrl"
            class="input"
            placeholder="https://api.example.com/v1"
            :disabled="!isEdit && vendorType === 'predefined'"
          />
        </div>
        <!-- thinkingTrigger -->
        <div class="form-field">
          <label class="field-label">Thinking Trigger</label>
          <select
            v-model="thinkingTrigger"
            class="input"
            :disabled="!isEdit && vendorType === 'predefined'"
          >
            <option value="enabled">enabled</option>
            <option value="none">none</option>
          </select>
        </div>
      </div>

      <!-- 区域3：API Key -->
      <div class="form-section">
        <div class="section-label">API Key</div>
        <!-- 编辑模式脱敏提示 -->
        <p v-if="isEdit && props.vendor?.apiKeyMasked" class="api-key-hint">
          当前：{{ props.vendor.apiKeyMasked }}（重新输入以更新）
        </p>
        <div class="form-field">
          <div class="api-key-row">
            <input
              v-model="apiKey"
              :type="apiKeyVisible ? 'text' : 'password'"
              class="input"
              placeholder="请输入 API Key"
            />
            <!-- 显示/隐藏切换（小眼睛按钮） -->
            <button
              class="btn-eye"
              :title="apiKeyVisible ? '隐藏' : '显示'"
              @click="toggleApiKeyVisible"
            >
              {{ apiKeyVisible ? '👁' : '🙈' }}
            </button>
          </div>
        </div>
        <!-- 测试连接 -->
        <div class="test-connection-row">
          <button
            class="btn-test"
            :disabled="testing || !baseUrl.trim() || !apiKey"
            @click="handleTestConnection"
          >
            {{ testing ? '测试中...' : '测试连接' }}
          </button>
        </div>
        <!-- 测试结果提示 -->
        <p
          v-if="testResult"
          class="test-result"
          :class="testResult.ok ? 'result-success' : 'result-fail'"
        >
          {{ testResult.message }}
        </p>
      </div>

      <!-- 区域4：模型配置 -->
      <div class="form-section">
        <div class="section-label">模型配置</div>
        <!-- 类型 tab -->
        <div class="type-tabs">
          <button
            v-for="tab in MODEL_TYPE_TABS"
            :key="tab.key"
            class="type-tab"
            :class="{ active: activeTypeTab === tab.key }"
            @click="switchTypeTab(tab.key)"
          >
            {{ tab.label }}
          </button>
        </div>

        <!-- 预定义 chat 模型下拉（仅预定义厂商 + 当前 chat tab） -->
        <div
          v-if="vendorType === 'predefined' && activeTypeTab === 'chat' && predefinedChatModels.length > 0"
          class="predefined-model-row"
        >
          <select
            class="input"
            :value="''"
            @change="addPredefinedChatModel(($event.target as HTMLSelectElement).value)"
          >
            <option value="" disabled>选择常用模型</option>
            <option
              v-for="m in predefinedChatModels"
              :key="m.modelName"
              :value="m.modelName"
            >
              {{ m.displayName }}
            </option>
          </select>
        </div>

        <!-- 当前类型模型行列表 -->
        <div
          v-for="(row, idx) in modelRows"
          :key="idx"
          v-show="row.type === activeTypeTab"
          class="model-row"
        >
          <input
            v-model="row.modelName"
            class="input model-name-input"
            placeholder="modelName"
          />
          <input
            v-model="row.displayName"
            class="input model-display-input"
            placeholder="displayName"
          />
          <!-- chat 类型支持视图理解勾选 -->
          <label v-if="row.type === 'chat'" class="vision-check">
            <input v-model="row.supportsVision" type="checkbox" />
            支持视图
          </label>
          <button class="btn-remove" @click="removeModelRow(idx)">删除</button>
        </div>
        <!-- 空模型名校验提示 -->
        <p v-if="hasEmptyModelName" class="field-error">
          模型名称不能为空
        </p>

        <!-- 添加模型按钮 -->
        <button class="btn-add-model" @click="addModelRow">
          添加{{ activeTypeTab === 'chat' ? '对话' : activeTypeTab === 'embedding' ? '向量化' : activeTypeTab === 'rerank' ? '重排' : '多模态' }}模型
        </button>
      </div>

      <!-- 操作按钮 -->
      <div class="dialog-actions">
        <button class="btn-cancel" @click="closeDialog">取消</button>
        <button
          class="btn-submit"
          :disabled="!canSave"
          @click="handleSave"
        >
          {{ isEdit ? '保存' : '添加' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dialog-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 200;
}

.dialog {
  background: var(--bg-sidebar);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: var(--spacing-lg);
  width: 560px;
  max-height: 90vh;
  overflow-y: auto;
  box-shadow: var(--shadow-sm);
}

.dialog-title {
  font-family: var(--font-display);
  font-size: 16px;
  font-weight: 700;
  color: var(--text-primary);
  margin-bottom: var(--spacing-md);
}

.form-section {
  margin-bottom: var(--spacing-md);
  padding: var(--spacing-md);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-input);
}

.section-label {
  font-family: var(--font-display);
  font-size: 12px;
  font-weight: 500;
  color: var(--accent);
  margin-bottom: var(--spacing-sm);
}

.readonly-name {
  font-size: 13px;
  color: var(--text-primary);
}

.form-field {
  margin-bottom: var(--spacing-sm);
}

.field-label {
  display: block;
  font-size: 12px;
  color: var(--text-secondary);
  margin-bottom: var(--spacing-xs);
}

.input {
  width: 100%;
  padding: var(--spacing-xs) var(--spacing-sm);
  background: var(--bg-sidebar);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  color: var(--text-primary);
  font-size: 13px;
  font-family: var(--font-body);
  outline: none;
  transition: border-color 0.2s;
}

.input:focus {
  border-color: var(--accent);
}

.input:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.api-key-hint {
  font-size: 12px;
  color: var(--text-muted);
  margin-bottom: var(--spacing-xs);
}

.api-key-row {
  display: flex;
  gap: var(--spacing-xs);
}

.api-key-row .input {
  flex: 1;
}

.btn-eye {
  flex-shrink: 0;
  padding: 0 var(--spacing-sm);
  background: var(--bg-sidebar);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  color: var(--text-secondary);
  cursor: pointer;
}

.btn-eye:hover {
  border-color: var(--accent-dim);
}

.test-connection-row {
  margin-top: var(--spacing-xs);
}

.btn-test {
  padding: var(--spacing-xs) var(--spacing-md);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-secondary);
  font-family: var(--font-display);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-test:hover:not(:disabled) {
  border-color: var(--accent-dim);
  color: var(--accent);
}

.btn-test:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.test-result {
  margin-top: var(--spacing-xs);
  font-size: 12px;
}

.result-success {
  color: var(--success);
}

.result-fail {
  color: var(--danger);
}

.type-tabs {
  display: flex;
  gap: var(--spacing-xs);
  margin-bottom: var(--spacing-sm);
}

.type-tab {
  padding: var(--spacing-xs) var(--spacing-sm);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-muted);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
}

.type-tab.active {
  color: var(--accent);
  border-color: var(--accent);
  background: var(--accent-dim);
}

.predefined-model-row {
  margin-bottom: var(--spacing-sm);
}

.model-row {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  margin-bottom: var(--spacing-xs);
}

.model-name-input {
  flex: 1;
}

.model-display-input {
  flex: 1.2;
}

.vision-check {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--text-secondary);
  white-space: nowrap;
}

.btn-remove {
  flex-shrink: 0;
  padding: var(--spacing-xs) var(--spacing-sm);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-secondary);
  font-size: 12px;
  cursor: pointer;
}

.btn-remove:hover {
  border-color: var(--danger);
  color: var(--danger);
}

.field-error {
  font-size: 12px;
  color: var(--danger);
  margin-top: var(--spacing-xs);
}

.btn-add-model {
  margin-top: var(--spacing-xs);
  padding: var(--spacing-xs) var(--spacing-md);
  border: 1px dashed var(--border);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-secondary);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-add-model:hover {
  border-color: var(--accent-dim);
  color: var(--accent);
}

.dialog-actions {
  display: flex;
  gap: var(--spacing-sm);
  justify-content: flex-end;
}

.btn-cancel,
.btn-submit {
  padding: var(--spacing-sm) var(--spacing-lg);
  border-radius: var(--radius-sm);
  font-size: 13px;
  cursor: pointer;
  border: none;
  transition: all 0.2s;
}

.btn-cancel {
  background: var(--bg-input);
  color: var(--text-secondary);
}

.btn-cancel:hover {
  background: var(--bg-hover);
}

.btn-submit {
  background: var(--accent);
  color: var(--bg-primary);
}

.btn-submit:hover:not(:disabled) {
  background: var(--accent-glow);
}

.btn-submit:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
</style>
