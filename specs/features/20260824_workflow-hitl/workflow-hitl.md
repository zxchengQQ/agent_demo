# AI Agent 需求说明书 (AI Agent Requirements Document)

| 字段 | 内容 |
|------|------|
| 版本 | v0.1 |
| 作者 | AI Agent 产品架构师 |
| 日期 | 2026-08-24 |
| 变更记录 | v0.1 \| 2026-08-24 \| 初始版本，基于苏格拉底式需求澄清生成 \| AI Agent 产品架构师 |

## 1. 背景与价值 (Context & Value)

*   **背景**: agent-demo 项目已实现单 Agent ReAct 对话的 HITL 人机交互能力（20260820 迭代），包括 askUser 工具、HITLReActStream 显式 ReAct 暂停-恢复、ask_user SSE 事件和前端 ConfirmCard 确认卡片。应用编排层（agent-demo-app）已实现 5 种工作流模式（串行/并行/条件/循环/Supervisor）和失败暂停-断点恢复机制，但工作流执行过程中 Agent 无法向用户提问/确认，只能盲目推进或在失败后被动暂停。现有 HITL 需求文档明确标注"工作流编排 HITL - 下一期扩展"。
*   **目标**: 将 HITL 人机交互能力从单 Agent 对话扩展到工作流编排层，使工作流执行过程中任一 Agent 步骤均可向用户提问/确认，等待用户回复后继续执行。
*   **关联**: 前序迭代 `specs/features/20260820_agent-human-interaction/`（单 Agent HITL）；应用编排层 `specs/features/2026-08-13_应用编排层/`（P1~P3）。

## 2. Agent 角色定义 (Agent Persona)

### 2.1 身份定位

*   **角色名称**: 工作流 HITL 交互能力（非独立 Agent，是现有应用编排层的能力增强）
*   **角色类型**: 混合型（对话澄清 + 任务确认 + 工作流编排）
*   **身份描述**: 工作流执行过程中的"人工介入点"，当 Agent 遇到歧义、需执行关键操作、或到达模板预设检查点时，主动暂停工作流等待用户决策
*   **核心价值**: 避免工作流中 Agent 盲目推进导致错误传播（一个 Agent 的错误输出影响后续所有步骤）；避免有副作用的操作未经确认就执行；提升用户对工作流执行过程的掌控感

### 2.2 目标用户与场景

*   **目标用户**: agent-demo 的学习者、开发者、API 调用方
*   **使用场景**:
    *   工作流中某 Agent 缺少必要信息无法继续（如"研究"Agent 需要用户指定研究方向）
    *   工作流中某 Agent 即将执行有副作用的操作（如"分析"Agent 需要确认是否执行数据删除）
    *   模板开发者在关键决策点预设检查点（如"任务拆解-执行-汇总"模板中主控 Agent 拆解后需用户确认子任务分配）
    *   循环模式中每次迭代前确认是否继续（如"质量评分-修订"模板评分不达标后修订前确认）
*   **交互风格**: 简洁直接，主动提问，提供选项引导，与单 Agent HITL 保持一致

## 3. 能力边界与自主性 (Capability & Autonomy)

### 3.1 能力清单（In Scope）

*   **Agent 主动追问**: 工作流中任一 Agent 在执行 ReAct 循环时，识别到信息不完整或需确认，调用 askUser 工具暂停工作流
*   **模板预设检查点**: 模板开发者通过 `@HumanCheckpoint` 注解在 `@Agent` 接口方法上声明执行前需人工确认
*   **异步暂停-恢复**: HITL 触发后工作流暂停为 `WAITING_USER` 状态，保存完整执行上下文（AgenticScope + 已完成步骤 + 消息列表），用户回复后从暂停点恢复
*   **全模式覆盖**: 串行、并行、条件、循环、Supervisor 五种编排模式均支持 HITL
*   **混合交互形式**: 开放式追问用纯文本（type=text），确认型交互用结构化卡片（type=confirm），复用单 Agent HITL 的两种交互形式
*   **SSE 事件扩展**: 复用 `ask_user` 事件携带问题数据，新增 `workflow_waiting` / `workflow_resumed` 事件通知工作流级暂停/恢复

### 3.2 能力禁区（Out of Scope）

*   **超时降级机制** - 本期不实现独立的 HITL 超时策略，复用现有会话超时（30 分钟）自然清理。不强制超时后自动推进，与 L2（确认后执行）语义一致
*   **提示注入防护** - 本期不实现，用户回复视为可信输入，与现有对话流安全级别一致。工作流模板预定义工具集，用户无法注入未授权工具
*   **长期记忆** - 本期不实现跨会话的 HITL 历史记忆
*   **多工作流并发 HITL** - 本期同一 executionId 同时只能有一个 WAITING_USER 状态；不同 executionId 的 HITL 互不影响（按 executionId 隔离）

### 3.3 自主性级别

*   **级别**: L2 - 确认后执行
*   **说明**: 工作流中 Agent 遇到歧义时主动追问，执行有副作用的操作前向用户确认，模板预设检查点处暂停等待用户决策，用户确认后才继续执行。常规无副作用操作（如查询、计算）Agent 自主推进。与单 Agent HITL（L2）保持一致，用户认知统一。
*   **人类介入点**:
    *   Agent 主动追问：Agent 缺少必要信息无法继续时
    *   模板预设检查点：标注了 `@HumanCheckpoint` 的方法执行前
    *   关键操作确认：Agent 即将执行有副作用的操作时
    *   追问超限：Agent 追问 3 次后仍无法获得有效信息时，终止任务并告知用户

### 3.4 知识边界

*   **知识范围**: 依赖工作流模板中各 Agent 的知识范围（由 AgentDefinition 的 roleName×scenarioName 决定）
*   **超出边界处理**: 当用户请求超出 Agent 能力时，Agent 应告知用户无法处理的原因，编排引擎继续后续步骤或根据模板逻辑降级

## 4. 工具与动作空间 (Tools & Action Space)

### 4.1 工具清单

| 工具名称 | 用途 | 输入 | 输出 | 副作用 | 权限 | 限制 | 常见失败场景 |
|---------|------|------|------|--------|------|------|-------------|
| askUser | 向用户发起提问/确认（复用单 Agent HITL 工具） | type（text/confirm）、question（问题文本）、options（选项列表，confirm 类型必填） | 用户回复文本 | 无（仅暂停工作流执行） | 只读（不修改任何系统状态） | 同一问题最多追问 3 次；同一工作流执行同时只能有一个 WAITING_USER 状态 | 用户长时间不回复（依赖会话超时清理） |
| @HumanCheckpoint | 模板预设检查点注解（非工具，是 Agent 方法注解） | 无（注解元数据） | 确认型 askUser 数据（type=confirm） | 无（仅暂停工作流执行） | 只读 | 仅可标注在 @Agent 接口方法上 | 不适用 |

### 4.2 工具间关系

*   **依赖关系**: askUser 工具的输出（用户回复）作为 Agent 后续 ReAct 推理的 Observation 输入；@HumanCheckpoint 的输出（用户确认/拒绝）决定编排引擎是否执行该方法
*   **互斥关系**: askUser 与其他工具不能在同一 ReAct 步骤中同时调用（askUser 会暂停工作流执行）；@HumanCheckpoint 检查点暂停期间不允许其他 Agent 步骤执行
*   **编排策略**:
    *   **Agent 主动追问**: AgentExecutor 执行 Agent 时，Agent 在 ReAct 循环中调用 askUser 工具，AgentExecutor 拦截调用（不执行方法体），保存执行上下文，推送 SSE 事件，工作流暂停
    *   **模板预设检查点**: AgentExecutor 在执行 Agent 方法前通过反射检测 @HumanCheckpoint 注解，若存在则构造确认型 askUser 数据，暂停工作流等待用户确认

### 4.3 工具选择策略

*   Agent 主动追问时通过 ReAct 推理自主判断是否需要调用 askUser 工具，与单 Agent HITL 逻辑一致
*   开放式追问（如缺少参数）使用 type=text，用户通过输入框回复
*   确认型交互（如关键操作确认、模板检查点）使用 type=confirm，携带选项列表，用户通过点击按钮回复
*   模板预设检查点固定使用 type=confirm（确认/取消二选一）
*   无副作用的查询/计算操作无需确认，Agent 直接执行

## 5. 推理流程与记忆策略 (Reasoning & Memory)

### 5.1 推理模式

*   **主要模式**: 工作流编排策略（串行/并行/条件/循环/Supervisor）驱动 Agent 逐步执行，每个 Agent 内部使用 ReAct（推理-行动）循环
*   **模式选择策略**: HITL 不改变现有编排策略，仅在 Agent 执行流程中注入暂停-恢复拦截点
*   **最大执行步骤**: 复用现有 `agent.max-iterations=10`（每个 Agent 步骤内），askUser 调用不消耗 ReAct 迭代次数（暂停状态不消耗迭代）

### 5.2 澄清策略

*   **主动澄清条件**:
    *   Agent 在 ReAct 推理中识别到缺少执行任务所需的必要信息
    *   Agent 即将执行有副作用的操作（删除、修改、发送等）
    *   模板预设检查点到达（@HumanCheckpoint 注解方法即将执行）
*   **自主推进条件**:
    *   无副作用的查询/计算操作
    *   用户指令明确、参数完整
    *   Agent 有足够信息自主决策
    *   非检查点步骤
*   **最大澄清轮次**: Agent 主动追问同一问题最多 3 次，每次应提供具体选项引导用户；3 次后终止当前 Agent 任务并告知用户。模板预设检查点为单次确认（用户拒绝后终止工作流，不重新提问）

### 5.3 短期记忆（对话内）

*   **上下文保留**: 复用现有工作流执行上下文（AgenticScope 共享变量 + ResumableExecutionState 快照），跨暂停-恢复周期保持完整上下文
*   **AgenticScope 保持**: 工作流暂停时保存 AgenticScope 状态（已完成步骤的输出变量），恢复后后续步骤可访问
*   **Agent 消息列表保持**: 暂停时保存 Agent 的 ReAct 消息列表（Thought/Action/Observation），恢复后 Agent 从暂停点继续推理

### 5.4 长期记忆（跨会话）

*   **是否需要**: 否
*   **记忆内容**: 不适用
*   **遗忘策略**: 不适用

## 6. 护栏安全与降级策略 (Guardrails & Fallback)

### 6.1 内容安全规则

*   **必须拒绝的请求**: 继承现有 Agent 的内容安全规则
*   **标准拒绝话术**: 继承现有 Agent 的拒绝话术
*   **敏感话题处理**: 继承现有 Agent 的处理策略

### 6.2 幻觉防护

*   **回答依据**: 继承现有 Agent 策略（可基于自身知识 + 工具/知识库数据）
*   **无结果处理**: Agent 应如实告知用户无法获取相关信息，不编造内容

### 6.3 敏感信息处理

*   **输入侧**: 用户回复视为可信输入，与现有对话流安全级别一致
*   **输出侧**: Agent 提问内容不应包含不必要的敏感信息

### 6.4 工具失败降级

| 工具/机制 | 失败场景 | 降级策略 | 是否转人工 |
|---------|---------|---------|-----------|
| askUser（Agent 主动追问） | 用户长时间不回复 | 依赖会话超时（30 分钟）自然清理 WAITING_USER 状态，工作流标记为 TIMEOUT | 否 |
| askUser（Agent 主动追问） | 用户回复仍然模糊/无效 | 最多追问 3 次，每次提供选项引导；3 次后终止当前 Agent 任务 | 否 |
| @HumanCheckpoint | 用户拒绝确认 | 工作流状态设为 TERMINATED，不执行后续步骤 | 否 |
| @HumanCheckpoint | 用户长时间不回复 | 依赖会话超时（30 分钟）自然清理 | 否 |
| HITL 恢复 | 恢复后 Agent 执行失败 | 工作流从 WAITING_USER 恢复为 RUNNING 后，Agent 重试耗尽转为 PAUSED（失败暂停），可通过现有 resume 重试 | 否 |

### 6.5 人机协作机制

*   **升级条件**: 工作流恢复后 Agent 仍无法完成任务（如工具失败、能力超出边界），或 Agent 追问 3 次后仍无法获得有效信息
*   **升级方式**: Agent 直接告知用户无法继续的原因和建议的后续步骤（无人工转接机制，本项目为学习示例）
*   **交接信息**: Agent 应提供当前工作流执行状态、已完成的步骤、失败原因

### 6.6 提示注入防护

*   **攻击识别**: 本期不实现提示注入检测，用户回复视为可信输入
*   **处理策略**: 与现有对话流安全级别一致（本项目为学习示例，无认证机制）
*   **工具返回防护**: 不适用（askUser 工具返回的是用户回复，非外部系统数据）
*   **工具集防护**: 工作流模板预定义工具集，用户无法在运行时注入未授权工具

### 6.7 权限与身份校验

*   **身份确认机制**: 继承项目现有策略（无认证机制，学习示例工程）
*   **防越权策略**: 继承项目现有策略（工作流执行按 executionId 隔离）
*   **数据隔离**: 工作流级隔离，WAITING_USER 状态按 executionId 隔离，不同工作流执行互不影响

## 7. 验收标准 (Acceptance Criteria)

> **重要**：以下验收标准是后续 Prompt 工程、工具开发和评估测试的直接依据。每条 AC 使用 Given-When-Then 格式，Given 描述上下文/状态，When 描述触发条件，Then 描述预期行为。AI Agent 的 AC 关注**行为约束**而非精确输出。

### 7.1 正常交互流程 (Normal Interaction)

- [ ] **AC-N01**: Agent 主动追问（工作流场景）
    - Given: 工作流正在执行中，某个 Agent 步骤的 Agent 在 ReAct 推理过程中识别到信息不完整或需确认，工作流处于 RUNNING 状态
    - When: Agent 在工作流执行中调用 askUser 工具
    - Then: 编排引擎应拦截 askUser 调用（不执行工具方法体），暂停工作流并将状态设为 WAITING_USER，保存当前执行上下文（AgenticScope + 已完成步骤 + 消息列表）；通过 SSE 推送 ask_user 事件（含 type/question/options）和 workflow_waiting 事件；Agent 不应在信息不完整时盲目推进任务或猜测参数

- [ ] **AC-N02**: 模板预设检查点触发
    - Given: 工作流模板中某 Agent 方法标注了 @HumanCheckpoint 注解，工作流执行到该 Agent 步骤
    - When: 编排引擎在执行该 Agent 方法前通过反射检测到 @HumanCheckpoint 注解
    - Then: 编排引擎应在方法执行前暂停工作流，将状态设为 WAITING_USER，通过 SSE 推送 ask_user 事件（type=confirm，携带确认/取消选项）和 workflow_waiting 事件；编排引擎不应跳过注解直接执行方法

- [ ] **AC-N03**: 用户回复后恢复执行
    - Given: 工作流处于 WAITING_USER 状态，用户收到 Agent 的提问/确认
    - When: 用户通过前端发送回复（文本回复或按钮点击）
    - Then: 编排引擎应将工作流状态从 WAITING_USER 恢复为 RUNNING，推送 workflow_resumed 事件；对于 Agent 主动追问，用户回复作为 Observation 回填 Agent 继续 ReAct 循环；对于模板检查点，用户确认后继续执行该步骤，用户拒绝后终止工作流；编排引擎应保留之前执行的完整上下文（AgenticScope 共享变量 + 已完成步骤输出）

- [ ] **AC-N04**: Supervisor 模式下主控 Agent 拆解后检查点确认
    - Given: Supervisor 模式工作流执行中，主控 Agent 完成任务拆解（supervisor_plan 事件已推送），被调度的 Worker Agent 方法标注了 @HumanCheckpoint
    - When: 编排引擎准备执行 Worker Agent 方法前检测到 @HumanCheckpoint 注解
    - Then: 编排引擎应暂停工作流，推送 ask_user（含拆解结果摘要和确认请求）和 workflow_waiting 事件；用户确认后执行 Worker Agent，用户拒绝后工作流状态设为 TERMINATED

### 7.2 工具选择与调用 (Tool Selection & Invocation)

- [ ] **AC-T01**: 工作流 askUser 拦截机制
    - Given: 工作流执行中某 Agent 调用 askUser 工具，工作流处于 RUNNING 状态
    - When: AgentExecutor 检测到 Agent 调用的工具名为 askUser
    - Then: AgentExecutor 应拦截该调用（不执行工具方法体），保存当前执行上下文（Agent 消息列表 + AgenticScope 状态 + 已完成步骤输出）到 HITL 快照；通过 WorkflowEventPublisher 推送 ask_user 和 workflow_waiting 事件；AgentExecutor 不应让 askUser 工具方法体执行

- [ ] **AC-T02**: @HumanCheckpoint 注解检测
    - Given: 工作流模板中某 Agent 方法标注了 @HumanCheckpoint，编排策略即将调度该 Agent 执行
    - When: AgentExecutor 在执行 Agent 方法前通过反射检测到 @HumanCheckpoint 注解
    - Then: AgentExecutor 应在方法执行前暂停，构造确认型 askUser 数据（type=confirm，携带确认/取消选项）；AgentExecutor 不应在未确认时执行标注了 @HumanCheckpoint 的方法

### 7.3 安全与护栏 (Safety & Guardrails)

- [ ] **AC-S01**: 模板检查点拒绝后终止工作流
    - Given: 工作流处于 WAITING_USER 状态（模板检查点触发），用户收到确认请求
    - When: 用户点击"拒绝/取消"按钮
    - Then: 工作流状态应设为 TERMINATED，推送 workflow_terminated 事件；工作流不应继续执行后续步骤；前端应显示"用户拒绝，工作流已终止"提示

- [ ] **AC-S02**: Agent 主动追问次数上限
    - Given: 工作流中某 Agent 已对同一问题追问 3 次，用户每次的回复仍然模糊或无效
    - When: Agent 达到第 3 次追问上限
    - Then: 编排引擎应返回错误 Observation 让 Agent 终止当前任务；Agent 应明确告知用户"由于信息不完整，无法继续执行该任务"；Agent 不应继续追问同一问题；Agent 应准备好接收新的用户指令

- [ ] **AC-S03**: WAITING_USER 状态下的操作保护
    - Given: 工作流处于 WAITING_USER 状态，等待用户回复
    - When: 在等待期间有其他请求尝试操作该工作流（如启动新执行、非恢复性操作）
    - Then: 编排引擎应拒绝非恢复/终止操作，返回工作流状态错误提示；编排引擎不应允许在 WAITING_USER 状态下启动新的执行

### 7.4 边界与降级 (Edge Cases & Fallback)

- [ ] **AC-E01**: 会话超时清理 WAITING_USER 状态
    - Given: 工作流处于 WAITING_USER 状态，30 分钟内用户未发送任何回复
    - When: 会话超时清理定时任务执行（每 5 分钟扫描一次）
    - Then: 工作流的 WAITING_USER 状态应随会话一起被清理；工作流状态应标记为 TIMEOUT 或 TERMINATED；不应残留过期的等待状态；用户下次操作时应看到工作流已终止

- [ ] **AC-E02**: 并行模式下多个 Agent 同时触发 HITL
    - Given: 并行模式下多个 Agent 同时执行，两个 Agent 几乎同时触发 askUser
    - When: 第一个 Agent 触发 HITL 后工作流暂停
    - Then: 其他并行 Agent 的 HITL 请求应排队等待（不丢弃，不聚合）；用户回复后工作流恢复，排队的 HITL 请求按序处理（用户回复作为工作流级上下文共享）；编排引擎应确保同一时间只有一个 WAITING_USER 状态；编排引擎不应丢弃或忽略排队的 HITL 请求

- [ ] **AC-E03**: 循环模式 HITL 暂停与恢复
    - Given: 循环模式工作流执行到第 N 次迭代时触发 HITL 暂停（如修订 Agent 调用 askUser 确认是否继续修订）
    - When: 用户回复后工作流恢复执行
    - Then: 编排引擎应保留循环计数器状态（当前迭代次数）；恢复后应从暂停的迭代点继续，而非从头开始循环；编排引擎不应因暂停-恢复而重置迭代计数

### 7.5 记忆与上下文 (Memory & Context)

- [ ] **AC-M01**: 跨暂停-恢复的 AgenticScope 上下文保持
    - Given: 工作流执行到第 3 步时触发 HITL 暂停，AgenticScope 中已包含前 2 步的输出
    - When: 用户回复后工作流恢复执行
    - Then: 编排引擎应加载暂停前的完整执行上下文（AgenticScope 共享变量 + 已完成步骤输出 + Agent 消息列表）；恢复后的步骤应能访问之前步骤的所有输出；编排引擎不应因暂停-恢复而丢失任何上下文

- [ ] **AC-M02**: Agent 消息列表跨暂停-恢复保持
    - Given: Agent 在 ReAct 循环中调用 askUser 暂停，Agent 的消息列表包含之前的 Thought/Action/Observation
    - When: 用户回复后工作流恢复，Agent 继续 ReAct 循环
    - Then: Agent 应能访问暂停前的完整消息列表（包括所有 Thought/Action/Observation）；Agent 应将用户回复整合到后续推理中作为新的 Observation；Agent 不应因暂停-恢复而丢失之前的推理上下文

### 7.6 人机协作 (Human-in-the-Loop)

- [ ] **AC-H01**: 用户主动终止等待中的工作流
    - Given: 工作流处于 WAITING_USER 状态，用户不想继续等待
    - When: 用户点击"终止"按钮
    - Then: 工作流状态应设为 TERMINATED；编排引擎应清理等待状态和 HITL 快照；前端应返回工作流列表或模板列表；编排引擎不应残留过期的等待状态

- [ ] **AC-H02**: 恢复后 Agent 失败的处理
    - Given: 工作流从 WAITING_USER 恢复为 RUNNING 后，恢复点后的 Agent 步骤执行失败
    - When: Agent 重试耗尽
    - Then: 工作流状态应从 RUNNING 转为 PAUSED（失败暂停，非 WAITING_USER）；编排引擎应保存失败步骤快照（ResumableExecutionState）；用户可通过现有恢复机制（resume）重试；此场景与正常失败暂停行为一致，不产生新的 HITL 交互

---

### AC 覆盖度自检
- [x] 正常交互的每个核心流程都有对应 AC（AC-N01~N04）
- [x] 每个有副作用的工具都有对应的安全 AC（AC-S01 覆盖检查点拒绝，AC-S02 覆盖追问超限）
- [x] 每条能力禁区都有对应的拒绝说明（3.2 节）
- [x] 每个工具的失败降级策略都有对应 AC（AC-E01 覆盖超时，AC-E02 覆盖并行 HITL，AC-E03 覆盖循环恢复，AC-H02 覆盖恢复后失败）
- [x] 多轮对话上下文保持有对应 AC（AC-M01~M02）
- [x] 人机协作的每个升级条件都有对应 AC（AC-H01~H02）
- [x] 提示注入防护有对应说明（6.6 节，本期不实现，信任用户回复）
- [x] 权限与防越权有对应说明（6.7 节，继承项目现有策略）
- [x] 每条 AC 的 Then 部分包含"应该做"和"不应该做"双向约束
- [x] 所有 AC 与第 3.3 节的自主性级别（L2）一致
- [x] 非功能性约束（追问上限 3 次、会话超时 30 分钟）已明确
- [x] 评估方式与通过标准已定义（第 9 节）

## 8. 范围界定 (Scope)

### 8.1 本次范围（In Scope）

*   **WAITING_USER 状态扩展**: 在现有工作流执行状态机中新增 WAITING_USER 状态，与 PAUSED（失败暂停）区分
*   **@HumanCheckpoint 注解**: 新增 Java 注解，标注在 @Agent 接口方法上声明执行前需人工确认
*   **AgentExecutor HITL 拦截**: AgentExecutor 执行 Agent 时拦截 askUser 工具调用和 @HumanCheckpoint 注解检测
*   **SSE 事件扩展**: 复用 ask_user 事件 + 新增 workflow_waiting / workflow_resumed 事件
*   **前端工作流等待 UI**: 工作流执行视图增加 WAITING_USER 状态的等待 banner + 确认卡片/文本回复交互
*   **HITL 快照保存-恢复**: 扩展 ResumableExecutionState 或新增 HITL 专用快照，保存 AgenticScope + 已完成步骤 + Agent 消息列表
*   **全模式适配**: 串行/并行/条件/循环/Supervisor 五种编排策略均适配 HITL 暂停-恢复
*   **预置模板检查点示例**: 修改 1-2 个现有预置模板，增加 @HumanCheckpoint 注解，提供可演示的端到端场景
*   **会话超时清理扩展**: 现有会话超时清理定时任务扩展支持 WAITING_USER 状态清理

### 8.2 不在本次范围（Out of Scope）

*   **超时降级机制** - 本期复用会话超时（30 分钟）自然清理，不实现独立的 HITL 超时策略和自动推进
*   **提示注入防护** - 本期信任用户回复，后续可增加关键词过滤和标记隔离
*   **长期记忆** - 本期不实现跨会话的 HITL 历史记忆
*   **多工作流并发 HITL** - 本期同一 executionId 同时只能有一个 WAITING_USER 状态，不同 executionId 互不影响
*   **参数输入表单** - 检查点需要的参数在执行前的模板参数表单中预设，不在运行时动态收集
*   **HITL 结果聚合展示** - 并行模式下不聚合多个 Agent 的问题，仅第一个生效其余排队

## 9. 评估方式 (Evaluation)

| AC 编号 | 评估方式 | 验证方法 |
|---------|---------|---------|
| AC-N01 | 人工 + 自动 | 执行工作流触发 Agent 追问，验证 WAITING_USER 状态和 SSE 事件；单元测试验证拦截逻辑 |
| AC-N02 | 人工 + 自动 | 执行含 @HumanCheckpoint 的模板，验证暂停和确认请求；单元测试验证注解检测 |
| AC-N03 | 人工 + 自动 | 在 WAITING_USER 状态发送回复，验证恢复执行和上下文保持；单元测试验证状态恢复 |
| AC-N04 | 人工 | 执行 Supervisor 模板，验证主控拆解后检查点确认流程 |
| AC-T01 | 自动 | 单元测试验证 AgentExecutor 拦截 askUser 调用、保存上下文 |
| AC-T02 | 自动 | 单元测试验证反射检测 @HumanCheckpoint 注解 |
| AC-S01 | 人工 + 自动 | 拒绝检查点，验证工作流 TERMINATED；单元测试验证状态转换 |
| AC-S02 | 自动 | 单元测试验证追问计数和终止逻辑 |
| AC-S03 | 自动 | 单元测试验证 WAITING_USER 状态下的操作拒绝 |
| AC-E01 | 自动 | 单元测试模拟会话超时，验证 WAITING_USER 状态清理 |
| AC-E02 | 人工 + 自动 | 并行模式触发多 HITL，验证排队和恢复；单元测试验证并发控制 |
| AC-E03 | 人工 + 自动 | 循环模式触发 HITL，验证迭代计数保持；单元测试验证循环恢复 |
| AC-M01 | 人工 + 自动 | 验证恢复后 AgenticScope 完整；单元测试验证快照保存/加载 |
| AC-M02 | 人工 + 自动 | 验证恢复后 Agent 消息列表完整；单元测试验证消息列表保存/加载 |
| AC-H01 | 人工 | 在 WAITING_USER 状态点击终止，验证状态和清理 |
| AC-H02 | 人工 + 自动 | 恢复后触发 Agent 失败，验证转为 PAUSED；单元测试验证状态转换 |
