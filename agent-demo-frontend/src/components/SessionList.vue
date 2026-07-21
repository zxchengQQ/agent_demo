<script setup lang="ts">
import { ref, computed } from 'vue';
import { useSessionStore } from '@/stores/session';

const store = useSessionStore();

// 重命名状态
const renamingId = ref<string | null>(null);
const renameValue = ref('');

// 确认对话框状态
const confirmAction = ref<{ type: 'delete' | 'clearAll'; sessionId?: string } | null>(null);

/** 当前会话列表（按 updatedAt 倒序，AC-018） */
const sessions = computed(() => store.sessions);

/** 开始重命名 */
function startRename(sessionId: string, currentTitle: string) {
  renamingId.value = sessionId;
  renameValue.value = currentTitle;
}

/** 确认重命名（限 50 字符，AC-007） */
function confirmRename() {
  if (renamingId.value) {
    store.renameSession(renamingId.value, renameValue.value.slice(0, 50));
    renamingId.value = null;
  }
}

/** 确认删除（AC-008） */
function confirmDelete() {
  if (confirmAction.value?.sessionId) {
    store.deleteSession(confirmAction.value.sessionId);
  }
  confirmAction.value = null;
}

/** 确认清空全部（AC-009） */
function confirmClearAll() {
  store.clearAll();
  confirmAction.value = null;
}
</script>

<template>
  <div class="session-list">
    <!-- 顶部：新建会话按钮 -->
    <div class="header">
      <button class="btn-new" @click="store.createNewSession()">
        + 新建会话
      </button>
    </div>

    <!-- 会话列表 -->
    <div class="list">
      <div
        v-for="session in sessions"
        :key="session.sessionId"
        class="session-item"
        :class="{ active: session.sessionId === store.currentSessionId }"
        @click="store.switchTo(session.sessionId)"
      >
        <!-- 标题/重命名输入框 -->
        <input
          v-if="renamingId === session.sessionId"
          v-model="renameValue"
          class="rename-input"
          maxlength="50"
          @keyup.enter="confirmRename"
          @blur="confirmRename"
        />
        <span v-else class="session-title" :title="session.title">
          {{ session.title }}
        </span>

        <!-- 操作按钮 -->
        <div v-if="renamingId !== session.sessionId" class="actions">
          <button class="icon-btn" title="重命名" @click.stop="startRename(session.sessionId, session.title)">
            ✎
          </button>
          <button class="icon-btn danger" title="删除" @click.stop="confirmAction = { type: 'delete', sessionId: session.sessionId }">
            ✕
          </button>
        </div>
      </div>

      <!-- 空状态 -->
      <div v-if="sessions.length === 0" class="empty">
        暂无会话
      </div>
    </div>

    <!-- 底部：清空全部 -->
    <div class="footer">
      <button class="btn-clear" @click="confirmAction = { type: 'clearAll' }">
        清空全部
      </button>
    </div>

    <!-- 确认对话框 -->
    <div v-if="confirmAction" class="confirm-overlay" @click="confirmAction = null">
      <div class="confirm-dialog" @click.stop>
        <p class="confirm-text">
          {{ confirmAction.type === 'clearAll' ? '确定清空所有会话纪录？此操作不可撤销。' : '确定删除该会话？' }}
        </p>
        <div class="confirm-actions">
          <button class="btn-cancel" @click="confirmAction = null">取消</button>
          <button
            class="btn-confirm"
            @click="confirmAction.type === 'clearAll' ? confirmClearAll() : confirmDelete()"
          >
            确定
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.session-list {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--bg-sidebar);
}

.header {
  padding: var(--spacing-md);
}

.btn-new {
  width: 100%;
  padding: var(--spacing-sm) var(--spacing-md);
  background: var(--accent-dim);
  color: var(--accent);
  border: 1px dashed var(--accent);
  border-radius: var(--radius-md);
  font-family: var(--font-display);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-new:hover {
  background: var(--accent);
  color: var(--bg-primary);
}

.list {
  flex: 1;
  overflow-y: auto;
  padding: 0 var(--spacing-sm);
}

.session-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--spacing-sm) var(--spacing-md);
  margin-bottom: var(--spacing-xs);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: background 0.15s;
  position: relative;
}

.session-item:hover {
  background: var(--bg-hover);
}

.session-item.active {
  background: var(--bg-active);
  border-left: 2px solid var(--accent);
}

.session-title {
  flex: 1;
  font-size: 13px;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rename-input {
  flex: 1;
  background: var(--bg-input);
  border: 1px solid var(--accent);
  border-radius: var(--radius-sm);
  padding: var(--spacing-xs) var(--spacing-sm);
  color: var(--text-primary);
  font-size: 13px;
  outline: none;
}

.actions {
  display: none;
  gap: var(--spacing-xs);
}

.session-item:hover .actions {
  display: flex;
}

.icon-btn {
  background: none;
  border: none;
  color: var(--text-muted);
  cursor: pointer;
  padding: 2px 6px;
  font-size: 14px;
  border-radius: var(--radius-sm);
}

.icon-btn:hover {
  background: var(--bg-input);
  color: var(--text-primary);
}

.icon-btn.danger:hover {
  color: var(--danger);
}

.empty {
  text-align: center;
  color: var(--text-muted);
  font-size: 13px;
  padding: var(--spacing-xl) 0;
}

.footer {
  padding: var(--spacing-md);
  border-top: 1px solid var(--border);
}

.btn-clear {
  width: 100%;
  padding: var(--spacing-sm);
  background: transparent;
  color: var(--text-muted);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-clear:hover {
  color: var(--danger);
  border-color: var(--danger);
}

/* 确认对话框 */
.confirm-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
}

.confirm-dialog {
  background: var(--bg-sidebar);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: var(--spacing-lg);
  width: 320px;
}

.confirm-text {
  font-size: 14px;
  color: var(--text-primary);
  margin-bottom: var(--spacing-md);
}

.confirm-actions {
  display: flex;
  gap: var(--spacing-sm);
  justify-content: flex-end;
}

.btn-cancel,
.btn-confirm {
  padding: var(--spacing-sm) var(--spacing-md);
  border-radius: var(--radius-sm);
  font-size: 13px;
  cursor: pointer;
  border: none;
}

.btn-cancel {
  background: var(--bg-input);
  color: var(--text-secondary);
}

.btn-confirm {
  background: var(--danger);
  color: white;
}
</style>
