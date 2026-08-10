# 功能需求说明书 (Feature Requirements Document)

## 1. 背景与价值 (Context & Value)

*   **背景**: 当前项目中的提示词（Prompt）存在多处质量短板：系统提示词角色定义模糊、无防幻觉约束、无输出格式规范；@Tool 工具描述过于简短导致 LLM 工具选择准确率低；任务拆解提示词的 JSON 输出约束不够强；硬编码提示词缺乏一致性规范。这些问题直接导致 Agent 对话质量不稳定——回答不准确/幻觉、工具调用误判、深度思考过程混乱、输出格式不统一。
*   **目标**: 通过系统性优化全部提示词（系统提示词、任务拆解提示词、@Tool 工具描述、硬编码提示词），建立"角色×场景"二维矩阵的提示词管理架构，将所有提示词外部化为模板文件，全面提升 Agent 对话质量。
*   **关联**: 关联 `agent-prompt-designer` 技能（提示词工程化设计），关联 BR-AGT-005（系统提示词通过 systemMessageProvider 动态提供）、BR-AGT-007（思考模式专用提示词）、BR-THINK-002（ReAct 提示词动态工具描述）。

## 2. 功能范围 (Scope)

### 2.1 本次范围（In Scope）

*   **角色×场景二维矩阵架构**: 定义 4 个角色模板（通用助手/代码助手/数据分析助手/文档助手）+ 6 个场景模板（普通对话/深度思考/ReAct/任务规划/任务执行/任务总结），运行时组合为最终提示词
*   **提示词全部外部化**: 所有提示词从配置类硬编码迁移到 `resources/prompts/` 目录下的模板文件，配置类保留默认值作为最终回退
*   **系统提示词内容优化**: 优化 3 条系统提示词（defaultSystemPrompt/thinkingSystemPrompt/thinkingReactSystemPrompt），增加角色定义、防幻觉规则、输出格式约束
*   **任务拆解提示词优化**: 优化 3 条任务拆解提示词（taskBreakdownPlanPrompt/taskExecutionSystemPrompt/taskSummaryPrompt），强化 JSON 输出约束和执行引导
*   **@Tool 工具描述全面优化**: 优化所有工具注解描述（计算器/HTTP/时间/文件读取/知识库/MCP），增加参数说明、使用场景、调用示例
*   **硬编码提示词优化**: 优化 ToolSchemaConverter 引导文本、ImageDescriptor 描述提示、McpToolExecutor 降级消息、AgentController 知识库注入提示
*   **旧模板文件清理**: 删除或归档现有 3 个旧提示词模板文件（default.txt/code-assistant.txt/general-assistant.txt）

### 2.2 不在本次范围（Out of Scope）

*   **提示词 A/B 测试机制** - 当前为学习示例项目，暂不需要多版本提示词对比测试
*   **提示词版本管理** - 暂不引入提示词版本号和回滚机制
*   **运行时动态切换角色** - 本次仅实现架构和模板，角色切换接口留给后续迭代
*   **前端角色选择 UI** - 本次不涉及前端改动，角色选择通过配置文件指定
*   **提示词效果量化监控** - 暂不引入 Token 消耗、回答质量评分等监控指标

## 3. 用户角色 (Actors)

*   **开发者**: 通过修改 `resources/prompts/` 下的模板文件调整 Agent 行为，无需改动代码
*   **学习者**: 通过运行 Demo 体验不同角色×场景组合下的 Agent 对话效果
*   **API 调用方**: 通过配置文件指定角色，获得不同专业领域的 Agent 服务

## 4. 用户故事 (User Stories)

*   **US-001**: 作为 **开发者**，我想要 **通过模板文件管理所有提示词**，以便 **无需修改代码即可调整 Agent 行为**。
    *   关联验收标准：AC-001, AC-002, AC-025, AC-030
*   **US-002**: 作为 **开发者**，我想要 **为不同角色（通用/代码/数据/文档）定义独立的提示词**，以便 **同一 Agent 在不同场景下展现不同专业能力**。
    *   关联验收标准：AC-003, AC-004, AC-005, AC-006, AC-007
*   **US-003**: 作为 **学习者**，我想要 **Agent 回答准确、不编造信息**，以便 **信任 Agent 的输出并用于学习参考**。
    *   关联验收标准：AC-008, AC-009, AC-027
*   **US-004**: 作为 **学习者**，我想要 **Agent 输出格式规范、结构清晰**，以便 **快速理解 Agent 的回答内容**。
    *   关联验收标准：AC-008, AC-028, AC-029
*   **US-005**: 作为 **开发者**，我想要 **工具描述清晰详细**，以便 **LLM 准确判断何时调用工具及如何传参**。
    *   关联验收标准：AC-014, AC-015, AC-016, AC-017, AC-018, AC-019, AC-020
*   **US-006**: 作为 **学习者**，我想要 **深度思考和任务拆解过程有条理**，以便 **理解 Agent 的推理路径和任务执行逻辑**。
    *   关联验收标准：AC-010, AC-011, AC-012, AC-013
*   **US-007**: 作为 **开发者**，我想要 **提示词模板缺失时系统优雅降级**，以便 **不会因配置问题导致 Agent 不可用**。
    *   关联验收标准：AC-024, AC-025

## 5. 详细需求与流程 (Detailed Requirements)

### 5.1 核心流程

1.  应用启动时，从 `resources/prompts/roles/` 加载 4 个角色模板，从 `resources/prompts/scenarios/` 加载 6 个场景模板
2.  用户发起对话请求，系统根据请求参数（场景模式）和配置（角色选择）确定角色和场景
3.  系统组合最终提示词 = 角色模板内容 + 场景模板内容 + 动态工具描述（如场景需要）
4.  最终提示词通过 `systemMessageProvider` 注入 Agent，Agent 执行对话/思考/任务拆解
5.  如果模板文件缺失，回退到默认角色（通用助手）或默认场景（普通对话），记录 WARNING 日志

### 5.2 交互/界面规则

*   本次不涉及前端 UI 改动，角色和场景选择通过后端配置文件（application.yml）指定
*   角色模板和场景模板文件采用纯文本格式，开发者可直接用文本编辑器修改
*   模板文件中可使用占位符（如 `{tools}`）标记动态内容注入点

### 5.3 业务规则

*   **角色×场景组合规则**: 最终提示词 = 角色模板 + "\n\n" + 场景模板 + （动态工具描述，如场景需要）
*   **防幻觉规则**: 所有最终组合的提示词必须包含防幻觉约束——不确定时如实告知、禁止编造事实/数据/引用来源
*   **输出格式规则**: 所有最终组合的提示词必须包含输出格式约束——结构化 Markdown、简洁优先（默认 3-5 句）、中文回复、技术术语可保留英文
*   **工具调用透明化规则**: 普通对话和 ReAct 场景下，Agent 调用工具后回答中应提及使用的工具及获取的信息
*   **模板回退规则**: 模板文件缺失时回退到默认模板，不抛出异常，记录 WARNING 日志
*   **配置外部化规则**: application.yml 中不再包含完整提示词文本，仅保留角色/场景选择配置
*   **旧文件清理规则**: 现有 `prompts/default.txt`、`prompts/code-assistant.txt`、`prompts/general-assistant.txt` 被新角色模板替代后删除

## 6. 验收标准 (Acceptance Criteria)

> **重要**：以下验收标准是后续技术方案、任务规划和 TDD 测试用例的直接依据。每条 AC 必须使用 Given-When-Then 格式，必须可被测试验证。

### 6.1 正常流程 (Happy Path)

- [ ] **AC-001**: 角色模板文件创建
    - Given: 项目中需要 4 个角色模板文件来定义不同专业领域的 Agent 角色
    - When: 创建角色模板文件到 `resources/prompts/roles/` 目录（general.txt / code.txt / data-analyst.txt / doc-writer.txt）
    - Then: 每个角色模板包含：角色身份定义、专业领域描述、回答风格约束、领域特定规则

- [ ] **AC-002**: 场景模板文件创建
    - Given: 项目中需要 6 个场景模板文件来定义不同对话场景下的行为约束
    - When: 创建场景模板文件到 `resources/prompts/scenarios/` 目录（chat.txt / thinking.txt / react.txt / task-plan.txt / task-execute.txt / task-summary.txt）
    - Then: 每个场景模板包含：场景行为约束、输出格式要求、护栏规则

- [ ] **AC-003**: 提示词组合机制
    - Given: 用户选择角色 A（如代码助手）和场景 B（如普通对话）进行对话
    - When: 系统构建最终系统提示词
    - Then: 最终提示词 = 角色A模板内容 + "\n\n" + 场景B模板内容 + （动态工具描述，如场景需要工具）

- [ ] **AC-004**: 通用助手角色模板内容
    - Given: 通用助手角色模板文件（general.txt）
    - When: 查看模板内容
    - Then: 包含：通用 AI 助手身份定义、覆盖问答/计算/查询/分析能力、简洁中文回复风格、不确定时如实告知规则

- [ ] **AC-005**: 代码助手角色模板内容
    - Given: 代码助手角色模板文件（code.txt）
    - When: 查看模板内容
    - Then: 包含：资深 Java 工程师身份、代码生成/审查/调试/架构设计能力、Java 编码规范要求、代码示例约束（遵循项目编码规范）

- [ ] **AC-006**: 数据分析助手角色模板内容
    - Given: 数据分析助手角色模板文件（data-analyst.txt）
    - When: 查看模板内容
    - Then: 包含：数据分析专家身份、数据解读/趋势分析/报表生成能力、数据呈现格式约束（优先使用表格/结构化描述）

- [ ] **AC-007**: 文档助手角色模板内容
    - Given: 文档助手角色模板文件（doc-writer.txt）
    - When: 查看模板内容
    - Then: 包含：技术文档撰写专家身份、API 文档/用户手册/技术方案撰写能力、文档结构化约束（标题层级/分节/示例代码块）

- [ ] **AC-008**: 普通对话场景模板内容
    - Given: 普通对话场景模板文件（chat.txt）
    - When: 查看模板内容
    - Then: 包含：工具调用引导（引导 Agent 主动使用工具）、防幻觉规则（不确定时如实告知）、输出格式约束（结构化 Markdown/简洁/中文）、工具调用透明化要求（调用工具后回答中提及）

- [ ] **AC-009**: 深度思考场景模板内容
    - Given: 深度思考场景模板文件（thinking.txt）
    - When: 查看模板内容
    - Then: 包含：深度推理引导（分步思考/逻辑推演）、不提及工具调用能力（此模式不传工具）、防幻觉规则、输出格式约束

- [ ] **AC-010**: ReAct 场景模板内容
    - Given: ReAct 场景模板文件（react.txt）
    - When: 查看模板内容
    - Then: 包含：Thought/Action/Observation/Final Answer 格式引导、工具使用约束（只能使用系统提供的工具，不编造工具名）、工具失败处理规则（分析原因后决定下一步，不盲目重试）、动态工具描述占位符

- [ ] **AC-011**: 任务规划场景模板内容
    - Given: 任务规划场景模板文件（task-plan.txt）
    - When: 查看模板内容
    - Then: 包含：任务复杂度判断规则（简单/复杂判断标准）、强 JSON 输出约束（JSON 数组格式/字段定义）、子任务拆解规则（最多 10 个/可独立执行/明确步骤）、只返回 JSON 不返回其他内容

- [ ] **AC-012**: 任务执行场景模板内容
    - Given: 任务执行场景模板文件（task-execute.txt）
    - When: 查看模板内容
    - Then: 包含：子任务执行引导、ReAct 格式引导（支持工具调用）、动态工具描述占位符、执行结果摘要要求（给出子任务执行结果摘要）

- [ ] **AC-013**: 任务总结场景模板内容
    - Given: 任务总结场景模板文件（task-summary.txt）
    - When: 查看模板内容
    - Then: 包含：总结规则（概括主要发现/结论/建议）、简洁约束（不重复每个子任务的详细过程）

- [ ] **AC-014**: 计算器工具描述优化
    - Given: CalculatorTool 的 calculate 方法
    - When: 查看 @Tool 注解描述
    - Then: 描述包含：功能说明（计算数学表达式）、支持的表达式类型（加减乘除/括号/幂运算用 ^ 表示）、参数说明（expression 为数学表达式字符串）、使用示例（如 2+3、(2+3)*4、2^10）

- [ ] **AC-015**: HTTP 工具描述优化
    - Given: HttpTool 的 httpGet 和 httpPost 方法
    - When: 查看 @Tool 注解描述
    - Then: httpGet 描述含功能说明 + url 参数说明（完整 URL）+ 使用场景（获取网页或 API 内容）；httpPost 描述含功能说明 + url 参数说明 + body 参数说明（JSON 字符串请求体）+ 使用场景

- [ ] **AC-016**: 时间工具描述优化
    - Given: TimeTool 的 getCurrentTime / getCurrentTimeByZone / getCurrentDate 方法
    - When: 查看 @Tool 注解描述
    - Then: 每个方法描述含功能说明、返回格式说明（如 yyyy-MM-dd HH:mm:ss）、参数说明（getCurrentTimeByZone 的 zoneId 参数含格式和示例：Asia/Shanghai、America/New_York、UTC）

- [ ] **AC-017**: 文件读取工具描述优化
    - Given: FileReadTool 的 readFile 方法
    - When: 查看 @Tool 注解描述
    - Then: 描述含功能说明（读取文件内容）、path 参数说明（相对路径，相对于允许目录）、安全限制说明（仅支持只读/限定在白名单目录内）

- [ ] **AC-018**: 知识库工具描述优化
    - Given: KnowledgeBaseToolFactory.buildToolDescription 方法
    - When: 查看动态生成的工具描述
    - Then: 描述含知识库名称、检索功能说明、调用场景引导（当用户问题涉及该知识库内容时调用）、query 参数说明（检索问题）

- [ ] **AC-019**: MCP 工具描述优化
    - Given: McpToolFactory.buildToolDescription 方法
    - When: 查看动态生成的工具描述
    - Then: 描述含 MCP Server 名、工具原始描述、结构化参数列表（参数名/类型/必填或可选/描述）、调用示例（JSON 格式的 argsJson 示例）

- [ ] **AC-020**: 工具列表引导文本优化
    - Given: ToolSchemaConverter.convertToDescriptionText 方法
    - When: 查看生成的工具列表文本
    - Then: 含清晰引导语（说明可用工具列表）、每个工具以"方法名: 描述"格式列出、工具调用时机引导（当问题需要实时信息或计算时主动调用）

- [ ] **AC-021**: 知识库注入提示优化
    - Given: AgentController 中知识库选择注入逻辑
    - When: 用户在前端指定知识库时
    - Then: 注入到用户消息末尾的提示文本更清晰，明确引导 LLM 调用对应知识库的检索工具获取相关信息

- [ ] **AC-022**: 图片描述提示优化
    - Given: ImageDescriptor.DESCRIBE_PROMPT 常量
    - When: 查看提示内容
    - Then: 含详细描述要求（图片中的文字/图表数据/图形元素等所有可见信息）、描述目的说明（便于后续通过文字检索）、简洁准确约束

- [ ] **AC-023**: MCP 降级提示优化
    - Given: McpToolExecutor.FALLBACK_MESSAGE 常量
    - When: 查看降级提示内容
    - Then: 含工具执行成功说明、非文本内容原因说明（图片/音频等当前环境无法直接展示）、对用户的建议操作（重新描述需求或使用其他工具）

### 6.2 边界与异常 (Edge & Error Cases)

- [ ] **AC-024**: 模板文件缺失降级
    - Given: 某个角色或场景模板文件不存在（如 prompts/roles/code.txt 缺失）
    - When: 系统尝试加载该模板
    - Then: 回退到默认角色（通用助手 general.txt）或默认场景（普通对话 chat.txt）的模板，记录 WARNING 日志，不抛出异常，Agent 正常工作

- [ ] **AC-025**: 模板文件加载机制
    - Given: 所有模板文件外部化到 resources/prompts/ 目录
    - When: 应用启动
    - Then: 角色模板从 `prompts/roles/` 目录加载，场景模板从 `prompts/scenarios/` 目录加载，AgentConfig 中的硬编码默认值保留作为最终回退（当模板文件也不存在时使用）

- [ ] **AC-026**: 旧模板文件清理
    - Given: 现有 prompts/ 目录下的 default.txt、code-assistant.txt、general-assistant.txt 三个旧文件
    - When: 新的角色×场景模板架构上线
    - Then: 旧文件被新的角色模板替代（内容迁移到 prompts/roles/ 下对应文件），旧文件删除

### 6.3 业务规则验证 (Business Rules)

- [ ] **AC-027**: 防幻觉规则一致性
    - Given: 所有 4 个角色模板和 6 个场景模板
    - When: 任意角色×场景组合为最终提示词
    - Then: 每个最终组合的提示词都包含防幻觉规则——不确定时如实告知用户、禁止编造事实/数据/引用来源

- [ ] **AC-028**: 输出格式规则一致性
    - Given: 所有 4 个角色模板和 6 个场景模板
    - When: 任意角色×场景组合为最终提示词
    - Then: 每个最终组合的提示词都包含输出格式约束——结构化 Markdown（技术内容用代码块/数据用表格/步骤用列表）、简洁优先（默认 3-5 句，复杂问题可展开）、中文回复（技术术语可保留英文）

- [ ] **AC-029**: 工具调用透明化规则
    - Given: 普通对话场景和 ReAct 场景模板
    - When: Agent 调用了工具后生成回答
    - Then: 回答中应提及使用了哪个工具以及获取了什么信息（如"通过时间工具查询到当前时间为..."）

- [ ] **AC-030**: 配置外部化一致性
    - Given: application.yml 中的 agent 配置段
    - When: 提示词模板外部化完成后
    - Then: yml 中不再包含完整提示词文本（default-system-prompt/thinking-system-prompt/thinking-react-system-prompt 等字段移除或置空），仅保留角色和场景选择配置（如 agent.default-role: general）

---

### AC 覆盖度自检
- [x] 正常流程的每个关键步骤都有对应 AC（AC-001~AC-023 覆盖模板创建、内容优化、工具描述优化、硬编码提示词优化）
- [x] 第 5.3 节的每条业务规则都有对应 AC（AC-027 防幻觉/AC-028 输出格式/AC-029 工具透明化/AC-030 配置外部化/AC-024 模板回退/AC-026 旧文件清理）
- [x] 所有已识别的边界/异常情况都有对应 AC（AC-024 模板缺失/AC-025 加载机制/AC-026 旧文件清理）
- [x] 每条 AC 描述的是可观测行为，而非内部实现
