# 阶段七：Web 接口层 任务执行计划

| 项 | 说明 |
|---|---|
| 阶段编号 | Phase-07 |
| 优先级 | P0（核心） |
| 状态 | 待执行 |
| 前置依赖 | 阶段一（工程骨架）、阶段二（common）、阶段五（memory）、阶段六（agent） |
| 被依赖 | 阶段八（bootstrap） |
| 验证粒度 | 阶段级（mvn compile + 接口测试） |

---

## 一、任务概述

实现 `agent-demo-web` 模块，提供 REST API 与 SSE 流式接口供外部调用 Agent。包含 DTO 定义、Controller、全局异常处理、Swagger 文档、Web 配置。本模块是对外暴露的入口层。

## 二、依赖关系

### 2.1 前置依赖
- **阶段一**：web 模块的 pom.xml 与包结构
- **阶段二**：使用 `Result`、`BusinessException`、`ErrorCode`
- **阶段五**：使用 `SessionManager`（会话管理）
- **阶段六**：使用 `BaseAgent` 接口（调用 chat / chatStream）

### 2.2 被依赖（下游影响）
- **阶段八（bootstrap）**：bootstrap 聚合 web 模块启动
- 本模块是对外接口层，DTO 与接口路径变更需通知前端调用方

## 三、任务清单

| 任务 ID | 任务 | 子任务数 | 产出物 |
|---|---|---|---|
| 7.1 | DTO 定义 | 2 | ChatRequest.java、ChatResponse.java |
| 7.2 | REST Controller | 1 | AgentController.java |
| 7.3 | SSE 流式接口 | 1 | 集成到 AgentController |
| 7.4 | 全局异常处理 | 1 | GlobalExceptionHandler.java |
| 7.5 | Swagger 配置 | 1 | OpenApiConfig.java |
| 7.6 | Web 配置 | 1 | WebConfig.java |
| 7.7 | 验证 | 1 | AgentControllerTest.java |

## 四、子任务详情

### 4.1 任务 7.1：DTO 定义

#### 子任务 7.1.1：ChatRequest
- **目标**：对话请求 DTO
- **字段设计**：
  ```java
  public class ChatRequest {
      private String sessionId;    // 会话 ID（可选，为空则新建）
      private String message;      // 用户消息（必填）
      private String agentType;    // Agent 类型（可选，默认 GENERAL）
      private String model;        // 指定模型（可选，为空用默认）
      private Map<String, Object> options; // 扩展参数
  }
  ```
- **校验**：
  - `@NotBlank(message = "消息内容不能为空")` message
  - `@Pattern` 校验 agentType 取值合法
- **调用方**：AgentController 接收请求

#### 子任务 7.1.2：ChatResponse
- **目标**：对话响应 DTO
- **字段设计**：
  ```java
  public class ChatResponse {
      private String sessionId;    // 会话 ID（回传，前端保存）
      private String content;      // 回复内容
      private String model;        // 实际使用的模型
      private long duration;       // 耗时（毫秒）
      private List<ToolCallInfo> toolCalls; // 工具调用记录
  }
  ```
- **ToolCallInfo 字段**：`toolName`、`args`、`result`、`duration`
- **调用方**：AgentController 返回结果

### 4.2 任务 7.2：REST Controller AgentController

- **目标**：提供同步对话 REST 接口
- **接口设计**：
  ```java
  @RestController
  @RequestMapping("/api/agent")
  public class AgentController {
      // 同步对话
      @PostMapping("/chat")
      public Result<ChatResponse> chat(@Valid @RequestBody ChatRequest request);

      // 流式对话（SSE）
      @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
      public Flux<ServerSentEvent<String>> stream(@Valid @RequestBody ChatRequest request);

      // 创建会话
      @PostMapping("/session")
      public Result<String> createSession();

      // 查询会话是否存在
      @GetMapping("/session/{sessionId}")
      public Result<Boolean> existsSession(@PathVariable String sessionId);

      // 清空会话记忆
      @DeleteMapping("/session/{sessionId}/memory")
      public Result<Void> clearMemory(@PathVariable String sessionId);
  }
  ```
- **实现要点**：
  - 注入 `BaseAgent`（按 agentType 路由，本阶段用 SimpleAgent）
  - 注入 `SessionManager` 处理会话
  - sessionId 为空时调用 `sessionManager.createSession()` 新建
  - 调用 `agent.chat(sessionId, message)` 获取响应
  - 封装 ChatResponse 返回
- **核心逻辑注释**：注明会话路由与 Agent 选择逻辑
- **接口路径规范**：统一 `/api/agent/*` 前缀

### 4.3 任务 7.3：SSE 流式接口

- **目标**：提供流式对话接口，实现 ChatGPT 式逐字输出
- **实现要点**：
  - produces: `MediaType.TEXT_EVENT_STREAM_VALUE`
  - 返回 `Flux<ServerSentEvent<String>>`
  - 调用 `agent.chatStream(sessionId, message)` 获取 Flux
  - 每个chunk 包装为 ServerSentEvent 返回
  - 流结束时发送 `[DONE]` 标记
  - **异常处理**：流中异常包装为 error 事件，不中断连接
- **调用方**：前端 EventSource 订阅
- **核心逻辑注释**：注明 SSE 协议与流式响应的业务意义

### 4.4 任务 7.4：全局异常处理 GlobalExceptionHandler

- **目标**：统一异常处理，返回标准 Result
- **实现要点**：
  ```java
  @RestControllerAdvice
  public class GlobalExceptionHandler {
      // 业务异常
      @ExceptionHandler(BusinessException.class)
      public Result<Void> handleBusiness(BusinessException e);

      // 参数校验异常
      @ExceptionHandler(MethodArgumentNotValidException.class)
      public Result<Void> handleValidation(MethodArgumentNotValidException e);

      // 未知异常
      @ExceptionHandler(Exception.class)
      public Result<Void> handleUnknown(Exception e);
  }
  ```
- **处理策略**：
  - BusinessException：返回对应 ErrorCode 的 code 与 message
  - 参数校验异常：返回 400 + 校验错误信息
  - 未知异常：返回 500 + "系统异常"，记录完整堆栈日志
  - 所有异常记录 traceId，便于追踪
- **核心逻辑注释**：注明异常分类与处理优先级

### 4.5 任务 7.5：Swagger 配置 OpenApiConfig

- **目标**：生成 API 文档
- **实现要点**：
  - 基于 springdoc-openapi 2.x
  - 配置 API 信息：标题、版本、描述
  - 配置分组：agent、session
  - 配置全局 Header（Authorization 预留）
- **访问地址**：`http://localhost:8080/swagger-ui.html`
- **调用方**：开发者联调

### 4.6 任务 7.6：Web 配置 WebConfig

- **目标**：CORS、拦截器、消息转换器配置
- **实现要点**：
  - **CORS**：允许所有源（开发环境），生产环境配置具体域名
  - **拦截器**：
    - `TraceIdInterceptor`：为每个请求生成 traceId 放入 MDC
    - `LoggingInterceptor`：记录请求响应日志
  - **消息转换器**：统一 UTF-8 编码，Jackson 配置
- **核心逻辑注释**：注明 CORS 与拦截器的业务用途

### 4.7 任务 7.7：验证

- **目标**：验证接口可用性
- **测试内容**：
  - `AgentControllerTest`：
    - 测试 `/api/agent/chat` 同步对话（Mock Agent）
    - 测试参数校验（message 为空返回 400）
    - 测试会话创建
  - `GlobalExceptionHandlerTest`：验证异常处理
- **验证命令**：`mvn test -pl agent-demo-web`

## 五、关键接口设计

### 5.1 REST 接口路径规范
| 方法 | 路径 | 功能 |
|---|---|---|
| POST | `/api/agent/chat` | 同步对话 |
| POST | `/api/agent/stream` | 流式对话（SSE） |
| POST | `/api/agent/session` | 创建会话 |
| GET | `/api/agent/session/{sessionId}` | 查询会话 |
| DELETE | `/api/agent/session/{sessionId}/memory` | 清空记忆 |

### 5.2 ChatRequest/ChatResponse 字段稳定性
- ChatRequest 字段变更需通知前端调用方
- ChatResponse 新增字段需向后兼容（前端忽略未知字段）

## 六、验证标准

| 验证项 | 验证方式 | 通过标准 |
|---|---|---|
| 编译 | `mvn clean compile -pl agent-demo-web` | BUILD SUCCESS |
| Swagger | 启动后访问 | UI 可访问，接口列表正确 |
| 同步接口 | curl 调用 | 返回 Result JSON，code=200 |
| 流式接口 | curl 调用 | 收到多个 SSE 事件 |
| 参数校验 | 空消息请求 | 返回 400 错误 |
| 异常处理 | 模拟异常 | 返回标准 Result 错误格式 |
| 会话管理 | 创建/查询 | 会话 ID 正确返回 |

## 七、风险与注意事项

1. **接口路径稳定性**：REST 路径一旦发布应保持稳定，变更需前端配合
2. **SSE 超时**：流式接口耗时较长，需配置较长的连接超时（建议 5 分钟）
3. **CORS 安全**：开发环境允许所有源，生产环境必须配置具体域名白名单
4. **参数校验**：所有入参必须校验，使用 `@Valid` + JSR303 注解
5. **日志脱敏**：请求日志中可能含用户敏感信息，需脱敏处理
6. **traceId 传递**：traceId 需贯穿整个请求链路（MDC + 响应头），便于问题定位
7. **响应统一性**：所有接口返回 `Result<T>`，避免直接返回裸数据
8. **流式异常**：SSE 流中异常不能直接抛出，需包装为 error 事件返回，避免连接中断
9. **会话校验**：chat 接口若传入 sessionId，需校验会话是否存在，不存在返回 404

## 八、执行顺序

```
7.1 DTO 定义（ChatRequest、ChatResponse）
   ↓
7.4 全局异常处理 GlobalExceptionHandler
   ↓
7.2 REST Controller AgentController（同步接口）
   ↓
7.3 SSE 流式接口（集成到 AgentController）
   ↓
7.6 Web 配置 WebConfig（CORS、拦截器）
   ↓
7.5 Swagger 配置 OpenApiConfig
   ↓
7.7 验证
```
