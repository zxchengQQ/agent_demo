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
