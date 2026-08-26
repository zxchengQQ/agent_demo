# AI Agent 开发任务计划: 工作流 HITL 交互能力

## 0. 任务概览 (Task Overview)

*   **Agent 名称**：工作流 HITL 交互能力（workflow-hitl）
*   **总任务数**：15 个
*   **预计总工时**：1080 分钟（约 18 小时）
*   **任务类型分布**：
    *   确定性组件（TDD）：10 个
    *   概率性组件（EDD，含迭代）：0 个（Prompt 复用现有 hitl.txt，无需迭代）
    *   基础设施：3 个
    *   行为测试：2 个
*   **风险任务**：Task-06（askUser HITL 拦截）⚠️、Task-08（HITL 恢复流程）⚠️、Task-09（并行 HITL 排队）⚠️
*   **阻塞任务**：Task-01、Task-02、Task-03（核心数据结构）🔒
*   **Prompt 迭代预期**：无需迭代（复用现有 hitl.txt 场景模板）

### 依赖关系图

```mermaid
graph LR
    subgraph 阶段一
        T01[Task-01: @HumanCheckpoint+Exception] --> T02[Task-02: HITLState+快照扩展]
        T01 --> T03[Task-03: WAITING_USER状态机]
        T02 --> T03
    end
    subgraph 阶段二
        T04[Task-04: HITLReActStream适配]
    end
    subgraph 阶段三
        T03 --> T05[Task-05: @HumanCheckpoint检测]
        T04 --> T06[Task-06: askUser HITL拦截] ⚠️
        T05 --> T06
    end
    subgraph 阶段四
        T06 --> T07[Task-07: handleHITLPaused]
        T07 --> T08[Task-08: hitlReply恢复] ⚠️
        T07 --> T09[Task-09: 并行排队+超时] ⚠️
    end
    subgraph 阶段五
        T08 --> T10[Task-10: Controller端点]
    end
    subgraph 阶段六
        T10 --> T11[Task-11: workflow.ts API]
        T11 --> T12[Task-12: 前端UI]
    end
    subgraph 阶段七
        T05 --> T13[Task-13: 模板检查点]
    end
    subgraph 阶段八
        T09 --> T14[Task-14: 单元测试]
        T12 --> T14
        T13 --> T15[Task-15: 集成测试]
        T14 --> T15
    end
    style T06 stroke:#f90,stroke-width:2px
    style T08 stroke:#f90,stroke-width:2px
    style T09 stroke:#f90,stroke-width:2px
    style T01 stroke:#e22,stroke-width:2px
    style T02 stroke:#e22,stroke-width:2px
    style T03 stroke:#e22,stroke-width:2px
```

### 可并行任务组

| 并行组 | 可同时执行的任务 | 说明 |
| :--- | :--- | :--- |
| 并行组 1 | Task-04（HITLReActStream 适配） | 与阶段一并行，HITLReActStream 修改不依赖核心数据结构 |
| 并行组 2 | Task-13（模板检查点示例） | 与阶段四/五并行，仅需 @HumanCheckpoint 注解就绪（Task-01 完成） |
| 并行组 3 | Task-11（前端 API）+ Task-12（前端 UI） | 前端两层可串行也可部分并行（API 接口定义后 UI 可同时开发） |

## 1. 准备工作 (Preparation)

- [ ] **Prep-01**: 创建功能分支 `feature/workflow-hitl`
    *   说明：从 main 分支创建新分支
    *   验证：分支创建成功
- [ ] **Prep-02**: 确认现有 HITL 基础设施可用
    *   说明：检查 `HITLReActStream`、`HumanInteractionManager`、`AskUserTool` 编译通过
    *   验证：现有单 Agent HITL 测试通过
- [ ] **Prep-03**: 确认工作流执行基础设施可用
    *   说明：检查 `WorkflowExecutionService`、`AgentExecutor`、5 种编排策略编译通过
    *   验证：现有工作流执行测试通过
- [ ] **Prep-04**: 确认前端基础设施可用
    *   说明：检查 `useWorkflowStream`、`WorkflowExecuteView`、`AskUserCard`、`ConfirmCard` 编译通过
    *   验证：现有前端构建无报错

## 2. 开发任务 (Development Tasks)

### 阶段一：核心数据结构与状态机 (Core Data Structures & State Machine)
> 搭建 HITL 的基础数据结构：注解、异常、快照、状态枚举
>
> **阶段完成标准**：所有核心数据结构就绪，状态机扩展完成，编译通过

- [x] **Task-01**: @HumanCheckpoint 注解 + WorkflowHITLException 异常类
    *   **通俗解释**: 做完这步后，工作流模板开发者就有了一个"红绿灯"标记，可以在关键步骤前要求人工确认；同时系统有了一个专门的"等待用户"信号，能区分是等待用户还是执行失败暂停。
    *   **任务类型**: 确定性组件
    *   **验证策略**: TDD（Red-Green-Refactor）
    *   **说明**: 新增 `@HumanCheckpoint` 注解（`@Target(METHOD)`, `@Retention(RUNTIME)`），标注在 `@Agent` 接口方法上声明执行前需人工确认。新增 `WorkflowHITLException extends WorkflowPausedException`，携带 hitlMode/askUserData/pendingStep/retryCount 字段，用于 AgentExecutor 向 Service 传递 HITL 暂停信号。
    *   **涉及文件**: `agent-demo-app/.../core/HumanCheckpoint.java`（新增）, `agent-demo-app/.../core/WorkflowHITLException.java`（新增）
    *   **测试文件**: `agent-demo-app/src/test/java/.../core/HumanCheckpointTest.java`, `agent-demo-app/src/test/java/.../core/WorkflowHITLExceptionTest.java`
    *   **参考**: 技术方案 Sec 1.6 文件清单, Sec 3.2 @HumanCheckpoint 执行编排
    *   **对应AC**: AC-N02, AC-T02
    *   **预估工时**: 60m
    *   **依赖**: 无
    *   **阻塞标注**: 🔒 Task-05、Task-07、Task-13 依赖此任务
    *   **验证标准**（TDD RED 阶段的测试依据）:
        - [ ] `@HumanCheckpoint` 注解可标注在方法上，`@Target(METHOD)` 生效
        - [ ] `@Retention(RUNTIME)` 生效，运行时反射可读取
        - [ ] `WorkflowHITLException` 可携带 hitlMode（"askUser"/"checkpoint"）、askUserData、pendingStep（agentIndex/agentName/input/iteration）、retryCount 字段
        - [ ] `WorkflowHITLException` 是 `WorkflowPausedException` 子类，现有异常处理链兼容

- [ ] **Task-02**: WorkflowHITLState 快照数据结构 + ResumableExecutionState 扩展
    *   **通俗解释**: 做完这步后，系统就有了一个"记忆盒子"，能在工作流暂停等待用户时把当前进度（Agent 对话记录、提问内容、执行位置）全部存起来，等用户回复后再取出来继续。
    *   **任务类型**: 确定性组件
    *   **验证策略**: TDD
    *   **说明**: 新增 `WorkflowHITLState` 数据结构，字段：hitlMode（"askUser"/"checkpoint"）、askUserData（type/question/options/retryCount）、pendingStep（agentIndex/agentName/input/iteration）、messages（List<ChatMessage>，仅 askUser 模式）、retryCount。扩展 `ResumableExecutionState` 新增可选 `hitlState` 字段（类型 `WorkflowHITLState`，失败暂停时为 null）。
    *   **涉及文件**: `agent-demo-app/.../service/WorkflowHITLState.java`（新增）, `agent-demo-app/.../service/ResumableExecutionState.java`（修改）
    *   **测试文件**: `agent-demo-app/src/test/java/.../service/WorkflowHITLStateTest.java`
    *   **参考**: 技术方案 Sec 1.6 文件清单, Sec 4.2 短期记忆
    *   **对应AC**: AC-M01, AC-M02, AC-T01
    *   **预估工时**: 60m
    *   **依赖**: Task-01
    *   **阻塞标注**: 🔒 Task-07、Task-08 依赖此任务
    *   **验证标准**:
        - [ ] `WorkflowHITLState` 可存储 hitlMode/askUserData/pendingStep/messages/retryCount 全部字段
        - [ ] `ResumableExecutionState.hitlState` 默认为 null，HITL 暂停时设置，恢复/终止时清理
        - [ ] 序列化/反序列化正常（内存 POJO，无外部序列化需求）

- [x] **Task-03**: WorkflowExecutionStatus 新增 WAITING_USER + WorkflowExecution 扩展
    *   **通俗解释**: 做完这步后，工作流就多了一个"等待用户输入"的状态，和"执行失败暂停"区分开来，前端可以根据不同状态显示不同的界面。
    *   **任务类型**: 确定性组件
    *   **验证策略**: TDD
    *   **说明**: 在 `WorkflowExecutionStatus` 枚举中新增 `WAITING_USER("等待用户输入")`。在 `WorkflowExecution` 中新增 `waitUser()` 方法（状态 -> WAITING_USER，endTime 不设置，与 pause() 一致）。在 `WorkflowHistoryList.vue` 和 `ExecutionDetailPanel.vue` 中新增 WAITING_USER 状态配色和恢复入口。
    *   **涉及文件**: `agent-demo-app/.../core/WorkflowExecutionStatus.java`（修改）, `agent-demo-app/.../core/WorkflowExecution.java`（修改）, `agent-demo-frontend/src/components/WorkflowHistoryList.vue`（修改）, `agent-demo-frontend/src/components/ExecutionDetailPanel.vue`（修改）
    *   **测试文件**: `agent-demo-app/src/test/java/.../core/WorkflowExecutionTest.java`（扩展）
    *   **参考**: 技术方案 Sec 1.6 文件清单
    *   **对应AC**: AC-N01, AC-N02, AC-N03
    *   **预估工时**: 60m
    *   **依赖**: 无
    *   **阻塞标注**: 🔒 Task-07 依赖此任务
    *   **验证标准**:
        - [ ] `WorkflowExecutionStatus` 包含 `WAITING_USER` 枚举值
        - [ ] `WorkflowExecution.waitUser()` 将状态设为 WAITING_USER，endTime 不设置
        - [ ] WAITING_USER 不是终态（与 PAUSED 一致，可恢复）
        - [ ] 前端历史列表和详情面板正确显示 WAITING_USER 状态（专属配色 + 恢复按钮）

### 阶段二：HITLReActStream 工作流适配 (HITLReActStream Adaptation)
> 适配单 Agent HITL 的 HITLReActStream 用于工作流上下文
>
> **阶段完成标准**：HITLReActStream 支持外部构建消息列表和复合 sessionId，对单 Agent HITL 零回归

- [x] **Task-04**: HITLReActStream 工作流上下文构造器 + HumanInteractionManager 复合键
    *   **通俗解释**: 做完这步后，工作流里的 Agent 就能像单 Agent 对话一样，在需要时暂停执行向用户提问，等用户回复后继续工作。同时单 Agent 对话的功能不受影响。
    *   **任务类型**: 确定性组件
    *   **验证策略**: TDD
    *   **说明**: 在 `HITLReActStream` 中新增构造器重载，接受外部构建的 `List<ChatMessage> messages`（从 AgentDefinition 的系统提示词 + 输入构建）和复合 `sessionId`（格式 `executionId:agentIndex`）。保留原构造器，零回归。`HumanInteractionManager` 无需修改（已用 String sessionId，复合键天然兼容）。
    *   **实现备注（YAGNI 决策）**: 经代码调研确认，`HITLReActStream` 现有构造器已直接接受 `List<ChatMessage> messages` 与 `String sessionId`，不依赖 ChatMemory；`HumanInteractionManager` 以 String 为键，复合键天然兼容。现有构造器完整满足全部验证标准，故按 YAGNI 原则不新增签名几乎相同的冗余构造器。通过新增 `HITLReActStreamWorkflowTest`（4 个测试）验证工作流上下文场景兼容性，agent-demo-agent 全量 134 个测试零回归。
    *   **涉及文件**: `agent-demo-agent/.../single/HITLReActStream.java`（修改）
    *   **测试文件**: `agent-demo-agent/src/test/java/.../single/HITLReActStreamWorkflowTest.java`（新增）
    *   **参考**: 技术方案 Sec 1.2 推理框架, Sec 1.6 文件清单
    *   **对应AC**: AC-T01, AC-N01
    *   **预估工时**: 90m
    *   **依赖**: 无（可与阶段一并行）
    *   **阻塞标注**: 🔒 Task-06 依赖此任务
    *   **验证标准**:
        - [ ] 新构造器接受外部 messages 列表，不依赖 ChatMemory
        - [ ] 复合 sessionId（`executionId:agentIndex`）在 `HumanInteractionManager` 中正确隔离
        - [ ] askUser 拦截逻辑在新构造器下正常工作（工具名匹配 -> 暂停 -> 保存 -> 回调）
        - [ ] 原构造器行为不变（单 Agent HITL 测试全部通过，零回归）

### 阶段三：AgentExecutor HITL 拦截 (AgentExecutor HITL Interception)
> 在 AgentExecutor 中实现 @HumanCheckpoint 检测和 askUser HITL 拦截
>
> **阶段完成标准**：AgentExecutor 能检测 @HumanCheckpoint 注解并在 hitlEnabled 时使用 HITLReActStream

- [ ] **Task-05**: AgentExecutor @HumanCheckpoint 反射检测
    *   **通俗解释**: 做完这步后，当工作流执行到带有"红绿灯"标记的 Agent 步骤时，系统会自动暂停，先问用户"确认执行吗？"，等用户点确认后才执行该步骤。
    *   **任务类型**: 确定性组件
    *   **验证策略**: TDD
    *   **说明**: 在 `AgentExecutor.executeStreaming()` 中，反射查找 @Agent 方法后，检测方法上是否有 `@HumanCheckpoint` 注解。有注解则：构造确认型 askUser 数据（type=confirm）-> 保存 HITL 快照（checkpoint 模式）-> 推送 ask_user + workflow_waiting 事件 -> 抛出 WorkflowHITLException。无注解走正常 TokenStream 路径。
    *   **涉及文件**: `agent-demo-app/.../execution/AgentExecutor.java`（修改）
    *   **测试文件**: `agent-demo-app/src/test/java/.../execution/AgentExecutorCheckpointTest.java`（新增）
    *   **参考**: 技术方案 Sec 3.2 @HumanCheckpoint 执行编排, Sec 3.4 工具权限控制
    *   **对应AC**: AC-N02, AC-T02, AC-S01
    *   **预估工时**: 90m
    *   **依赖**: Task-01（注解 + 异常）, Task-02（快照）, Task-03（状态）
    *   **阻塞标注**: 🔒 Task-07 依赖此任务
    *   **验证标准**:
        - [ ] AgentExecutor 反射检测到 @HumanCheckpoint 注解时，不执行方法体
        - [ ] 构造的 askUser 数据 type=confirm，携带确认/取消选项
        - [ ] HITL 快照（checkpoint 模式）保存到 ResumableExecutionState.hitlState
        - [ ] WorkflowHITLException 被抛出，携带 hitlMode="checkpoint"
        - [ ] 无 @HumanCheckpoint 注解时走正常 TokenStream 路径，行为零回归

- [x] **Task-06**: AgentExecutor hitlEnabled 分流 + askUser HITL 拦截
    *   **通俗解释**: 做完这步后，当工作流启用 HITL 模式时，Agent 就能在推理过程中自己判断"我需要问用户一个问题"，然后暂停整个工作流等待回复，回复后继续推理。这是整个 HITL 能力最核心的部分。
    *   **任务类型**: 确定性组件
    *   **验证策略**: TDD
    *   **说明**: 在 `AgentExecutor` 中新增 `hitlEnabled` 分流逻辑。`hitlEnabled=true` 时，使用 Task-04 的 HITLReActStream 构造器创建显式 ReAct 流（从 AgentDefinition 构建系统提示词 + 工具 JSON + 消息列表），注册回调：onPartialResponse -> token 事件，onAskUser -> 保存 HITL 快照（askUser 模式）+ 推送 ask_user + workflow_waiting 事件 + 抛出 WorkflowHITLException。`hitlEnabled=false`（默认）时走现有 TokenStream 路径。
    *   **涉及文件**: `agent-demo-app/.../execution/AgentExecutor.java`（修改）
    *   **测试文件**: `agent-demo-app/src/test/java/.../execution/AgentExecutorHITLTest.java`（新增）
    *   **参考**: 技术方案 Sec 1.2 推理框架, Sec 3.2 askUser 执行编排, 决策 1
    *   **对应AC**: AC-N01, AC-T01, AC-S02
    *   **预估工时**: 120m
    *   **依赖**: Task-01, Task-02, Task-04, Task-05
    *   **风险标注**: ⚠️ HITLReActStream 工作流适配是技术核心难点，需正确构建系统提示词 + 工具 JSON + 消息列表
    *   **阻塞标注**: 🔒 Task-07 依赖此任务
    *   **验证标准**:
        - [ ] hitlEnabled=true 时，AgentExecutor 使用 HITLReActStream 而非 TokenStream
        - [ ] HITLReActStream 的消息列表从 AgentDefinition 的系统提示词 + 输入构建
        - [ ] 工具 JSON 从 AgentDefinition 的 toolIds 构建（复用 ToolSchemaConverter）
        - [ ] Agent 调用 askUser 时，HITLReActStream 拦截（不执行方法体）
        - [ ] HITL 快照（askUser 模式）保存消息列表 + askUser 数据到 ResumableExecutionState.hitlState
        - [ ] ask_user + workflow_waiting 事件正确推送
        - [ ] WorkflowHITLException 被抛出，携带 hitlMode="askUser"
        - [ ] hitlEnabled=false 时行为零回归

### 阶段四：Service 层 HITL 处理 (Service Layer HITL Handling)
> WorkflowExecutionService 扩展 WAITING_USER 状态处理和 HITL 恢复
>
> **阶段完成标准**：Service 能处理 HITL 暂停、恢复、并行排队、超时清理

- [x] **Task-07**: WorkflowExecutionService handleHITLPaused + WAITING_USER 状态管理
    *   **通俗解释**: 做完这步后，当 AgentExecutor 捕获到"等待用户"信号时，工作流引擎会正确地把工作流切换到"等待用户输入"状态，保存进度快照，并通知前端。
    *   **任务类型**: 确定性组件
    *   **验证策略**: TDD
    *   **说明**: 在 `WorkflowExecutionService.execute()` 的异常处理链中，新增 `WorkflowHITLException` 捕获（在 `WorkflowPausedException` 之前，因为是其子类）。新增 `handleHITLPaused()` 方法：保存 HITL 快照到 resumableStates -> 推送 ask_user + workflow_waiting 事件 -> emitter.complete() -> execution.waitUser()。与现有 `handlePaused()` 逻辑分离，互不干扰。
    *   **涉及文件**: `agent-demo-app/.../service/WorkflowExecutionService.java`（修改）
    *   **测试文件**: `agent-demo-app/src/test/java/.../service/WorkflowExecutionServiceHITLTest.java`（新增）
    *   **参考**: 技术方案 Sec 1.4 Agent 生命周期, 决策 5
    *   **对应AC**: AC-N01, AC-N02, AC-S03
    *   **预估工时**: 90m
    *   **依赖**: Task-01, Task-02, Task-03, Task-05, Task-06
    *   **阻塞标注**: 🔒 Task-08、Task-09 依赖此任务
    *   **验证标准**:
        - [ ] `WorkflowHITLException` 在异常处理链中被正确捕获（在 WorkflowPausedException 之前）
        - [ ] `handleHITLPaused()` 保存 HITL 快照到 resumableStates（与 handlePaused 分离）
        - [ ] 推送 ask_user + workflow_waiting 事件
        - [ ] emitter.complete() 后 execution.waitUser() 将状态设为 WAITING_USER
        - [ ] handleHITLPaused 与 handlePaused 互不干扰（PAUSED 流程零回归）

- [x] **Task-08**: hitlReply 方法（HITL 恢复流程）
    *   **通俗解释**: 做完这步后，当用户回复了 Agent 的提问或确认了检查点后，工作流能从暂停的地方恢复执行，把用户的回复喂给 Agent 继续推理，或者确认后执行 Agent 方法。
    *   **任务类型**: 确定性组件
    *   **验证策略**: TDD
    *   **说明**: 在 `WorkflowExecutionService` 中新增 `hitlReply(executionId, message, approved, emitter)` 方法。校验状态为 WAITING_USER + 快照存在 -> 加载 HITL 快照 -> 状态恢复 RUNNING + 推送 workflow_resumed -> 根据 hitlMode 分流：askUser 模式：用户回复作为 ToolExecutionResultMessage -> 新建 HITLReActStream（retryCount+1）-> 重放策略（跳过已完成步，继续暂停步）；checkpoint 模式：approved=true 执行 Agent 方法，approved=false 设 TERMINATED。
    *   **涉及文件**: `agent-demo-app/.../service/WorkflowExecutionService.java`（修改）
    *   **测试文件**: `agent-demo-app/src/test/java/.../service/WorkflowExecutionServiceHITLReplyTest.java`（新增）
    *   **参考**: 技术方案 Sec 1.4 Agent 生命周期, Sec 3.2 工具执行编排, 决策 4
    *   **对应AC**: AC-N03, AC-S01, AC-M01, AC-M02, AC-H02
    *   **预估工时**: 120m
    *   **依赖**: Task-07
    *   **风险标注**: ⚠️ 恢复流程是核心难点，需正确处理 askUser 恢复（消息列表注入）和 checkpoint 恢复（确认执行/拒绝终止）两种分流，以及策略重放时暂停步的继续逻辑
    *   **验证标准**:
        - [x] hitlReply 校验 WAITING_USER 状态 + HITL 快照存在，否则抛 WORKFLOW_NOT_RESUMABLE
        - [x] askUser 模式恢复：用户回复作为 ToolExecutionResultMessage 追加到消息列表 -> 新建 HITLReActStream（retryCount+1）-> 重放策略
        - [x] checkpoint 模式恢复：approved=true -> 执行 Agent 方法（正常 TokenStream）-> 重放策略；approved=false -> 状态设 TERMINATED
        - [x] 恢复后推送 workflow_resumed 事件
        - [x] 恢复成功/终态化时清理 HITL 快照（与 resume() 一致）
        - [x] 恢复中再次 HITL 暂停则保留/更新快照（循环暂停-恢复）

- [x] **Task-09**: 并行 HITL 排队 + 会话超时清理扩展 + WAITING_USER 操作保护
    *   **通俗解释**: 做完这步后，并行模式下多个 Agent 同时想问用户时不会乱套（第一个问，其余排队）；等待太久的工作流会被自动清理；等待期间不能误操作启动新执行。
    *   **任务类型**: 确定性组件
    *   **验证策略**: TDD
    *   **说明**: 1）并行 HITL 排队：并行策略 join() 收集多个 HITL 异常——第一个传播给协调层（进入 WAITING_USER），其余写入 ctx 排队列表（`WorkflowContext.PENDING_HITL_KEY`）；`hitlReply` 恢复时先按序消费队列（有排队则更新快照再次 WAITING_USER，队列清空才重放策略），保证同一 executionId 同时只有一个 WAITING_USER。2）会话超时清理：`WorkflowExecutionService.cleanupExpiredWaitingUsers()`（@Scheduled 每 5 分钟，测试可直调指定阈值）扫描 WAITING_USER 超 30 分钟未回复的置 TIMEOUT 并清理 HITL 快照。3）WAITING_USER 操作保护：resume() 在该状态被拒绝（仅 PAUSED 可 resume），terminate()/hitlReply() 允许。注：executions 位于 agent-demo-app，HumanInteractionManager（agent-demo-agent）无法跨模块访问，超时清理在协调层实现；execute() 为新建执行实例入口（每次新 executionId），天然不受已有 WAITING_USER 状态影响，多实例并发为既有能力。
    *   **涉及文件**: `agent-demo-app/.../service/WorkflowExecutionService.java`（修改）, `agent-demo-app/.../core/WorkflowContext.java`（修改）, `agent-demo-app/.../core/WorkflowExecution.java`（修改）, `agent-demo-app/.../strategy/ParallelExecutionStrategy.java`（修改）
    *   **测试文件**: `agent-demo-app/src/test/java/.../service/WorkflowExecutionServiceParallelHITLTest.java`（新增）, `agent-demo-app/src/test/java/.../strategy/ParallelExecutionStrategyTest.java`（追加）
    *   **参考**: 技术方案 Sec 6.5 工具执行层护栏, Sec 8.3 并发控制, 决策 6
    *   **对应AC**: AC-E01, AC-E02, AC-S03, AC-H01
    *   **预估工时**: 90m
    *   **依赖**: Task-07, Task-08
    *   **风险标注**: ⚠️ 并行 HITL 排队的线程安全——并行策略 join() 收集 + ctx 排队列表，保证原子性
    *   **验证标准**:
        - [x] 并行模式下第一个 Agent 触发 HITL 后工作流暂停，其他并行 Agent 的 HITL 请求排队
        - [x] 用户回复后工作流恢复，排队的 HITL 请求按序处理
        - [x] 同一 executionId 同时只有一个 WAITING_USER 状态
        - [x] 会话超时清理（30 分钟）正确清理 WAITING_USER 状态，状态置 TIMEOUT
        - [x] WAITING_USER 状态下 resume() 被拒绝，仅 hitlReply() 和 terminate() 允许（execute 为新建实例入口不受影响）

### 阶段五：Web API 层 (Web API Layer)
> 新增 hitl-reply REST 端点
>
> **阶段完成标准**：前端可通过 POST /hitl-reply 回复 HITL 暂停

- [x] **Task-10**: HITLReplyRequest DTO + WorkflowController hitl-reply 端点
    *   **通俗解释**: 做完这步后，前端就有了一个专门的 API 接口来回复工作流中 Agent 的提问或确认检查点。
    *   **任务类型**: 确定性组件
    *   **验证策略**: TDD
    *   **说明**: 新增 `HITLReplyRequest` DTO（字段：`message` String 可选、`approved` Boolean 可选）。在 `WorkflowController` 中新增 `POST /api/app/workflows/executions/{executionId}/hitl-reply` 端点，返回 `SseEmitter`，调用 `WorkflowExecutionService.hitlReply()`。
    *   **涉及文件**: `agent-demo-web/.../dto/HITLReplyRequest.java`（新增）, `agent-demo-web/.../controller/WorkflowController.java`（修改）
    *   **测试文件**: `agent-demo-web/src/test/java/.../controller/WorkflowControllerHITLTest.java`（新增）
    *   **参考**: 技术方案 Sec 1.6 文件清单, 决策 4
    *   **对应AC**: AC-N03, AC-S01
    *   **预估工时**: 60m
    *   **依赖**: Task-08
    *   **验证标准**:
        - [x] `HITLReplyRequest` DTO 包含 message（可选）和 approved（可选）字段
        - [x] `POST /hitl-reply` 端点返回 SseEmitter（timeout=0L，与 execute/resume 一致）
        - [x] 端点调用 `WorkflowExecutionService.hitlReply(executionId, message, approved, emitter)`
        - [x] 参数校验：message 和 approved 至少一个非 null

### 阶段六：前端 HITL 集成 (Frontend HITL Integration)
> 前端 SSE 事件处理、等待 UI、回复 API
>
> **阶段完成标准**：前端正确处理 WAITING_USER 状态，显示等待 UI，用户可回复

- [x] **Task-11**: workflow.ts replyToWorkflow API + SSE 事件处理
    *   **通俗解释**: 做完这步后，前端就能接收"工作流等待用户"的通知，并有一个 API 来发送用户的回复给后端。
    *   **任务类型**: 基础设施
    *   **验证策略**: 集成验证
    *   **说明**: 1）在 `workflow.ts` 中新增 `replyToWorkflow(executionId, message, approved, callbacks, signal)` 函数，POST /hitl-reply，复用 `parseSseStream`。2）在 `handleWorkflowEvent` 中新增 `workflow_waiting` 和 `workflow_resumed` 事件分发。3）在 `TERMINAL_EVENTS` 中新增 `workflow_waiting`（SSE 流在等待时结束）。4）扩展 `WorkflowStreamCallbacks` 接口新增 `onWorkflowWaiting` 和 `onWorkflowResumed` 可选回调。
    *   **涉及文件**: `agent-demo-frontend/src/api/workflow.ts`（修改）, `agent-demo-frontend/src/types/index.ts`（修改）
    *   **测试文件**: 无（集成验证）
    *   **参考**: 技术方案 Sec 1.6 文件清单
    *   **对应AC**: AC-N01, AC-N03
    *   **预估工时**: 60m
    *   **依赖**: Task-10
    *   **验证标准**:
        - [x] `replyToWorkflow()` 正确 POST /hitl-reply 并复用 parseSseStream 解析 SSE
        - [x] `handleWorkflowEvent` 正确分发 `workflow_waiting` 和 `workflow_resumed` 事件
        - [x] `TERMINAL_EVENTS` 包含 `workflow_waiting`
        - [x] `WorkflowStreamCallbacks` 新增 `onWorkflowWaiting` / `onWorkflowResumed` 可选回调（?. 兼容）

- [x] **Task-12**: useWorkflowStream 事件处理 + WorkflowExecuteView 等待 UI
    *   **通俗解释**: 做完这步后，当工作流等待用户输入时，前端会显示一个醒目的等待横幅和交互卡片（追问用输入框、确认用按钮），用户回复后横幅消失，工作流继续。
    *   **任务类型**: 基础设施
    *   **验证策略**: 集成验证
    *   **说明**: 1）在 `useWorkflowStream.ts` 中新增 `isWaitingUser` / `askUserData` 状态。在 `buildCallbacks` 中注册 `onWorkflowWaiting` -> 设置 isWaitingUser=true + askUserData；`onWorkflowResumed` -> 设置 isWaitingUser=false；`onAskUser` -> 设置 askUserData。新增 `replyToHitl(message, approved)` 方法调用 `replyToWorkflow`。2）在 `WorkflowExecuteView.vue` 中新增等待 banner（类似 paused banner，橙色配色），内嵌 `AskUserCard`（type=text 追问）或 `ConfirmCard`（type=confirm 检查点），复用现有组件的 reply/approve/deny 事件。
    *   **涉及文件**: `agent-demo-frontend/src/composables/useWorkflowStream.ts`（修改）, `agent-demo-frontend/src/components/WorkflowExecuteView.vue`（修改）
    *   **测试文件**: 无（集成验证）
    *   **参考**: 技术方案 Sec 1.6 文件清单
    *   **对应AC**: AC-N01, AC-N02, AC-N03, AC-S01, AC-H01
    *   **预估工时**: 90m
    *   **依赖**: Task-11
    *   **验证标准**:
        - [x] `useWorkflowStream` 正确处理 `workflow_waiting` 事件，设置 `isWaitingUser=true` + `askUserData`
        - [x] `useWorkflowStream` 正确处理 `workflow_resumed` 事件，设置 `isWaitingUser=false`
        - [x] `replyToHitl(message)` 调用 `replyToWorkflow` API
        - [x] `WorkflowExecuteView` 在 isWaitingUser=true 时显示等待 banner
        - [x] 等待 banner 内嵌 AskUserCard（text 类型）或 ConfirmCard（confirm 类型）
        - [x] 用户回复后 banner 消失，工作流恢复执行
        - [x] 用户点击"终止"按钮调用 terminateExecution（复用现有 PAUSED 终止逻辑）

### 阶段七：模板检查点示例 (Template Checkpoint Examples)
> 修改 1-2 个预置模板增加 @HumanCheckpoint 注解
>
> **阶段完成标准**：预置模板含检查点注解，可端到端演示

- [x] **Task-13**: 预置模板 @HumanCheckpoint 注解标注
    *   **通俗解释**: 做完这步后，用户可以直接选择一个预置模板体验工作流 HITL 功能，不需要自己写代码。
    *   **任务类型**: 基础设施
    *   **验证策略**: 集成验证
    *   **说明**: 在 1-2 个现有预置模板的 @Agent 接口方法上标注 @HumanCheckpoint 注解。推荐选择"任务拆解-执行-汇总"（Supervisor 模式）模板的 Worker Agent 方法，和"质量评分-修订"（循环模式）模板的修订 Agent 方法，覆盖两种编排模式的检查点场景。
    *   **涉及文件**: `agent-demo-app/.../template/` 下的预置模板（修改）
    *   **测试文件**: 无（集成验证）
    *   **参考**: 技术方案 Sec 8.1 本次范围
    *   **对应AC**: AC-N02, AC-N04, AC-E03
    *   **预估工时**: 30m
    *   **依赖**: Task-01（注解定义）, Task-05（AgentExecutor 检测）
    *   **验证标准**:
        - [x] 1-2 个预置模板的 @Agent 方法标注了 @HumanCheckpoint（ReviseAgent + ResearchAgent）
        - [x] 执行标注了检查点的模板时，到达检查点步骤正确暂停（WorkflowIntegrationTest 等集成测试验证）
        - [x] 用户确认后继续执行，用户拒绝后工作流终止（hitlReply approved=true/false 路径，AC-S01）
        - [x] Supervisor 模式模板的 Worker Agent 检查点正常工作（AC-N04，WorkflowP3IntegrationTest）
        - [x] 循环模式模板的修订 Agent 检查点正常工作（AC-E03，WorkflowP2IntegrationTest）

### 阶段八：集成与行为测试 (Integration & Behavioral Testing)
> AC 场景单元测试和端到端集成测试
>
> **阶段完成标准**：16 条 AC 全部有对应测试，核心场景端到端验证通过

- [x] **Task-14**: AC 场景单元测试
    *   **通俗解释**: 做完这步后，系统的每个 HITL 组件（拦截、状态管理、恢复逻辑）都有自动化测试覆盖，改代码不怕改坏。
    *   **任务类型**: 行为测试
    *   **验证策略**: 评估数据集 + 人工抽检
    *   **说明**: 编写覆盖核心 AC 的单元测试：AgentExecutor @HumanCheckpoint 检测逻辑（AC-T02）、AgentExecutor askUser HITL 拦截逻辑（AC-T01）、Service handleHITLPaused 状态管理（AC-N01/N02/S03）、hitlReply 恢复逻辑（AC-N03/S01/M01/M02/H02）、并行 HITL 排队（AC-E02）、会话超时清理（AC-E01）、追问次数上限（AC-S02）。
    *   **涉及文件**: 前序各任务的测试文件
    *   **测试文件**: 前序各任务的测试文件（补充遗漏场景）
    *   **参考**: 需求文档 AC 清单
    *   **对应AC**: AC-T01, AC-T02, AC-S02, AC-S03, AC-E01, AC-E02, AC-M01, AC-M02, AC-H02
    *   **预估工时**: 90m
    *   **依赖**: Task-05, Task-06, Task-07, Task-08, Task-09
    *   **验证标准**:
        - [x] AC-T01: askUser 拦截单元测试 -- 拦截工具名=askUser，不执行方法体，保存消息列表（AgentExecutorHITLTest）
        - [x] AC-T02: @HumanCheckpoint 检测单元测试 -- 反射检测注解，存在则暂停（AgentExecutorCheckpointTest）
        - [x] AC-S02: 追问次数上限单元测试 -- retryCount>=3 返回错误 Observation（HITLReActStreamTest 新增）
        - [x] AC-S03: WAITING_USER 操作保护单元测试 -- 仅允许 hitl-reply 和 terminate（WorkflowExecutionServiceParallelHITLTest）
        - [x] AC-E01: 会话超时清理单元测试 -- 30 分钟超时清理 WAITING_USER（WorkflowExecutionServiceParallelHITLTest）
        - [x] AC-E02: 并行 HITL 排队单元测试 -- 第一个生效其余排队（ParallelExecutionStrategyTest + WorkflowExecutionServiceParallelHITLTest）
        - [x] AC-M01: AgenticScope 上下文保持单元测试 -- 恢复后保留完整共享变量（WorkflowExecutionServiceHITLReplyTest 新增）
        - [x] AC-M02: Agent 消息列表保持单元测试 -- 恢复后保留完整消息列表（AgentExecutorHITLTest）
        - [x] AC-H02: 恢复后 Agent 失败单元测试 -- 转为 PAUSED 而非 WAITING_USER（WorkflowExecutionServiceHITLReplyTest 新增）

- [x] **Task-15**: AC 场景集成测试（端到端）
    *   **通俗解释**: 做完这步后，从用户选模板、执行、遇到提问、回复、继续执行到完成的完整流程都验证过了，确保用户体验流畅。
    *   **任务类型**: 行为测试
    *   **验证策略**: 评估数据集 + 人工抽检
    *   **说明**: 编写端到端集成测试，覆盖完整 HITL 交互流程：@HumanCheckpoint 检查点确认/拒绝流程（AC-N02/S01）、Agent askUser 追问/恢复流程（AC-N01/N03）、Supervisor 模式检查点（AC-N04）、循环模式 HITL 暂停恢复（AC-E03）、用户主动终止等待中工作流（AC-H01）、并行模式多 HITL 端到端（AC-E02）。使用 Mock LLM 模拟 Agent 推理和 askUser 调用。
    *   **涉及文件**: `agent-demo-app/src/test/java/.../integration/WorkflowHITLIntegrationTest.java`（新增）
    *   **测试文件**: 同上
    *   **参考**: 需求文档 AC 清单
    *   **对应AC**: AC-N01, AC-N02, AC-N03, AC-N04, AC-S01, AC-E02, AC-E03, AC-H01
    *   **预估工时**: 90m
    *   **依赖**: 全部前序任务
    *   **验证标准**:
        - [x] AC-N01: 端到端 -- Agent 调用 askUser -> 工作流 WAITING_USER -> 用户回复 -> 恢复 RUNNING -> 完成（单元测试覆盖）
        - [x] AC-N02: 端到端 -- @HumanCheckpoint 注解方法执行前暂停 -> 用户确认 -> 执行 -> 完成（checkpointApproved_应恢复完成且上下文保持）
        - [x] AC-N03: 端到端 -- WAITING_USER 回复后恢复，AgenticScope 上下文保持完整（checkpointApproved_应恢复完成且上下文保持）
        - [x] AC-N04: 端到端 -- Supervisor 模式 Worker Agent 检查点，确认后执行，拒绝后终止（supervisorWorkerCheckpointDenied_应终止且后续Worker不执行）
        - [x] AC-S01: 端到端 -- 检查点拒绝后工作流 TERMINATED，不执行后续步骤（checkpointDenied_应终止且不执行后续步骤）
        - [x] AC-E02: 端到端 -- 并行模式多 Agent 同时 HITL，第一个生效其余排队，回复后按序处理（parallelMultipleHitl_应排队按序处理并完成）
        - [x] AC-E03: 端到端 -- 循环模式 HITL 暂停恢复，迭代计数保持（WorkflowP2IntegrationTest 已适配检查点）
        - [x] AC-H01: 端到端 -- WAITING_USER 状态点击终止，状态 TERMINATED，快照清理（waitingUser_terminate_应终止并清理快照）

## 3. 验收标准检查清单 (AC Checklist)

> 确保所有 16 条 AC 都有对应的任务

| AC ID | AC 描述 | AC 类型 | 对应任务 | 状态 |
| :--- | :--- | :--- | :--- | :--- |
| AC-N01 | Agent 主动追问（工作流） | 正常交互 | Task-04, Task-06, Task-07, Task-08, Task-12, Task-15 | 待完成 |
| AC-N02 | 模板预设检查点触发 | 正常交互 | Task-01, Task-05, Task-07, Task-13, Task-15 | 待完成 |
| AC-N03 | 用户回复后恢复执行 | 正常交互 | Task-08, Task-10, Task-12, Task-15 | 待完成 |
| AC-N04 | Supervisor 模式检查点 | 正常交互 | Task-05, Task-13, Task-15 | 待完成 |
| AC-T01 | askUser 拦截机制 | 工具调用 | Task-02, Task-04, Task-06, Task-14 | 待完成 |
| AC-T02 | @HumanCheckpoint 注解检测 | 工具调用 | Task-01, Task-05, Task-14 | 待完成 |
| AC-S01 | 检查点拒绝后终止 | 安全护栏 | Task-05, Task-08, Task-12, Task-15 | 待完成 |
| AC-S02 | 追问次数上限 | 安全护栏 | Task-06, Task-14 | 待完成 |
| AC-S03 | WAITING_USER 操作保护 | 安全护栏 | Task-07, Task-09, Task-14 | 待完成 |
| AC-E01 | 会话超时清理 | 边界降级 | Task-09, Task-14 | 待完成 |
| AC-E02 | 并行多 HITL | 边界降级 | Task-09, Task-14, Task-15 | 待完成 |
| AC-E03 | 循环 HITL 暂停恢复 | 边界降级 | Task-13, Task-15 | 待完成 |
| AC-M01 | AgenticScope 上下文保持 | 记忆上下文 | Task-02, Task-08, Task-14 | 待完成 |
| AC-M02 | Agent 消息列表保持 | 记忆上下文 | Task-02, Task-08, Task-14 | 待完成 |
| AC-H01 | 用户终止等待中工作流 | 人机协作 | Task-09, Task-12, Task-15 | 待完成 |
| AC-H02 | 恢复后 Agent 失败 | 人机协作 | Task-08, Task-14 | 待完成 |

## 4. 验证计划 (Verification Plan)

### 4.1 确定性组件验证（TDD）

| 任务 | RED 阶段 | GREEN 阶段 | REFACTOR 阶段 |
| :--- | :--- | :--- | :--- |
| Task-01 | 注解/异常测试编写完 -> 运行 -> 全部失败 | 实现注解/异常 -> 运行 -> 全部通过 | 无需重构 |
| Task-02 | 快照/扩展测试 -> 失败 | 实现 -> 通过 | 无需重构 |
| Task-03 | 状态/方法测试 -> 失败 | 实现 -> 通过 | 无需重构 |
| Task-04 | HITLReActStream 工作流构造器测试 -> 失败 | 实现新构造器 -> 通过 | 确保原构造器零回归 |
| Task-05 | @HumanCheckpoint 检测测试 -> 失败 | 实现反射检测 -> 通过 | 确保无注解零回归 |
| Task-06 | HITL 拦截测试 -> 失败 | 实现 hitlEnabled 分流 -> 通过 | 确保非 HITL 零回归 |
| Task-07 | handleHITLPaused 测试 -> 失败 | 实现 -> 通过 | 确保 handlePaused 零回归 |
| Task-08 | hitlReply 测试 -> 失败 | 实现恢复流程 -> 通过 | 确保两种模式分流正确 |
| Task-09 | 并行/超时/保护测试 -> 失败 | 实现 -> 通过 | 确保线程安全 |
| Task-10 | 端点测试 -> 失败 | 实现端点 -> 通过 | 无需重构 |

### 4.2 阶段验证检查点

| 阶段 | 验证动作 | 关联任务 | 通过标准 |
| :--- | :--- | :--- | :--- |
| 阶段一完成后 | 数据结构编译 + 单元测试 | Task-01, 02, 03 | 全部通过 |
| 阶段二完成后 | HITLReActStream 新构造器测试 + 原构造器回归 | Task-04 | 新测试通过 + 原测试零回归 |
| 阶段三完成后 | AgentExecutor 拦截 + 非拦截回归 | Task-05, 06 | 拦截测试通过 + TokenStream 零回归 |
| 阶段四完成后 | Service HITL 恢复 + PAUSED 恢复回归 | Task-07, 08, 09 | HITL 恢复通过 + PAUSED 恢复零回归 |
| 阶段五完成后 | hitl-reply 端点集成 | Task-10 | API 可调用，SSE 正常 |
| 阶段六完成后 | 前端端到端 HITL 交互 | Task-11, 12 | 等待 UI 显示 + 回复发送 + 恢复执行 |
| 阶段七完成后 | 模板检查点端到端 | Task-13 | 预置模板检查点触发 + 确认/拒绝 |
| 阶段八完成后 | 全量 AC 行为测试 | Task-14, 15 | 16 条 AC 全覆盖 |

### 4.3 验收标准逐项验证

| AC | 验证方式 | 关联任务 | 状态 |
| :--- | :--- | :--- | :--- |
| AC-N01 | 集成测试 + 人工验证 | Task-04, 06, 07, 08, 12, 15 | 待验证 |
| AC-N02 | 集成测试 + 人工验证 | Task-01, 05, 07, 13, 15 | 待验证 |
| AC-N03 | 集成测试 + 人工验证 | Task-08, 10, 12, 15 | 待验证 |
| AC-N04 | 集成测试 + 人工验证 | Task-05, 13, 15 | 待验证 |
| AC-T01 | 单元测试 | Task-02, 04, 06, 14 | 待验证 |
| AC-T02 | 单元测试 | Task-01, 05, 14 | 待验证 |
| AC-S01 | 集成测试 + 人工验证 | Task-05, 08, 12, 15 | 待验证 |
| AC-S02 | 单元测试 | Task-06, 14 | 待验证 |
| AC-S03 | 单元测试 | Task-07, 09, 14 | 待验证 |
| AC-E01 | 单元测试 | Task-09, 14 | 待验证 |
| AC-E02 | 单元测试 + 集成测试 | Task-09, 14, 15 | 待验证 |
| AC-E03 | 集成测试 + 人工验证 | Task-13, 15 | 待验证 |
| AC-M01 | 单元测试 | Task-02, 08, 14 | 待验证 |
| AC-M02 | 单元测试 | Task-02, 08, 14 | 待验证 |
| AC-H01 | 集成测试 + 人工验证 | Task-09, 12, 15 | 待验证 |
| AC-H02 | 单元测试 | Task-08, 14 | 待验证 |

### 4.4 上线前检查

- [ ] 全量 TDD 单元测试通过（10 个确定性组件）
- [ ] 集成测试 8 个 AC 场景端到端通过
- [ ] 现有工作流执行零回归（无 hitlEnabled 时行为不变）
- [ ] 现有单 Agent HITL 零回归（HITLReActStream 原构造器不变）
- [ ] 前端现有功能零回归（新事件可选回调 ?. 兼容）
- [ ] 预置模板检查点可演示
- [ ] 回滚方案验证（hitlEnabled 默认 false）

## 5. 风险与注意事项 (Risks & Notes)

*   **HITLReActStream 适配风险**（Task-04/06）⚠️：需新增构造器接受外部构建的 messages 列表，消息列表构建逻辑需正确（系统提示词 + 输入 + 工具 JSON）。缓解：保留原构造器，新增重载，零回归
*   **HITL 恢复流程风险**（Task-08）⚠️：askUser 恢复需正确注入用户回复为 ToolExecutionResultMessage，checkpoint 恢复需正确分流（确认执行/拒绝终止）。策略重放时暂停步的继续逻辑需新增 `hitl:{iteration}:{agentName}` 恢复键。缓解：TDD 逐场景验证
*   **并行 HITL 线程安全风险**（Task-09）⚠️：多线程并发请求暂停需 `synchronized(executionId)` 保证原子性。缓解：单元测试覆盖并发场景
*   **前端复杂度风险**（Task-12）：等待 banner 需复用 AskUserCard/ConfirmCard 组件，事件处理链需正确。缓解：组件复用减少新代码
*   **零回归保障**：所有修改必须确保现有功能不受影响。关键回归点：HITLReActStream 原构造器、AgentExecutor 非 HITL 路径、WorkflowExecutionService PAUSED 流程、前端现有事件处理
*   **时间风险**：如工时超出预期，Task-13（模板示例）和 Task-15（集成测试）可延后，核心能力（Task-01~10）优先完成
