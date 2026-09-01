# Agent 变更记录: langsmith-observability - CR-001

## 0. 变更概览 (Change Overview)
*   **变更标题**: 观察空间扩展--五域事件采集（RAG 检索/记忆压缩/工作流编排/MCP 协议层/Skill 激活）
*   **变更类型**: 能力扩展 (Capability Extension)（含安全边界变更要素：能力禁区条款修改 + 数据出境面扩大，**按从严标准执行全量对抗回归**）
*   **变更原因**: 需求文档 §8.2 遗留演进项第 1 项（原第 1 条明确标注"经 ai-agent-evolution 增量扩展"）；核心链路（LLM+工具）已实现并审查通过后，将观察空间扩展至剩余五域，补齐工作流路径完全无 trace 的盲区
*   **更新载体判定**: **程序（代码/工具）**-- 按「知识库 -> 指令 -> 程序 -> 参数」最小充分顺序：本变更为确定性采集逻辑扩展（五域埋点 + span 构建），行为可由单元测试完全锁定，无需知识库承载（无新事实经验）、无需指令承载（零 Prompt 制品变更，无语言化判断规则）、更不涉及模型参数
*   **发起日期**: 2026-08-31
*   **开发方法**: TDD + EDD 双驱动 - 确定性组件按 TDD（Red-Green-Refactor）执行；本变更零概率性组件（无 Prompt 制品），EDD 不适用
*   **关联 Agent**: langsmith-observability（LangSmith 可观测子系统）
*   **关联文档**:
    -   需求文档: `specs/features/20260829_langsmith-observability/langsmith-observability.md`（v1.1）
    -   技术方案: `specs/features/20260829_langsmith-observability/langsmith-observability_技术方案.md`
    -   任务规划: `specs/features/20260829_langsmith-observability/langsmith-observability_任务规划.md`（原 Task-01~14；Task-14 真实接入联调与本次变更并行推进）

## 1. 影响分析 (Impact Analysis)

### 1.1 需求影响
| 影响项 | 变更类型 | 详情 |
| :--- | :--- | :--- |
| AC-N05~N09 | 新增 | RAG 检索上报 / 记忆压缩上报 / 工作流编排上报 / MCP 协议层上报 / Skill 激活上报（详见 §2.1） |
| AC-S06 | 新增 | 新采集域脱敏前置（单一出口扩展至六类新 span） |
| AC-E05 | 新增 | 新埋点零回归（异常吞掉 + WARN + finally 清理） |
| AC-M03 | 新增 | 工作流 trace 会话关联（executionId 聚合键） |
| 自主性级别 | 不变 | L4 风险归类维持（自动上报 + 不可回滚特征不变）；三重护栏不削弱，脱敏护栏适用面扩大（AC-S06） |
| 能力禁区 | 修改 | 移除「不采集五域」条款（原 §3.2 第 1 条 / 原 §8.2 第 1 条）；保留通用反射工具与 embedding 既有边界 |

### 1.2 技术影响
| 影响层 | 影响范围 | 详情 |
| :--- | :--- | :--- |
| Prompt 工程架构 | 无影响 | 零 Prompt 制品变更（纯基础设施扩展） |
| 工具集成 | 采集点扩展 | TraceCollector 新增 6 方法 + 6 事件 record；五域挂钩（searchByKbId / 压缩回调 / WorkflowExecutionService 三入口 + executeOrSkip / McpToolExecutor.execute / SkillSessionManager 两处）；五模块 pom 补 observability 直接依赖 |
| 记忆与上下文 | 上下文传播扩展 | 工作流路径补齐根 span 与 TraceContextHolder 传播（三入口 + 并行线程池 Runnable 包装）；记忆压缩回调闭包捕获 sessionId |
| 护栏与安全 | 输出过滤层扩展 | 脱敏单一出口（OtlpTraceCollector 构建时）自然覆盖六类新 span 属性；AC-S06 验证无绕行路径 |
| 评估框架 | 数据集扩充 | 五域正例 + 失败注入 + 新内容类型脱敏对抗用例 + 陷阱任务（详见 §1.4） |

### 1.3 Prompt 制品影响
> 无 Prompt 制品变更。本表不适用（无任何制品需要落地路由到 `agent-prompt-designer` / `tool-design`）。

### 1.4 代码与评估影响
**代码影响：**
| 文件路径 | 操作 | 影响说明 |
| :--- | :--- | :--- |
| `agent-demo-observability/.../TraceCollector.java` | 修改 | 新增 6 方法（recordRag/recordMemoryCompression/recordWorkflow/recordWorkflowStep/recordMcp/recordSkillActivation）+ 6 事件嵌套 record |
| `agent-demo-observability/.../NoopTraceCollector.java` | 修改 | 6 方法空实现（未启用时零开销，AC-S02 语义保持） |
| `agent-demo-observability/.../OtlpTraceCollector.java` | 修改 | 6 类新 span 构建（属性见技术方案 §7.1 span 设计表，全部经 masker 脱敏截断） |
| `agent-demo-rag/.../retriever/KnowledgeRetrieverTool.java` | 修改 | RAG 埋点：searchByKbId 计时 + recordRag（前后计时，异常路径先记再抛） |
| `agent-demo-rag/pom.xml` | 修改 | 补 observability 直接依赖 |
| `agent-demo-memory/.../shortterm/ChatMemoryManager.java`、`CompressingChatMemory.java` | 修改 | 压缩回调注入（创建 memory 时闭包捕获 sessionId），压缩完成/降级点记录 |
| `agent-demo-memory/pom.xml` | 修改 | 补 observability 直接依赖 |
| `agent-demo-app/.../service/WorkflowExecutionService.java` | 修改 | 三入口（execute/resume/hitlReply）runAsync 内上下文 set + startRequest/endRequest + finally 清理 |
| `agent-demo-app/.../strategy/AbstractExecutionStrategy.java`、`ParallelExecutionStrategy.java` | 修改 | 步骤埋点（executeOrSkip 收口）+ 并行线程池 Runnable 包装传播 |
| `agent-demo-app/pom.xml` | 修改 | 补 observability 直接依赖 |
| `agent-demo-mcp/.../tool/McpToolExecutor.java` | 修改 | MCP 协议层埋点：execute 计时 + recordMcp（原始 argsJson，Wrapper 缓存 clear 前取值） |
| `agent-demo-mcp/pom.xml` | 修改 | 补 observability 直接依赖 |
| `agent-demo-skill/.../session/SkillSessionManager.java` | 修改 | 激活埋点：activate（含被拒）+ applyManualSelection（手动批量）两处 |
| `agent-demo-skill/pom.xml` | 修改 | 补 observability 直接依赖 |

**评估影响：**
| 评估资产 | 影响类型 | 说明 |
| :--- | :--- | :--- |
| 采集完整性行为测试（TraceCompletenessBehaviorTest 或新测试类） | 需扩充 | 五域各 >=1 正例 + 失败注入用例（AC-N05~N09） |
| 脱敏对抗性数据集（ObservabilityGuardrailBehaviorTest） | 需扩充 | 新内容类型正反例：检索命中块/压缩摘要段/工作流输出/MCP 参数与结果含密钥正例；含 sk-/ignore 正常文本反例；陷阱任务（绕过脱敏的对抗样本） |
| 已有评估用例 | 无影响（仅回归重跑） | 既有 43 观测模块测试 + 全模块基线零回归重验 |

### 1.5 回归风险评估
*   **变更类型对应回归级别**: 能力扩展 + 安全边界变更要素 -> **从严执行全量**（能力禁区条款修改 + 数据出境面扩大，按安全边界变更标准）
*   **本次回归范围清单**:
    1.  全部脱敏对抗性用例（正例 100% 命中 / 反例 0% 误伤，含新内容类型）
    2.  全部安全类 AC（AC-S01~S06）重验
    3.  全部人机协作 AC（AC-H01~H02）重验（自动化部分；云侧部分依赖 Task-14 并行推进）
    4.  全模块既有测试零回归（observability 43 / llm 148 / tools 160 / web 121 / app / rag / mcp / skill / memory 基线）
    5.  新增 AC 评估（N05~N09、E05、M03 用例全过）
*   **评估指标基线（变更前）**: 脱敏正例命中 100%、反例误伤 0%；无 Key 启动零网络请求；主流程零异常；ReAct 链路完整率 100%；上报成功率 >= 99%；既有测试套件全绿（观测 43 + llm 148 + tools 160 + web 121；app 模块 3 个工作流测试失败为既有基线）
*   **候选验证门槛**: 候选版本必须通过全部回归评估（含陷阱任务与 Pass^k 稳定性指标，至少 3 次运行取均值）方可上线；灰度期间指标劣化立即回滚
*   **安全边界检查**: 本次变更未削弱任何护栏规则与权限门控（脱敏/默认关/静默降级三重护栏不变，脱敏适用面扩大）；评估证据与候选变更隔离（测试用例在候选实现前编写，TDD RED 阶段锁定），未用候选版本生成/修改评估标准
*   **高风险区域**: ①工作流并行线程上下文传播（Runnable 包装遗漏致 span 归属错乱）②记忆压缩埋点位于消息写入路径（阻塞风险，靠 try-catch + 微秒级操作对冲）③McpToolExecutor 埋点取值时序（Wrapper 缓存 clear 前取值）④ModelFactory/各挂钩类构造器变更影响既有测试（需同步适配）

## 2. 需求变更详情 (Requirements Delta)
> 完整 AC 文本见需求文档 v1.1 §7（已就地更新），此处为索引与摘要。

### 2.1 新增/修改的行为验收标准

#### 正常交互 (Normal) - AC-N
- **AC-N05**: RAG 检索上报--检索 span（知识库标识/查询词/命中块数/耗时/成败）必现于 trace，命中块内容脱敏截断后上报
- **AC-N06**: 记忆压缩上报--压缩 span（前后消息数/压缩条数/摘要脱敏截断/降级标记），降级不丢记录
- **AC-N07**: 工作流编排上报--工作流级 + 步骤级双层 span 按执行序串联，终态必现
- **AC-N08**: MCP 协议层上报--MCP span（serverName/原始工具名/原始参数/协议耗时/状态），对话路径与工具 span 双层并存
- **AC-N09**: Skill 激活上报--激活/被拒均留痕（含手动批量激活）

#### 安全护栏 (Safety) - AC-S
- **AC-S06**: 新采集域脱敏前置--六类新 span 属性全部经 SensitiveDataMasker 单一出口，无绕行路径

#### 边界降级 (Edge) - AC-E
- **AC-E05**: 新埋点零回归--任一埋点异常时主流程行为与变更前完全一致（异常吞掉 + WARN + finally 清理）

#### 记忆上下文 (Memory) - AC-M
- **AC-M03**: 工作流 trace 会话关联--executionId 聚合键 + 云地 traceId 互查；并行步骤按执行 ID 归属不错乱

### 2.2 移除的内容
- 移除需求 §3.2 / §8.2 中「不采集 RAG/记忆压缩/工作流/MCP/Skill 事件」条款（CR-001 解除，五域纳入范围）
- 保留既有边界：AiServices 反射路径通用工具（非 MCP/RAG 收口类）不采集、embedding 调用不采集

## 3. 技术变更详情 (Technical Delta)

### 3.1 Prompt 架构变更
- 无（零 Prompt 制品变更）

### 3.2 工具空间变更
> 本节「工具」为可观测子系统的采集点（需求 §4.1 映射），非 Agent 业务工具。业务工具集零变更。

| 操作 | 采集点 | 说明 | 契约设计 |
| :--- | :--- | :--- | :--- |
| 新增 | RAG 检索监听 | searchByKbId 唯一收口埋点（覆盖对话+工作流全部检索） | 事件 record 定义于 TraceCollector 接口（Task-15），无外部契约 |
| 新增 | 记忆压缩监听 | 压缩回调注入（闭包捕获 sessionId） | 同上 |
| 新增 | 工作流编排监听 | 三入口根 span + executeOrSkip 步骤收口 + 并行线程池传播 | 同上 |
| 新增 | MCP 通信监听 | 协议层原始参数与耗时（双层平级 span，属性关联） | 同上 |
| 新增 | Skill 激活监听 | activate + applyManualSelection 两处（含被拒留痕） | 同上 |

### 3.3 护栏变更
- 护栏规则不削弱；脱敏输出过滤层适用面扩大至六类新 span（AC-S06 验证单一出口无绕行）
- 自主性级别不变（L4 风险归类 + 三重护栏对冲结构不变）

### 3.4 兼容性与回滚
*   **向前兼容**: 业务行为零变更（全部只读旁路埋点）；既有对话 trace 结构不变（新增 span 类型不影响既有 span）
*   **Prompt 版本回滚**: 不适用（零 Prompt 制品）
*   **代码回滚方案**: `langsmith.enabled=false`（或移除 LANGSMITH_API_KEY）秒级回到关闭态（AC-E03 装配级保障）；代码级 revert 五域埋点与接口扩展无数据迁移依赖；云侧数据可按 AC-H02 处置流程删除

## 4. 增量开发任务 (Incremental Tasks)
> 任务编号从原任务规划最后一个编号（Task-14）之后继续
> 每个任务耗时 < 2h (120m)
> 每个任务标注任务类型与验证策略，与 ai-agent-task-planning 规范一致
> 构建环境（项目习惯）：`export JAVA_HOME=/home/zhaoxc/tools/jdk17; export PATH="$JAVA_HOME/bin:/home/zhaoxc/tools/apache-maven-3.9.6/bin:$PATH"`；测试命令 `mvn test -pl {模块} -am "-Dtest=XXX" "-Dsurefire.failIfNoSpecifiedTests=false"`

### 阶段一：Prompt 制品变更 (Prompt Artifact Delta) - EDD
> 跳过（零 Prompt 制品变更，无概率性组件）

### 阶段二：工具与代码变更 (Tool & Code Delta) - TDD
> 按 RED -> GREEN -> REFACTOR 循环执行

- [x] **Task-15**: TraceCollector 接口扩展（6 方法 + 6 事件 record + Noop 空实现）
    *   **说明**: `TraceCollector` 接口新增 `recordRag(RagRetrievalEvent)` / `recordMemoryCompression(MemoryCompressionEvent)` / `recordWorkflow(WorkflowExecutionEvent)` / `recordWorkflowStep(WorkflowStepEvent)` / `recordMcp(McpCallEvent)` / `recordSkillActivation(SkillActivationEvent)` 六方法与对应嵌套 record（字段与技术方案 §7.1 span 设计表对应：RAG-kbId/query/命中块/耗时/成败；压缩-前后消息数/压缩条数/摘要/降级标记；工作流-模板/模式/状态/总耗时/executionId；步骤-步骤名/索引/状态/耗时/重试/输出；MCP-server/tool/原始参数/协议耗时/状态/断连；激活-skillId/名称/来源/绑定工具/拒绝原因）；`NoopTraceCollector` 补空实现；上下文字段（traceId/sessionId/executionId）不随事件传入，由实现方从 TraceContextHolder 读取（既有约定保持）
    *   **变更类型**: 修改
    *   **任务类型**: 确定性组件
    *   **验证策略**: TDD（Red-Green-Refactor）
    *   **通俗解释**: 做完这步后，"上报接口"清单里多了六种新记录类型（检索/压缩/工作流/步骤/MCP/激活），开关没开时它们全部是空操作
    *   **涉及文件**: `agent-demo-observability/src/main/java/com/agentdemo/observability/TraceCollector.java`、`NoopTraceCollector.java`
    *   **测试文件**: `agent-demo-observability/src/test/java/com/agentdemo/observability/NoopTraceCollectorTest.java`（扩展）、`TraceCollectorContractTest.java`（新增，接口契约测试）
    *   **参考**: 技术方案 §7.1 span 设计表（CR-001 六行）、决策 8~11
    *   **对应AC**: AC-N05~N09（采集接口基础）
    *   **预估工时**: 60m
    *   **依赖**: 无
    *   **验证标准**（TDD RED 阶段测试依据）:
        - [ ] 六个事件 record 字段与技术方案 §7.1 span 设计表一一对应
        - [ ] NoopTraceCollector 六新方法无副作用、不抛异常（isEnabled 仍为 false）
        - [ ] 既有 recordLlm/recordTool 契约零变化（既有测试零回归）

- [x] **Task-16**: OtlpTraceCollector 六类新 span 构建
    *   **说明**: 实现六新方法 -> OTel span 构建：span 命名与属性严格按技术方案 §7.1 新增六行（`rag search {kbName}` / `memory compress` / `workflow {templateName}` / `step {agentName}` / `mcp {server}.{tool}` / `skill activate {skillName}`，SpanKind 统一 CLIENT 除工作流根为 SERVER）；全部字符串属性经 `SensitiveDataMasker.maskSafe` 脱敏截断（单一出口，AC-S06）；上下文属性（`log.trace_id`/`gen_ai.conversation.id`/`langsmith.thread.id`）复用既有 putContextAttributes（工作流路径 conversation.id=executionId）；失败事件 StatusCode.ERROR + 异常属性；内部 try-catch WARN 降级（AC-E05）
    *   **变更类型**: 修改
    *   **任务类型**: 确定性组件
    *   **验证策略**: TDD（InMemorySpanExporter 断言 span 结构）
    *   **通俗解释**: 做完这步后，六种新记录会被真正加工成标准追踪记录并打上各自标签（哪个知识库/哪个工作流/哪个 MCP 服务等），发出去前统一脱敏
    *   **涉及文件**: `agent-demo-observability/src/main/java/com/agentdemo/observability/OtlpTraceCollector.java`
    *   **测试文件**: `agent-demo-observability/src/test/java/com/agentdemo/observability/OtlpTraceCollectorTest.java`（扩展）
    *   **参考**: 技术方案 §7.1、§6.2
    *   **对应AC**: AC-N05~N09、AC-S06、AC-E05、AC-M03
    *   **预估工时**: 120m
    *   **依赖**: Task-15
    *   **验证标准**（TDD RED 阶段测试依据）:
        - [ ] 六类事件 -> span 名称/属性/状态正确（含 ERROR 路径）
        - [ ] 含密钥的检索命中块/压缩摘要/MCP 参数 -> 输出属性无明文（AC-S06）
        - [ ] 超长字段截断 + `[TRUNCATED]` 标识（复用既有阈值）
        - [ ] collector 内部异常 -> WARN 不上抛（AC-E05）
        - [ ] 工作流事件 conversation.id=executionId（AC-M03）

- [x] **Task-17**: RAG 检索埋点（KnowledgeRetrieverTool）
    *   **说明**: `searchByKbId` 前后计时，构造 RagRetrievalEvent（kbId/query/kbName/命中块数/Top-N/maxScore/耗时/成败+异常）-> collector；异常路径先记录再上抛（对齐 ToolExecutor 既有模式）；agent-demo-rag pom 补 observability 直接依赖
    *   **变更类型**: 修改
    *   **任务类型**: 确定性组件
    *   **验证策略**: TDD（mock collector）
    *   **通俗解释**: 做完这步后，每次知识库检索都会记下"查了哪个库、用什么词、命中几块、花了多久、成没成功"，且对话和工作流两条路都覆盖
    *   **涉及文件**: `agent-demo-rag/src/main/java/com/agentdemo/rag/retriever/KnowledgeRetrieverTool.java`、`agent-demo-rag/pom.xml`
    *   **测试文件**: `agent-demo-rag/src/test/java/com/agentdemo/rag/retriever/KnowledgeRetrieverToolTest.java`（扩展或新增）
    *   **参考**: 技术方案 §3.1（RAG 检索埋点行）
    *   **对应AC**: AC-N05、AC-E05
    *   **预估工时**: 45m
    *   **依赖**: Task-15
    *   **验证标准**（TDD RED 阶段测试依据）:
        - [ ] 成功检索 -> 事件含 kbId/query/命中块数/耗时
        - [ ] 检索抛异常 -> 事件含异常且仍上抛给调用方（AC-E05）
        - [ ] 既有检索语义零变化（既有测试零回归）

- [x] **Task-18**: 记忆压缩埋点（回调注入）
    *   **说明**: `ChatMemoryManager.getMemory` 创建 `CompressingChatMemory` 时注入压缩回调（闭包捕获 sessionId，回调内构造 MemoryCompressionEvent -> collector）；CompressingChatMemory 在压缩完成点（含摘要失败 FIFO 降级路径）触发回调，回调自身 try-catch 吞异常；agent-demo-memory pom 补 observability 直接依赖；构造器新增参数对既有调用方提供默认重载（项目习惯：接口扩展同步适配）
    *   **变更类型**: 修改
    *   **任务类型**: 确定性组件
    *   **验证策略**: TDD（mock collector + 摘要器）
    *   **通俗解释**: 做完这步后，每次会话记忆"打包压缩"都会留一条记录：压缩前后各多少条、压成什么摘要、有没有降级，并且带上会话号
    *   **涉及文件**: `agent-demo-memory/src/main/java/com/agentdemo/memory/shortterm/ChatMemoryManager.java`、`CompressingChatMemory.java`、`agent-demo-memory/pom.xml`
    *   **测试文件**: `agent-demo-memory/src/test/java/com/agentdemo/memory/shortterm/`（扩展既有测试）
    *   **参考**: 技术方案决策 10、§3.1（记忆压缩埋点行）
    *   **对应AC**: AC-N06、AC-E05
    *   **预估工时**: 60m
    *   **依赖**: Task-15
    *   **验证标准**（TDD RED 阶段测试依据）:
        - [ ] 压缩触发 -> 回调收到含前后消息数/压缩条数/摘要的事件，sessionId 正确（闭包捕获）
        - [ ] 摘要 LLM 失败降级 FIFO -> 事件带降级标记且对话不中断（AC-N06）
        - [ ] 回调异常 -> 吞掉 + WARN，压缩主逻辑不受影响（AC-E05）
        - [ ] 既有记忆语义零变化（既有测试零回归）

- [x] **Task-19**: 工作流上下文传播与根 span（三入口 + 并行线程池包装）
    *   **说明**: `WorkflowExecutionService` 三入口（execute/resume/hitlReply）仿 AgentController.runTracedAsync 模式：runAsync 前 MDC 捕获 traceId，lambda 内 `TraceContextHolder.set(TraceContext(traceId, executionId))` + `startRequest` + finally `endRequest`/clear（executionId 作聚合键，决策 8）；`ParallelExecutionStrategy` 固定线程池任务 Runnable 包装 set/clear 传播（步骤 span 归属不错乱，AC-M03）；agent-demo-app pom 补 observability 直接依赖；**复用既有 startRequest/endRequest 接口，无新接口依赖**
    *   **变更类型**: 修改
    *   **任务类型**: 确定性组件
    *   **验证策略**: TDD
    *   **通俗解释**: 做完这步后，工作流执行终于有了"追踪起点"：每次执行（含暂停后恢复）都开一条新追踪线，并行步骤也都能正确挂到同一条线上
    *   **涉及文件**: `agent-demo-app/src/main/java/com/agentdemo/app/service/WorkflowExecutionService.java`、`strategy/ParallelExecutionStrategy.java`、`agent-demo-app/pom.xml`
    *   **测试文件**: `agent-demo-app/src/test/java/com/agentdemo/app/service/`（扩展既有测试）
    *   **参考**: 技术方案决策 8、§11 风险 5；AgentController.runTracedAsync 先例
    *   **对应AC**: AC-M03、AC-E05
    *   **预估工时**: 90m
    *   **依赖**: 无（复用既有接口，可与 Task-15 并行；Task-20 依赖本任务）
    *   **验证标准**（TDD RED 阶段测试依据）:
        - [ ] 三入口 runAsync 线程内 holder 有值（traceId/executionId 正确）
        - [ ] 执行结束 finally 清理（无泄漏）
        - [ ] 并行线程池内步骤执行时 holder 有值（Runnable 包装生效）
        - [ ] 上下文缺失路径（异常中断）不外抛（AC-E05）

- [x] **Task-20**: 工作流与步骤 span 埋点
    *   **说明**: `AbstractExecutionStrategy.executeOrSkip` 步骤收口埋点（步骤名/索引/状态/耗时--复用 StepExecution.durationMs/重试/输出 -> recordWorkflowStep）；`WorkflowExecutionService` 终态处理（handleTerminated/handleTimeout/handleFailure/handlePaused/handleHITLPaused/complete）记工作流级 span（模板/模式/状态/总耗时 -> recordWorkflow）；输出字段脱敏截断由 collector 单一出口承担；恢复步 resumePausedStep 同埋
    *   **变更类型**: 修改
    *   **任务类型**: 确定性组件
    *   **验证策略**: TDD（mock collector）
    *   **通俗解释**: 做完这步后，工作流的每一步（哪个 Agent 步骤、什么状态、耗时多少、重试几次）和整体结果（成功/失败/暂停/超时）都会出现在追踪里
    *   **涉及文件**: `agent-demo-app/src/main/java/com/agentdemo/app/strategy/AbstractExecutionStrategy.java`、`service/WorkflowExecutionService.java`
    *   **测试文件**: `agent-demo-app/src/test/java/com/agentdemo/app/strategy/`（扩展既有测试）
    *   **参考**: 技术方案 §3.1（工作流根 span + 步骤埋点行）、§7.1
    *   **对应AC**: AC-N07、AC-E05
    *   **预估工时**: 90m
    *   **依赖**: Task-15、Task-19
    *   **验证标准**（TDD RED 阶段测试依据）:
        - [ ] 步骤执行 -> 事件含步骤名/状态/耗时/重试次数（六策略统一经 executeOrSkip 收口）
        - [ ] 工作流各终态 -> 工作流级事件含模板/模式/状态/总耗时
        - [ ] 步骤失败/重试路径不丢事件
        - [ ] 既有工作流语义零变化（既有测试零回归，注意 app 模块 3 个既有失败为基线）

- [x] **Task-21**: MCP 协议层埋点（McpToolExecutor）
    *   **说明**: `McpToolExecutor.execute` 前后计时，构造 McpCallEvent（serverName/原始 toolName/原始 argsJson/协议耗时/状态/断连标记）-> collector；**结果取值须在 Wrapper 缓存 clear 之前**（调研结论）；异常路径先记再上抛；agent-demo-mcp pom 补 observability 直接依赖；对话路径与 ToolExecutor 工具 span 双层平级并存（决策 9，无去重逻辑）
    *   **变更类型**: 修改
    *   **任务类型**: 确定性组件
    *   **验证策略**: TDD（mock collector + mock client）
    *   **通俗解释**: 做完这步后，每次 MCP 调用会多记一条"协议层"明细：连的哪个服务、原始参数是什么、网络往返多久--对话和工作流（含此前的盲区）全覆盖
    *   **涉及文件**: `agent-demo-mcp/src/main/java/com/agentdemo/mcp/tool/McpToolExecutor.java`、`agent-demo-mcp/pom.xml`
    *   **测试文件**: `agent-demo-mcp/src/test/java/com/agentdemo/mcp/tool/McpToolExecutorTest.java`（扩展）
    *   **参考**: 技术方案决策 9、§3.1（MCP 协议埋点行）
    *   **对应AC**: AC-N08、AC-E05
    *   **预估工时**: 45m
    *   **依赖**: Task-15
    *   **验证标准**（TDD RED 阶段测试依据）:
        - [ ] 成功调用 -> 事件含 serverName/原始 toolName/原始 argsJson/耗时
        - [ ] 调用失败/断连 -> 事件含状态与异常且仍上抛（AC-E05）
        - [ ] 既有 MCP 执行语义零变化（既有测试零回归）

- [x] **Task-22**: Skill 激活埋点（SkillSessionManager）
    *   **说明**: `activate` 方法激活成功与被拒（上限/排除/禁用/不存在）两路径均记事件（skillId/skillName 可得时补齐/source=auto 或 manual/绑定脚本工具/拒绝原因）；`applyManualSelection` 手动批量选择路径逐项记录（source=manual）；agent-demo-skill pom 补 observability 直接依赖；幂等重复激活去重（仅首次记"新激活"）
    *   **变更类型**: 修改
    *   **任务类型**: 确定性组件
    *   **验证策略**: TDD（mock collector）
    *   **通俗解释**: 做完这步后，技能每次被启用（不管是 Agent 自己匹配的还是用户手动指定的）或被拒绝，都会留一条记录，包括绑定带了哪些脚本工具
    *   **涉及文件**: `agent-demo-skill/src/main/java/com/agentdemo/skill/session/SkillSessionManager.java`、`agent-demo-skill/pom.xml`
    *   **测试文件**: `agent-demo-skill/src/test/java/com/agentdemo/skill/session/`（扩展既有测试）
    *   **参考**: 技术方案 §3.1（Skill 激活埋点行）
    *   **对应AC**: AC-N09、AC-E05
    *   **预估工时**: 60m
    *   **依赖**: Task-15
    *   **验证标准**（TDD RED 阶段测试依据）:
        - [ ] 激活成功 -> 事件含 skillId/名称/来源/绑定工具
        - [ ] 被拒激活 -> 事件含拒绝原因（不丢记录）
        - [ ] 手动批量选择 -> 逐项记录 source=manual
        - [ ] 重复激活幂等 -> 不重复记录"新激活"
        - [ ] 既有激活语义零变化（既有测试零回归）

### 阶段三：评估数据集扩充 (Evaluation Dataset Delta)

- [x] **Task-23**: 对抗性测试扩充（新内容类型脱敏正反例 + 陷阱任务）
    *   **说明**: 扩充脱敏对抗用例至新内容类型：正例--检索命中块/压缩摘要段/工作流输出/MCP 参数与结果中含 `sk-` 密钥、Bearer 令牌、环境密钥值（断言六类新 span 输出属性无明文，AC-S06）；反例--含 `sk-`/`ignore` 等的正常知识文本 0 误伤；陷阱任务--构造绕过脱敏的对抗样本（如分片密钥、嵌套转义形态，断言保守整字段 `[MASKED]` 或命中）；既有对抗用例全量重跑（AC-S01~S06 全部重验）
    *   **变更类型**: 新增
    *   **任务类型**: 行为测试
    *   **验证策略**: 行为测试（评估数据集 + 对抗性测试）
    *   **通俗解释**: 做完这步后，会用自动化测试反复"攻击"新增的五个采集面：密钥无论藏在检索结果、压缩摘要还是 MCP 参数里，都必须被擦掉才能发出去
    *   **涉及文件**: `agent-demo-observability/src/test/java/com/agentdemo/observability/ObservabilityGuardrailBehaviorTest.java`（扩展）
    *   **参考**: 本文档 §1.4 评估影响、§1.5 回归范围
    *   **对应AC**: AC-S01~S06（全量重验）
    *   **预估工时**: 90m
    *   **依赖**: Task-16、Task-17~22
    *   **验证标准**:
        - [ ] 新内容类型正例 100% 命中、反例 0% 误伤
        - [ ] 陷阱任务无绕过路径（脱敏单一出口验证）
        - [ ] 既有对抗用例（AC-S01~S05）全部重跑通过

- [x] **Task-24**: 五域采集完整性行为测试
    *   **说明**: 扩充完整性用例：五域各 >=1 正例（事件发生 -> span 断言：名称/属性/状态/上下文）+ 失败注入用例（检索异常/压缩降级/步骤失败重试/MCP 断连/激活被拒均不丢记录）；工作流并行步骤归属断言（同一 executionId，AC-M03）；thread 聚合属性断言（conversation.id=executionId）；上下文 finally 清理断言（无 ThreadLocal 泄漏，AC-E05）
    *   **变更类型**: 新增
    *   **任务类型**: 行为测试
    *   **验证策略**: 行为测试（InMemorySpanExporter 无外联断言）
    *   **通俗解释**: 做完这步后，自动化测试会确认五个新采集面的记录"该记的都在、失败的也留痕、并行的不串线、用完的上下文不残留"
    *   **涉及文件**: `agent-demo-observability/src/test/java/com/agentdemo/observability/TraceCompletenessBehaviorTest.java`（扩展）+ 各域测试补充
    *   **参考**: 本文档 §1.4；技术方案 §7.1
    *   **对应AC**: AC-N05~N09、AC-M03、AC-E05
    *   **预估工时**: 90m
    *   **依赖**: Task-16、Task-17~22
    *   **验证标准**:
        - [ ] 五域正例 span 结构断言全过（必备字段齐全率 100%）
        - [ ] 失败注入用例全过（失败记录不丢失）
        - [ ] 并行步骤归属正确（executionId 一致）
        - [ ] 上下文无泄漏（finally 清理断言）

### 阶段四：回归验证 (Regression Verification)
> 回归范围按从严标准（能力扩展 + 安全边界变更要素）：全量对抗性回归

- [x] **Task-25**: 全量回归与候选验证
    *   **说明**: 按第 1.5 节回归范围清单执行：①全模块既有测试零回归（observability/llm/tools/web/app/rag/mcp/skill/memory）②全量对抗性用例 + AC-S01~S06 + AC-H01~H02（自动化部分）重验③新增 AC 评估全过④Pass^k 稳定性：全部相关测试套件重复运行 3 次（k=3）全绿，排除偶发⑤实际启动验证（项目习惯：mock 全绿 != 能启动；改动 Spring Bean 构造器的模块--memory/app/rag/mcp/skill--必须 `mvn -pl agent-demo-bootstrap spring-boot:run` 启动验证，无 Key 零外联）⑥候选 -> 验证 -> 灰度 -> 回滚链路就绪（灰度=`langsmith.enabled` 开关控制，回滚触发=任一指标劣化即关）⑦云侧冒烟依赖 Task-14 并行推进（真实 Key 下五域 span 在 LangSmith 可见）
    *   **变更类型**: 验证
    *   **任务类型**: 行为测试（评估数据集 + 对抗性测试）
    *   **验证策略**: 行为测试 + 集成验证
    *   **涉及文件**: 回归范围清单中的全部测试文件
    *   **对应AC**: 所有受影响的 AC（AC-S01~S06、AC-H01~H02 重验 + AC-N05~N09/E05/M03 新验）
    *   **预估工时**: 90m
    *   **依赖**: Task-23、Task-24（及阶段二全部任务）
    *   **验证标准**:
        - [ ] 原有单元测试全量通过（无回归；app 模块 3 个既有失败为基线，不新增）
        - [ ] 已达标评估指标不低于基线（脱敏 100%/0%、零外联、主流程零异常，见 §1.5）
        - [ ] 稳定性指标 Pass^3 达标（3 次运行全绿）
        - [ ] 陷阱任务无幻觉（一票否决项：编造字段值即判负）
        - [ ] 对抗性测试全部通过（强制全量）
        - [ ] 已有对话/工作流/检索/激活场景行为无异常变化
        - [ ] 灰度方案已就绪（enabled 开关回退路径 + 观察指标）

## 5. 增量验收标准检查清单 (Incremental AC Checklist)
> 仅包含本次变更涉及的验收标准

| 验收标准ID | 验收标准描述 | 状态 | 对应任务 | 操作 |
| :--- | :--- | :--- | :--- | :--- |
| AC-N05 | RAG 检索上报 | 待完成 | Task-15/16/17、Task-24 | 新增 |
| AC-N06 | 记忆压缩上报 | 待完成 | Task-15/16/18、Task-24 | 新增 |
| AC-N07 | 工作流编排上报 | 待完成 | Task-15/16/19/20、Task-24 | 新增 |
| AC-N08 | MCP 协议层上报 | 待完成 | Task-15/16/21、Task-24 | 新增 |
| AC-N09 | Skill 激活上报 | 待完成 | Task-15/16/22、Task-24 | 新增 |
| AC-S06 | 新采集域脱敏前置 | 待完成 | Task-16/23 | 新增 |
| AC-E05 | 新埋点零回归 | 待完成 | Task-16~22、Task-24/25 | 新增 |
| AC-M03 | 工作流 trace 会话关联 | 待完成 | Task-16/19/20、Task-24 | 新增 |
| AC-S01~S05 | 既有安全护栏 | 基线达标 | Task-23/25 | 全量重验（安全边界从严） |
| AC-H01~H02 | 评估追溯/泄漏处置 | 基线达标（自动化部分） | Task-25 | 全量重验（云侧依赖 Task-14） |

## 6. 变更总结 (Change Summary)
*   **总新增任务数**: 11 个（TDD 8 个 / EDD 0 个 / 行为测试 3 个）
*   **预计总工时**: 840 分钟（约 14 小时）
*   **风险等级**: 中
*   **风险说明**: ①工作流并行线程上下文传播是新设计（Runnable 包装遗漏致归属错乱，Task-19 专项 + Task-24 断言对冲）②记忆压缩埋点位于消息写入热路径（微秒级内存操作 + try-catch 对冲）③五个模块同时改构造器/装配（必须实际启动验证，项目习惯）④span 数量增长（截断阈值不变 + 队列余量充足）
*   **测试影响**: 需扩展 5 个既有测试类（Noop/OtlpTraceCollectorTest/KnowledgeRetrieverTool/McpToolExecutorTest/记忆与工作流测试），新增约 3 个测试类/用例组，扩充评估用例 >= 8 条 + 对抗性用例若干
*   **评估基线变化**: 既有指标全部保持（脱敏 100%/0%、零外联、主流程零异常、ReAct 完整率 100%、上报成功率 >= 99%）；新增五域必备字段齐全率目标 100%
*   **预期效果**: LangSmith 观察空间从 LLM+工具两类扩展至七类（+RAG/记忆压缩/工作流编排双层/MCP 协议层/Skill 激活）；工作流路径从"完全无 trace"补齐为"编排层+步骤+LLM+MCP/RAG 工具"完整链路；对话路径 MCP 调用获得协议层明细（原始参数+网络耗时）；被拒 Skill 激活与记忆降级事件可追溯
