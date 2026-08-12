<script setup lang="ts">
import { ref } from 'vue';
import { addServer } from '@/api/mcp';
import { parseMcpServersConfig } from '@/utils/mcp-config';
import type { AddResult } from '@/types';

/**
 * MCP JSON 配置编辑器弹窗（Task-06）
 * 业务含义：允许用户粘贴业界通用的 mcpServers JSON 配置（与 Claude Desktop / Cursor 一致），
 * 自动解析并逐个添加 Server。支持 stdio/sse/http 全部传输方式。
 * 全部成功关闭弹窗；部分/全部失败时展示结果列表，弹窗保持打开（AC-024）。
 */
const props = defineProps<{
  /** 是否显示弹窗 */
  visible: boolean;
}>();

// 业务含义：visible 通过模板 v-if 消费，此处仅保留类型定义；
// 无需在脚本中引用 props 变量（避免 TS6133 未使用告警）。
void props;

const emit = defineEmits<{
  /** 关闭弹窗 */
  close: [];
  /** 全部添加成功 */
  saved: [];
}>();

/** JSON 编辑内容 */
const jsonText = ref('');
/** 提交中状态（防重复提交，AC-028） */
const loading = ref(false);
/** 批量添加结果列表 */
const results = ref<AddResult[] | null>(null);
/** 全局错误信息（JSON 解析错误等） */
const errorMessage = ref('');
/** 是否展开配置格式说明 */
const showFormatHelp = ref(false);

/** 配置格式示例 */
const formatExample = `{
  "mcpServers": {
    "fetch": {
      "command": "npx",
      "args": ["mcp-fetch-server"]
    },
    "mermaid": {
      "url": "https://mcp.mermaid.ai/mcp"
    },
    "myserver": {
      "url": "https://example.com/sse",
      "transport": "sse"
    }
  }
}`;

/** 关闭弹窗（重置状态） */
function close() {
  emit('close');
  reset();
}

/** 重置弹窗内部状态 */
function reset() {
  jsonText.value = '';
  loading.value = false;
  results.value = null;
  errorMessage.value = '';
}

/**
 * 保存配置（批量添加 Server）
 * 业务含义：解析 JSON -> 逐个调用 addServer -> 收集结果。
 * 全部成功时 emit 'saved'（父组件关闭弹窗）；部分/全部失败时展示结果列表（AC-024）。
 */
async function handleSave() {
  if (loading.value) return; // 防重复提交（AC-028）
  loading.value = true;
  results.value = null;
  errorMessage.value = '';

  // 1. 解析 JSON 配置（可能抛出格式错误）
  let requests;
  try {
    requests = parseMcpServersConfig(jsonText.value);
  } catch (e) {
    errorMessage.value = (e as Error).message;
    loading.value = false;
    return;
  }

  // 2. 逐个添加 Server，收集结果
  const addResults: AddResult[] = [];
  for (const req of requests) {
    try {
      await addServer(req);
      addResults.push({ name: req.name, success: true });
    } catch (e) {
      addResults.push({ name: req.name, success: false, error: (e as Error).message });
    }
  }

  // 3. 处理结果
  results.value = addResults;
  loading.value = false;

  // 全部成功 -> 通知父组件关闭
  if (addResults.every((r) => r.success)) {
    emit('saved');
    reset();
  }
}
</script>

<template>
  <div v-if="visible" class="editor-overlay" @click.self="close">
    <div class="editor-dialog">
      <!-- 弹窗头部 -->
      <div class="editor-header">
        <span class="editor-title">添加 MCP 服务</span>
        <button class="editor-close" @click="close">✕</button>
      </div>

      <!-- 配置格式说明（可折叠） -->
      <div class="editor-help">
        <button class="editor-help-toggle" @click="showFormatHelp = !showFormatHelp">
          配置格式说明 {{ showFormatHelp ? '▾' : '▸' }}
        </button>
        <pre v-if="showFormatHelp" class="editor-help-body">{{ formatExample }}</pre>
        <div v-if="showFormatHelp" class="editor-help-notes">
          <p>· <code>command</code> + <code>args</code>：stdio 本地服务（如 npx 启动）</p>
          <p>· <code>url</code>：远程服务，默认使用 Streamable HTTP 传输</p>
          <p>· <code>transport: "sse"</code>：远程服务使用 SSE 传输</p>
          <p>· <code>headers</code> / <code>env</code>：可选的自定义请求头 / 环境变量</p>
          <p>· Windows 下 stdio 命令建议使用 <code>npx.cmd</code> 而非 <code>npx</code></p>
        </div>
      </div>

      <!-- JSON 编辑区 -->
      <textarea
        v-model="jsonText"
        class="editor-textarea"
        placeholder='{"mcpServers": {"myServer": {"url": "https://example.com/mcp"}}}'
        spellcheck="false"
      ></textarea>

      <!-- 全局错误信息 -->
      <div v-if="errorMessage" class="editor-error">{{ errorMessage }}</div>

      <!-- 批量添加结果列表（部分/全部失败时显示，AC-024） -->
      <div v-if="results && results.length > 0" class="editor-results">
        <div
          v-for="r in results"
          :key="r.name"
          class="editor-result-item"
          :class="r.success ? 'result-success' : 'result-fail'"
        >
          <span class="result-icon">{{ r.success ? '✓' : '✗' }}</span>
          <span class="result-name">{{ r.name || '配置' }}</span>
          <span v-if="!r.success && r.error" class="result-error">{{ r.error }}</span>
        </div>
      </div>

      <!-- 操作按钮 -->
      <div class="editor-actions">
        <button class="editor-btn btn-cancel" @click="close">关闭</button>
        <button class="editor-btn btn-save" :disabled="loading" @click="handleSave">
          {{ loading ? '添加中...' : '保存配置' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.editor-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
}

.editor-dialog {
  width: 560px;
  max-width: 90vw;
  max-height: 80vh;
  background: var(--bg-sidebar);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: var(--spacing-lg);
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.editor-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.editor-title {
  font-family: var(--font-display);
  font-size: 16px;
  font-weight: 700;
  color: var(--text-primary);
}

.editor-close {
  border: none;
  background: none;
  color: var(--text-muted);
  font-size: 14px;
  cursor: pointer;
}

.editor-close:hover {
  color: var(--text-primary);
}

.editor-help-toggle {
  border: none;
  background: none;
  color: var(--accent);
  font-family: var(--font-display);
  font-size: 12px;
  cursor: pointer;
  padding: 0;
}

.editor-help-body {
  background: var(--bg-input);
  border-radius: var(--radius-sm);
  padding: var(--spacing-sm);
  font-size: 11px;
  color: var(--text-secondary);
  white-space: pre-wrap;
  word-break: break-all;
}

.editor-help-notes {
  font-size: 11px;
  color: var(--text-muted);
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.editor-help-notes code {
  color: var(--accent);
}

.editor-textarea {
  min-height: 160px;
  font-family: var(--font-mono, monospace);
  font-size: 12px;
  background: var(--bg-input);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  color: var(--text-primary);
  padding: var(--spacing-sm);
  resize: vertical;
  line-height: 1.6;
}

.editor-textarea:focus {
  outline: none;
  border-color: var(--accent);
}

.editor-error {
  color: var(--danger);
  font-size: 12px;
}

.editor-results {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 150px;
  overflow-y: auto;
}

.editor-result-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  font-size: 12px;
  padding: 4px var(--spacing-xs);
  border-radius: var(--radius-sm);
}

.result-success {
  color: var(--success);
  background: rgba(0, 255, 136, 0.08);
}

.result-fail {
  color: var(--danger);
  background: rgba(255, 77, 77, 0.08);
}

.result-name {
  font-weight: 700;
}

.result-error {
  color: var(--text-muted);
}

.editor-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--spacing-sm);
}

.editor-btn {
  padding: var(--spacing-xs) var(--spacing-md);
  border: none;
  border-radius: var(--radius-sm);
  font-family: var(--font-display);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-cancel {
  background: transparent;
  border: 1px solid var(--border);
  color: var(--text-secondary);
}

.btn-save {
  background: var(--accent);
  color: var(--bg-primary);
}

.btn-save:hover:not(:disabled) {
  box-shadow: var(--shadow-glow);
}

.btn-save:disabled {
  background: var(--bg-input);
  color: var(--text-muted);
  cursor: not-allowed;
}
</style>
