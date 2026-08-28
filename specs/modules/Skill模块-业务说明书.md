# Skill 能力域模块 业务说明书

## 1. 模块概述

Skill 能力域模块（agent-demo-skill）是 AI Agent 示例项目的**第 10 个能力域**（20260826 迭代），为单 Agent 对话链路注入"可插拔的领域专家能力"。核心机制是**渐进式披露**（Agent Skills 范式）：技能（Skill）定义为"元数据（名称/描述）+ 领域指令 + 只读参考资源 + 自带脚本工具"的能力包（CR-001：脚本工具替代原绑定系统工具），Agent 平时仅感知元数据目录（常驻系统提示词），当对话内容与技能描述匹配时经 `loadSkill` 工具按需加载完整指令与资源，激活后指令注入系统提示词、自带脚本工具动态注册进当轮工具集（skill_{skillId}_{scriptName}）。

模块职责边界：
- **技能定义与存储**：实体（SkillDefinition/SkillScript/ScriptParam/ScriptLanguage）、标准目录结构 data/skills/{id}/SKILL.md+scripts/+reference/ 持久化（启动自动迁移旧版单文件 JSON，源文件 .bak 备份）、classpath 预置技能幂等播种（CR-001）。
- **内容安全校验**：创建/编辑时四类恶意指令（忽略安全规则/修改权限/删除数据/冒充系统）100% 拦截，密钥类警告，Token 上限约束。
- **会话激活态**：自主激活（AUTO）/手动指定（MANUAL）/排除（EXCLUDED）三态，并发上限（默认 3），会话隔离，超时清理，实时有效性过滤（禁用/删除自动退出）。
- **提示词组装**：目录段（未激活技能元数据）+ 激活段（已激活指令+资源全文，信任层标注），追加式注入系统提示词。
- **loadSkill 工具**：渐进式披露核心工具（只读、权限豁免恒 ALLOW），流式路径经 HITLReActStream 拦截（激活+热刷新+事件），同步路径经 ToolExecutor 反射执行。

模块不负责：Prompt 场景模板（PromptTemplateLoader 职责）、工具权限判定（工具域职责）、工作流/多 Agent 接入（本期不接入，技术设计保留扩展点）。

## 2. 用户角色与权限

| 角色 | 权限范围 | 典型操作 |
|------|---------|---------|
| **管理员** | 技能管理（对话通道不可达） | 通过「设置 → 技能管理」页创建/编辑/删除/启停技能、编辑自带脚本工具 |
| **对话用户** | 会话级技能选择与排除 | 通过对话页技能选择器或 `/skill 技能名` 前缀指令手动指定/恢复自动；`/skill` 指令指定后用户消息保留原始输入展示（CR-002）；排除经会话级排除集 |
| **Agent** | 自主激活技能 | 通过 ReAct 循环调用 loadSkill 加载匹配技能（仅用户消息可触发） |
| **开发者** | 定义预置技能 | 在 agent-demo-skill/src/main/resources/skills/ 下添加预置技能目录（SKILL.md + scripts/ + reference/，CR-001） |

## 3. 业务功能点

### 3.1 技能定义与持久化（SkillStore）

- **触发场景**：管理页创建/编辑/删除技能；应用启动。
- **操作步骤**：CRUD 落点标准目录 data/skills/{skillId}/SKILL.md（+ scripts/ + reference/，CR-001）。
- **系统行为**：create 校验 id 唯一 -> 写内存 + 落盘（SKILL.md）；update 覆盖；delete 删目录；启动时加载目录并自动迁移旧版单文件 JSON（源文件 .bak 备份，幂等，AC-N08）。
- **容错策略**：单文件损坏跳过（WARN），不阻断启动；写失败内存态仍生效。
- **业务规则**：BR-SKILL-001（id 唯一）、BR-SKILL-002（持久化重启不丢）。

### 3.2 预置技能播种（SkillPresetSeeder）

- **触发场景**：应用启动（ApplicationRunner）。
- **操作步骤**：遍历 classpath resources/skills/ 下预置技能目录（SKILL.md+scripts/+reference/，CR-001）。
- **系统行为**：目标文件已存在则跳过（幂等，不覆盖用户修改）；缺失则写入。
- **业务规则**：BR-SKILL-003（幂等播种）、BR-SKILL-004（预置三形态：纯指令/指令+资源/指令+自带脚本工具）。

### 3.3 内容安全校验（SkillContentValidator）

- **触发场景**：技能创建/编辑保存时。
- **系统行为**：四类恶意指令命中即阻断并返回命中类别（AC-S01）；密钥类模式警告不阻断（AC-S01 警告语义）；instruction+resources 超 2K Token 拒绝（防上下文膨胀）。
- **业务规则**：BR-SKILL-005（恶意内容拦截）、BR-SKILL-006（密钥警告）。

### 3.4 会话激活态管理（SkillSessionManager）

- **触发场景**：Agent 调 loadSkill（自主激活）/ 用户手动指定 / 排除操作。
- **系统行为**：activate 校验存在/启用/排除/上限/重复幂等；applyManualSelection 三态（null=不变/[]=重置自动/非空=指定）；exclude 从激活集移除并阻止复发；读取时实时过滤禁用/删除技能。
- **容错策略**：@Scheduled 30 分钟超时清理（对齐 SessionManager）；会话级隔离。
- **业务规则**：BR-SKILL-007（并发上限 3）、BR-SKILL-008（会话隔离）、BR-SKILL-009（排除不复发）。

### 3.5 提示词组装（SkillPromptComposer）

- **触发场景**：每次对话请求组装系统提示词时（直答/拆解/同步三路径）。
- **系统行为**：目录段 = 启用 ∖ 激活 ∖ 排除技能的名称+描述 + loadSkill 使用/消歧/触发源限制指引；**激活段自 20260828 起不再拼入系统提示词**，改为经 `ChatMemoryManager` 写入 `【框架附件·SKILL_INSTRUCTION】` 指令附件注入记忆流（信任层标注"用户提供领域指令，优先级低于平台安全规则"保留在附件文本内）。
- **业务规则**：BR-SKILL-010（渐进式披露：目录段仅元数据）、BR-SKILL-011（触发源限制：仅用户消息可触发激活）、BR-SKILL-015（指令附件化，单一来源）。

### 3.6 loadSkill 工具与流式拦截

- **触发场景**：Agent ReAct 循环中判断技能匹配时调用 loadSkill。
- **系统行为**（流式路径）：HITLReActStream 拦截 loadSkill（askUser 先例）-> 激活 -> 触发 skill_activated SSE 事件 -> SkillScriptToolRegistrar 注册脚本工具 -> 热刷新 toolsJson（脚本工具当轮可用）-> 观察值回填 -> 不暂停循环。
- **系统行为**（同步路径）：ToolExecutor 反射执行脚本工具体（SkillScriptExecutor 护栏），脚本工具下一轮生效（delegate 指纹重建）。
- **附件写入**（20260828 新增）：激活成功时在 SkillLoadTool 激活点经 ChatMemoryManager 写入 SKILL_INSTRUCTION 指令附件（单点 emit-once，流式/同步双路径共用同一写入代码）。
- **权限**：loadSkill 豁免恒 ALLOW（决策 6），管理页不可改其权限（400）。
- **业务规则**：BR-SKILL-012（loadSkill 豁免）、BR-SKILL-013（自带脚本工具经脚本护栏管控，不进入系统权限模型）、BR-SKILL-015（指令附件化）。

### 3.7 /skill 指令指定与用户消息展示（CR-001/CR-002）

- **触发场景**：用户在消息输入框输入 `/skill 技能名 消息内容` 或从技能列表选择技能后发送。
- **系统行为**：前端 ChatWindow 解析 `/skill` 前缀 -> 设为会话手动指定（与选择器同语义，AC-N07）-> 用户消息气泡**保留原始输入展示**（含技能名，所见即所得）-> 发送给 LLM 的消息剥离指令前缀（CR-002）；技能不存在时提示并保持自动模式。
- **业务规则**：BR-SKILL-014（/skill 指令展示：用户消息保留原始输入，LLM 内容剥离前缀，不额外插入 AI 提示消息）。

## 4. 业务规则清单

| 规则编号 | 规则内容 | 说明 |
|---------|---------|------|
| BR-SKILL-001 | 技能 id 全局唯一 | create 重复 id 拒绝 |
| BR-SKILL-002 | 技能定义持久化 | 标准目录 data/skills/{skillId}/SKILL.md（+scripts/+reference/），重启不丢（CR-001） |
| BR-SKILL-003 | 预置播种幂等 | 目标文件已存在不覆盖用户修改 |
| BR-SKILL-004 | 预置三形态 | 纯指令/指令+资源/指令+自带脚本工具（3 个预置样本） |
| BR-SKILL-005 | 恶意内容拦截 | 四类指令 100% 阻断（AC-S01） |
| BR-SKILL-006 | 密钥警告 | 密钥类模式警告不阻断（AC-S01 警告语义） |
| BR-SKILL-007 | 并发激活上限 | 单会话默认 3（AC-S05），超限引导 askUser |
| BR-SKILL-008 | 激活态会话隔离 | 按 sessionId 隔离（AC-M03） |
| BR-SKILL-009 | 排除不复发 | 排除后不再自主激活（AC-M04） |
| BR-SKILL-010 | 渐进式披露 | 目录段仅元数据，指令全文按需加载（AC-T01） |
| BR-SKILL-011 | 触发源限制 | 仅用户消息可触发激活（AC-S03） |
| BR-SKILL-012 | loadSkill 豁免 | 只读能力工具恒 ALLOW（决策 6） |
| BR-SKILL-013 | 脚本护栏 | 自带脚本工具经脚本护栏（白名单/参数/超时/危险命令拦截）管控，不进入系统权限模型（AC-T03/S06，CR-001） |
| BR-SKILL-015 | 指令附件化 | 激活成功时在 SkillLoadTool 激活点单点写入 `【框架附件·SKILL_INSTRUCTION】` 指令附件（emit-once，流式/同步双路径覆盖），替代系统提示词激活段（20260828 新增，AC-T01） |
| BR-SKILL-016 | 目录/状态附件隔离 | 目录附件/状态附件由 SkillPromptComposer 生成文本，经 ChatMemoryManager 写入记忆流；状态附件仅框架代码可写，技能激活不写 STATUS 附件（20260828 新增） |

## 5. 与其他模块的关系

| 模块 | 关系 |
|------|------|
| agent-demo-common | 依赖：SimpleTokenEstimator（Token 上限校验）、Result/异常体系 |
| agent-demo-memory | 依赖：ChatMemoryManager（SkillLoadTool 激活点写 SKILL_INSTRUCTION 附件；SkillPromptComposer 附件文本） |
| agent-demo-tools | 依赖：ToolRegistry（绑定工具解析）、ToolPermissionService（loadSkill 豁免） |
| agent-demo-agent | 被依赖：HITLReActStream 拦截、SessionToolResolver 合并、三路径提示词注入 |
| agent-demo-web | 被依赖：SkillController 管理 API、ChatRequest 技能字段、skill_activated SSE |
| agent-demo-bootstrap | 被依赖：skill.* 配置、default-tools 增加 builtin:loadSkill |

## 6. 接口清单

| 接口 | 方法 | 用途 |
|------|------|------|
| GET /api/skill/list | 查询技能列表 | 管理页列表 |
| POST /api/skill | 创建技能 | 内容校验 + 脚本内容/语言白名单/参数 schema 校验（CR-001） |
| PUT /api/skill/{skillId} | 更新技能 | 内容校验 + 脚本内容/语言白名单/参数 schema 校验（CR-001） |
| PUT /api/skill/{skillId}/enabled | 启用/禁用 | AC-E04 平滑退出联动 |
| DELETE /api/skill/{skillId} | 删除技能 | AC-E04 平滑退出联动 |
| SSE skill_activated | 技能激活事件 | 前端记录激活状态（AC-S04）；展示由用户消息保留原始输入承载（CR-002，不再插入 AI 提示/页面徽标） |
