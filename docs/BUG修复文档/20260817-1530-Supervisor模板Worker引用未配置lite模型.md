# BUG 修复报告

## 基本信息
- 日期：2026-08-17 15:30
- 报告人：AI Assistant
- 模块/功能：agent-demo-app（Supervisor 预置工作流模板）
- 严重级别（低/中/高）：高
- 影响范围：仅"任务拆解-执行-汇总"（task-breakdown-supervisor）模板的 Worker 派发阶段；P1/P2 其余 4 个模板不指定 modelId（走默认模型）不受影响
 - 严重级别依据：Supervisor 模板执行 100% 阻断于第一个子任务派发；主控拆解已可正常完成（上轮修复生效）

## 问题描述
- 期望结果：主控拆解完成后，子任务依次派发给研究/分析/总结 Worker 并执行
- 实际结果：拆解阶段正常，派发第一个子任务时报错 `Agent [研究] 配置的模型不存在: doubao-seed-2.0-lite`

## 复现步骤（必须可复现）
1) "设置 → LLM 配置"中仅配置 glm-5.2（一个 chat 模型）
2) 进入"编排"页面，选择"任务拆解-执行-汇总"模板
3) 输入任务（如"调研分类算法"），点击"执行"
4) 观察：主控面板完成子任务拆解（上轮修复已生效），随后报 `Agent [研究] 配置的模型不存在: doubao-seed-2.0-lite`

## 复现环境
- 设备/系统：Windows / JDK 17 / Spring Boot 3.2.5
- 应用版本/配置：main 分支；LLM 配置（前端同步至 LlmConfigStore）仅含 glm-5.2
- 相关依赖或外部条件：无（模型查找发生在 Agent 构建阶段）

## 定位过程
- 关键线索：报错从主控（glm-5.2，上轮已改）转移到 Worker（doubao-seed-2.0-lite），确认逐 Agent 解析、报错 Agent 与模板 modelId 一一对应
- 排查路径：
  1. 全局检索 `MODEL_DOUBAO_SEED_2_LITE`：main 代码仅 `TaskBreakdownSupervisorTemplate` 3 个 Worker 引用（研究/分析/总结）
  2. 对照 P1/P2 模板：均不设置 modelId → `AgenticAgentFactory` 走默认流式模型（配置中首个 chat 模型），故任何环境可运行
  3. 确认 LLM 配置来源：前端一次性提交厂商配置 → `LlmConfigController.replaceAll` 内存替换；当前环境无 doubao-seed-2.0-lite 记录
- 根因说明：Supervisor 模板（P3 Task-16，AC-031）为体现"主控强模型/Worker 快模型"的差异化设计，Worker 硬编码引用 `doubao-seed-2.0-lite`；模板引用的模型必须在 LLM 配置中存在（上轮已支持 modelName 兜底解析），但环境中没有该模型记录，兜底也无法命中 → 派发 Worker 构建 Agent 时抛 `WORKFLOW_MODEL_NOT_FOUND`。属于模板静态引用与运行时环境配置不匹配，上一报告"遗留问题"中已预告

## 修复方案
- 修改点说明：`TaskBreakdownSupervisorTemplate` 3 个 Worker 的 `modelId` 由 `MODEL_DOUBAO_SEED_2_LITE` 改为 `MODEL_GLM_52`，与主控一致；同步更新两个测试文件
- AC-031 影响（重要说明）：AC-031"主控与 Worker 使用不同模型"的原语义是成本优化（拆解/汇总用强模型、执行用快模型），前提是环境同时配置两类模型。当前环境仅有 glm-5.2，模板降级为主控/Worker 同模型；模板结构未变，未来在 LLM 配置中新增 lite 类模型后，将 Worker 改回差异化常量即可恢复 AC-031 完整语义
- 影响评估：仅影响该模板自身；P1/P2 模板与对话链路零涉及
- 风险点：Worker 与主控同模型后成本/速度与原设计有差异（demo 场景可接受）；若后续环境配置了 lite 模型，模板不会自动切回（需手动改常量）
- 回滚方案：还原模板 3 处 `MODEL_GLM_52` 为 `MODEL_DOUBAO_SEED_2_LITE` 并同步两个测试断言

## 变更内容
- 代码/配置改动摘要：
  1. `TaskBreakdownSupervisorTemplate`：Worker（研究/分析/总结）modelId `MODEL_DOUBAO_SEED_2_LITE` → `MODEL_GLM_52`（3 处），注释说明 AC-031 降级原因
  2. `WorkflowTemplatesTest`：测试重命名 `taskBreakdownSupervisor_agents_shouldUseGlm52Model`，Worker 断言改 `MODEL_GLM_52`（RED 用例：修复前失败 `expected: <glm-5.2> but was: <doubao-seed-2.0-lite>`）
  3. `WorkflowControllerTest`：mock 模板 Worker modelId 与断言同步改 `glm-5.2`（4 处）
- 相关文件：
  - `agent-demo-app/src/main/java/com/agentdemo/app/template/TaskBreakdownSupervisorTemplate.java`（修改）
  - `agent-demo-app/src/test/java/com/agentdemo/app/template/WorkflowTemplatesTest.java`（修改）
  - `agent-demo-web/src/test/java/com/agentdemo/web/controller/WorkflowControllerTest.java`（修改）
 - 回归风险/可能受影响模块：agent-demo-app / agent-demo-web，均已回归通过

## 单元测试
- 测试文件：`WorkflowTemplatesTest`
- RED：先改断言为 glm-5.2 并运行，`taskBreakdownSupervisor_agents_shouldUseGlm52Model` 失败（`Worker 应全部使用 glm-5.2 模型: 研究 ==> expected: <glm-5.2> but was: <doubao-seed-2.0-lite>`），模板级复现运行时报错
- GREEN：修改模板后 `WorkflowTemplatesTest` 13/13 通过
- 说明：本 BUG 属"静态模板引用与运行时配置不匹配"，模板断言测试即为最有效的回归防线（模板引用的常量再被改回未配置模型时会立即失败）；运行时 modelName 兜底解析行为由上轮 `ModelFactoryTest` 4 用例守护

## 验证步骤（手动，逐步）
1) 重启后端应用（模板为启动时注册的 Spring Bean，需重启生效）
2) 进入"设置 → LLM 配置"，确认存在 modelName 为 glm-5.2 的 chat 模型
3) 进入"编排"页面，点击"任务拆解-执行-汇总"模板卡片
4) 在 task 输入框输入"调研主流大语言模型的分类算法并总结"
5) 点击"执行"
6) 逐步观察执行视图：
   - "任务拆解(主控)"面板流式输出子任务 JSON，随后子任务卡片列表出现
   - 第一张子任务卡片变为运行态（⟳）——此处为原报错点，应不再出现"配置的模型不存在"
   - 研究/分析/总结 Worker 面板依次流式输出
   - "主控汇总中…"出现，最终输出综合报告，状态 COMPLETED

## 验证结果
- 结果说明：WorkflowTemplatesTest 13/13、WorkflowControllerTest 18/18 通过；全量回归 common 13 / llm 136 / tools 27 / splitter 121 / rag 82 / agent 85 / app 196 / web 113 合计 773 全绿（agent-demo-mcp 3 个失败为已知本机环境问题：TRAE 插件 node.cmd 的 `\\?\` 非法路径 + MCP 连接拒绝，本次未触碰 mcp 模块）
- 是否通过：是（自动化）；手动步骤 1-6 待用户重启后执行

## 遗留问题/后续行动
- AC-031 差异化模型语义降级：环境配置第二个模型（如 lite/flash 类）后，可将 Worker 改回快模型常量恢复原设计
- 根本性改进建议（两轮同源 BUG）：预置模板 modelId 缺失时回退默认 chat 模型（`getFirstChatModel`）而非直接失败，或在模板详情页提示所引用模型未配置——可作为后续迭代项
- agent-demo-mcp 环境失败建议单独排查（Windows `\\?\` 路径适配）
