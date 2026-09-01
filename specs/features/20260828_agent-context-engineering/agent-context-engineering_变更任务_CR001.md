# Agent 变更记录: agent-context-engineering - CR-001

## 0. 变更概览 (Change Overview)
*   **变更标题**: Out of Scope 质量债清偿--hitl 规则单源化 / 降级默认值同步 / XML 标签体系 / 引导语去重
*   **变更类型**: Prompt 调优 (Prompt Tune，含程序支撑)
*   **变更原因**: 原迭代 Out of Scope 中的 4 项 P2 质量债（双处维护漂移、降级质量骤降、护栏呈现风格、注意力稀释）经 ai-agent-evolution 增量演进清偿；#4（{{tools}} 人读文本移除）因需真实模型评估基线暂缓，#5（方舟 API 不支持）与 #6（需 Redis/Milvus 架构演进）维持排除
*   **更新载体判定**: 指令（Prompt 制品重构）为主 + 程序（片段机制/默认值同步/引导语移除）为辅 -- 选择理由：规则合并与标签化是可语言化的制品重构（指令载体可测试、执行稳定）；片段展开机制、默认值同步、引导语移除属确定性程序逻辑（知识库载体无法承载）；远未达到需要参数（模型微调）的程度
*   **发起日期**: 2026-08-31
*   **开发方法**: TDD + EDD 双驱动 - 确定性组件按 TDD（Red-Green-Refactor）执行，概率性组件（Prompt 制品）按 EDD（BUILD->EVALUATE->TUNE->RE-EVALUATE）执行
*   **关联 Agent**: agent-context-engineering（20260828 迭代）
*   **关联文档**:
    -   需求文档: `specs/features/20260828_agent-context-engineering/agent-context-engineering.md`
    -   技术方案: `specs/features/20260828_agent-context-engineering/agent-context-engineering_技术方案.md`
    -   任务规划: `specs/features/20260828_agent-context-engineering/agent-context-engineering_任务规划.md`

## 1. 影响分析 (Impact Analysis)

### 1.1 需求影响
| 影响项 | 变更类型 | 详情 |
| :--- | :--- | :--- |
| AC-N04 | 新增 | hitl 双模板规则单源化（片段机制 + 共享片段，展开后语义零丢失） |
| AC-N05 | 新增 | 工具引导语单源（工具描述尾部通用引导移除，场景模板差异化承载） |
| AC-E03 | 新增 | 模板缺失降级默认值同步（AgentConfig 默认值与模板语义一致 + 守护测试） |
| AC-S04 | 新增 | 护栏段结构化标签等价（`<guardrails>` 包裹，条文内容与对抗拦截零回归） |
| 自主性级别 | 无变化 | 维持 L2；护栏规则条文、权限门控、HITL 机制零变更（仅呈现结构标签化） |

### 1.2 技术影响
| 影响层 | 影响范围 | 详情 |
| :--- | :--- | :--- |
| Prompt 工程架构 | 模块划分/注入点 | 新增片段加载机制（`{{include:xxx}}` 单层展开，`prompts/fragments/` 目录）；场景模板段落 XML 标签化 |
| 工具集成 | 修改编排 | convertToDescriptionText 尾部 2 行通用引导移除；工具消歧结构不变 |
| 记忆与上下文 | 无影响 | 【框架附件·TYPE】帧标记/摘要前缀/`<agent_status>` 标签均不改动 |
| 护栏与安全 | Prompt 层 | 护栏段结构标签化（条文内容零变更）；对抗集强制重跑验证等价性 |
| 评估框架 | 静态扫描套件 | PromptTemplateLoaderTest / HitlZeroRegressionTest / CacheStabilityBehaviorTest 断言适配 |

### 1.3 Prompt 制品影响
> 仅标注变更范围与验收标准，制品文本由 ai-agent-implementation 执行增量任务时落地

| 制品 | 变更类型 | 落地路由 | 验收要求 |
| :--- | :--- | :--- | :--- |
| hitl.txt（场景模板） | 修改 | `agent-prompt-designer` | 片段引用替换重复段落；few-shot 与护栏段标签化；展开后语义零丢失（AC-N04/S04） |
| hitl-guidance.txt（app 引导段） | 修改 | `agent-prompt-designer` | 片段引用替换重复段落；保留 app 差异化内容（AC-N04） |
| hitl-shared-rules.txt（新增片段） | 新增 | `agent-prompt-designer` | 承载 4 段公共规则（工具协议/askUser 5 规则/追问策略 4 条/错误处理 2 条）单源维护 |
| chat.txt / task-plan.txt / task-execute.txt / task-summary.txt | 修改 | `agent-prompt-designer` | XML 标签化（护栏段 `<guardrails>` 等）；引导语补齐（承载工具调用引导，AC-N05/S04） |
| 工具描述生成（ToolSchemaConverter 尾部引导） | 修改 | 程序变更（TDD） | 尾部通用引导移除；工具清单本体不变（AC-N05） |

### 1.4 代码与评估影响
**代码影响：**
| 文件路径 | 操作 | 影响说明 |
| :--- | :--- | :--- |
| `agent-demo-agent/src/main/java/com/agentdemo/agent/prompt/PromptTemplateLoader.java` | 修改 | 新增片段加载（`{{include:xxx}}` 单层展开，FRAGMENTS_DIR 常量与 loadFragment） |
| `agent-demo-agent/src/main/java/com/agentdemo/agent/config/AgentConfig.java` | 修改 | 6 个降级默认值与模板内容语义同步 |
| `agent-demo-tools/src/main/java/com/agentdemo/tools/registry/ToolSchemaConverter.java` | 修改 | convertToDescriptionText 尾部 2 行通用引导移除 |
| `agent-demo-agent/src/main/resources/prompts/fragments/hitl-shared-rules.txt` | 新增 | hitl 公共规则共享片段 |
| `agent-demo-agent/src/main/resources/prompts/scenarios/hitl.txt` 等 6 个模板 | 修改 | 片段引用 + XML 标签化（EDD 落地） |

**评估影响：**
| 评估资产 | 影响类型 | 说明 |
| :--- | :--- | :--- |
| 静态扫描套件（PromptTemplateLoaderTest / HitlZeroRegressionTest） | 需修改 | 断言适配片段展开与标签结构；few-shot 工具名扫描逻辑不变 |
| CacheStabilityBehaviorTest | 需重跑 | 片段展开后系统提示词指纹仍须字节级一致（AC-N01 回归） |
| 对抗性测试集（附件伪造/状态投毒/few-shot 虚构） | 需重跑 | AC-S04 等价性验证，拦截率 100% 保持 |
| 降级一致性守护测试 | 需新增 | AgentConfig 默认值与模板语义锚点断言（防漂移，AC-E03） |

### 1.5 回归风险评估
*   **变更类型对应回归级别**: Prompt 调优 = 相关数据集重跑；因涉及护栏段呈现结构，**加码执行 AC-S 全组 + AC-H01 重验（对抗集不缩减）**
*   **本次回归范围清单**:
    - 静态扫描套件全量（PromptTemplateLoaderTest / HitlZeroRegressionTest / CacheStabilityBehaviorTest / AttachmentForgeBehaviorTest）
    - 对抗性用例：附件伪造 2 例、状态投毒 2 例、few-shot 工具名扫描（幻觉一票否决）
    - AC 重验：AC-N01（缓存稳定，片段展开后指纹）/ AC-T02（工具清单冻结）/ AC-S01~S04 全组 / AC-H01（HITL 零回归）/ AC-E03（新增）
    - 模块全量：memory 21 / agent 186 / skill 93 / web+app 278（3 个既有失败为基线，非回归）
*   **评估指标基线（变更前）**: 静态扫描通过率 100%；缓存指纹字节级一致 100%；对抗拦截率 100%；模块测试基线如上
*   **候选验证门槛**: 候选版本必须通过全部回归评估（含陷阱任务与 Pass^k 稳定性指标，静态断言套件至少连续 3 次运行全绿）方可视为 release 候选；灰度期间指标劣化立即回滚
*   **安全边界检查**: 本次变更未触碰护栏规则条文/权限门控（仅结构标签包裹条文，内容零变更）；评估证据与候选变更隔离（静态扫描基线在变更前已固化）；护栏与门控机制未被削弱
*   **高风险区域**: ① 片段展开若引入非确定性（如顺序）会破坏冻结契约--展开须为纯静态文本替换；② 模板重构若误删规则条文会导致护栏失效--以展开后文本 diff 比对守护；③ ToolSchemaConverter 引导移除后若模板侧引导缺失，模型主动性下降--逐模板确认引导语承载

## 2. 需求变更详情 (Requirements Delta)
> 已同步就地插入需求文档 §7（AC-N04/N05/S04/E03），此处仅列索引

### 2.1 新增/修改的行为验收标准

#### 正常交互 (Normal) - AC-N
- **AC-N04**: hitl 双模板规则单源化（CR-001）-- Given 4 段重复规则 / When 片段机制上线 / Then 单源维护且展开后语义零丢失、无双处漂移
- **AC-N05**: 工具引导语单源（CR-001）-- Given 工具描述尾部通用引导与场景模板重复 / When 模型读取系统提示词 / Then 引导仅由场景模板差异化承载，无重复

#### 安全护栏 (Safety) - AC-S
- **AC-S04**: 护栏段结构化标签等价（CR-001）-- Given 护栏段 XML 标签包裹且条文不变 / When 对抗测试重跑 / Then 拦截行为与标签化前完全一致（100% 保持）

#### 边界降级 (Edge) - AC-E
- **AC-E03**: 模板缺失降级默认值同步（CR-001）-- Given 模板缺失降级 / When 降级路径触发 / Then 默认值与当前模板语义一致，守护测试防漂移

### 2.2 移除的内容（如有）
- 无能力移除；Out of Scope #1/#2/#3/#7 从"排除"转为"纳入"（需求文档 §8.2 已标记）

## 3. 技术变更详情 (Technical Delta)

### 3.1 Prompt 架构变更
- 新增片段加载机制：PromptTemplateLoader 支持 `{{include:fragment-name}}` 占位符（单层展开、不递归、缺失片段 WARN 并保留占位符原文降级不中断），片段置于 `prompts/fragments/`
- 场景模板 XML 语义标签化：`<guardrails>`（护栏段）/`<interaction_rules>`（交互规则）/`<tool_guidance>`（工具引导）等段落标签；**不改动**【框架附件·TYPE】帧标记、【历史对话摘要】前缀、`<agent_status>` 标签
- 引导语单源化：convertToDescriptionText 输出仅含工具清单本体

### 3.2 工具空间变更
| 操作 | 工具 | 说明 | 契约设计 |
| :--- | :--- | :--- | :--- |
| 无 | - | 工具集零变更 | 工具描述文本零变更（仅生成器尾部引导移除） |

### 3.3 护栏变更（如适用）
- 护栏规则条文内容零变更（呈现结构标签化）；护栏/门控机制未被削弱；对抗集全量重跑验证等价（AC-S04）

### 3.4 兼容性与回滚
*   **向前兼容**: 完全兼容--片段展开发生在模板加载层，运行时消息结构与既有会话/记忆数据无交互；模板重构后系统提示词内容语义等价（缓存指纹随部署一次性变化，属预期）
*   **Prompt 版本回滚**: 模板经 git 管理，回滚 = revert 模板与片段文件；回滚触发条件：对抗测试拦截率 < 100% 或静态扫描套件任一失败
*   **代码回滚方案**: PromptTemplateLoader 片段机制为新增能力（旧模板无 `{{include:}}` 时行为不变），回滚代码后新模板将保留占位符原文并 WARN--需与模板同步回滚

## 4. 增量开发任务 (Incremental Tasks)
> 任务编号自 Task-19 起（原规划最大 Task-18）；每任务 < 2h

### 阶段一：Prompt 制品变更 (Prompt Artifact Delta) - EDD

- [x] **Task-19**: PromptTemplateLoader 片段加载机制（程序支撑，先行）
    *   **说明**: 实现 `{{include:fragment-name}}` 占位符单层展开；新增 FRAGMENTS_DIR（`prompts/fragments/`）与片段加载；片段缺失时 WARN 并保留占位符原文（降级不中断）；单层不递归（防循环引用）
    *   **变更类型**: 修改
    *   **任务类型**: 确定性组件
    *   **验证策略**: TDD（Red-Green-Refactor）
    *   **通俗解释**: 模板里可以写"这里插入公共规则片段"，系统组装时自动把规则内容填进去，规则只需维护一份
    *   **涉及文件**: `agent-demo-agent/src/main/java/com/agentdemo/agent/prompt/PromptTemplateLoader.java`
    *   **测试文件**: `agent-demo-agent/src/test/java/com/agentdemo/agent/prompt/PromptTemplateLoaderTest.java`
    *   **对应AC**: AC-N04（机制支撑）
    *   **预估工时**: 30m
    *   **依赖**: 无
    *   **验证标准**（TDD RED 阶段的测试依据）:
        - [ ] 模板含 `{{include:hitl-shared-rules}}` 时，加载结果为片段内容替换占位符后的完整文本
        - [ ] 片段文件缺失时 WARN 并原样保留占位符，不抛异常（对话不中断）
        - [ ] 片段内再次包含 `{{include:}}` 时不二次展开（单层语义）
        - [ ] 不含占位符的模板行为与现状完全一致（零回归）

- [x] **Task-20**: hitl 规则单源化重构（制品）
    *   **说明**: 新增 `prompts/fragments/hitl-shared-rules.txt` 承载 4 段公共规则（工具协议权威声明/askUser 交互 5 规则/追问策略 4 条/工具错误处理 2 条）；hitl.txt 与 hitl-guidance.txt 重复段落替换为 `{{include:hitl-shared-rules}}`；hitl-guidance 保留 app 差异化内容（{{tools}} 占位与引导）
    *   **变更类型**: 新增 + 修改
    *   **任务类型**: 概率性组件
    *   **验证策略**: EDD（构建-评估-调优-再评估）
    *   **通俗解释**: 两份提示词里一字不差的重复规则合并成一份公共文件，改一处两边同时生效
    *   **涉及制品**: `hitl-shared-rules.txt`（新增）/ `hitl.txt` / `hitl-guidance.txt`
    *   **落地路由**: `agent-prompt-designer`（设计模式）
    *   **参考**: 本文档 Sec 1.3 / Sec 3.1
    *   **对应AC**: AC-N04
    *   **预估工时**: 30m（含评估轮次）
    *   **依赖**: Task-19
    *   **验证标准**（EDD EVALUATE 阶段的通过条件）:
        - [ ] 展开后两模板完整文本与重构前文本 diff 仅限结构性空白（语义零丢失）
        - [ ] 静态扫描：公共规则关键词（askUser/追问 3 次/confirm 2-4 选项）在展开后文本中全部存在
        - [ ] HitlZeroRegressionTest 全绿（few-shot 工具名扫描不受影响）

### 阶段二：工具与代码变更 (Tool & Code Delta) - TDD

- [x] **Task-21**: AgentConfig 降级默认值同步 + 守护测试
    *   **说明**: 将 AgentConfig 6 个降级默认值（defaultSystemPrompt/thinkingSystemPrompt/thinkingReactSystemPrompt/taskBreakdownPlanPrompt/taskExecutionSystemPrompt/taskSummaryPrompt）与当前模板内容语义对齐；新增一致性守护测试（锚点断言：默认值核心语义关键词 ⊆ 模板文本）防再次漂移
    *   **变更类型**: 修改
    *   **任务类型**: 确定性组件
    *   **验证策略**: TDD（Red-Green-Refactor）
    *   **通俗解释**: 提示词模板万一丢失时用的"备用内容"与正式模板保持同步，且有测试防止将来再次不一致
    *   **涉及文件**: `agent-demo-agent/src/main/java/com/agentdemo/agent/config/AgentConfig.java`
    *   **测试文件**: `agent-demo-agent/src/test/java/com/agentdemo/agent/config/AgentConfigFallbackConsistencyTest.java`（新增）
    *   **对应AC**: AC-E03
    *   **预估工时**: 30m
    *   **依赖**: 无（可与 Task-19 并行）
    *   **验证标准**（TDD RED 阶段的测试依据）:
        - [ ] 守护测试：每个默认值的关键语义锚点（如 defaultSystemPrompt 含"工具"引导语义）在对应模板文本中存在
        - [ ] 模板文件全部存在时，AgentConfig 默认值不被使用（现状回归）
        - [ ] 模拟模板缺失（测试 classpath 场景）时降级路径可用且 WARN 日志保留

- [x] **Task-22**: ToolSchemaConverter 尾部引导移除 + 模板引导语承载确认
    *   **说明**: 移除 convertToDescriptionText 尾部 2 行通用引导（"当问题需要实时信息…"与"调用工具后，在回答中简要提及…"）；逐场景确认模板侧工具调用引导覆盖（chat/hitl/task-execute/task-plan/task-summary + hitl-guidance），缺失处在 Task-23 标签化时一并补齐
    *   **变更类型**: 修改
    *   **任务类型**: 确定性组件
    *   **验证策略**: TDD（Red-Green-Refactor）
    *   **通俗解释**: 工具列表后面不再重复说"记得主动用工具"，这类话由每个场景的提示词自己说
    *   **涉及文件**: `agent-demo-tools/src/main/java/com/agentdemo/tools/registry/ToolSchemaConverter.java`
    *   **测试文件**: `agent-demo-tools/src/test/java/com/agentdemo/tools/registry/ToolSchemaConverterTest.java`
    *   **对应AC**: AC-N05
    *   **预估工时**: 25m
    *   **依赖**: Task-20（模板结构稳定后移除，确保引导已有承载）
    *   **验证标准**（TDD RED 阶段的测试依据）:
        - [ ] convertToDescriptionText 输出不含通用引导句（"主动调用对应工具"/"简要提及"关键词断言）
        - [ ] 工具清单本体（工具名+描述行）与现状逐字一致（零变更）
        - [ ] 各场景模板工具引导承载静态扫描通过（每模板含至少一条工具使用引导语）

- [x] **Task-23**: 场景模板 XML 语义标签化（制品）
    *   **说明**: agent 5 个场景模板 + hitl-guidance 采用结构化标签包裹段落（`<guardrails>` 护栏段 / `<interaction_rules>` 交互规则 / `<tool_guidance>` 工具引导）；护栏条文内容逐字保留；引导语缺失场景借此补齐（AC-N05 承载）；**边界**：不改【框架附件·TYPE】/摘要前缀/`<agent_status>`
    *   **变更类型**: 修改
    *   **任务类型**: 概率性组件
    *   **验证策略**: EDD（构建-评估-调优-再评估）
    *   **通俗解释**: 提示词各段落加上醒目的"标签牌"，模型能更准确定位规则、护栏等内容，且规则原文一个字不变
    *   **涉及制品**: `chat.txt` / `hitl.txt` / `task-plan.txt` / `task-execute.txt` / `task-summary.txt` / `hitl-guidance.txt`
    *   **落地路由**: `agent-prompt-designer`（设计模式）
    *   **参考**: 本文档 Sec 1.3 / Sec 3.1
    *   **对应AC**: AC-S04、AC-N05
    *   **预估工时**: 40m（含评估轮次）
    *   **依赖**: Task-20（在同一批模板上迭代，避免冲突）
    *   **验证标准**（EDD EVALUATE 阶段的通过条件）:
        - [ ] 标签化前后护栏条文 diff 为零（逐字比对）
        - [ ] 每模板标签配对闭合（`<x>`...`</x>` 静态扫描）
        - [ ] 每模板含 `<tool_guidance>` 引导段（AC-N05 承载确认）
        - [ ] 模板加载组装冒烟通过（组合结果含全部标签段）

### 阶段三：评估数据集扩充 (Evaluation Dataset Delta)
> 本次新增 AC 均可由静态扫描/守护测试覆盖（确定性断言），无新增概率性评估用例需求；对抗等价性用例复用既有对抗集（见阶段四）

- [x] **Task-24**: 对抗回归 + 缓存稳定重验（行为测试）
    *   **说明**: AC-S04 等价性验证：对抗集全量重跑（附件伪造/状态投毒/few-shot 虚构扫描）；缓存稳定重验（片段展开+标签化后系统提示词同会话字节级一致，AC-N01/T02 回归）；HITL 零回归（AC-H01）
    *   **变更类型**: 验证
    *   **任务类型**: 行为测试
    *   **验证策略**: 行为测试（评估数据集 + 对抗性测试）
    *   **涉及文件**: `CacheStabilityBehaviorTest` / `AttachmentForgeBehaviorTest` / `HitlZeroRegressionTest` / `AgentControllerUnifiedTest`
    *   **对应AC**: AC-S01~S04、AC-N01、AC-T02、AC-H01
    *   **预估工时**: 30m
    *   **依赖**: Task-20、Task-22、Task-23
    *   **验证标准**:
        - [ ] 对抗用例全部通过（拦截率 100% 保持，幻觉扫描一票否决项为零）
        - [ ] 同会话相邻请求系统提示词 SHA-256 指纹一致（100%）
        - [ ] HITL 确认流/事件载荷/追问计数既有断言全绿

### 阶段四：回归验证 (Regression Verification)

- [x] **Task-25**: 候选验证与全量回归
    *   **说明**: 按 Sec 1.5 回归范围执行全量回归；候选验证门槛：静态断言套件连续 3 次运行全绿（Pass^3 = 100%）；全模块测试基线比对；灰度与回滚方案就绪确认
    *   **变更类型**: 验证
    *   **任务类型**: 行为测试
    *   **验证策略**: 行为测试（评估数据集 + 对抗性测试）
    *   **涉及文件**: 全模块测试套件
    *   **对应AC**: 所有受影响 AC（N01/N04/N05/T02/S01~S04/E03/H01）
    *   **预估工时**: 30m
    *   **依赖**: Task-19~Task-24 全部
    *   **验证标准**:
        - [ ] 原有单元测试全量通过（memory 21 / agent 186 / skill 93 / web+app 278，3 个既有失败为基线非回归）
        - [ ] 静态扫描套件 Pass^3 = 100%（连续 3 次运行全绿）
        - [ ] 陷阱任务无幻觉（few-shot 工具名 ⊆ 真实工具集，一票否决项）
        - [ ] 对抗性测试全部通过（拦截率 100% 保持）
        - [ ] 已有对话场景行为无异常变化（AC-H01 零回归）
        - [ ] 灰度方案已就绪（模板经 git 管理，回滚 = revert；触发条件已写入 Sec 3.4）

## 5. 增量验收标准检查清单 (Incremental AC Checklist)

| 验收标准ID | 验收标准描述 | 状态 | 对应任务 | 操作 |
| :--- | :--- | :--- | :--- | :--- |
| AC-N04 | hitl 双模板规则单源化（展开后语义零丢失） | 已完成 | Task-19、Task-20 | 新增 |
| AC-N05 | 工具引导语单源（模板差异化承载） | 已完成 | Task-22、Task-23 | 新增 |
| AC-E03 | 降级默认值同步 + 守护测试防漂移 | 已完成 | Task-21 | 新增 |
| AC-S04 | 护栏段标签化等价（对抗拦截零回归） | 已完成 | Task-23、Task-24 | 新增 |

## 6. 变更总结 (Change Summary)
*   **总新增任务数**: 7 个（TDD 3 个 / EDD 2 个 / 行为测试 1 个 / 候选验证 1 个）
*   **预计总工时**: 215 分钟（约 3.5 小时）
*   **风险等级**: 低-中
*   **风险说明**: 片段展开的确定性（冻结契约）与护栏条文零变更是两大风险点，分别由 CacheStabilityBehaviorTest 指纹重验与标签化前后 diff 比对守护；EDD 任务各含评估轮次
*   **测试影响**: 需修改 3 个已有测试（PromptTemplateLoaderTest/ToolSchemaConverterTest 断言适配），新增 1 个测试（AgentConfigFallbackConsistencyTest），对抗集全量重跑
*   **评估基线变化**: 静态指标保持 100%（缓存一致/对抗拦截/扫描通过）；模板重构后系统提示词语义等价（指纹变化为预期一次性部署效应）
*   **预期效果**: 消除双处维护漂移（规则单源）、降级质量保障（默认值同步）、段落定位准确性提升（XML 标签）、注意力与 token 冗余治理（引导语去重）--Out of Scope 4 项 P2 质量债全部清偿
