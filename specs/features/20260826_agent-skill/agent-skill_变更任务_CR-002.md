# Agent 变更记录: agent-skill - CR-002

## 0. 变更概览 (Change Overview)
*   **变更标题**: `/skill` 技能展示方式调整——用户消息保留原始输入（所见即所得）
*   **变更类型**: 能力扩展 (Capability Extension)
*   **变更原因**: 用户反馈——选中技能后不希望 AI 在对话框插入"已加载技能"提示消息，而应在用户消息中展示使用了哪个技能（如 `/skill data-query-assistant 帮我查看一下今天的新闻` 原样展示在用户气泡）。
*   **发起日期**: 2026-08-27
*   **开发方法**: TDD — 本次变更仅前端确定性组件（展示逻辑），无概率性 Prompt 制品变更
*   **关联 Agent**: agent-skill（Skill 能力域）
*   **关联文档**:
    -   需求文档: `specs/features/20260826_agent-skill/agent-skill.md`
    -   技术方案: `specs/features/20260826_agent-skill/agent-skill_技术方案.md`
    -   任务规划: `specs/features/20260826_agent-skill/agent-skill_任务规划.md`

## 1. 影响分析 (Impact Analysis)

### 1.1 需求影响
| 影响项 | 变更类型 | 详情 |
| :--- | :--- | :--- |
| AC-N07 | 修改 | `/skill` 指令展示方式：由"对话框可视化区块 + 剥离前缀"改为"用户消息气泡保留原始输入展示（含技能名）"；发送给 LLM 仍剥离前缀 |
| 能力清单 3.1 激活状态透明 | 修改 | "/skill 指令指定在对话框内以可视化区块展示" → "在用户消息气泡保留原始输入展示" |
| 自主性级别 | 无影响 | 保持 L3（Skill 激活决策/加载/脚本执行均不变） |

### 1.2 技术影响
| 影响层 | 影响范围 | 详情 |
| :--- | :--- | :--- |
| Prompt 工程架构 | 无影响 | 无 System Prompt / 输出契约变更（激活段注入、目录段均不变） |
| 工具集成 | 无影响 | 脚本工具注册/合并链路不变；后端 skills 传递零改动 |
| 记忆与上下文 | 无影响 | 会话级 skills 状态、激活集逻辑不变 |
| 护栏与安全 | 无影响 | 脚本护栏、内容校验不变 |
| 评估框架 | 无影响 | 无新增评估用例；技能加载行为不变 |

### 1.3 Prompt 制品影响
> 本次变更不涉及 Prompt 制品（无 System Prompt / Tool 描述 / 输出格式契约变更）

### 1.4 代码与评估影响
**代码影响：**
| 文件路径 | 操作 | 影响说明 |
| :--- | :--- | :--- |
| `agent-demo-frontend/src/components/ChatWindow.vue` | 修改 | sendMessage：/skill 指令场景用户消息气泡保存原始输入（含前缀），streamChat 仍发送剥离前缀 rest；onSkillActivated 移除"已加载技能"assistant 消息插入 |
| `agent-demo-frontend/src/components/ChatWindow-skill-command.test.ts` | 修改 | 适配：断言用户消息保留原始输入；移除"已加载技能"assistant 消息断言（改为断言无该消息） |

**评估影响：**
| 评估资产 | 影响类型 | 说明 |
| :--- | :--- | :--- |
| 前端 vitest（695 用例基线） | 需适配 | ChatWindow-skill-command.test.ts 适配；其余用例不受影响 |
| skill-eval 数据集 | 无影响 | 技能加载行为不变（正常交互/对抗用例不涉及展示形式断言） |

### 1.5 回归风险评估
*   **变更类型对应回归级别**: 能力扩展 = 受影响数据集回归
*   **本次回归范围清单**: 前端全量 vitest（695 基线）+ vue-tsc 类型检查 + 后端 skill/agent/web 测试冒烟（skills 传递链路零改动确认）
*   **评估指标基线（变更前）**: 前端 vitest 695/695 全绿；后端 skill 85 / agent 165 / web 115 全绿
*   **高风险区域**: ChatWindow sendMessage /skill 分支（气泡内容 vs LLM 内容分离）；skill_activated 回调（移除提示消息后激活状态记录仍保留）

## 2. 需求变更详情 (Requirements Delta)

### 2.1 新增/修改的行为验收标准

#### 正常交互 (Normal) — AC-N
- **AC-N07**: `/skill` 前缀指令强制指定技能（CR-002 修改展示方式）
    - Given: 平台已启用 Skill 能力，存在启用状态的技能（如「数据查询助手」）
    - When: 用户在消息输入框输入 `/skill 数据查询助手 帮我查 XX 数据`
    - Then: 应：解析 `/skill` 前缀，将指定技能设为会话手动指定（与选择器指定同语义），用户消息气泡**保留原始输入展示（含技能名，所见即所得）**，发送给 LLM 的消息剥离指令前缀；不应：将指令前缀作为普通消息发给 LLM，或指定技能不存在时静默忽略（应提示并保持自动模式），或以 AI 提示消息/可视化区块形式额外展示技能

### 2.2 移除的内容（如有）
- 移除 skill_activated 事件触发插入的 AI"已加载技能：X"提示消息（改为用户消息保留原始输入展示，技能加载反馈由用户气泡承载）

## 3. 技术变更详情 (Technical Delta)

### 3.1 Prompt 架构变更
- 无（激活段/目录段提示词不变）

### 3.2 工具空间变更
| 操作 | 工具 | 说明 | 契约设计 |
| :--- | :--- | :--- | :--- |
| 无 | - | - | - |

### 3.3 护栏变更（如适用）
- 无

### 3.4 兼容性与回滚
*   **向前兼容**: 是。仅调整 /skill 指令的用户气泡展示与移除 AI 提示消息；后端 skills 传递、激活语义、脚本工具链路全部不变；选择器场景行为不变。
*   **Prompt 版本回滚**: 不涉及（无 Prompt 制品变更）
*   **代码回滚方案**: 还原 ChatWindow.vue 的 sendMessage /skill 分支与 onSkillActivated 回调即可；无持久化变更。

## 4. 增量开发任务 (Incremental Tasks)
> 任务编号从原任务规划最后一个编号（Task-41，CR-001 末）之后继续

### 阶段二：工具与代码变更 (Tool & Code Delta) — TDD

- [x] **Task-42**: ChatWindow `/skill` 指令用户消息保留原始输入 + 移除 AI"已加载技能"提示
    *   **说明**: ①`sendMessage` 中 `/skill` 指令场景：用户消息气泡 `addMessage` 使用**原始输入**（含 `/skill 技能名前缀`），`streamChat` 仍发送剥离前缀后的 rest；仅指令无内容时仍不发送。②`onSkillActivated` 回调移除"已加载技能：X"assistant 消息插入，仅保留 `addActivatedSkill`（记录到助手消息）与 `addActivatedSkillToSession`（会话级激活状态，供排除逻辑）。
    *   **变更类型**: 修改
    *   **任务类型**: 确定性组件
    *   **验证策略**: TDD（Red-Green-Refactor）
    *   **通俗解释**: 用户发 `/skill 数据查询助手 帮我查今天的新闻` 时，自己的消息气泡里能看到完整原文（含技能名），AI 不再额外弹一条"已加载技能"提示。
    *   **涉及文件**: `agent-demo-frontend/src/components/ChatWindow.vue`
    *   **测试文件**: `agent-demo-frontend/src/components/ChatWindow-skill-command.test.ts`
    *   **参考**: 本文档 Sec 1.4 / Sec 3
    *   **对应AC**: AC-N07
    *   **预估工时**: 45m
    *   **依赖**: 无
    *   **验证标准**（TDD RED 阶段的测试依据）:
        - [ ] 发送 `/skill data-query-assistant 帮我查数据` → 用户消息 content 等于完整原始输入（含 `/skill data-query-assistant ` 前缀）；streamChat 收到的消息等于"帮我查数据"
        - [ ] 触发 skill_activated 后，会话消息中**不**出现"已加载技能：数据查询助手"assistant 消息；激活状态（activatedSkillsBySession）仍记录
        - [ ] 选择器选中技能（无 /skill 前缀）发送普通消息 → 用户消息保持原样，不额外展示技能
        - [ ] 技能不存在 `/skill 幽灵技能 查一下` → cmd-error 提示，会话级 skills 不设置（保持自动模式）

### 阶段四：回归验证 (Regression Verification)

- [x] **Task-43**: 回归验证
    *   **说明**: 按第 1.5 节声明的回归范围清单执行回归。前端全量 vitest（基线 695）+ vue-tsc；后端 skill/agent/web 测试冒烟（确认 skills 传递链路零改动无回归）。
    *   **变更类型**: 验证
    *   **任务类型**: 行为测试
    *   **验证策略**: 行为测试（vitest + 后端冒烟）
    *   **涉及文件**: `agent-demo-frontend/src/**/*.test.ts`、后端三模块测试
    *   **对应AC**: AC-N07
    *   **预估工时**: 30m
    *   **依赖**: Task-42
    *   **验证标准**:
        - [ ] 前端全量 vitest ≥ 695 通过（无回归）
        - [ ] vue-tsc 无新增错误（仅基线既有 useWorkflowStream.spec 错误）
        - [ ] 后端 skill/agent/web 测试全量通过（无回归）
        - [ ] 手动验收：/skill 指令用户气泡显示原文、无 AI 提示消息、技能仍生效

## 5. 增量验收标准检查清单 (Incremental AC Checklist)

| 验收标准ID | 验收标准描述 | 状态 | 对应任务 | 操作 |
| :--- | :--- | :--- | :--- | :--- |
| AC-N07 | `/skill` 前缀指令强制指定技能（用户消息保留原始输入展示，发送仍剥离前缀） | ✅ 满足 | Task-42/43 | 修改 |

## 6. 变更总结 (Change Summary)
*   **总新增任务数**: 2 个（TDD 1 个 / 行为测试 1 个）
*   **预计总工时**: 75 分钟（约 1.25 小时）
*   **风险等级**: 低
*   **风险说明**: 仅前端展示层调整，后端 skills 传递与激活链路零改动；风险集中在 /skill 分支气泡内容与 LLM 内容分离（已由测试用例覆盖）。
*   **测试影响**: 修改 1 个已有测试文件（ChatWindow-skill-command.test.ts），新增断言用例；无新增评估用例。
*   **评估基线变化**: 无（技能加载行为不变，前端 vitest 保持 695 全绿）
*   **预期效果**: 用户发送 `/skill 技能名 内容` 时，自己的消息气泡完整显示原文（含技能名，所见即所得），不再出现 AI"已加载技能"提示消息；技能加载反馈由用户气泡承载，体验统一。
