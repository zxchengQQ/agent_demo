# 阶段完成报告: agent-context-engineering CR-001（Out of Scope 质量债清偿）

| 字段 | 内容 |
|------|------|
| Agent | agent-context-engineering（20260828 迭代） |
| 变更 | CR-001：hitl 规则单源化 / 降级默认值同步 / XML 标签体系 / 引导语去重 |
| 执行日期 | 2026-08-31 |
| 开发方法 | TDD + EDD 双驱动（确定性组件 TDD / 概率性制品 EDD） |
| 关联文档 | `specs/features/20260828_agent-context-engineering/`（需求/技术方案/变更任务 CR-001） |

## 1. 已完成任务清单

| 任务 | 类型 | 验证策略 | 状态 | 结果 |
|------|------|---------|------|------|
| Task-19 | 确定性组件 | TDD | ✅ | PromptTemplateLoader 片段加载机制（`{{include:xxx}}` 单层展开、片段缺失降级、防循环） |
| Task-20 | 概率性制品 | EDD | ✅ | hitl 规则单源化（hitl-shared-rules 共享片段 + 双模板重构），第 1 轮达标 |
| Task-21 | 确定性组件 | TDD | ✅ | AgentConfig.defaultSystemPrompt 补"读取文件"语义 + 守护测试（4 锚点组防漂移） |
| Task-22 | 确定性组件 | TDD | ✅ | ToolSchemaConverter 尾部通用引导移除 + 模板引导承载确认 |
| Task-23 | 概率性制品 | EDD | ✅ | 5 agent 场景模板 + hitl-guidance XML 语义标签化，第 1 轮达标 |
| Task-24 | 行为测试 | 行为测试 | ✅ | 对抗回归 + 缓存稳定重验（AC-S 组 + AC-N01 + AC-H01） |
| Task-25 | 候选验证 | 行为测试 | ✅ | Pass^3 = 100% + 全模块回归 |

## 2. TDD 循环记录（确定性组件）

| 任务 | RED（失败测试） | GREEN（实现） | REFACTOR |
|------|----------------|--------------|----------|
| Task-19 | `includePlaceholderShouldBeExpanded` / `nestedIncludeShouldNotBeRecursivelyExpanded` 2 个因片段机制未实现失败 | `expandFragments` 单层展开 + `loadRawTemplate` 纯读分离 + INCLUDE_PATTERN 常量 | 无（实现已最小化：Pattern 常量、quoteReplacement、缺失保留占位符） |
| Task-21 | `AgentConfigFallbackConsistencyTest` 1 个失败（defaultSystemPrompt 缺"读取文件"锚点） | defaultSystemPrompt 补"读取文件" | 无 |
| Task-22 | `shouldContainPrefixNotSuffix` / `shouldHandleEmptyToolListWithPrefixOnly` 2 个失败（尾部引导未移除） | convertToDescriptionText 移除尾部 2 行通用引导 | 无（方法更简洁） |

## 3. EDD 迭代记录（概率性制品）

> 本环境无真实 LLM（无方舟 API Key），采用**静态语义断言评估**（与 Task-11 一致）：语义等价通过关键词集合 + 结构完整性断言验证。Pass^k 稳定性指标用静态套件连续运行代替。

| 任务 | 评估方式 | 目标 | 本轮得分 | 达标 |
|------|---------|------|---------|------|
| Task-20 | 展开后 12 条公共规则关键词 × hitl 模板 + 特有内容保留 + 共享片段可加载 | 语义零丢失 | 19/19 断言通过 | ✅ |
| Task-20 | hitl-guidance 展开（app 模块）12 条公共规则 + 内联副本消除 | 语义零丢失 | 3/3 断言通过 | ✅ |
| Task-23 | 标签配对闭合 × 5 模板 + 护栏条文逐字保留 + 组装冒烟 | 结构完整 + 语义等价 | 全部通过 | ✅ |

## 4. 回归验证结果

| 模块 | 测试数 | 结果 | 说明 |
|------|--------|------|------|
| agent-demo-memory | 27 | ✅ 全绿 | 基线 21 + 新增 |
| agent-demo-agent | 200 | ✅ 全绿 | 基线 186 + 新增 14（片段 5 + 守护 4 + 单源 2 + 承载 1 + 标签 3） |
| agent-demo-skill | 97 | ✅ 全绿 | 基线 93（2 skipped）+ 新增 |
| agent-demo-web + app | 283 | ✅ 280 绿 + 3 个**既有基线失败** | WorkflowP3IntegrationTest ×2 + WorkflowIntegrationTest ×1（toolRegistry=null，git stash 基线验证与本次无关） |

**关键回归点**：
- AC-N01 缓存稳定：`CacheStabilityBehaviorTest` 通过（标签化+片段展开后系统提示词指纹字节级一致）
- AC-S 对抗：`AttachmentForgeBehaviorTest`（2/2）、`HitlZeroRegressionTest`（3/3）全绿
- AC-H01 HITL 零回归：AgentExecutorHITL/Checkpoint 套件全绿
- **Pass^3 = 100%**：静态断言套件（28 测试）连续 3 次运行全绿

## 5. 文件变更清单

**代码（3 文件修改）：**
- `agent-demo-agent/.../prompt/PromptTemplateLoader.java`（+片段加载机制）
- `agent-demo-agent/.../config/AgentConfig.java`（defaultSystemPrompt 同步）
- `agent-demo-tools/.../registry/ToolSchemaConverter.java`（尾部引导移除）

**Prompt 制品（1 新增 + 6 修改）：**
- `agent-demo-agent/src/main/resources/prompts/fragments/hitl-shared-rules.txt`（新增，12 条公共规则单源）
- `agent-demo-agent/.../scenarios/hitl.txt`、`chat.txt`、`task-plan.txt`、`task-execute.txt`、`task-summary.txt`（XML 标签化 + 片段引用）
- `agent-demo-app/.../scenarios/hitl-guidance.txt`（XML 标签化 + 片段引用）

**测试（3 新增 + 2 修改 + test 资源）：**
- 新增：`PromptTemplateLoaderTest`（+10 测试）、`AgentConfigFallbackConsistencyTest`（+4）、`HitlZeroRegressionTest`（+1）
- 修改：`ToolSchemaConverterTest`（2 断言适配新契约）
- test 资源：`prompts/scenarios/frag-demo.txt` 等 4 个 + `prompts/fragments/test-shared.txt` 等

## 6. 验收标准检查（4 条新增 AC）

| AC | 描述 | 状态 | 验证证据 |
|----|------|------|---------|
| AC-N04 | hitl 双模板规则单源化（展开后语义零丢失） | ✅ | `hitlSharedRulesShouldBeSingleSourced` / `hitlSharedFragmentShouldBeLoadable` / `hitlGuidance_共享片段展开` |
| AC-N05 | 工具引导语单源（模板差异化承载） | ✅ | `shouldContainPrefixNotSuffix` / `toolUsageGuidanceShouldBeCarriedByScenarioTemplates` |
| AC-E03 | 降级默认值同步 + 守护测试防漂移 | ✅ | `AgentConfigFallbackConsistencyTest`（4 组锚点双向守护） |
| AC-S04 | 护栏段标签化等价（对抗拦截零回归） | ✅ | `xmlTaggedTemplatesShouldBeBalanced` / `guardrailRulesShouldBePreservedUnderXmlTags` + 对抗套件全绿 |

## 7. Token 成本

- 全部测试使用 **mock LLM / 静态断言**（零真实 Token 消耗），与既有迭代一致
- EDD 评估为静态语义断言，无 LLM 调用成本

## 8. 遇到的问题与解决方案

| 问题 | 解决方案 |
|------|---------|
| hitl-guidance.txt 属 app 模块资源，agent 模块单测 classpath 不可加载 | guidance 展开断言移至 app 模块 HitlZeroRegressionTest |
| Task-21 守护测试初版锚点选择 | 采用"模板含锚点 + 默认值含锚点"双向守护（模板演进后默认值未同步即失败） |
| 3 个既有 workflow 集成失败 | 确认与本次变更无关（未触碰 workflow 代码），git stash 基线验证为既有问题 |

## 9. 下一步建议

1. 执行 `ai-agent-code-review` 对 CR-001 交付物独立双轨审查（模板制品走 EDD 轨：单源语义/标签闭合/护栏等价；代码走 TDD 轨）
2. 真实模型联调时验证标签化对模型段落定位/工具遵循的实际影响（需方舟 API Key）
3. 待真实评估基线建立后，评估 #4（`{{tools}}` 人读文本完全移除）的可行性
