<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useSkillStore } from '@/stores/skill';
import type { SkillInfo, SkillScriptInfo } from '@/types';
import * as skillApi from '@/api/skill';

/**
 * 技能管理页面（agent-skill Task-21，AC-N06/S01/H03；CR-001 适配自带脚本工具）
 * 业务含义：设置页技能管理标签——技能列表展示、创建/编辑表单（含自带脚本编辑）、
 * 启停开关、删除确认、内容校验错误分类反馈（AC-H03）。
 */

const skillStore = useSkillStore();

const loading = ref(false);
const error = ref('');
const formError = ref('');
const formWarnings = ref<string[]>([]);

/** 编辑中的技能（null 表示新建表单关闭） */
const editing = ref<SkillInfo | null>(null);
const isNew = ref(false);

/** 表单字段 */
const formId = ref('');
const formName = ref('');
const formDescription = ref('');
const formInstruction = ref('');
/** 自带脚本列表（CR-001） */
const formScripts = ref<SkillScriptInfo[]>([]);

/** 技能列表按来源分组展示（预置在前） */
const sortedSkills = computed(() => {
  return [...skillStore.skills].sort((a, b) => {
    if (a.source !== b.source) return a.source === 'PRESET' ? -1 : 1;
    return a.id.localeCompare(b.id);
  });
});

onMounted(async () => {
  loading.value = true;
  try {
    await skillStore.loadSkills();
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载技能失败';
  } finally {
    loading.value = false;
  }
});

/** 打开新建表单 */
function openCreate() {
  isNew.value = true;
  editing.value = null;
  formId.value = '';
  formName.value = '';
  formDescription.value = '';
  formInstruction.value = '';
  formScripts.value = [];
  formError.value = '';
  formWarnings.value = [];
}

/** 打开编辑表单 */
function openEdit(skill: SkillInfo) {
  isNew.value = false;
  editing.value = skill;
  formId.value = skill.id;
  formName.value = skill.name;
  formDescription.value = skill.description;
  formInstruction.value = skill.instruction ?? '';
  formScripts.value = skill.scripts ? JSON.parse(JSON.stringify(skill.scripts)) : [];
  formError.value = '';
  formWarnings.value = [];
}

/** 关闭表单（CR-001 bug 修复：同时清 isNew，否则新建表单无法关闭） */
function closeForm() {
  editing.value = null;
  isNew.value = false;
  formError.value = '';
  formWarnings.value = [];
}

// ===== CR-001: 自带脚本编辑 =====

/** 添加空脚本 */
function addScript() {
  formScripts.value.push({
    name: '',
    language: 'shell',
    description: '',
    params: [],
    content: '',
  });
}

/** 删除脚本 */
function removeScript(index: number) {
  formScripts.value.splice(index, 1);
}

/** 添加脚本参数 */
function addParam(scriptIndex: number) {
  const script = formScripts.value[scriptIndex];
  if (!script.params) script.params = [];
  script.params.push({ name: '', type: 'string', required: false, description: '' });
}

/** 删除脚本参数 */
function removeParam(scriptIndex: number, paramIndex: number) {
  formScripts.value[scriptIndex].params?.splice(paramIndex, 1);
}

/** 保存（创建/编辑），校验错误分类反馈（AC-H03） */
async function save() {
  formError.value = '';
  formWarnings.value = [];
  const payload: skillApi.SkillRequestPayload = {
    name: formName.value.trim(),
    description: formDescription.value.trim(),
    instruction: formInstruction.value,
    resources: [],
    scripts: formScripts.value.filter((s) => s.name.trim() && s.content.trim()),
  };
  if (isNew.value) {
    if (!formId.value.trim()) {
      formError.value = '技能 id 不能为空';
      return;
    }
    payload.id = formId.value.trim();
  }
  try {
    if (isNew.value) {
      const created = await skillApi.createSkill(payload);
      formWarnings.value = created.warnings ?? [];
    } else if (editing.value) {
      const updated = await skillApi.updateSkill(editing.value.id, payload);
      formWarnings.value = updated.warnings ?? [];
    }
    await skillStore.loadSkills();
    if (formWarnings.value.length === 0) {
      closeForm();
    }
    // 有警告时保留表单展示警告（密钥类提示）
  } catch (e) {
    formError.value = e instanceof Error ? e.message : '保存失败';
  }
}

/** 启停切换 */
async function toggleEnabled(skill: SkillInfo) {
  try {
    await skillStore.toggleEnabled(skill.id, !skill.enabled);
  } catch (e) {
    error.value = e instanceof Error ? e.message : '操作失败';
  }
}

/** 删除技能 */
async function removeSkill(skill: SkillInfo) {
  if (!window.confirm(`确认删除技能「${skill.name}」？`)) return;
  try {
    await skillStore.deleteSkill(skill.id);
  } catch (e) {
    error.value = e instanceof Error ? e.message : '删除失败';
  }
}
</script>

<template>
  <div class="skill-management">
    <div class="skill-management-header">
      <div class="skill-management-title">
        <span class="title-icon">✦</span>
        <span>技能管理</span>
      </div>
      <button class="btn-create" @click="openCreate">+ 新建技能</button>
    </div>

    <div v-if="loading" class="skill-loading">加载中...</div>
    <div v-else-if="error" class="skill-error">
      <span>{{ error }}</span>
      <button class="btn-retry" @click="error = ''; skillStore.loadSkills()">重试</button>
    </div>
    <div v-else-if="skillStore.skills.length === 0" class="skill-empty">
      暂无技能，点击"新建技能"创建
    </div>
    <div v-else class="skill-list">
      <div v-for="skill in sortedSkills" :key="skill.id" class="skill-item">
        <div class="skill-item-body">
          <div class="skill-item-head">
            <span class="skill-item-name">{{ skill.name }}</span>
            <span class="skill-badge" :class="skill.source === 'PRESET' ? 'badge-preset' : 'badge-custom'">
              {{ skill.source === 'PRESET' ? '预置' : '自定义' }}
            </span>
            <span class="skill-item-id">{{ skill.id }}</span>
          </div>
          <div class="skill-item-desc">{{ skill.description }}</div>
          <div v-if="skill.scripts && skill.scripts.length" class="skill-item-tools">
            自带脚本：{{ skill.scripts.map((s) => `${s.name}(${s.language})`).join('、') }}
          </div>
        </div>
        <div class="skill-item-actions">
          <button class="btn-toggle" :class="{ on: skill.enabled }" @click="toggleEnabled(skill)">
            {{ skill.enabled ? '已启用' : '已禁用' }}
          </button>
          <button class="btn-edit" @click="openEdit(skill)">编辑</button>
          <button class="btn-delete" @click="removeSkill(skill)">删除</button>
        </div>
      </div>
    </div>

    <!-- 创建/编辑表单 -->
    <div v-if="isNew || editing" class="skill-form-overlay">
      <div class="skill-form">
        <div class="skill-form-title">{{ isNew ? '新建技能' : `编辑技能：${editing?.name}` }}</div>
        <div v-if="formError" class="form-error">{{ formError }}</div>
        <div v-if="formWarnings.length" class="form-warning">
          <div v-for="(w, i) in formWarnings" :key="i">{{ w }}</div>
        </div>
        <label class="form-field">
          <span>技能 id（kebab-case，仅新建可填）</span>
          <input v-model="formId" :disabled="!isNew" placeholder="如 weekly-report-expert" />
        </label>
        <label class="form-field">
          <span>技能名称</span>
          <input v-model="formName" placeholder="如 周报撰写专家" />
        </label>
        <label class="form-field">
          <span>技能描述（Agent 匹配依据，须简洁）</span>
          <textarea v-model="formDescription" rows="2" placeholder="描述技能适用场景，Agent 据此判断是否激活" />
        </label>
        <label class="form-field">
          <span>领域指令</span>
          <textarea v-model="formInstruction" rows="5" placeholder="技能激活后注入的指令内容" />
        </label>

        <!-- CR-001: 自带脚本编辑（替代原绑定系统工具） -->
        <div class="form-field">
          <span>自带脚本（预定义参数化脚本，语言白名单 shell/python3）</span>
          <button type="button" class="btn-add-script" @click="addScript">+ 添加脚本</button>
        </div>
        <div v-for="(script, si) in formScripts" :key="si" class="script-editor">
          <div class="script-editor-head">
            <span class="script-editor-title">脚本 {{ si + 1 }}</span>
            <button type="button" class="btn-script-remove" @click="removeScript(si)">删除脚本</button>
          </div>
          <div class="script-row">
            <input v-model="script.name" placeholder="脚本名（kebab-case，如 http-get）" />
            <select v-model="script.language">
              <option value="shell">shell</option>
              <option value="python3">python3</option>
            </select>
          </div>
          <input v-model="script.description" placeholder="脚本描述（Agent 路由依据）" />
          <textarea v-model="script.content" rows="3" placeholder="脚本内容（经环境变量 SKILL_PARAM_{参数名} 引用参数）" />
          <div class="param-editor">
            <span class="param-label">参数</span>
            <button type="button" class="btn-add-param" @click="addParam(si)">+ 参数</button>
            <div v-for="(param, pi) in script.params ?? []" :key="pi" class="param-row">
              <input v-model="param.name" placeholder="参数名" />
              <select v-model="param.type">
                <option value="string">string</option>
                <option value="integer">integer</option>
                <option value="number">number</option>
                <option value="boolean">boolean</option>
              </select>
              <label class="param-required"><input v-model="param.required" type="checkbox" />必填</label>
              <button type="button" class="btn-param-remove" @click="removeParam(si, pi)">×</button>
            </div>
          </div>
        </div>
        <div class="form-actions">
          <button class="btn-cancel" @click="closeForm">取消</button>
          <button class="btn-save" @click="save">保存</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.skill-management {
  flex: 1;
  overflow-y: auto;
  padding: var(--spacing-md);
  background: var(--bg-primary);
}

.skill-management-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--spacing-md);
}

.skill-management-title {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  font-family: var(--font-display);
  font-size: 16px;
  color: var(--text-primary);
}

.title-icon {
  color: var(--accent);
}

.btn-create {
  padding: 6px 14px;
  border: 1px solid var(--accent);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--accent);
  font-family: var(--font-display);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-create:hover {
  background: var(--accent-dim);
}

.skill-loading, .skill-empty {
  padding: var(--spacing-lg);
  text-align: center;
  color: var(--text-muted);
  font-size: 13px;
}

.skill-error {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-sm);
  color: #ff6b6b;
  font-size: 13px;
}

.btn-retry {
  padding: 2px 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
}

.skill-list {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.skill-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-sm) var(--spacing-md);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-sidebar);
}

.skill-item-body {
  flex: 1;
  min-width: 0;
}

.skill-item-head {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
}

.skill-item-name {
  font-family: var(--font-display);
  font-size: 14px;
  color: var(--text-primary);
}

.skill-badge {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: var(--radius-sm);
}

.badge-preset {
  color: var(--accent);
  border: 1px solid var(--accent-dim);
}

.badge-custom {
  color: var(--text-muted);
  border: 1px solid var(--border);
}

.skill-item-id {
  font-size: 11px;
  color: var(--text-muted);
}

.skill-item-desc {
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-secondary);
}

.skill-item-tools {
  margin-top: 4px;
  font-size: 11px;
  color: var(--text-muted);
}

.skill-item-actions {
  display: flex;
  gap: var(--spacing-xs);
}

.btn-toggle, .btn-edit, .btn-delete {
  padding: 3px 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: transparent;
  font-size: 12px;
  cursor: pointer;
}

.btn-toggle.on {
  color: var(--accent);
  border-color: var(--accent-dim);
}

.btn-delete {
  color: #ff6b6b;
}

.skill-form-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
}

.skill-form {
  width: 480px;
  max-height: 80vh;
  overflow-y: auto;
  padding: var(--spacing-lg);
  background: var(--bg-sidebar);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
}

.skill-form-title {
  font-family: var(--font-display);
  font-size: 16px;
  color: var(--text-primary);
  margin-bottom: var(--spacing-md);
}

.form-error {
  padding: var(--spacing-xs) var(--spacing-sm);
  margin-bottom: var(--spacing-sm);
  border: 1px solid #ff6b6b;
  border-radius: var(--radius-sm);
  color: #ff6b6b;
  font-size: 12px;
}

.form-warning {
  padding: var(--spacing-xs) var(--spacing-sm);
  margin-bottom: var(--spacing-sm);
  border: 1px solid #ffd93d;
  border-radius: var(--radius-sm);
  color: #ffd93d;
  font-size: 12px;
}

.form-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: var(--spacing-sm);
}

.form-field span {
  font-size: 12px;
  color: var(--text-secondary);
}

.form-field input, .form-field textarea, .form-field select {
  padding: 6px 8px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-primary);
  color: var(--text-primary);
  font-family: var(--font-body);
  font-size: 13px;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--spacing-sm);
  margin-top: var(--spacing-md);
}

/* CR-001: 脚本编辑器 */
.btn-add-script, .btn-add-param {
  margin-left: auto;
  padding: 2px 10px;
  border: 1px dashed var(--accent-dim);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--accent);
  font-size: 12px;
  cursor: pointer;
}

.script-editor {
  margin-bottom: var(--spacing-sm);
  padding: var(--spacing-sm);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-primary);
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.script-editor-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.script-editor-title {
  font-size: 12px;
  color: var(--accent);
  font-family: var(--font-display);
}

.btn-script-remove {
  padding: 1px 8px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: transparent;
  color: #ff6b6b;
  font-size: 11px;
  cursor: pointer;
}

.script-row {
  display: flex;
  gap: var(--spacing-xs);
}

.param-editor {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: var(--spacing-xs);
  border: 1px dashed var(--border);
  border-radius: var(--radius-sm);
}

.param-label {
  font-size: 11px;
  color: var(--text-muted);
}

.param-row {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
}

.param-row input, .param-row select {
  flex: 1;
  padding: 4px 6px !important;
  font-size: 12px !important;
}

.param-required {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: 11px;
  color: var(--text-muted);
  white-space: nowrap;
}

.btn-param-remove {
  border: none;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  font-size: 13px;
}

.btn-cancel, .btn-save {
  padding: 6px 16px;
  border-radius: var(--radius-sm);
  font-family: var(--font-display);
  font-size: 13px;
  cursor: pointer;
}

.btn-cancel {
  border: 1px solid var(--border);
  background: transparent;
  color: var(--text-muted);
}

.btn-save {
  border: 1px solid var(--accent);
  background: var(--accent);
  color: #0a0e1a;
}
</style>
