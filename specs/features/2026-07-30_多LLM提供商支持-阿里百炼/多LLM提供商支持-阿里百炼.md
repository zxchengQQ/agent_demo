# 功能需求说明书 (Feature Requirements Document)

## 1. 背景与价值 (Context & Value)

* **背景**: agent-demo 项目当前 LLM 提供商仅支持**火山引擎方舟 Coding Plan**（通过 `ark.coding-plan.*` 配置），所有模型实例（ChatModel、StreamingChatModel、EmbeddingModel）均绑定火山引擎的 Base URL 和 API Key。项目学习者/开发者如需切换其他 LLM 提供商（如阿里百炼），需要修改代码，灵活性不足。

* **目标**: 支持多 LLM 提供商配置，通过配置级切换（`llm.provider`）选择使用哪个提供商，本次先接入**阿里百炼（Alibaba Bailian）**。

* **关联**: KNOWLEDGE\_BASE.md 第 3.2 节 LLM 提供商配置（当前仅标注火山引擎）；specs/modules/LLM接入模块-业务说明书.md 第 3 节（业务功能点均绑定火山引擎）

## 2. 功能范围 (Scope)

### 2.1 本次范围（In Scope）

* **配置级提供商切换**：新增 `llm.provider` 配置项，支持 `ark`（火山引擎）和 `bailian`（阿里百炼）两种取值

* **阿里百炼配置**：新增独立的 `bailian.*` 配置前缀（`bailian.base-url`、`bailian.api-key`、`bailian.default-model`、`bailian.models`、`bailian.timeout`、`bailian.max-retries`、`bailian.temperature`）

* **阿里百炼对话模型**：通过 LangChain4j OpenAI 适配器接入阿里百炼 OpenAI 兼容接口，支持 ChatModel（同步）和 StreamingChatModel（流式）的创建与缓存复用

* **阿里百炼 Embedding 模型**：切换提供商后，Embedding 模型也对应切换到阿里百炼的 Embedding 模型

* **API Key 隔离校验**：切换为阿里百炼时校验 `BAILIAN_API_KEY`，切换回火山引擎时校验 `ARK_API_KEY`，互不干扰

* **模型常量扩展**：在 `ModelConstants` 中新增阿里百炼的模型名称常量

* **配置示例**：更新 application.yml 和 KNOWLEDGE\_BASE.md 中的配置说明

### 2.2 不在本次范围（Out of Scope）

* **~~阿里百炼思考模式~~**~~：本次不接入阿里百炼的思考/推理能力（如 QwQ 模型的 reasoning\_content 解析），后续迭代~~（已于 CR-001 解除限制，百炼深度思考模式已支持）

* **运行时双提供商共存**：本次只支持配置级切换，不支持运行时同时使用两个提供商，后续按需扩展

* **阿里百炼专属非 OpenAI 兼容 API**：本次仅使用阿里百炼的 OpenAI 兼容协议（`/compatible-mode/v1`），不接入阿里百炼的专属 SDK 或特有 API

* **其他厂商接入**：DeepSeek、智谱 GLM 等暂不接入，本次仅接入阿里百炼

* **前端提供商切换 UI**：切换仅通过配置项控制，不涉及前端界面

* **提供商切换热加载**：切换需重启应用（配置项变更需重启生效），不支持运行时热加载

## 3. 用户角色 (Actors)

* **运维者**: 通过修改 application.yml 和环境变量，切换 LLM 提供商

* **开发者**: 在 `ModelConstants` 中扩展阿里百炼的模型常量，在 `ModelFactory` 中扩展创建逻辑

* **对话用户**: 无感知，提供商切换后对话行为不变（仅底层模型不同）

## 4. 用户故事 (User Stories)

* **US-001**: 作为 **运维者**，我想要 **通过修改配置项切换 LLM 提供商**，以便 **在不同厂商的模型之间灵活选择**。

  * 关联验收标准：AC-001, AC-004, AC-006

* **US-002**: 作为 **运维者**，我想要 **阿里百炼的配置独立于火山引擎**，以便 **两个厂商的配置互不干扰，切换时只需改一个配置项**。

  * 关联验收标准：AC-001, AC-002, AC-003, AC-005, AC-009, AC-012

* **US-003**: 作为 **对话用户**，我想要 **提供商切换后对话体验一致**，以便 **不受底层模型变更的影响，正常使用所有功能**。

  * 关联验收标准：AC-001, AC-002, AC-003, AC-004

## 5. 详细需求与流程 (Detailed Requirements)

### 5.1 核心流程

**流程 A - 配置切换（启动时）：**

1. 运维者在 application.yml 中设置 `llm.provider: bailian`
2. 配置阿里百炼的相关参数（base-url、api-key、default-model、models 等）
3. 设置环境变量 `BAILIAN_API_KEY`
4. 重启应用
5. 系统启动时根据 `llm.provider` 值加载对应的配置类，构建对应提供商的模型工厂
6. 应用正常提供服务，所有 LLM 调用（对话、流式对话、Embedding）均使用阿里百炼

**流程 B - 配置切换回火山引擎：**

1. 运维者在 application.yml 中设置 `llm.provider: ark`
2. 确保环境变量 `ARK_API_KEY` 已配置
3. 重启应用
4. 系统恢复使用火山引擎方舟，原有功能不受影响

**流程 C - 阿里百炼模型调用：**

1. Agent 层调用 `ModelFactory.getChatModel(scene)` 获取对话模型
2. `ModelFactory` 根据当前激活的提供商，路由到对应的模型创建逻辑
3. 创建阿里百炼 ChatModel（使用阿里百炼的 baseUrl、apiKey、modelName）
4. 返回模型实例，后续流程与火山引擎完全一致

### 5.2 交互/界面规则 (UI/UX Rules)

* 提供商切换对对话用户完全透明，无任何 UI 变化

* 切换后对话体验（流式输出、Markdown 渲染等）保持不变

* 仅通过配置项控制，无需任何前端交互

### 5.3 业务规则 (Business Rules)

* **BR-LLM-008**: LLM 提供商通过 `llm.provider` 配置项切换，支持 `ark`（火山引擎）和 `bailian`（阿里百炼）两种取值。默认值为 `ark`（向后兼容）。

* **BR-LLM-009**: 阿里百炼 API Key 必须通过环境变量 `BAILIAN_API_KEY` 注入，禁止硬编码入库。

* **BR-LLM-010**: 阿里百炼的 Base URL 使用 OpenAI 兼容协议地址（`https://dashscope.aliyuncs.com/compatible-mode/v1`），可配置覆盖。

* **BR-LLM-011**: 阿里百炼的模型名称必须通过 `ModelConstants` 常量类引用。

* **BR-LLM-012**: 切换提供商时，只校验当前使用提供商的 API Key，不校验另一个提供商的 API Key。

* **BR-LLM-013**: 切换提供商后，Embedding 模型也跟随切换，使用对应提供商的 Embedding 模型。

* **BR-LLM-014**: 阿里百炼模式下支持深度思考模式和任务拆解功能，行为与火山引擎模式一致。通过 `getThinkingStreamingChatModel()` 统一路由到对应提供商的思考流式模型实现。

## 6. 验收标准 (Acceptance Criteria)

> **重要**：以下验收标准是后续技术方案、任务规划和 TDD 测试用例的直接依据。每条 AC 必须使用 Given-When-Then 格式，必须可被测试验证。

### 6.1 正常流程 (Happy Path)

* [ ] **AC-001**: 切换提供商为阿里百炼后同步对话正常

  * Given: 配置 `llm.provider: bailian`，`BAILIAN_API_KEY` 已正确配置，阿里百炼服务可用

  * When: 用户发送一条同步对话消息

  * Then: 系统调用阿里百炼的对话模型成功，返回正常回复

* [ ] **AC-002**: 切换提供商为阿里百炼后流式对话正常

  * Given: 配置 `llm.provider: bailian`，`BAILIAN_API_KEY` 已正确配置，阿里百炼服务可用

  * When: 用户发送一条流式对话消息

  * Then: 系统通过 SSE 逐字返回阿里百炼的回复内容，流式输出完整无中断

* [ ] **AC-003**: 切换提供商为阿里百炼后 Embedding 正常

  * Given: 配置 `llm.provider: bailian`，`BAILIAN_API_KEY` 已正确配置，阿里百炼服务可用

  * When: 系统触发 Embedding 操作（如 RAG 文档向量化）

  * Then: 系统调用阿里百炼的 Embedding 模型成功，返回向量化结果

* [ ] **AC-004**: 切换回火山引擎后原有功能不受影响

  * Given: 配置 `llm.provider: ark`，`ARK_API_KEY` 已正确配置，火山引擎服务可用

  * When: 用户发送同步/流式对话消息

  * Then: 系统使用火山引擎方舟正常响应，行为与切换前完全一致

* [ ] **AC-005**: 阿里百炼场景路由正常

  * Given: 配置 `llm.provider: bailian`，`bailian.models` 中配置了 `chat` 和 `code` 场景对应的模型

  * When: 开发者调用 `getChatModel("chat")` 和 `getChatModel("code")`

  * Then: 分别返回对应场景配置的阿里百炼模型实例，未命中场景时回退到 `bailian.default-model`

* [ ] **AC-015**: 阿里百炼深度思考模式正常

  * Given: 配置 `llm.provider: bailian`，`BAILIAN_API_KEY` 已正确配置，阿里百炼服务可用

  * When: 用户启用深度思考模式（`enableThinking=true`）并发送对话消息

  * Then: 系统调用阿里百炼的思考流式模型成功，通过 SSE 逐字返回推理内容（`reasoning_content`）和正式回复（`content`），流式输出完整无中断

* [ ] **AC-016**: 阿里百炼任务拆解功能完整可用

  * Given: 配置 `llm.provider: bailian`，`BAILIAN_API_KEY` 已正确配置，用户开启任务拆解开关（`enableTaskBreakdown=true`）

  * When: 用户发送一条需要拆解的复杂任务消息

  * Then: 系统正常完成规划（`task_plan`）、子任务执行（`task_start`/`task_token`/`task_complete`）和总结三个阶段，所有子任务执行成功并生成最终总结

### 6.2 边界与异常 (Edge & Error Cases)

* [ ] **AC-006**: 配置阿里百炼但未设置 BAILIAN\_API\_KEY

  * Given: 配置 `llm.provider: bailian`，但环境变量 `BAILIAN_API_KEY` 未设置

  * When: 系统启动后首次调用 LLM 模型

  * Then: 系统抛出异常，提示"BAILIAN\_API\_KEY 未配置，请通过环境变量注入"

* [ ] **AC-007**: 配置阿里百炼但 API Key 无效

  * Given: 配置 `llm.provider: bailian`，`BAILIAN_API_KEY` 设置为无效值

  * When: 用户发送对话消息

  * Then: 系统返回 LLM 调用失败的错误提示（错误码 5001），不导致系统崩溃

* [ ] **AC-008**: 阿里百炼服务不可用

  * Given: 配置 `llm.provider: bailian`，但阿里百炼服务不可达

  * When: 用户发送对话消息

  * Then: 系统返回 LLM 调用超时或连接失败的提示（错误码 5001/5002），不导致系统崩溃

* [ ] **AC-009**: 配置不存在的提供商值

  * Given: 配置 `llm.provider: unknown`（不支持的取值）

  * When: 系统启动或首次调用 LLM

  * Then: 系统抛出明确的配置错误提示，告知"不支持的 LLM 提供商: unknown，支持的值: ark, bailian"

* [ ] **AC-010**: 阿里百炼配置项缺失时的默认行为

  * Given: 配置 `llm.provider: bailian`，但未配置 `bailian.base-url`

  * When: 系统加载阿里百炼配置

  * Then: 系统使用阿里百炼的默认 Base URL（`https://dashscope.aliyuncs.com/compatible-mode/v1`），不报错

* [ ] **AC-011**: 切换提供商后火山引擎配置不校验

  * Given: 配置 `llm.provider: bailian`，`BAILIAN_API_KEY` 已配置，但 `ARK_API_KEY` 未设置

  * When: 用户发送对话消息

  * Then: 系统正常调用阿里百炼模型，不因 `ARK_API_KEY` 缺失而报错

* [ ] **AC-017**: 阿里百炼深度思考模式下 ReAct 工具调用正常

  * Given: 配置 `llm.provider: bailian`，用户启用深度思考模式并发送需要调用工具的对话消息

  * When: LLM 在推理过程中决定调用工具（`finish_reason=tool_calls`）

  * Then: 系统正确解析工具调用请求，执行工具并回填结果，继续下一轮推理，最终完成整个 ReAct 循环并返回正确结果

### 6.3 业务规则验证 (Business Rules)

* [ ] **AC-012**: 阿里百炼 API Key 环境变量注入

  * Given: 阿里百炼的 `apiKey` 字段在配置类中声明为 `${BAILIAN_API_KEY}`

  * When: 检查代码中阿里百炼 API Key 的注入方式

  * Then: API Key 必须通过环境变量 `BAILIAN_API_KEY` 注入，禁止在代码或配置文件中硬编码（BR-LLM-009）

* [ ] **AC-013**: 阿里百炼模型名常量化

  * Given: 阿里百炼的模型名称在 `ModelConstants` 中定义了常量（如 `MODEL_QWEN_PLUS`、`MODEL_QWEN_MAX` 等）

  * When: 检查所有引用阿里百炼模型名的代码

  * Then: 所有模型名必须通过 `ModelConstants` 常量引用，禁止在代码中硬编码模型名字符串（BR-LLM-011）

* [ ] **AC-014**: 阿里百炼模型实例缓存复用

  * Given: 开发者多次调用 `getChatModel("chat")` 获取相同场景的模型

  * When: 检查模型实例的创建行为

  * Then: 相同模型名称的 ChatModel 实例只创建一次，后续调用返回缓存中的同一实例（遵循 BR-LLM-004）

***

### AC 覆盖度自检

* [x] 正常流程的每个关键步骤都有对应 AC（AC-001 \~ AC-005 覆盖同步对话、流式对话、Embedding、回切验证、场景路由五条主线；AC-015 \~ AC-016 覆盖百炼深度思考和任务拆解）

* [x] 第 5.3 节的每条业务规则都有对应 AC（BR-LLM-008 -> AC-009, BR-LLM-009 -> AC-012, BR-LLM-010 -> AC-010, BR-LLM-011 -> AC-013, BR-LLM-012 -> AC-011, BR-LLM-013 -> AC-003, BR-LLM-014 -> AC-015/AC-016）

* [x] 所有已识别的边界/异常情况都有对应 AC（AC-006 \~ AC-011、AC-017 覆盖 API Key 缺失、无效、服务不可用、不支持的提供商值、配置缺失默认值、切换后不校验另一厂商、百炼 ReAct 工具调用）

* [x] 每条 AC 描述的是可观测行为，而非内部实现

***

## 附录：需求决策记录

| 决策项          | 决策结果                                        | 决策理由                                                            |
| ------------ | ------------------------------------------- | --------------------------------------------------------------- |
| 阿里百炼接入方式     | OpenAI 兼容协议 + LangChain4j 适配器               | 与现有框架无缝集成，改动最小                                                  |
| 提供商切换方式      | 配置级切换（`llm.provider`）                       | 简单直接，对现有代码影响最小                                                  |
| 配置结构         | 火山引擎保持 `ark.coding-plan.*`，新增独立 `bailian.*` | 零改动现有配置，新增清晰独立                                                  |
| API Key 校验   | 切换后只校验当前提供商的 API Key                        | 各管各的，切换前不需要提前配置另一个                                              |
| Embedding 切换 | 跟随提供商一起切换                                   | 整体迁移，避免理解复杂度                                                    |
| 思考模式         | ~~本次暂不支持~~ 已于 CR-001 支持                     | 通过抽象 `ThinkingStreamingChatModel` 接口，为百炼新增原生 HTTP 实现，与方舟保持一致的能力 |
| 运行时双提供商      | 暂不支持                                        | 先做简单方案，后续按需扩展                                                   |

***

## 变更日志 (Change Log)

| 版本   | 日期         | 变更内容                                       |
| ---- | ---------- | ------------------------------------------ |
| v1.0 | 2026-07-30 | 初始版本 — 多 LLM 提供商支持（阿里百炼接入），14 条 AC，5 条业务规则 |

### CR-001: 阿里百炼深度思考与任务拆解支持 (2026-07-31)

**变更类型**: 扩展
**变更原因**: 用户需要阿里百炼厂商支持深度思考流程和任务拆解流程，解除原 Out of Scope 限制
**变更内容摘要**:

* \[新增] BR-LLM-014: 阿里百炼模式下支持深度思考模式和任务拆解功能

* \[新增] AC-015: 阿里百炼深度思考模式正常（推理内容 + 正式回复流式输出）

* \[新增] AC-016: 阿里百炼任务拆解功能完整可用（规划→执行→总结三阶段）

* \[新增] AC-017: 阿里百炼深度思考模式下 ReAct 工具调用正常

* \[修改] 2.2 Out of Scope: 删除"阿里百炼思考模式"限制，标注已解除

* \[修改] 附录决策记录: "思考模式"决策从"本次暂不支持"更新为"已于 CR-001 支持"

