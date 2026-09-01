# BUG 修复报告

## 基本信息
- 日期：2026-09-01
- 报告人：AI Assistant（bugfix-workflow）
- 模块/功能：agent-demo-observability（可观测性）+ agent-demo-web / agent-demo-app（HITL 链路）
- 严重级别（低/中/高）：中
- 影响范围：所有启用 LangSmith 追踪且使用 HITL（单 Agent askUser / tool_confirm、工作流 WAITING_USER）的多轮人机交互场景
  - 严重级别依据：不阻断业务功能（对话正常、可追溯性受损），无安全风险、无数据丢失；影响开发者排查体验与 LangSmith 链路可读性（跨请求可绕过——按 thread 聚合仍可看全），故定"中"。

## 问题描述
- 期望结果：单任务（含多次人机交互轮次）在 LangSmith 上应呈现为**一条完整 trace 链路**（从首次请求到最终完成，含各人机交互轮次的 LLM/工具 span）。
- 实际结果：每一轮人机交互（askUser / tool_confirm / 工作流 WAITING_USER 的暂停与恢复）都在 LangSmith 上产生**一条独立的 request 记录（独立 trace）**；最终数据展示为"一条完整链路 + 多条独立的人机交互短链路"——任务被碎片化为多条 trace。

## 复现步骤（必须可复现）
1) 启用 LangSmith 追踪（配置 `langsmith.enabled=true` + `LANGSMITH_API_KEY`）。
2) 开启 HITL 并发送一条会让 Agent 调用 `askUser` 追问的任务消息（如"帮我查资料，信息不足时先问我"）。
3) Agent 暂停并弹出提问 → 用户在输入框回复；若 Agent 再次追问，继续回复（共 ≥2 轮人机交互）。
4) 打开 LangSmith 控制台对应 session 的 thread：观察到每轮人机交互各有一条 request 记录（多条独立短链路），而非整任务一条完整链路。

## 复现环境
- 设备/系统：WSL2 / Linux，Java 17，Spring Boot 3.2.5，LangChain4j 1.17.2，OTel 1.42.1
- 应用版本/配置：agent-demo（20260831 langsmith-observability CR-001/CR-002 基线）；`langsmith.enabled=true`
- 相关依赖或外部条件：LangSmith 云（OTLP HTTP）；HITL 暂停-恢复跨 HTTP 请求

## 定位过程
- 关键线索：LangSmith 的"trace"标识 = OTel 根 span 的 traceId；同一 traceId 的 span 聚合为一条链路。
- 排查路径：
  1. `AgentController.runTracedAsync`（web）：**每个** `/chat/stream` HTTP 请求（含 HITL 恢复轮，`hasPending` 分支）都调用 `traceCollector.startRequest()` → `OtlpTraceCollector.startRequest()` 用 `tracer.spanBuilder(...).startSpan()` 创建**随机新 traceId 的全新根 span**（OtlpTraceCollector.java:66-75）。
  2. `WorkflowExecutionService.runAsyncWithTrace`（app）：execute / resume / hitlReply 三入口同样每请求 `startRequest()` 新建根 span（WorkflowExecutionService.java:681）。
  3. HITL 暂停时根 span 随本次请求结束（endRequest）导出关闭；恢复是新 HTTP 请求 → 新随机 traceId → LangSmith 新 trace。技术方案 §11 风险 4 曾将此列为"已知边界"（本期接受），用户现报告为需要修复的体验问题。
- 根因说明：**HITL 暂停/恢复的跨请求 trace 断裂**——恢复轮未以暂停轮根 span 为父续接，而是独立新建随机 traceId 的根 span，导致多轮人机交互碎片化为多条独立 trace。

## 修复方案
- 修改点说明：实现 **HITL 暂停→恢复的 trace 续接**——暂停点（askUser / tool_confirm / 工作流 WAITING_USER）调用 `TraceCollector.markHITLPause(resumeKey)` 保存当前根 span 的 SpanContext；恢复轮（hasPending 分支 / 工作流 hitlReply）调用 `TraceCollector.resumeRequest(resumeKey)` 以该 SpanContext 为**远程父上下文**（`Context.root().with(Span.wrap(savedCtx))`）创建新根 span，使恢复轮 span 与原任务共享同一 traceId，LangSmith 呈现一条完整链路。无暂停记录时（新请求/服务重启后）回退独立新 trace，不误续接。
- 影响评估：
  - 行为变更面：仅启用 LangSmith 追踪且发生 HITL 暂停-恢复时，trace 从"多条碎片"变为"一条完整链路"（各轮次以根 span 树形嵌套）；未启用追踪（Noop）零影响；非 HITL 独立请求行为不变（独立 trace）。
  - 恢复轮 `log.trace_id`（本地日志互查键）仍为恢复请求自身的 MDC traceId（每 HTTP 请求独立），保持本地日志逐请求可查；仅 OTel trace（LangSmith 链路标识）续接。
- 风险点：暂停后用户永不回复（超时清理）时，续接表残留条目不无限增长（有界 LRU 256）；恢复轮以已结束 span 为父属 OTel 标准异步续接模式（LangSmith 按 traceId 增量组装）。工作流"失败恢复 PAUSED→resume"仍为独立 trace（本次仅覆盖人机交互，见遗留）。
- 回滚方案：`TraceCollector` 接口默认方法（markHITLPause 空操作 / resumeRequest 回退 startRequest）保证 Noop/RecordingTraceCollector 零改动；恢复代码仅需移除 Controller/Workflow 中 resumeRequest/markHITLPause 调用即可回退，接口方法保留不破坏调用方。

## 变更内容
- 代码/配置改动摘要：
  - `TraceCollector`（observability）：新增 default 方法 `markHITLPause(String resumeKey)`（默认空操作）与 `resumeRequest(String resumeKey)`（默认回退 `startRequest()`）。
  - `OtlpTraceCollector`：实现两者——`markHITLPause` 保存当前线程根 span 的 SpanContext（有界 LRU map 256，键=sessionId/executionId）；`resumeRequest` 查表并以远程父上下文续接（消费即删，链式暂停由新一轮 mark 覆盖）。
  - `AgentController`（web）：`onAskUser`/`onToolConfirm` 回调触发时 `markHITLPause(sessionId)`；`runTracedAsync` 增加 `hitlResume` 参数，HITL 恢复分支（hasPending）调 `resumeRequest(sessionId)`。
  - `WorkflowExecutionService`（app）：`recordWorkflowTerminal` 状态为 WAITING_USER 时 `markHITLPause(executionId)`；`runAsyncWithTrace` 改调 `resumeRequest(executionId)`（新执行无记录回退独立 trace）。
- 相关文件：
  - `agent-demo-observability/.../TraceCollector.java`、`OtlpTraceCollector.java`
  - `agent-demo-web/.../controller/AgentController.java`
  - `agent-demo-app/.../service/WorkflowExecutionService.java`
  - 测试：`OtlpTraceCollectorTest`（+4）、`AgentControllerUnifiedTest`（+2）、`WorkflowExecutionTraceTest`（+1）、`LlmConfigSeedTest`（flaky 断言修复）
- 回归风险/可能受影响模块：observability（接口/实现）、web（Controller 恢复分支）、app（工作流执行）、evaluation（RecordingTraceCollector 实现默认方法不受影响）、tools/mcp/rag/memory/skill（埋点方仅调用 recordXxx，不受影响）

## 单元测试
- 新增/更新测试：
  1. `OtlpTraceCollectorTest.reproduction_hitlRounds_currentBehavior_fragmentsIntoSeparateTraces`——**复现**当前碎片化（两轮独立请求不同 traceId），修复前通过（文档化 BUG）。
  2. `OtlpTraceCollectorTest.hitlPauseResume_roundsShareSameTrace`——**修复验证**：暂停→恢复两轮共享同一 traceId 且恢复根为暂停根子节点（修复前 RED）。
  3. `OtlpTraceCollectorTest.hitlMultiRound_chainContinuesThroughEachPause`——三轮链式续接，traceId 全程一致。
  4. `OtlpTraceCollectorTest.resumeRequest_withoutPause_fallsBackToFreshTrace`——无暂停记录回退独立新 trace。
  5. `AgentControllerUnifiedTest.hasPending_恢复轮续接原trace`——Controller 恢复分支调 `resumeRequest(sessionId)`。
  6. `AgentControllerUnifiedTest.askUser暂停回调_标记trace续接点`——onAskUser 回调触发 `markHITLPause(sessionId)`。
  7. `WorkflowExecutionTraceTest.handleHITLPaused_marksTraceResumePoint_forHitlReplyContinuation`——工作流 WAITING_USER 暂停标记续接点。
  8. `LlmConfigSeedTest.judgeSeparateVendor_multiSourceJudge_createsTwoVendors`——消除对 `getFirstChatModel()` 迭代序的依赖（flaky 修复，与本 BUG 同批回归发现）。
- 若未补测试，原因说明：不适用（已补）。

## 验证步骤（手动，逐步）
1) 启动应用：`mvn -pl agent-demo-bootstrap spring-boot:run`（配置 `langsmith.enabled=true` + `LANGSMITH_API_KEY` 环境变量；HITL 开启）。
2) 打开前端对话页（或直接 curl `/api/agent/chat/stream`），发送一条会触发 `askUser` 追问的任务消息（如"帮我规划一次包含多次确认的行程，信息不足先问我"）。
3) 在提问卡片/输入框回复第一次；若 Agent 再次追问，继续回复（确保 ≥2 轮人机交互），直至任务完成。
4) 打开 LangSmith 控制台，定位该 session 对应的 thread。
5) 观察：**整个任务（含所有轮次）应聚合为一条完整 trace 链路**（根 span "agent.request" 下嵌套各轮次的 LLM/工具 span），不再出现"多条独立人机交互短链路"。
6) （工作流路径）编排一个含 `@HumanCheckpoint` 检查点的工作流并执行，在检查点确认后继续；在 LangSmith 确认该 executionId 对应**单条完整 trace**（含每次检查点后的步骤 span），而非每条 hitl-reply 独立短链路。
7) （回归）关闭 LangSmith（不配 Key）重复步骤 2-3，确认对话功能与之前完全一致（零外联、无影响）。

## 验证结果
- 结果说明：自动化 4 个 HITL 续接核心测试 + 3 个接线测试全绿；回归 observability 71 / web 123 / tools 169 / evaluation 60 全绿；app 284 仅 3 个既有基线失败（WorkflowIntegration/WorkflowP3，与本修复无关）。手动验证需真实 LangSmith 环境执行（见上）。
- 是否通过：是（自动化）；手动步骤待用户在真实 LangSmith 环境执行确认。

## 遗留问题/后续动作
- 工作流"失败恢复"路径（PAUSED → /resume，非人机交互）恢复轮仍为独立 trace（本次最小改动仅覆盖人机交互场景，与用户报告范围一致）；如需整次工作流执行跨失败恢复也一条链路，可后续在 PAUSED 状态同样 markHITLPause 扩展。
- 已同步更新文档：可观测性模块说明书 §3.2（AiServices 工具采集）与相关说明；技术方案 §11 风险 4 的"已知边界"表述应更新为"已修复（2026-09-01）"。
