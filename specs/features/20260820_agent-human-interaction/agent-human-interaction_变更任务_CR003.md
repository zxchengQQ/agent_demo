# Agent 变更记录: agent-human-interaction - CR-003

## 0. 变更概览 (Change Overview)
*   **变更标题**: 多次审批/追问记录保留（同一气泡内多次交互记录互不覆盖、可回看）
*   **变更类型**: 能力扩展 (Capability Extension)
*   **变更原因**: 多次审批/追问页面只保留最后一次记录——`message.askUserData` 为单对象，每次新的 tool_confirm/ask_user 事件覆盖上一次；CR-001 同气泡续写后多次审批复用同一气泡，导致此前已完成的审批记录（工具/参数 + 决策）丢失。希望多次审批记录逐一保留、可回看。
*   **更新载体判定**: **程序（代码）** — 前端数据模型（store）与渲染逻辑，属于「程序与 Harness」载体；不涉及外部事实（无需知识库）、不涉及可语言化规则调整（无需 Prompt 制品）、不涉及模型能力（无需参数微调）。按「知识库 → 指令 → 程序 → 参数」最小充分顺序，程序是最轻充分载体。
*   **发起日期**: 2026-08-28
*   **开发方法**: TDD 双驱动 — 全部为前端确定性组件，按 TDD（Red-Green-Refactor）执行；无概率性 Prompt 制品变更。
*   **关联 Agent**: agent-human-interaction（单 Agent 人机交互能力）
*   **关联文档**:
    -   需求文档: `specs/features/20260820_agent-human-interaction/agent-human-interaction.md`
    -   技术方案: `specs/features/20260820_agent-human-interaction/agent-human-interaction_技术方案.md`
    -   变更任务计划 CR-002: `specs/features/20260820_agent-human-interaction/agent-human-interaction_变更任务_CR002.md`

## 1. 影响分析 (Impact Analysis)

### 1.1 需求影响
| 影响项 | 变更类型 | 详情 |
| :--- | :--- | :--- |
| AC-N06 多次审批/追问记录保留 | 新增 | 同一气泡内连续多次工具审批（或多次 askUser 追问）均保留各自记录（工具/问题/参数 + 决策），内嵌展示于对应工具步骤，可回看 |
| AC-N04 HITL 决策后同气泡续写 | 修改 | 补充：同一气泡内可连续发生多次交互，每次记录独立保留，互不覆盖 |
| 自主性级别 | 不变 | L2 - 确认后执行（机制语义不变，仅前端数据模型与展示变化） |

### 1.2 技术影响
| 影响层 | 影响范围 | 详情 |
| :--- | :--- | :--- |
| Prompt 工程架构 | 无影响 | 无 System Prompt / 输出契约 / Few-shot 变更 |
| 工具集成 | 无影响 | 工具清单与契约不变 |
| 记忆与上下文 | 无影响 | 后端 pending 状态、记忆、恢复链路均不变 |
| 护栏与安全 | 无影响 | 不触碰输入/Prompt/输出/工具执行层护栏；非安全边界变更 |
| 评估框架 | 前端测试扩充 | 新增/修改 session store 与 MessageItem 渲染单元测试；本项目前端无独立评估数据集（与需求文档 8.3 节一致，前端为单元测试 + 人工验证） |

### 1.3 Prompt 制品影响
> 仅标注变更范围与验收标准，制品文本由 ai-agent-implementation 执行增量任务时落地

| 制品 | 变更类型 | 落地路由 | 验收要求 |
| :--- | :--- | :--- | :--- |
| 无 | 无 | - | 本变更不涉及任何 Prompt 制品 |

### 1.4 代码与评估影响
**代码影响：**
| 文件路径 | 操作 | 影响说明 |
| :--- | :--- | :--- |
| `agent-demo-frontend/src/types/index.ts` | 修改 | `Message` 新增 `askUserHistory?: AskUserData[]`（历史记录数组） |
| `agent-demo-frontend/src/stores/session.ts` | 修改 | `setToolConfirmData`/`setAskUserData` 由覆盖写改为"追加历史 + 同步镜像 askUserData"；`setToolConfirmApproved`/`setAskUserAnswer` 改为"更新最后一条历史 + 同步镜像" |
| `agent-demo-frontend/src/components/MessageItem.vue` | 修改 | 遍历 `askUserHistory` 逐条内嵌渲染于对应工具步骤（首个未占用匹配，延续 CR-002 内嵌布局）；无匹配记录兜底底部渲染（不丢记录） |

**评估影响：**
| 评估资产 | 影响类型 | 说明 |
| :--- | :--- | :--- |
| 前端单元测试 `session.test.ts` | 需扩充 | 新增历史追加/镜像/更新最后一条/旧数据兼容用例 |
| 前端单元测试 `components.test.ts` | 需扩充 | 新增多次审批各自内嵌/锁定/兜底渲染用例 |

### 1.5 回归风险评估
*   **变更类型对应回归级别**: 能力扩展 → 受影响数据集回归（前端测试全量 + HITL AC 人工验证）
*   **本次回归范围清单**: 前端测试全量（session store / MessageItem / ChatWindow / 相关组件测试）；HITL 相关 AC 人工验证（AC-N03、AC-N04、AC-N05、AC-N06、AC-T01、AC-T02、AC-H01、AC-H02）
*   **评估指标基线（变更前）**: 前端全量测试 712/712 通过；HITL AC（15 条，含 CR-001/CR-002 新增 AC-N04/N05）已验证
*   **候选验证门槛**: 候选版本必须通过全部回归评估（前端测试全量 + AC-N06 端到端手动验证，至少 3-5 次运行取均值）方可上线；灰度期间指标劣化立即回滚到上一版本
*   **安全边界检查**: 本次变更不触碰护栏规则与权限门控，非安全边界变更；评估证据与候选变更已隔离
*   **高风险区域**: `askUserHistory` 与 `askUserData` 镜像一致性、多记录与 react-step 匹配（重复工具名）、旧数据（仅有 askUserData）兼容、localStorage 持久化

## 2. 需求变更详情 (Requirements Delta)
> 仅记录本次变更涉及的需求变化，已有需求不重复列出

### 2.1 新增/修改的行为验收标准

#### 正常交互 (Normal) — AC-N
- **AC-N04**（修改）: HITL 决策后同气泡续写
    - Given: Agent 已发起 HITL 交互（如确认卡片 ConfirmCard 的批准/拒绝，或 AskUserCard 的选项/文本回复），用户完成了决策或回复
    - When: Agent 从暂停点恢复执行并生成后续输出（流式 token）
    - Then: 后续输出应在含该交互卡片/问题的同一助手气泡内继续展示；不应新启独立助手气泡，也不应改变交互卡片的锁定态展示；交互卡片内嵌于该气泡的 ReAct 推理过程区块内对应工具调用步骤处展示（而非气泡底部）；**同一气泡内可连续发生多次交互（多次审批/追问），每次交互记录独立保留，互不覆盖**

- **AC-N06**（新增）: 多次审批/追问记录保留
    - Given: 同一助手气泡内 Agent 连续发起多次 HITL 交互（如多次工具权限审批：工具A确认→批准→续跑→工具B确认→…；或多次 askUser 追问），`message.askUserData` 已由单对象扩展为"当前镜像 + 历史记录数组"
    - When: 用户逐一批准/拒绝或回复每次交互
    - Then: 每次交互的记录（工具名/问题/参数 + 决策 approved/answer）均被保留，并各自内嵌展示于对应工具调用步骤处，带各自锁定态可回看；不应只保留最后一次交互记录，也不应因新交互到来而覆盖/丢失此前已完成的审批记录

### 2.2 移除的内容（如有）
- 无

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
*   **向前兼容**: 兼容已有对话上下文与 localStorage 会话数据——旧数据仅有 `askUserData`（无 `askUserHistory`）时按既有单卡片渲染；新方法对无历史字段的消息按"追加"处理，不破坏旧逻辑（isWaitingForUserInput / ChatWindow 恢复检测 / 兜底渲染依赖 askUserData 镜像）
*   **Prompt 版本回滚**: 不适用（无 Prompt 变更）
*   **代码回滚方案**: 回滚 `types/index.ts` / `session.ts` / `MessageItem.vue` 至 CR-003 前版本即可恢复"仅保留最后一次记录"行为；前端为独立部署单元，回滚成本低

## 4. 增量开发任务 (Incremental Tasks)
> 任务编号从 CR-002 最后一个编号（Task-19）之后继续
> 每个任务耗时 < 2h (120m)

### 阶段一：Prompt 制品变更 (Prompt Artifact Delta) — EDD
> 本变更无 Prompt 制品变更，跳过此阶段

### 阶段二：工具与代码变更 (Tool & Code Delta) — TDD
> 按 RED → GREEN → REFACTOR 循环执行

- [x] **Task-20**: types + session store 历史记录模型（askUserHistory 追加/镜像/更新）
    *   **说明**: 1) `types/index.ts` 的 `Message` 新增 `askUserHistory?: AskUserData[]`；2) `session.ts` 改造 4 个方法：`setToolConfirmData`/`setAskUserData` 由覆盖写改为"追加新记录到 askUserHistory + 同步镜像 askUserData"；`setToolConfirmApproved`/`setAskUserAnswer` 改为"更新 askUserHistory 最后一条的决策/答案 + 同步镜像 askUserData"（内存同引用 + localStorage 持久化后需按最后一条索引同步）；3) 对无 askUserHistory 字段的旧消息按首次追加初始化，不破坏既有行为。
    *   **变更类型**: 修改
    *   **任务类型**: 确定性组件
    *   **验证策略**: TDD（Red-Green-Refactor）
    *   **通俗解释**: 做完这步后，每次 AI 发起的审批/提问都会像流水账一样被记下来，而不是新的一条盖掉旧的一条。
    *   **涉及文件**: `agent-demo-frontend/src/types/index.ts`、`agent-demo-frontend/src/stores/session.ts`
    *   **测试文件**: `agent-demo-frontend/src/stores/session.test.ts`
    *   **参考**: 本文档 Sec 1.4 / Sec 3.4；现有 `setAskUserData`/`setAskUserAnswer`/`setToolConfirmData`/`setToolConfirmApproved`（session.ts 425-558 行）
    *   **对应AC**: AC-N06
    *   **预估工时**: 90m
    *   **依赖**: 无
    *   **验证标准**（TDD RED 阶段的测试依据）:
        - [ ] `setAskUserData` 连续调用两次：`askUserHistory` 长度为 2，两条记录互不覆盖；`askUserData` 镜像等于最后一条
        - [ ] `setToolConfirmData` 连续调用两次（工具A、工具B）：`askUserHistory` 含两条 permission 记录，各自 toolName 正确
        - [ ] `setToolConfirmApproved(sessionId, true)` 只更新 `askUserHistory` **最后一条**的 approved，不改变此前记录
        - [ ] `setAskUserAnswer(sessionId, '确认')` 只更新最后一条历史的 answer，不改变此前记录
        - [ ] 镜像一致性：更新最后一条后 `askUserData` 与最后一条历史保持一致（含 localStorage 重新加载后）
        - [ ] 旧数据兼容：仅有 `askUserData`（无 askUserHistory）的消息首次调用追加方法时正常初始化，既有行为无回归
        - [ ] `isWaitingForUserInput` / `setAskUserAnswer` 等既有逻辑（依赖 askUserData 镜像）无回归

- [x] **Task-21**: MessageItem.vue 遍历历史内嵌渲染多次审批记录
    *   **说明**: 1) 将现有 `inlineCardTarget` 扩展为按历史逐条匹配：遍历 `askUserHistory`，每条记录用"首个未占用匹配"定位对应工具步骤（askUser 类匹配 `toolName==='askUser'`，权限类匹配 `toolName===记录工具名`，已被更早记录占用的工具调用不再复用）；2) react-block 内对每个命中步骤内嵌渲染对应记录卡片（各自锁定态）；3) 无匹配的记录兜底渲染于底部 `ask-user-block`（多条可堆叠，不丢记录）；4) 向后兼容：无 `askUserHistory` 时按既有单 `askUserData` 渲染。
    *   **变更类型**: 修改
    *   **任务类型**: 确定性组件
    *   **验证策略**: TDD（Red-Green-Refactor）
    *   **通俗解释**: 做完这步后，同一气泡里每次审批都会在推理过程的对应工具步骤处各显示一张卡片，批准/拒绝状态各自保留。
    *   **涉及文件**: `agent-demo-frontend/src/components/MessageItem.vue`
    *   **测试文件**: `agent-demo-frontend/src/components/components.test.ts`
    *   **参考**: 本文档 Sec 1.4 / Sec 3.4；现有 `inlineCardTarget`/`hasInlineCard` 与内嵌渲染（CR-002 Task-17）
    *   **对应AC**: AC-N06、AC-N04、AC-N05
    *   **预估工时**: 90m
    *   **依赖**: Task-20
    *   **验证标准**（TDD RED 阶段的测试依据）:
        - [ ] 多次权限审批（工具A 已批准、工具B 待决策）：两条记录各自内嵌于对应工具步骤，工具A 卡片锁定"已批准"，工具B 卡片显示批准/拒绝按钮
        - [ ] 多次 askUser 追问（两轮）：两条记录各自内嵌，前一问答锁定态展示，后一轮可交互
        - [ ] 同一工具名被批准两次（重复匹配）：两条记录分别内嵌于该工具的两次调用步骤（首个未占用匹配）
        - [ ] 部分记录无匹配工具步骤：匹配的内嵌渲染，无匹配的兜底渲染于底部 ask-user-block，所有记录均可见
        - [ ] 旧数据兼容：仅有 `askUserData`（无 askUserHistory）的消息按既有单卡片渲染（CR-002 行为无回归）
        - [ ] 含历史记录的消息 react-block 恒展开（AC-N05 延续）；不含卡片消息折叠语义无回归
        - [ ] 内嵌卡片事件（reply/approve/deny）从各自记录正常向上传递

### 阶段三：评估数据集扩充 (Evaluation Dataset Delta)
> 本项目前端无独立评估数据集（与 agent-human-interaction 需求文档 8.3 节一致，前端为单元测试 + 人工验证）；新增 AC 的评估通过 Task-20/21 的单元测试用例与 Task-22 回归验证中的端到端人工验证承载，故本阶段合并至 Task-20/21（TDD 用例即评估用例）与 Task-22（人工验证）。

### 阶段四：回归验证 (Regression Verification)
> 每个增量变更必须包含回归验证；回归范围按变更类型的回归矩阵执行

- [x] **Task-22**: 前端回归验证 + HITL AC 端到端人工验证
    *   **说明**: 1) 前端测试全量回归（`npm test`）：确认 session store / MessageItem / ChatWindow 等相关测试与既有全量测试无回归；2) 端到端人工验证多次审批场景：同一对话内连续 2-3 次工具权限审批，验证每次审批记录均内嵌展示于对应工具步骤、各自锁定态可回看、新审批不覆盖旧审批；3) 回归 CR-001/CR-002 行为：同气泡续写、卡片内嵌、恒展开、无卡片消息折叠语义；4) 检查旧会话数据（仅 askUserData）展示正常。
    *   **变更类型**: 验证
    *   **任务类型**: 行为测试
    *   **验证策略**: 行为测试（前端测试全量 + 人工端到端验证）
    *   **涉及文件**: 前端全量测试文件 + 人工验证脚本（复用 agent-human-interaction Task-13 AC 端到端验证清单 + CR-001/CR-002 报告 §8.3 清单）
    *   **对应AC**: AC-N03、AC-N04、AC-N05、AC-N06、AC-T01、AC-T02、AC-H01、AC-H02
    *   **预估工时**: 60m
    *   **依赖**: Task-20、Task-21
    *   **验证结果**: ✅ 前端全量测试 724 个全部通过（52 个测试文件），无回归；⏳ HITL AC 端到端人工验证待真实运行环境执行（见完成报告 §8.3 验证清单）
    *   **验证标准**:
        - [ ] 前端全量测试通过（无回归）
        - [ ] AC-N06 端到端人工验证通过（至少 3-5 次运行全部一致：多次审批记录逐一保留、各自锁定可回看）
        - [ ] AC-N04/N05 回归通过（同气泡续写、卡片内嵌、恒展开）
        - [ ] AC-N03/T01/T02/H01/H02 人工回归通过（追问、确认卡片、无法完成告知、取消 均正常）
        - [ ] 旧会话 localStorage 数据兼容（仅 askUserData 的旧消息按单卡片展示正常）
        - [ ] 灰度方案已就绪（前端独立部署：灰度比例/观察指标/回滚触发条件——记录丢失或渲染错乱即回滚）

## 5. 增量验收标准检查清单 (Incremental AC Checklist)
> 仅包含本次变更涉及的验收标准

| 验收标准ID | 验收标准描述 | 状态 | 对应任务 | 操作 |
| :--- | :--- | :--- | :--- | :--- |
| AC-N04 | HITL 决策后同气泡续写（补充：同一气泡多次交互记录互不覆盖） | ✅ 已更新 | Task-20/21/22 | 修改 |
| AC-N06 | 多次审批/追问记录保留（每次记录独立保留、内嵌展示、可回看） | ✅ 已完成（自动化） | Task-20/21/22 | 新增 |

> 说明：AC-N04/AC-N06 已通过前端单元测试验证（自动化）；端到端人工验证待真实运行环境执行。

## 6. 变更总结 (Change Summary)
*   **总新增任务数**: 3 个（TDD 2 个 / 行为测试 1 个）
*   **预计总工时**: 240 分钟（约 4 小时）
*   **风险等级**: 低
*   **风险说明**: 纯前端数据模型与渲染变更，后端与 Prompt 零改动；主要风险为 `askUserHistory` 与 `askUserData` 镜像一致性、多记录与 react-step 匹配（重复工具名）、旧数据兼容，均已在 Task-20/21 验证标准与 Task-22 回归中覆盖。
*   **测试影响**: 修改 2 个测试文件（session.test.ts / components.test.ts），新增约 13 条前端测试用例
*   **评估基线变化**: 前端测试保持全绿；HITL AC 覆盖从 15 条增至 16 条（新增 AC-N06），AC-N04 约束增强
*   **预期效果**: 多次审批/追问记录逐一保留、各自内嵌展示于对应工具步骤、带各自锁定态可回看，消除"只保留最后一次记录"的记录丢失问题
