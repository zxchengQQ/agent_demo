# Web 接口模块 业务说明书

## 1. 模块概述

Web 接口模块（agent-demo-web）是 AI Agent 示例项目的对外接入层，负责通过 REST API 暴露 Agent 对话能力、会话管理与 LLM 厂商/模型配置管理能力。模块基于 Spring Boot Web MVC 提供 RESTful 接口，支持同步对话、会话创建/查询/清空，集成 Springdoc OpenAPI 3 自动生成接口文档，通过 GlobalExceptionHandler 统一异常处理，TraceIdInterceptor 注入链路追踪 ID。v2.0 新增 LlmConfigController 提供 LLM 厂商动态配置管理（厂商 CRUD、API Key 连接测试、模型查询、配置状态与批量同步）。

## 2. 用户角色与权限

| 角色 | 权限范围 | 典型操作 |
|------|---------|---------|
| **学习者** | 调用全部 API | 对话、会话管理、查看 Swagger |
| **API 调用方** | 调用全部 API | 集成 Agent 能力到外部应用 |
| **开发者** | 扩展接口 | 新增 Controller、调整 DTO |
| **运维者** | 管理接口文档 | 配置 Swagger 开关 |

## 3. 业务功能点

### 3.1 同步对话

- **触发场景**：用户发送消息给 Agent，期望获得同步回复。
- **操作步骤**：`POST /api/agent/chat`，请求体含 sessionId（可选）+ message（必填）。
- **系统行为**：
  1. 校验 sessionId，无效则新建
  2. 记录用户消息到 ChatMemory
  3. 调用 `BaseAgent.chat(sessionId, message)`
  4. 记录助手回复到 ChatMemory
  5. 返回 `Result<ChatResponse>`
- **前置条件**：message 不能为空（`@NotBlank`）。
- **后置结果**：返回含 sessionId/response/duration 的响应。

### 3.2 创建会话

- **触发场景**：用户主动创建新会话。
- **操作步骤**：`POST /api/agent/session`。
- **系统行为**：`SessionManager.createSession()` 生成 UUID，返回 `Result<String>`。
- **后置结果**：返回 sessionId。

### 3.3 查询会话

- **触发场景**：校验会话是否存在。
- **操作步骤**：`GET /api/agent/session/{sessionId}`。
- **系统行为**：`SessionManager.exists(sessionId)`，返回 `Result<Boolean>`。

### 3.4 清空会话记忆

- **触发场景**：用户主动清空对话历史，重新开始。
- **操作步骤**：`DELETE /api/agent/session/{sessionId}/memory`。
- **系统行为**：`ChatMemoryManager.clearMemory(sessionId)`，返回 `Result<Void>`。
- **业务规则**：清空记忆后会话本身保留，后续对话从头开始。

### 3.5 接口文档

- **触发场景**：开发者或调用方查看接口说明。
- **操作步骤**：访问 `http://localhost:8080/swagger-ui.html`。
- **系统行为**：Springdoc OpenAPI 3 自动生成接口文档，含 `@Operation` 描述。

### 3.6 全局异常处理

- **触发场景**：任何 Controller 抛出异常，或客户端访问不存在的静态资源/接口路径。
- **系统行为**：`GlobalExceptionHandler`（`@RestControllerAdvice`）拦截并转换为 `Result<T>`，按异常类型分类处理：
  - `BusinessException`：保留原错误码
  - `MethodArgumentNotValidException`：返回 PARAM_INVALID(400)
  - `NoResourceFoundException`：返回 NOT_FOUND(404)，日志降级为 1 行 WARN，避免堆栈污染
  - 其他异常：返回 SYSTEM_ERROR(5000)
- **业务规则**：
  - BusinessException 保留原错误码，其他异常返回 SYSTEM_ERROR(5000)。
  - `NoResourceFoundException` 必须专门处理返回 404，禁止落入兜底 `Exception` 处理器导致 500 + 40+ 行堆栈污染日志（如未引入 actuator 时访问 `/actuator/health`）。

### 3.7 链路追踪

- **触发场景**：每次 HTTP 请求。
- **系统行为**：`TraceIdInterceptor` 生成 traceId 存入 MDC，日志中通过 `%X{traceId}` 输出。
- **业务规则**：traceId 贯穿一次请求的所有日志，便于问题定位。

### 3.8 流式对话（SSE）

- **触发场景**：用户发送消息，期望逐字接收 Agent 回复。
- **操作步骤**：`POST /api/agent/chat/stream`，请求体含 sessionId（可选）+ message（必填）+ enableThinking（可选，CR-001 新增）。
- **系统行为**：
  1. 校验 sessionId，无效则新建并发送 `session` 事件
  2. 记录用户消息到 ChatMemory
  3. 根据 `enableThinking` 分流：
     - true：走 `SimpleAgent.chatThinkingStream()`，推送 `reasoning` + `token` 事件
     - false/null：走 `BaseAgent.chatStream()`，推送 `token` 事件
  4. 流式完成后记录助手回复到 ChatMemory，发送 `done` 事件
- **SSE 事件协议**：session（新建会话时）/ reasoning（推理片段，CR-001 新增）/ token（文本片段）/ done（完成）/ error（异常）。
- **前置条件**：message 不能为空（`@NotBlank`）。
- **后置结果**：SSE 事件流，客户端逐字接收。
- **异常处理**：SSE 响应一旦开始写入，异常无法走 `@RestControllerAdvice`，需内部捕获并通过 `error` 事件通知前端。
- **v2.0 扩展**：AgentController 读取 `ChatRequest.model` 作为 modelId 透传给 SimpleAgent/PlanAgent（chat/chatStream/chatThinkingReActStream/chatTaskBreakdownStream），为空时使用默认模型。

### 3.9 厂商配置管理（CRUD，v2.0 新增）

- **触发场景**：管理 LLM 厂商及模型的动态配置。
- **操作步骤**：
  - `GET /api/llm/config/predefined`：获取系统内置的预定义厂商目录（前端"添加厂商"页面选项来源）。
  - `GET /api/llm/config/vendors`：获取已配置厂商列表（API Key 脱敏，保留前 3 位 + **** + 后 4 位）。
  - `POST /api/llm/config/vendors`：添加厂商（含模型列表），添加后清除模型工厂全部缓存。
  - `PUT /api/llm/config/vendors/{vendorId}`：编辑厂商（API Key 为空时保留原值），更新后清除该厂商模型缓存。
  - `DELETE /api/llm/config/vendors/{vendorId}`：删除厂商及其所有模型，删除后清除该厂商模型缓存。
- **业务规则**：厂商配置变更后必须清除模型工厂缓存（`modelFactory.clearAllCache/clearCacheForVendor`），确保新配置即时生效。

### 3.10 API Key 连接测试（v2.0 新增）

- **触发场景**：添加/编辑厂商前验证 API Key 与连通性。
- **操作步骤**：`POST /api/llm/config/test`，请求体含 baseUrl + apiKey。
- **系统行为**：使用 Java HttpClient 向 `{baseUrl}/models` 发送 GET（`Authorization: Bearer {apiKey}`，10s 超时），按响应状态码返回结果：
  - 200：连接成功
  - 401：API Key 无效（认证失败）
  - 404：服务端点不存在
  - 其他：服务端返回状态码
  - 异常：连接失败（含原因）
- **后置结果**：返回 `TestConnectionResponse`（success/message/latency）。

### 3.11 模型查询与配置状态（v2.0 新增）

- **触发场景**：查询已配置模型或获取 LLM 配置整体状态。
- **操作步骤**：
  - `GET /api/llm/config/models?type=`：遍历所有厂商模型，可选按 type（chat/embedding）过滤，返回含所属厂商信息的模型列表。
  - `GET /api/llm/config/status`：返回配置状态（hasConfig/vendorCount/hasChatModel/hasEmbeddingModel/chatModelCount），前端据此判断系统是否可用。

### 3.12 配置同步（v2.0 新增）

- **触发场景**：配置导入、批量更新。
- **操作步骤**：`POST /api/llm/config/sync`，请求体为完整的厂商配置列表。
- **系统行为**：`configStore.replaceAll()` 替换所有现有配置，同步后清除模型工厂全部缓存。

## 4. 业务流程串联

```mermaid
flowchart TD
    A[HTTP 请求] --> B[TraceIdInterceptor<br/>生成 traceId 存入 MDC]
    B --> C[AgentController]
    C --> D{参数校验 @Valid}
    D -->|失败| E[GlobalExceptionHandler<br/>返回 PARAM_INVALID]
    D -->|通过| F{sessionId 有效?}
    F -->|是| G[复用会话]
    F -->|否| H[createSession]
    G --> I[addUserMessage]
    H --> I
    I --> J[agent.chat ReAct 循环]
    J --> K[addAssistantMessage]
    K --> L[构造 ChatResponse]
    L --> M[返回 Result.success]
    J -->|异常| N[GlobalExceptionHandler<br/>转换错误码]
    N --> O[返回 Result.error]
```

**流程说明**：
1. 请求到达，TraceIdInterceptor 注入 traceId 到 MDC
2. Controller 接收请求，`@Valid` 校验参数
3. 校验失败由 GlobalExceptionHandler 处理
4. 校验通过后处理 sessionId（复用或新建）
5. 记录用户消息，调用 Agent 推理
6. 记录助手回复，构造 ChatResponse 返回
7. 任何异常由 GlobalExceptionHandler 统一转换

## 5. 安全与合规

- **参数校验**：`@Valid` + Bean Validation（如 `@NotBlank`）。
- **全局异常**：`@RestControllerAdvice` 统一拦截，避免堆栈泄露给调用方。
- **traceId 追踪**：每次请求生成唯一 traceId，便于审计。
- **Swagger 开关**：生产环境建议关闭（`springdoc.swagger-ui.enabled=false`）。
- **CORS 配置**：WebConfig 可配置跨域策略。

## 6. 前端入口

- **Swagger UI**：`http://localhost:8080/swagger-ui.html`
- **OpenAPI JSON**：`http://localhost:8080/v3/api-docs`
- **API Base Path**：`http://localhost:8080/api/agent/`

## 7. 核心数据实体

- **AgentController**：Agent 对话 Controller，提供 chat/session 接口，CR-001 扩展 chatStream 方法支持 enableThinking 分流 + reasoning 事件推送，v2.0 透传 modelId 给 SimpleAgent/PlanAgent。
- **LlmConfigController**（v2.0 新增）：LLM 配置管理 Controller（`@RequestMapping("/api/llm/config")`），提供厂商 CRUD、预定义厂商查询、API Key 连接测试、模型列表查询、配置状态查询与批量同步 9 个 API，依赖 `LlmConfigStore`/`PredefinedVendorCatalog`/`ModelFactory`。
- **ChatRequest**：对话请求 DTO，含 sessionId/message/enableThinking（CR-001 新增，Boolean 可选，默认 false）/model（v2.0 启用为 modelId，可选，为空使用第一个可用 chat 模型）。
- **ChatResponse**：对话响应 DTO，含 sessionId/response/toolCalls/duration/usage。
- **VendorRequest**（v2.0 新增）：厂商配置请求 DTO（添加/编辑），含 name/type/baseUrl/apiKey/thinkingTrigger/timeout/maxRetries/temperature/models。
- **VendorResponse**（v2.0 新增）：厂商响应 DTO，含 id/name/type/baseUrl/apiKeyMasked/apiKeyConfigured/thinkingTrigger/timeout/maxRetries/temperature/models。
- **ModelResponse**（v2.0 新增）：模型响应 DTO，含 id/vendorId/vendorName/modelName/displayName/type/supportsVision。
- **PredefinedVendorResponse**（v2.0 新增）：预定义厂商响应 DTO，含 code/name/baseUrl/thinkingTrigger/models。
- **TestConnectionRequest**（v2.0 新增）：连接测试请求 DTO，含 baseUrl/apiKey。
- **TestConnectionResponse**（v2.0 新增）：连接测试响应 DTO，含 success/message/latency。
- **SyncConfigRequest**（v2.0 新增）：同步配置请求 DTO，含 vendors 列表。
- **ConfigStatusResponse**（v2.0 新增）：配置状态响应 DTO，含 hasConfig/vendorCount/hasChatModel/hasEmbeddingModel/chatModelCount。
- **GlobalExceptionHandler**：全局异常处理器，统一异常转换。
- **TraceIdInterceptor**：链路追踪拦截器，注入 traceId 到 MDC。
- **WebConfig**：Web 配置，注册拦截器、CORS 等。
- **OpenApiConfig**：OpenAPI 文档配置。

## 8. API 接口清单

| 接口路径 | HTTP方法 | 功能说明 | 权限要求 | 请求参数 | 响应类型 |
|---------|---------|---------|---------|---------|---------|
| `/api/agent/chat` | POST | 同步对话 | 无 | ChatRequest | `Result<ChatResponse>` |
| `/api/agent/chat/stream` | POST | 流式对话（SSE） | 无 | ChatRequest（含 enableThinking，CR-001 扩展） | `SseEmitter`（text/event-stream） |
| `/api/agent/session` | POST | 创建会话 | 无 | 无 | `Result<String>` |
| `/api/agent/session/{sessionId}` | GET | 查询会话是否存在 | 无 | path: sessionId | `Result<Boolean>` |
| `/api/agent/session/{sessionId}/memory` | DELETE | 清空会话记忆 | 无 | path: sessionId | `Result<Void>` |
| `/api/llm/config/predefined` | GET | 获取预定义厂商目录（v2.0） | 无 | 无 | `Result<List<PredefinedVendorResponse>>` |
| `/api/llm/config/vendors` | GET | 获取已配置厂商列表（API Key 脱敏）（v2.0） | 无 | 无 | `Result<List<VendorResponse>>` |
| `/api/llm/config/vendors` | POST | 添加厂商（v2.0） | 无 | VendorRequest | `Result<VendorResponse>` |
| `/api/llm/config/vendors/{vendorId}` | PUT | 编辑厂商（v2.0） | 无 | path: vendorId + VendorRequest | `Result<VendorResponse>` |
| `/api/llm/config/vendors/{vendorId}` | DELETE | 删除厂商（v2.0） | 无 | path: vendorId | `Result<Void>` |
| `/api/llm/config/test` | POST | 测试 API Key 连接（v2.0） | 无 | TestConnectionRequest | `Result<TestConnectionResponse>` |
| `/api/llm/config/models` | GET | 获取模型列表（按类型过滤）（v2.0） | 无 | query: type（可选） | `Result<List<ModelResponse>>` |
| `/api/llm/config/status` | GET | 获取配置状态（v2.0） | 无 | 无 | `Result<ConfigStatusResponse>` |
| `/api/llm/config/sync` | POST | 批量同步配置（v2.0） | 无 | SyncConfigRequest | `Result<Void>` |

**ChatRequest 字段**：

| 字段 | 类型 | 必填 | 校验 | 说明 |
|------|------|------|------|------|
| sessionId | String | 否 | - | 为空则新建 |
| message | String | 是 | `@NotBlank`、`@Size(max=4000)` | 用户消息，上限 4000 字符（AC-015） |
| enableThinking | Boolean | 否 | - | 是否开启深度思考（CR-001 新增，默认 false） |
| model | String | 否 | - | 模型 ID（v2.0 启用为 modelId，可选，为空使用第一个可用 chat 模型） |

**ChatResponse 字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| sessionId | String | 会话 ID |
| response | String | Agent 回复 |
| toolCalls | List<ToolCallInfo> | 工具调用信息 |
| duration | long | 耗时（毫秒） |
| usage | Object | Token 使用统计 |

## 9. 业务规则

| 规则编号 | 规则描述 | 级别 |
|---------|---------|------|
| BR-WEB-001 | Controller 返回值必须用 `Result<T>` 包装 | 🔴 强制 |
| BR-WEB-002 | API 路径前缀统一 `/api/agent/` | 🔴 强制 |
| BR-WEB-003 | Controller 方法必须添加 `@Operation` OpenAPI 注解 | 🟡 尽量 |
| BR-WEB-004 | 入参 DTO 必须使用 `@Valid` + Bean Validation 校验 | 🔴 强制 |
| BR-WEB-005 | 全局异常通过 `@RestControllerAdvice` 统一拦截 | 🔴 强制 |
| BR-WEB-006 | 每次请求必须生成 traceId 注入 MDC | 🔴 强制 |
| BR-WEB-007 | 生产环境应关闭 Swagger UI 访问 | 🟡 尽量 |
| BR-WEB-008 | 传入无效 sessionId 时自动新建，不抛错 | 🔴 强制 |
| BR-WEB-009 | `NoResourceFoundException` 必须专门处理返回 404，禁止落入兜底 `Exception` 处理器导致 500 + 堆栈污染 | 🔴 强制 |
| BR-WEB-010 | SSE 流式响应一旦开始写入，内部异常必须通过 `error` 事件通知前端，无法走 `@RestControllerAdvice`（CR-001 新增） | 🔴 强制 |
| BR-WEB-011 | LLM 配置管理 API 路径统一前缀 `/api/llm/config/`（v2.0 新增） | 🔴 强制 |
| BR-WEB-012 | 厂商查询响应中 API Key 必须脱敏（保留前 3 位 + **** + 后 4 位），禁止明文泄露（v2.0 新增） | 🔴 强制 |
| BR-WEB-013 | 厂商配置变更（添加/编辑/删除/同步）后必须清除模型工厂缓存（`clearAllCache`/`clearCacheForVendor`），确保新配置即时生效（v2.0 新增） | 🔴 强制 |
| BR-WEB-014 | 对话请求 model 字段透传为 modelId，为空时使用默认模型（第一个可用 chat 模型）（v2.0 新增） | 🔴 强制 |

## 10. 异常处理

| 异常场景 | 错误码 | 提示信息 | 处理方式 |
|---------|-------|---------|---------|
| 参数校验失败 | 400 | 参数无效 | GlobalExceptionHandler 拦截 |
| 消息为空 | 400 | 消息内容不能为空 | `@NotBlank` 校验 |
| 资源/接口不存在 | 404 | 资源不存在 | `NoResourceFoundException` 专门处理，WARN 日志 |
| LLM 调用失败 | 5001 | LLM 调用失败 | BusinessException 转换 |
| LLM 超时 | 5002 | LLM 调用超时 | BusinessException 转换 |
| API Key 无效 | 5004 | LLM API Key 无效 | BusinessException 转换 |
| LLM 配置不存在 | 5008 | LLM 配置不存在 | BusinessException 转换（v2.0） |
| LLM 厂商不存在 | 5009 | LLM 厂商不存在 | BusinessException 转换（v2.0） |
| LLM 模型不存在 | 5010 | LLM 模型不存在 | BusinessException 转换（v2.0） |
| LLM 厂商名称已存在 | 5011 | LLM 厂商名称已存在 | BusinessException 转换（v2.0） |
| LLM 模型名称已存在 | 5012 | LLM 模型名称已存在 | BusinessException 转换（v2.0） |
| LLM 连接测试失败 | 5013 | LLM 连接测试失败 | BusinessException 转换（v2.0） |
| 无可用 chat 模型 | 5014 | 无可用 chat 模型 | BusinessException 转换（v2.0） |
| 无可用 embedding 模型 | 5015 | 无可用 embedding 模型 | BusinessException 转换（v2.0） |
| 工具执行失败 | 5100 | 工具执行失败 | BusinessException 转换 |
| 会话不存在 | 5201 | 会话不存在 | 自动新建会话 |
| 系统未知异常 | 5000 | 系统异常 | GlobalExceptionHandler 兜底 |

## 11. 性能要求

| 指标 | 要求 | 说明 |
|------|------|------|
| 接口响应时间 | < 60s | 受 LLM 响应时间影响 |
| SSE 超时 | 300s | Tomcat connection-timeout 配置 |
| 并发支持 | 100 QPS | 受 LLM 限流约束 |
| Swagger 加载 | < 1s | OpenAPI 文档生成 |
| traceId 注入 | < 1ms | 拦截器开销 |

## 12. 接口调用示例

### 12.1 创建会话

```bash
curl -X POST http://localhost:8080/api/agent/session
```

响应：

```json
{
  "code": 200,
  "data": "a1b2c3d4e5f6...",
  "msg": ""
}
```

### 12.2 同步对话

```bash
curl -X POST http://localhost:8080/api/agent/chat \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"","message":"现在几点？"}'
```

响应：

```json
{
  "code": 200,
  "data": {
    "sessionId": "a1b2c3d4e5f6...",
    "response": "现在是 2026年7月20日 14:30。",
    "toolCalls": [{"name": "TimeTool", "args": {}}],
    "duration": 2340,
    "usage": null
  },
  "msg": ""
}
```

### 12.3 清空会话记忆

```bash
curl -X DELETE http://localhost:8080/api/agent/session/a1b2c3d4e5f6/memory
```

---

**文档维护**：
- 新增接口时，补充到第 3 节业务功能点与第 8 节接口清单
- DTO 字段变更时，更新第 8 节字段说明
- 异常场景新增时，更新第 10 节异常处理

**变更日志**：
- v2.0（2026-08-11）：LLM 厂商模型配置迭代 — 新增 LlmConfigController（/api/llm/config 9 个 API）与 8 个 DTO（VendorRequest/VendorResponse/ModelResponse/PredefinedVendorResponse/TestConnectionRequest/TestConnectionResponse/SyncConfigRequest/ConfigStatusResponse）；ChatRequest.model 启用为 modelId 透传给 Agent 层；新增 4 条业务规则（BR-WEB-011~014）；补充 8 个错误码（5008-5015）
