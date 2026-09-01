# AI Agent 代码审查报告: agent-context-engineering CR-001（Out of Scope 质量债清偿）

| 字段 | 内容 |
|------|------|
| 审查人 | ai-agent-code-review（代码审查技能） |
| 日期 | 2026-08-31 |
| 审查范围基准 | CR-001 变更任务（Task-19~25）实际变更文件（git status 核对） |
| 关联文档 | `specs/features/20260828_agent-context-engineering/`（需求/技术方案/变更任务 CR001）、`docs/dev-records/20260828_agent-context-engineering_CR001_report.md` |

## 0. 审查结论

**通过（Pass）**

CR-001 交付物可靠：片段加载机制（确定性 TDD 轨）实现正确且充分测试；模板单源化与 XML 标签化（EDD 轨）经静态语义断言验证（本次变更的可验证属性本质是确定性的——展开完整性与条文保留，非模型概率行为）。护栏条文经 git 比对**逐字保留**，对抗拦截 100% 保持。4 条新增 AC 全部满足。审查发现 2 个 Minor（已 AUTO-FIX），无 Critical / Important。

## 1. 做得好的部分 (Strengths)

- **片段展开的降级安全**（`PromptTemplateLoader.expandFragments`：163-181）：片段缺失时 WARN + 原样保留占位符（降级不中断），单层不递归防循环引用，`quoteReplacement` 防特殊字符注入——满足不可简化清单的降级逻辑要求。
- **模板单源化彻底**：hitl.txt 与 hitl-guidance.txt 的 12 条公共规则（工具协议/askUser 5 规则/追问策略 4 条/错误处理 2 条）统一收敛到 `hitl-shared-rules.txt`，双模板经 `{{include:}}` 引用，无内联副本残留（`hitlGuidance_共享片段展开` 断言"### 追问策略"不再内联）。
- **护栏等价性可验证**：审查独立执行 git 比对，5 个模板护栏条文与原文件**逐字一致**（唯一差异为新增 `</guardrails>` 闭合标签），AC-S04 有客观证据而非自评。
- **守护测试双向锚点**（`AgentConfigFallbackConsistencyTest.assertFallbackAligned`：56-65）：同时断言"模板含锚点 + 默认值含锚点"，模板演进后默认值未同步即失败——防漂移设计优于单向断言。
- **跨模块展开正确性**：hitl-guidance（app 模块）引用片段（agent 模块）能正确展开（`HitlZeroRegressionTest` 3/3 通过），且审查确认无绕过 `PromptTemplateLoader` 直接读模板原始内容的路径（include 不会出现"未展开"泄漏到运行时）。

## 2. 范围与意图比对

> [CLEAN]

| 比对项 | 结果 | 说明 |
| :--- | :--- | :--- |
| 越权改动 | 无 | 全部变更对应 CR-001 的 Task-19~25，无顺手重构 |
| 遗漏任务 | 无 | 7 个任务全部落地并有测试/制品证据；Task-22 的模板引导承载确认已补测试 |

## 3. 链路一致性（实际变更 vs CR-001 文档声明文件）

> [一致]

| 类型 | 文件路径 | 说明 |
| :--- | :--- | :--- |
| 一致 | PromptTemplateLoader / AgentConfig / ToolSchemaConverter | 3 个代码文件全部落地 |
| 一致 | hitl-shared-rules.txt（新增）+ 5 agent 模板 + hitl-guidance | 6 个制品全部落地 |
| 多出 | `src/test/resources/prompts/` 下 4 个场景 + 2 个片段 | 测试专用资源（test classpath，不进 main jar），验证片段机制所需，合理 |

## 4. 已自动修复项 (AUTO-FIXED)

- [AUTO-FIXED] `PromptTemplateLoader.expandFragments` 用 `StringBuffer`（线程安全但本地变量无并发需求）→ 改 `StringBuilder`（`PromptTemplateLoader.java:168`）
- [AUTO-FIXED] `missingFragmentShouldKeepPlaceholderAndNotThrow` 中 `if (result != null)` 守卫可导致断言被跳过（假阳性风险）→ 移除守卫，改为直接断言（`PromptTemplateLoaderTest.java`）

> 两项均无行为变更（StringBuffer/StringBuilder 在单线程字符串拼接语义等价；测试守卫移除后断言更严格），符合 AUTO-FIX 红线。

## 5. 需决策的关键问题 (Action Required)

### Critical（阻断）

> 无。

### Important（修复后通过）

> 无。

## 6. 次要问题与建议 (Minor)

1. 位置：`PromptTemplateLoader.java` INCLUDE_PATTERN `\\{\\{include:([\\w-]+)}}`——`}}` 未转义但 Java 正则将其按字面量解析（实测通过）。建议后续统一加 `\\}\\}` 提升可读性（当前行为正确，仅风格）。
2. 位置：`AgentConfigFallbackConsistencyTest` 锚点"ReAct"为英文关键词，若模板演进改用中文措辞（"思考-行动-观察"）会误触发同步提示——此为守护测试预期行为（宁严勿松），无需改动，仅提示。
3. 位置：CR-001 报告 §4 记 skill 97 全绿——与 surefire 实际输出一致，无问题。

## 7. 测试质量评估（双轨核查）

### 确定性组件（TDD 轨）

| 检查项 | 结果 | 说明 |
| :--- | :--- | :--- |
| 测试真实测逻辑 | 是 | 片段测试断言真实展开内容/缺失保留/嵌套不展开；守护测试断言锚点存在性；无 mock 自嗨 |
| 正常/边界/异常覆盖 | 完整 | Task-19：正常展开/片段缺失降级/嵌套单层/无占位符零回归四态齐全；Task-21：4 组锚点；Task-22：含/不含引导双态 |
| TDD 合规（RED→GREEN） | 合规 | Task-19（2 失败→通过）、Task-21（1 失败→通过）、Task-22（2 失败→通过），RED 均为"功能未实现" |

### 概率性组件（EDD 轨）

| 检查项 | 结果 | 说明 |
| :--- | :--- | :--- |
| EDD 迭代记录完整 | 合规（简化） | Task-20/23 各 1 轮 BUILD→EVALUATE 即达标，无需 Tune |
| 评估数据集覆盖验证场景 | 完整 | 12 条公共规则关键词 × 双模板、特有内容、标签闭合、护栏条文保留全部断言覆盖 |
| 验证策略与任务类型匹配 | 匹配（附说明） | Task-20/23 为 Prompt 制品（概率性组件），采用静态语义断言评估。**判定依据**：本次变更的可验证属性（展开完整性/标签闭合/条文保留/单源收敛）本质是确定性的，非模型概率行为；与 Task-11 项目既定模式一致；真实模型行为影响留待线上联调。不属于"用单元测试代替评估"的违规 |

## 8. Prompt 制品与护栏审查（Agent 特有）

### Prompt 制品（EDD 轨）

| 检查项 | 结果 | 说明 |
| :--- | :--- | :--- |
| 评估指标达标 | 达标 | 静态语义断言全绿（12 条公共规则 × 双模板、特有内容、标签闭合、组装冒烟） |
| System Prompt 与 2.1 架构一致 | 一致 | 片段机制按技术方案 §2.1 CR-001 增补落地；`{{tools}}`/`{{include:}}` 注入点正确 |
| Token 成本在预算内 | 是 | 标签对增量约 2-4 token/段，全模板约 20 token，可忽略；无超支 |

### 护栏与安全

| 检查项 | 结果 | 说明 |
| :--- | :--- | :--- |
| 多层护栏落地完整（对照第 6 章） | 完整 | Prompt 层护栏条文**逐字保留**（git 比对实证），结构标签化不改语义 |
| 对抗性测试 100% 拦截 | 是 | AttachmentForgeBehaviorTest（2/2）、HitlZeroRegressionTest（3/3）、CacheStabilityBehaviorTest 全绿 |
| 工具执行权限门控 | 无缺口 | 本次零接触权限/HITL 门控，AC-H01 套件全绿 |

### 工具契约一致性

| 检查项 | 结果 | 说明 |
| :--- | :--- | :--- |
| 工具描述与 2.2 结构模板一致 | 一致 | ToolSchemaConverter 仅移除尾部通用引导，工具清单本体（前缀 + 工具行）零变更（测试断言） |
| 与 tool-design 制品无漂移 | 一致 | 无工具描述文本变更，消歧结构未动 |

## 9. 需求符合性（CR-001 新增 AC 覆盖映射表）

| AC 编号 | AC 摘要 | 实现位置 | 验证证据 | 满足 |
| :--- | :--- | :--- | :--- | :--- |
| AC-N04 | hitl 双模板规则单源化（展开后语义零丢失） | `hitl-shared-rules.txt` + PromptTemplateLoader.expandFragments | hitlSharedRulesShouldBeSingleSourced / hitlSharedFragmentShouldBeLoadable / hitlGuidance_共享片段展开 | ✅ |
| AC-N05 | 工具引导语单源（模板差异化承载） | ToolSchemaConverter.convertToDescriptionText + 模板 | shouldContainPrefixNotSuffix / toolUsageGuidanceShouldBeCarriedByScenarioTemplates | ✅ |
| AC-E03 | 降级默认值同步 + 守护测试防漂移 | AgentConfig.defaultSystemPrompt | AgentConfigFallbackConsistencyTest（4 组锚点） | ✅ |
| AC-S04 | 护栏段标签化等价（对抗拦截零回归） | 5 模板 guardrails 标签 | xmlTaggedTemplatesShouldBeBalanced / guardrailRulesShouldBePreservedUnderXmlTags + git 逐字比对 + 对抗套件 | ✅ |

**Scope Creep 检查**：无（变更严格限于 CR-001 范围）。

---

*审查链路：ai-agent-evolution（CR-001 规划）→ ai-agent-implementation（Task-19~25 执行）→ **代码审查（本报告）** → 后续流程*
