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

// ========== 工具按需加载相关类型 ==========

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

/** 工作流执行状态（对应后端 WorkflowExecutionStatus 枚举） */
export type WorkflowExecutionStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'TERMINATED' | 'TIMEOUT'

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
}
