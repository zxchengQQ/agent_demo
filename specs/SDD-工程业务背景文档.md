# AI Agent 示例项目 - 工程业务背景文档（业务真理源）

> **版本**：v1.0 | **基线日期**：2026-07-20 | **适用范围**：agent-demo
> **定位**：纯业务视角。以 AI Agent 学习示例项目的**能力框架**为基准，系统性列出所有业务逻辑约束。不涉及具体技术实现。
> **约束分级**：🔴 强制 -> 🟡 尽量 -> 🟢 建议 -> ⚪ 可覆盖（详见第 5 节）

---

## 1. 产品定位

**AI Agent 示例项目 (agent-demo)** 是一套面向 AI 应用开发学习者的**企业级 Agent 能力演练平台**。系统基于 Java + LangChain4j + 火山引擎方舟构建，以**单 Agent 工具调用（ReAct 循环）**为基础，扩展**多 Agent 协作、RAG 知识库问答、MCP 协议互通、有状态工作流编排**等能力。

业务生命周期覆盖：

```
用户提问 -> 会话建立 -> Agent 思考(ReAct) -> 工具调用 -> 结果回填 -> 最终回复 -> 会话归档
```

### 1.1 项目目标

- 构建企业级 AI Agent 示例工程，覆盖主流 Agent 能力域
- 以 Java 强类型语言直观呈现 Agent 接口契约与协作原理
- 提供可运行的最小 Demo（单 Agent + 工具调用 + 火山引擎）

### 1.2 项目非目标

- 不追求商业产品级完整度，聚焦学习与原理验证
- 不实现完整的权限/RBAC/多租户治理体系
- 不部署到 K8s 等生产编排环境

---

## 2. 业务领域全景

### 2.1 核心业务域

| 业务域 | 核心实体 | 业务定位 |
|--------|---------|---------|
| **Agent 编排域** | BaseAgent、SimpleAgent | Agent 对话入口与 ReAct 循环执行，项目核心能力 |
| **LLM 接入域** | ChatModel、StreamingChatModel、EmbeddingModel | 火山引擎方舟模型统一接入与场景路由 |
| **工具调用域** | ToolRegistry、CalculatorTool、TimeTool、HttpTool、FileReadTool | Agent 可调用的能力集，声明式注册 |
| **记忆管理域** | ChatMemory、SessionMetadata | 会话级短期记忆与多会话隔离 |
| **对话接入域** | AgentController、ChatRequest、ChatResponse | REST API 对外暴露 Agent 能力 |

### 2.2 辅助支撑域

| 域 | 职责 |
|---|------|
| **公共组件** | 常量（ModelConstants）、枚举（AgentType/MemoryType/MessageType）、异常体系（ErrorCode）、统一返回（Result/PageResult）、工具类 |
| **RAG 检索**（规划中） | 文档加载、分块、向量化、检索（Milvus + 豆包 Embedding） |
| **MCP 协议**（规划中） | MCP 客户端/服务端、外部工具集成 |
| **应用编排**（规划中） | 场景化 Agent（客服/研究/编程）、端到端 pipeline |
| **启动与配置** | Spring Boot 主启动、多环境配置、提示词模板 |

### 2.3 能力矩阵

| 能力域 | 实现状态 | 学习要点 |
|--------|---------|---------|
| LLM 调用 | ✅ 已实现 | 模型抽象、流式输出、参数调优、场景路由 |
| 工具调用 | ✅ 已实现 | ReAct 循环、Function Calling、声明式注册 |
| 记忆系统 | ✅ 已实现（短期） | 短期窗口记忆、会话隔离、超时清理 |
| Agent 编排 | ✅ 已实现（单 Agent） | AiServices 代理、ReAct 循环、懒加载 |
| Web 接口 | ✅ 已实现 | REST 同步对话、会话管理、Swagger 文档 |
| RAG 检索 | 🚧 规划中 | 文档分块、向量化、检索策略 |
| MCP 协议 | 🚧 规划中 | MCP 工具集成、A2A 通信 |
| 多 Agent 协作 | 🚧 规划中 | Sequential/Hierarchical 模式 |
| 工作流编排 | 🚧 规划中 | 状态机、分支重试、Human-in-the-loop |

---

## 3. 核心业务框架模型

> 以下是系统已建成的**能力框架骨架**，所有新功能开发必须在此框架内进行。

### 3.1 Agent 类型框架

**Agent 协作模式**：

| 类型 | 含义 | 流转动作 |
|------|------|---------|
| SINGLE | 单 Agent 独立完成任务 | 用户消息 -> ReAct 循环 -> 回复 |
| MULTI | 多 Agent 角色协作（规划中） | 任务拆解 -> Agent1 -> Agent2 -> 汇总 |
| WORKFLOW | 工作流编排（规划中） | 状态机驱动 -> 分支/重试/HITL |

通过 `AgentType` 枚举统一标识，调用方按类型路由到不同 Agent 实现。

### 3.2 ReAct 循环机制

```
用户输入 -> 构造 Prompt -> LLM 思考 -> 是否调用工具？
                                    ├─ 是 -> 执行工具 -> 结果回填 -> 回到 LLM 思考
                                    └─ 否 -> 生成最终回答 -> 返回
```

- **触发条件**：用户消息到达，Agent 委托 AiServices 代理执行
- **迭代上限**：`agent.max-iterations`（默认 10），防止无限循环消耗 Token
- **配置来源**`application.yml` 的 `agent.*` 配置项
- **豁免条件**：无（强制上限保护）

### 3.3 会话管理机制

- **触发条件**：用户首次对话或传入无效 sessionId
- **会话标识**：UUID 去横线生成，全局唯一
- **隔离规则**：按 sessionId 隔离 ChatMemory，互不干扰
- **超时清理**：每 5 分钟扫描，清理 30 分钟未活跃会话（`@Scheduled`）
- **配置来源**：`session.timeout-minutes`（默认 30）

### 3.4 记忆窗口机制

- **窗口大小**：默认保留最近 20 条消息（`agent.chat-memory-window-size`）
- **淘汰策略**：超出窗口后旧消息自动淘汰（FIFO）
- **平衡点**：20 条为经验值，过大消耗 Token，过小丢失上下文

### 3.5 工具注册框架

| 工具 | 功能 | 安全措施 |
|------|------|---------|
| CalculatorTool | 数学计算 | 数值范围校验 |
| TimeTool | 时间查询 | 无风险 |
| HttpTool | HTTP 请求 | SSRF 防护（禁止内网）、响应截断（10KB） |
| FileReadTool | 文件读取 | 目录白名单（`agent.file-allowed-dir`） |

- **注册方式**：Spring Bean + `@Tool` 注解，启动后懒加载扫描
- **动态注册**：支持运行时新增工具（MCP 外部工具）
- **线程安全**：CopyOnWriteArrayList 存储

### 3.6 模型路由框架

| 场景标识 | 模型 | 适用场景 |
|---------|------|---------|
| `chat`（默认） | doubao-seed-2.0-pro | 通用旗舰对话 |
| `code` | doubao-seed-2.0-code | 编程任务 |
| `lite` | doubao-seed-2.0-lite | 轻量快速场景 |
| Embedding | doubao-embedding-large-text-240915 | RAG 向量化 |

- **路由方式**：`ModelFactory.getChatModel(scene)` 按场景查找
- **回退策略**：未命中场景配置时回退到 `default-model`
- **缓存策略**：模型实例线程安全，ConcurrentHashMap 缓存复用

### 3.7 错误码框架

| 区间 | 业务域 | 示例 |
|------|--------|------|
| 200 | 成功 | SUCCESS(200) |
| 4xx | 客户端错误 | PARAM_INVALID(400)、UNAUTHORIZED(401) |
| 5001-5099 | LLM 相关 | LLM_CALL_FAILED、LLM_TIMEOUT、LLM_API_KEY_INVALID |
| 5100-5199 | 工具相关 | TOOL_EXECUTION_FAILED、TOOL_NOT_FOUND |
| 5200-5299 | 记忆/会话 | MEMORY_NOT_FOUND、SESSION_EXPIRED |
| 5300-5399 | RAG 相关 | RAG_RETRIEVE_FAILED、RAG_EMBEDDING_FAILED |
| 5400-5499 | MCP 相关 | MCP_CONNECTION_FAILED、MCP_TOOL_CALL_FAILED |
| 5000 | 系统异常 | SYSTEM_ERROR |

---

## 4. 用户角色与 RACI 矩阵

### 4.1 角色定义

| 角色 | 职责范围 |
|------|---------|
| **学习者** | 运行 Demo、调用 API、观察 Agent 行为、阅读源码理解原理 |
| **开发者** | 扩展工具、新增 Agent 实现、接入新 LLM、编写场景化 pipeline |
| **API 调用方** | 通过 REST API 集成 Agent 能力到外部应用 |
| **运维者** | 配置 API Key、监控 Token 消耗、管理会话超时 |

### 4.2 核心操作 RACI

> R=执行 A=审批 C=咨询 I=知会

| 业务活动 | 学习者 | 开发者 | API 调用方 | 运维者 |
|---------|---------|---------|---------|---------|
| 运行 Demo | **R** | **R** | I | I |
| 调用对话 API | **R** | C | **R** | I |
| 新增工具 | - | **R/A** | - | I |
| 新增 Agent 实现 | - | **R/A** | - | I |
| 配置 API Key | - | C | - | **R/A** |
| 切换 LLM 模型 | - | **R** | I | **A** |
| 会话超时调优 | - | C | I | **R/A** |

---

## 5. 业务约束清单

### 5.1 约束分级标准

| 级别 | 标签 | 含义 | 违反后果 |
|------|------|------|---------|
| 🔴 强制 | `MUST` | 系统必须遵守，不可绕过 | 业务逻辑异常、数据不一致、资金风险 |
| 🟡 尽量 | `SHOULD` | 正常情况下必须遵守，极端场景可审批豁免 | 业务合规风险 |
| 🟢 建议 | `RECOMMENDED` | 推荐遵守，提升业务质量 | 体验下降、效率降低 |
| ⚪ 可覆盖 | `CONFIGURABLE` | 可由管理员配置 | 依赖管理员决策 |

---

### 5.2 LLM 接入约束

| 编号 | 约束描述 | 级别 |
|------|---------|------|
| BR-LLM-001 | API Key 必须通过环境变量 `ARK_API_KEY` 注入，禁止硬编码入库 | 🔴 强制 |
| BR-LLM-002 | 必须使用 Coding Plan 专用地址 `/api/coding/v3`（按次计费），禁止使用标准 `/api/v3`（按 Token 计费） | 🔴 强制 |
| BR-LLM-003 | 模型名称必须通过 `ModelConstants` 常量类引用，禁止在调用方硬编码 | 🔴 强制 |
| BR-LLM-004 | 模型实例必须通过 `ModelFactory` 获取并缓存复用，禁止每次调用重新创建 | 🔴 强制 |
| BR-LLM-005 | 调用超时时间默认 60s，可通过 `ark.coding-plan.timeout` 配置 | ⚪ 可覆盖 |
| BR-LLM-006 | 最大重试次数默认 3 次，网络异常时自动重试 | ⚪ 可覆盖 |

### 5.3 Agent 编排约束

| 编号 | 约束描述 | 级别 |
|------|---------|------|
| BR-AGT-001 | 所有 Agent 实现必须实现 `BaseAgent` 接口 | 🔴 强制 |
| BR-AGT-002 | ReAct 循环最大迭代次数默认 10，超过后强制返回当前结果 | 🔴 强制 |
| BR-AGT-003 | Agent delegate 必须懒加载，避免构造时触发循环依赖 | 🔴 强制 |
| BR-AGT-004 | 会话记忆按 sessionId 隔离，禁止跨会话读取记忆 | 🔴 强制 |
| BR-AGT-005 | 系统提示词通过 `systemMessageProvider` 动态提供，支持场景定制 | 🟡 尽量 |
| BR-AGT-006 | Agent 调用日志默认开启，记录 sessionId/耗时/回复长度 | ⚪ 可覆盖 |

### 5.4 工具调用约束

| 编号 | 约束描述 | 级别 |
|------|---------|------|
| BR-TOOL-001 | 工具类必须加 `@Component`，工具方法必须加 `@Tool` 注解并描述功能 | 🔴 强制 |
| BR-TOOL-002 | 工具注册采用懒加载，首次调用 `listTools()` 时扫描 | 🔴 强制 |
| BR-TOOL-003 | HTTP 工具必须执行 SSRF 防护，禁止访问内网地址 | 🔴 强制 |
| BR-TOOL-004 | HTTP 工具响应超过 10KB 必须截断，防止 Token 消耗过大 | 🔴 强制 |
| BR-TOOL-005 | 文件读取工具必须限定在 `agent.file-allowed-dir` 目录白名单内 | 🔴 强制 |
| BR-TOOL-006 | 工具执行失败必须抛出 `BusinessException` + 对应 ErrorCode | 🔴 强制 |
| BR-TOOL-007 | 动态注册工具（MCP 等）应通过 `ToolRegistry.register()` 注册 | 🟡 尽量 |

### 5.5 记忆与会话约束

| 编号 | 约束描述 | 级别 |
|------|---------|------|
| BR-MEM-001 | 会话 ID 必须使用 UUID 去横线生成，保证全局唯一 | 🔴 强制 |
| BR-MEM-002 | 会话超时默认 30 分钟，每 5 分钟扫描清理一次 | ⚪ 可覆盖 |
| BR-MEM-003 | 短期记忆窗口默认 20 条消息，超出自动淘汰旧消息 | ⚪ 可覆盖 |
| BR-MEM-004 | `ChatMemoryManager.getMemory()` 使用 `computeIfAbsent`，回调内禁止修改同一 map | 🔴 强制 |
| BR-MEM-005 | 传入无效 sessionId 时应自动新建会话，不应抛出错误 | 🔴 强制 |

### 5.6 数据安全与合规约束

| 编号 | 约束描述 | 级别 |
|------|---------|------|
| BR-SEC-001 | API Key 禁止明文打印到日志 | 🔴 强制 |
| BR-SEC-002 | HTTP 工具禁止访问内网地址（10./172.16-31./192.168./127./localhost） | 🔴 强制 |
| BR-SEC-003 | 文件读取工具禁止读取白名单目录外的文件 | 🔴 强制 |
| BR-SEC-004 | 生产环境应关闭 Swagger UI 访问 | 🟡 尽量 |
| BR-SEC-005 | 日志中不应打印用户消息完整明文（可截断或脱敏） | 🟢 建议 |

### 5.7 错误码约束

| 编号 | 约束描述 | 级别 |
|------|---------|------|
| BR-ERR-001 | 错误码必须通过 `ErrorCode` 枚举统一定义，禁止在调用方硬编码 | 🔴 强制 |
| BR-ERR-002 | 业务异常必须使用 `BusinessException` + `ErrorCode`，禁止直接 `throw new RuntimeException` | 🔴 强制 |
| BR-ERR-003 | 错误码编号区间按业务域划分，不可重叠 | 🔴 强制 |
| BR-ERR-004 | 新增错误码必须在 `ErrorCode` 枚举中分配编号并补充注释 | 🔴 强制 |

---

## 6. 业务大屏与预警

| 区域 | 内容 | 说明 |
|------|------|------|
| 会话监控 | 活跃会话数、累计会话数 | `SessionManager.activeSessionCount()` |
| 调用统计 | LLM 调用次数、平均耗时、失败率 | 日志聚合统计 |
| Token 消耗 | 套餐额度消耗进度 | 火山引擎控制台查看 |
| 工具调用 | 各工具调用次数、成功率、平均耗时 | 日志聚合统计 |
| 预警监控 | API Key 失效、LLM 限流、超时会话堆积 | 日志告警 |

---

## 7. 术语表

| 术语 | 英文 | 含义 |
|------|------|------|
| Agent | Agent | 智能体，具备感知、决策、行动能力的 AI 程序 |
| ReAct | Reasoning + Acting | 推理-行动循环，Agent 思考后调用工具，工具返回结果后继续思考 |
| Function Calling | Function Calling | LLM 自主决定调用哪个函数（工具）的能力 |
| AiServices | AiServices | LangChain4j 提供的声明式 Agent 构建器，通过动态代理实现接口 |
| 火山引擎方舟 | Volcengine Ark | 字节跳动旗下 LLM 服务平台，提供豆包等模型 |
| Coding Plan | Coding Plan | 火山引擎方舟按次计费套餐，专用 `/api/coding/v3` 地址 |
| 豆包 | Doubao | 火山引擎自研的 LLM 系列，含 Pro/Code/Lite 等版本 |
| ChatMemory | Chat Memory | 会话级对话记忆，保留最近 N 条消息作为上下文 |
| sessionId | Session ID | 会话唯一标识，用于隔离不同用户的对话记忆 |
| Token | Token | LLM 处理文本的最小单位，计费依据 |
| Embedding | Embedding | 文本向量化，将文本转换为向量用于相似度检索 |
| RAG | Retrieval-Augmented Generation | 检索增强生成，先检索知识库再生成回答 |
| MCP | Model Context Protocol | 模型上下文协议，Agent 间工具共享标准 |
| HITL | Human-in-the-Loop | 人工介入循环，工作流中需人工确认的环节 |
| Guardrails | Guardrails | 输入/输出守护，防止 Prompt 注入、内容过滤 |
| SSRF | Server-Side Request Forgery | 服务端请求伪造，HTTP 工具需防护的安全风险 |

---

## 附录：约束速查索引

### A.1 🔴 强制约束速查

| 领域 | 条数 | 关键约束 |
|------|------|---------|
| LLM 接入 | 4 | API Key 环境变量注入、Coding Plan 地址、模型名常量化、模型实例缓存 |
| Agent 编排 | 4 | BaseAgent 接口、迭代上限、懒加载、会话隔离 |
| 工具调用 | 6 | @Tool 注解、懒加载注册、SSRF 防护、响应截断、目录白名单、异常封装 |
| 记忆会话 | 2 | UUID 生成、computeIfAbsent 规范、无效 sessionId 自动新建 |
| 数据安全 | 3 | API Key 不入日志、SSRF 防护、目录白名单 |
| 错误码 | 4 | 枚举统一、BusinessException、区间不重叠、新增需注释 |

### A.2 ⚪ 可覆盖约束速查

| 领域 | 关键约束 |
|------|---------|
| LLM 接入 | 超时时间、重试次数、温度参数 |
| Agent 编排 | 调用日志开关 |
| 记忆会话 | 会话超时、记忆窗口大小 |

---

**文档维护**：
- 新增业务规则时，按模块添加约束条目
- 约束编号保持连续，避免跳号
- 定期审查约束的有效性，过时约束标记为"已废弃"
