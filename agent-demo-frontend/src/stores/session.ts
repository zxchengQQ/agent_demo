import { defineStore } from 'pinia';
import type { SessionRecord, Message, ReactStep, SubTaskStatus, SubTaskReactStep, TokenUsage, KnowledgeSource } from '@/types';
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
    /**
     * 知识库选择器状态（按会话隔离，AC-015/AC-037）
     * 业务含义：key 为 sessionId，value 为用户选中的知识库名称列表。
     * 空数组或无记录表示"自动"模式（Agent 自主检索）。
     * 不持久化到 localStorage（与深度思考开关行为一致，刷新后重置）。
     */
    knowledgeBasesBySession: {} as Record<string, string[]>,
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
     * 追加推理内容到指定消息（CR-001 新增，AC-024 推理过程持久化）
     * 业务含义：开启深度思考时，模型推理片段逐字追加到 reasoning 字段，
     * 随消息持久化到 localStorage，历史回看可见。
     * reasoning 为可选字段，旧数据可能为 undefined，首次追加时初始化为空字符串。
     */
    appendReasoning(messageId: string, reasoning: string) {
      for (const session of this.sessions) {
        const msg = session.messages.find((m) => m.id === messageId);
        if (msg) {
          if (msg.reasoning === undefined) {
            msg.reasoning = '';
          }
          msg.reasoning += reasoning;
          storage.saveSessions(this.sessions);
          return;
        }
      }
    },

    /**
     * 获取或创建指定 iteration 的 ReactStep（内部辅助方法）
     * 业务含义：ReAct 模式下按 iteration 分组管理 Thought/Action/Observation，
     * 首次访问时初始化 reactSteps 数组和对应 step。
     */
    _getOrCreateStep(messageId: string, iteration: number): ReactStep | undefined {
      for (const session of this.sessions) {
        const msg = session.messages.find((m) => m.id === messageId);
        if (msg) {
          if (msg.reactSteps === undefined) {
            msg.reactSteps = [];
          }
          let step = msg.reactSteps.find((s) => s.iteration === iteration);
          if (!step) {
            step = { iteration, thought: '', toolCalls: [] };
            msg.reactSteps.push(step);
          }
          return step;
        }
      }
      return undefined;
    },

    /**
     * 追加 ReAct 思考内容到指定消息
     * 业务含义：收到 thought 事件时，将思考文本追加到对应 iteration 的 reactStep。
     */
    appendThought(messageId: string, thought: string, iteration: number) {
      const step = this._getOrCreateStep(messageId, iteration);
      if (step) {
        step.thought += thought;
        storage.saveSessions(this.sessions);
      }
    },

    /**
     * 追加 ReAct 工具调用信息到指定消息
     * 业务含义：收到 action 事件时，在对应 iteration 的 reactStep 中新增一条工具调用记录。
     */
    appendAction(messageId: string, toolName: string, args: string, iteration: number) {
      const step = this._getOrCreateStep(messageId, iteration);
      if (step) {
        step.toolCalls.push({ toolName, arguments: args, result: '' });
        storage.saveSessions(this.sessions);
      }
    },

    /**
     * 追加 ReAct 工具结果到指定消息
     * 业务含义：收到 observation 事件时，将结果填入对应 iteration 最后一条工具调用记录。
     */
    appendObservation(messageId: string, result: string, iteration: number) {
      for (const session of this.sessions) {
        const msg = session.messages.find((m) => m.id === messageId);
        if (msg && msg.reactSteps) {
          const step = msg.reactSteps.find((s) => s.iteration === iteration);
          if (step && step.toolCalls.length > 0) {
            // 将结果填入最后一条工具调用（ReAct 模式下 action 和 observation 一一对应）
            step.toolCalls[step.toolCalls.length - 1].result = result;
            storage.saveSessions(this.sessions);
          }
          return;
        }
      }
    },

    /**
     * 将指定 iteration 的 thought 移动到 message.content，并清空该轮 thought
     * 业务含义：收到 final-answer 事件时，最终答案对应的 thought 即为正式回复，
     * 将其移入 content 展示，同时清空 reactStep 中的 thought 避免重复展示。
     */
    moveThoughtToContent(messageId: string, iteration: number) {
      for (const session of this.sessions) {
        const msg = session.messages.find((m) => m.id === messageId);
        if (msg && msg.reactSteps) {
          const step = msg.reactSteps.find((s) => s.iteration === iteration);
          if (step) {
            msg.content += step.thought;
            step.thought = '';
            storage.saveSessions(this.sessions);
          }
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
     * 追加知识库来源信息到指定消息（CR-002 新增，AC-043）
     * 业务含义：observation/task_observation 事件中解析到来源信息时累积写入消息。
     * 多次事件来源信息累积（不覆盖），同一知识库+文件名组合去重。
     */
    addKnowledgeSources(messageId: string, sources: KnowledgeSource[]) {
      for (const session of this.sessions) {
        const msg = session.messages.find((m) => m.id === messageId);
        if (msg) {
          if (!msg.knowledgeSources) {
            msg.knowledgeSources = [];
          }
          for (const source of sources) {
            // 去重：同一知识库名+文件名不重复添加
            const exists = msg.knowledgeSources.some(
              (s) => s.knowledgeBaseName === source.knowledgeBaseName && s.fileName === source.fileName,
            );
            if (!exists) {
              msg.knowledgeSources.push(source);
            }
          }
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

    // ===== CR-002 新增：子任务状态管理（AC-003, AC-005, AC-014, AC-016）=====

    /**
     * 初始化子任务列表（task_plan 事件触发，AC-001/AC-016）
     * 业务含义：Agent 将复杂任务拆解为子任务列表，前端初始化全部为 pending 状态。
     */
    initSubTasks(messageId: string, tasks: { index: number; title: string }[]) {
      for (const session of this.sessions) {
        const msg = session.messages.find((m) => m.id === messageId);
        if (msg) {
          msg.subTasks = tasks.map((t) => ({
            index: t.index,
            title: t.title,
            status: 'pending' as SubTaskStatus,
          }));
          storage.saveSessions(this.sessions);
          return;
        }
      }
    },

    /**
     * 更新子任务状态（task_start/complete/failed/cancelled 事件触发，AC-003/AC-006/AC-007）
     * 业务含义：子任务状态流转，failed 时附带错误原因。
     */
    updateSubTaskStatus(messageId: string, index: number, status: SubTaskStatus, error?: string) {
      for (const session of this.sessions) {
        const msg = session.messages.find((m) => m.id === messageId);
        if (msg && msg.subTasks) {
          const subTask = msg.subTasks.find((st) => st.index === index);
          if (subTask) {
            subTask.status = status;
            if (error !== undefined) {
              subTask.error = error;
            }
            storage.saveSessions(this.sessions);
          }
          return;
        }
      }
    },

    /**
     * 追加子任务执行内容（task_token 事件触发，AC-005）
     * 业务含义：子任务执行中内容片段逐字追加，类似 appendContent 但定位到子任务。
     */
    appendSubTaskContent(messageId: string, index: number, content: string) {
      for (const session of this.sessions) {
        const msg = session.messages.find((m) => m.id === messageId);
        if (msg && msg.subTasks) {
          const subTask = msg.subTasks.find((st) => st.index === index);
          if (subTask) {
            if (subTask.content === undefined) {
              subTask.content = '';
            }
            subTask.content += content;
            storage.saveSessions(this.sessions);
          }
          return;
        }
      }
    },

    /**
     * 追加子任务推理内容（task_reasoning 事件触发，AC-011）
     * 业务含义：子任务推理片段逐字追加，类似 appendReasoning 但定位到子任务。
     */
    appendSubTaskReasoning(messageId: string, index: number, content: string) {
      for (const session of this.sessions) {
        const msg = session.messages.find((m) => m.id === messageId);
        if (msg && msg.subTasks) {
          const subTask = msg.subTasks.find((st) => st.index === index);
          if (subTask) {
            if (subTask.reasoning === undefined) {
              subTask.reasoning = '';
            }
            subTask.reasoning += content;
            storage.saveSessions(this.sessions);
          }
          return;
        }
      }
    },

    /**
     * 获取或创建子任务的指定 iteration 的 SubTaskReactStep（内部辅助方法）
     * 业务含义：复用现有 _getOrCreateStep 的逻辑模式，但操作 subTask.reactSteps。
     */
    _getOrCreateSubTaskStep(messageId: string, index: number, iteration: number): SubTaskReactStep | undefined {
      for (const session of this.sessions) {
        const msg = session.messages.find((m) => m.id === messageId);
        if (msg && msg.subTasks) {
          const subTask = msg.subTasks.find((st) => st.index === index);
          if (subTask) {
            if (subTask.reactSteps === undefined) {
              subTask.reactSteps = [];
            }
            let step = subTask.reactSteps.find((s) => s.iteration === iteration);
            if (!step) {
              step = { iteration, thought: '', toolCalls: [] };
              subTask.reactSteps.push(step);
            }
            return step;
          }
        }
      }
      return undefined;
    },

    /**
     * 追加子任务 ReAct 思考（task_thought 事件触发，AC-005）
     */
    appendSubTaskThought(messageId: string, index: number, thought: string, iteration: number) {
      const step = this._getOrCreateSubTaskStep(messageId, index, iteration);
      if (step) {
        step.thought += thought;
        storage.saveSessions(this.sessions);
      }
    },

    /**
     * 追加子任务工具调用（task_action 事件触发，AC-005）
     */
    appendSubTaskAction(messageId: string, index: number, toolName: string, args: string, iteration: number) {
      const step = this._getOrCreateSubTaskStep(messageId, index, iteration);
      if (step) {
        step.toolCalls.push({ toolName, arguments: args, result: '' });
        storage.saveSessions(this.sessions);
      }
    },

    /**
     * 追加子任务工具结果（task_observation 事件触发，AC-005）
     * 业务含义：将结果填入对应 iteration 最后一条工具调用记录。
     */
    appendSubTaskObservation(messageId: string, index: number, result: string, iteration: number) {
      for (const session of this.sessions) {
        const msg = session.messages.find((m) => m.id === messageId);
        if (msg && msg.subTasks) {
          const subTask = msg.subTasks.find((st) => st.index === index);
          if (subTask && subTask.reactSteps) {
            const step = subTask.reactSteps.find((s) => s.iteration === iteration);
            if (step && step.toolCalls.length > 0) {
              step.toolCalls[step.toolCalls.length - 1].result = result;
              storage.saveSessions(this.sessions);
            }
            return;
          }
        }
      }
    },

    // ===== Token 消耗统计（Task-18 新增）=====

    /**
     * 累加会话 Token 用量（usage 事件触发）
     * 业务含义：后端在流式结束时推送本轮 Token 用量，前端累加到会话维度并持久化。
     * 旧会话可能无 tokenUsage 字段，首次累加时初始化为 0。
     * estimated 标记：任一轮为估算值则整体标记为估算（保守展示）。
     */
    addTokenUsage(sessionId: string, usage: TokenUsage) {
      const session = this.sessions.find((s) => s.sessionId === sessionId);
      if (!session) return;
      if (!session.tokenUsage) {
        session.tokenUsage = { inputTokens: 0, outputTokens: 0, totalTokens: 0, estimated: false };
      }
      session.tokenUsage.inputTokens += usage.inputTokens;
      session.tokenUsage.outputTokens += usage.outputTokens;
      session.tokenUsage.totalTokens += usage.totalTokens;
      if (usage.estimated) {
        session.tokenUsage.estimated = true;
      }
      storage.saveSessions(this.sessions);
    },

    // ===== 知识库选择器会话级状态（Task-05，AC-015/AC-037）=====

    /**
     * 获取指定会话的知识库选择
     * 业务含义：无记录时返回空数组（自动模式），有记录时返回选中的知识库名称列表。
     */
    getKnowledgeBases(sessionId: string): string[] {
      return this.knowledgeBasesBySession[sessionId] ?? [];
    },

    /**
     * 设置指定会话的知识库选择
     * 业务含义：用户通过知识库选择器切换选择时调用，按会话隔离保存。
     */
    setKnowledgeBases(sessionId: string, bases: string[]) {
      this.knowledgeBasesBySession[sessionId] = bases;
    },
  },
});
