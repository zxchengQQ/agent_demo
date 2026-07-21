/**
 * 前端类型定义
 * 关联 AC：AC-006、AC-010、AC-012、AC-016、AC-019、AC-020
 */

/** 消息角色 */
export type MessageRole = 'user' | 'assistant';

/** 消息状态 */
export type MessageStatus = 'complete' | 'incomplete' | 'error';

/** 单条消息 */
export interface Message {
  /** 消息唯一 ID（前端生成） */
  id: string;
  /** 消息角色 */
  role: MessageRole;
  /** 消息内容 */
  content: string;
  /** 创建时间戳 */
  createdAt: number;
  /** 消息状态（incomplete 用于流式中断标记，AC-012） */
  status: MessageStatus;
}

/** 会话纪录（localStorage 存储单元） */
export interface SessionRecord {
  /** 后端会话 ID（透明续聊时可能变化，AC-010） */
  sessionId: string;
  /** 会话标题（首条消息前 20 字符，AC-006） */
  title: string;
  /** 创建时间戳 */
  createdAt: number;
  /** 最后活跃时间戳（排序依据，AC-018） */
  updatedAt: number;
  /** 消息列表 */
  messages: Message[];
}

/** SSE 事件回调 */
export interface StreamCallbacks {
  /** 收到 session 事件（透明续聊，AC-010） */
  onSession: (sessionId: string) => void;
  /** 收到 token 事件（逐字显示，AC-020） */
  onToken: (token: string) => void;
  /** 收到 done 事件（流式完成） */
  onDone: (duration: number) => void;
  /** 收到 error 事件（错误提示，AC-012/AC-013） */
  onError: (message: string) => void;
}
