/**
 * 前端类型定义
 * 关联 AC：AC-006、AC-010、AC-012、AC-016、AC-019、AC-020
 */

/** 消息角色 */
export type MessageRole = 'user' | 'assistant';

/** 消息状态 */
export type MessageStatus = 'complete' | 'incomplete' | 'error';

/**
 * 工具调用信息（ReAct 推理模式）
 * 业务含义：记录 Agent 在某一轮迭代中调用的工具名称、入参和返回结果。
 */
export interface ToolCallInfo {
  /** 工具名称 */
  toolName: string;
  /** 工具入参（JSON 字符串） */
  arguments: string;
  /** 工具返回结果 */
  result: string;
}

/**
 * ReAct 推理单步记录
 * 业务含义：ReAct 模式下每一轮迭代包含一个 Thought（推理）和若干工具调用。
 */
export interface ReactStep {
  /** 迭代轮次（从 1 开始） */
  iteration: number;
  /** 思考内容 */
  thought: string;
  /** 本轮工具调用列表 */
  toolCalls: ToolCallInfo[];
}

/**
 * 子任务执行状态（CR-002 新增，AC-016）
 * 业务含义：标记每个子任务在拆解执行流程中的当前状态。
 */
export type SubTaskStatus = 'pending' | 'in-progress' | 'completed' | 'failed' | 'cancelled';

/**
 * 子任务 ReAct 单步记录（CR-002 新增，AC-016）
 * 业务含义：与 ReactStep 结构一致，但归属于子任务而非顶层消息。
 */
export interface SubTaskReactStep {
  /** 迭代轮次（从 1 开始） */
  iteration: number;
  /** 思考内容 */
  thought: string;
  /** 本轮工具调用列表 */
  toolCalls: ToolCallInfo[];
}

/**
 * 单个子任务（CR-002 新增，AC-016）
 * 业务含义：任务拆解规划阶段产出的单个子任务及其执行状态和详情。
 */
export interface SubTask {
  /** 序号（1-based） */
  index: number;
  /** 标题描述 */
  title: string;
  /** 执行状态 */
  status: SubTaskStatus;
  /** 执行内容（从 task_token 累积） */
  content?: string;
  /** 推理内容（enableThinking=true 时） */
  reasoning?: string;
  /** ReAct 步骤列表 */
  reactSteps?: SubTaskReactStep[];
  /** 失败原因（status=failed 时） */
  error?: string;
}

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
  /**
   * 推理内容（CR-001 新增，AC-022/AC-024）
   * 业务含义：开启深度思考时，模型推理过程随消息持久化到 localStorage，历史回看可见。
   * 可选字段，向前兼容旧数据（未开启思考的旧消息 reasoning 为 undefined）。
   */
  reasoning?: string;
  /**
   * ReAct 推理步骤列表
   * 业务含义：ReAct 模式下记录每轮迭代的 Thought/Action/Observation，流式展示推理过程。
   * 可选字段，向前兼容旧数据；不持久化到 localStorage（仅当前会话实时展示）。
   */
  reactSteps?: ReactStep[];
  /**
   * 任务拆解子任务列表（CR-002 新增，AC-014/AC-016）
   * 业务含义：开启任务拆解模式后，Agent 将复杂任务拆解为子任务列表，
   * 随消息完整持久化到 localStorage（含各子任务状态和详情），刷新页面可回看。
   * 可选字段，向前兼容旧数据（未开启拆解的旧消息 subTasks 为 undefined）。
   */
  subTasks?: SubTask[];
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
  /**
   * 收到 reasoning 事件（CR-001 新增，AC-022）
   * 业务含义：开启深度思考时，模型推理片段逐字追加到推理区块。
   * 可选回调，向前兼容（未注册时 handleSseEvent 用可选链跳过，不报错）。
   */
  onReasoning?: (reasoning: string) => void;
  /**
   * 收到 thought 事件（ReAct 推理模式）
   * 业务含义：ReAct 模式下某一轮迭代的思考内容，追加到对应 iteration 的 reactStep。
   */
  onThought?: (thought: string, iteration: number) => void;
  /**
   * 收到 action 事件（ReAct 推理模式）
   * 业务含义：ReAct 模式下某一轮迭代触发的工具调用，记录工具名和入参。
   * 注：参数名用 args 而非 arguments（arguments 是严格模式保留字）。
   */
  onAction?: (toolName: string, args: string, iteration: number) => void;
  /**
   * 收到 observation 事件（ReAct 推理模式）
   * 业务含义：ReAct 模式下某一轮迭代工具调用的返回结果。
   */
  onObservation?: (result: string, iteration: number) => void;
  /**
   * 收到 final-answer 事件（ReAct 推理模式）
   * 业务含义：ReAct 模式下某一轮迭代得出最终答案，将 thought 移入正式回复。
   */
  onFinalAnswer?: (iteration: number) => void;
  /** 收到 done 事件（流式完成） */
  onDone: (duration: number) => void;
  /** 收到 error 事件（错误提示，AC-012/AC-013） */
  onError: (message: string) => void;

  // ===== CR-002 新增：任务拆解回调（均为可选，向前兼容）=====

  /**
   * 收到 task_plan 事件（任务拆解规划完成，AC-001）
   * 业务含义：Agent 将复杂任务拆解为子任务列表，前端初始化任务列表展示。
   */
  onTaskPlan?: (tasks: { index: number; title: string }[]) => void;
  /** 收到 task_start 事件（子任务开始执行，AC-003） */
  onTaskStart?: (index: number, title: string) => void;
  /** 收到 task_token 事件（子任务执行内容片段，AC-005） */
  onTaskToken?: (index: number, content: string) => void;
  /** 收到 task_reasoning 事件（子任务推理片段，AC-011） */
  onTaskReasoning?: (index: number, content: string) => void;
  /** 收到 task_thought 事件（子任务 ReAct 思考，AC-005） */
  onTaskThought?: (index: number, content: string, iteration: number) => void;
  /**
   * 收到 task_action 事件（子任务工具调用，AC-005）
   * 注：参数名用 args 而非 arguments（arguments 是 JS 保留字）。
   */
  onTaskAction?: (index: number, toolName: string, args: string, iteration: number) => void;
  /** 收到 task_observation 事件（子任务工具结果，AC-005） */
  onTaskObservation?: (index: number, result: string, iteration: number) => void;
  /** 收到 task_complete 事件（子任务执行完成，AC-003） */
  onTaskComplete?: (index: number) => void;
  /** 收到 task_failed 事件（子任务执行失败，AC-006） */
  onTaskFailed?: (index: number, error: string) => void;
  /** 收到 task_cancelled 事件（子任务被取消，AC-006/AC-007） */
  onTaskCancelled?: (index: number) => void;
}
