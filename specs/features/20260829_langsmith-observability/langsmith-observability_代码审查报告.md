# AI Agent 代码审查报告: LangSmith 可观测子系统（langsmith-observability）

| 字段 | 内容 |
|------|------|
| 审查人 | ai-agent-code-review（独立审查，非实现者自评） |
| 日期 | 2026-08-29 |
| 审查范围基准 | git status 隔离本次特性文件（17 新增 + 13 修改）；工作区其余既有未提交改动不在本次范围 |
| 关联文档 | `specs/features/20260829_langsmith-observability/langsmith-observability.md`、`_技术方案.md`、`_任务规划.md`、`docs/开发记录/20260829_langsmith-observability_全阶段_完成报告.md` |

## 0. 审查结论

**[通过]**

代码交付物整体可靠：采集接口/脱敏/OTel span 构建/条件装配/三处埋点实现正确，测试充分（36 观测 + 30 llm + 160 tools + 121 web 全绿），双路径实际启动验证通过。审查发现 **0 Critical、2 Important**，均已按用户决策修复并回归验证（见 §5 处理标记）。另有 3 项 Minor 建议（不阻断）。

## 1. 做得好的部分 (Strengths)

- **默认关 + 条件装配落地干净**（`ObservabilityAutoConfiguration.java:63-83`）：enabled 与 Key 双条件判定，Noop/Otlp 单一 Bean 出口，启动日志明确留痕，双路径实际启动验证通过（AC-S02/E03）。
- **根 span 父子归属实现正确**（`OtlpTraceCollector.java:59-92`）：直接持有 root span 引用而非 `Span.current()`（规避 scope.close 后 current 失效的坑），父子 span 共享 trace 的语义被 `OtlpTraceCollectorTest.request_spans_shareSameTraceId_andParentChild` 与 `TraceCompletenessBehaviorTest.reactLoop` 双重锁定（AC-T01）。
- **脱敏单一出口设计良好**（`OtlpTraceCollector.java:164-190`）：所有出境属性统一经 `SensitiveDataMasker`，无绕行路径；正例 100% 命中/反例 0% 误伤有行为级测试（`ObservabilityGuardrailBehaviorTest.masking_*`）。
- **真实 Token 采集**（`TracingThinkingStreamingChatModel.java:83-96`）：onComplete 取 API 真实 TokenUsage 而非 SSE 估算值，符合技术难点 3 的设计要求。
- **测试非 mock 自嗨**：OtlpTraceCollector 系列用真实 OTel SDK + `InMemorySpanExporter` 断言 span 结构/父子/脱敏，行为测试用逼真 ReAct 场景（含模型切换 + 失败注入），可验证性强。
- **既有测试适配规范**：ModelFactory/ToolExecutor/AgentController 构造器变更全链路搜索适配，新增的异步竞态 lenient() 适配有根因注释（`AgentControllerUnifiedTest.java:334-337`）。

## 2. 范围与意图比对

> [CLEAN]

| 比对项 | 结果 | 说明 |
| :--- | :--- | :--- |
| 越权改动 | 无 | 全部变更均在任务规划范围内（含 3 个 Controller 测试文件的构造器适配，属必要适配非顺手重构） |
| 遗漏任务 | 无 | Task-01~13 + Verify-01 均已落地且有测试/验证证据；Task-14 标记阻塞（需真实 Key），未虚假勾选 |

## 3. 链路一致性（实际变更 vs 技术方案文件清单）

> 比对基准：技术方案 1.6 代码结构与领域模块设计文件清单。 [一致]

| 类型 | 文件路径 | 说明 |
| :--- | :--- | :--- |
| 多出（方案未定义） | 无 | 测试文件为技术方案明确注明"测试文件：任务规划定"的配套文件，符合约定 |
| 缺失（方案未兑现） | 无 | 方案清单 9 主文件 + 2 llm 新类全部落地，无遗漏 |

## 4. 已自动修复项 (AUTO-FIXED)

无（本次审查未发现需 AUTO-FIX 的规范类问题；实现阶段的 TDD 修复已记录于完成报告 §2，非审查期修复）。

## 5. 需决策的关键问题 (Action Required)

### Critical（阻断——未解决则审查不通过）

无。

### Important（修复后通过）

1. **Task-08 验证标准缺口：OpenAI 系 listener 挂载未直接测试** ✅ 已修复
   - 位置：`agent-demo-llm/src/test/java/com/agentdemo/llm/registry/ModelFactoryTest.java`（`ObservabilityEnabledTest`）
   - 问题：任务规划 Task-08 验证标准②「ModelFactory 构建的 OpenAI 系模型带 listener（缓存清空重建后仍在）」未落地为测试。ModelFactoryTest 仅断言 thinking 系返回装饰器类型，未断言 `OpenAiChatModel`/`OpenAiStreamingChatModel` 实例的 `listeners()` 非空、`clearCacheForVendor` 后重建实例仍带 listener。
   - 为什么重要：listener 挂载是 OpenAI 系（同步 /chat、TaskPlanJudge、工作流）LLM span 采集的唯一路径（AC-N01/N03/T02）。缺直接测试，未来 builder 改动若漏掉 `.listeners(...)` 调用将静默丢失采集且无测试拦截——测试层保护缺口。
   - 修复方案：ModelFactoryTest 增补断言（已核实 `OpenAiChatModel.listeners()` 为 public）：`getChatModelByModelId` 返回实例 `listeners()` 非空；`clearCacheForVendor` 后再次获取重建实例 `listeners()` 仍非空。属测试补齐，无行为变更。
   - 处理：[x] A) 同意并修复（已落地：`ObservabilityEnabledTest` 5 用例全绿，覆盖 chat/streaming listener 挂载、缓存重建保留、thinking 装饰器、Noop 零挂载）

2. **AC-S02「零开销」承诺未完全满足：Noop 状态下埋点仍执行消息序列化** ✅ 已修复
   - 位置：`TraceCollector.java:29-33`（新增 `isEnabled()`）、`ModelFactory.java:154-192`（挂载门控）、`OtlpTraceCollector.java:57-60`（覆写 true）
   - 问题：默认关闭（NoopTraceCollector）时，ModelFactory 仍无条件挂 listener / 包装装饰器；onResponse/onComplete 在调用 `recordLlm`（noop）之前先执行 `serializeMessages(request.messages())` 构造事件——每次 LLM 调用仍承担 O(messages) 的消息拼接成本，并非 AC-S02 承诺的严格"零开销"。
   - 为什么重要：AC-S02 明确承诺「未配置 Key 时追踪子系统不启动、零开销、对话行为与关闭前完全一致」；序列化虽小，但违背了默认关闭的"零影响"语义，且未来消息结构变复杂时成本会被放大。
   - 修复方案（用户选定方案①）：`TraceCollector` 增加 `default boolean isEnabled()`（Noop 默认 false / Otlp 覆写 true），`ModelFactory` 仅在 `isEnabled()` 时挂 listener / 包装装饰器；默认关时返回裸模型（零额外开销）。
   - 处理：[x] A) 同意并修复（方案①，已落地：NoopTraceCollectorTest/OtlpTraceCollectorTest 补 isEnabled 断言；ModelFactoryTest 新增 Noop 零挂载 + 启用挂载双向断言；35 测试全绿）

## 6. 次要问题与建议 (Minor)

1. 位置：`TraceChatModelListener.java:83-104` 与 `TracingThinkingStreamingChatModel.java:111-133` —— `serializeMessages`/`messageText` 约 20 行逻辑重复。建议提取共享工具类（受技术方案 1.6 文件清单约束，需先补充文件清单再落地）。
2. 位置：`ObservabilityAutoConfiguration.java` —— OTel SDK 无显式关闭（无 `@PreDestroy` 调 `OpenTelemetrySdk.close()`），应用关闭时 BatchSpanProcessor 队列中待导出 span 可能不 flush。建议加 `@PreDestroy` 确定性落盘（对学习项目为可选增强）。
3. 位置：`ToolExecutor.java:96-99` —— DENY/工具不存在路径的事件语义：`result` 与 `errorMessage` 同值（均记拒绝/不存在文案）。建议失败路径 `result=null` 仅 `errorMessage` 承载原因，语义更清晰（可选）。

## 7. 测试质量评估（双轨核查）

### 确定性组件（TDD 轨）

| 检查项 | 结果 | 说明 |
| :--- | :--- | :--- |
| 测试真实测逻辑 | 是 | OtlpTraceCollector/TraceCompleteness 用真实 OTel SDK + InMemorySpanExporter 断言 span 结构/父子/脱敏，非 mock 自嗨 |
| 正常/边界/异常覆盖 | 完整 | 正常（成功事件/链路完整）、边界（截断/启停循环）、异常（exporter 抛错/LLM 失败/工具失败/deny）均覆盖；审查发现的 Task-08 listener 挂载缺口已修复（`ObservabilityEnabledTest` 5 用例） |
| TDD 合规（RED→GREEN） | 合规 | 完成报告 §2 记录完整 RED→GREEN 与关键修复，无跳过 RED 迹象 |

### 概率性组件（EDD 轨）

| 检查项 | 结果 | 说明 |
| :--- | :--- | :--- |
| EDD 迭代记录完整 | 不适用 | 本特性零 Prompt 制品（阶段自适应声明，非违规） |
| 评估数据集覆盖验证场景 | 不适用 | 同上 |
| 验证策略与任务类型匹配 | 匹配 | 确定性组件全走 TDD、基础设施走集成验证、行为测试用行为级断言，无错配 |

## 8. Prompt 制品与护栏审查（Agent 特有）

### Prompt 制品（EDD 轨）

| 检查项 | 结果 | 说明 |
| :--- | :--- | :--- |
| 评估指标达标 | 不适用 | 零 Prompt 制品变更，无 EDD 评估对象 |
| System Prompt 与 2.1 架构一致 | 不适用 | 技术方案 §2 明确声明零 Prompt 变更 |
| Token 成本在预算内 | 是 | 对话 Token 零增量（不改请求）；上报流量极小（技术方案 §8.4 估算） |

### 护栏与安全

| 检查项 | 结果 | 说明 |
| :--- | :--- | :--- |
| 多层护栏落地完整（对照第 6 章） | 完整 | 三重护栏全落地：默认关（条件装配）、脱敏（导出前单一出口）、静默降级（内部 try-catch + 异步不阻塞）——无缺口 |
| 对抗性测试 100% 拦截 | 是 | 脱敏正例 100% 命中/反例 0% 误伤、无 Key 零外联、exporter 故障不传导（`ObservabilityGuardrailBehaviorTest` 6 用例全绿） |
| 工具执行权限门控 | 无缺口 | ToolExecutor 既有 deny 门控零回归（既有 15 测试全绿 + 新增 deny 埋点事件测试），埋点为只读旁路不触碰门控 |

### 工具契约一致性

| 检查项 | 结果 | 说明 |
| :--- | :--- | :--- |
| 工具描述与 2.2 结构模板一致 | 不适用 | 无新增业务工具（采集点为旁路监听，非 Agent 工具） |
| 与 tool-design 制品无漂移 | 不适用 | 同上 |

## 9. 需求符合性（六类 AC 覆盖映射表）

| AC 编号 | AC 摘要 | 实现位置 | 验证证据 | 验证策略 | 满足 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| AC-N01 | 含工具对话完整上报 | OtlpTraceCollector + 三处埋点 | TraceCompleteness.reactLoop（7 span 共享 trace）+ 启动验证 | TDD+行为 | ✅ |
| AC-N02 | 会话 thread 聚合 | OtlpTraceCollector 属性 | conversation.id/thread.id 双写断言；LangSmith 侧聚合待 Task-14 联调 | TDD | ⏸ 部分 |
| AC-N03 | LLM span 必备字段 | OtlpTraceCollector + listener/装饰器 | llmSpan 字段断言 + 真实 TokenUsage 断言 | TDD | ✅ |
| AC-N04 | 工具 span 必备字段 | ToolExecutor 埋点 | toolSpan 五要素断言 + ToolExecutorTest | TDD | ✅ |
| AC-T01 | ReAct 链路完整 | 根 span 父子归属 | reactLoop span 顺序/父子/共享 trace | 行为 | ✅ |
| AC-T02 | LLM 失败不丢 | listener/装饰器 onError | failureEvents + onError 事件断言 | TDD | ✅ |
| AC-T03 | 工具失败不丢 | ToolExecutor 异常路径 | execute_failure 事件 + failureToolSpan | TDD | ✅ |
| AC-S01 | 密钥脱敏 | SensitiveDataMasker 单一出口 | 正例 100% / 反例 0% 行为测试 | TDD+行为 | ✅ |
| AC-S02 | 默认关零外联 | 条件装配 + isEnabled 门控 | AutoConfigTest + disabledConfig + 启动日志 + ModelFactoryTest 零挂载断言 | 集成验证 | ✅ |
| AC-S03 | Key 不泄漏 | 环境变量 + exporter 头 | 设计保证 + 启动验证 | 集成验证 | ✅ |
| AC-S04 | 上报失败静默降级 | 内部 try-catch + 异步 | exportFailure 不抛异常 | 行为 | ✅ |
| AC-S05 | trace 无执行面 | 只读旁路设计 | 设计保证（OTel span 属性无执行路径） | 设计 | ✅ |
| AC-E01 | 上报不阻塞主流程 | BatchSpanProcessor 异步 | exportFailure + 启动无阻塞 | 行为 | ✅ |
| AC-E02 | 超长内容截断 | maxFieldChars + 标识 | masker 单测 + span 级截断断言 | TDD+行为 | ✅ |
| AC-E03 | 启停确定性 | 条件装配无中间态 | startEndCycles + 双路径启动 | 行为 | ✅ |
| AC-M01 | 云端/本地日志互查 | TraceContextHolder + Controller 捕获 | holder 测试 + 异步注入测试 + log.trace_id | TDD+行为 | ✅ |
| AC-M02 | 模型切换如实 | 各 span 模型名 | reactLoop modelA/B 断言 | 行为 | ✅ |
| AC-H01 | 评估可追溯 | - | 待 Task-14 真实联调（需 Key） | 集成验证 | ⏸ 待联调 |
| AC-H02 | 泄漏处置流程 | - | 待 Task-14 演练（需 Key） | 集成验证 | ⏸ 待联调 |

**Scope Creep 检查**：无（未实现 AC 之外的功能；`isEnabled` 修复若采纳属既有 AC 的保障，非新功能）。

---

**附注**：agent-demo-app 3 个工作流测试失败（WorkflowIntegrationTest ×1、WorkflowP3IntegrationTest ×2）经 git stash 隔离验证为**工作区基线既有问题**（先前 agent-context-engineering/HITLReActStream 未提交改动所致），非本次审查范围，不计入本报告问题；建议单独排查。

*审查链路：需求澄清 → 技术设计（1.6 文件清单）→ 任务规划（涉及文件同源）→ 实现（TDD+EDD）→ **代码审查（本报告）** → 后续流程*

---

# CR-001 代码审查：观察空间扩展（五域事件采集）

| 字段 | 内容 |
|------|------|
| 审查人 | ai-agent-code-review（独立审查，非实现者自评） |
| 日期 | 2026-08-31 |
| 审查范围基准 | 完成报告文件变更清单 + `langsmith-observability_变更任务_CR001.md`（Task-15~25 涉及文件）；工作区其余历史改动不在本次范围 |
| 关联文档 | `langsmith-observability.md`（v1.1，新增 8 AC）、`langsmith-observability_技术方案.md`（CR-001 章节）、`langsmith-observability_变更任务_CR001.md`、`docs/dev-records/20260831_langsmith-observability_CR001_report.md` |

## 0. 审查结论

**[修复后通过]**

CR-001 交付物整体可靠：TraceCollector 六类事件接口 + OtlpTraceCollector 六类 span 构建（脱敏单一出口无绕行）、五域埋点挂钩（RAG/记忆压缩/工作流/MCP/Skill）静默降级全覆盖、工作流 executionId 聚合与并行线程上下文传播正确。审查发现 **0 Critical、1 Important、3 Minor**；Important 已按用户决策修复并回归验证（§5），2 项 Minor 已 AUTO-FIX（§4）。

## 1. 做得好的部分 (Strengths)

- **脱敏单一出口完整**（`OtlpTraceCollector.java`）：六类新 span 全部文本属性经 `masker.maskSafe`（`buildRagAttributes:300-315` / `buildMcpAttributes:372-383` / `buildMemoryCompressionAttributes:320-332` / `buildWorkflowAttributes:337-350` / `buildWorkflowStepAttributes:355-367` / `buildSkillActivationAttributes:388-405`），数值/布尔属性不入 masker，无绕行路径（AC-S06）。
- **静默降级全覆盖（AC-E05）**：五域埋点 `recordRag`（KnowledgeRetrieverTool）/ `recordMcp`（McpToolExecutor）/ `recordStepSpan`+`recordFailedStepSpan`（AbstractExecutionStrategy）/ `recordWorkflowTerminal`（WorkflowExecutionService）/ `fireListener`（CompressingChatMemory）/ `recordActivation`（SkillSessionManager）均内部 try-catch + WARN，异常不传导主流程。
- **工作流终态 9 处记录全覆盖**（`WorkflowExecutionService.java:136/241/255/269/306/363/413/497/556`）：execute/resume/hitlReply 成功 + handleTerminated/Timeout/Failure/Paused/HITLPaused + checkpoint 拒绝，无终态遗漏（AC-N07）。
- **并行线程上下文传播正确**（`ParallelExecutionStrategy.java`）：捕获主线程 TraceContext 包装进 workflow-parallel-N 任务 + finally 清理，工作线程步骤 span 聚合到 executionId（AC-M03）。
- **记忆压缩成功/降级双路径触发监听**（`CompressingChatMemory.java:275/281`）：摘要成功与 FIFO 降级均上报 stats，降级标记正确（AC-N06）。
- **测试免 sleep 竞态**：`WorkflowExecutionTraceTest` 用 CountDownLatch + 5s 超时，非 Thread.sleep，稳定性好。
- **修复后的失败步骤 span 谨慎**（`AbstractExecutionStrategy.recordFailedStepSpan`）：仅采集留痕，不调 `step.fail()`，避免步骤状态被标记 FAILED 导致前端执行历史回归。

## 2. 范围与意图比对

> [CLEAN]

| 比对项 | 结果 | 说明 |
| :--- | :--- | :--- |
| 越权改动 | 无 | CR-001 变更严格限定在 observability + 五域挂钩 + 五模块 pom + 对应测试；web 测试辅助类（AgentControllerUnifiedTest CapturingTraceCollector 补 6 方法）为接口扩展的合法兼容工作，非范围蔓延 |
| 遗漏任务 | 无 | Task-15~25 涉及文件全部落地；审查中发现 Task-20 验证标准缺口（失败步骤 span，见 §5），已修复 |

## 3. 链路一致性（实际变更 vs 技术方案文件清单）

> [一致]

| 类型 | 文件路径 | 说明 |
| :--- | :--- | :--- |
| 多出（方案未定义） | 5 个策略类（Sequential/Conditional/Loop/Parallel/Supervisor）构造器 | 实现 AbstractExecutionStrategy 注入 TraceCollector 的必然结果（方案文件清单按策略层级粒度），合理，非链路断点 |
| 缺失（方案未兑现） | 无 | 方案 CR-001 清单（TraceCollector/Noop/Otlp + 五域挂钩 + 五 pom）全部落地 |

## 4. 已自动修复项 (AUTO-FIXED)

- [AUTO-FIXED] `SkillSessionManager.recordActivation` 重复 `skillStore.get(skillId)` 两次（skillName/boundTools 各查一次）→ 合并为单次 `var skill = skillStore.get(skillId)` 复用 (`SkillSessionManager.java:398-412`，行为无变更)
- [AUTO-FIXED] `OtlpTraceCollector.recordSkillActivation` 无耗时语义却用 `endInstant.minusMillis(0)` → 直接 `setStartTimestamp(endInstant)` + 注释说明瞬时内存操作语义 (`OtlpTraceCollector.java:232-241`，行为无变更)

## 5. 需决策的关键问题 (Action Required)

### Critical（阻断——未解决则审查不通过）

无。

### Important（修复后通过）

1. **Task-20 验证标准"步骤失败/重试路径不丢事件"未兑现：失败步骤无步骤级 span**
   - 位置：`agent-demo-app/.../strategy/AbstractExecutionStrategy.java` executeOrSkip（原仅成功路径调用 recordStepSpan）
   - 问题：`executeWithRetry` 抛异常时（步骤失败/重试耗尽）该步骤无步骤级 span——仅工作流级 FAILED/PAUSED span 与 LLM 错误 span 存在，步骤粒度失败点不可定位；CR-001 任务文档 Task-20 验证标准明确要求"步骤失败/重试路径不丢事件"。
   - 为什么重要：链路一致性（验证标准未兑现）+ 可观测性完整性（LangSmith 无法按步骤粒度定位失败）。
   - 修复方案：executeOrSkip 与 resumePausedStep 用 try/catch 包裹执行，`WorkflowHITLException`（等待用户输入，非失败）直接上抛不记 FAILED；其余异常记录 `recordFailedStepSpan`（status=FAILED，耗时取 startTimeStamp→now，不调 `step.fail()` 避免前端执行历史回归），再原样上抛（AC-E05 不吞异常）。新增测试 `WorkflowStepTraceTest.failedStep_reportsFailedStepSpan_andStillThrows` 断言 FAILED span + 步骤状态仍为 RUNNING（前端零回归）。
   - 处理：[x] A) 同意并修复（用户确认 2026-08-31）→ 已实现并验证：app 45 用例含新测试全绿，全模块 282 用例仅剩 3 个基线失败，无新回归

## 6. 次要问题与建议 (Minor)

1. `KnowledgeRetrieverTool.java:199-201` —— catch 块内 `ragProperties.getRetrieval().getMaxResults()` 作为 recordRag 参数求值，若配置访问异常会覆盖"知识库服务暂时不可用"降级路径；风险极低（Spring 注入 Retrieval 非空），建议保持现状或后续防御性处理。
2. `AbstractExecutionStrategy.recordStepSpan/recordFailedStepSpan` —— `retry_count` 恒 0（`StepExecution.retryCount` 从未被设置，AgentExecutor 内部重试计数未回写步骤对象），为已文档化的已知简化；如需真实重试数，需 AgentExecutor 回传 attempt 次数（可后续 CR 增强）。
3. `docs/dev-records/20260831_langsmith-observability_CR001_report.md` —— 文件变更清单"修改 19"与实际 21 处小误差（漏计 web 测试兼容 + 部分策略类），不影响审查结论。

## 7. 测试质量评估（双轨核查）

### 确定性组件（TDD 轨）

| 检查项 | 结果 | 说明 |
| :--- | :--- | :--- |
| 测试真实测逻辑 | 是 | 新测试均用 ArgumentCaptor 捕获事件断言字段 + 状态断言 + 上下文断言，无 mock 自嗨/空洞断言（如 `WorkflowStepTraceTest` 断言 executionId/agentName/status/duration；`McpToolExecutorTraceTest` 断言断线标记；`ChatMemoryManagerTraceTest` 经 mock 采集器捕获 holder sessionId） |
| 正常/边界/异常覆盖 | 完整 | 各域均覆盖成功 + 失败/降级 + collector 异常（AC-E05）：RAG（成功/异常/hint 路径/collector 抛错）、记忆（成功/降级/无监听器）、工作流（上下文传播/并行传播/步骤成功/步骤失败）、MCP（成功/失败/断线/collector 抛错）、Skill（成功/被拒/手动批量/未知技能） |
| TDD 合规（RED→GREEN） | 合规 | 完成报告 §2 记录各任务 RED（编译失败/功能缺失）→ GREEN → REFACTOR；失败步骤 span 修复同样 RED（Wanted but not invoked）→ GREEN |

### 概率性组件（EDD 轨）

> 不适用：CR-001 零 Prompt 制品变更（纯代码确定性扩展），无 EDD 迭代、无评估数据集调优。

## 8. 需求符合性核查（六类 AC）

| AC | 状态 | 验证证据 |
|----|------|---------|
| AC-N05 RAG 检索上报 | ✅ | KnowledgeRetrieverToolTraceTest + 检索 span 属性断言 |
| AC-N06 记忆压缩上报 | ✅ | CompressingChatMemoryListenerTest + ChatMemoryManagerTraceTest（含降级） |
| AC-N07 工作流编排上报 | ✅ | WorkflowStepTraceTest（成功 + 失败） + WorkflowExecutionTraceTest + 9 终态记录 |
| AC-N08 MCP 协议层上报 | ✅ | McpToolExecutorTraceTest（成功/失败/断线） |
| AC-N09 Skill 激活上报 | ✅ | SkillSessionManagerTraceTest（成功/被拒/手动批量/未知） |
| AC-S06 新采集域脱敏前置 | ✅ | ObservabilityGuardrailBehaviorTest 新内容类型正反例 + 陷阱任务 |
| AC-E05 新埋点零回归 | ✅ | 各域 collector 异常测试 + 上下文清理断言 |
| AC-M03 工作流会话关联 | ✅ | 并行传播测试 + workflow.execution_id 断言 |
| AC-S01~S05（既有全量重验） | ✅ | ObservabilityGuardrailBehaviorTest 10 用例全过（脱敏正反例/零外联/静默降级/截断/启停） |
| AC-H01~H02 | ⏸ 待联调 | 依赖 Task-14 真实 Key（并行推进），自动化部分无回归 |

**Scope Creep 检查**：无（未实现 AC 之外的功能）。

## 9. 附注

- app 模块 3 个工作流集成测试失败（toolRegistry null）为**工作区基线既有问题**（集成测试用轻量构造器未装配 HITL 依赖），非 CR-001 引入，不计入本报告。
- 回归证据：observability 67 / skill 97 / app 282（仅 3 基线）/ rag / memory / mcp 全绿；Pass^3（observability 67 × 3 次）通过；实际启动验证 Noop 零外联。
- 评估可信度：行为测试为确定性 JUnit（无 LLM），单次运行即有效；Pass^3 用于排除偶发稳定性。

*审查链路：CR-001 变更任务 → 完成报告 → 代码审查（本报告）→ 后续 ai-agent-code-review 专项（如需要）*
