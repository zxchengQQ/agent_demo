# LLM 接入模块 业务说明书

## 1. 模块概述

LLM 接入模块（agent-demo-llm）是 AI Agent 示例项目的 LLM 能力提供模块，负责 **LLM 厂商与模型配置的动态管理**、模型实例的统一创建与按需复用。模块通过 LangChain4j OpenAI 适配器对接各厂商 OpenAI 兼容协议，屏蔽底层差异，对上层提供 ChatModel / StreamingChatModel / EmbeddingModel / ThinkingStreamingChatModel / VisionChatModel 五类模型实例。

**v3.0 动态配置模式架构**：模块彻底移除 CR-002 的 Provider（提供商策略）模式，改为「内存配置存储 + 动态模型工厂」架构（方案 D）。LLM 厂商与模型配置不再来自静态 `application.yml`，而是由用户在前端「LLM 配置」页面动态录入，保存到后端内存 `LlmConfigStore`（唯一事实来源）与前端 `localStorage`。`ModelFactory` 从 `LlmConfigStore` 读取配置、按 `vendorId:modelName` 缓存复用模型实例，支持 **多厂商同时配置、按 modelId 选择模型、配置即生效无需重启**。系统内置 5 个预定义厂商（火山引擎方舟/阿里百炼/OpenAI/DeepSeek/Ollama）模板供快速接入，同时支持自定义厂商。

**关键架构差异**（相对 CR-002）：
- 移除 `provider` 包（`LlmServiceProvider` / `ArkLlmServiceProvider` / `BailianLlmServiceProvider`）与 `capability` 包 5 个能力接口（`ChatModelProvider` / `StreamingChatModelProvider` / `EmbeddingModelProvider` / `VisionChatModelProvider` / `ThinkingStreamingChatModelProvider`）
- 删除 config 包旧类：`LlmProperties`、`ArkProperties`、`BailianProperties`、`LlmProvider`、`LlmProviderConfig`、`LlmConfig`
- 新增 config 包：`LlmConfigStore`、`LlmVendorConfig`、`LlmModelConfig`、`PredefinedVendorCatalog`、`PredefinedVendor`、`PredefinedModel`
- `ModelFactory` 构造器由 `(LlmProperties, List<LlmServiceProvider>)` 简化为 `(LlmConfigStore)`
- 保留 `thinking` 包（思考流式模型）与 `exception` 包 `UnsupportedCapabilityException`
- LLM 配置从 `application.yml` 环境变量注入 API Key 改为前端动态配置（API Key 由用户在前端录入并脱敏显示）

## 2. 用户角色与权限

| 角色 | 权限范围 | 典型操作 |
|------|---------|---------|
| **配置管理员** | 管理 LLM 厂商、模型与 API Key（v3.0 新增，当前项目无认证机制，所有访问者均可担任） | 在「LLM 配置」页面添加/编辑/删除厂商、配置模型、录入 API Key、测试连接；选择预定义厂商或录入自定义厂商 |
| **对话用户** | 调用 Agent 对话、选择模型 | 在对话界面模型选择器中按会话维度选择 chat 模型；在未配置模型时按引导进入配置 |
| **学习者** | 调用 Agent 间接受益 | 无需直接操作 LLM 模块 |
| **开发者** | 扩展模型接入（仅预定义厂商模板场景） | 在 `PredefinedVendorCatalog` 中新增预定义厂商/模型模板；动态配置模式下新增运行时厂商无需改代码 |
| **运维者** | 管理部署环境 | 移除环境变量 `ARK_API_KEY` / `BAILIAN_API_KEY` 依赖（API Key 改由前端配置，环境变量不再被读取） |

> **说明**：配置管理员与对话用户可能是同一个人（学习/演示场景）。配置操作面向所有访问者开放（项目无认证机制）。

## 3. 业务功能点

### 3.1 厂商配置管理（v3.0 新增）

- **触发场景**：配置管理员在「LLM 配置」页面管理厂商，或后端重启后前端恢复配置。
- **操作步骤**：前端通过 REST API 调用 `LlmConfigStore` 的 CRUD 方法（`addVendor` / `updateVendor` / `deleteVendor` / `getVendor` / `getAllVendors` / `replaceAll`）。
- **系统行为**：
  1. 添加厂商：校验名称非空且全局唯一、Base URL 非空、API Key 非空，生成 vendorId 与模型 ID，存入 `ConcurrentHashMap<String, LlmVendorConfig>`
  2. 编辑厂商：API Key 为空时保留原 Key（不覆盖），非空时更新；校验同厂商下同类型模型名称唯一
  3. 删除厂商：删除厂商及其所有模型配置，级联清除对应模型实例缓存
  4. 同步配置（sync）：清空当前配置后 `replaceAll` 批量导入，用于后端重启后从 localStorage 恢复
- **业务规则**：遵循 BR-LLM-CONF-001~004、BR-LLM-CONF-009、BR-LLM-CONF-014、BR-LLM-CONF-015。
- **前置条件**：厂商名称 / Base URL / API Key 均非空。
- **后置结果**：配置写入内存，变更即时生效（ModelFactory 缓存被清空，下次请求用新配置）。

### 3.2 预定义厂商目录（v3.0 新增）

- **触发场景**：配置管理员添加厂商时选择预定义模板。
- **操作步骤**：前端调用 `GET /api/llm/config/predefined` 获取 `PredefinedVendorCatalog.getPredefinedVendors()`。
- **系统行为**：返回 5 个预定义厂商（火山引擎方舟/阿里百炼/OpenAI/DeepSeek/Ollama），各厂商自带默认 Base URL、thinkingTrigger 与常用模型清单；Ollama 为本地部署（模型清单为空，用户自行录入）。
- **业务规则**：遵循 BR-LLM-CONF-018（预定义厂商目录）、BR-LLM-CONF-003（预定义厂商自动填充 Base URL）。

### 3.3 测试 API Key 连接（v3.0 新增）

- **触发场景**：保存厂商配置前验证 API Key 有效性。
- **操作步骤**：前端调用 `POST /api/llm/config/test`，向 `{baseUrl}/models` 发送 GET 请求（Header: `Authorization: Bearer {apiKey}`）。
- **系统行为**：HTTP 200 → 连接成功；HTTP 401 → API Key 无效；HTTP 404 → Base URL 错误或端点不支持；超时/网络异常 → 连接失败并提示具体原因。测试连接不缓存任何模型实例、不保存配置。
- **业务规则**：遵循 BR-LLM-CONF-007（保存前必须通过测试连接）。测试连接使用独立的 `GET /models` 请求，不消耗 Token、不缓存模型实例。

### 3.4 同步对话模型获取（按 modelId）

- **触发场景**：Agent 构建 AiServices 时调用 `ModelFactory.getChatModelByModelId(modelId)` 或 `getDefaultChatModel()`。
- **操作步骤**：按 `modelId` 从 `LlmConfigStore.getModel(modelId)` 查找模型配置 → 校验类型为 chat → 取对应厂商配置 → 以 `vendorId:modelName` 为 key 从 `chatModelCache` 取缓存，未命中则创建并缓存。
- **系统行为**：基于 `OpenAiChatModel.builder()` 构建（baseUrl/apiKey/modelName/temperature/timeout/maxRetries 均取自 `LlmVendorConfig`）。
- **前置条件**：已配置至少一个 chat 类型模型；指定的 modelId 存在且类型为 chat。
- **后置结果**：返回缓存的 ChatModel 实例。
- **异常**：modelId 不存在或类型非 chat → 抛 `LLM_MODEL_NOT_FOUND`(5010)；无任何 chat 模型（默认获取）→ 抛 `LLM_NO_CHAT_MODEL`(5014)。

### 3.5 流式对话模型获取

- **触发场景**：SSE 流式输出场景（对话请求携带 modelId）。
- **操作步骤**：`ModelFactory.getStreamingChatModelByModelId(modelId)` 或 `getDefaultStreamingChatModel()` → 按 modelId 查配置 → 从 `streamingModelCache` 取缓存/创建。
- **系统行为**：基于 `OpenAiStreamingChatModel.builder()` 构建流式模型。
- **业务规则**：流式与非流式模型分别构建、独立缓存（沿用 BR-LLM-004 语义，缓存 key 为 `vendorId:modelName`）。

### 3.6 Embedding 模型获取

- **触发场景**：RAG 文档向量化、长期记忆向量化。
- **操作步骤**：`ModelFactory.getEmbeddingModel()` → `LlmConfigStore.getFirstEmbeddingModel()` 获取第一个 embedding 类型模型 → 从 `embeddingModelCache` 取缓存/创建。
- **系统行为**：基于 `OpenAiEmbeddingModel.builder()` 构建，模型名取自动态配置（替代原 Ark/BAILIAN 硬编码的豆包/text-embedding-v4）。
- **异常**：无 embedding 模型 → 抛 `LLM_NO_EMBEDDING_MODEL`(5015)，RAG 阻止文档上传并提示配置（BR-LLM-CONF-017）。

### 3.7 思考流式模型获取（v3.0 按 thinkingTrigger 选择实现类）

- **触发场景**：Agent 思考流式对话（enableThinking=true）时调用 `ModelFactory.getThinkingStreamingChatModelByModelId(modelId)` 或 `getDefaultThinkingStreamingChatModel()`。
- **操作步骤**：按 modelId 查配置 → 从 `thinkingModelCache` 取缓存/创建。
- **系统行为**：
  1. 按 vendor 配置的 `thinkingTrigger` 选择实现类（`createThinkingStreamingChatModel`）：
     - `enabled` → `ArkThinkingStreamingChatModel`（请求体含 `thinking.type=enabled`，火山引擎风格）
     - `none` → `BailianThinkingStreamingChatModel`（模型名称自身触发思考，阿里百炼风格）
  2. 子类仅实现 `customizeRequestBody` 钩子，通用 SSE 解析/HTTP 调用逻辑由 `AbstractThinkingStreamingChatModel` 基类提供
  3. 按 `vendorId:modelName` 缓存复用
- **业务规则**：遵循 BR-LLM-004（缓存复用）、BR-LLM-007（不走 openai4j）、BR-LLM-016（代码重复率 ≤ 30%）。
- **技术债务**：实现类仍以 Ark/Bailian 命名，在非对应厂商场景下有语义误导，后续可重命名为 `EnabledThinkingStreamingChatModel` / `PassthroughThinkingStreamingChatModel`。

### 3.8 视觉对话模型获取（v3.0 改为 supportsVision 属性检测）

- **触发场景**：PDF 图像描述、视觉理解场景调用 `ModelFactory.getVisionChatModel()`。
- **操作步骤**：遍历 `LlmConfigStore.getAllVendors()`，查找第一个 `type=chat && supportsVision=true` 的模型 → 从 `chatModelCache` 取缓存/创建。
- **系统行为**：基于 `OpenAiChatModel.builder()` 构建视觉模型，能力由模型配置的 `supportsVision` 字段标记（替代 CR-002 的 `VisionChatModelProvider` 能力接口）。
- **异常**：无可用的支持视图理解的 chat 模型 → 抛 `LLM_MODEL_NOT_CONFIGURED`(5005)。
- **业务规则**：遵循 BR-LLM-CONF-006（仅 chat 类型支持视图理解标记）。

### 3.9 模型缓存管理（v3.0）

- **触发场景**：厂商配置变更（增/删/改）、同步配置。
- **操作步骤**：`ModelFactory.clearCacheForVendor(vendorId)` 清除指定厂商前缀的缓存，`clearAllCache()` 清空全部缓存。
- **系统行为**：配置变更后精准失效对应厂商缓存（不影响其他厂商），后续请求创建新配置的模型实例。
- **业务规则**：遵循 BR-LLM-CONF-020（缓存按厂商+模型名管理，配置变更时清除对应缓存）。

## 4. 业务流程串联

```mermaid
flowchart TD
    A[前端 LLM 配置页面] -->|POST /api/llm/config/vendors CRUD| Ctl[LlmConfigController]
    Ctl -->|addVendor/updateVendor/deleteVendor| Store[(LlmConfigStore 内存 ConcurrentHashMap)]
    Store -->|读取配置| MF[ModelFactory 从 LlmConfigStore 获取配置]
    MF -->|按 vendorId:modelName 缓存| Cache[(模型实例缓存]
    Store -->|配置变更| Clear[ModelFactory.clearCacheForVendor / clearAllCache]
    Clear --> Cache

    U[对话请求 携带 modelId] --> Agent[AgentController.chatStream<br>ChatRequest.model = modelId]
    Agent --> SA[SimpleAgent.getDelegate modelId<br>按 modelId 缓存 delegate]
    SA -->|getChatModelByModelId / getStreamingChatModelByModelId| MF
    MF -->|modelId 查 LlmConfigStore.getModel| Store
    MF -->|创建/获取 缓存| Cache
    Cache -->|返回模型实例| SA
    SA --> Use[供 AiServices 使用]

    RAG[DocumentService 向量化] -->|getEmbeddingModel| MF
    MF -->|getFirstEmbeddingModel| Store
    MF -->|无 embedding 模型| ErrEmb[抛 BusinessException 5015<br>LLM_NO_EMBEDDING_MODEL]
    MF -->|无 chat 模型| ErrChat[抛 BusinessException 5014<br>LLM_NO_CHAT_MODEL]
    MF -->|modelId 不存在| ErrModel[抛 BusinessException 5010<br>LLM_MODEL_NOT_FOUND]

    S[前端页面加载] -->|GET /api/llm/config/status| St{hasConfig?}
    St -->|true| Pull[GET /api/llm/config/vendors 拉取最新配置]
    St -->|false| LS{localStorage 有配置?}
    LS -->|有| Sync[POST /api/llm/config/sync 推送恢复]
    LS -->|无| Empty[显示空状态 引导首次配置]
```

**流程说明**（v3.0 动态配置模式）：
1. **配置流向**：前端配置页面 → REST API → `LlmConfigStore`（内存）→ `ModelFactory.clearCacheForVendor` → 后续请求使用新配置
2. **对话流向**：用户选择模型 → `ChatRequest.model(modelId)` → `SimpleAgent.getDelegate(modelId)` → `ModelFactory.getStreamingChatModelByModelId(modelId)` → 查 `LlmConfigStore` → 创建/获取缓存的模型实例 → SSE 流式返回
3. **RAG 向量化流向**：`DocumentService` → `ModelFactory.getEmbeddingModel()` → `LlmConfigStore.getFirstEmbeddingModel()` → 创建/获取缓存的 `OpenAiEmbeddingModel`
4. **重启恢复流向**：前端加载 → `GET /api/llm/config/status` → `hasConfig=false` → 前端读取 localStorage → `POST /api/llm/config/sync` → `LlmConfigStore.replaceAll` 恢复全部配置
5. ModelFactory 不再依赖 Provider 路由，直接从 LlmConfigStore 读取配置并创建模型实例

## 5. 安全与合规

- **API Key 保护（v3.0 变更）**：API Key 由用户在前端「LLM 配置」页面录入，明文存储在后端内存中（与原有环境变量注入方式等效）；API Key 在 API 响应中 **脱敏显示**（`apiKeyMasked` 保留后 4 位，如 `sk-****7890`）；禁止明文打印到日志（遵循 BR-SEC-001）。
- **API Key 脱敏规则**：前端界面展示已保存的 Key 时一律脱敏，编辑时需重新输入完整 Key（BR-LLM-CONF-008）。
- **localStorage 存储**：前端 localStorage 存储完整 API Key（浏览器本地存储，仅同步时通过 API 传输）。
- **环境变量变更**：`ARK_API_KEY` / `BAILIAN_API_KEY` 环境变量不再被读取（对应 Properties 类已删除）；若用户仍设置不影响功能。
- **Coding Plan 地址**：预定义厂商火山引擎方舟使用 `/api/coding/v3` 按次计费（保存在 `PredefinedVendorCatalog` 模板中）。
- **阿里百炼协议地址**：预定义厂商阿里百炼使用 `/compatible-mode/v1` 路径（模板内置）。
- **模型名常量化**：预定义模型名统一维护在 `PredefinedVendorCatalog` / `PredefinedModel`，禁止调用方硬编码。
- **测试连接安全**：测试连接使用独立的 HTTP 请求（GET /models），不缓存任何模型实例、不保存配置。
- **能力缺失显式报错**：无支持视图理解的 chat 模型时 `getVisionChatModel` 必须抛 `LLM_MODEL_NOT_CONFIGURED`，禁止隐式失败（沿用 BR-LLM-017 语义，异常改为 `BusinessException`）。

## 6. 前端入口

本模块通过 agent-demo-web 暴露配置管理 REST API，并通过 agent-demo-frontend 提供「LLM 配置」页面与对话模型选择器：
- **LLM 配置页面**：厂商卡片列表（名称/类型/Base URL/模型摘要/API Key 状态）、添加/编辑厂商弹窗（预定义选择 + API Key + 测试连接 + 模型配置）、删除确认
- **对话模型选择器**：按厂商分组展示 chat 模型，标注「支持视图」标记，按会话维度保持选择状态
- **前端状态管理**：配置通过 llm store 管理，每次 CRUD 操作后写入 localStorage（`agent-demo:llm-config`）；对话模型选择通过 session store 的 `modelBySession` 按会话隔离

## 7. 核心数据实体

### 7.1 编排层（registry 包）

- **ModelFactory**：模型工厂，v3.0 重构后仅持有 `LlmConfigStore configStore`（构造器注入），不再持有 `LlmProperties` 与 `providerRegistry`。内部维护 4 个 `ConcurrentHashMap` 缓存（`chatModelCache` / `streamingModelCache` / `thinkingModelCache` / `embeddingModelCache`），缓存 key 均为 `vendorId:modelName`。提供方法：`getChatModelByModelId` / `getDefaultChatModel` / `getStreamingChatModelByModelId` / `getDefaultStreamingChatModel` / `getThinkingStreamingChatModelByModelId` / `getDefaultThinkingStreamingChatModel` / `getEmbeddingModel` / `getVisionChatModel` / `clearCacheForVendor` / `clearAllCache`。私有创建方法 `createChatModel` / `createStreamingChatModel` / `createThinkingStreamingChatModel` / `createEmbeddingModel` 从 `LlmVendorConfig` 读取连接与请求参数构建实例。

### 7.2 配置层（config 包，v3.0 全新）

- **LlmConfigStore**：LLM 配置内存存储（`@Component`），**配置唯一事实来源**。内部为 `ConcurrentHashMap<String, LlmVendorConfig> vendors`。提供 CRUD 与查询：`addVendor` / `updateVendor` / `deleteVendor` / `getVendor` / `getAllVendors` / `getModel` / `getFirstChatModel` / `getFirstEmbeddingModel` / `hasConfig` / `clear` / `replaceAll`。内置私有校验：厂商字段非空、厂商名称全局唯一、同厂商同类型模型名唯一、模型名非空。
- **LlmVendorConfig**：厂商配置实体（`@Data`）。字段：`id`（UUID 去横线）、`name`（显示名称，全局唯一）、`type`（predefined/custom）、`baseUrl`、`apiKey`（明文存储内存，响应时脱敏）、`thinkingTrigger`（enabled/none）、`timeout`（默认 60s）、`maxRetries`（默认 3）、`temperature`（默认 0.7）、`models`（`List<LlmModelConfig>`）。
- **LlmModelConfig**：模型配置实体（`@Data`）。字段：`id`（UUID 去横线）、`vendorId`（所属厂商）、`modelName`（API 模型名）、`displayName`（显示名）、`type`（chat/embedding/rerank/multimodal）、`supportsVision`（是否支持视图理解，仅 chat 类型有意义）。
- **PredefinedVendorCatalog**：预定义厂商目录（`@Component`），静态不可变列表，含 5 个预定义厂商模板（火山引擎方舟/阿里百炼/OpenAI/DeepSeek/Ollama）。提供 `getPredefinedVendors()`。
- **PredefinedVendor**：预定义厂商模板（`@Data`）。字段：`code`、`name`、`baseUrl`、`thinkingTrigger`、`models`（`List<PredefinedModel>`）。
- **PredefinedModel**：预定义模型模板（`@Data`）。字段：`modelName`、`displayName`、`type`、`supportsVision`。

### 7.3 能力契约层（capability 包）

**v3.0 已删除**：`ChatModelProvider` / `StreamingChatModelProvider` / `ThinkingStreamingChatModelProvider` / `EmbeddingModelProvider` / `VisionChatModelProvider` 5 个能力接口随 Provider 模式一并移除。能力差异改由 `LlmModelConfig` 的 `type` 与 `supportsVision` 字段表达（如视觉能力由 `supportsVision=true` 的 chat 模型承担）。

### 7.4 厂商策略层（provider 包）

**v3.0 已删除**：`LlmServiceProvider` / `ArkLlmServiceProvider` / `BailianLlmServiceProvider` 随 Provider 模式移除。厂商由 `LlmVendorConfig` 动态配置表达，`ModelFactory` 直接从 `LlmConfigStore` 读取配置创建模型实例。

### 7.5 思考流式模型层（thinking 包，v3.0 保留）

- **ThinkingStreamingChatModel**：思考流式核心接口（stream 方法契约）。
- **AbstractThinkingStreamingChatModel**：模板方法基类，上提 SSE 解析、HTTP 调用、回调分发、消息转换、tools 处理逻辑。子类仅实现 `customizeRequestBody(ObjectNode)` 钩子。
- **ArkThinkingStreamingChatModel**：`thinkingTrigger=enabled` 场景实现（请求体添加 `thinking.type=enabled` 字段）。
- **BailianThinkingStreamingChatModel**：`thinkingTrigger=none` 场景实现（模型名称自身触发思考能力，钩子为空实现）。
- **ThinkingStreamHandler**：思考流式回调接口（CR-001 新增），定义 onPartialThinking/onPartialResponse/onComplete/onError 四个回调方法。
- **ToolCall**：工具调用数据结构。

### 7.6 异常层（exception 包）

- **UnsupportedCapabilityException**：能力不支持异常（CR-002 新增，v3.0 保留），继承 `BusinessException`，错误码 `LLM_CAPABILITY_NOT_SUPPORTED(5007)`，携带 `providerCode` 和 `capabilityName` 字段。v3.0 视觉能力缺失改由 `LLM_MODEL_NOT_CONFIGURED`(5005) 表达，此异常主要用于显式能力缺失场景。

## 8. API 接口清单

本模块为内部能力层，通过 agent-demo-web 暴露 LLM 配置管理 REST API（`/api/llm/config/*`），并提供以下内部方法（v3.0 重构后签名变更，新增 ByModelId 方法）：

### 8.1 配置管理 REST API（v3.0 新增）

| 接口名称 | 方法 | 路径 | 功能说明 |
|------|---------|--------|---------|
| 获取预定义厂商目录 | GET | /api/llm/config/predefined | 返回预定义厂商列表及常用模型 |
| 获取已配置厂商列表 | GET | /api/llm/config/vendors | 返回所有已配置厂商（API Key 脱敏） |
| 添加厂商 | POST | /api/llm/config/vendors | 添加厂商配置（含模型列表） |
| 编辑厂商 | PUT | /api/llm/config/vendors/{vendorId} | 修改厂商配置（API Key 为空保留原值） |
| 删除厂商 | DELETE | /api/llm/config/vendors/{vendorId} | 删除厂商及其所有模型，级联清除缓存 |
| 测试 API Key 连接 | POST | /api/llm/config/test | 验证 API Key 有效性（GET /models） |
| 获取模型列表 | GET | /api/llm/config/models?type=chat | 获取模型列表（可按类型过滤） |
| 获取配置状态 | GET | /api/llm/config/status | 返回 hasConfig/hasChatModel/hasEmbeddingModel |
| 同步配置 | POST | /api/llm/config/sync | 前端推送完整配置到后端（重启恢复） |

### 8.2 内部方法（ModelFactory）

| 方法 | 功能说明 | 调用方 | v3.0 变更 |
|------|---------|--------|------------|
| `ModelFactory.getChatModelByModelId(modelId)` | 按模型 ID 获取对话模型 | agent 层 | 新增（替代 getChatModel(scene)） |
| `ModelFactory.getDefaultChatModel()` | 获取默认对话模型（第一个 chat 模型） | agent 层 | 实现改为从 LlmConfigStore 读取 |
| `ModelFactory.getStreamingChatModelByModelId(modelId)` | 按模型 ID 获取流式模型 | web 层（SSE） | 新增 |
| `ModelFactory.getDefaultStreamingChatModel()` | 获取默认流式模型 | web 层 | 实现改为从 LlmConfigStore 读取 |
| `ModelFactory.getThinkingStreamingChatModelByModelId(modelId)` | 按模型 ID 获取思考流式模型 | agent 层（chatThinkingStream） | 新增 |
| `ModelFactory.getDefaultThinkingStreamingChatModel()` | 获取默认思考流式模型 | agent 层 | 新增 |
| `ModelFactory.getEmbeddingModel()` | 获取 Embedding 模型（第一个 embedding 模型） | rag/memory 层 | 实现改为从 LlmConfigStore 读取 |
| `ModelFactory.getVisionChatModel()` | 获取视觉对话模型（supportsVision=true 的第一个 chat 模型） | rag/splitter 层 | 由能力接口检测改为 supportsVision 属性检测 |
| `ModelFactory.clearCacheForVendor(vendorId)` | 清除指定厂商的模型缓存 | web 层（配置变更） | 新增 |
| `ModelFactory.clearAllCache()` | 清除全部模型缓存 | web 层（sync） | 新增 |

## 9. 业务规则

### 9.1 基础规则（BR-LLM-XXX）

| 规则编号 | 规则描述 | 级别 |
|---------|---------|------|
| BR-LLM-001 | API Key 必须由用户在前端「LLM 配置」页面录入，明文存储于后端内存，API 响应时脱敏显示，禁止明文打印到日志（v3.0 更新：原环境变量 `ARK_API_KEY` / `BAILIAN_API_KEY` 注入改为前端动态配置） | 🔴 强制 |
| BR-LLM-002 | 火山引擎必须使用 Coding Plan 专用地址 `/api/coding/v3`（v3.0 更新：维护在 `PredefinedVendorCatalog` 火山引擎模板中自动填充） | 🔴 强制 |
| BR-LLM-003 | 模型名称统一维护在 `PredefinedVendorCatalog` / `PredefinedModel` 预定义模板中，禁止调用方硬编码（v3.0 更新：原 `ModelConstants` 常量类引用改为预定义目录维护） | 🔴 强制 |
| BR-LLM-004 | 模型实例必须通过 `ModelFactory` 获取并缓存复用（v3.0 补充：缓存 key 为 `vendorId:modelName`，持有者变更但语义不变） | 🔴 强制 |
| BR-LLM-005 | 调用超时时间默认 60s | ⚪ 可覆盖 |
| BR-LLM-006 | 最大重试次数默认 3 次 | ⚪ 可覆盖 |
| BR-LLM-007 | 思考流式模型必须通过自定义 `AbstractThinkingStreamingChatModel` 实现类直连 API，不走 LangChain4j openai4j（因 openai4j 不透传 reasoning_content）（v3.0 更新：按厂商 `thinkingTrigger` 选择 Ark/Bailian 实现类）（CR-001 新增） | 🔴 强制 |
| BR-LLM-009 | 阿里百炼 API Key 由用户在前端「LLM 配置」页面录入（v3.0 更新：原环境变量 `BAILIAN_API_KEY` 注入改为前端配置，与 BR-LLM-001 一致） | 🔴 强制 |
| BR-LLM-010 | 每个厂商独立校验其 API Key，互不影响（v3.0 更新：多厂商同时配置，各自校验；原「切换提供商后只校验当前提供商的 API Key」） | 🔴 强制 |
| BR-LLM-011 | 阿里百炼必须使用 OpenAI 兼容协议地址 `/compatible-mode/v1`（v3.0 更新：维护在 `PredefinedVendorCatalog` 阿里百炼模板中自动填充） | 🔴 强制 |
| BR-LLM-012 | 阿里百炼支持深度思考：`BailianThinkingStreamingChatModel` 模型名称自身触发思考能力（v3.0 更新：`thinkingTrigger=none` 的厂商使用此实现类）（CR-002 修正） | 🔴 强制 |
| BR-LLM-013 | Embedding 模型跟随配置（v3.0 更新：使用 `LlmConfigStore.getFirstEmbeddingModel()` 获取第一个 embedding 类型模型，替代原 ARK 用 `doubao-embedding-vision`、BAILIAN 用 `text-embedding-v4` 的硬编码） | 🔴 强制 |
| BR-LLM-016 | `ArkThinkingStreamingChatModel` 与 `BailianThinkingStreamingChatModel` 代码重复率必须 ≤ 30%，通过继承 `AbstractThinkingStreamingChatModel` 实现（CR-002 新增，jscpd 检测） | 🔴 强制 |
| BR-LLM-017 | 能力缺失必须显式报错，禁止隐式失败（v3.0 更新：capability 接口已移除，视觉能力缺失由 `getVisionChatModel` 抛 `LLM_MODEL_NOT_CONFIGURED`(5005) 表达）（CR-002 新增） | 🔴 强制 |

> **v3.0 移除的规则**：BR-LLM-008（`llm.provider` 配置项已移除）、BR-LLM-014/015（Provider 模式相关，已不适用）、BR-LLM-018（能力接口 ISP 拆分，capability 包已移除）。配置源相关规则（BR-LLM-001/002/003/009/010/011/012/013）已更新为动态配置语义，或由 BR-LLM-CONF-XXX 规则替代。

### 9.2 动态配置规则（BR-LLM-CONF-XXX，v3.0 新增）

| 规则编号 | 规则描述 | 级别 |
|---------|---------|------|
| BR-LLM-CONF-001 | 厂商名称全局唯一，不允许重复（预定义厂商和自定义厂商统一校验） | 🔴 强制 |
| BR-LLM-CONF-002 | 每个厂商必须配置 API Key 才能保存（API Key 不可为空） | 🔴 强制 |
| BR-LLM-CONF-003 | 每个厂商必须配置 Base URL（预定义厂商自动填充，自定义厂商手动填写，均不可为空） | 🔴 强制 |
| BR-LLM-CONF-004 | 模型名称不可为空，同一厂商下同一类型的模型名称不可重复 | 🔴 强制 |
| BR-LLM-CONF-005 | 模型类型限定为四种：chat（对话）、embedding（向量化）、rerank（重排）、multimodal（多模态生图/视频） | 🔴 强制 |
| BR-LLM-CONF-006 | 仅 chat 类型模型支持「支持视图理解」标记，其他类型不显示此选项 | 🔴 强制 |
| BR-LLM-CONF-007 | 保存厂商配置前必须通过 API Key 测试连接（测试失败不允许保存） | 🔴 强制 |
| BR-LLM-CONF-008 | API Key 在前端界面展示时必须脱敏，禁止明文显示已保存的 Key | 🔴 强制 |
| BR-LLM-CONF-009 | 配置修改保存后即时生效，后续对话使用新配置，无需重启服务 | 🔴 强制 |
| BR-LLM-CONF-010 | 模型选择按会话维度保持状态，切换会话互不影响，同一会话内保持状态 | 🔴 强制 |
| BR-LLM-CONF-011 | 模型选择按消息维度控制（发送消息时使用当前选择的模型，不影响历史消息） | 🔴 强制 |
| BR-LLM-CONF-012 | 新建会话时默认选择上次使用的 chat 模型，无历史记录时选择第一个可用 chat 模型 | ⚪ 可覆盖 |
| BR-LLM-CONF-013 | 删除厂商后，正在使用该厂商模型的会话自动切换到第一个可用 chat 模型，无可用模型时禁用对话 | 🔴 强制 |
| BR-LLM-CONF-014 | 配置同时保存到后端内存和前端 localStorage，确保后端重启后可从 localStorage 恢复 | 🔴 强制 |
| BR-LLM-CONF-015 | 后端重启后前端检测到后端无配置时，自动从 localStorage 推送配置到后端恢复 | 🔴 强制 |
| BR-LLM-CONF-016 | 对话功能依赖至少一个已配置的 chat 模型，无 chat 模型时对话禁用并引导配置 | 🔴 强制 |
| BR-LLM-CONF-017 | RAG 文档向量化依赖已配置的 embedding 模型，无 embedding 模型时阻止文档上传并引导配置 | 🔴 强制 |
| BR-LLM-CONF-018 | 预定义厂商列表包含：火山引擎方舟、阿里百炼、OpenAI、DeepSeek、Ollama（本地），每个预定义厂商自带默认 Base URL 和常用模型清单 | 🔴 强制 |
| BR-LLM-CONF-019 | 移除 `application.yml` 中 `llm`、`ark`、`bailian` 配置段，LLM 配置完全由前端动态管理 | 🔴 强制 |
| BR-LLM-CONF-020 | 后端 LLM 模型实例缓存按厂商+模型名称维度管理，配置变更时清除对应缓存 | 🔴 强制 |

## 10. 异常处理

| 异常场景 | 错误码 | 提示信息 | 处理方式 |
|---------|-------|---------|---------|
| LLM 调用失败 | 5001 | LLM 调用失败 | 上层捕获并转换 |
| LLM 调用超时 | 5002 | LLM 调用超时 | 上层捕获并转换 |
| LLM 被限流 | 5003 | LLM 调用被限流 | 上层捕获并转换 |
| LLM API Key 无效 | 5004 | LLM API Key 无效 | 对话中 API Key 失效时抛 BusinessException，Controller 发送 SSE error 事件 |
| LLM 模型未配置（v3.0 视觉能力缺失） | 5005 | LLM 模型未配置 | `getVisionChatModel` 无可用的 supportsVision=true chat 模型时抛出 |
| LLM 提供商未注册 | 5006 | LLM 提供商未注册 | 已随 Provider 模式移除（历史错误码保留） |
| LLM 能力不支持 | 5007 | LLM 能力不支持 | 抛 UnsupportedCapabilityException（v3.0 保留） |
| LLM 配置不存在（v3.0 新增） | 5008 | LLM 配置不存在 | 配置查询失败时抛出 |
| 厂商不存在（v3.0 新增） | 5009 | 厂商不存在: {id} | 编辑/删除不存在的厂商时抛出 BusinessException |
| 模型不存在（v3.0 新增） | 5010 | 模型不存在或类型不是 chat: {modelId} | `get*ByModelId` 按 modelId 查找失败时抛出 |
| 厂商名称已存在（v3.0 新增） | 5011 | 厂商名称已存在: {name} | `addVendor`/`updateVendor` 名称查重失败时抛出 |
| 同类型同名模型已存在（v3.0 新增） | 5012 | 同类型同名模型已存在: {modelName} | 模型查重失败时抛出 |
| 连接测试失败（v3.0 新增） | 5013 | 连接测试失败 | 测试连接接口返回失败原因（HTTP 401/404/超时） |
| 未配置 chat 模型（v3.0 新增） | 5014 | 未配置 chat 模型 | `getDefaultChatModel`/`getDefaultStreamingChatModel`/`getDefaultThinkingStreamingChatModel` 无 chat 模型时抛出 |
| 未配置 embedding 模型（v3.0 新增） | 5015 | 未配置 embedding 模型 | `getEmbeddingModel` 无 embedding 模型时抛出，RAG 阻止文档上传 |

## 11. 性能要求

| 指标 | 要求 | 说明 |
|------|------|------|
| 配置 CRUD | < 1ms | 内存操作（ConcurrentHashMap） |
| 模型创建耗时 | 50-100ms | 仅首次创建（OpenAI client 初始化），后续缓存命中 <1ms |
| 缓存命中率 | > 99% | 应用启动后稳定运行 |
| 配置变更后首次对话 | 100-200ms | 缓存失效后需重建（delegate + 模型实例），仅影响变更厂商 |
| LLM 调用超时 | 60s | 默认值，可通过厂商配置 timeout 调整 |
| 重试次数 | 3 次 | 默认值，可通过厂商配置 maxRetries 调整 |
| 测试连接 | < 2s | 取决于目标 API 响应时间 |

## 12. 支持的模型清单

### 预定义厂商（PredefinedVendorCatalog，v3.0 内置模板）

#### 火山引擎方舟（ark，thinkingTrigger=enabled）

| 模型 | Model Name | 类型 | 支持视图 |
|------|-----------|------|---------|
| 豆包Seed 2.0 Pro | `doubao-seed-2.0-pro` | chat | ❌ |
| 豆包Seed 2.0 Code | `doubao-seed-2.0-code` | chat | ❌ |
| 豆包Seed 2.0 Lite | `doubao-seed-2.0-lite` | chat | ❌ |
| 豆包Vision Pro | `doubao-vision-pro` | chat | ✅ |
| 豆包Embedding Large Text | `doubao-embedding-large-text-240915` | embedding | ❌ |

#### 阿里百炼（bailian，thinkingTrigger=none）

| 模型 | Model Name | 类型 | 支持视图 |
|------|-----------|------|---------|
| DeepSeek V4 Flash | `deepseek-v4-flash` | chat | ❌ |
| GLM 5.2 | `glm-5.2` | chat | ❌ |
| 通义千问 3.7 Plus | `qwen3.7-plus` | chat | ✅ |
| 文本向量化 V4 | `text-embedding-v4` | embedding | ❌ |

#### OpenAI（openai，thinkingTrigger=none）

| 模型 | Model Name | 类型 | 支持视图 |
|------|-----------|------|---------|
| GPT-4o | `gpt-4o` | chat | ✅ |
| GPT-4o Mini | `gpt-4o-mini` | chat | ❌ |
| Text Embedding 3 Small | `text-embedding-3-small` | embedding | ❌ |

#### DeepSeek（deepseek，thinkingTrigger=none）

| 模型 | Model Name | 类型 | 支持视图 |
|------|-----------|------|---------|
| DeepSeek Chat | `deepseek-chat` | chat | ❌ |
| DeepSeek Reasoner | `deepseek-reasoner` | chat | ❌ |

#### Ollama（本地）（ollama，thinkingTrigger=none）

| 模型 | Model Name | 类型 | 支持视图 |
|------|-----------|------|---------|
| （本地部署，模型清单为空） | - | - | - |

> 除预定义模板外，配置管理员可通过「自定义厂商/自定义模型」录入任意厂商与模型（动态配置模式）。

---

## 附录：CR-002 重构效果汇总（历史）

| 指标 | 重构前 | 重构后（CR-002） |
|------|--------|--------|
| ModelFactory 代码行数 | 410 行 | 203 行（含 javadoc）/ 58 行（纯代码） |
| 厂商硬编码分支 | 7 处 | 0 处 |
| 思考流式模型代码重复率 | 95% | 10.48%（jscpd 行级） |
| 模块包结构 | 单一 `factory` 包（16 文件杂烩） | 6 个职责子包（config/capability/provider/thinking/registry/exception） |

**v3.0 迭代说明**：本迭代（LLM 厂商模型配置）在 CR-002 基础上进一步演进，将配置源从静态 yml 切换为动态用户配置：
- 移除 Provider 模式（provider 包 + capability 包能力接口），`ModelFactory` 直接从 `LlmConfigStore` 创建模型实例
- 包结构收敛为 4 个子包（config/registry/thinking/exception），config 包由配置属性绑定类演进为动态配置存储与实体
- 新增错误码 5008-5015（8 个）
- 新增业务规则 BR-LLM-CONF-001~020（20 条）
- 新增前端「LLM 配置」页面与对话模型选择器

---

**文档维护**：
- 新增/修改预定义厂商或模型时，更新 `PredefinedVendorCatalog.java` 与第 12 节清单
- 配置校验规则变更时，更新 `LlmConfigStore` 私有校验方法与 BR-LLM-CONF-XXX 规则
- 安全策略变更时，更新第 5 节（API Key 脱敏、localStorage 存储）
- 新增 ModelFactory 方法时，更新第 8.2 节内部方法清单
- 新增错误码时，更新 `ErrorCode.java` 与第 10 节异常处理表
- 动态配置模式下新增运行时厂商无需修改任何后端核心代码（由前端录入）

**变更日志**：
- v1.0（2026-07-20）：初始版本
- v1.1（2026-07-30）：补充阿里百炼接入、LlmProvider 枚举、多提供商路由
- v1.2（2026-07-31）：补充 CR-001 思考流式模型、ArkThinkingStreamingChatModel、ThinkingStreamHandler
- v2.0（2026-08-05）：CR-002 重构 — 模块按能力矩阵 + 提供商策略 + 注册表架构重组目录；ModelFactory 改为注册表路由；新增 5 条业务规则（BR-LLM-014~018）；新增 2 个错误码（5006/5007）；修正 BR-LLM-012
- **v3.0（2026-08-11）：LLM 厂商模型配置迭代 — 移除 Provider 模式与 capability 包能力接口；删除 config 包旧类（LlmProperties/ArkProperties/BailianProperties/LlmProvider/LlmProviderConfig/LlmConfig）；新增动态配置（LlmConfigStore/LlmVendorConfig/LlmModelConfig/PredefinedVendorCatalog/PredefinedVendor/PredefinedModel）；ModelFactory 重构为从 LlmConfigStore 读取配置并按 vendorId:modelName 缓存，新增 ByModelId 方法；thinking 包保留并按 thinkingTrigger 动态选择实现类；新增 20 条 BR-LLM-CONF 规则；新增 8 个错误码（5008-5015）；API Key 由前端配置并脱敏显示；移除 application.yml 中 llm/ark/bailian 配置段**
