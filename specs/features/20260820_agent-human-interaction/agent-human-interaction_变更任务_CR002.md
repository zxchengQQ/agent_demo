# Agent 变更记录: agent-human-interaction - CR-002

## 0. 变更概览 (Change Overview)
*   **变更标题**: HITL 交互卡片内嵌于 ReAct 推理过程区块
*   **变更类型**: 能力扩展 (Capability Extension)
*   **变更原因**: CR-001 同气泡续写落地后，交互卡片（AskUserCard/ConfirmCard）仍展示在气泡底部（ask-user-block），与 ReAct 推理过程割裂。希望将卡片内嵌到 ReAct 推理过程区块内对应工具调用步骤处，让"Agent 推理到某一步 → 调用工具 → 此处交互"的语义更清晰。
*   **更新载体判定**: **程序（代码）** — 前端展示逻辑与折叠状态管理，属于「程序与 Harness」载体；不涉及外部事实（无需知识库）、不涉及可语言化规则调整（无需 Prompt 制品）、不涉及模型能力（无需参数微调）。按「知识库 → 指令 → 程序 → 参数」最小充分顺序，程序是最轻充分载体。
*   **发起日期**: 2026-08-28
*   **开发方法**: TDD 双驱动 — 全部为前端确定性组件，按 TDD（Red-Green-Refactor）执行；无概率性 Prompt 制品变更。
*   **关联 Agent**: agent-human-interaction（单 Agent 人机交互能力）
*   **关联文档**:
    -   需求文档: `specs/features/20260820_agent-human-interaction/agent-human-interaction.md`
    -   技术方案: `specs/features/20260820_agent-human-interaction/agent-human-interaction_技术方案.md`
    -   变更任务计划 CR-001: `specs/features/20260820_agent-human-interaction/agent-human-interaction_变更任务_CR001.md`

## 1. 影响分析 (Impact Analysis)

### 1.1 需求影响
| 影响项 | 变更类型 | 详情 |
| :--- | :--- | :--- |
| AC-N04 HITL 决策后同气泡续写 | 修改 | 补充展示约束：交互卡片内嵌于 ReAct 推理过程区块内对应工具调用步骤处（而非气泡底部） |
| AC-N05 HITL 推理过程区块保持展开 | 新增 | 含 HITL 交互卡片的消息，ReAct 推理过程区块恒展开，卡片始终可见可交互/可回看 |
| 自主性级别 | 不变 | L2 - 确认后执行（机制语义不变，仅展示层变化） |

### 1.2 技术影响
| 影响层 | 影响范围 | 详情 |
| :--- | :--- | :--- |
| Prompt 工程架构 | 无影响 | 无 System Prompt / 输出契约 / Few-shot 变更 |
| 工具集成 | 无影响 | 工具清单与契约不变 |
| 记忆与上下文 | 无影响 | 后端 pending 状态、记忆、恢复链路均不变 |
| 护栏与安全 | 无影响 | 不触碰输入/Prompt/输出/工具执行层护栏；非安全边界变更 |
| 评估框架 | 前端测试扩充 | 新增/修改 MessageItem 渲染与折叠逻辑单元测试；本项目前端无独立评估数据集（与需求文档 8.3 节一致，前端为单元测试 + 人工验证） |

### 1.3 Prompt 制品影响
> 仅标注变更范围与验收标准，制品文本由 ai-agent-implementation 执行增量任务时落地

| 制品 | 变更类型 | 落地路由 | 验收要求 |
| :--- | :--- | :--- | :--- |
| 无 | 无 | - | 本变更不涉及任何 Prompt 制品 |

### 1.4 代码与评估影响
**代码影响：**
| 文件路径 | 操作 | 影响说明 |
| :--- | :--- | :--- |
| `agent-demo-frontend/src/components/MessageItem.vue` | 修改 | react-block 内对对应工具调用步骤内嵌渲染 AskUserCard/ConfirmCard；折叠逻辑调整（含 HITL 卡片恒展开）；ask-user-block 保留为无匹配时的兜底 |
| `agent-demo-frontend/src/components/AskUserCard.vue` / `ConfirmCard.vue` | 修改（如需要） | 内嵌样式适配（卡片宽度/边距适配工具卡片容器），默认不改变组件接口 |
| `agent-demo-frontend/src/components/components.test.ts` | 修改 | 卡片位置断言从 `.ask-user-block` 适配为 react-block 内；新增折叠/关联用例 |

**评估影响：**
| 评估资产 | 影响类型 | 说明 |
| :--- | :--- | :--- |
| 前端单元测试 `components.test.ts` | 需扩充/修改 | 新增用例覆盖 AC-N05（含卡片消息恒展开）与 AC-N04（卡片内嵌 react-block）；适配既有卡片位置断言 |
| 已有前端测试 | 需修改 | MessageItem 卡片渲染相关断言适配 |

### 1.5 回归风险评估
*   **变更类型对应回归级别**: 能力扩展 → 受影响数据集回归（前端测试全量 + HITL AC 人工验证）
*   **本次回归范围清单**: 前端测试全量（MessageItem / ChatWindow / session store / 相关组件测试）；HITL 相关 AC 人工验证（AC-N03、AC-N04、AC-N05、AC-T01、AC-T02、AC-H01、AC-H02）
*   **评估指标基线（变更前）**: 前端全量测试 705/705 通过；HITL AC（13 条 + CR-001 新增 AC-N04）已验证
*   **候选验证门槛**: 候选版本必须通过全部回归评估（前端测试全量 + AC-N04/N05 端到端手动验证，至少 3-5 次运行取均值）方可上线；灰度期间指标劣化立即回滚到上一版本
*   **安全边界检查**: 本次变更不触碰护栏规则与权限门控，非安全边界变更；评估证据与候选变更已隔离
*   **高风险区域**: react-block 折叠/展开逻辑（不能破坏无卡片消息的折叠行为）、卡片-步骤关联匹配（多轮追问、拒绝后续写新增 react-steps 的定位）、消息流渲染顺序

## 2. 需求变更详情 (Requirements Delta)
> 仅记录本次变更涉及的需求变化，已有需求不重复列出

### 2.1 新增/修改的行为验收标准

#### 正常交互 (Normal) — AC-N
- **AC-N04**（修改）: HITL 决策后同气泡续写
    - Given: Agent 已发起 HITL 交互（如确认卡片 ConfirmCard 的批准/拒绝，或 AskUserCard 的选项/文本回复），用户完成了决策或回复
    - When: Agent 从暂停点恢复执行并生成后续输出（流式 token）
    - Then: 后续输出应在含该交互卡片/问题的同一助手气泡内继续展示；不应新启独立助手气泡，也不应改变交互卡片的锁定态展示；**CR-002：交互卡片内嵌于该气泡的 ReAct 推理过程区块内对应工具调用步骤处展示（而非气泡底部）**

- **AC-N05**（新增）: HITL 推理过程区块保持展开
    - Given: 某助手消息的 ReAct 推理过程区块内包含 HITL 交互卡片（AskUserCard/ConfirmCard），消息处于"等待用户输入"暂停态或已完成续写
    - When: 用户查看该消息，或消息状态从 incomplete 变为 complete（流式结束）
    - Then: 该消息的 ReAct 推理过程区块应保持展开，交互卡片始终可见（等待态下可交互，完成后可回看锁定态）；不应因区块默认折叠导致卡片被隐藏而无法交互或回看；不含 HITL 卡片的普通消息维持原折叠语义

### 2.2 移除的内容（如有）
- 无（ask-user-block 保留为无匹配时的兜底回退，不删除能力）

## 3. 技术变更详情 (Technical Delta)
> 仅记录本次变更涉及的技术变化

### 3.1 Prompt 架构变更
- 无

### 3.2 工具空间变更
| 操作 | 工具 | 说明 | 契约设计 |
| :--- | :--- | :--- | :--- |
| 无 | - | 工具空间不变 | - |

### 3.3 护栏变更（如适用）
- 不适用（未触碰护栏规则与权限门控）

### 3.4 兼容性与回滚
*   **向前兼容**: 兼容已有对话上下文与 localStorage 会话数据（历史含 askUser 卡片的消息按新内嵌位置渲染；无匹配时回退底部卡片，不丢卡片）
*   **Prompt 版本回滚**: 不适用（无 Prompt 变更）
*   **代码回滚方案**: 回滚 `MessageItem.vue` / `AskUserCard.vue` / `ConfirmCard.vue` 至 CR-002 前版本即可恢复"卡片在气泡底部"的展示；前端为独立部署单元，回滚成本低

## 4. 增量开发任务 (Incremental Tasks)
> 任务编号从 CR-001 最后一个编号（Task-16）之后继续
> 每个任务耗时 < 2h (120m)

### 阶段一：Prompt 制品变更 (Prompt Artifact Delta) — EDD
> 本变更无 Prompt 制品变更，跳过此阶段

### 阶段二：工具与代码变更 (Tool & Code Delta) — TDD
> 按 RED → GREEN → REFACTOR 循环执行

- [x] **Task-17**: MessageItem.vue react-block 内嵌交互卡片 + 折叠逻辑
    *   **说明**: 1) 在 `MessageItem.vue` 的 `react-block` 内，对**对应工具调用步骤**的 `tool-card` 内嵌渲染 HITL 交互卡片：askUser 类（`message.askUserData.kind !== 'permission'`）按 `toolCall.toolName === 'askUser'` 匹配；权限确认（`kind === 'permission'`）按卡片 `toolName` 匹配工具卡片；2) 折叠逻辑调整：含 HITL 卡片（`message.askUserData` 存在）的消息 `react-block` 恒展开（不随 complete 折叠），不含卡片消息维持原语义（`isReactExpanded` 基于 status 与手动切换）；3) 原底部 `ask-user-block` 保留为无匹配时的兜底回退（不重复渲染：内嵌命中后不再渲染底部卡片）。
    *   **变更类型**: 修改
    *   **任务类型**: 确定性组件
    *   **验证策略**: TDD（Red-Green-Refactor）
    *   **通俗解释**: 做完这步后，AI 提问/确认的交互卡片会出现在 ReAct 推理过程的对应工具步骤里，而不是气泡最下面，对话流程更直观。
    *   **涉及文件**: `agent-demo-frontend/src/components/MessageItem.vue`
    *   **测试文件**: `agent-demo-frontend/src/components/components.test.ts`
    *   **参考**: 本文档 Sec 1.4 / Sec 3.4；现有 `react-block` / `tool-card` / `ask-user-block` 渲染（MessageItem.vue 246-292、392-414 行）
    *   **对应AC**: AC-N04、AC-N05
    *   **预估工时**: 90m
    *   **依赖**: 无
    *   **验证标准**（TDD RED 阶段的测试依据）:
        - [ ] 含 askUser 卡片的消息：卡片内嵌于 `toolName === 'askUser'` 的工具卡片内，且底部 `ask-user-block` 不重复渲染
        - [ ] 含权限确认卡片（kind=permission）的消息：卡片内嵌于 `toolName` 匹配的对应工具卡片内
        - [ ] 含 HITL 卡片的消息 status 从 incomplete 变为 complete 后，`react-block` 仍保持展开（卡片可见）
        - [ ] 不含 HITL 卡片的普通消息：`react-block` 维持原折叠语义（complete 后折叠、可手动展开）
        - [ ] 多轮追问场景：卡片内嵌于当前轮 askUser 对应步骤（不误匹配历史轮次）
        - [ ] 无匹配工具步骤时（兜底）：卡片回退渲染于底部 `ask-user-block`，不丢失
        - [ ] 卡片事件（reply/approve/deny）从内嵌位置正常向上传递

- [x] **Task-18**: AskUserCard/ConfirmCard 内嵌样式适配
    *   **说明**: 对 `AskUserCard.vue` / `ConfirmCard.vue` 做内嵌样式适配（如卡片在工具卡片容器内的宽度/边距/背景微调），保证内嵌到 react-block 工具步骤后视觉清晰、与工具卡片区分；**不改组件 props/events 接口**（对外契约不变，仅在样式层适配）。
    *   **变更类型**: 修改
    *   **任务类型**: 确定性组件
    *   **验证策略**: TDD（Red-Green-Refactor）
    *   **通俗解释**: 做完这步后，内嵌在推理过程里的交互卡片外观协调，不会因为位置变化而显得突兀。
    *   **涉及文件**: `agent-demo-frontend/src/components/AskUserCard.vue`、`agent-demo-frontend/src/components/ConfirmCard.vue`
    *   **测试文件**: 复用 `components.test.ts`（渲染断言）+ 视觉人工核对
    *   **参考**: 本文档 Sec 1.4；现有卡片样式（`ask-user-block` / `.ask-user-card` / `.confirm-card`）
    *   **对应AC**: AC-N05
    *   **预估工时**: 40m
    *   **依赖**: Task-17
    *   **验证标准**（TDD RED 阶段的测试依据）:
        - [ ] AskUserCard/ConfirmCard 组件 props/events 接口保持不变（既有组件测试无回归）
        - [ ] 内嵌渲染后卡片可见、可交互（按钮可点击），样式不溢出工具卡片容器
        - [ ] 既有卡片独立渲染场景（无 react-block 兜底时）样式无回归

### 阶段三：评估数据集扩充 (Evaluation Dataset Delta)
> 本项目前端无独立评估数据集（与 agent-human-interaction 需求文档 8.3 节一致，前端为单元测试 + 人工验证）；新增 AC 的评估通过 Task-17/18 的单元测试用例与 Task-19 回归验证中的端到端人工验证承载，故本阶段合并至 Task-17/18（TDD 用例即评估用例）与 Task-19（人工验证）。

### 阶段四：回归验证 (Regression Verification)
> 每个增量变更必须包含回归验证；回归范围按变更类型的回归矩阵执行

- [x] **Task-19**: 前端回归验证 + HITL AC 端到端人工验证
    *   **说明**: 1) 前端测试全量回归（`npm test`）：确认 MessageItem / ChatWindow / session store 等相关测试与既有全量测试无回归；2) 端到端人工验证 HITL 场景：歧义追问文本回复、关键操作确认批准/拒绝、confirm 型选项回复——验证卡片内嵌于 ReAct 推理过程对应步骤、含卡片消息推理区块恒展开、无卡片消息折叠语义不变、续写同气泡仍正常；3) 检查旧会话数据（历史 askUser 卡片）内嵌展示正常。
    *   **变更类型**: 验证
    *   **任务类型**: 行为测试
    *   **验证策略**: 行为测试（前端测试全量 + 人工端到端验证）
    *   **涉及文件**: 前端全量测试文件 + 人工验证脚本（复用 agent-human-interaction Task-13 AC 端到端验证清单 + CR-001 报告 §8.3 清单）
    *   **对应AC**: AC-N03、AC-N04、AC-N05、AC-T01、AC-T02、AC-H01、AC-H02
    *   **预估工时**: 60m
    *   **依赖**: Task-17、Task-18
    *   **验证结果**: ✅ 前端全量测试 712 个全部通过（52 个测试文件），无回归；⏳ HITL AC 端到端人工验证待真实运行环境执行（见完成报告 §8.3 验证清单）
    *   **验证标准**:
        - [ ] 前端全量测试通过（无回归）
        - [ ] AC-N04/AC-N05 端到端人工验证通过（至少 3-5 次运行全部一致：卡片内嵌位置 + 恒展开）
        - [ ] AC-N03/T01/T02/H01/H02 人工回归通过（追问、确认卡片、无法完成告知、取消 均正常）
        - [ ] 不含 HITL 卡片的普通消息折叠语义无回归（React 区块可正常折叠/展开）
        - [ ] 旧会话 localStorage 数据兼容（历史卡片内嵌展示正常、可回看）
        - [ ] 灰度方案已就绪（前端独立部署：灰度比例/观察指标/回滚触发条件——卡片位置错乱或折叠异常即回滚）

## 5. 增量验收标准检查清单 (Incremental AC Checklist)
> 仅包含本次变更涉及的验收标准

| 验收标准ID | 验收标准描述 | 状态 | 对应任务 | 操作 |
| :--- | :--- | :--- | :--- | :--- |
| AC-N04 | HITL 决策后同气泡续写（补充：卡片内嵌于 ReAct 推理过程区块对应工具步骤） | ✅ 已更新 | Task-17/18/19 | 修改 |
| AC-N05 | HITL 推理过程区块保持展开（含卡片消息恒展开，卡片可见可交互/可回看） | ✅ 已完成（自动化） | Task-17/18/19 | 新增 |

> 说明：AC-N04/AC-N05 已通过前端单元测试验证（自动化）；端到端人工验证待真实运行环境执行。

## 6. 变更总结 (Change Summary)
*   **总新增任务数**: 3 个（TDD 2 个 / 行为测试 1 个）
*   **预计总工时**: 190 分钟（约 3.2 小时）
*   **风险等级**: 低
*   **风险说明**: 纯前端展示层变更，后端与 Prompt 零改动；主要风险为 react-block 折叠逻辑（不得破坏无卡片消息语义）与卡片-步骤关联匹配（多轮追问/拒绝续写定位），均已列入 Task-17 验证标准与 Task-19 回归。
*   **测试影响**: 修改 1 个测试文件（components.test.ts 卡片位置断言），新增约 7 条前端测试用例
*   **评估基线变化**: 前端测试保持全绿；HITL AC 覆盖从 14 条增至 15 条（新增 AC-N05），AC-N04 展示约束增强
*   **预期效果**: 交互卡片内嵌于 ReAct 推理过程对应工具步骤，卡片始终可见可交互，"Agent 推理 → 调用工具 → 此处交互"语义清晰，对话流程更连贯
