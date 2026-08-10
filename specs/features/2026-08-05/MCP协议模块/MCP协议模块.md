# 功能需求说明书 (Feature Requirements Document)

> **功能名称**：MCP 协议模块（agent-demo-mcp）
> **创建日期**：2026-08-05
> **阶段**：P1 需求分析
> **关联文档**：`KNOWLEDGE_BASE.md`（能力矩阵第 9 项）、`specs/技术架构文档-TOGAF.md`、`agent-demo-mcp/pom.xml`
> **适用边界守卫**：`specs/GUARDRAILS.md` 第 3.1 节 P1 阶段规则

---

## 1. 背景与价值 (Context & Value)

*   **背景**：agent-demo 项目已实现单 Agent ReAct 工具调用、RAG 知识库、深度思考等核心能力，但 Agent 可用的工具仅限于项目本地工具集（Calculator/Time/Http/FileRead/RAG 知识库 Tool）。MCP（Model Context Protocol）是 Anthropic 提出的开放协议，已成为 LLM 与外部工具/数据源标准化互通的事实标准。引入 MCP 客户端能力，可让 Agent 即时接入 MCP 生态工具（如文件系统、数据库查询、Git 操作、Web Fetch 等官方/社区 MCP Server），无需在项目内重新实现这些工具。
*   **目标**：为 Agent 提供消费外部 MCP Server 工具的能力——通过 stdio 或 HTTP+SSE 连接 MCP Server，自动发现工具并接入 ReAct 循环，让 Agent 工具集从"项目内自研"扩展到"MCP 生态可消费"。
*   **关联**：
    *   KNOWLEDGE_BASE.md 能力矩阵第 9 项 MCP 协议：🚧 规划中 → ✅ 已实现
    *   错误码区间 5400-5499（MCP 相关），已预留 `MCP_CONNECTION_FAILED(5400)`、`MCP_TOOL_CALL_FAILED(5401)`
    *   模块依赖方向：`agent-demo-mcp` ← 依赖 `common, tools`；`agent-demo-app` → 依赖 `mcp`
    *   CR-003 已验证的"动态 Tool 注册"机制为本模块工具集成提供基础设施

## 2. 功能范围 (Scope)

### 2.1 本次范围（In Scope）

*   **MCP 客户端角色**：仅实现 Client 角色，消费外部 MCP Server 暴露的工具
*   **双传输方式**：stdio（本地子进程）+ HTTP+SSE（远程 URL）两种 MCP 标准传输方式
*   **Server 管理（静态 + 动态）**：
    *   静态配置：通过 `application.yml` 预配置 Server 列表，应用启动时批量加载并连接
    *   动态管理：运行时通过 REST API 增删查重连 Server，新增 Server 立即接入 Agent 工具集
*   **工具集成**：MCP Server 拉取的工具按 `mcp_{serverName}_{toolName}` 前缀规则注册到现有 `ToolRegistry`，与本地工具一视同仁参与 Function Calling
*   **Agent 集成**：复用 `SimpleAgent` 已有的 `lastToolCount` 检测机制（BR-AGT-003），MCP 工具增删自动触发 delegate 重建绑定最新工具列表
*   **容错策略**：分场景容错
    *   静态 Server 启动失败：记录 ERROR 日志 + 跳过，不阻塞应用启动
    *   动态添加失败：返回 `MCP_CONNECTION_FAILED(5400)` 错误码给调用方
    *   运行中断线：标记 `DISCONNECTED` 状态 + 注销其工具 + 记录日志
*   **Web 接口**：提供 REST API 管理 MCP Server（列表/添加/删除/状态查询/重连/工具列表），路径前缀 `/api/mcp/servers`
*   **错误码**：新增 `MCP_SERVER_NAME_EXISTS(5402)`、`MCP_SERVER_NOT_FOUND(5403)`、`MCP_TRANSPORT_UNSUPPORTED(5404)`、`MCP_MODULE_DISABLED(5405)`、`MCP_SERVER_ALREADY_CONNECTED(5406)`
*   **配置结构**：统一 YAML 配置 + `transport` 类型字段路由（stdio/sse 字段条件生效）
*   **集成验证**：MCP 工具能被 Agent 通过 Function Calling 调用，结果回填到 ReAct 循环
*   **非文本内容处理（CR-001 扩展）**：MCP 工具返回图片等非文本内容时，系统通过 Transport 包装器拦截原始 MCP 响应，提取图片 URL/base64 信息以 Markdown 格式返回给 LLM；前端对话界面支持 Markdown 图片渲染
*   **输出解析结构化重构（CR-002 扩展）**：MCP 工具返回内容解析从"异常驱动双重路径"重构为"原始响应统一解析"架构，新增 `McpContentParser` 内容类型策略分发器，覆盖 MCP 协议定义的全部内容类型（text/image/audio/resource/structuredContent/unknown），解耦 LangChain4j 内部异常行为依赖

### 2.2 不在本次范围（Out of Scope）

*   **MCP 服务端实现** — 本次仅消费外部 MCP Server，不实现 Server 角色（如对外暴露项目本地工具为 MCP Server）。计划：后续 CR 补充
*   **Vue 前端 MCP Server 管理界面** — 本次仅提供 REST API。计划：后续 CR 补充（与 RAG "先后端后前端"演进路径一致）
*   **MCP Server 自身的开发** — 不开发新的 MCP Server 程序，仅消费现有 MCP 生态（官方/社区 Server）
*   **持久化存储** — 动态添加的 Server 仅存内存，应用重启后丢失。计划：与 RAG 模块风格一致，未来接入 MySQL 时统一持久化
*   **MCP 协议高级特性** — 不实现资源订阅（Resources）、提示模板（Prompts）、日志流（Logging）、根目录（Roots）等扩展能力。计划：后续 CR 按需补充
*   **鉴权与认证扩展** — 本次仅支持 `headers` 透传（如 `Authorization: Bearer xxx`），不实现 OAuth 等鉴权流程。计划：未来接入 Spring Security 时统一处理
*   **MCP Server 健康检查定时任务** — 不实现主动探活定时任务，断线检测依赖工具调用时的失败回调。计划：后续 CR 补充
*   **MCP 工具调用响应大小截断** — 不实现类似 `HttpTool` 的 10KB 截断机制，依赖 MCP Server 自身的响应规范。计划：未来按需补充

## 3. 用户角色 (Actors)

*   **API 调用方**：通过 REST API 管理 MCP Server（添加/删除/查询状态/重连/查询工具列表）
*   **对话用户**：通过现有对话界面与 Agent 交互，Agent 自主调用 MCP 工具完成用户请求
*   **Agent（SimpleAgent）**：通过 Function Calling 自主选择调用 MCP 工具或本地工具，无需感知工具来源
*   **运维者**：通过 `application.yml` 配置静态 MCP Server 列表，通过日志观察连接状态

## 4. 用户故事 (User Stories)

*   **US-001**: 作为 **API 调用方**，我想要 **通过 REST API 动态添加/删除/查询 MCP Server**，以便 **在运行时管理 Agent 可消费的外部工具集**。
    *   关联验收标准：AC-007, AC-008, AC-009, AC-011, AC-019, AC-020, AC-022, AC-026, AC-028, AC-029

*   **US-002**: 作为 **运维者**，我想要 **通过 application.yml 预配置 MCP Server 列表**，以便 **应用启动时自动连接并接入工具，无需手动操作**。
    *   关联验收标准：AC-001, AC-002, AC-003, AC-004, AC-005, AC-006, AC-014, AC-032

*   **US-003**: 作为 **对话用户**，我想要 **Agent 能调用外部 MCP Server 暴露的工具**，以便 **完成本地工具集无法完成的任务（如查询天气、读取远程文件、操作 Git 等）**。
    *   关联验收标准：AC-012, AC-013, AC-018

*   **US-004**: 作为 **API 调用方**，我想要 **重连已断线的 MCP Server**，以便 **在网络恢复或 Server 重启后自动恢复工具可用性**。
    *   关联验收标准：AC-010, AC-016, AC-017, AC-027

## 5. 详细需求与流程 (Detailed Requirements)

### 5.1 核心流程

**流程 A - 静态配置加载（应用启动时）：**

1. 应用启动触发 MCP 模块初始化（受 `mcp.enabled` 开关控制）
2. 读取 `application.yml` 中 `mcp.servers` 静态配置列表
3. 过滤 `enabled=false` 的 Server（标记为 `DISABLED` 状态，不连接）
4. 对每个启用的 Server，按 `transport` 字段（stdio/sse）选择对应 `McpTransport` 实现
5. 异步并发连接各 Server（避免单个慢 Server 阻塞启动）
6. 连接成功后调用 `McpClient.listTools()` 拉取工具列表
7. 工具按 `mcp_{serverName}_{toolName}` 前缀规则注册到 `ToolRegistry`
8. 所有静态 Server 处理完成后，`SimpleAgent` 通过 `lastToolCount` 检测机制触发 delegate 重建
9. 任意 Server 连接失败时：记录 ERROR 日志 + 标记为 `ERROR` 状态 + 不阻塞应用启动

**流程 B - 动态 Server 管理（REST API）：**

1. API 调用方通过 `POST /api/mcp/servers` 提交新 Server 配置（name + transport + 对应传输参数）
2. 系统校验 name 唯一性、字段合法性、transport 取值有效性
3. 校验通过后建立连接（stdio 启动子进程 / sse 建立 HTTP 连接）
4. 连接成功后拉取工具列表，注册到 `ToolRegistry`
5. Server 状态标记为 `CONNECTED`，返回 Server 详情（含工具列表）
6. 失败时返回 `MCP_CONNECTION_FAILED(5400)` 错误码，不污染 `ToolRegistry`

**流程 C - Agent 工具调用：**

1. 对话用户在现有对话界面提问（如"用 weather 工具查北京天气"）
2. Agent（ReAct 循环）通过 Function Calling 自主选择 MCP 工具（如 `mcp_weather_getForecast`）
3. `ToolRegistry` 路由到对应 MCP 工具代理对象
4. 代理对象通过 `McpClient.executeTool()` 调用外部 MCP Server
5. MCP Server 返回结果（受 `tool-timeout` 控制，默认 60s）
6. 结果回填到 ReAct 循环，Agent 基于结果组织最终回答
7. 调用超时或失败时抛出 `MCP_TOOL_CALL_FAILED(5401)`，Agent 接收错误信息决定重试或换工具

**流程 D - Server 断线处理：**

1. MCP Server 主动断开 / 网络中断 / 子进程退出
2. `McpClient` 检测到断线（心跳失败或调用时 IOException）
3. Server 状态变更为 `DISCONNECTED`
4. 该 Server 暴露的所有工具从 `ToolRegistry` 注销
5. `SimpleAgent` 通过 `lastToolCount` 检测机制触发 delegate 重建
6. 记录 WARN 日志，下次列表查询时反馈 `DISCONNECTED` 状态
7. API 调用方可通过 `POST /api/mcp/servers/{name}/reconnect` 触发重连

### 5.2 交互/界面规则 (UI/UX Rules)

*   **无前端界面**：本期仅提供 REST API，无 Vue 前端管理界面
*   **REST API 路径约定**：统一前缀 `/api/mcp/servers`（遵循项目 `/api/{module}/` 规范，参考 `BR-NAME` 与 `AgentController` 的 `/api/agent/` 风格）
*   **REST API 响应格式**：统一使用 `Result<T>` 包装（遵循 `BR-WEB` 通用规则）
*   **REST API 错误码**：使用 `ErrorCode` 枚举统一定义（遵循 `BR-ERR-001`）
*   **Server 状态查询响应字段**：必须包含 `name`、`transport`、`status`、`toolCount`、`lastError`（可选）
*   **接口文档**：通过 `springdoc-openapi` 自动生成 Swagger 文档，可通过 `/swagger-ui.html` 查看

### 5.3 业务规则 (Business Rules)

> **编号规则**：遵循 `BR-{模块缩写}-{3位序号}` 格式，模块缩写为 `MCP`。下列规则将同步至 `specs/SDD-工程业务背景文档.md`。

#### BR-MCP-001 ~ BR-MCP-015（核心业务规则）

*   **BR-MCP-001**：MCP 模块总开关为 `mcp.enabled`（默认 `true`），设置为 `false` 时模块完全禁用（不加载任何 Server、所有 REST API 返回 `MCP_MODULE_DISABLED(5405)`）。🔴 强制
*   **BR-MCP-002**：MCP Server 名称长度 1-50 个字符，仅允许中英文、数字、下划线和连字符（与知识库名称规则 `BR-RAG-001` 一致）。🔴 强制
*   **BR-MCP-003**：MCP Server 名称全局唯一（与知识库名称唯一性规则 `BR-RAG-002` 一致），重复时返回 `MCP_SERVER_NAME_EXISTS(5402)`。🔴 强制
*   **BR-MCP-004**：MCP Server 必须指定 `transport` 字段，取值仅限 `stdio` 或 `sse`，其他值返回 `MCP_TRANSPORT_UNSUPPORTED(5404)`。🔴 强制
*   **BR-MCP-005**：stdio 传输方式的 Server 必须配置 `command` 字段（非空），`args` 和 `env` 可选。🔴 强制
*   **BR-MCP-006**：sse 传输方式的 Server 必须配置 `url` 字段（合法 HTTP/HTTPS URL），`headers` 可选。🔴 强制
*   **BR-MCP-007**：MCP 工具名采用 `mcp_{serverName}_{toolName}` 前缀规则注册到 `ToolRegistry`，保证全局唯一。🔴 强制
*   **BR-MCP-008**：MCP 工具调用超时默认 60s（受 `mcp.default-tool-timeout` 控制），可被单 Server 的 `tool-timeout` 覆盖。⚪ 可覆盖
*   **BR-MCP-009**：静态配置的 Server 启动连接失败时记录 ERROR 日志并跳过，不阻塞应用启动（参考 `BR-RAG-012`"工具不可用不阻塞 Agent"原则）。🔴 强制
*   **BR-MCP-010**：动态添加的 Server 连接失败时返回 `MCP_CONNECTION_FAILED(5400)` 错误码给调用方，不污染 `ToolRegistry`。🔴 强制
*   **BR-MCP-011**：MCP Server 运行中断线时自动注销其所有工具（通过 `ToolRegistry.unregisterTool()`），并触发 `SimpleAgent` delegate 重建。🔴 强制
*   **BR-MCP-012**：MCP Server 状态枚举为：`CONNECTED`（已连接）、`DISCONNECTED`（已断线）、`ERROR`（连接失败）、`DISABLED`（配置禁用）。🔴 强制
*   **BR-MCP-013**：动态添加的 MCP Server 仅存内存，应用重启后丢失（不持久化），与 RAG 模块"内存存储"风格一致。🔴 强制
*   **BR-MCP-014**：MCP Server 的 `enabled=false` 时跳过连接，状态标记为 `DISABLED`，可在运行时通过重连接口尝试启用。🔴 强制
*   **BR-MCP-015**：重连已处于 `CONNECTED` 状态的 Server 时返回 `MCP_SERVER_ALREADY_CONNECTED(5406)` 业务错误。🔴 强制

#### BR-MCP-016 ~ BR-MCP-022（CR-001 扩展规则）

*   **BR-MCP-016**：MCP 工具参数 Schema 序列化时，必须手动提取 `JsonObjectSchema` 的 `properties()` 逐字段构建 JSON Schema，禁止直接使用 Jackson 序列化（`JsonSchemaElement` 是多态接口，Jackson 无法自动序列化）。🔴 强制
*   **BR-MCP-017**：MCP 工具的 `@Tool` 描述必须包含结构化参数列表和使用示例（通过 `parseParametersSchema` 生成），以降低 LLM 猜测参数名的概率。🔴 强制
*   **BR-MCP-018**：`DefaultMcpClient.Builder` 必须配置 `initializationTimeout`（默认 60s），避免 stdio 子进程首次启动时（如 `npx` 下载依赖）初始化超时。🔴 强制
*   **BR-MCP-019**：`McpToolExecutor` 必须捕获 LangChain4j 内部抛出的 `Unsupported content type` 异常，不得将此异常直接传播给 Agent。🔴 强制
*   **BR-MCP-020**：MCP 工具返回非文本内容时，必须从原始 MCP JSON-RPC 响应中提取内容信息（URL/base64），以 Markdown 格式 `![图片](url)` 返回给 LLM，禁止仅返回通用提示信息。🔴 强制
*   **BR-MCP-021**：`McpTransportWrapper` 必须透明代理 `McpTransport` 的所有方法，仅拦截工具调用响应进行缓存，不得修改或阻断任何方法的行为。🔴 强制
*   **BR-MCP-022**：前端 Markdown 渲染白名单必须包含 `img` 标签以支持图片显示，同时 DOMPurify 必须过滤 `onerror`、`onload` 等危险事件属性，确保 XSS 防护有效。🔴 强制

#### BR-MCP-023 ~ BR-MCP-025（CR-002 扩展规则）

*   **BR-MCP-023**：MCP 工具返回内容必须统一从 `McpTransportWrapper` 缓存的原始 JSON-RPC 响应解析，禁止依赖 LangChain4j `ToolExecutionResult` 的内容提取路径（`extractResultText`）。`executeTool()` 的返回值仅用于触发协议交换，不用于内容提取。🔴 强制
*   **BR-MCP-024**：MCP 内容解析必须通过 `McpContentParser` 按内容类型策略分发，每种类型（text/image/audio/resource/structuredContent/unknown）有独立处理逻辑。新增内容类型仅需在 Parser 中添加处理分支，不修改 `McpToolExecutor`。🔴 强制
*   **BR-MCP-025**：未知内容类型必须静默跳过并记录 WARNING 日志，不得导致工具调用失败（协议演进韧性）。`isError=true` 的工具错误响应必须在内容前添加 `[工具错误]` 前缀，帮助 LLM 区分正常结果与错误结果。🔴 强制

## 6. 验收标准 (Acceptance Criteria)

> **重要**：以下验收标准是后续技术方案、任务规划和 TDD 测试用例的直接依据。每条 AC 使用 Given-When-Then 格式，可被测试验证。

### 6.1 正常流程 (Happy Path)

- [ ] **AC-001**: 应用启动加载 MCP 静态配置
    - Given: `application.yml` 中 `mcp.enabled=true`，且 `mcp.servers` 列表包含至少 1 个 `enabled=true` 的 Server 配置
    - When: 应用启动完成
    - Then: 系统按配置顺序加载每个 Server，对每个启用的 Server 建立连接、拉取工具、注册到 `ToolRegistry`

- [ ] **AC-002**: stdio 传输方式建立子进程连接
    - Given: 静态配置中存在一个 `transport=stdio`、`command=node`、`args=[/path/to/server.js]` 的 Server
    - When: 应用启动并初始化该 Server
    - Then: 系统启动子进程执行 `node /path/to/server.js`，建立 stdio 通道，Server 状态变更为 `CONNECTED`

- [ ] **AC-003**: HTTP+SSE 传输方式建立远程连接
    - Given: 静态配置中存在一个 `transport=sse`、`url=https://mcp.example.com/sse` 的 Server
    - When: 应用启动并初始化该 Server
    - Then: 系统向该 URL 发起 HTTP 连接建立 SSE 通道，Server 状态变更为 `CONNECTED`

- [ ] **AC-004**: 连接成功后拉取 MCP Server 工具列表
    - Given: 一个 MCP Server 已成功建立连接
    - When: 系统调用 `McpClient.listTools()`
    - Then: 返回该 Server 暴露的所有工具元数据（工具名、描述、参数 Schema），存入内存 Server 元数据

- [ ] **AC-005**: 工具按前缀规则注册到 ToolRegistry
    - Given: MCP Server 名称为 `weather`，暴露工具 `getForecast`、`getAlerts`
    - When: 系统将工具注册到 `ToolRegistry`
    - Then: `ToolRegistry` 中新增 `mcp_weather_getForecast`、`mcp_weather_getAlerts` 两个工具，可通过 `listTools()` 查询到

- [ ] **AC-006**: 静态 Server 加载完成后触发 SimpleAgent delegate 重建
    - Given: 静态 Server 全部加载完成，`ToolRegistry.getToolCount()` 数量发生变化
    - When: 下次 Agent 对话调用 `getDelegate()`
    - Then: `SimpleAgent` 检测到 `lastToolCount` 变化，重建 delegate 绑定最新工具列表（包含 MCP 工具）

- [ ] **AC-007**: GET /api/mcp/servers 返回 Server 列表及状态
    - Given: 系统中已加载若干 MCP Server（含静态和动态添加的）
    - When: API 调用方发起 `GET /api/mcp/servers`
    - Then: 返回 `Result.success(serverList)`，每个 Server 包含 `name`、`transport`、`status`、`toolCount` 字段

- [ ] **AC-008**: POST /api/mcp/servers 动态添加 Server
    - Given: API 调用方持有合法的 Server 配置（name=fetch、transport=sse、url=合法 URL）
    - When: 发起 `POST /api/mcp/servers`，请求体携带配置
    - Then: 系统校验通过、建立连接、拉取工具、注册到 `ToolRegistry`，返回 `Result.success(serverDetail)`，状态为 `CONNECTED`

- [ ] **AC-009**: DELETE /api/mcp/servers/{name} 删除 Server 并注销工具
    - Given: 系统中存在一个 `CONNECTED` 状态的 Server，名为 `fetch`
    - When: API 调用方发起 `DELETE /api/mcp/servers/fetch`
    - Then: 系统断开连接、注销其所有工具（`ToolRegistry.unregisterTool()`）、从内存移除、返回 `Result.success()`，下次列表查询不再包含该 Server

- [ ] **AC-010**: POST /api/mcp/servers/{name}/reconnect 重连断线 Server
    - Given: 一个名为 `weather` 的 Server 当前状态为 `DISCONNECTED`
    - When: API 调用方发起 `POST /api/mcp/servers/weather/reconnect`
    - Then: 系统尝试重新建立连接，成功后重新拉取工具并注册到 `ToolRegistry`，状态变更为 `CONNECTED`，返回最新 Server 详情

- [ ] **AC-011**: GET /api/mcp/servers/{name}/tools 查询 Server 工具列表
    - Given: 一个名为 `weather` 的 Server 处于 `CONNECTED` 状态
    - When: API 调用方发起 `GET /api/mcp/servers/weather/tools`
    - Then: 返回该 Server 暴露的所有工具元数据列表（含 `originalName`、`registeredName`、`description`、`parametersSchema`）

- [ ] **AC-012**: Agent 通过 Function Calling 调用 MCP 工具
    - Given: 系统中有一个名为 `weather` 的 MCP Server，暴露 `mcp_weather_getForecast` 工具
    - When: 对话用户提问"查一下北京天气"，Agent 通过 ReAct 决定调用 `mcp_weather_getForecast`
    - Then: 工具代理对象通过 `McpClient.executeTool()` 调用外部 MCP Server，获得返回结果

- [ ] **AC-013**: MCP 工具调用结果回填到 ReAct 循环
    - Given: Agent 已发起对 `mcp_weather_getForecast` 的调用并获得结果
    - When: 工具结果回填到 Agent 上下文
    - Then: Agent 基于工具结果组织最终回答（如"北京今天晴，气温 25°C"），通过 SSE 流式返回给用户

- [ ] **AC-036**: MCP 工具返回图片 URL 时系统从原始响应提取并返回给 LLM（CR-001 + CR-002 修改）
    - Given: mermaid-mcp Server 已连接，Agent 调用 `validate_and_render_mermaid_diagram` 工具
    - When: MCP Server 返回包含 `ImageContent`（带 URL）的响应，系统通过 `McpContentParser` 统一解析原始 JSON-RPC 响应
    - Then: 系统从 `McpTransportWrapper` 缓存的原始 JSON-RPC 响应中提取图片 URL，以 Markdown 格式 `![图片](url)` 返回给 LLM，LLM 可将图片链接转述给用户

- [ ] **AC-037**: MCP 工具返回 base64 图片时系统提取图片信息返回给 LLM（CR-001 + CR-002 修改）
    - Given: MCP Server 返回包含 `ImageContent`（带 base64Data，无 URL）的响应
    - When: 系统通过 `McpContentParser` 统一解析原始 JSON-RPC 响应
    - Then: 系统从原始响应中提取 base64 图片信息（MIME 类型 + 数据摘要），返回文本描述给 LLM（如 `[图片] 已生成 PNG 格式图片，base64 数据长度: 12345 字符`）

- [ ] **AC-038**: MCP 工具返回混合内容时系统正确提取所有内容（CR-001 + CR-002 修改）
    - Given: MCP Server 返回包含 `TextContent` + `ImageContent` 的混合响应
    - When: 系统通过 `McpContentParser` 统一解析原始 JSON-RPC 响应
    - Then: 系统从原始响应中同时提取文本和图片信息，按响应顺序拼接返回给 LLM（文本在前，图片 Markdown 在后）

- [ ] **AC-041**: 系统统一从原始 JSON-RPC 响应解析所有 MCP 内容类型（CR-002）
    - Given: MCP Server 已连接，Agent 调用任意 MCP 工具
    - When: MCP Server 返回任意类型内容（text/image/audio/resource/structuredContent），`mcpClient.executeTool()` 成功或抛出 `Unsupported content type` 异常
    - Then: 系统不依赖 `ToolExecutionResult` 的内容提取，统一从 `McpTransportWrapper` 缓存的原始 JSON-RPC 响应通过 `McpContentParser` 解析，返回 LLM 可消费的文本

### 6.2 边界与异常 (Edge & Error Cases)

- [ ] **AC-014**: 静态 Server 连接失败不阻塞应用启动
    - Given: `application.yml` 中有 2 个静态 Server，其中 1 个的 `command` 路径不存在
    - When: 应用启动
    - Then: 失败的 Server 记录 ERROR 日志、状态标记为 `ERROR`、跳过；另一个 Server 正常连接；应用启动成功，Agent 可正常对话

- [ ] **AC-015**: 动态添加 Server 连接失败返回 MCP_CONNECTION_FAILED
    - Given: API 调用方提交一个 sse Server，`url=https://nonexistent.example.com/sse`
    - When: 发起 `POST /api/mcp/servers`
    - Then: 连接尝试失败，返回 `Result.error(MCP_CONNECTION_FAILED, "连接 MCP Server 失败: ...")`，HTTP 状态 500，错误码 5400，`ToolRegistry` 无新增

- [ ] **AC-016**: 已连接 Server 运行中断线标记为 DISCONNECTED
    - Given: 一个 `CONNECTED` 状态的 stdio Server，其子进程被外部强制杀死
    - When: 下次工具调用或健康检测发现连接已断开
    - Then: Server 状态变更为 `DISCONNECTED`，记录 WARN 日志，下次列表查询反馈新状态

- [ ] **AC-017**: 断线 Server 工具自动从 ToolRegistry 注销
    - Given: Server 进入 `DISCONNECTED` 状态时，`ToolRegistry` 中有该 Server 暴露的 3 个工具
    - When: 断线事件触发
    - Then: 3 个工具通过 `ToolRegistry.unregisterTool()` 全部注销，`ToolRegistry.getToolCount()` 减少 3，`SimpleAgent` 触发 delegate 重建

- [ ] **AC-018**: MCP 工具调用超时返回 MCP_TOOL_CALL_FAILED
    - Given: 一个 `CONNECTED` 状态的 Server，其 `tool-timeout=5s`，但 MCP Server 响应需要 30s
    - When: Agent 调用该 Server 的工具
    - Then: 5 秒后超时，抛出 `BusinessException(MCP_TOOL_CALL_FAILED, "工具调用超时")`，错误码 5401，Agent 接收错误信息决定下一步

- [ ] **AC-019**: 重复添加同名 Server 返回 MCP_SERVER_NAME_EXISTS
    - Given: 系统中已存在名为 `weather` 的 Server
    - When: API 调用方再次发起 `POST /api/mcp/servers`，name=weather
    - Then: 校验失败，返回 `Result.error(MCP_SERVER_NAME_EXISTS, "MCP Server 名称已存在: weather")`，HTTP 状态 400，错误码 5402

- [ ] **AC-020**: 操作不存在的 Server 返回 MCP_SERVER_NOT_FOUND
    - Given: 系统中不存在名为 `nonexistent` 的 Server
    - When: API 调用方发起 `DELETE /api/mcp/servers/nonexistent`
    - Then: 返回 `Result.error(MCP_SERVER_NOT_FOUND, "MCP Server 不存在: nonexistent")`，HTTP 状态 404，错误码 5403

- [ ] **AC-021**: transport 值非 stdio/sse 返回 MCP_TRANSPORT_UNSUPPORTED
    - Given: API 调用方提交 Server 配置，`transport=websocket`
    - When: 系统校验 transport 字段
    - Then: 返回 `Result.error(MCP_TRANSPORT_UNSUPPORTED, "不支持的传输方式: websocket")`，HTTP 状态 400，错误码 5404

- [ ] **AC-022**: mcp.enabled=false 时模块不加载
    - Given: `application.yml` 中 `mcp.enabled=false`
    - When: 应用启动
    - Then: MCP 模块完全不加载，不连接任何 Server，`ToolRegistry` 中无 MCP 工具；任何 `GET /api/mcp/servers` 返回 `Result.error(MCP_MODULE_DISABLED, "MCP 模块已禁用")`，错误码 5405

- [ ] **AC-023**: stdio 模式 command 字段为空时校验失败
    - Given: API 调用方提交 Server 配置，`transport=stdio`，但 `command` 字段为空
    - When: 系统校验配置合法性
    - Then: 返回 `Result.error(PARAM_INVALID, "stdio 传输方式必须指定 command 字段")`，HTTP 状态 400，错误码 400，不建立连接

- [ ] **AC-024**: sse 模式 url 字段为空或格式错误时校验失败
    - Given: API 调用方提交 Server 配置，`transport=sse`，但 `url=ftp://invalid`（非 HTTP/HTTPS）
    - When: 系统校验 URL 格式
    - Then: 返回 `Result.error(PARAM_INVALID, "sse 传输方式的 url 必须为合法 HTTP/HTTPS URL")`，HTTP 状态 400，错误码 400

- [ ] **AC-025**: 不同 Server 同名工具前缀隔离不冲突
    - Given: Server A（name=github）暴露工具 `search`，Server B（name=gitlab）也暴露工具 `search`
    - When: 两个 Server 都注册到 `ToolRegistry`
    - Then: `ToolRegistry` 同时存在 `mcp_github_search` 和 `mcp_gitlab_search` 两个工具，无冲突，Agent 可通过工具名区分调用

- [ ] **AC-026**: 重连已 CONNECTED 状态的 Server 返回业务错误
    - Given: 一个名为 `weather` 的 Server 当前状态为 `CONNECTED`
    - When: API 调用方发起 `POST /api/mcp/servers/weather/reconnect`
    - Then: 返回 `Result.error(MCP_SERVER_ALREADY_CONNECTED, "MCP Server 已连接，无需重连: weather")`，HTTP 状态 400，错误码 5406

- [ ] **AC-027**: 删除 Server 时正在执行的工具调用优雅失败
    - Given: Agent 正在调用 `mcp_weather_getForecast` 工具（请求已发出，结果未返回）
    - When: API 调用方并发发起 `DELETE /api/mcp/servers/weather`
    - Then: 删除操作执行（断开连接、注销工具），正在执行的工具调用收到 `BusinessException(MCP_TOOL_CALL_FAILED, "MCP Server 已断开")`，错误码 5401，Agent 接收错误后由 LLM 决定是否重试或换工具

- [ ] **AC-039**: 前端对话界面支持 Markdown 图片渲染（CR-001）
    - Given: LLM 回复中包含 `![alt](url)` 格式的 Markdown 图片语法
    - When: 前端渲染助手消息（`renderMarkdown` 函数处理）
    - Then: 图片以 `<img>` 标签正确渲染显示，图片宽度不超出消息气泡边界（`max-width: 100%`），`onerror` 等危险属性被 DOMPurify 过滤

- [ ] **AC-040**: Transport 包装器对现有三种传输方式无影响（CR-001 回归）
    - Given: 系统使用 `McpTransportWrapper` 包装 stdio/SSE/HTTP 三种传输方式
    - When: 正常 MCP 工具调用（返回纯文本内容）
    - Then: 包装器透明代理所有方法，工具调用结果与未包装时完全一致，无异常、无延迟、无数据丢失

- [ ] **AC-042**: 系统支持 AudioContent 类型内容处理（CR-002）
    - Given: MCP Server 返回包含 `AudioContent`（带 base64Data + mimeType）的响应
    - When: 系统通过 `McpContentParser` 解析原始响应
    - Then: 系统返回文本描述（如 `[音频] 已生成 audio/wav 格式音频，base64 数据长度: 67890 字符`），不导致工具调用失败

- [ ] **AC-043**: 系统支持 EmbeddedResource 内容类型处理（CR-002）
    - Given: MCP Server 返回包含 `EmbeddedResource` 的响应，resource 子类型为 `TextResourceContents`（含 text 字段）或 `BlobResourceContents`（含 blob base64 字段）
    - When: 系统通过 `McpContentParser` 解析原始响应
    - Then: TextResourceContents 提取文本内容返回；BlobResourceContents 返回文本描述（如 `[资源] application/pdf 格式二进制资源，base64 数据长度: 54321 字符`）

- [ ] **AC-044**: 系统支持 structuredContent 结构化输出处理（CR-002）
    - Given: MCP Server 返回包含 `structuredContent` 字段的响应（JSON 对象）
    - When: 系统通过 `McpContentParser` 解析原始响应
    - Then: 系统将 structuredContent 序列化为 JSON 文本返回给 LLM，LLM 可理解结构化数据

- [ ] **AC-045**: 系统对未知内容类型静默跳过不崩溃（CR-002）
    - Given: MCP Server 返回包含未知 `type` 字段（如 `"type":"future_type"`）的 content 项
    - When: 系统通过 `McpContentParser` 解析原始响应
    - Then: 未知类型 content 项被静默跳过并记录 WARNING 日志，其他已知类型 content 项正常解析，工具调用不失败

### 6.3 业务规则验证 (Business Rules)

- [ ] **AC-028**: MCP Server 名称长度与字符规则
    - Given: API 调用方提交 Server 配置，name=`abc@def!`（含非法字符）
    - When: 系统校验名称合法性
    - Then: 返回 `Result.error(PARAM_INVALID, "Server 名称仅允许中英文、数字、下划线和连字符")`，HTTP 状态 400；同样校验 name 长度 > 50 字符时返回错误

- [ ] **AC-029**: MCP Server 名称全局唯一
    - Given: 系统中已存在名为 `weather` 的 Server（无论何种状态：`CONNECTED` / `DISCONNECTED` / `ERROR` / `DISABLED`）
    - When: API 调用方提交新 Server 配置，name=weather
    - Then: 校验失败返回 `MCP_SERVER_NAME_EXISTS(5402)` 错误（参考 AC-019）；删除后同名可再次添加

- [ ] **AC-030**: MCP 工具调用超时默认 60s，可单 Server 覆盖
    - Given: `application.yml` 中 `mcp.default-tool-timeout=60s`，未配置单 Server 的 `tool-timeout`
    - When: 调用该 Server 工具
    - Then: 超时阈值为 60s；若 Server 单独配置 `tool-timeout=10s`，则该 Server 工具调用超时阈值为 10s

- [ ] **AC-031**: 工具名加前缀 mcp_{serverName}_{toolName}
    - Given: Server 名称为 `github`，MCP Server 返回工具 `search`、`createIssue`
    - When: 工具注册到 `ToolRegistry`
    - Then: 注册的工具名分别为 `mcp_github_search`、`mcp_github_createIssue`，可通过 `ToolRegistry.listTools()` 查询到，且不会与本地工具（如 `calculator`）冲突

- [ ] **AC-032**: 静态配置 Server enabled=false 时跳过不连接
    - Given: `application.yml` 中某个 Server 配置 `enabled=false`
    - When: 应用启动加载该 Server
    - Then: 该 Server 状态标记为 `DISABLED`，不建立连接、不注册工具；出现在 `GET /api/mcp/servers` 列表中，可通过 `reconnect` 接口尝试启用

- [ ] **AC-033**: 动态添加的 Server 仅存内存，重启后丢失
    - Given: 通过 `POST /api/mcp/servers` 动态添加了 Server `temp-fetch`，状态 `CONNECTED`
    - When: 应用重启
    - Then: 重启后 `temp-fetch` Server 不存在（仅静态配置的 Server 会被加载），需重新通过 API 添加

- [ ] **AC-034**: MCP Server 状态枚举完整
    - Given: MCP 模块已加载
    - When: 查询任意 Server 状态
    - Then: 状态值必须为以下之一：`CONNECTED`（已连接）、`DISCONNECTED`（已断线）、`ERROR`（连接失败）、`DISABLED`（配置禁用）；不允许其他自定义状态值

- [ ] **AC-035**: 配置同时支持 stdio 和 sse 两种传输方式
    - Given: `application.yml` 中同时配置了 stdio Server（本地子进程）和 sse Server（远程 URL）
    - When: 应用启动加载
    - Then: 两种传输方式的 Server 都能正确加载、连接、注册工具，互不影响；REST API 列表能同时返回两种传输方式的 Server

---

### AC 覆盖度自检

- [x] 正常流程的每个关键步骤都有对应 AC（AC-001~AC-013 覆盖静态加载、动态管理、Agent 调用全流程；AC-036~AC-038 覆盖非文本内容处理；AC-041 覆盖统一解析路径）
- [x] 第 5.3 节的每条业务规则都有对应 AC（BR-MCP-001->AC-022、BR-MCP-002->AC-028、BR-MCP-003->AC-029、BR-MCP-004->AC-021、BR-MCP-005->AC-023、BR-MCP-006->AC-024、BR-MCP-007->AC-031、BR-MCP-008->AC-030、BR-MCP-009->AC-014、BR-MCP-010->AC-015、BR-MCP-011->AC-017、BR-MCP-012->AC-034、BR-MCP-013->AC-033、BR-MCP-014->AC-032、BR-MCP-015->AC-026；BR-MCP-020->AC-036、BR-MCP-021->AC-040、BR-MCP-022->AC-039；BR-MCP-023->AC-041、BR-MCP-024->AC-041~045、BR-MCP-025->AC-045）
- [x] 所有已识别的边界/异常情况都有对应 AC（连接失败、断线、超时、命名冲突、参数校验、并发删除、非文本内容处理、前端图片渲染、Transport 包装器回归、音频/资源/结构化输出/未知类型处理等）
- [x] 每条 AC 描述的是可观测行为，而非内部实现
- [x] 三类场景覆盖完整：正常 17 条（13+4） + 边界/异常 20 条（14+6） + 业务规则 8 条 = 45 条

---

## 附录：决策记录摘要

| 决策项 | 选项 | 决策理由 |
|---|---|---|
| Q1 角色范围 | 仅 MCP 客户端 | 项目能力矩阵第 9 项原文强调"集成外部工具"；客户端是 Agent 演进最直接的增量价值；学习曲线平缓 |
| Q2 传输方式 | stdio + HTTP+SSE 都支持 | LangChain4j 1.0.0 双传输开箱即用；覆盖本地和远程两种场景；与项目 Web 模块"多传输方式"风格一致 |
| Q3 Server 管理 | 静态配置 + 动态管理 | 与 RAG 模块"启动加载 + 运行时 CRUD"风格一致；静态便于演示，动态便于真实场景 |
| Q4 工具集成 | 直接注册到 ToolRegistry | 复用 CR-003 已验证的动态 Tool 机制；SimpleAgent 已有 `lastToolCount` 检测；KISS 原则 |
| Q5 容错策略 | 分场景容错 | 静态失败不阻塞启动（参考 BR-RAG-012）；动态失败必须反馈；运行中断线需状态机管理 |
| Q6 对外接口 | 核心 + REST API | REST API 是后端模块标准契约；前端界面作为后续 CR 补充；学习项目核心目标是 MCP 协议本身 |
| Q7 Server 标识 | 用户指定 name | 与 RAG"知识库名称全局唯一"风格一致；REST API 路径可读性强 |
| Q8 工具命名 | `mcp_{serverName}_{toolName}` 前缀 | `mcp_` 前缀明确来源；二级前缀避免不同 Server 同名冲突；日志直接体现来源 |
| Q9 配置结构 | 统一配置 + 类型字段 | 与 `ark.coding-plan` 配置风格一致；避免分组冗余；字段名与 LangChain4j Builder 对齐 |
| Q10 范围边界 | 完全同意 | 遵循"渐进式演进"原则；包含完整闭环；可独立验证 |

---

## 变更日志

| 日期 | 版本 | 变更内容 | 变更人 |
|---|---|---|---|
| 2026-08-05 | v1.0 | 初始版本，基于 10 轮苏格拉底式提问澄清产出 35 条 AC（正常 13 + 边界/异常 14 + 业务规则 8），覆盖 MCP 客户端 + 双传输 + 静态/动态管理 + 分场景容错 | feature-requirements-clarification |
| 2026-08-07 | v1.1 (CR-001) | 新增 5 条 AC（AC-036~AC-040）+ 3 条业务规则（BR-MCP-020~022），扩展非文本内容处理能力：Transport 包装器拦截原始 MCP 响应提取图片 URL/base64 + 前端 Markdown 图片渲染支持 | feature-evolution |
| 2026-08-07 | v1.2 (CR-002) | 新增 5 条 AC（AC-041~AC-045）+ 3 条业务规则（BR-MCP-023~025），修改 3 条 AC（AC-036~038 从异常路径改为统一解析路径）。重构内容：新增 `McpContentParser` 内容类型策略分发器，废弃 `extractResultText()` + `extractFromRawResponse()` 双重解析路径，统一从原始 JSON-RPC 响应解析，覆盖 MCP 协议全部内容类型（text/image/audio/resource/structuredContent/unknown），解耦 LangChain4j 内部异常行为依赖 | feature-evolution |
