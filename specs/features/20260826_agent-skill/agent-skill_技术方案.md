# AI Agent 技术设计文档: Agent Skill 能力域 (agent-skill)

| 字段 | 内容 |
|------|------|
| 版本 | v2.0 |
| 作者 | ai-agent-tech-design（技术设计技能） |
| 日期 | 2026-08-26 |
| 变更记录 | v1.0 \| 2026-08-26 \| 初版：基于 agent-skill.md 需求说明书（26 条 AC）的技术方案设计 \| 技术设计流程<br>v1.1 \| 2026-08-26 \| 1.6 文件清单与模块表补充 skill-tool 模块（SkillLoadTool.java，任务规划前置回填）\| 技术设计流程<br>v2.0 \| 2026-08-27 \| CR-001：/skill 指令化指定、自带脚本工具（动态注册+脚本护栏）替代绑定系统工具、标准目录结构+迁移、管理页取消 bug（修订决策 2/3、新增决策 8/9，见文末变更日志）\| feature-evolution |

## 0. 设计概要 (Design Summary)

*   **Agent 描述**：为单 Agent 对话链路注入"可插拔领域专家能力"——Skill 以元数据目录常驻系统提示词，LLM 匹配后经 loadSkill 工具按需加载完整指令与资源（渐进式披露），激活后指令注入提示词、自带脚本工具动态注册进当轮工具集（skill_{id}_{script}）。
*   **Agent 类型**：混合型（横切现有单 Agent 对话链路的能力增强层，非新增独立 Agent）
*   **自主性级别**：分层（Skill 激活 L3 自主可回滚；自带脚本执行 L3 自主可回滚受脚本护栏管控）——与需求文档 3.3 一致
*   **影响范围**：新增 Maven 模块 `agent-demo-skill`；修改 `agent-demo-agent`（HITLReActStream/UnifiedChatStream/TaskBreakdownStream/SessionToolResolver/SimpleAgent）、`agent-demo-web`（ChatRequest/AgentController/SkillController）、`agent-demo-frontend`（选择器/管理页/SSE 事件//skill 指令/可视化区块）
*   **技术难点**：
    1. **渐进式披露的上下文工程**：目录段（元数据）与激活段（指令全文）的分段注入，及激活段的分层信任标注（AC-T01/AC-S02）
    2. **脚本工具热刷新**：loadSkill 拦截后当轮迭代内动态注册脚本工具并刷新 toolsJson，使脚本工具"激活即用"（AC-T02/AC-T05）
    3. **激活状态与消息窗口解耦**：激活态存于会话级管理器而非对话记忆，跨轮持续且不受 FIFO 淘汰影响（AC-M01）
    4. **脚本安全护栏**：语言白名单（shell/python3）/参数校验/执行超时/危险命令拦截/输出截断，脚本执行不进入系统权限模型（AC-T03/AC-S06）
    5. **标准目录结构与迁移**：data/skills/{id}/SKILL.md + scripts/ + reference/ 持久化，启动自动迁移旧版单文件 JSON（AC-N08）
*   **依赖关系**：复用现有基础设施——ToolRegistry 动态工具注册（知识库 ByteBuddy 先例）、ToolExecutor 错误即观察值模式、askUser/tool_confirm HITL 机制、SSE 事件协议、data/*.json 持久化范式。脚本执行用 JDK ProcessBuilder（进程超时/输出捕获），无新第三方依赖。

## 1. Agent 架构概览 (Agent Architecture)

### 1.1 编排模式

*   **模式**：单 Agent（现有 SimpleAgent/UnifiedChatStream 编排零变更，Skill 作为横切增强层嵌入）
*   **选择理由**：需求文档明确 Skill 横切"单 Agent 对话链路"而非新增 Agent；现有统一对话模式（直答+拆解双路径）已收敛于 UnifiedChatStream，Skill 在系统提示词组装点与工具解析管道两个切面注入，无需改变编排拓扑。
*   **工作流/多 Agent 扩展点（需求 8.2 强制预留）**：本设计的三个核心组件均为 Agent 无关的领域组件——`SkillPromptComposer`（纯函数式段组装，输入 sessionId 输出文本段）、`SkillSessionManager`（会话激活态）、`SessionToolResolver 技能脚本工具合并`（工具解析管道扩展）。未来工作流接入时：编排模板为 Agent 节点静态声明 skillIds，节点执行前调用 `SkillSessionManager.applyManualSelection` 预激活 + 提示词组装点复用 Composer，无需改动 skill 模块。

### 1.2 推理框架

*   **主要框架**：ReAct（沿用现有显式 ReAct 循环，HITLReActStream）
*   **Skill 匹配机制**：**纯 LLM 驱动**——目录段（启用技能的名称+描述）常驻系统提示词，LLM 在 ReAct 推理中自主决定是否调用 `loadSkill` 工具。不引入向量匹配/分类器等前置路由（决策 D5，见第 10 节）。
*   **歧义与上限处理**：Prompt 层行为规则引导（目录段含消歧指引：多候选置信度相近时先 askUser 追问、上限时引导用户选择——具体文本由 agent-prompt-designer 落地），叠加代码层硬约束（激活上限校验，AC-S05）。
*   **最大执行步骤**：沿用 `agent.max-iterations` / `thinkingMaxIterations`，loadSkill 计入工具调用轮次，不新增限制。

### 1.3 系统集成架构

*   **部署形态**：服务化（嵌入现有 Spring Boot 单体，新增 Maven 模块）
*   **接入方式**：REST 管理 API（`/api/skill/*`）+ SSE 事件（`skill_activated`）+ 系统提示词注入（SkillPromptComposer）

```mermaid
graph TB
    subgraph 前端
        FE[ChatWindow] --> SEL[SkillSelector 会话级]
        FE --> MGMT[SkillManagementPage 管理页]
    end
    subgraph Web层
        CTL[AgentController] -->|skills/excludedSkills| SSM[SkillSessionManager]
        SKC[SkillController 管理 API] --> STORE[SkillStore]
        CTL -->|skill_activated 事件| FE
    end
    subgraph Agent层
        UCS[UnifiedChatStream] --> HRS[HITLReActStream]
        UCS --> TBS[TaskBreakdownStream] --> HRS
        SA[SimpleAgent 同步路径]
    end
    subgraph Skill域
        SSM --> ACT[activate/排除/上限]
        SPC[SkillPromptComposer] -->|目录段+激活段| UCS
        SPC --> SA
        STR[SessionToolResolver] -->|脚本工具合并| HRS
        REG[SkillScriptToolRegistrar] -->|动态注册 skill_{id}_{script}| TR[(ToolRegistry)]
        EXEC[SkillScriptExecutor] -->|护栏执行| REG
        STORE -->|校验| VAL[SkillContentValidator]
        STORE -->|播种| PRE[预置技能 classpath]
    end
    HRS -->|loadSkill 拦截| SSM
    SSM --> STORE
    SSM --> REG
    STORE --> FS[(data/skills/{id}/SKILL.md + scripts/ + reference/)]
```

### 1.4 Agent 生命周期

*   **会话初始化**：ChatRequest 携带 `skills`（null=自动/[]=重置/非空=手动指定）与 `excludedSkills`（null=不变/非空=设置排除集），Controller 在分发前调用 `SkillSessionManager.applyManualSelection(sessionId, skills, excludedSkills)` 完成状态变更（手动指定立即激活，source=manual，Controller 直接下发 `skill_activated` SSE 事件）。**CR-001**：`/skill 技能名` 前缀指令由前端 ChatWindow 解析为会话级 skills 状态（消息剥离前缀后作为正常消息发送，skills 字段随请求传递），与选择器指定同语义（AC-N07）。**CR-002**：展示调整——用户消息气泡保留原始输入（含 `/skill 技能名前缀`，所见即所得），发送给 LLM 仍剥离前缀；不再插入 AI"已加载技能"提示消息、不再以可视化区块展示。
*   **执行循环**：ReAct 每轮 `model.stream(messages, toolsJson, handler)`；LLM 调用 loadSkill 时拦截处理（见 3.2），不暂停循环（激活非暂停点，区别于 askUser/tool_confirm）。
*   **会话终止**：SkillSessionManager 会话状态随会话超时清理（@Scheduled 对齐 SessionManager 30 分钟超时，HumanInteractionManager 同构范式）；Skill 定义持久化于 data/skills/，跨会话存续。
*   **超时控制**：沿用现有 SSE(0L) 永不超时 + emitter 生命周期回调取消编排。

### 1.5 模型能力要求

| 能力维度 | 要求 | 说明 |
|---------|------|------|
| 上下文窗口 | ≥ 16K tokens（沿用统一对话模式基线） | 估算：hitl 场景提示词 ~2K + 技能目录（N 个技能 × ~100）+ 激活段（每技能指令+资源 1~2K）+ 20 条消息窗口 + 工具 Schema ~1K |
| 工具调用/Function Calling | 是（必须） | loadSkill 即工具，渐进式披露依赖原生函数调用 |
| 结构化输出/JSON Mode | 否（沿用现状） | 对话文本 + SSE 事件流，无新增结构化输出 |
| 多语言能力 | 是（中文为主） | 沿用现状 |
| 推理能力 | 基础+ | 技能匹配判断与消歧需中等推理能力，与现有统一模式判断（TaskPlanJudge）同级 |

### 1.6 代码结构与领域模块设计

**领域模块划分**：

| 模块名 | 职责（一句话） | 不负责（边界） | 复用/新建 | 包含文件 |
| :--- | :--- | :--- | :--- | :--- |
| skill-config | 技能域配置（开关/上限/存储路径） | 不负责 Agent 配置 | 新建 | `SkillProperties.java` |
| skill-entity | 技能定义实体（元数据+指令+资源+绑定工具） | 不负责会话状态 | 新建 | `SkillDefinition.java` |
| skill-store | 技能 CRUD + JSON 持久化 + 预置播种 | 不负责内容校验（委托 Validator） | 新建 | `SkillStore.java` |
| skill-security | 创建/编辑内容安全校验 | 不负责运行期护栏 | 新建 | `SkillContentValidator.java` |
| skill-session | 会话激活态（激活/排除/手动指定/上限） | 不负责提示词组装 | 新建 | `SkillSessionManager.java` |
| skill-prompt | 目录段+激活段提示词组装 | 不负责场景模板（PromptTemplateLoader 职责） | 新建 | `SkillPromptComposer.java` |
| skill-tool | loadSkill 工具体（激活+观察值生成，同步路径执行） | 不负责流式拦截与事件下发（HITLReActStream 职责） | 新建 | `SkillLoadTool.java` |
| skill-script | 自带脚本执行器（白名单/参数校验/超时/危险命令拦截/输出截断）+ 脚本工具动态注册 | 不负责脚本内容校验（Validator 职责） | 新建 | `SkillScriptExecutor.java` / `SkillScriptToolRegistrar.java` |
| 拦截与注入（agent 模块内修改） | loadSkill 拦截 + 系统提示词技能段注入 + 脚本工具合并 | 不负责技能数据管理 | 修改 | HITLReActStream / UnifiedChatStream / TaskBreakdownStream / SessionToolResolver / SimpleAgent |
| 管理 API（web 模块内新增） | 技能 CRUD/启停 REST 接口 | 不负责对话链路 | 新建 | SkillController + 3 个 DTO |
| 前端技能 UI | 会话级选择器 + 管理页 + 激活徽标 | 不负责对话渲染主体 | 新建 | api/skill.ts / stores/skill.ts / SkillSelector.vue / SkillManagementPage.vue |

**目录归属与文件清单**：

*   **目录归属**：新建 `agent-demo-skill` Maven 模块（平台惯例：能力域独立模块，与 rag/mcp/splitter 同构），包名 `com.agentdemo.skill.{config,entity,store,security,session,prompt}`。依赖方向：`agent-demo-skill -> common, tools`（rag 模块先例：需 ToolRegistry 解析绑定工具与权限过滤）。
*   **文件清单**（任务规划"涉及文件"的唯一来源）：

    | 文件路径 | 操作 | 用途 |
    | :--- | :--- | :--- |
    | `agent-demo-skill/pom.xml` | 新增 | 模块定义（依赖 common + tools） |
    | `agent-demo-skill/.../skill/config/SkillProperties.java` | 新增 | skill.enabled/max-active-skills=3/storage-dir 配置绑定 |
    | `agent-demo-skill/.../skill/entity/SkillDefinition.java` | 新增 | 技能实体：id/name/description/instruction/resources[]/scripts[]/enabled/source（CR-001：boundToolIds 移除，新增 SkillScript 脚本声明） |
    | `agent-demo-skill/.../skill/store/SkillStore.java` | 新增 | CRUD + 标准目录结构 data/skills/{id}/SKILL.md+scripts/+reference/ 读写 + 旧 JSON 自动迁移 + ApplicationRunner 预置播种 |
    | `agent-demo-skill/.../skill/security/SkillContentValidator.java` | 新增 | 恶意指令模式拦截（阻断）+ 密钥模式警告 + 脚本内容/参数 schema 校验（CR-001） |
    | `agent-demo-skill/.../skill/script/SkillScriptExecutor.java` | 新增 | 脚本执行器：语言白名单 shell/python3、参数校验、ProcessBuilder 执行、超时、危险命令拦截、输出截断（CR-001） |
    | `agent-demo-skill/.../skill/script/SkillScriptToolRegistrar.java` | 新增 | 激活时动态注册 skill_{id}_{script} 脚本工具（知识库动态工具先例）、可回滚注销（CR-001） |
    | `agent-demo-skill/.../skill/session/SkillSessionManager.java` | 新增 | 会话激活态：activate/applyManualSelection/exclude/上限校验/@Scheduled 清理（+激活/退出联动脚本工具注册注销，CR-001） |
    | `agent-demo-skill/.../skill/prompt/SkillPromptComposer.java` | 新增 | composeCatalogSegment + composeActivatedSegment（含信任层标注） |
    | `agent-demo-skill/.../skill/tool/SkillLoadTool.java` | 新增 | loadSkill @Tool 具体（激活+观察值生成），经 ToolRegistry.register 注册 |
    | `agent-demo-skill/src/main/resources/skills/{id}/SKILL.md+scripts/+reference/` | 新增 | 3 个预置技能（标准目录结构；data-query-assistant 改自带脚本型，CR-001） |
    | `agent-demo-agent/.../single/HITLReActStream.java` | 修改 | loadSkill 工具名拦截（askUser 同构）：激活+事件回调+脚本工具热刷新+观察值回填，不暂停 |
    | `agent-demo-agent/.../core/UnifiedChatStream.java` | 修改 | buildHitlMessages/createBreakdownStream 技能段注入；onSkillActivated 回调转发；HITLReActStream 构造透传新依赖 |
    | `agent-demo-agent/.../core/TaskBreakdownStream.java` | 修改 | 子任务系统提示词技能段注入；子任务 HITLReActStream 回调转发 |
    | `agent-demo-agent/.../single/SessionToolResolver.java` | 修改 | resolveSessionTools 合并激活技能自带脚本工具（经 ToolRegistry 动态注册，CR-001） |
    | `agent-demo-agent/.../single/SimpleAgent.java` | 修改 | systemMessageProvider 追加技能段（同步路径） |
    | `agent-demo-agent/pom.xml` | 修改 | + agent-demo-skill 依赖 |
    | `agent-demo-web/.../dto/ChatRequest.java` | 修改 | +skills/+excludedSkills 字段（反序列化兼容：旧请求体无字段可解析） |
    | `agent-demo-web/.../controller/AgentController.java` | 修改 | applyManualSelection 前置处理 + skill_activated SSE 事件下发 |
    | `agent-demo-web/.../controller/SkillController.java` | 修改 | GET /api/skill/list、POST /api/skill、PUT/DELETE /api/skill/{id}、PUT /api/skill/{id}/enabled（CR-001：字段改 scripts[]，移除绑定工具校验） |
    | `agent-demo-web/.../dto/SkillResponse.java` 等 3 个 DTO | 修改 | scripts[] 字段（CR-001：boundToolIds → scripts） |
    | `agent-demo-web/pom.xml` | 修改 | + agent-demo-skill 依赖 |
    | `agent-demo-tools/.../permission/ToolPermissionService.java` | 修改 | builtin:loadSkill 豁免恒 ALLOW（askUser 同构，防确认死锁；决策 6 不变） |
    | `agent-demo-bootstrap/.../application.yml` | 修改 | skill 配置段（storage-dir 不变 + 脚本护栏参数：白名单语言/超时上限）+ default-tools 增加 builtin:loadSkill |
    | 根 `pom.xml` | 修改 | + agent-demo-skill 模块声明 |
    | `agent-demo-frontend/src/api/skill.ts` | 修改 | 管理 REST 封装（脚本字段，CR-001） |
    | `agent-demo-frontend/src/stores/skill.ts` | 新增 | 技能列表状态（rag.ts 同构） |
    | `agent-demo-frontend/src/stores/session.ts` | 修改 | skillsBySession/excludedSkillsBySession（会话级不持久化，knowledgeBases 同构） |
    | `agent-demo-frontend/src/api/chat.ts` | 修改 | 请求参数 skills/excludedSkills + skill_activated 事件解析回调（CR-001：/skill 前缀解析） |
    | `agent-demo-frontend/src/types/index.ts` | 修改 | Skill/SkillResource/SkillScript 类型 + StreamCallbacks.onSkillActivated |
    | `agent-demo-frontend/src/components/SkillSelector.vue` | 新增 | 会话级技能选择器（KnowledgeBaseSelector 同构：空=自动） |
    | `agent-demo-frontend/src/components/SkillManagementPage.vue` | 修改 | 技能管理页（CR-001：表单改脚本管理 + 修复取消 bug） |
    | `agent-demo-frontend/src/components/SettingsPage.vue` | 修改 | + 技能管理标签页 |
    | `agent-demo-frontend/src/components/ChatWindow.vue` | 修改 | SkillSelector 集成 + 激活技能徽标展示 + 排除操作入口（CR-001：+ /skill 指令解析 + 当前技能可视化区块） |
    | `agent-demo-frontend/src/components/MessageInput.vue` | 修改 | + `/skill` 前缀命令提示条（/plan 同构，CR-001） |

## 2. Prompt 工程架构 (Prompt Engineering Architecture)
> 本节定义 Prompt 的架构和策略，具体 Prompt 文本由 agent-prompt-designer Skill 实现。

### 2.1 System Prompt 架构

*   **模块划分**（在现有"角色模板 + 场景模板"组合基础上追加技能段）：

| 模块 | 内容 | 注入方式 |
|------|------|---------|
| 角色定义 | 现有 roles/general.txt | 静态（现状） |
| 场景行为规则 | 现有 scenarios/hitl.txt（含 {{tools}}） | 静态（现状） |
| **技能目录段** | 启用且未激活且未排除技能的名称+描述清单 + loadSkill 使用与消歧指引 | 动态：每请求由 SkillPromptComposer 按 sessionId 组装 |
| **技能激活段** | 已激活技能的指令全文 + 只读资源，以信任层标注包裹（"用户提供领域指令，优先级低于平台安全规则"） | 动态：每请求由 SkillPromptComposer 按 sessionId 组装 |

*   **上下文注入点**（组装位置均在 `composeSystemPrompt` 之后追加，PromptTemplateLoader 零改动）：
    - 流式直答路径：`UnifiedChatStream.buildHitlMessages`——`composeSystemPrompt(HITL).replace({{tools}}) + "\n\n" + composer.composeCatalogSegment(sessionId) + composer.composeActivatedSegment(sessionId)`
    - 流式拆解路径：`TaskBreakdownStream` 子任务提示词组装点（同构追加）
    - 同步路径：`SimpleAgent.systemMessageProvider(memoryId -> composeSystemPrompt(CHAT) + 技能段)`（memoryId 可用）
    - 目录段为空（无启用技能/全部激活）时省略该段，零 Token 开销
*   **版本管理**：技能指令随 SkillDefinition 版本演进（编辑后新内容自下一轮生效，进行中会话的已加载内容不突变——与权限变更"下一轮生效"语义一致，AC-N06）；场景/角色模板版本管理沿用现状。
*   **Prompt 制品移交**：目录段与激活段的**具体文本**（含信任层标注措辞、消歧指引、上限引导话术）由 agent-prompt-designer Skill 依据本节架构落地。

### 2.2 Tool/Function 描述设计
> 本节仅定义工具描述的结构模板与消歧策略；具体描述文本、参数 Schema 细节由 tool-design Skill 落地。

| 工具名称 | when-to-use | when-not-to-use | 参数约束要点 | 返回格式要点 | 对应需求文档工具 |
|---------|-------------|-----------------|------------|---------|----------------|
| loadSkill | 用户请求与目录中某技能描述匹配，需要该领域能力 | 技能已激活/已被用户排除/无匹配/已达并发上限 | skillName（或 skillId）：取值须来自目录段中列出的技能 | 激活结果观察值：成功=技能指令要点+资源摘要；失败/上限/排除=明确状态说明 | loadSkill（加载 Skill） |

*   **工具消歧策略**：loadSkill 与知识库检索工具（kb_*）消歧——loadSkill 是"获得行为能力"，kb_* 是"查询知识内容"；与 askUser 消歧——歧义时先 askUser 确认再 loadSkill。消歧规则写入目录段 Prompt（agent-prompt-designer）+ loadSkill 工具描述 when-not-to-use（tool-design）。
*   **工具契约移交**：loadSkill 的完整描述文本、参数 Schema（类型/枚举约束/错误恢复消息——如"技能不存在时返回可用技能列表助 LLM 自纠"，对齐 ToolExecutor.listAvailableToolNames 范式）由 tool-design Skill 落地。
*   **非 Agent 工具说明**：Skill 管理 REST API（/api/skill/*）不注册为 Agent 工具（对话通道不可达，AC-E03）；Skill 元数据目录不是工具而是提示词常驻段（需求 4.1 表格区分）。

### 2.3 输出格式契约

*   **结构化输出方案**：沿用现状（对话文本 + SSE 事件流），无新增 LLM 结构化输出要求。
*   **新增 SSE 事件契约**：

    | 事件名 | 数据 | 触发时机 |
    |--------|------|---------|
    | `skill_activated` | JSON `{skillId, skillName, source: "auto"\|"manual", boundToolIds: []}` | LLM 调用 loadSkill 拦截激活成功后（auto）；Controller 处理手动指定后立即下发（manual） |

*   **校验规则**：skill_activated 事件载荷由后端代码构造（非 LLM 输出），字段完整性由构造点保证；前端按 types/index.ts 类型断言解析。
*   **解析失败处理**：前端未知事件类型静默忽略（chat.ts 既有容错策略沿用），不影响 token 流渲染。

### 2.4 Few-shot 示例策略

*   **不引入独立 Few-shot 模块**。消歧/上限等行为规则以目录段行为规则表达（成本敏感：目录段每轮常驻，示例会线性放大 Token 开销）。若评估阶段发现消歧命中率不达标，由 agent-prompt-designer 在目录段内追加 1 个边界反例（如"用户输入同时近似两个技能 -> 先追问"），评估数据驱动决策。

## 3. 工具集成设计 (Tool Integration)

### 3.1 工具适配层

| 现有 API/Service | 包装为 Tool 名 | 参数转换逻辑 | 返回值转换逻辑 | 副作用 |
|-----------------|---------------|-------------|---------------|--------|
| SkillStore + SkillSessionManager | `loadSkill`（builtin:loadSkill，注册进 default-tools） | LLM 输出 skillName -> SkillStore 查找（名称精确匹配，找不到返回候选列表助自纠） | 激活成功：指令要点+资源+脚本清单摘要作为 Observation 回填；激活态变更存 SkillSessionManager | 无外部副作用（会话内状态变更） |
| Skill 自带脚本（SkillScriptToolRegistrar + SkillScriptExecutor） | `skill_{id}_{script}`（激活时动态注册进 ToolRegistry） | 脚本声明参数 schema -> 工具参数 Schema；Agent 传参 -> 脚本护栏参数校验 | 脚本标准输出/错误（截断 4K）作为观察值；护栏拦截（超时/危险命令）返回明确状态 | 执行脚本（受护栏约束，AC-T03/S06） |

*   **注册方式**：`SkillLoadTool` 以 @Component 置于 skill 模块，经 `ToolRegistry.register(tool, "builtin")` 显式注册（KnowledgeBaseToolRegistrar 动态注册先例），保证组件扫描与注册时机确定性。
*   **权限豁免**：`ToolPermissionService` 增加 `LOAD_SKILL_TOOL_ID = "builtin:loadSkill"` 特判恒 ALLOW（askUser 豁免同构，防止激活动作自身触发确认死锁）；管理页修改其权限返回 400（askUser 同构）。

### 3.2 工具执行编排——loadSkill 拦截（核心机制）

**流式路径**（直答+拆解子任务共用，单一拦截点覆盖双路径——HITLReActStream 是两路径的共同执行器）：

```mermaid
sequenceDiagram
    participant LLM
    participant HRS as HITLReActStream
    participant SSM as SkillSessionManager
    participant UCS as UnifiedChatStream
    participant FE as 前端

    LLM->>HRS: tool_calls: loadSkill(skillName)
    HRS->>HRS: 拦截（工具名=loadSkill，askUser 先例）
    HRS->>SSM: activate(sessionId, skillName)
    alt 激活成功
        SSM-->>HRS: Activation(观察值文本, boundToolIds)
        HRS->>UCS: onSkillActivated(skillName, source, boundToolIds)
        UCS->>FE: SSE skill_activated
        HRS->>HRS: 重解析工具集（SessionToolResolver 合并脚本工具）→ 刷新 toolsJson
        HRS->>HRS: 回填 Observation（指令要点）→ 继续循环（不暂停）
        LLM->>HRS: 下一迭代（当轮即可调用脚本工具）
    else 上限/已排除/不存在
        SSM-->>HRS: 状态观察值（引导 askUser / 告知排除）
        HRS->>HRS: 回填 Observation → 继续循环
    end
```

*   **不暂停语义**：激活是 L3 可回滚动作，与 askUser（暂停等待）/tool_confirm（暂停等待批准）不同——拦截后立即回填观察值并继续循环。
*   **工具热刷新**：HITLReActStream 构造函数新增 `SkillSessionManager`/`SessionToolResolver`/`ToolSchemaConverter` 依赖（由 UnifiedChatStream/TaskBreakdownStream 构造时透传，两者均持有全部依赖）；拦截成功后触发 SkillScriptToolRegistrar 动态注册脚本工具（skill_{id}_{script}）→ 重调 `sessionToolResolver.resolveSessionTools(sessionId, null)`（合并逻辑见 3.4）+ `ensureAskUserTool` + `convertToJson` 刷新 `toolsJson` 字段（由 final 改为可变）。
*   **HITL 暂停一致性**：askUser/tool_confirm 暂停时 PendingInteraction 快照的 toolsJson 为激活后已刷新值（刷新先于暂停发生），恢复续跑无状态漂移。
*   **同步路径**：loadSkill 经 ToolExecutor 正常反射执行（工具体含激活逻辑），观察值回填一致；**自带脚本工具下一轮生效**（AiServices delegate 按工具指纹缓存，激活变更指纹后重建——文档化的路径不对称性，决策 D2）。

### 3.3 工具错误处理

| 工具 | 失败场景 | 重试策略 | 降级方案 | 是否转人工 |
|------|---------|---------|---------|-----------|
| loadSkill | 技能不存在 | 不重试 | 观察值返回可用技能名列表（自纠引导，listAvailableToolNames 范式） | 否 |
| loadSkill | 内容读取/格式异常 | 不重试 | 观察值返回"技能暂不可用"，LLM 以通用能力继续（AC-E01） | 否 |
| loadSkill | 达并发上限 | 不重试 | 观察值引导 LLM 调用 askUser 让用户选择（AC-S05） | 用户决策 |
| loadSkill | 技能已被用户排除 | 不重试 | 观察值告知已排除，不激活（AC-M04） | 否 |
| 脚本工具 | 超时/危险命令被拦/参数非法/报错 | 不重试（护栏拦截） | 护栏观察值（超时/被拦原因），LLM 换参数或换方案（AC-T04/S06） | 否 |

*   **统一原则**：所有失败以观察值字符串回填，不抛异常中断循环（ToolExecutor AC-012 范式）。

### 3.4 工具权限控制（CR-001 修订：绑定系统工具 → 自带脚本工具 + 脚本护栏）

*   **合并管道**：`SessionToolResolver.resolveSessionTools` 在现有"默认 ∪ 指定 + askUser 补入 + 去重"流程后追加一步——合并会话激活技能的自带脚本工具：激活时由 SkillScriptToolRegistrar 将脚本注册进 ToolRegistry，Resolver 经 `resolveToolsForStreaming(脚本工具名)` 纳入会话工具集；`dedupeToolsByMethodName` 去重兜底（AC-T02/T05）。
*   **脚本护栏（替代系统权限模型）**：自带脚本工具**不进入 allow/ask/deny 权限模型**，改由 SkillScriptExecutor 护栏统一管控——语言白名单（shell/python3）、声明式参数校验（类型/必填/取值范围）、执行超时（默认 10s）、危险命令拦截、输出截断（4K）；skill 模块不包含任何系统权限判定代码（AC-T03/S06）。
*   **系统工具权限**：全局系统工具（如 httpGet）权限判定沿用现状（ask→tool_confirm 卡片，AC-H02；deny→不可见），与 Skill 无关。
*   **只读工具**：loadSkill（allow 豁免）、getCurrentTime 等默认工具——自主调用。
*   **读写/高风险工具**：由既有三级权限模型管控，与 Skill 无关。

### 3.5 身份与权限架构

*   **身份传播机制**：沿用现状（学习示例工程无用户认证，权限主体为工具维度）；Skill 配置为全局配置，激活状态为会话维度。
*   **工具调用鉴权**：无下游服务鉴权需求（自带脚本为 Skill 内部执行，受护栏管控）。
*   **数据隔离**：激活状态按 sessionId 隔离（ConcurrentHashMap，ChatMemoryManager 同构）；Skill 定义为全局只读数据（对话通道无修改路径）。
*   **权限映射**：

| 工具/操作 | 所需权限 | 校验位置 | 越权处理 |
|----------|---------|---------|---------|
| loadSkill | 豁免恒 ALLOW | ToolPermissionService 特判 | 管理页改权限返回 400 |
| Skill 管理 API | 管理页通道 | 无对话触达路径（结构隔离） | 对话内配置请求由 Prompt 层拒绝（AC-E03） |
| Skill 自带脚本工具 | 脚本护栏（白名单/参数/超时/危险命令/输出截断） | SkillScriptExecutor（独立于系统权限模型） | 护栏拦截返回明确观察值（AC-S06） |
| 系统工具（全局） | allow/ask/deny | 加载期 + 执行期双层（现状） | 拒绝文案固定（现状） |

## 4. 记忆与上下文架构 (Memory & Context)

### 4.1 对话上下文管理

*   **管理策略**：滑动窗口（现状 20 条 FIFO）+ **激活段系统提示词旁路**。
*   **关键设计——激活状态与消息窗口解耦**（AC-M01）：激活技能的指令经系统提示词的激活段**每请求注入**，存储于 SkillSessionManager（会话级），不作为对话消息进入窗口。窗口淘汰不影响激活持续性；多轮指代（AC-M02）由消息窗口 + 激活段共同支撑。
*   **上下文构建管道**：

```mermaid
graph LR
    SysPrompt[场景模板+{{tools}}] --> Context[完整上下文]
    Catalog[技能目录段] --> Context
    Activated[技能激活段] --> Context
    Memory[消息窗口 20 条] --> Context
    User[当前用户消息] --> Context
    Context --> LLM[LLM 推理]
```

### 4.2 短期记忆

*   **存储方案**：内存（现状）。
*   **生命周期**：会话级；SkillSessionManager 激活态随会话超时清理（@Scheduled 扫描对齐 30 分钟，HumanInteractionManager 范式）。
*   **数据结构**：`ConcurrentHashMap<sessionId, SessionSkillState>`——activeSkillIds（有序，容量≤3）/ manualSkillIds / excludedSkillIds。

### 4.3 长期记忆

*   **不需要**（需求文档 5.4 明确：Skill 配置持久化为全局数据，激活态会话级消亡，无跨会话偏好记忆）。
*   **Skill 定义持久化（CR-001 修订）**：标准目录结构 `data/skills/{skillId}/SKILL.md + scripts/{name}.{ext} + reference/{name}`（Anthropic Agent Skills 风格，AC-N08）；启动时自动迁移旧版单文件 `data/skills/{skillId}.json`（幂等，已迁移目录跳过，不覆盖用户编辑）；预置技能于 SkillStore 启动时从 classpath `resources/skills/{id}/` 幂等播种（目录不存在才写入，用户编辑后不被覆盖）。

### 4.4 上下文注入管道

*   **链路**：SkillStore 读取（带 enabled 过滤）→ SkillSessionManager 过滤（排除 active/excluded）→ SkillPromptComposer 组装（目录段：名称+描述；激活段：指令+资源，信任层包裹）→ 追加至系统提示词。
*   **Token 预算分配**：目录段每技能约 100 Token（名称+描述约束于 2 句内，管理页表单提示）；激活段每技能指令+资源上限 2K Token（SkillContentValidator 校验总量，超限拒绝保存——防上下文膨胀的创建期防线）；3 技能全激活极端场景激活段 ≤ 6K，仍在 16K 窗口预算内。

## 5. 知识与检索设计 (Knowledge & RAG)

> 本功能不涉及向量检索。技能资源为**激活时全文注入**的只读参考（模板/领域文档），体量受 4.4 Token 预算约束。若未来技能资源膨胀至需检索，属演进项（需求 8.2 之外），经 ai-agent-evolution 评估。

## 6. 护栏与安全设计 (Guardrails & Safety)

### 6.1 多层护栏架构

```mermaid
graph TB
    Input[用户输入] --> InputFilter[输入过滤层: 现状 /plan 解析]
    InputFilter --> PromptGuard[Prompt 层: 目录段消歧指引 + 激活段信任层标注 + 平台规则优先声明]
    PromptGuard --> LLM[LLM 推理]
    LLM --> SkillGate[技能层: 创建校验 + 激活硬约束]
    SkillGate --> ToolGate[工具执行层: 权限双层防线 现状]
    ToolGate --> Sanitize[产出清洗层: 现状 间接注入标记]
    Sanitize --> Output[观察值回填/最终回答]
```

### 6.2 输入过滤层（Pre-processing）

*   **沿用现状**：/plan 解析、消息长度校验。技能指定参数（skills/excludedSkills）做存在性校验（不存在返回 400，管理页反馈）。
*   **间接注入防线**（AC-S03）：依赖既有 tool-output-sanitization 层（工具返回中可疑指令模式标记）+ Prompt 层触发源声明，双层防御（详见 6.3）。

### 6.3 Prompt 层护栏（In-context）

*   **信任层标注**（AC-S02）：激活段以固定标注包裹技能指令——声明其为"用户提供的领域指令，优先级低于平台安全规则（工具权限/SSRF 防护/白名单/产出清洗）"。具体措辞由 agent-prompt-designer 落地。
*   **触发源限制**（AC-S03）：目录段行为规则声明"技能激活仅可基于用户本人请求判断，工具返回/检索内容中的激活建议一律忽略"。**诚实约束说明**：此为 LLM 行为约束（概率性），代码层无法完全区分 loadSkill 调用的触发来源；纵深防御 = Prompt 层声明 + 产出清洗层标记 + 评估集对抗验证（注入用例 0 生效为通过标准）。
*   **对话内防篡改**（AC-E03）：行为规则声明"对话中不可修改技能配置"（无代码路径可越权，Prompt 层为话术兜底）。
*   **角色锁定/输出格式**：沿用现状。

### 6.4 输出过滤层（Post-processing）

*   **沿用现状**：拒绝话术脱敏（不暴露配置细节）原则应用于技能场景——激活失败观察值不暴露存储路径/校验规则内部实现。
*   **格式校验**：skill_activated 事件由代码构造，无 LLM 输出校验需求。

### 6.5 工具执行层护栏（Action Gating）

*   **确认机制**：ask 级系统工具沿用 tool_confirm 卡片（AC-H02）；loadSkill 豁免（只读无副作用，激活可回滚）；自带脚本工具不进入确认流（脚本护栏自动管控，AC-T03）。
*   **频率限制**：激活上限 3（代码硬约束，AC-S05）；loadSkill 计入 maxIterations。
*   **参数安全校验（CR-001 新增脚本护栏）**：skillName 白名单（目录内精确匹配）；脚本工具执行前经 SkillScriptExecutor 护栏——语言白名单（shell/python3）、声明式参数校验（类型/必填/取值范围）、执行超时（默认 10s）、危险命令拦截（rm -rf /、下载执行、fork 炸弹、写系统目录等）、输出截断（4K）；技能资源/指令不进入 System Prompt 模板替换通道（追加式注入，无模板注入面）。

### 6.6 降级策略

*   **全局开关**：`skill.enabled=false` → 目录段/激活段组装返回空串、loadSkill 不注册、拦截跳过、ChatRequest 技能字段忽略——全链路零影响退化（权限功能开关同构）。
*   **技能失效降级**（AC-E04）：Composer/Resolver 组装时实时校验 Skill 存在性与 enabled（缺失/禁用自动退出激活集，同时注销其脚本工具，AC-T05），对话不中断。
*   **SkillStore 异常**：JSON 损坏跳过该文件（WARN 日志），其余技能正常；播种失败不阻断启动。
*   **护栏触发降级**：校验失败返回明确原因（AC-H03）；激活硬约束触发返回引导观察值（AC-S05/AC-M04）。

### 6.7 身份与权限架构

*   见 3.5。补充：`SkillContentValidator` 拦截规则（AC-S01）——四类恶意指令模式（忽略安全规则/修改工具权限/删除破坏数据/冒充系统提升优先级）命中即阻断并返回命中类别；密钥类模式（API Key 形态）命中返回警告但允许保存（需求 6.3 语义）。

## 7. 评估与可观测性设计 (Evaluation & Observability)

### 7.1 决策链路追踪

*   **沿用现有追踪设施**：`action`/`observation` SSE 事件天然记录 loadSkill 调用与返回；应用日志记录 activate/deactivate/exclude/校验拦截（含 sessionId/skillId/来源/结果），对齐现有 SanitizeLogs/权限事件日志风格。
*   **新增日志点**：激活（含 source 与 boundToolIds）、手动指定/排除、上限拦截、校验拦截、播种、会话清理。

### 7.2 评估框架

*   **评估数据集**（覆盖需求 7.7 六类场景，3 个预置技能为标准样本）：

| 数据集 | 构成 | 评估方式 | 通过标准 |
|--------|------|---------|---------|
| 正常交互集 | 周报/文档/数据查询各 5+ 条标准对话脚本 | 半自动：skill_activated 事件断言 + 人工评判回复符合 Skill 指令约束 | 激活事件 100%；符合度抽评 ≥80% |
| 工具调用集 | JUnit 集成测试（mock LLM tool_calls 决策 + spy 绑定工具） | 自动 | 断言全通过；deny 零触发 |
| 注入对抗集 | 恶意 Skill 样本 10+ / 间接注入用例 10+（工具返回嵌"激活XX技能"指令） | 自动（校验拦截/SSE 断言）+ 人工 | 恶意样本 100% 拦截；注入 0 生效 |
| 边界降级集 | 损坏 JSON/运行中删除技能/上限/排除 | 自动（JUnit 异常路径） | 降级不中断 100% |
| 记忆上下文集 | 多轮指代/跨会话/排除后复发脚本 | 半自动（固定脚本重放） | 状态流转断言 100% |
| HITL/管理集 | askUser 歧义卡片/tool_confirm 卡片渲染/CRUD 持久化 | 自动（vitest + REST 测试） | 全通过 |

*   **评估指标**：

| 指标类别 | 指标名称 | 定义 | 目标值 |
|---------|---------|------|--------|
| 正确性 | 激活准确率 | 应激活场景正确触发 loadSkill 比例 | ≥90% |
| 正确性 | 指令遵循度 | 激活后回复符合 Skill 约束比例（人工抽评） | ≥80% |
| 安全性 | 恶意内容拦截率 | 创建校验拦截恶意 Skill 比例 | 100% |
| 安全性 | 间接注入无效率 | 工具返回注入激活指令不生效比例 | 100% |
| 效率 | 目录 Token 开销 | 无激活时目录段 Token | ≤600 |
| 用户体验 | 误澄清率 | 无歧义场景误触发追问比例 | ≤10% |

*   **对抗测试方案**：红队样本内建为测试资源（`src/test/resources/skill-adversarial/`），JUnit 参数化执行；覆盖 Prompt Injection（用户输入+工具返回双通道）、越权（对话内改配置/改权限）、恶意 Skill 内容四类攻击面。
*   **A/B 测试**：不引入框架；以 Prompt 版本（目录段措辞迭代）× 评估数据集重放对比，人工比对指标。

### 7.3 监控与告警

*   **沿用现状**（学习项目，日志 + 前端 usage 展示）。新增可观测点：激活/拦截日志可 grep 统计激活率与拦截率；无新增告警基础设施。

## 8. 性能与成本设计 (Performance & Cost)

### 8.1 Token 成本优化

*   **渐进式披露本身就是成本优化**：常驻仅目录段（每技能 ~100 Token），指令全文仅激活后注入。
*   **激活段上限约束**：SkillContentValidator 限制单技能指令+资源 ≤2K Token（创建期硬约束）。
*   **空目录零开销**：无启用技能时目录段为空串。
*   **模型分级调用**：沿用现状（会话级 modelId），不新增分级。

### 8.2 延迟优化

*   **流式输出**：沿用现状；skill_activated 事件在拦截点即时推送（激活可感知延迟 < 一次工具调用）。
*   **无新增串行步骤**：激活复用 ReAct 迭代（本就存在），热刷新仅重解析工具集（内存操作，<1ms 量级）。
*   **预计算**：SkillStore 全量内存缓存（CRUD 时刷新），组装无 IO。

### 8.3 并发控制

*   **沿用现状**：会话级状态 ConcurrentHashMap；无全局新增限流（loadSkill 频率受 maxIterations 天然约束）。
*   **会话激活态容量**：3 技能/会话（内存占用可忽略）。

### 8.4 成本估算

| 场景 | 单次 Token 消耗（增量） | 说明 |
|------|----------------------|------|
| 无技能激活对话 | +目录段 ~300-600 | N 个启用技能的元数据 |
| 单技能激活会话 | +目录段 + 激活段 ~1-2K/轮 | 指令每请求注入（跨轮持续语义） |
| 3 技能全激活 | +~6K/轮 | 极端场景，受窗口预算约束 |
| 合计评估 | 相对基线 +15%~40% | 学习项目按次计费，绝对成本可忽略；激活段上限是主要防线 |

## 9. 验收标准映射 (AC Mapping)

| AC ID | AC 描述 | AC 类型 | 对应技术实现 |
|-------|--------|--------|-------------|
| AC-N01 | Skill 自主激活（渐进式披露） | 正常交互 | 目录段（2.1）+ loadSkill 拦截（3.2）+ skill_activated 事件（2.3）+ 观察值回填 |
| AC-N02 | Skill 指令风格生效 | 正常交互 | 激活段注入系统提示词（2.1/4.4） |
| AC-N03 | 手动指定优先且不被替换（选择器 / `/skill` 指令） | 正常交互 | applyManualSelection 前置激活（1.4）+ 目录段排除 active 技能 + activate 增量语义 + ChatWindow `/skill` 前缀解析（CR-001） |
| AC-N04 | 多 Skill 并发融合 | 正常交互 | 激活集有序累积（4.2）+ 上限 3 硬约束（6.5）+ 信任层冲突声明（6.3） |
| AC-N05 | Skill 资源被使用 | 正常交互 | 激活段含资源全文（4.4）+ 创建期 Token 预算 |
| AC-N06 | Skill 管理闭环与预置初始态 | 正常交互 | SkillStore CRUD+目录结构持久化+播种（4.3）+ SkillController（1.6）+ 编辑下一轮生效（2.1） |
| AC-N07 | `/skill` 前缀指令强制指定技能 | 正常交互 | ChatWindow 前缀解析 → 会话级 skills 状态 → 用户消息保留原始输入展示（发送仍剥离前缀）（1.4，CR-001；CR-002 调整展示） |
| AC-N08 | 标准目录结构持久化与迁移 | 正常交互 | SkillStore 目录读写 + 启动自动迁移（4.3，CR-001） |
| AC-T01 | 渐进式加载 | 工具调用 | 目录段仅元数据（2.1）+ loadSkill 加载全文（3.1）+ 工具体不含指令常驻 |
| AC-T02 | 自带脚本工具注入与优先选用 | 工具调用 | SkillScriptToolRegistrar 动态注册（3.4/3.1）+ 拦截点热刷新（3.2）+ 目录段优先选用指引（2.2 消歧） |
| AC-T03 | 脚本护栏（不进入系统权限模型） | 工具调用 | SkillScriptExecutor 护栏（参数校验/白名单/超时/危险命令/输出截断，3.4）+ 测试矩阵（7.2） |
| AC-T04 | 脚本工具失败降级 | 工具调用 | 护栏拦截观察值（3.3，CR-001） |
| AC-T05 | 脚本工具激活即用与可回滚注销 | 工具调用 | 激活注册/退出注销 SkillScriptToolRegistrar（3.2/6.6，CR-001） |
| AC-S01 | 创建时内容校验拦截（含脚本） | 安全护栏 | SkillContentValidator 四类模式 + 脚本语言白名单/危险命令/参数 schema 阻断（6.7，CR-001）+ 管理页反馈（AC-H03 联动） |
| AC-S02 | 平台规则优先（分层信任） | 安全护栏 | 激活段信任层标注（6.3）+ 工具/清洗护栏零改动（结构保证）+ Prompt 层声明 |
| AC-S03 | 间接注入不触发激活 | 安全护栏 | Prompt 层触发源声明（6.3）+ 产出清洗层标记（6.2）+ 对抗集验证（7.2） |
| AC-S04 | 激活透明可回滚 | 安全护栏 | skill_activated 事件（2.3）+ 前端徽标/排除入口（1.6）+ excluded 集（6.5） |
| AC-S05 | 并发上限引导 | 安全护栏 | activate 上限校验 + 引导观察值（3.3）+ 目录段上限指引（2.1） |
| AC-S06 | 脚本执行护栏 | 安全护栏 | SkillScriptExecutor 超时/危险命令拦截/输出截断（6.5，CR-001） |
| AC-E01 | Skill 加载失败降级 | 边界降级 | activate 异常观察值（3.3）+ 不中断循环 |
| AC-E02 | 无匹配不强激活 | 边界降级 | LLM 自主不调用 loadSkill（2.2 when-not-to-use）+ 目录段行为规则 |
| AC-E03 | 对话内越权指令忽略 | 边界降级 | 无对话触达配置路径（3.5 结构隔离）+ Prompt 层话术兜底（6.3） |
| AC-E04 | 被删/禁用 Skill 平滑退出 | 边界降级 | Composer/Resolver 组装时实时有效性过滤（6.6，含脚本注销）+ @Scheduled 清理（4.2） |
| AC-M01 | 激活跨轮持续 | 记忆上下文 | 激活态独立于消息窗口（4.1）+ 每请求激活段注入 |
| AC-M02 | 上下文指代延续 | 记忆上下文 | 消息窗口（现状）+ 激活段持续（4.1） |
| AC-M03 | 激活状态会话隔离 | 记忆上下文 | ConcurrentHashMap<sessionId>（4.2） |
| AC-M04 | 排除后不复发 | 记忆上下文 | excluded 集激活校验（3.3/6.5） |
| AC-H01 | 匹配歧义追问 | 人机协作 | 目录段消歧指引（2.1）+ askUser 现状复用 + 误澄清率指标（7.2） |
| AC-H02 | ask 级系统工具确认卡片同构复用 | 人机协作 | 系统工具 ask 级走既有确认流（3.4，零新增 UI）；脚本工具不进确认流 |
| AC-H03 | 校验失败反馈引导 | 人机协作 | SkillContentValidator 分类原因返回 + 管理页表单反馈（6.7） |

**覆盖度自检**：30/30 AC 全部映射；安全类 AC（S01-S06）均映射至 ≥2 层护栏；脚本副作用（脚本工具）映射 T03/S06/T04/E04。

## 10. 技术决策说明 (Technical Decisions)

*   **决策 1：独立 Maven 模块 agent-demo-skill**
    *   选项：A 独立模块 / B 并入 agent-demo-agent / C 并入 agent-demo-tools
    *   选择：A
    *   理由：平台"能力域=模块"惯例（rag/mcp/splitter 先例）；技能域含实体/存储/校验/会话态/提示词组装五个内聚子域，塞入 agent 或 tools 模块均破坏职责边界。依赖方向 `skill -> common, tools`（rag 先例，需解析绑定工具），agent/web 依赖 skill，无循环。
*   **决策 2：脚本工具生效时机——流式同轮 / 同步下一轮（不对称）**（CR-001 修订：绑定系统工具 → 自带脚本工具，时机语义不变）
    *   选项：A 双路径均同轮 / B 流式同轮+同步下一轮 / C 双路径均下一轮
    *   选择：B
    *   理由：同轮生效是"数据查询助手"预置技能的核心演示价值（AC-T02"激活即用"）；流式路径可经拦截热刷新实现；同步路径受 AiServices delegate 工具固化机制限制，强行同轮需放弃 delegate 缓存体系（影响面过大）。不对称性文档化 + 前端演示以流式为主路径。
*   **决策 3：Skill 存储——标准目录结构 + 启动自动迁移**（CR-001 修订：单文件 JSON → 标准目录）
    *   选项：A 单文件 JSON（原） / B 标准目录 SKILL.md+scripts/+reference/ / C 数据库
    *   选择：B
    *   理由：符合用户"标准 skill 目录结构"诉求（Anthropic Agent Skills 风格），与自带脚本工具（scripts/）天然契合；项目无数据库；启动时对既有单文件 JSON 做幂等自动迁移（AC-N08），用户数据不丢失；播种幂等（目录存在即跳过）保证用户编辑不被覆盖。
*   **决策 4：loadSkill 双形态——真实工具体 + 流式拦截**
    *   选项：A 纯拦截（askUser 纯占位模式）/ B 真实工具体 + 流式拦截增强
    *   选择：B
    *   理由：同步路径走 AiServices 内部循环，无拦截点，必须真实工具体；流式路径拦截复用 askUser 先例以获得事件下发与热刷新能力。两路径共享 SkillSessionManager.activate 单一激活逻辑。
*   **决策 5：匹配机制——纯 LLM 驱动，不引入前置路由**
    *   选项：A 纯 LLM / B 向量相似度预筛 / C 分类器路由
    *   选择：A
    *   理由：渐进式披露的本原形态（Anthropic Agent Skills 同构）；技能数量级小（个位数），目录段常驻成本可忽略；歧义处理复用 askUser HITL 能力；零新基础设施（向量库/训练数据），符合学习项目的演进节奏。
*   **决策 6：loadSkill 权限豁免（恒 ALLOW）**
    *   选项：A 豁免 / B 默认 ask（激活前确认）
    *   选择：A
    *   理由：激活是只读、可回滚（L3）动作，无外部副作用；若 ask 则每次激活弹确认卡，与"自主激活"需求（AC-N01）直接矛盾；askUser 豁免先例（防确认死锁）结构同构。风险由创建期校验（S01）+ 信任层（S02）+ 用户排除权（S04）对冲。
*   **决策 7：激活状态存 SkillSessionManager 而非对话记忆**
    *   选项：A 独立会话管理器 / B 写入 ChatMemory 消息
    *   选择：A
    *   理由：消息窗口 FIFO 淘汰会导致激活丢失（AC-M01 违反）；系统提示词旁路注入与消息窗口正交；PendingInteraction 暂停恢复亦不依赖激活消息。
*   **决策 8：自带脚本工具——激活时动态注册，可回滚注销**（CR-001 新增）
    *   选项：A 激活时动态注册（可回滚） / B 仅提示词声明（无真实执行） / C 全局常驻注册
    *   选择：A
    *   理由：激活时由 SkillScriptToolRegistrar 注册 skill_{id}_{script}（知识库动态工具 ByteBuddy 先例），退出（排除/禁用/删除/超时）时注销——与激活态生命周期一致（AC-T05），避免常驻注册带来的上下文/安全负担；"仅提示词声明"无真实执行能力，不满足需求 2。
*   **决策 9：脚本安全护栏独立于系统权限模型**（CR-001 新增）
    *   选项：A 独立脚本护栏（白名单/参数/超时/危险命令/输出截断） / B 复用系统 allow/ask/deny 权限模型
    *   选择：A
    *   理由：脚本工具由 Skill 定义、经创建期校验，语义与系统工具不同；复用权限模型会引入"ask 弹确认卡"打断自主执行（与 AC-T02 自主调用冲突），且无法覆盖脚本特有风险（超时/危险命令）。护栏在创建期（Validator 静态校验）+ 执行期（Executor 动态拦截）双层落地（AC-S06）。

## 11. 风险与注意事项 (Risks & Notes)

*   **技术风险**：
    - **激活准确率不确定（概率性行为）** → 评估数据集重放（7.2）+ 目录段描述质量约束（管理表单提示）+ Prompt 迭代（agent-prompt-designer 闭环）；不达标时追加 Few-shot 边界反例（2.4）。
    - **脚本执行安全风险（CR-001 新增）**：恶意/受损脚本、命令注入（参数注入 shell）、资源耗尽 → 创建期静态校验（危险命令/语言白名单/参数 schema）+ 执行期护栏（参数校验/超时/输出截断/单进程），双层防线；残余风险经 `skill.enabled=false` 全链路关闭兜底。
    - **间接注入防御为概率性**（AC-S03 诚实约束）→ Prompt 声明 + 清洗层标记双层 + 对抗集 100% 通过为门禁；残余风险由 ask 级系统工具确认（如 httpGet）兜底。
    - **HITLReActStream 构造签名扩展**（+3 依赖 × 3 构造点）→ 接口签名变更全链路搜索适配（PROJECT_HABITS AI 规范）；TaskBreakdownStream/UnifiedChatStream 透传即可。
    - **toolsJson 可变化引入的状态一致性** → 快照语义分析已完成（3.2：刷新先于暂停，快照即最新）；集成测试覆盖"激活后暂停-恢复"链路。
    - **数据迁移风险（CR-001 新增）**：旧版单文件 JSON 迁移失败 → 迁移幂等（迁移前备份原文件 + 单文件损坏跳过 WARN + 已迁移目录跳过），不覆盖用户数据。
*   **兼容性（CR-001 修订）**：SkillRequest/SkillResponse 移除 boundToolIds 字段 → 管理 API 请求/响应变更（旧前端字段忽略，@JsonIgnoreProperties）；ChatRequest 新字段反序列化兼容（旧请求体无字段正常解析）；SSE 新增事件前端未知事件静默忽略；`skill.enabled=false` 全链路零影响退化（6.6）；旧 data/skills/*.json 启动自动迁移（AC-N08）。
*   **性能影响**：组装路径纯内存；热刷新 <1ms；脚本执行为新 IO（受超时约束）；详见第 8 节。
*   **安全风险**：恶意 Skill 内容（创建校验拦截）、脚本注入/逃逸（脚本护栏双层，决策 9）、模板注入（追加式注入无替换通道，6.5）、越权（结构隔离，6.7）。
*   **回滚方案**：
    - 功能降级开关：`skill.enabled=false` 秒级退化为无技能现状（决策 6.6）。
    - 单技能回滚：管理页禁用/删除（下一轮平滑退出，AC-E04）。
    - 数据回滚：data/skills/ 为纯新增目录，删除即回滚；旧 JSON 迁移前备份；预置可由 classpath 重新播种。
    - 代码回滚：新模块 + 既有文件增量修改，git revert 无数据迁移负担。

## 12. 数据隐私与合规 (Data Privacy & Compliance)

*   **数据存储加密**：Skill 定义为明文 JSON（与 tool-permissions.json 同级的管理数据，非用户对话数据）；学习项目无静态加密要求（现状对齐）。
*   **数据传输加密**：沿用现状（部署层 TLS 职责）。
*   **PII 识别与脱敏**：SkillContentValidator 密钥模式检测（创建/编辑时警告，需求 6.3）；Skill 内容不含用户数据（领域指令与模板）。
*   **日志保留与审计**：激活/拦截日志沿用现有日志策略（logs/ 滚动）；校验拦截日志含命中类别（审计线索）；不引入 WORM/存证（学习项目）。
*   **用户数据权利**：Skill 删除即数据删除（文件级）；激活态随会话清理，无遗留。
*   **合规要求**：无特殊行业合规约束（学习演示性质，现状对齐）。

---

## 变更日志 (Change Log)

### CR-001: Skill 指令化指定、自带脚本工具与标准目录结构 (2026-08-27)
**变更类型**: 重构（含微调 bug 修复）
**变更原因**: 用户提出 4 项变更（/skill 指令化指定+可视化、自带脚本工具替代绑定系统工具、标准目录结构、管理页取消 bug）
**影响范围**: 数据层（存储结构+迁移）/ 组件（SkillScriptExecutor/SkillScriptToolRegistrar）/ API（SkillRequest/SkillResponse 字段）/ 前端（/skill 指令+可视化区块+管理页）
**变更内容摘要**:
- [新增] 组件 skill-script：`SkillScriptExecutor`（语言白名单 shell/python3、参数校验、ProcessBuilder 执行、超时 10s、危险命令拦截、输出截断 4K）+ `SkillScriptToolRegistrar`（激活时动态注册 skill_{id}_{script}，可回滚注销）
- [修改] 决策 2：绑定系统工具 → 自带脚本工具（生效时机语义不变：流式同轮/同步下一轮）
- [修改] 决策 3：单文件 JSON → 标准目录结构 data/skills/{id}/SKILL.md+scripts/+reference/ + 启动幂等迁移
- [新增] 决策 8：自带脚本工具激活时动态注册（可回滚注销），否决"仅提示词声明/全局常驻"
- [新增] 决策 9：脚本安全护栏独立于系统权限模型（创建期静态校验 + 执行期动态拦截双层）
- [修改] 3.1/3.2/3.3/3.4/3.5/4.3/6.5/6.6/6.7/9 节：绑定工具合并管道 → 脚本工具注册与脚本护栏
- [修改] 1.6 文件清单：SkillDefinition boundToolIds→scripts[]、SkillStore 目录结构+迁移、新增 SkillScriptExecutor/SkillScriptToolRegistrar、SkillController/SkillRequest/SkillResponse 字段、ChatWindow /skill 指令+可视化区块、MessageInput /skill 提示
- [新增] AC 映射 N07/N08/T05/S06（30/30 AC 覆盖）
- [保留] loadSkill 权限豁免（决策 6）、ToolPermissionService 豁免行不变
- [修复] 管理页新建表单取消 bug（closeForm 未清 isNew 标志）

### CR-002: `/skill` 技能展示方式调整——用户消息保留原始输入 (2026-08-27)
**变更类型**: 能力扩展
**变更原因**: 用户反馈——选中技能后不希望 AI 在对话框插入"已加载技能"提示消息，而应在用户消息中展示使用了哪个技能（所见即所得保留原始输入）。
**影响范围**: 前端交互（ChatWindow 展示层）
**变更内容摘要**:
- [修改] ChatWindow sendMessage：`/skill` 指令场景用户消息气泡保存**原始输入**（含 `/skill 技能名前缀`），streamChat 仍发送剥离前缀后的 rest（skills 字段传递链路不变）
- [移除] onSkillActivated 中"已加载技能：X"assistant 提示消息插入（skill_activated 仅记录激活状态，供会话级状态与排除逻辑使用）
- [修改] AC 映射 AC-N07：可视化区块 → 用户消息保留原始输入展示
- [修改] 1.4 会话初始化：补充 CR-002 展示调整说明
- [保留] 后端 skills 传递（applyManualSelection 前置激活 + skill_activated(manual) 事件）零改动；选择器场景不额外展示
