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
 * 知识库来源信息（CR-002 新增，AC-043）
 * 业务含义：对话中使用知识库检索时，记录来源的知识库名和文件名，
 * 用于助手消息底部"引用来源"条展示。
 */
export interface KnowledgeSource {
  /** 知识库名称 */
  knowledgeBaseName: string;
  /** 文档文件名 */
  fileName: string;
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

/**
 * HITL 人机交互数据（Task-07 新增）
 * 业务含义：后端通过 ask_user / tool_confirm SSE 事件向用户发起交互请求，前端据 kind 渲染不同 UI。
 * kind 缺失（存量数据）默认按 askUser 形态渲染（兼容旧会话，技术方案 §11）。
 */
export interface AskUserData {
  /** 交互类型：text=文本输入，confirm=选项确认（permission 形态复用 confirm 的按钮语义） */
  type: 'text' | 'confirm';
  /**
   * 交互形态：askUser=LLM 主动提问；permission=工具权限确认（AC-H01）。
   * 无该字段的存量数据默认按 askUser 形态渲染（兼容性保障）。
   */
  kind?: 'askUser' | 'permission';
  /** 向用户展示的问题文本 */
  question: string;
  /** confirm 类型的可选项列表（text 类型无此字段） */
  options?: string[];
  /** 重试次数（0=首次提问，>0=用户回答不合规后重新提问） */
  retryCount: number;
  /**
   * 用户回答（unified-chat-mode 决策 7：卡片回答后锁定保留 + 持久化）
   * 业务含义：用户通过卡片选项/内嵌输入框/主输入框回复后写入，
   * 存在时卡片进入回答锁定态（可回看），isWaitingForUserInput 返回 false。
   */
  answer?: string;
  // ===== permission 形态新增字段（工具权限确认，Task-17）=====

  /** 工具名称（kind=permission 时，对应后端 tool_confirm 的 toolName） */
  toolName?: string;
  /** 工具用途描述（kind=permission 时，对应后端 tool_confirm 的 toolDescription） */
  toolDescription?: string;
  /** 参数摘要（kind=permission 时，对应后端 tool_confirm 的 arguments，JSON 字符串） */
  toolArguments?: string;
  /** 用户决策结果（kind=permission 时；true=已批准，false=已拒绝，undefined=待决策） */
  approved?: boolean;
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
  /**
   * 知识库来源信息（CR-002 新增，AC-043）
   * 业务含义：对话中使用知识库检索时，记录来源知识库名和文件名，
   * 随消息持久化到 localStorage，助手消息底部展示"引用来源"条。
   * 可选字段，向前兼容旧数据（未使用知识库的旧消息 knowledgeSources 为 undefined）。
   */
  knowledgeSources?: KnowledgeSource[];
  /**
   * HITL 人机交互数据（Task-07 新增）
   * 业务含义：后端 ask_user 事件触发时写入，前端据 type 渲染文本输入或选项确认卡片。
   * 不持久化到 localStorage（仅当前会话实时展示），刷新后清除。
   * 用户回复后由 clearAskUser 清除并标记消息 complete。
   */
  askUserData?: AskUserData;
  /**
   * HITL 交互历史记录数组（CR-003 新增，AC-N06）
   * 业务含义：同一助手气泡内可连续发生多次 HITL 交互（多次工具审批/多次 askUser 追问），
   * 每次交互（工具/问题/参数 + 决策 approved/answer）独立追加于此数组，互不覆盖、可回看。
   * askUserData 为最新一条的镜像（不破坏 isWaitingForUserInput / ChatWindow 恢复检测 / 兜底渲染）。
   * 随消息持久化到 localStorage。可选字段，向前兼容旧数据（仅有 askUserData 的旧消息无此字段）。
   */
  askUserHistory?: AskUserData[];
  /**
   * 技能激活列表（agent-skill Task-22 新增）
   * 业务含义：后端 skill_activated 事件触发时写入，前端据此展示激活徽标（技能名+来源）。
   * 不持久化到 localStorage（仅当前会话实时展示），刷新后清除。
   */
  activatedSkills?: SkillActivatedEvent[];
}

/**
 * Token 消耗数据（Task-17 新增）
 * 业务含义：记录单次对话的 Token 用量，支持估算标记（后端无法精确计量时标记 estimated=true）。
 */
export interface TokenUsage {
  /** 输入 Token 数 */
  inputTokens: number;
  /** 输出 Token 数 */
  outputTokens: number;
  /** 总 Token 数 */
  totalTokens: number;
  /** 是否为估算值（后端无法精确计量时为 true） */
  estimated: boolean;
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
  /**
   * 会话累计 Token 用量（Task-17 新增）
   * 业务含义：随每次对话 usage 事件累加，持久化到 localStorage，刷新页面可回看。
   * 可选字段，向前兼容旧数据（旧会话 tokenUsage 为 undefined）。
   */
  tokenUsage?: TokenUsage;
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
  /**
   * 收到 usage 事件（Token 消耗统计，Task-17 新增）
   * 业务含义：后端在流式结束时推送本轮对话的 Token 用量，前端累加到会话维度展示。
   * 可选回调，向前兼容（未注册时 handleSseEvent 用可选链跳过，不报错）。
   */
  onUsage?: (usage: TokenUsage) => void;
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

  // ===== CR-002 新增：知识库来源回调 =====

  /**
   * 从 observation/task_observation 事件中解析到知识库来源信息（AC-043）
   * 业务含义：SSE observation 事件中包含检索结果的来源前缀（来源: {知识库名}/{文件名}），
   * 前端正则提取后通过此回调通知调用方，累积写入当前助手消息的 knowledgeSources 字段。
   * 可选回调，向前兼容（未注册时 handleSseEvent 用可选链跳过，不报错）。
   */
  onSources?: (sources: KnowledgeSource[]) => void;

  // ===== Task-07 新增：HITL 人机交互回调 =====

  /**
   * 收到 ask_user 事件（HITL 人机交互请求）
   * 业务含义：Agent 在执行过程中需要用户输入或确认时，通过 ask_user 事件向用户发起提问。
   * 前端据 data.type 渲染文本输入框或选项确认卡片，用户回复后作为新消息发送。
   * 可选回调，向前兼容（未注册时 handleSseEvent 用可选链跳过，不报错）。
   */
  onAskUser?: (data: AskUserData) => void;

  // ===== 工具权限确认新增：tool_confirm 回调 =====

  /**
   * 收到 tool_confirm 事件（ask 级工具权限确认请求）
   * 业务含义：Agent 调用 ask 级工具时后端推送确认卡片所需四要素（工具名/描述/参数摘要），
   * 前端渲染确认卡片，用户批准/拒绝后以 toolApproved 参数重新发起流式请求恢复执行。
   * 事件后流保持打开（pending 挂起），不等 done。
   * 可选回调，向前兼容（未注册时 handleSseEvent 用可选链跳过，不报错）。
   */
  onToolConfirm?: (data: ToolConfirmData) => void;

  // ===== 技能激活新增：skill_activated 回调 =====

  /**
   * 收到 skill_activated 事件（技能激活成功，agent-skill）
   * 业务含义：Agent 自主激活（loadSkill）或用户手动指定技能时，后端推送激活信息
   * （技能 id/名称/来源/绑定工具），前端据此渲染激活徽标与排除入口（AC-S04）。
   * 可选回调，向前兼容（未注册时 handleSseEvent 用可选链跳过，不报错）。
   */
  onSkillActivated?: (data: SkillActivatedEvent) => void;
}

// ===== RAG 知识库类型定义（Task-01，关联 AC-003/AC-005/AC-009）=====

/**
 * 文档处理状态
 * 业务含义：文档异步处理的状态流转：待处理 -> 处理中 -> 已完成/失败
 */
export type DocumentStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';

/**
 * 知识库信息
 * 业务含义：对应后端 KnowledgeBaseResponse，知识库列表展示数据。
 */
export interface KnowledgeBase {
  /** 知识库 ID */
  id: string;
  /** 知识库名称（1-50 字符，全局唯一） */
  name: string;
  /** 知识库描述（最长 200 字符） */
  description: string;
  /** 文档数量 */
  documentCount: number;
  /** 创建时间（ISO 字符串） */
  createTime: string;
}

/**
 * 文档信息
 * 业务含义：对应后端 DocumentResponse，文档列表展示数据。
 */
export interface DocumentInfo {
  /** 文档 ID */
  documentId: string;
  /** 文件名 */
  fileName: string;
  /** 文件大小（字节） */
  fileSize: number;
  /** 文档格式（txt/md/pdf） */
  format: string;
  /** 处理状态 */
  status: DocumentStatus;
  /** 分块数量 */
  chunkCount: number;
  /** 失败原因（FAILED 时填充，否则 null） */
  failReason: string | null;
  /** 上传时间（ISO 字符串） */
  uploadTime: string;
}

/**
 * 文档状态查询响应
 * 业务含义：对应后端 DocumentStatusResponse，供前端轮询文档处理进度。
 */
export interface DocumentStatusResponse {
  /** 文档 ID */
  documentId: string;
  /** 处理状态 */
  status: DocumentStatus;
  /** 分块数量 */
  chunkCount: number;
  /** 失败原因（FAILED 时填充，否则 null） */
  failReason: string | null;
}

/**
 * 文档分块信息（CR-001 新增，AC-038）
 * 业务含义：对应后端 DocumentChunkResponse，文档分块详情展示数据。
 */
export interface DocumentChunk {
  /** 分块索引（从 0 开始，按原文档顺序） */
  chunkIndex: number;
  /** 分块文本内容 */
  content: string;
  /** 分块字符数 */
  charCount: number;
}

// ========== LLM 厂商模型配置相关类型 ==========

/** 厂商配置（前端展示用，API Key 脱敏） */
export interface LlmVendor {
  id: string
  name: string
  type: 'predefined' | 'custom'
  baseUrl: string
  /** 脱敏后的 API Key（如 sk-****7890） */
  apiKeyMasked: string
  /** 是否已配置 API Key */
  apiKeyConfigured: boolean
  thinkingTrigger: 'enabled' | 'none'
  timeout: number
  maxRetries: number
  temperature: number
  models: LlmModel[]
}

/** 模型配置 */
export interface LlmModel {
  id: string
  vendorId: string
  vendorName: string
  /** API 模型名称（如 doubao-seed-2.0-pro） */
  modelName: string
  /** 显示名称（如"豆包Seed 2.0 Pro"） */
  displayName: string
  type: 'chat' | 'embedding' | 'rerank' | 'multimodal'
  /** 是否支持视图理解（仅 chat 类型有意义） */
  supportsVision: boolean
}

/** 预定义厂商（供用户选择） */
export interface PredefinedVendor {
  code: string
  name: string
  baseUrl: string
  thinkingTrigger: 'enabled' | 'none'
  models: PredefinedModel[]
}

/** 预定义模型 */
export interface PredefinedModel {
  modelName: string
  displayName: string
  type: 'chat' | 'embedding' | 'rerank' | 'multimodal'
  supportsVision: boolean
}

/** 测试连接结果 */
export interface TestConnectionResult {
  success: boolean
  message: string
  latency: number
}

/** 配置状态 */
export interface ConfigStatus {
  hasConfig: boolean
  hasChatModel: boolean
  hasEmbeddingModel: boolean
  vendorCount: number
  chatModelCount: number
}

/** 添加/编辑厂商请求体 */
export interface VendorRequest {
  name: string
  type: 'predefined' | 'custom'
  baseUrl: string
  apiKey: string
  thinkingTrigger: 'enabled' | 'none'
  timeout: number
  maxRetries: number
  temperature: number
  models: {
    modelName: string
    displayName: string
    type: 'chat' | 'embedding' | 'rerank' | 'multimodal'
    supportsVision: boolean
  }[]
}

/** 同步配置请求体 */
export interface SyncConfigRequest {
  vendors: VendorRequest[]
}

// ========== MCP 服务管理相关类型 ==========

/** MCP 传输方式（对应后端 McpTransportType 枚举） */
export type McpTransportType = 'STDIO' | 'SSE' | 'HTTP'

/** MCP Server 状态（对应后端 McpServerStatus 枚举） */
export type McpServerStatus = 'CONNECTED' | 'DISCONNECTED' | 'ERROR' | 'DISABLED'

/** MCP Server 信息（对应后端 McpServerResponse） */
export interface McpServerInfo {
  /** Server 名称 */
  name: string
  /** 传输方式 */
  transport: McpTransportType
  /** 当前状态 */
  status: McpServerStatus
  /** 是否启用 */
  enabled: boolean
  /** 工具数量 */
  toolCount: number
  /** 最近错误信息（ERROR/DISCONNECTED 状态时填充） */
  lastError: string | null
  /** 首次连接时间 */
  connectTime: string | null
  /** sse/http Server 的连接 URL（stdio 类型为 null） */
  url: string | null
  /** stdio Server 的执行命令（sse/http 类型为 null） */
  command: string | null
  /** stdio Server 的命令参数（sse/http 类型为 null） */
  args: string[] | null
}

/** MCP 工具信息（对应后端 McpToolResponse） */
export interface McpToolInfo {
  /** MCP Server 返回的原始工具名 */
  originalName: string
  /** 注册到 ToolRegistry 的工具方法名（mcp_{serverName}_{toolName}） */
  registeredName: string
  /** 工具描述 */
  description: string
  /** 参数 JSON Schema 字符串 */
  parametersSchema: string
}

/** 添加 MCP Server 请求体（对应后端 CreateMcpServerRequest） */
export interface CreateMcpServerRequest {
  /** Server 唯一标识 */
  name: string
  /** 传输方式 */
  transport: McpTransportType
  /** 是否启用 */
  enabled: boolean
  /** stdio 专用：可执行命令 */
  command?: string
  /** stdio 专用：命令参数 */
  args?: string[]
  /** stdio 专用：环境变量 */
  env?: Record<string, string>
  /** sse/http 专用：连接 URL */
  url?: string
  /** sse/http 专用：请求头 */
  headers?: Record<string, string>
}

/** 批量添加 Server 的单条结果 */
export interface AddResult {
  /** Server 名称 */
  name: string
  /** 是否成功 */
  success: boolean
  /** 失败原因（success=false 时填充） */
  error?: string
}

// ========== 技能（Skill）相关类型 ==========

/**
 * 技能参考资源（对应后端 SkillResponse.ResourceResponse）
 * 业务含义：技能携带的只读领域参考（如模板），激活后注入提示词。
 */
export interface SkillResource {
  name: string
  content: string
}

/**
 * 技能自带脚本参数（CR-001，对应后端 ScriptParam）
 */
export interface SkillScriptParam {
  name: string
  type: string
  required: boolean
  description: string
}

/**
 * 技能自带脚本声明（CR-001，对应后端 SkillResponse.ScriptResponse）
 * 业务含义：Skill 自带的预定义参数化脚本（白名单语言 shell/python3），激活时动态注册为工具。
 */
export interface SkillScriptInfo {
  name: string
  language: string
  description: string
  params?: SkillScriptParam[]
  content: string
}

/**
 * 技能信息（对应后端 SkillResponse）
 * 业务含义：技能管理页列表/编辑与对话页选择器的数据类型。
 */
export interface SkillInfo {
  id: string
  name: string
  description: string
  instruction: string
  resources: SkillResource[]
  scripts: SkillScriptInfo[]
  enabled: boolean
  source: 'PRESET' | 'CUSTOM'
  /** 创建/编辑时的校验警告（密钥类，透传展示） */
  warnings?: string[]
}

/**
 * 技能激活事件载荷（对应后端 SSE skill_activated 事件）
 * 业务含义：Agent 自主激活（auto）或用户手动指定（manual）技能时，前端据此渲染激活徽标。
 */
export interface SkillActivatedEvent {
  skillId: string
  skillName: string
  source: 'auto' | 'manual'
  boundToolIds: string[]
}

// ========== 工具按需加载相关类型 ==========

/**
 * 工具权限等级（对应后端 ToolPermissionLevel）
 * 业务含义：管理页三档开关（allow/ask/deny）与对话页选择器过滤的依据。
 * allow=放行直接执行；ask=需用户确认（流式路径触发确认卡片）；deny=禁止（选择器隐藏 + 后端静默剔除）。
 */
export type ToolPermissionLevel = 'allow' | 'ask' | 'deny'

/** 工具信息（对应后端 ToolInfo） */
export interface ToolInfo {
  /** 工具标识，格式 category:name（如 builtin:getCurrentTime、mcp:mermaid-mcp） */
  id: string
  /** 工具类别：builtin / mcp / rag */
  category: string
  /** 工具名称（方法名或 serverName） */
  name: string
  /** 工具描述 */
  description: string
  /** 是否为默认加载工具 */
  isDefault: boolean
  /**
   * 工具权限等级（allow/ask/deny，小写字符串）
   * 业务含义：来自后端 ToolPermissionService 裁决结果，管理页回显下拉、对话页过滤 deny。
   * 可选字段，向前兼容旧数据（旧接口未返回时 permission 为 undefined）。
   */
  permission?: ToolPermissionLevel
}

/**
 * 工具权限确认数据（对应后端 tool_confirm SSE 事件）
 * 业务含义：ask 级工具被 Agent 调用时，后端推送确认卡片所需四要素
 * （工具名、用途描述、参数摘要），前端渲染后由用户批准/拒绝并回传 toolApproved。
 */
export interface ToolConfirmData {
  /** 工具名称 */
  toolName: string
  /** 工具用途描述 */
  toolDescription: string
  /** 参数摘要（JSON 字符串） */
  arguments: string
}

/** 工具列表响应（对应后端 GET /api/agent/tools） */
export interface ToolsResponse {
  /** 所有工具信息列表 */
  tools: ToolInfo[]
  /** 默认工具 ID 列表 */
  defaults: string[]
}

// ========== 工作流编排相关类型（P2 新增）==========

/** 编排模式（对应后端 OrchestrationMode 枚举） */
export type OrchestrationMode = 'SEQUENTIAL' | 'PARALLEL' | 'CONDITIONAL' | 'LOOP' | 'SUPERVISOR'

/**
 * 工作流执行状态（对应后端 WorkflowExecutionStatus 枚举）
 * P3 新增：PAUSED（重试耗尽暂停待恢复，非终态，AC-016）
 * 工作流 HITL 新增：WAITING_USER（等待用户回复，非终态，AC-N01/AC-N03）
 */
export type WorkflowExecutionStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'TERMINATED' | 'TIMEOUT' | 'PAUSED' | 'WAITING_USER'

/** 工作流参数定义 */
export interface WorkflowParameter {
  name: string
  type: string
  required: boolean
  description: string
}

/** 模板摘要（对应后端 WorkflowTemplateResponse） */
export interface WorkflowTemplateSummary {
  id: string
  name: string
  description: string
  mode: OrchestrationMode
  agentCount: number
  parameters: WorkflowParameter[]
}

/** 模板 Agent 项 */
export interface WorkflowAgentItem {
  name: string
  description: string
  modelId: string | null
  tools: string[]
}

/** 模板详情（对应后端 WorkflowDetailResponse） */
export interface WorkflowTemplateDetail {
  id: string
  name: string
  description: string
  mode: OrchestrationMode
  maxRetries: number
  agents: WorkflowAgentItem[]
  parameters: WorkflowParameter[]
  parallelGroups?: { name: string; agents: WorkflowAgentItem[] }[]
  branches?: { name: string; conditionDescription: string; agents: WorkflowAgentItem[] }[]
  loop?: { maxIterations: number; exitConditionDescription: string; agents: WorkflowAgentItem[] }
  /** SUPERVISOR 模式：层级编排结构（P3 新增，AC-002/AC-007） */
  supervisor?: SupervisorDefinitionItem
}

/**
 * Supervisor 层级编排定义（模板详情，P3 新增）
 * 业务含义：主控拆解 Agent + Worker 池 + 主控汇总 Agent + 最大子任务数（AC-007/AC-031）。
 */
export interface SupervisorDefinitionItem {
  maxSubtasks: number
  planAgent: WorkflowAgentItem
  workers: WorkflowAgentItem[]
  summarizeAgent: WorkflowAgentItem
}

/**
 * Supervisor 子任务项（supervisor_plan 事件 data.subtasks 元素，P3 新增）
 * 业务含义：主控拆解出的单个子任务；routed=false 表示未精确/包含命中、按兜底规则派发（AC-007）。
 */
export interface SupervisorSubtaskItem {
  id: number
  description: string
  /** 主控指定的目标 Worker 名（可能为空/不精确） */
  agent: string
  /** 实际路由到的 Worker 名 */
  routedAgent: string
  /** 是否按主控指定路由（false=兜底派发） */
  routed: boolean
}

/** 执行历史摘要（对应后端 WorkflowExecutionSummaryResponse） */
export interface WorkflowExecutionSummary {
  executionId: string
  templateId: string
  templateName: string
  mode: OrchestrationMode | null
  status: WorkflowExecutionStatus
  startTime: string | null
  endTime: string | null
  finalResult: string
  iterationCount: number
}

/** 执行步骤项 */
export interface WorkflowStepItem {
  agentName: string
  status: string
  durationMs: number
  /**
   * 步骤输出内容（CR-001 Task-31 新增，AC-034）
   * 业务含义：执行详情接口返回该步骤 Agent 的完整输出，详情面板折叠区展示。
   * 可选字段，向后兼容（后端不返回时省略，不影响既有消费方）。
   */
  output?: string
}

/** 执行详情（对应后端 WorkflowExecutionResponse） */
export interface WorkflowExecutionDetail extends WorkflowExecutionSummary {
  steps: WorkflowStepItem[]
}

/** 工作流 SSE 流式事件回调 */
export interface WorkflowStreamCallbacks {
  onWorkflowStart: (data: { executionId: string; templateName: string; mode: string; agentCount: number }) => void
  onStepStart: (data: { agentIndex: number; agentName: string; groupIndex?: number; iteration?: number; totalAgents: number }) => void
  onToken: (data: { agentIndex: number; content: string }) => void
  onStepComplete: (data: { agentIndex: number; agentName: string; durationMs: number; outputLength: number }) => void
  onStepRetry: (data: { agentIndex: number; retryCount: number; remainingRetries: number }) => void
  onStepError: (data: { agentIndex: number; error: string; retryCount: number }) => void
  onBranchSelected: (data: { branchIndex: number; branchName: string; agentCount: number }) => void
  onLoopIteration: (data: { iteration: number; maxIterations: number; agentCount: number }) => void
  onGroupStart: (data: { groupIndex: number; groupName: string; agentCount: number }) => void
  onGroupComplete: (data: { groupIndex: number; groupName: string; durationMs: number; outputLength: number }) => void
  onWorkflowComplete: (data: { executionId: string; finalResult: string; mode: string; totalDurationMs: number; iterationCount?: number; exitReason?: string }) => void
  onWorkflowFailed: (data: { executionId: string; status: string; error: string }) => void
  onError: (message: string) => void

  // ===== P3 新增回调（均为可选，向前兼容）=====

  /**
   * 收到 workflow_paused 事件（重试耗尽暂停，AC-016）
   * 业务含义：工作流进入 PAUSED 待恢复状态，前端展示暂停 UI 与恢复入口。
   */
  onWorkflowPaused?: (data: { executionId: string; failedAgent: string; failedIndex: number; error: string; resumable: boolean }) => void
  /**
   * 收到 step_skipped 事件（断点恢复跳过已完成步骤，AC-017）
   * 业务含义：恢复执行时该步骤已完成，直接复用历史输出跳过。
   */
  onStepSkipped?: (data: { agentIndex: number; agentName: string; reason: string }) => void
  /**
   * 收到 supervisor_plan 事件（主控拆解完成，AC-007）
   * 业务含义：主控 Agent 将任务拆解为子任务清单，前端初始化子任务卡片列表。
   */
  onSupervisorPlan?: (data: { subtasks: SupervisorSubtaskItem[]; totalSubtasks: number }) => void
  /**
   * 收到 supervisor_dispatch 事件（主控派发子任务，AC-007）
   * 业务含义：第 subtaskIndex 个子任务派发给 agentName 对应 Worker（routed=false 为兜底派发）。
   */
  onSupervisorDispatch?: (data: { subtaskIndex: number; totalSubtasks: number; description: string; agentName: string; routed: boolean }) => void
  /**
   * 收到 supervisor_summary 事件（主控汇总开始，AC-007）
   * 业务含义：所有子任务完成，主控进入结果汇总阶段。
   */
  onSupervisorSummary?: (data: { subtaskCount: number }) => void

  // ===== 工作流 HITL 新增回调（Task-11，均为可选，向前兼容）=====

  /**
   * 收到 ask_user 事件（HITL 提问数据，工作流 HITL）
   * 业务含义：Agent 暂停前推送提问数据（type/question/options/retryCount），
   * 与 workflow_waiting 成对出现，前端据此渲染 AskUserCard。
   */
  onAskUser?: (data: {
    agentIndex: number
    agentName: string
    type: string
    question: string
    options: string[]
    retryCount: number
  }) => void
  /**
   * 收到 workflow_waiting 事件（工作流等待用户回复，AC-N01/AC-N03）
   * 业务含义：Agent 暂停等待用户决策（askUser 追问或 checkpoint 检查点），
   * SSE 流随之结束。前端据 hitlMode 渲染等待横幅与交互卡片，
   * 用户回复后调用 replyToWorkflow 恢复执行。
   */
  onWorkflowWaiting?: (data: {
    executionId: string
    agentIndex: number
    agentName: string
    /** HITL 暂停模式：askUser=Agent 追问；checkpoint=预设检查点；toolConfirm=ask 级工具确认（Task-17） */
    hitlMode: 'askUser' | 'checkpoint' | 'toolConfirm'
    resumable: boolean
  }) => void
  /**
   * 收到 workflow_resumed 事件（工作流恢复执行，AC-N03）
   * 业务含义：用户回复后执行状态回 RUNNING，新 SSE 流首事件即该事件，
   * 前端据此隐藏等待横幅并继续展示执行进度。
   */
  onWorkflowResumed?: (data: { executionId: string; status: string }) => void
  /**
   * 收到 tool_confirm 事件（ask 级工具确认请求，工作流版，Task-17，AC-H01）
   * 业务含义：工作流中 Agent 调用 ask 级工具被权限拦截暂停，后端推送工具四要素 +
   * 暂停步骤（agentIndex/agentName），与 workflow_waiting(hitlMode=toolConfirm) 成对出现，
   * 前端据此渲染 ConfirmCard，用户批准/拒绝后调用 replyToWorkflow(executionId, null, approved)。
   */
  onToolConfirm?: (data: {
    agentIndex: number
    agentName: string
    toolName: string
    toolDescription: string
    arguments: string
  }) => void
}
