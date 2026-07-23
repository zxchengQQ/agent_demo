# 功能变更记录: 深度思考模式优化-ReAct与工具调用 - CR-001

## 0. 变更概览 (Change Overview)
*   **变更标题**: 动态工具声明优化（移除 prompt 硬编码工具描述）
*   **变更类型**: 扩展 (Extension)
*   **变更原因**: 当前深度思考 ReAct 模式的系统提示词中，工具描述以硬编码方式写在 `AgentConfig.thinkingReactSystemPrompt` 和 `application.yml` 中，无法根据实际注册的 @Tool 工具动态声明，导致新增/移除工具时需手动同步修改配置，且工具描述可能与实际注册工具不一致
*   **发起日期**: 2026-07-23
*   **开发方法**: TDD（测试驱动开发）- 每个任务按 Red-Green-Refactor 循环执行
*   **关联功能**: 深度思考模式优化-ReAct与工具调用
*   **关联文档**:
    -   需求文档: `specs/features/20260722_深度思考模式优化-ReAct与工具调用/深度思考模式优化-ReAct与工具调用.md`
    -   技术方案: `specs/features/20260722_深度思考模式优化-ReAct与工具调用/深度思考模式优化-ReAct与工具调用_技术方案.md`
    -   任务规划: `specs/features/20260722_深度思考模式优化-ReAct与工具调用/深度思考模式优化-ReAct与工具调用_任务规划.md`

## 1. 影响分析 (Impact Analysis)

### 1.1 需求影响
| 影响项 | 变更类型 | 详情 |
| :--- | :--- | :--- |
| AC-019 | 修改 | 原为"系统提示词包含 ReAct 引导与工具描述"（工具描述硬编码），改为"系统提示词包含 ReAct 引导与动态工具描述"（工具描述运行时动态生成） |
| AC-025 | 新增 | 工具描述与实际注册工具动态一致（新增/移除 @Tool 工具后工具描述自动同步） |

### 1.2 技术影响
| 影响层 | 影响范围 | 详情 |
| :--- | :--- | :--- |
| 数据层 | 无影响 | 不涉及数据库/内存数据结构变更（AgentConfig 字段不变，仅默认值变化） |
| API 层 | 无影响 | 不涉及 API 接口变更 |
| 表现层 | 无影响 | 不涉及前端组件变更 |
| 业务逻辑 | 修改逻辑 | `ToolSchemaConverter` 新增 `convertToDescriptionText()` 方法；`SimpleAgent.buildReActMessagesWithMemory()` 组装提示词时动态拼接工具描述 |

### 1.3 代码影响
| 文件路径 | 操作 | 影响说明 |
| :--- | :--- | :--- |
| `agent-demo-tools/.../registry/ToolSchemaConverter.java` | 修改 | 新增 `convertToDescriptionText()` 方法，反射扫描 @Tool 注解生成人类可读工具描述文本 |
| `agent-demo-agent/.../single/SimpleAgent.java` | 修改 | `buildReActMessagesWithMemory()` 方法改为动态拼接 `thinkingReactSystemPrompt` + `convertToDescriptionText()` |
| `agent-demo-agent/.../config/AgentConfig.java` | 修改 | `thinkingReactSystemPrompt` 默认值移除末尾硬编码的工具描述段 |
| `agent-demo-bootstrap/.../application.yml` | 修改 | `thinking-react-system-prompt` 配置同步移除硬编码工具描述 |

### 1.4 测试影响
| 测试文件 | 影响类型 | 说明 |
| :--- | :--- | :--- |
| `agent-demo-tools/.../ToolSchemaConverterTest.java` | 需新增 | 新增 `convertToDescriptionText` 测试用例 |
| `agent-demo-agent/.../AgentConfigTest.java` | 需修改 | AC-019 验证标准变化：`thinkingReactSystemPrompt` 默认值不再包含硬编码工具名（calculate/getCurrentTime 等） |
| `agent-demo-agent/.../SimpleAgentTest.java` | 需修改 | 验证 `buildReActMessagesWithMemory` 返回的 SystemMessage 包含动态工具描述 |
| `agent-demo-llm/.../ArkThinkingStreamingReActTest.java` | 无影响 | 不涉及流式模型变更 |

### 1.5 回归风险评估
*   **高风险区域**: `SimpleAgent.buildReActMessagesWithMemory()` 是 ReAct 循环的入口，修改提示词组装逻辑可能影响 LLM 对工具的认知和行为
*   **已有测试覆盖**: `SimpleAgentTest` 已覆盖 `buildReActMessagesWithMemory` 的消息列表组装验证；`ToolSchemaConverterTest` 已覆盖 `convertToJson` 的工具扫描逻辑
*   **需要补充的测试**: `convertToDescriptionText()` 方法的输出格式验证、动态工具描述与注册工具一致性验证、AgentConfig 默认值不再包含硬编码工具名验证

## 2. 需求变更详情 (Requirements Delta)
> 仅记录本次变更涉及的需求变化，已有需求不重复列出

### 2.1 新增/修改的用户故事
- **US-006**（CR-001 新增）: 作为 **开发者**，我想要 **新增或移除 @Tool 工具时系统提示词中的工具描述自动同步更新**，以便 **无需手动修改提示词配置就能让 AI 感知到工具的变化**。
    - 关联验收标准：AC-025

### 2.2 新增/修改的验收标准

#### 业务规则 (Business Rules)
- **AC-019**（修改）: 系统提示词包含 ReAct 引导与动态工具描述
    - Given: 深度思考模式启用，已注册若干 @Tool 注解方法
    - When: Agent 组装消息列表
    - Then: 系统提示词包含 ReAct 格式引导（要求 LLM 使用 Thought/Action/Observation 结构化标签输出推理过程，允许在 Thought 中用自然语言展开分析），工具能力描述部分通过运行时反射扫描已注册的 @Tool 方法动态生成（不硬编码在提示词配置中），同时通过 `tools` 参数传递工具的 JSON Schema 定义

- **AC-025**（新增）: 工具描述与实际注册工具动态一致
    - Given: 深度思考模式启用，系统中注册了若干 @Tool 工具（如 calculate、getCurrentTime 等）
    - When: Agent 组装系统提示词时动态生成工具描述
    - Then: 工具描述文本中列出的工具名和描述与实际注册的 @Tool 方法完全一致；新增或移除 @Tool 工具后，工具描述自动同步更新，无需修改提示词配置

### 2.3 移除的内容
- 无移除内容。原 AC-019 中"工具能力描述"部分从硬编码改为动态生成，不是移除而是变更实现方式。

## 3. 技术变更详情 (Technical Delta)
> 仅记录本次变更涉及的技术变化

### 3.1 数据库变更
> 无数据库变更。本项目当前无传统关系数据库。

### 3.2 API 变更
> 无 API 变更。不涉及接口路径、参数、响应格式变化。

### 3.3 组件变更
| 操作 | 组件 | 说明 |
| :--- | :--- | :--- |
| 修改 | `ToolSchemaConverter` | 新增 `convertToDescriptionText()` 方法，反射扫描 @Tool 注解生成人类可读工具描述文本 |
| 修改 | `SimpleAgent` | `buildReActMessagesWithMemory()` 方法改为动态拼接 `thinkingReactSystemPrompt` + `convertToDescriptionText()` |
| 修改 | `AgentConfig` | `thinkingReactSystemPrompt` 默认值移除末尾硬编码工具描述段 |
| 修改 | `application.yml` | `thinking-react-system-prompt` 配置同步移除硬编码工具描述 |

### 3.4 兼容性说明
*   **向前兼容**: 完全兼容。`thinkingReactSystemPrompt` 配置项仍然存在，仅默认值内容变化（移除了工具描述段）。如果用户在 `application.yml` 中自定义了 `thinking-react-system-prompt`，自定义值会生效但不再包含工具描述（工具描述由 `convertToDescriptionText()` 动态追加）。
*   **迁移方案**: 无需迁移。变更后系统提示词 = 配置的 `thinkingReactSystemPrompt` + 动态生成的工具描述文本，自动拼接。
*   **回滚方案**: 如需回滚到硬编码模式，恢复 `AgentConfig` 和 `application.yml` 中的原始 `thinkingReactSystemPrompt` 值即可（但 `SimpleAgent.buildReActMessagesWithMemory()` 会继续追加动态工具描述，需同步回退该方法）。

## 4. 增量开发任务 (Incremental Tasks)
> 任务编号从原任务规划最后一个编号（Task-16）之后继续
> 每个任务按 TDD 循环执行：RED（写测试）-> GREEN（写实现）-> REFACTOR（重构）

### 阶段一：工具层变更 (Tools Layer Delta)

- [x] **Task-17**: ToolSchemaConverter 新增 convertToDescriptionText() 方法
    *   **说明**: 在 `ToolSchemaConverter` 类中新增 `convertToDescriptionText()` 方法，遍历 `ToolRegistry.listTools()`，反射扫描 `@Tool` 注解方法，提取方法名和 @Tool 注解描述值，生成人类可读的工具描述文本字符串。格式为"你可以调用以下工具来获取信息：\n- {方法名}: {描述}\n...当问题需要实时信息或计算时，请主动调用工具。"
    *   **变更类型**: 新增
    *   **涉及文件**: `agent-demo-tools/src/main/java/com/agentdemo/tools/registry/ToolSchemaConverter.java`
    *   **测试文件**: `agent-demo-tools/src/test/java/com/agentdemo/tools/registry/ToolSchemaConverterTest.java`
    *   **参考**: 技术方案 Sec 4.4.1
    *   **对应AC**: AC-025
    *   **预估工时**: 60m
    *   **依赖**: 无（ToolSchemaConverter 和 ToolRegistry 已存在）
    *   **验证标准**（TDD RED 阶段的测试依据）:
        - [ ] `convertToDescriptionText()` 返回非空字符串
        - [ ] 返回的字符串包含 "你可以调用以下工具来获取信息" 前缀
        - [ ] 返回的字符串包含 "当问题需要实时信息或计算时，请主动调用工具" 后缀
        - [ ] 返回的字符串包含已注册工具的方法名（如 "calculate"、"getCurrentTime"）
        - [ ] 返回的字符串包含 @Tool 注解的描述值（如 "数学表达式计算"）
        - [ ] 每个工具以 "- {方法名}: {描述}" 格式列出
        - [ ] 无工具注册时，返回的字符串仍包含前缀和后缀（中间无工具条目）

### 阶段二：Agent 层与配置变更 (Agent & Config Layer Delta)

- [x] **Task-18**: AgentConfig/yml 移除硬编码工具描述 + SimpleAgent 动态拼接
    *   **说明**: 分三步修改：(1) 修改 `AgentConfig.thinkingReactSystemPrompt` 默认值，移除末尾硬编码的工具描述段（"你可以调用以下工具来获取信息：计算器 calculate..."），仅保留 ReAct 格式引导和约束规则；(2) 同步修改 `application.yml` 中 `thinking-react-system-prompt` 配置；(3) 修改 `SimpleAgent.buildReActMessagesWithMemory()` 方法，将 `agentConfig.getThinkingReactSystemPrompt()` 与 `toolSchemaConverter.convertToDescriptionText()` 拼接后作为 SystemMessage
    *   **变更类型**: 修改
    *   **涉及文件**:
        - `agent-demo-agent/src/main/java/com/agentdemo/agent/config/AgentConfig.java`
        - `agent-demo-bootstrap/src/main/resources/application.yml`
        - `agent-demo-agent/src/main/java/com/agentdemo/agent/single/SimpleAgent.java`
    *   **测试文件**:
        - `agent-demo-agent/src/test/java/com/agentdemo/agent/config/AgentConfigTest.java`
        - `agent-demo-agent/src/test/java/com/agentdemo/agent/single/SimpleAgentTest.java`
    *   **参考**: 技术方案 Sec 3.1、Sec 4.4.1
    *   **对应AC**: AC-019, AC-025
    *   **预估工时**: 90m
    *   **依赖**: Task-17（convertToDescriptionText 方法）
    *   **验证标准**（TDD RED 阶段的测试依据）:
        - [ ] `AgentConfig.getThinkingReactSystemPrompt()` 默认值包含 "Thought"、"Action"、"Observation" 关键词
        - [ ] `AgentConfig.getThinkingReactSystemPrompt()` 默认值**不包含** "calculate"、"getCurrentTime"、"httpGet"、"readFile" 等硬编码工具名
        - [ ] `AgentConfig.getThinkingReactSystemPrompt()` 默认值**不包含** "你可以调用以下工具来获取信息" 硬编码工具描述段
        - [ ] `application.yml` 中 `thinking-react-system-prompt` 配置不包含硬编码工具名
        - [ ] `SimpleAgent.buildReActMessagesWithMemory()` 返回的消息列表首条为 SystemMessage
        - [ ] 该 SystemMessage 的内容包含 `thinkingReactSystemPrompt` 的 ReAct 引导部分
        - [ ] 该 SystemMessage 的内容包含 `convertToDescriptionText()` 动态生成的工具描述部分
        - [ ] 该 SystemMessage 的内容包含已注册工具的方法名（如 "calculate"）
        - [ ] 现有 `chatThinkingStream` 方法不受影响（使用 `thinkingSystemPrompt`，不走 ReAct 路径）
        - [ ] 现有 `chat` 和 `chatStream` 方法不受影响

### 阶段三：回归验证 (Regression Verification)
> 每个增量变更必须包含回归验证

- [x] **Task-19**: 回归验证
    *   **说明**: 运行全量已有测试套件，确保变更未破坏原有功能。重点验证：ReAct 循环正常工作、工具调用正常、SSE 事件推送正常、CR-001 单轮思考模式不回归、普通模式零回归。注意：每个增量任务内部已通过 TDD 循环完成了自身的测试编写，此处重点是验证跨模块的回归安全性。
    *   **变更类型**: 验证
    *   **涉及文件**: 所有测试文件
    *   **对应AC**: AC-019, AC-025, 以及所有已有 AC 的回归
    *   **预估工时**: 60m
    *   **依赖**: Task-17, Task-18
    *   **验证标准**:
        - [ ] 后端 `mvn test` 全部通过，无失败
        - [ ] `ToolSchemaConverterTest` 新增的 `convertToDescriptionText` 测试通过
        - [ ] `AgentConfigTest` 修改后的 AC-019 验证通过（默认值不含硬编码工具名）
        - [ ] `SimpleAgentTest` 修改后的动态工具描述注入验证通过
        - [ ] `ReActThinkingStreamTest` 全部通过（ReAct 循环不受影响）
        - [ ] `ArkThinkingStreamingReActTest` 全部通过（流式模型不受影响）
        - [ ] CR-001 单轮思考模式（`chatThinkingStream`）仍正常工作
        - [ ] 普通模式（`enableThinking=false`）零回归
        - [ ] 测试覆盖率未下降

## 5. 增量验收标准检查清单 (Incremental AC Checklist)
> 仅包含本次变更涉及的验收标准

| 验收标准ID | 验收标准描述 | 状态 | 对应任务 | 操作 |
| :--- | :--- | :--- | :--- | :--- |
| AC-019 | 系统提示词包含 ReAct 引导与动态工具描述 | 已完成 | Task-18 | 修改 |
| AC-025 | 工具描述与实际注册工具动态一致 | 已完成 | Task-17, Task-18 | 新增 |

## 6. 变更总结 (Change Summary)
*   **总新增任务数**: 3 个（Task-17 ~ Task-19）
*   **预计总工时**: 210 分钟（约 3.5 小时）
*   **风险等级**: 低
*   **风险说明**: 本次变更仅修改提示词组装逻辑，不涉及 ReAct 循环核心逻辑、流式模型、SSE 协议等高风险区域。`convertToDescriptionText()` 复用已有的 `ToolRegistry.listTools()` 和 @Tool 反射扫描逻辑，与 `convertToJson()` 共享相同的工具遍历模式，技术成熟度高。主要风险在于 AgentConfig 默认值和 application.yml 配置的同步修改，需确保两者一致。
*   **测试影响**: 需修改 2 个已有测试（AgentConfigTest、SimpleAgentTest），新增 1 个测试方法（ToolSchemaConverterTest 中的 convertToDescriptionText 测试）
*   **预期效果**: 变更完成后，新增或移除 @Tool 工具时，系统提示词中的工具描述自动同步更新，无需手动修改 AgentConfig 或 application.yml 配置。工具描述与实际注册工具始终保持一致，消除因配置不同步导致的"工具不存在"等问题。
