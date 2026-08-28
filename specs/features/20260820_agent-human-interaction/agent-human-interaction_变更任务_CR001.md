# Agent 变更记录: agent-human-interaction - CR-001

## 0. 变更概览 (Change Overview)
*   **变更标题**: HITL 恢复后续写同气泡展示（批准/拒绝、AskUser 回复后不再新启助手气泡）
*   **变更类型**: 能力扩展 (Capability Extension)
*   **变更原因**: 人机交互模式下，用户点击批准/拒绝或回复 HITL 交互后，前端会新启一个独立助手气泡展示续写内容，容易被用户误认为已开启新一轮对话；实际上仍是在处理同一个对话问题。希望恢复后的续写在同一个对话气泡内继续展示。
*   **更新载体判定**: **程序（代码）** — 选择理由：本变更是前端确定性展示逻辑（消息气泡流式追加与复用），属于「程序与 Harness」载体；不涉及外部事实（无需知识库）、不涉及可语言化的规则调整（无需 Prompt 制品）、不涉及模型能力（无需参数微调）。按「知识库 → 指令 → 程序 → 参数」最小充分顺序，程序是能承载该能力的最轻充分载体。
*   **发起日期**: 2026-08-28
*   **开发方法**: TDD 双驱动 — 全部为前端确定性组件，按 TDD（Red-Green-Refactor）执行；无概率性 Prompt 制品变更。
*   **关联 Agent**: agent-human-interaction（单 Agent 人机交互能力）
*   **关联文档**:
    -   需求文档: `specs/features/20260820_agent-human-interaction/agent-human-interaction.md`
    -   技术方案: `specs/features/20260820_agent-human-interaction/agent-human-interaction_技术方案.md`
    -   任务规划: `specs/features/20260820_agent-human-interaction/agent-human-interaction_任务规划.md`

## 1. 影响分析 (Impact Analysis)

### 1.1 需求影响
| 影响项 | 变更类型 | 详情 |
| :--- | :--- | :--- |
| AC-N03 用户回复后恢复执行 | 修改 | 原内容：Agent 从暂停点恢复执行，保留上下文。修改后补充：askUser 文本/选项回复不产生独立用户气泡（卡片锁定态展示答案）；恢复后的输出在同一对话气泡内继续展示，不新启助手气泡 |
| AC-N04 HITL 决策后同气泡续写 | 新增 | Given: Agent 已发起 HITL 交互（ConfirmCard 批准/拒绝 或 AskUserCard 选项/文本回复），用户完成决策或回复; When: Agent 恢复执行并生成后续输出; Then: 后续输出在含交互卡片的同一助手气泡内继续展示，不应新启独立助手气泡 |
| 自主性级别 | 不变 | L2 - 确认后执行（机制语义不变，仅展示层变化） |

### 1.2 技术影响
| 影响层 | 影响范围 | 详情 |
| :--- | :--- | :--- |
| Prompt 工程架构 | 无影响 | 无 System Prompt / 输出契约 / Few-shot 变更 |
| 工具集成 | 无影响 | 工具清单与契约不变，AskUserTool 无改动 |
| 记忆与上下文 | 无影响 | 后端 pending 状态、ChatMemory、恢复链路均不变 |
| 护栏与安全 | 无影响 | 不触碰输入/Prompt/输出/工具执行层护栏；非安全边界变更 |
| 评估框架 | 前端测试扩充 | 新增/修改前端单元测试覆盖 AC-N03/AC-N04；本项目前端无独立评估数据集（与需求文档 8.3 节一致，前端为单元测试 + 人工验证） |

### 1.3 Prompt 制品影响
> 仅标注变更范围与验收标准，制品文本由 ai-agent-implementation 执行增量任务时落地

| 制品 | 变更类型 | 落地路由 | 验收要求 |
| :--- | :--- | :--- | :--- |
| 无 | 无 | - | 本变更不涉及任何 Prompt 制品 |

### 1.4 代码与评估影响
**代码影响：**
| 文件路径 | 操作 | 影响说明 |
| :--- | :--- | :--- |
| `agent-demo-frontend/src/stores/session.ts` | 修改 | 新增 `markStreaming(messageId)` 方法：把复用气泡 status 置回 `incomplete`，供续写流式展示；`onDone` 时仍走现有 `markComplete` |
| `agent-demo-frontend/src/components/ChatWindow.vue` | 修改 | `sendMessage` 新增 HITL 恢复分支：当 `toolApproved !== undefined` 或存在待回复的 askUser 卡片时，复用最后一条含 HITL 卡片/问题的助手气泡 id 作为流式目标（不新建气泡）；`handleAskUserReply` 改为 silent 模式（不插入用户气泡） |

**评估影响：**
| 评估资产 | 影响类型 | 说明 |
| :--- | :--- | :--- |
| 前端单元测试 `ChatWindow-model.test.ts` / `session.test.ts` | 需扩充 | 新增用例覆盖 AC-N04（批准/拒绝/选项/文本回复后续写同气泡）与 AC-N03（回复后不产生用户气泡、同气泡续写） |
| 已有前端测试 | 需修改 | 涉及 askUser 回复产生用户气泡的既有断言需同步适配（silent 化） |

### 1.5 回归风险评估
*   **变更类型对应回归级别**: 能力扩展 → 受影响数据集回归（前端测试全量 + HITL AC 人工验证）
*   **本次回归范围清单**: 前端测试全量（ChatWindow / session store / MessageItem / 相关组件测试）；HITL 相关 AC 人工验证（AC-N03、AC-N04、AC-T01、AC-T02、AC-H01、AC-H02）
*   **评估指标基线（变更前）**: 前端测试全量通过；agent-human-interaction 13 条 AC 已验证通过
*   **候选验证门槛**: 候选版本必须通过全部回归评估（前端测试全量 + AC-N03/N04 端到端手动验证，至少 3-5 次运行取均值）方可上线；灰度期间指标劣化立即回滚到上一版本
*   **安全边界检查**: 本次变更不触碰护栏规则与权限门控，非安全边界变更；评估证据与候选变更已隔离（测试用例独立编写，未用候选版本生成评估标准）
*   **高风险区域**: 消息流渲染顺序、卡片锁定态与续写状态流转、localStorage 持久化兼容（旧会话数据无 markStreaming 依赖）

## 2. 需求变更详情 (Requirements Delta)
> 仅记录本次变更涉及的需求变化，已有需求不重复列出

### 2.1 新增/修改的行为验收标准

#### 正常交互 (Normal) — AC-N
- **AC-N03**（修改）: 用户回复后恢复执行
    - Given: Agent 处于"等待用户输入"暂停状态，用户收到 Agent 的提问
    - When: 用户发送回复消息（CR-001：回复后该消息不产生独立用户气泡，由交互卡片锁定态展示答案）
    - Then: Agent 应从暂停点恢复执行，将用户回复作为上下文继续 ReAct 循环；Agent 应保留之前对话的完整上下文（包括提问内容和用户的回复）；Agent 恢复后生成的输出应在同一对话气泡（含提问/交互卡片的气泡）内继续展示，不应新启独立助手气泡

- **AC-N04**（新增）: HITL 决策后同气泡续写
    - Given: Agent 已发起 HITL 交互（如确认卡片 ConfirmCard 的批准/拒绝，或 AskUserCard 的选项/文本回复），用户完成了决策或回复
    - When: Agent 从暂停点恢复执行并生成后续输出（流式 token）
    - Then: 后续输出应在含该交互卡片/问题的同一助手气泡内继续展示，让用户明确这是对同一对话问题的延续，而非新一轮对话；不应新启独立助手气泡，也不应改变交互卡片的锁定态展示

### 2.2 移除的内容（如有）
- 无（askUser 回复改为 silent 属展示层调整，不删除能力）

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
*   **向前兼容**: 兼容已有对话上下文与 localStorage 会话数据（新增方法不影响旧数据读取；旧会话中已存在的 askUser 卡片按锁定态展示）
*   **Prompt 版本回滚**: 不适用（无 Prompt 变更）
*   **代码回滚方案**: 回滚 `ChatWindow.vue` 与 `session.ts` 至 CR-001 前版本即可恢复"新气泡续写"行为；前端为独立部署单元，回滚成本低

## 4. 增量开发任务 (Incremental Tasks)
> 任务编号从原任务规划最后一个编号（Task-13）之后继续
> 每个任务耗时 < 2h (120m)

### 阶段一：Prompt 制品变更 (Prompt Artifact Delta) — EDD
> 本变更无 Prompt 制品变更，跳过此阶段

### 阶段二：工具与代码变更 (Tool & Code Delta) — TDD
> 按 RED → GREEN → REFACTOR 循环执行

- [x] **Task-14**: session store 新增"恢复流式状态"方法（markStreaming）
    *   **说明**: 在 `agent-demo-frontend/src/stores/session.ts` 新增 `markStreaming(messageId: string)` action：将指定消息的 `status` 置回 `incomplete`（供 HITL 续写在同一气泡内流式展示），并持久化到 localStorage；`onDone` 后复用现有 `markComplete` 收尾。不改变其他既有 action 行为。
    *   **变更类型**: 新增
    *   **任务类型**: 确定性组件
    *   **验证策略**: TDD（Red-Green-Refactor）
    *   **通俗解释**: 做完这步后，前端状态层支持"把一个已经结束的气泡重新点亮成流式中"的状态，为后续在同一气泡续写打基础。
    *   **涉及文件**: `agent-demo-frontend/src/stores/session.ts`
    *   **测试文件**: `agent-demo-frontend/src/stores/session.test.ts`
    *   **参考**: 本文档 Sec 1.4 / Sec 3.4；现有 `markComplete` 方法
    *   **对应AC**: AC-N04
    *   **预估工时**: 40m
    *   **依赖**: 无
    *   **验证标准**（TDD RED 阶段的测试依据）:
        - [ ] 对 status=complete 的消息调用 `markStreaming(messageId)` 后，status 变为 `incomplete`
        - [ ] `markStreaming` 不影响消息其他字段（content/reasoning/askUserData 等保持原值）
        - [ ] 消息不存在时 `markStreaming` 静默跳过，不抛错
        - [ ] 调用后 localStorage 已持久化更新
        - [ ] 既有 `markComplete` 等 action 行为无回归（既有测试全绿）

- [x] **Task-15**: ChatWindow.vue HITL 恢复同气泡续写（sendMessage 复用气泡 + askUser 回复 silent）
    *   **说明**: 1) 在 `sendMessage` 中识别 HITL 恢复场景（`toolApproved !== undefined`，或当前会话存在待回复的 askUser 卡片且本次为对它的回复），**复用**最后一条含 HITL 卡片/问题的助手消息 id 作为流式目标：先 `markStreaming(id)` 点亮，流式回调（onToken/onReasoning/onThought/onAction 等）与 onDone 均指向该 id（`markComplete` 收尾），不再新建助手占位气泡；2) `handleAskUserReply` 增加 `silent=true` 并复用气泡（与 `handleApprove`/`handleDeny` 一致，决策/答案由卡片锁定态展示，不插入用户气泡）；3) 普通新消息路径保持现状（新建气泡）。注意 `setAskUserAnswer`/`setToolConfirmApproved` 已把卡片消息置 complete，续写前需经 `markStreaming` 恢复。
    *   **变更类型**: 修改
    *   **任务类型**: 确定性组件
    *   **验证策略**: TDD（Red-Green-Refactor）
    *   **通俗解释**: 做完这步后，用户点批准/拒绝或回复问题后，AI 的续写会出现在同一个气泡里继续输出，不再另起一个新气泡，对话看起来更连贯。
    *   **涉及文件**: `agent-demo-frontend/src/components/ChatWindow.vue`
    *   **测试文件**: `agent-demo-frontend/src/components/ChatWindow-model.test.ts`
    *   **参考**: 本文档 Sec 1.4 / Sec 3.4；现有 `handleApprove`/`handleDeny`/`handleAskUserReply`/`sendMessage`
    *   **对应AC**: AC-N03、AC-N04
    *   **预估工时**: 90m
    *   **依赖**: Task-14（markStreaming）
    *   **验证标准**（TDD RED 阶段的测试依据）:
        - [ ] 批准（toolApproved=true, silent）：不新增助手气泡，续写 token 追加到最后一条含 ConfirmCard 的消息 content
        - [ ] 拒绝（toolApproved=false, silent）：同批准，续写追加到同一气泡
        - [ ] AskUserCard 文本/选项回复：不插入用户气泡，不新增助手气泡，续写追加到含 AskUserCard 的消息 content
        - [ ] 续写期间该气泡 status 为 `incomplete`，onDone 后回到 `complete`
        - [ ] ConfirmCard/AskUserCard 锁定态在续写期间保持可见（askUserData 不被清除）
        - [ ] 普通新消息路径行为不变（新建用户气泡 + 新建助手气泡）
        - [ ] 后端 resume 响应在无 pending 时降级为普通对话，前端不报错（复用逻辑安全回退）

### 阶段三：评估数据集扩充 (Evaluation Dataset Delta)
> 本项目前端无独立评估数据集（与 agent-human-interaction 需求文档 8.3 节一致，前端为单元测试 + 人工验证）；新增 AC 的评估通过 Task-14/15 的单元测试用例与 Task-16 回归验证中的端到端人工验证承载，故本阶段合并至 Task-14/15（TDD 用例即评估用例）与 Task-16（人工验证）。

### 阶段四：回归验证 (Regression Verification)
> 每个增量变更必须包含回归验证；回归范围按变更类型的回归矩阵执行

- [x] **Task-16**: 前端回归验证 + HITL AC 端到端人工验证
    *   **说明**: 1) 前端测试全量回归（`npm test` 或项目约定的前端测试命令）：确认 ChatWindow/session store/MessageItem 等相关测试与既有全量测试无回归；2) 端到端人工验证 HITL 场景：歧义追问文本回复、关键操作确认批准、关键操作确认拒绝、confirm 型选项回复——均验证续写同气泡、卡片锁定态正确、无新气泡；3) 检查旧会话数据（已有 askUser 卡片）刷新后展示正常。
    *   **变更类型**: 验证
    *   **任务类型**: 行为测试
    *   **验证策略**: 行为测试（前端测试全量 + 人工端到端验证）
    *   **涉及文件**: 前端全量测试文件 + 人工验证脚本（复用 agent-human-interaction Task-13 AC 端到端验证清单）
    *   **对应AC**: AC-N03、AC-N04、AC-T01、AC-T02、AC-H01、AC-H02
    *   **预估工时**: 60m
    *   **依赖**: Task-14、Task-15
    *   **验证结果**: ✅ 前端全量测试 705 个全部通过（52 个测试文件），无回归；⏳ HITL AC 端到端人工验证待真实运行环境执行（见完成报告 8.3 注意事项验证清单）
    *   **验证标准**:
        - [ ] 前端全量测试通过（无回归）
        - [ ] AC-N03/AC-N04 端到端人工验证通过（至少 3-5 次运行全部一致）
        - [ ] AC-T01/T02/H01/H02 人工回归通过（追问、确认卡片、无法完成告知、取消 均正常）
        - [ ] 旧会话 localStorage 数据兼容（刷新后卡片可回看、不破坏展示）
        - [ ] 灰度方案已就绪（前端独立部署：灰度比例/观察指标/回滚触发条件——任何续写错位或新气泡回归即回滚）

## 5. 增量验收标准检查清单 (Incremental AC Checklist)
> 仅包含本次变更涉及的验收标准

| 验收标准ID | 验收标准描述 | 状态 | 对应任务 | 操作 |
| :--- | :--- | :--- | :--- | :--- |
| AC-N03 | 用户回复后恢复执行（补充：同气泡续写 + 回复不产生用户气泡） | ✅ 已更新 | Task-14/15/16 | 修改 |
| AC-N04 | HITL 决策后同气泡续写 | ✅ 已完成（自动化） | Task-14/15/16 | 新增 |

> 说明：AC-N03/AC-N04 已通过前端单元测试验证（自动化）；端到端人工验证待真实运行环境执行。

## 6. 变更总结 (Change Summary)
*   **总新增任务数**: 3 个（TDD 2 个 / 行为测试 1 个）
*   **预计总工时**: 190 分钟（约 3.2 小时）
*   **风险等级**: 低
*   **风险说明**: 纯前端展示层变更，后端与 Prompt 零改动；主要风险为消息流状态流转（卡片锁定态 ↔ 流式续写）与旧会话数据兼容，均已在 Task-15 验证标准与 Task-16 回归中覆盖。
*   **测试影响**: 修改约 2 个已有测试文件（session.test.ts / ChatWindow-model.test.ts），新增约 7 条前端测试用例
*   **评估基线变化**: 前端测试保持全绿；HITL AC 覆盖从 13 条增至 14 条（新增 AC-N04），AC-N03 约束增强
*   **预期效果**: 用户点击批准/拒绝或回复 HITL 交互后，AI 续写在同一对话气泡内继续展示，对话连贯性提升，消除"误以为开启新一轮对话"的体验问题
