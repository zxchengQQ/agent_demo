# AI Agent 需求说明书 (AI Agent Requirements Document)

> **需求名称**：工具权限全域统一管控（Tool Permission Unification）

| 字段 | 内容 |
|------|------|
| 版本 | v1.0 |
| 作者 | 需求澄清会话（AI Agent 产品架构师） |
| 日期 | 2026-08-25 |
| 变更记录 | v1.0 \| 2026-08-25 \| 初版：基于 20260824_tool-permission-control 的全域收口演进 \| 需求澄清会话 |

## 1. 背景与价值 (Context & Value)

*   **背景**: 上一期（`specs/features/20260824_tool-permission-control/`）建立了工具三级权限模型（allow/ask/deny），但仅在单 Agent 对话链路（agent-demo-agent）闭环。**应用编排模块（agent-demo-app）的工作流路径绕过权限控制**：
    1. **非 HITL 路径（默认）双重绕过**：`AgenticAgentFactory.buildAgent` 调用 `resolveTools` 未传过滤参数（NONE 模式），deny 工具照常注入；且工具由 LangChain4j 内部反射直调，不经过 ToolExecutor，deny 执行期兜底失效——管理员将 httpGet 设为 deny 后，工作流仍会加载并直接执行 HTTP 外呼。
    2. **HITL 路径加载期绕过**：`AgentExecutor.resolveHitlTools` 同为 NONE 模式，deny 工具对 LLM 可见（仅执行期兜底拦截，"可见但不可执行"）。
    3. **ask 工具卡死**：`awaitHitlStream` 未注册 `onToolConfirm` 回调，ask 工具触发暂停后 CompletableFuture 永不完成，最终 5 分钟超时失败——ask 表现为"卡死"而非"人工确认"。
    *   **架构根因**：当前权限过滤模式（NONE/STREAMING/SYNC）由**调用方自行选择**，权限执行依赖各模块自觉传参，违反领域自治原则。
*   **目标**: 工具权限是工具域的子领域，权限判定规则全局唯一——**无论哪一个模块使用工具，都受同一套权限约束**；外部模块只能声明自身能力（有无暂停-恢复能力），不能选择权限语义。
*   **关联**: 上期需求 `specs/features/20260824_tool-permission-control/`（Out of Scope 中"Workflow 模块工具权限管控列入下期演进"的本期兑现）。

## 2. Agent 角色定义 (Agent Persona)

> 本需求为系统级权限治理能力，不引入新 Agent。本章定义**权限治理规则**在各 Agent（单 Agent / 工作流 Agent）上的统一角色约束。

### 2.1 身份定位

*   **角色名称**: 工具权限统一管控（横切各 Agent 的治理层）
*   **角色类型**: 工作流型 + 任务型（治理规则作用于所有工具调用场景）
*   **身份描述**: 工具域内部的权限子领域——权限判定内聚于工具域（ToolRegistry + ToolExecutor + 工具包装层），是所有 Agent 使用工具的唯一权威闸门。
*   **核心价值**: 消除"权限跟着外部模块变动而有差异"的病灶：同一工具、同一权限配置，在任何模块中的可见性与可执行性完全一致；未来新模块自动继承约束，无需（也无法）自行选择权限语义。

### 2.2 目标用户与场景

*   **目标用户**:
    *   **管理员**：通过管理页（`PUT /api/agent/tools/{toolId}/permission`）配置工具权限，期望配置一处生效、全域生效。
    *   **终端用户**：与单 Agent 对话或执行工作流，遇到 ask 工具时收到确认卡片并决策。
*   **使用场景**:
    *   管理员将高危工具（如 httpGet）设为 deny，随后执行任意工作流，验证该工具全域不可用。
    *   用户执行 HITL 工作流，Agent 调用 ask 级工具时工作流暂停，弹出确认卡片，批准/拒绝后续跑。
*   **交互风格**: 权限拦截对用户透明（deny 静默不可见）；ask 确认交互与单 Agent 确认卡片同构（工具名 + 参数摘要）。

## 3. 能力边界与自主性 (Capability & Autonomy)

### 3.1 能力清单（In Scope）

*   **工具域强制收口**：ToolRegistry 加载期强制过滤（deny 永不返回）+ 工具包装层执行期强制拦截（deny 方法体零触发），删除 NONE 过滤模式，调用方无法绕过。
*   **能力声明双方法 API**：`resolveToolsForStreaming`（调用方支持暂停-恢复，ask 可见待确认）/ `resolveToolsForDirect`（调用方无暂停能力，ask 不注入）。调用方声明的是**自身能力**而非权限选择。
*   **工作流 ask 升级为暂停**：HITL 工作流路径新增 `MODE_TOOL_CONFIRM` 恢复模式，复用现有 WAITING_USER 状态 + `workflow_waiting` SSE 事件 + 前端等待 UI。
*   **非 HITL 路径 ask 加载期排除**：与单 Agent 同步路径 SYNC 语义完全一致。
*   **全量收口**：app 模块 2 个调用点（AgenticAgentFactory / AgentExecutor.resolveHitlTools）+ SimpleAgent 3 参数兼容方法旁路一并清理。
*   **模板适配**：3 个绑定 httpGet（ask 级）的现有模板默认启用 `hitlEnabled=true`。

### 3.2 能力禁区（Out of Scope）

*   **对话内容不可修改权限配置** - 权限配置仅经管理页 API 变更（沿用 BR-PERM-007）。
*   **非 HITL 路径不提供 ask 确认能力** - 无暂停-恢复能力的路径上 ask 工具直接不注入，不出现"静默直执行"或"卡死超时"。
*   **不提供模块级权限豁免** - 任何模块（含工作流、未来新模块）不得绕过权限判定；不存在"工作流专用工具白名单"。

### 3.3 自主性级别

*   **级别**: 由权限等级决定，全域统一映射：
    | 权限等级 | Agent 自主性 | 全域行为 |
    |---------|-------------|---------|
    | allow | L3 自主执行可回滚 | 所有路径注入并直接执行，结果可观测 |
    | ask | L2 确认后执行 | 有暂停能力的路径可见+确认后执行；无能力路径不注入 |
    | deny | 禁区 | 所有路径不可见 + 方法体零触发（双重防线） |
*   **说明**: 自主性不随模块变化——同一工具在单 Agent 与工作流中的自主性级别完全一致，这是本需求的核心命题。
*   **人类介入点**: ask 级工具执行前（单 Agent：tool_confirm SSE 事件 + 确认卡片；工作流：WAITING_USER 状态 + workflow_waiting 事件 + 确认卡片）。

### 3.4 知识边界

*   **知识范围**: 权限判定依据为：显式配置（`data/tool-permissions.json`）> `@DefaultPermission` 注解 / 注册类别默认（rag->allow / mcp->ask）> ask 保守兜底（沿用 BR-PERM-001）。
*   **超出边界处理**: 权限配置缺失/文件损坏时按默认值重建，不阻断服务；权限判定异常时保守拒绝（宁可多问，不可越界）。

## 4. 工具与动作空间 (Tools & Action Space)

### 4.1 工具权限模型（全域统一）

| 权限等级 | 语义 | 加载期（能力声明双方法） | 执行期（包装层） |
|---------|------|------------------------|-----------------|
| allow | 放行 | 所有路径注入 | LLM 决策即执行，结果回填续跑 |
| ask | 需确认 | 仅 `resolveToolsForStreaming` 注入（`resolveToolsForDirect` 不注入） | 拦截 -> 暂停保存现场 -> 确认卡片 -> 批准执行 / 拒绝文案回填 LLM 换方案 |
| deny | 禁止 | **双方法均不注入**（LLM 不可见） | **方法体零触发**，返回固定拒绝提示（不暴露配置细节） |

### 4.2 各模块工具链路收口后状态

| 模块/路径 | 工具解析入口 | ask 处理 | deny 处理 |
|-----------|-------------|---------|----------|
| 单 Agent-同步 chat | `resolveToolsForDirect` | 不注入（现状保持） | 不注入 + 包装层兜底 |
| 单 Agent-流式（直答/拆解） | `resolveToolsForStreaming` | 确认卡片（现状保持） | 不注入 + 执行期拦截（现状保持） |
| 工作流-非 HITL | `resolveToolsForDirect`（新接入） | **不注入**（修复卡死/直执行） | **不注入 + 包装层拦截**（修复双重绕过） |
| 工作流-HITL | `resolveToolsForStreaming`（新接入） | **暂停-确认-续跑**（修复卡死超时） | 不注入 + 执行期拦截（修复加载期绕过） |
| SimpleAgent 3 参数兼容方法 | 收口清理，无过滤旁路不复存在 | - | - |

### 4.3 工具间关系

*   **权限判定优先级**: 显式配置 > 注册默认（注解/类别）> ask 兜底，规则全局唯一。
*   **豁免规则**: `builtin:askUser` 固定豁免恒 allow（防确认流程自身死锁，AC-S03，沿用 BR-PERM-004）。
*   **编排策略**: 工作流并行模式下多个 Agent 同时触发 ask 工具时，确认按触发顺序逐个弹出（沿用现有 HITL 工作流 askUser 的串行确认语义）。

## 5. 推理流程与记忆策略 (Reasoning & Memory)

### 5.1 推理模式

*   **主要模式**: 权限拦截不改变各 Agent 既有推理模式（ReAct / 工作流编排策略）。
*   **工作流 ask 暂停-恢复链路**: LLM 发起 ask 工具调用 -> 包装层 checkPermission 判定 ask -> 保存 tool_confirm 现场（WorkflowHITLState 新增 MODE_TOOL_CONFIRM，含 pendingToolCallId/Name/Arguments）-> 工作流进入 WAITING_USER -> SSE `workflow_waiting` 事件 -> 前端确认卡片 -> 批准：执行工具、结果回填、工作流续跑 / 拒绝：固定拒绝文案回填 LLM 换方案、工作流续跑。
*   **最大执行步骤**: 沿用各路径现状（ReAct 最大 10 次迭代、工作流 agentTimeoutMinutes 默认 5 分钟）。

### 5.2 澄清策略

*   **主动澄清条件**: 仅 ask 级工具触发（工具确认卡片，非对话追问）。
*   **自主推进条件**: allow 工具直接执行；deny 工具静默不可见，LLM 自然换方案。

### 5.3 短期记忆（对话内）

*   **上下文保留**: 沿用现状（会话记忆按 sessionId 隔离）。
*   **暂停恢复上下文**: tool_confirm 暂停期间 Agent 的推理历史与工具调用记录完整保存于 WorkflowHITLState，批准/拒绝后从暂停点续跑，不重头执行。

### 5.4 长期记忆（跨会话）

*   **是否需要**: 否（权限配置持久化于 `data/tool-permissions.json`，非 Agent 记忆范畴）。
*   **权限变更生效时机**: 本轮已解析工具列表不变（避免运行中突变），下一次工具解析起生效（沿用 BR-PERM-005，扩展至工作流场景）。

## 6. 护栏安全与降级策略 (Guardrails & Fallback)

### 6.1 内容安全规则

*   **必须拒绝的行为**: 任何路径执行 deny 工具（方法体零触发）；无暂停能力路径注入 ask 工具。
*   **标准拒绝话术**: 权限拒绝提示仅含"权限不足"类语义，固定文案，不暴露配置者/配置时间等细节（沿用 BR-PERM-007）。

### 6.2 幻觉防护

*   **回答依据**: 沿用各 Agent 现状；权限拒绝回填文案为固定模板，LLM 不得编造"工具不可用原因"。

### 6.3 敏感信息处理

*   **输入侧**: 沿用现状。
*   **输出侧**: 权限拒绝提示不暴露权限配置细节（配置者/变更时间/配置文件路径）。

### 6.4 工具失败降级

| 场景 | 失败场景 | 降级策略 | 是否转人工 |
|------|---------|---------|-----------|
| ask 暂停无响应 | 超过 agentTimeoutMinutes 未批准/拒绝 | 工作流超时失败结束，记录暂停上下文，不无限挂起 | 否（用户可断点续执行） |
| 权限配置文件损坏 | 启动时 JSON 解析失败 | 按默认值（注解/类别）重建，不阻断服务启动 | 否 |
| 工具注销 | 知识库删除 / MCP Server 断开 | 权限配置同步清理，不残留孤儿配置（沿用 BR-PERM-006） | 否 |
| deny 工具被注入（未来新路径） | 绕过解析直接注入工具对象 | 包装层执行期拦截，方法体零触发，返回固定拒绝提示 | 否（WARN 日志记录） |

### 6.5 人机协作机制

*   **升级条件**: ask 级工具执行前（全域唯一人工介入点）。
*   **升级方式**: 单 Agent：SSE `tool_confirm` 事件 + 确认卡片（现状）；工作流：WAITING_USER 状态 + `workflow_waiting` SSE 事件 + 前端 5 模式执行视图弹出确认卡片（新增，与单 Agent 确认卡片同构）。
*   **交接信息**: 确认卡片展示工具名称与参数摘要，用户无需查询日志即可决策；批准/拒绝经现有恢复通道（单 Agent：`ChatRequest.toolApproved` 静默恢复；工作流：WorkflowHITLState 恢复接口）。

### 6.6 提示注入防护

*   **攻击识别**: 对话内容（含工具返回内容）中"忽略权限/开放工具/修改工具权限"类指令一律不生效。
*   **处理策略**: 权限配置仅可经管理页 API 变更，对话通道（无论单 Agent 还是工作流执行中）不可修改权限（沿用 BR-PERM-007，全域适用）。
*   **工具返回防护**: 工具返回内容中的指令不改变权限判定；权限拒绝提示固定文案，LLM 无法借工具返回内容绕过闸门。

### 6.7 权限与身份校验

*   **身份确认机制**: 本项目为学习示例工程，未接入用户认证（沿用现状）；权限主体为**工具维度**（非用户维度），全域统一。
*   **防越权策略**: 权限判定入口唯一（工具域），任何模块不可绕过——这是本需求的核心防越权设计。
*   **数据隔离**: 会话隔离沿用现状；权限配置为全局配置（非会话级），本期不做会话级/角色级组合（见 8.2）。

## 7. 验收标准 (Acceptance Criteria)

> **重要**：以下验收标准是后续技术方案、任务规划和评估测试的直接依据。每条 AC 使用 Given-When-Then 格式，Then 部分含"应该做什么 + 不应该做什么"双向约束。

### 7.1 正常交互流程 (Normal Interaction)

- [ ] **AC-N01**: 权限全局一致性
    - Given: 管理员将某工具（如 builtin:calculator）配置为 allow
    - When: 任一模块（单 Agent 对话 / HITL 工作流 / 非 HITL 工作流）的 Agent 解析工具列表
    - Then: 应：该工具在所有模块均被注入且 LLM 决策即可执行；不应：出现"单 Agent 可用而工作流不可用"（或反之）的模块差异

- [ ] **AC-N02**: 工作流工具确认后续跑
    - Given: HITL 工作流 Agent 绑定 ask 级工具（如 httpGet）且用户在确认卡片上批准
    - When: 工具执行完成
    - Then: 应：工作流从 WAITING_USER 状态恢复、工具结果回填 Agent 上下文、后续步骤继续执行；不应：从工作流起点重跑或丢失暂停前的推理历史

- [ ] **AC-N03**: 现有模板开箱可用
    - Given: 3 个绑定 httpGet 的现有模板（ResearchAnalyzeSummarize / SmartRouting / TaskBreakdownSupervisor）已默认启用 hitlEnabled
    - When: 用户执行任一模板工作流且 Agent 决定调用 httpGet
    - Then: 应：弹出工具确认卡片（工具名 + 参数摘要），批准后工作流正常完成；不应：功能退化（工具缺失导致 Agent 无法完成原任务）或无确认直接外呼

### 7.2 工具选择与调用 (Tool Selection & Invocation)

- [ ] **AC-T01**: 权限入口唯一（能力声明双方法）
    - Given: 工具域收口完成
    - When: 任意调用方（含未来新模块）解析工具
    - Then: 应：仅可通过 `resolveToolsForStreaming`（ask 可见）或 `resolveToolsForDirect`（ask 不注入）两个能力声明方法获取工具；不应：存在无权限过滤的解析入口（NONE 模式 API 彻底删除，含 SimpleAgent 3 参数旁路）

- [ ] **AC-T02**: deny 加载期全域剔除
    - Given: 管理员将某工具配置为 deny
    - When: 任一模块任一路径（单 Agent 同步/流式、工作流 HITL/非 HITL）解析工具列表
    - Then: 应：该工具不被注入（LLM 不可见）、静默跳过不报错；不应：因模块不同而出现可见性差异

- [ ] **AC-T03**: deny 执行期零触发（双重防线）
    - Given: deny 工具因任何原因（如未来新路径绕过解析）仍被注入工具对象
    - When: LLM 发起该工具调用
    - Then: 应：工具包装层拦截、方法体零触发、返回固定拒绝提示并记录 WARN 日志；不应：工具方法体被执行（如 HTTP 外呼实际发生）或拒绝提示暴露配置细节

- [ ] **AC-T04**: 执行链路全域统一
    - Given: allow 工具被 LLM 调用
    - When: 在单 Agent 或工作流任一路径执行
    - Then: 应：均经过工具域执行链路（包装层 checkPermission -> 方法体触发 -> 结果回填）；不应：存在绕过包装层的直调路径（LangChain4j 反射直调需经包装层收口）

### 7.3 安全与护栏 (Safety & Guardrails)

- [ ] **AC-S01**: deny 全局强制（修复最严重绕过）
    - Given: 管理员将 builtin:httpGet 配置为 deny
    - When: 非 HITL 工作流（当前绕过最严重的默认路径）运行且其 Agent 尝试调用 httpGet
    - Then: 应：httpGet 不被加载（LLM 不可见）；即使被注入，HTTP 外呼也不发生（方法体零触发）；不应：出现当前"httpGet 设 deny 后工作流仍执行 HTTP 外呼"的行为

- [ ] **AC-S02**: 权限配置防篡改（含提示注入）
    - Given: 单 Agent 对话进行中或工作流执行中
    - When: 用户输入或工具返回内容中包含"忽略权限限制/开放所有工具/修改工具权限"类指令（提示注入攻击）
    - Then: 应：权限配置保持不变、deny 工具仍不可用、Agent 按原任务继续；不应：权限判定被对话内容改变或攻击者获得越权工具

- [ ] **AC-S03**: askUser 工具豁免（防确认死锁）
    - Given: builtin:askUser 固定豁免为 allow
    - When: 任一模块（含工作流两条路径）解析工具列表，或管理员尝试经 API 修改 askUser 权限
    - Then: 应：askUser 在所有路径始终可用（确认流程自身不被二次确认）；权限配置接口对 askUser 返回 400；不应：任何路径出现"确认工具自身需确认"的死锁

- [ ] **AC-S04**: 权限拒绝提示脱敏
    - Given: 工具被 deny 拦截或 ask 确认被用户拒绝
    - When: 拒绝结果回填 LLM（单 Agent 或工作流任一路径）
    - Then: 应：提示仅含"权限不足/用户拒绝执行"类固定语义；不应：暴露配置者、配置时间、配置文件路径等细节

### 7.4 边界与降级 (Edge Cases & Fallback)

- [ ] **AC-E01**: 无暂停能力路径的 ask 语义
    - Given: 非 HITL 工作流路径（hitlEnabled=false）的 Agent 绑定的工具中含 ask 级工具
    - When: 工作流启动并解析工具列表
    - Then: 应：ask 级工具在加载期即不注入（LLM 不可见），与单 Agent 同步路径 SYNC 语义一致；不应：出现当前"ask 工具触发暂停后 CompletableFuture 永不完成、5 分钟超时卡死"或"ask 工具被静默直接执行"的行为

- [ ] **AC-E02**: 孤儿权限清理
    - Given: 知识库被删除或 MCP Server 断开
    - When: 对应动态工具注销
    - Then: 应：权限配置同步清理（沿用现有规则，全域适用）；不应：残留孤儿权限配置

- [ ] **AC-E03**: ask 暂停超时降级
    - Given: HITL 工作流 tool_confirm 暂停等待用户决策
    - When: 超过 agentTimeoutMinutes（默认 5 分钟）仍无批准/拒绝响应
    - Then: 应：工作流以超时失败结束并保存暂停上下文（支持断点续执行语义）；不应：无限挂起或静默放弃

- [ ] **AC-E04**: 权限配置异常降级
    - Given: 权限配置文件（data/tool-permissions.json）损坏或不可读
    - When: 服务启动
    - Then: 应：按 @DefaultPermission 注解与类别默认值重建判定，服务正常启动；不应：因配置文件异常阻断启动或放开全部权限

### 7.5 记忆与上下文 (Memory & Context)

- [ ] **AC-M01**: 权限变更生效时机（不中途突变）
    - Given: 某工作流已解析工具列表并在运行中
    - When: 管理员变更某工具权限（如 allow -> deny）
    - Then: 应：本轮已解析的工具列表与运行中行为不变，下一次工具解析起按新权限生效；不应：运行中工具列表突变导致行为不可预测

- [ ] **AC-M02**: 暂停上下文完整保持
    - Given: HITL 工作流在 Agent 第 N 步触发 tool_confirm 暂停
    - When: 用户批准后工作流恢复
    - Then: 应：Agent 此前的推理历史、工具调用记录、工作流步骤状态完整保留，从暂停点（第 N 步工具执行）续跑；不应：从工作流起点重跑或丢失暂停前上下文

### 7.6 人机协作 (Human-in-the-Loop)

- [ ] **AC-H01**: 工作流工具确认交互（复用 WAITING_USER 体系）
    - Given: HITL 工作流 Agent 调用 ask 级工具
    - When: 权限拦截触发
    - Then: 应：工作流进入 WAITING_USER 状态、经 workflow_waiting SSE 事件通知前端、在 5 模式执行视图中弹出与单 Agent 同构的确认卡片（工具名 + 参数摘要）；用户批准 -> 工具执行、结果回填、工作流续跑；用户拒绝 -> 固定拒绝文案回填 LLM、LLM 换方案续跑；不应：表现为超时卡死（当前缺陷）或无确认直接执行

- [ ] **AC-H02**: 确认信息充分可决策
    - Given: tool_confirm 暂停且前端展示确认卡片
    - When: 用户查看卡片
    - Then: 应：卡片完整展示工具名称与调用参数摘要（与单 Agent 确认卡片同构），用户无需查询后端日志即可做出批准/拒绝决策；不应：仅显示"工具待确认"而无工具与参数信息

---

### AC 覆盖度自检

- [x] 正常交互的每个核心流程都有对应 AC（AC-N01~N03 覆盖全域一致/续跑/模板适配）
- [x] 每个有副作用的工具都有对应的安全 AC（httpGet 为代表：AC-S01/AC-N03/AC-H01；通用规则覆盖全部工具）
- [x] 每条能力禁区都有对应的拒绝 AC（3.2 三条禁区 -> AC-S01/AC-E01/AC-T01）
- [x] 每个工具的失败降级策略都有对应 AC（AC-E01~E04 覆盖 6.4 全部场景）
- [x] 多轮对话上下文保持有对应 AC（AC-M01/M02）
- [x] 人机协作的每个升级条件都有对应 AC（AC-H01/H02）
- [x] 提示注入防护有对应 AC（AC-S02）
- [x] 权限与防越权有对应 AC（AC-T01~T04/S01）
- [x] 每条 AC 的 Then 部分包含"应该做"和"不应该做"双向约束
- [x] 所有 AC 与第 3.3 节的自主性级别一致（allow=L3 / ask=L2 / deny=禁区，全域统一映射）
- [x] 非功能性约束已明确（agentTimeoutMinutes 暂停超时、权限变更下轮生效）
- [x] 评估方式与通过标准已定义（见下节）

### 评估方式

| 评估类型 | 内容 | 通过标准 |
|---------|------|---------|
| 单元测试 | ToolRegistry 双方法过滤逻辑、包装层 checkPermission 拦截、NONE 模式删除后编译期无入口 | 全部通过 |
| 集成测试 | 非 HITL 工作流：deny 工具不加载不执行（AC-S01/T02/T03）；HITL 工作流：ask 暂停-确认-批准/拒绝-续跑闭环（AC-H01/M02）；ask 卡死超时缺陷不复现（AC-E01） | 全部通过 |
| 一致性验证 | 同一权限配置在单 Agent / HITL 工作流 / 非 HITL 工作流三处解析结果一致（AC-N01/T02） | 行为矩阵一致 |
| 人工验收 | 管理页配置 httpGet=deny -> 执行 ResearchAnalyzeSummarize 模板 -> 验证无 HTTP 外呼；配置 ask -> HITL 工作流验证确认卡片与续跑 | 按验收脚本执行通过 |

## 8. 范围界定 (Scope)

### 8.1 本次范围（In Scope）

*   **工具域强制收口**：ToolRegistry 加载期强制过滤 + 工具包装层执行期强制拦截；删除 NONE 过滤模式；能力声明双方法 API（`resolveToolsForStreaming` / `resolveToolsForDirect`）
*   **应用编排模块（agent-demo-app）接入**：AgenticAgentFactory（非 HITL 路径）与 AgentExecutor.resolveHitlTools（HITL 路径）两个调用点改用能力声明方法
*   **工作流 ask 暂停能力**：WorkflowHITLState 新增 MODE_TOOL_CONFIRM 恢复模式；awaitHitlStream 注册 onToolConfirm 回调并转译为 WAITING_USER 暂停；批准/拒绝恢复通道
*   **Web/前端联动**：workflow_waiting 事件扩展 tool_confirm 载荷；前端 5 模式执行视图集成工具确认卡片（与单 Agent 确认卡片同构）
*   **旁路清理**：SimpleAgent 3 参数兼容方法的 NONE 旁路全量收口
*   **模板适配**：ResearchAnalyzeSummarize / SmartRouting / TaskBreakdownSupervisor 三个模板默认启用 hitlEnabled=true
*   **文档同步**：KNOWLEDGE_BASE.md 8.5 节、BR-PERM 规则、工具调用模块/应用编排模块业务说明书更新

### 8.2 不在本次范围（Out of Scope）

*   会话级 / Agent 角色级权限组合模式 - 沿用上期 Out of Scope，权限主体保持工具维度全局配置
*   参数级权限（如仅允许 FileRead 读特定目录）- 沿用上期决策
*   权限审计日志（谁在何时变更了权限、deny 拦截记录上报）- 沿用上期决策，本期仅 WARN 日志
*   权限配置导入导出 / 批量操作 - 沿用上期决策
*   用户身份认证与 RBAC 接入（Spring Security / JWT）- 项目定位为学习示例工程，沿用现状
*   非 HITL 路径的 ask 确认能力 - 设计上明确不支持：要用 ask 工具须启用 hitlEnabled（能力边界而非功能缺失）
