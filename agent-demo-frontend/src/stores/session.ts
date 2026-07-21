import { defineStore } from 'pinia';
import type { SessionRecord, Message } from '@/types';
import * as storage from '@/utils/storage';

/**
 * 会话状态管理
 * 关联 AC：AC-001、AC-005、AC-006、AC-007、AC-008、AC-009、AC-010、AC-018、AC-019
 *
 * 业务含义：管理所有会话的当前状态，作为各组件共享的数据源。
 * 所有变更操作同步写入 localStorage（AC-019 持久化）。
 */

/** 生成前端临时 ID（后端会话 ID 在首次对话后由 session 事件回传） */
function generateId(): string {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
}

/** 标题最大长度（AC-006: 前 20 字符） */
const TITLE_MAX = 20;
/** 重命名标题上限（AC-007: 50 字符） */
const RENAME_MAX = 50;

export const useSessionStore = defineStore('session', {
  state: () => ({
    /** 会话列表（按 updatedAt 倒序，AC-018） */
    sessions: [] as SessionRecord[],
    /** 当前激活的会话 ID */
    currentSessionId: '' as string,
  }),

  actions: {
    /**
     * 初始化：从 localStorage 加载（AC-019）
     * 无会话时自动新建空会话（AC-001）
     */
    init() {
      this.sessions = storage.loadSessions();
      if (this.sessions.length === 0) {
        this.createNewSession();
      } else {
        this.currentSessionId = this.sessions[0].sessionId;
      }
    },

    /**
     * 新建空会话，插入列表头部
     */
    createNewSession() {
      const session: SessionRecord = {
        sessionId: generateId(),
        title: '新会话',
        createdAt: Date.now(),
        updatedAt: Date.now(),
        messages: [],
      };
      this.sessions.unshift(session);
      this.currentSessionId = session.sessionId;
      storage.saveSessions(this.sessions);
    },

    /**
     * 切换会话（AC-005）
     */
    switchTo(sessionId: string) {
      this.currentSessionId = sessionId;
    },

    /**
     * 透明续聊：更新 sessionId 关联（AC-010）
     * 业务含义：后端会话超时返回新 sessionId，前端无感切换
     */
    updateSessionId(oldId: string, newId: string) {
      const session = this.sessions.find((s) => s.sessionId === oldId);
      if (session) {
        session.sessionId = newId;
        if (this.currentSessionId === oldId) {
          this.currentSessionId = newId;
        }
        storage.saveSessions(this.sessions);
      }
    },

    /**
     * 生成会话标题：首条消息前 20 字符（AC-006）
     */
    generateTitle(sessionId: string, firstMessage: string) {
      const session = this.sessions.find((s) => s.sessionId === sessionId);
      if (session) {
        session.title =
          firstMessage.length > TITLE_MAX
            ? firstMessage.slice(0, TITLE_MAX) + '...'
            : firstMessage;
        storage.saveSessions(this.sessions);
      }
    },

    /**
     * 重命名会话标题，限 50 字符（AC-007）
     */
    renameSession(sessionId: string, newTitle: string) {
      const session = this.sessions.find((s) => s.sessionId === sessionId);
      if (session) {
        session.title = newTitle.slice(0, RENAME_MAX);
        storage.saveSessions(this.sessions);
      }
    },

    /**
     * 删除指定会话（AC-008）
     */
    deleteSession(sessionId: string) {
      this.sessions = this.sessions.filter((s) => s.sessionId !== sessionId);
      storage.saveSessions(this.sessions);
      // 若删除的是当前会话，切换到第一个或新建
      if (this.currentSessionId === sessionId) {
        if (this.sessions.length > 0) {
          this.currentSessionId = this.sessions[0].sessionId;
        } else {
          this.createNewSession();
        }
      }
    },

    /**
     * 清空所有会话（AC-009）
     * 业务含义：左侧列表清空显示引导，UI 层负责后续新建空会话
     */
    clearAll() {
      this.sessions = [];
      this.currentSessionId = '';
      storage.clearAllSessions();
    },

    /**
     * 添加消息到指定会话
     */
    addMessage(sessionId: string, message: Message): Message {
      const session = this.sessions.find((s) => s.sessionId === sessionId);
      if (session) {
        session.messages.push(message);
        storage.saveSessions(this.sessions);
      }
      return message;
    },

    /**
     * 追加内容到指定消息（AC-020 逐字显示）
     */
    appendContent(messageId: string, content: string) {
      for (const session of this.sessions) {
        const msg = session.messages.find((m) => m.id === messageId);
        if (msg) {
          msg.content += content;
          storage.saveSessions(this.sessions);
          return;
        }
      }
    },

    /**
     * 标记消息完成
     */
    markComplete(messageId: string) {
      for (const session of this.sessions) {
        const msg = session.messages.find((m) => m.id === messageId);
        if (msg) {
          msg.status = 'complete';
          storage.saveSessions(this.sessions);
          return;
        }
      }
    },

    /**
     * 标记消息错误
     */
    markError(messageId: string, errorMsg: string) {
      for (const session of this.sessions) {
        const msg = session.messages.find((m) => m.id === messageId);
        if (msg) {
          msg.status = 'error';
          msg.content = msg.content + '\n\n[错误] ' + errorMsg;
          storage.saveSessions(this.sessions);
          return;
        }
      }
    },

    /**
     * 更新会话最后活跃时间（AC-018 排序依据）
     */
    touchSession(sessionId: string) {
      const session = this.sessions.find((s) => s.sessionId === sessionId);
      if (session) {
        session.updatedAt = Date.now();
        this.sessions.sort((a, b) => b.updatedAt - a.updatedAt);
        storage.saveSessions(this.sessions);
      }
    },
  },
});
