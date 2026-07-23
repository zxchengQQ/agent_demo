# 功能需求说明书 (Feature Requirements Document)

## 1. 背景与价值 (Context & Value)

*   **背景**: 当前项目已实现深度思考模式（CR-001），通过 `ArkThinkingStreamingChatModel` 直连方舟 API 获取 `reasoning_content` 并流式展示。但该模式绕过了 LangChain4j AiServices 的 ReAct 循环，**不支持工具调用**（BR-AGT-007 规定思考模式使用专用提示词、不提及工具）。用户在深度思考模式下无法让 Agent 自主调用工具（如查询天气、搜索文档等），功能体验不完整。
*   **目标**: 在深度思考模式下引入 ReAct（Reasoning + Acting）循环和工具调用能力，同时保留双重推理层（模型内部推理 + 显式 ReAct 推理），让用户能看到 AI 的完整思考与决策过程。
*   **关联**: CR-001（深度思考模式基础能力）、Agent 编排模块、LLM 接入模块、工具调用模块、前端模块

## 2. 功能范围 (Scope)

### 2.1 本次范围（In Scope）

*   方舟 LLM 原生驱动 ReAct 循环（通过 `tool_calls` 字段触发工具调用）
*   双重推理层：模型内部推理（`reasoning_content`）+ 显式 ReAct 推理（Prompt 引导，结构化标签 Thought/Action/Observation + 自由文本）
*   工具调用支持（复用现有 `@Tool` 注解工具集，通过 OpenAI 兼容 `tools` 参数传递 JSON Schema）
*   SSE 流式输出协议扩展（7 类事件：reasoning / thought / action / observation / token / done / error，携带 iteration 轮次标识）
*   ReAct 循环边界控制（最大迭代次数可配置默认 8、达到上限强制总结、工具失败回填 Observation）
*   会话记忆手动管理，仅持久化最终回答
*   前端 UI 改造：新增"ReAct 推理过程"折叠区块、工具调用卡片渲染

### 2.2 不在本次范围（Out of Scope）

*   多工具并行调用 - 本次仅支持串行工具调用（一次一个），降低复杂度；后续迭代再考虑并行执行
*   思考过程持久化 - 推理过程（reasoning/thought/action/observation）仅实时推送，不持久化到数据库；后续可增加历史回看功能
*   普通模式与深度思考模式的热切换 - 模式在会话开始时确定，不支持同一会话中途切换

## 3. 用户角色 (Actors)

*   **学习者**: 通过前端界面开启深度思考模式，与 Agent 对话并观察推理过程
*   **开发者**: 扩展 `@Tool` 工具集、调整 Agent 配置参数（如最大迭代次数）
*   **API 调用方**: 通过 SSE 接口集成深度思考 + ReAct 能力到外部应用

## 4. 用户故事 (User Stories)

*   **US-001**: 作为 **学习者**，我想要 **在深度思考模式下看到 AI 的 ReAct 推理过程和工具调用决策**，以便 **理解 AI 是如何一步步得出结论的**。
    *   关联验收标准：AC-001, AC-002, AC-003, AC-004, AC-005, AC-006

*   **US-002**: 作为 **学习者**，我想要 **深度思考模式支持工具调用（如查天气、搜文档）**，以便 **AI 能获取实时信息来回答问题**。
    *   关联验收标准：AC-001, AC-004, AC-005, AC-008

*   **US-003**: 作为 **学习者**，我想要 **前端以折叠区块和工具卡片展示 ReAct 过程**，以便 **清晰地看到每轮推理的 Thought、Action 和 Observation**。
    *   关联验收标准：AC-009, AC-010

*   **US-004**: 作为 **开发者**，我想要 **配置深度思考模式的最大迭代次数**，以便 **控制 Token 消耗和响应时间**。
    *   关联验收标准：AC-011, AC-018

*   **US-005**: 作为 **学习者**，我想要 **工具调用失败时 AI 能自行决策重试或换方案**，以便 **对话不会因工具异常而中断**。
    *   关联验收标准：AC-012

## 5. 详细需求与流程 (Detailed Requirements)

### 5.1 核心流程

1.  用户开启深度思考模式（`enableThinking=true`），发送消息
2.  Agent 手动组装消息列表（系统提示词含 ReAct 引导 + 工具描述 + 历史消息 + 当前用户消息）
3.  Agent 将已注册的 `@Tool` 方法转换为 OpenAI 兼容 `tools` JSON Schema 参数
4.  Agent 直连方舟 API（`stream=true`, `thinking.enabled=true`, `tools=[...]`），启动 ReAct 循环
5.  方舟返回流式响应，系统逐 Token 解析并推送 SSE 事件：
    *   `reasoning_content` -> 推送 `reasoning` 事件（内部推理）
    *   `content` 中的 Thought 文本 -> 推送 `thought` 事件（显式 ReAct 推理）
    *   `tool_calls` -> 推送 `action` 事件（工具调用描述）
6.  若 `finish_reason="tool_calls"`：
    *   Agent 串行执行工具调用
    *   推送 `observation` 事件（工具执行结果）
    *   将 `tool` 角色消息回填到消息列表
    *   回到步骤 4，进入下一轮 ReAct 推理（iteration 递增）
7.  若 `finish_reason="stop"`：
    *   `content` 中的最终回答 -> 推送 `token` 事件
    *   推送 `done` 事件，结束 ReAct 循环
8.  仅将用户消息和最终回答持久化到会话记忆，推理过程不持久化

```
┌─────────────────────────────────────────────────┐
│                  用户发送消息                     │
│                  (enableThinking=true)            │
└──────────────────────┬──────────────────────────┘
                       ▼
┌──────────────────────────────────────────────────┐
│  组装消息列表 + 转换 @Tool 为 tools 参数           │
│  (system prompt含ReAct引导 + history + user msg)  │
└──────────────────────┬──────────────────────────┘
                       ▼
┌──────────────────────────────────────────────────┐
│  直连方舟 API (stream, thinking, tools)            │
│  iteration = 1                                    │
└──────────────────────┬──────────────────────────┘
                       ▼
              ┌───────────────┐
              │ 解析流式响应    │
              └───────┬───────┘
                      ▼
         ┌────────────────────────┐
         │ reasoning_content?     │──是──▶ 推送 reasoning 事件
         └────────────┬───────────┘
                      ▼
         ┌────────────────────────┐
         │ content含Thought?      │──是──▶ 推送 thought 事件
         └────────────┬───────────┘
                      ▼
         ┌────────────────────────┐
         │ finish_reason?         │
         └───┬───────────────┬────┘
             │               │
        tool_calls          stop
             │               │
             ▼               ▼
    推送 action 事件     推送 token 事件
    执行工具             推送 done 事件
    推送 observation     ─────────────▶ 结束
    回填 tool 消息
    iteration++
    回到"直连方舟API" ▲
```

### 5.2 交互/界面规则 (UI/UX Rules)

*   **内部推理区块（"已思考"）**: 位于消息气泡上方，流式中展开、完成后可折叠。展示模型内部 `reasoning_content`。
*   **ReAct 推理过程区块**: 位于内部推理区块下方、消息气泡上方。流式中展开、完成后可折叠。按轮次（iteration）分组展示 Thought / Action / Observation。
*   **工具调用卡片**: Action 渲染为带工具图标、工具名、参数的卡片。收到 Observation 后在卡片中追加展示工具结果。
*   **最终回答区域**: 位于最下方，Markdown 渲染，流式中显示光标动画。
*   **停止生成**: 流式中显示"停止生成"按钮，点击后中断 ReAct 循环，已推送内容保留。

### 5.3 业务规则 (Business Rules)

*   **BR-THINK-001**: 深度思考模式 ReAct 循环最大迭代次数默认 8 次，通过 `agent.thinking-max-iterations` 配置。
*   **BR-THINK-002**: 系统提示词必须包含 ReAct 格式引导（Thought/Action/Observation 结构化标签），工具能力描述通过运行时动态生成（反射扫描已注册的 @Tool 方法），不硬编码在提示词配置中。同时通过 `tools` 参数传递工具 JSON Schema。（CR-001 修改：原为"工具能力描述"硬编码在提示词中，改为动态生成）
*   **BR-THINK-003**: 工具定义复用现有 `@Tool` 注解方法，自动转换为 OpenAI 兼容 `tools` 参数，与普通模式工具集一致。
*   **BR-THINK-004**: 工具调用串行执行（不并行），即使方舟 API 返回多个 `tool_calls` 也按顺序逐个执行。
*   **BR-THINK-005**: SSE 事件 thought/action/observation 必须携带 `iteration` 字段标识当前 ReAct 轮次（从 1 开始递增）。
*   **BR-THINK-006**: 会话记忆手动管理（不使用 LangChain4j ChatMemory），仅持久化用户消息和最终回答，推理过程不持久化。
*   **BR-THINK-007**: 达到最大迭代次数后，系统发送一次不带 `tools` 参数的请求，要求 LLM 基于已有信息生成总结性回答。
*   **BR-THINK-008**: 工具调用失败时，将错误信息作为 Observation 回填给 LLM，不中断 ReAct 循环，由 LLM 决定重试或换方案。

## 6. 验收标准 (Acceptance Criteria)

> **重要**：以下验收标准是后续技术方案、任务规划和 TDD 测试用例的直接依据。每条 AC 必须使用 Given-When-Then 格式，必须可被测试验证。

### 6.1 正常流程 (Happy Path)

- [ ] **AC-001**: 深度思考模式启动 ReAct + 工具调用循环
    - Given: 用户已开启深度思考模式（`enableThinking=true`），ARK_API_KEY 已配置，已注册 @Tool 工具
    - When: 用户发送一条可能需要工具调用的消息（如"北京今天天气怎么样"）
    - Then: 系统通过方舟 API 发起流式请求（`stream=true`, `thinking.enabled=true`, `tools=工具列表`），启动 ReAct 循环，开始流式推送 SSE 事件

- [ ] **AC-002**: 内部推理（reasoning_content）逐 Token 流式推送
    - Given: 深度思考模式 ReAct 循环已启动，方舟 API 返回 `reasoning_content` 字段
    - When: 模型逐 Token 输出推理内容片段
    - Then: 系统通过 SSE 逐 Token 推送 `reasoning` 事件，前端在"已思考"折叠区块中实时展示推理内容

- [ ] **AC-003**: 显式 ReAct 推理（Thought）逐 Token 流式推送
    - Given: 方舟 API 返回 `content` 字段中的 Thought 推理文本
    - When: 模型逐 Token 输出 Thought 推理片段
    - Then: 系统通过 SSE 逐 Token 推送 `thought` 事件（携带 `iteration` 轮次标识），前端在"ReAct 推理过程"区块中按轮次展示 Thought 内容

- [ ] **AC-004**: 工具调用触发与 action 事件推送
    - Given: 方舟 API 返回 `finish_reason="tool_calls"` 且 `tool_calls` 数组非空
    - When: Agent 解析 `tool_calls` 并准备执行对应工具
    - Then: 系统通过 SSE 推送 `action` 事件（含工具名、参数、`iteration` 轮次标识），前端渲染为工具调用卡片

- [ ] **AC-005**: 工具结果回填与 observation 事件推送
    - Given: 工具执行完成并返回结果
    - When: Agent 将工具结果作为 `tool` 角色消息回填到消息列表
    - Then: 系统通过 SSE 推送 `observation` 事件（含工具结果、`iteration` 轮次标识），前端在工具调用卡片中展示结果，随后进入下一轮 ReAct 推理（`iteration` 递增）

- [ ] **AC-006**: ReAct 循环终止与最终回答推送
    - Given: ReAct 循环中方舟 API 返回 `finish_reason="stop"`
    - When: 模型输出最终回答内容
    - Then: 系统通过 SSE 逐 Token 推送 `token` 事件，前端在正式回复区域展示最终回答，全部输出完成后推送 `done` 事件

- [ ] **AC-007**: 仅持久化最终回答到会话记忆
    - Given: 深度思考模式 ReAct 循环已完成
    - When: 系统处理会话记忆持久化
    - Then: 仅将用户消息和最终回答持久化到会话记忆，推理过程（reasoning/thought/action/observation）不持久化

- [ ] **AC-008**: 无需工具调用时的深度思考
    - Given: 用户开启深度思考模式发送一条不需要工具调用的消息（如"你好"）
    - When: 方舟 API 返回 `finish_reason="stop"` 且无 `tool_calls`
    - Then: 系统推送 `reasoning` 事件（内部推理）+ `token` 事件（最终回答）+ `done` 事件，不推送 `thought`/`action`/`observation` 事件

- [ ] **AC-009**: 前端 ReAct 推理过程折叠区块
    - Given: 深度思考模式对话进行中或已完成
    - When: 前端收到 `thought`/`action`/`observation` 事件
    - Then: 在 reasoning 折叠区块下方显示"ReAct 推理过程"折叠区块，按 `iteration` 轮次分组展示 Thought/Action/Observation；流式中保持展开，完成后可手动折叠

- [ ] **AC-010**: 前端工具调用卡片渲染
    - Given: 前端收到 `action` 事件
    - When: 渲染工具调用信息
    - Then: 以卡片样式展示工具图标、工具名、参数；收到对应 `observation` 事件后在卡片中追加展示工具结果

### 6.2 边界与异常 (Edge & Error Cases)

- [ ] **AC-011**: 达到最大迭代次数强制总结
    - Given: ReAct 循环已达到配置的最大迭代次数（默认 8），仍未得到最终回答（`finish_reason` 仍为 `tool_calls`）
    - When: 系统检测到迭代次数超限
    - Then: 系统向方舟 API 发送一次不带 `tools` 参数的请求，要求 LLM 基于已有信息生成总结性回答，推送 `token` 事件后推送 `done` 事件

- [ ] **AC-012**: 工具调用失败回填 Observation
    - Given: ReAct 循环中工具执行抛出异常（如工具内部错误、参数不合法）
    - When: Agent 捕获工具执行异常
    - Then: 将错误信息作为 Observation 回填给 LLM（格式如"工具执行失败：[错误信息]"），通过 SSE 推送 `observation` 事件，让 LLM 决定重试或换方案，不中断整个 ReAct 循环

- [ ] **AC-013**: LLM 调用失败错误推送
    - Given: 深度思考模式 ReAct 循环中方舟 API 调用失败（网络错误/超时/限流/API Key 无效）
    - When: 系统捕获 LLM 调用异常
    - Then: 通过 SSE 推送 `error` 事件（含错误信息），终止 ReAct 循环，前端显示错误提示

- [ ] **AC-014**: 用户主动停止生成
    - Given: 深度思考模式 ReAct 循环进行中，前端已建立 SSE 连接
    - When: 用户点击"停止生成"按钮
    - Then: 系统中断方舟 API 请求和 ReAct 循环，已推送的内容保留显示，前端标记消息状态为 `incomplete`

- [ ] **AC-015**: 空消息输入校验
    - Given: 用户在深度思考模式下发送空消息（message 为空或仅含空白字符）
    - When: 系统接收到空消息
    - Then: 返回错误提示"消息不能为空"，不启动 ReAct 循环

- [ ] **AC-016**: ARK_API_KEY 未配置
    - Given: 深度思考模式请求时 ARK_API_KEY 未配置
    - When: 系统尝试创建方舟 API 请求
    - Then: 返回错误码 5004，提示"ARK_API_KEY 未配置"，不启动 ReAct 循环

- [ ] **AC-017**: 会话不存在时创建新会话
    - Given: 用户传入不存在的 sessionId
    - When: 系统查找会话记忆
    - Then: 创建新会话，正常启动 ReAct 循环，通过 `session` 事件返回新 sessionId

### 6.3 业务规则验证 (Business Rules)

- [ ] **AC-018**: 最大迭代次数可配置
    - Given: 配置文件中设置了 `agent.thinking-max-iterations=5`
    - When: 深度思考模式 ReAct 循环执行
    - Then: 最大迭代次数为 5 次（而非默认 8 次），达到 5 次后触发强制总结（AC-011）

- [ ] **AC-019**: 系统提示词包含 ReAct 引导与动态工具描述
    - Given: 深度思考模式启用，已注册若干 @Tool 注解方法
    - When: Agent 组装消息列表
    - Then: 系统提示词包含 ReAct 格式引导（要求 LLM 使用 Thought/Action/Observation 结构化标签输出推理过程，允许在 Thought 中用自然语言展开分析），工具能力描述部分通过运行时反射扫描已注册的 @Tool 方法动态生成（不硬编码在提示词配置中），同时通过 `tools` 参数传递工具的 JSON Schema 定义

- [ ] **AC-020**: 工具定义复用现有 @Tool 注解
    - Given: 深度思考模式启用，已注册若干 `@Tool` 注解方法
    - When: Agent 构建 `tools` 参数
    - Then: 自动扫描已注册的 `@Tool` 方法，转换为 OpenAI 兼容的 `tools` JSON Schema 格式传给方舟 API，工具集与普通模式一致

- [ ] **AC-021**: 会话记忆手动管理
    - Given: 深度思考模式 ReAct 循环执行中
    - When: Agent 组装消息列表
    - Then: 手动管理消息列表（system/user/assistant/tool 角色），按 sessionId 存取，不使用 LangChain4j ChatMemory 自动管理

- [ ] **AC-022**: 串行工具调用（不并行）
    - Given: 方舟 API 返回多个 `tool_calls`（如同时请求 getWeather 和 getTime）
    - When: Agent 执行工具调用
    - Then: 按顺序串行执行每个工具调用（不并行），依次推送 `action` 和 `observation` 事件，依次回填 `tool` 角色消息

- [ ] **AC-023**: SSE 事件携带 iteration 轮次标识
    - Given: 深度思考模式 ReAct 循环进行多轮推理
    - When: 系统推送 `thought`/`action`/`observation` 事件
    - Then: 每条 SSE 消息携带 `iteration` 字段标识当前 ReAct 轮次（从 1 开始递增），前端据此按轮次分组展示

- [ ] **AC-024**: 推理过程不持久化到 localStorage
    - Given: 深度思考模式对话完成，前端保存会话
    - When: 前端将会话写入 localStorage
    - Then: 仅持久化最终回答内容（`content` 字段），`reasoning` 和 ReAct 过程（thought/action/observation）不保存到 localStorage

- [ ] **AC-025**: 工具描述与实际注册工具动态一致（CR-001 新增）
    - Given: 深度思考模式启用，系统中注册了若干 @Tool 工具（如 calculate、getCurrentTime 等）
    - When: Agent 组装系统提示词时动态生成工具描述
    - Then: 工具描述文本中列出的工具名和描述与实际注册的 @Tool 方法完全一致；新增或移除 @Tool 工具后，工具描述自动同步更新，无需修改提示词配置

---

### AC 覆盖度自检

- [x] 正常流程的每个关键步骤都有对应 AC（AC-001 ~ AC-010）
- [x] 第 5.3 节的每条业务规则都有对应 AC（BR-THINK-001 -> AC-018, BR-THINK-002 -> AC-019/AC-025, BR-THINK-003 -> AC-020, BR-THINK-004 -> AC-022, BR-THINK-005 -> AC-023, BR-THINK-006 -> AC-021/AC-024, BR-THINK-007 -> AC-011, BR-THINK-008 -> AC-012）
- [x] 所有已识别的边界/异常情况都有对应 AC（AC-011 ~ AC-017）
- [x] 每条 AC 描述的是可观测行为，而非内部实现

---
## 变更日志 (Change Log)
### CR-001: 动态工具声明优化（移除 prompt 硬编码工具描述） (2026-07-23)
**变更类型**: 扩展
**变更原因**: 当前深度思考 ReAct 模式的系统提示词中，工具描述以硬编码方式写在 AgentConfig.thinkingReactSystemPrompt 和 application.yml 中，无法根据实际注册的 @Tool 工具动态声明，导致新增/移除工具时需手动同步修改配置，且工具描述可能与实际注册工具不一致
**变更内容摘要**:
- [修改] AC-019: 系统提示词包含 ReAct 引导与工具描述 -> 系统提示词包含 ReAct 引导与动态工具描述（工具描述部分改为运行时动态生成，不硬编码在提示词配置中）
- [新增] AC-025: 工具描述与实际注册工具动态一致（新增/移除 @Tool 工具后工具描述自动同步，无需修改提示词配置）
- [修改] BR-THINK-002: 工具能力描述通过运行时动态生成（反射扫描已注册的 @Tool 方法），不硬编码在提示词配置中
