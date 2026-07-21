# 阶段三：LLM 接入层 任务执行计划

| 项 | 说明 |
|---|---|
| 阶段编号 | Phase-03 |
| 优先级 | P0（核心） |
| 状态 | 待执行 |
| 前置依赖 | 阶段一（工程骨架）、阶段二（common） |
| 被依赖 | 阶段五（memory）、阶段六（agent）、阶段七（web，间接） |
| 验证粒度 | 阶段级（mvn compile + 连通性测试） |

---

## 一、任务概述

实现 `agent-demo-llm` 模块，封装火山引擎方舟 Coding Plan 接入，提供模型工厂、统一调用接口、流式响应支持。本模块是 Agent 调用 LLM 的统一入口，需屏蔽火山引擎与 OpenAI 协议的差异。

## 二、依赖关系

### 2.1 前置依赖
- **阶段一**：llm 模块的 pom.xml 与包结构
- **阶段二**：使用 `ModelConstants`、`ErrorCode`、`BusinessException`、`JsonUtils`

### 2.2 被依赖（下游影响）
- **阶段五（memory）**：EmbeddingModel 用于长期记忆向量化
- **阶段六（agent）**：ChatModel 是 Agent 的"大脑"，ModelFactory 被 Agent 构建时调用
- **阶段七（web）**：间接依赖（通过 agent 层）
- **后续 RAG 模块**：EmbeddingModel 用于文档向量化
- 若 ModelFactory 接口签名变更，需同步评估 agent 层、memory 层、rag 层调用方

## 三、任务清单

| 任务 ID | 任务 | 子任务数 | 产出物 |
|---|---|---|---|
| 3.1 | 配置类实现 | 1 | ArkProperties.java |
| 3.2 | 模型工厂实现 | 1 | ModelFactory.java |
| 3.3 | ChatModel 封装 | 1 | ArkChatModel.java |
| 3.4 | EmbeddingModel 封装 | 1 | ArkEmbeddingModel.java |
| 3.5 | 流式调用支持 | 1 | 流式方法集成到 ArkChatModel |
| 3.6 | 连通性验证 | 1 | LlmConnectionTest.java |

## 四、子任务详情

### 4.1 任务 3.1：配置类 ArkProperties

- **目标**：绑定 application.yml 中 `ark.coding-plan.*` 配置
- **实现要点**：
  - 使用 `@ConfigurationProperties(prefix = "ark.coding-plan")`
  - 字段：`baseUrl`、`apiKey`、`defaultModel`、`models`（Map）、`timeout`、`maxRetries`、`temperature`
  - models 字段为 Map<String, String>，支持按场景配置多个模型
- **配置示例**（对应 application.yml）：
  ```yaml
  ark:
    coding-plan:
      base-url: https://ark.cn-beijing.volces.com/api/coding/v3
      api-key: ${ARK_API_KEY}
      default-model: doubao-seed-2.0-code
      models:
        chat: doubao-seed-2.0-pro
        code: doubao-seed-2.0-code
        lite: doubao-seed-2.0-lite
      timeout: 60s
      max-retries: 3
      temperature: 0.7
  ```
- **调用方**：ModelFactory、ArkChatModel、ArkEmbeddingModel

### 4.2 任务 3.2：模型工厂 ModelFactory

- **目标**：按场景返回对应的 ChatModel/EmbeddingModel，支持多模型路由
- **接口设计**：
  ```java
  public class ModelFactory {
      // 按场景获取对话模型（chat/code/lite 等）
      public ChatLanguageModel getChatModel(String scene);
      // 获取默认对话模型
      public ChatLanguageModel getDefaultChatModel();
      // 获取流式对话模型
      public StreamingChatLanguageModel getStreamingChatModel(String scene);
      // 获取 Embedding 模型
      public EmbeddingModel getEmbeddingModel();
  }
  ```
- **实现要点**：
  - 使用 `@Component`，注入 ArkProperties
  - 内部缓存模型实例（避免重复创建，模型对象线程安全可复用）
  - scene 参数对应 models Map 的 key，未命中时回退到 defaultModel
  - 模型创建失败抛出 `BusinessException(ErrorCode.LLM_CALL_FAILED)`
- **调用方**：agent 层（构建 AiServices 时）、memory 层（Embedding）、rag 层（Embedding）
- **接口变更影响**：新增/删除参数需同步 agent 层、memory 层、rag 层

### 4.3 任务 3.3：ChatModel 封装 ArkChatModel

- **目标**：封装火山引擎 ChatModel，处理 OpenAI 兼容协议适配
- **实现要点**：
  - 基于 `OpenAiChatModel.builder()`
  - baseUrl: ArkProperties.baseUrl
  - apiKey: ArkProperties.apiKey
  - modelName: 按场景从 models Map 获取
  - timeout: 从配置读取
  - temperature: 从配置读取
- **火山引擎特有能力**：
  - 深度思考 `thinking` 字段：通过 `customHeaders` 或 LangChain4j 的扩展参数传递
  - 自定义 request id：通过 `extraHeaders` 传递，用于日志串联
- **核心逻辑注释**：模型构建逻辑需注明为何这样配置（如 baseUrl 指向 coding plan 而非标准 api/v3，原因是按次计费）

### 4.4 任务 3.4：EmbeddingModel 封装 ArkEmbeddingModel

- **目标**：封装火山引擎 Embedding 模型，用于 RAG 与长期记忆
- **实现要点**：
  - 基于 `OpenAiEmbeddingModel.builder()`
  - modelName: `ModelConstants.MODEL_DOUBAO_EMBEDDING`
  - 与 ChatModel 共用 baseUrl 与 apiKey
- **调用方**：memory 层（长期记忆向量化）、rag 层（文档向量化）

### 4.5 任务 3.5：流式调用支持

- **目标**：支持 SSE 流式输出，实现 ChatGPT 式逐字返回
- **实现要点**：
  - 基于 `OpenAiStreamingChatModel.builder()`
  - 返回 `Flux<String>` 或 LangChain4j 的 `StreamingResponseHandler`
  - 流式模型实例同样在 ModelFactory 中缓存
- **调用方**：agent 层的 `chatStream` 方法、web 层的 SSE 接口

### 4.6 任务 3.6：连通性验证

- **目标**：验证火山引擎 API 可达性与模型调用正确性
- **测试内容**：
  - 创建 `LlmConnectionTest` 单元测试
  - 测试默认模型调用：发送 "Hello"，验证返回非空
  - 测试流式调用：验证 Flux 可订阅
  - 测试 Embedding：验证向量维度正确
- **前置条件**：环境变量 `ARK_API_KEY` 已配置
- **验证命令**：`mvn test -pl agent-demo-llm -Dtest=LlmConnectionTest`

## 五、关键接口设计

### 5.1 ModelFactory 接口签名（核心，影响多个调用方）
```java
public ChatLanguageModel getChatModel(String scene);
public StreamingChatLanguageModel getStreamingChatModel(String scene);
public EmbeddingModel getEmbeddingModel();
```
**调用方枚举**：
- agent 层 `SimpleAgent`：调用 getChatModel / getStreamingChatModel
- memory 层 `LongTermMemory`：调用 getEmbeddingModel
- rag 层 `RagService`：调用 getEmbeddingModel

### 5.2 配置项与 application.yml 映射
- `ark.coding-plan.base-url` -> ArkProperties.baseUrl
- `ark.coding-plan.api-key` -> ArkProperties.apiKey（从环境变量 ARK_API_KEY 注入）
- `ark.coding-plan.models.*` -> ArkProperties.models Map

## 六、验证标准

| 验证项 | 验证方式 | 通过标准 |
|---|---|---|
| 编译 | `mvn clean compile -pl agent-demo-llm` | BUILD SUCCESS |
| 配置绑定 | 启动日志 | ArkProperties 加载成功，字段非空 |
| LLM 连通性 | LlmConnectionTest | 调用返回非空内容 |
| 流式调用 | 单元测试 | Flux 可订阅，收到多个 chunk |
| Embedding | 单元测试 | 返回向量维度正确 |
| 异常处理 | 模拟错误 API Key | 抛出 BusinessException(LLM_CALL_FAILED) |

## 七、风险与注意事项

1. **API Key 安全**：严格通过环境变量 `ARK_API_KEY` 注入，**禁止写入代码或配置文件**，bootstrap 的 application.yml 用 `${ARK_API_KEY}` 占位
2. **Base URL 正确性**：必须使用 `https://ark.cn-beijing.volces.com/api/coding/v3`（Coding Plan 专用），误用 `/api/v3` 会导致按 Token 计费而非套餐
3. **模型实例缓存**：ChatLanguageModel 对象创建成本较高且线程安全，应在 ModelFactory 中缓存复用，避免每次调用都新建
4. **超时配置**：火山引擎响应可能较慢，timeout 建议至少 60s，并配置 maxRetries=3
5. **模型名变更**：火山引擎可能更新模型名，需通过 ModelConstants 统一管理，便于全局替换
6. **深度思考字段**：doubao-seed-2.0 支持 `thinking` 字段控制深度思考，需通过 extra_body 传递，注意 LangChain4j OpenAI 适配器的支持方式
7. **限频**：Coding Plan Lite 套餐有 5 小时 1200 次限制，测试时避免高频调用

## 八、执行顺序

```
3.1 配置类 ArkProperties
   ↓
3.3 ChatModel 封装 ArkChatModel
   ↓
3.4 EmbeddingModel 封装 ArkEmbeddingModel
   ↓
3.5 流式调用支持
   ↓
3.2 模型工厂 ModelFactory（整合上述模型）
   ↓
3.6 连通性验证
```
