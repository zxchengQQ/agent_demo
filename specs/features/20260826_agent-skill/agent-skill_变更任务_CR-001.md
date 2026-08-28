# 功能变更记录: agent-skill - CR-001

## 0. 变更概览 (Change Overview)
*   **变更标题**: Skill 指令化指定、自带脚本工具替代绑定系统工具、标准目录结构与迁移、管理页取消 bug 修复
*   **变更类型**: 重构 (Refactor)（含微调 bug 修复）
*   **变更原因**: 用户提出 4 项变更——①对话框无法显示指定 skill 的使用，希望像 `/plan` 强制任务拆解一样指令化指定并可视化；②skill 工具不应绑定系统现有工具而应是 skill 自带脚本工具；③保存方式希望按标准 skill 目录结构；④新建 skill 框无法点击取消关闭（bug）。
*   **发起日期**: 2026-08-27
*   **开发方法**: TDD（测试驱动开发）— 每个任务按 Red-Green-Refactor 循环执行
*   **关联功能**: agent-skill（Agent Skill 能力域）
*   **关联文档**:
    -   需求文档: `specs/features/20260826_agent-skill/agent-skill.md`（v2.0）
    -   技术方案: `specs/features/20260826_agent-skill/agent-skill_技术方案.md`（v2.0）
    -   任务规划: `specs/features/20260826_agent-skill/agent-skill_任务规划.md`（原 Task-01~28 已完成）

## 1. 影响分析 (Impact Analysis)

### 1.1 需求影响
| 影响项 | 变更类型 | 详情 |
| :--- | :--- | :--- |
| AC-N03 | 修改 | 手动指定入口扩展：选择器 + `/skill` 前缀指令双入口 |
| AC-N06 | 修改 | 管理闭环含脚本管理 + 标准目录结构 |
| AC-N07 | 新增 | `/skill` 前缀指令强制指定技能并可视化展示 |
| AC-N08 | 新增 | 标准目录结构持久化 + 启动自动迁移旧 JSON |
| AC-T02 | 修改 | 绑定系统工具 → 自带脚本工具（skill_{id}_{script} 动态注册） |
| AC-T03 | 修改 | 权限协同（不改权限等级）→ 脚本护栏（自带脚本不进入系统权限模型） |
| AC-T04 | 修改 | 绑定工具失败降级 → 脚本工具失败降级（护栏观察值） |
| AC-T05 | 新增 | 脚本工具激活即用与可回滚注销 |
| AC-S01 | 修改 | 内容校验扩展到脚本内容/语言白名单/参数 schema |
| AC-S06 | 新增 | 脚本执行护栏（超时/危险命令/参数校验/输出截断） |
| AC-H02 | 修改 | 绑定工具 ask 确认 → ask 级系统工具确认（脚本不进确认流） |
| 能力禁区 3.2 | 修改 | "不携带可执行脚本" → "仅携带预定义参数化脚本" |
| 8.2 Out of Scope | 修改 | "脚本不在规划内" → "仅支持受限预定义脚本，任意代码/沙箱不在规划内" |
| 数据 | 重构 | boundToolIds 移除 → scripts[]；data/skills/{id}.json → 目录结构 |

### 1.2 技术影响
| 影响层 | 影响范围 | 详情 |
| :--- | :--- | :--- |
| 数据层 | 新增字段 + 存储结构重构 | SkillDefinition.boundToolIds → scripts[]（SkillScript：name/language/description/params[]/content）；data/skills/{id}.json → data/skills/{id}/SKILL.md + scripts/ + reference/；启动幂等迁移 |
| API 层 | 修改接口 | SkillRequest/SkillResponse：boundToolIds → scripts[]；SkillController 移除绑定工具校验 |
| 表现层 | 新增组件 + 修改组件 | ChatWindow（/skill 前缀解析 + 可视化区块）、MessageInput（/skill 提示）、SkillManagementPage（脚本表单 + 取消 bug）、api/skill.ts/types |
| 业务逻辑 | 新增逻辑 | SkillScriptExecutor（脚本护栏）+ SkillScriptToolRegistrar（动态注册/注销）；SessionToolResolver 合并脚本工具 |
| 依赖 | 无新三方依赖 | 脚本执行用 JDK ProcessBuilder（超时/输出捕获） |

### 1.3 代码影响
| 文件路径 | 操作 | 影响说明 |
| :--- | :--- | :--- |
| `agent-demo-skill/.../entity/SkillDefinition.java` | 修改 | boundToolIds → scripts[]；新增 SkillScript 实体 |
| `agent-demo-skill/.../entity/SkillScript.java` | 新增 | 脚本声明实体（name/language/description/params[]/content） |
| `agent-demo-skill/.../store/SkillStore.java` | 修改 | 目录结构读写 + 旧 JSON 自动迁移 + 目录播种 |
| `agent-demo-skill/.../script/SkillScriptExecutor.java` | 新增 | 脚本执行器（护栏） |
| `agent-demo-skill/.../script/SkillScriptToolRegistrar.java` | 新增 | 激活注册/退出注销脚本工具 |
| `agent-demo-skill/.../security/SkillContentValidator.java` | 修改 | 脚本内容/语言白名单/参数 schema 校验 |
| `agent-demo-skill/.../session/SkillSessionManager.java` | 修改 | 激活/退出联动脚本注册注销 |
| `agent-demo-skill/.../store/SkillPresetSeeder.java` | 修改 | 目录结构播种 |
| `agent-demo-skill/src/main/resources/skills/{id}/` | 修改 | 3 个预置技能改目录结构；data-query-assistant 改脚本技能 |
| `agent-demo-agent/.../single/SessionToolResolver.java` | 修改 | 合并脚本工具（经 ToolRegistry 动态注册） |
| `agent-demo-agent/.../single/HITLReActStream.java` | 修改 | 拦截后触发脚本注册 + 热刷新 |
| `agent-demo-agent/.../single/SimpleAgent.java` | 修改 | 同步路径脚本工具合并 |
| `agent-demo-web/.../controller/SkillController.java` | 修改 | 字段变更 + 移除绑定工具校验 + 脚本参数校验 |
| `agent-demo-web/.../dto/SkillRequest.java` / `SkillResponse.java` | 修改 | boundToolIds → scripts[] |
| `agent-demo-bootstrap/.../application.yml` | 修改 | skill 配置段 + 脚本护栏参数（语言白名单/超时上限） |
| `agent-demo-frontend/src/components/ChatWindow.vue` | 修改 | /skill 前缀解析 + 当前技能可视化区块 |
| `agent-demo-frontend/src/components/MessageInput.vue` | 修改 | /skill 前缀提示条（/plan 同构） |
| `agent-demo-frontend/src/components/SkillManagementPage.vue` | 修改 | 脚本表单 + 取消 bug 修复 |
| `agent-demo-frontend/src/api/skill.ts` / `types/index.ts` | 修改 | 脚本字段类型 |

### 1.4 测试影响
| 测试文件 | 影响类型 | 说明 |
| :--- | :--- | :--- |
| `SkillStoreTest` | 需修改 | 目录结构 CRUD + 迁移用例 |
| `SkillContentValidatorAdversarialTest` | 需修改 | 脚本违规样本（白名单外语言/危险命令/非法 schema） |
| `SkillControllerTest` | 需修改 | scripts[] 字段 + 脚本参数校验 |
| `SkillActivationIntegrationTest` | 需修改 | 绑定工具断言 → 脚本工具动态注册/热刷新断言 |
| `SkillHITLAndSessionTest` | 无影响 | 激活态逻辑不变（仅注册联动，需回归确认） |
| `skill-management-page.test.ts` | 需修改 | 脚本表单 + 取消关闭用例（isNew bug 回归） |
| `chat.test.ts` | 需修改 | /skill 前缀解析用例 |
| `skill-selector.test.ts` | 无影响 | 不涉及变更范围 |
| — | 需新增 | SkillScriptExecutorTest（护栏矩阵）、SkillStoreMigrationTest（迁移）、ChatWindow 可视化区块用例 |

### 1.5 回归风险评估
*   **高风险区域**: SkillStore 持久化（目录结构/迁移）、SessionToolResolver 工具合并（脚本工具）、SkillController API 字段（前端类型）、ChatWindow 输入链路（/skill 前缀）
*   **已有测试覆盖**: 既有 skill 模块 59 测试 + agent 166 + web 114 + 前端 684（变更后将相应更新）
*   **需要补充的测试**: 迁移测试、脚本护栏测试、/skill 前缀测试、可视化区块测试、脚本工具注册/注销集成测试

## 2. 需求变更详情 (Requirements Delta)

### 2.1 新增/修改的用户故事
- **US-CR001-1**: 作为对话用户，我希望在输入框输入 `/skill 技能名` 强制指定使用某技能并在对话框看到当前技能展示，以便像 `/plan` 强制拆解一样显式控制技能使用（关联 AC-N07）
- **US-CR001-2**: 作为学习者，我希望技能自带可执行的脚本工具（而非绑定系统工具），以便观察"技能自带能力"的完整形态（关联 AC-T02/T03/T05/S06）
- **US-CR001-3**: 作为管理员，我希望技能按标准目录结构保存，以便与业界 Agent Skills 惯例一致并支持脚本/资源分目录管理（关联 AC-N08）
- **US-CR001-4**: 作为管理员，我希望新建技能弹窗可取消关闭，以便误触新建时能退出（关联 AC-N06 bug 修复）

### 2.2 新增/修改的验收标准
> 完整 Given-When-Then 见需求文档 v2.0（AC-N07/N08/T05/S06 新增；N03/N06/T02/T03/T04/S01/H02 修改）。

### 2.3 移除的内容（如有）
- [移除] 绑定系统工具能力（boundToolIds）：完全由自带脚本工具替代（系统工具仍为全局默认工具，AC-H02 保留系统工具确认流）
- [移除] 能力禁区"Skill 不携带可执行脚本"（细化为"仅携带预定义参数化脚本"）

## 3. 技术变更详情 (Technical Delta)

### 3.1 数据变更
```
# data/skills/{id}.json（旧）→ data/skills/{id}/（新，启动自动迁移）
data/skills/{id}/
  SKILL.md            # id/name/description/instruction/enabled/source 元数据 + 指令（YAML front-matter + Markdown）
  scripts/{name}.{ext}  # 脚本声明：params schema 在 SKILL.md front-matter 声明或随脚本
  reference/{name}    # 只读参考资源（原 resources[]）
```

### 3.2 API 变更
| 操作 | 方法 | 路径 | 说明 |
| :--- | :--- | :--- | :--- |
| 修改 | POST/PUT | `/api/skill`、`/api/skill/{id}` | SkillRequest.boundToolIds → scripts[]（name/language/description/params[]/content），移除绑定工具存在性校验，新增脚本语言/参数 schema 校验 |
| 修改 | GET | `/api/skill/list` | SkillResponse.boundToolIds → scripts[] |

### 3.3 组件变更
| 操作 | 组件 | 说明 |
| :--- | :--- | :--- |
| 新增 | `SkillScriptExecutor` | 脚本执行器：语言白名单（shell/python3）、参数校验、ProcessBuilder 执行、超时（默认 10s）、危险命令拦截、输出截断（4K） |
| 新增 | `SkillScriptToolRegistrar` | 激活时动态注册 skill_{id}_{script}、退出时注销（知识库动态工具先例） |
| 修改 | `ChatWindow.vue` | `/skill` 前缀解析 + 当前技能可视化区块 |
| 修改 | `MessageInput.vue` | `/skill` 前缀提示条 |
| 修改 | `SkillManagementPage.vue` | 脚本管理表单 + 取消 bug 修复 |

### 3.4 兼容性说明
*   **向前兼容**: 旧 data/skills/*.json 启动自动迁移（幂等，已迁移跳过）；旧前端忽略 scripts 字段变化需前端同步更新
*   **迁移方案**: SkillStore 启动检测 data/skills/*.json → 转换为 {id}/SKILL.md + reference/，源文件备份后保留或删除（迁移确认后删除）

## 4. 增量开发任务 (Incremental Tasks)
> 任务编号从原任务规划最后一个编号（Task-28）之后继续
> 每个任务按 TDD 循环执行：RED（写测试）→ GREEN（写实现）→ REFACTOR（重构）

### 阶段一：数据层变更 (Data Layer Delta)

- [x] **Task-29**: SkillDefinition 数据结构重构（boundToolIds → scripts[]）
    *   **说明**: 移除 boundToolIds 字段，新增 SkillScript 实体（name/language/description/params[]/content）与 scripts[] 字段；SkillScript.params 为声明式参数（name/type/required/取值范围）
    *   **变更类型**: 修改
    *   **涉及文件**: `agent-demo-skill/.../entity/SkillDefinition.java`、`agent-demo-skill/.../entity/SkillScript.java`（新增）
    *   **测试文件**: `agent-demo-skill/src/test/java/com/agentdemo/skill/entity/SkillDefinitionTest.java`
    *   **参考**: 技术方案 Sec 1.6/3.2
    *   **对应AC**: AC-N06
    *   **预估工时**: 45m
    *   **依赖**: 无
    *   **验证标准**（TDD RED 阶段的测试依据）:
        - [ ] JSON 序列化/反序列化 scripts[]（含参数 schema）往返一致
        - [ ] 反序列化含旧 boundToolIds 的 JSON 不报错（忽略未知字段，向后兼容）
        - [ ] 语言白名单枚举校验（shell/python3，其他拒绝）

- [x] **Task-30**: SkillStore 标准目录结构持久化 + 旧 JSON 自动迁移
    *   **说明**: CRUD 落点改为 data/skills/{id}/（SKILL.md + scripts/ + reference/）；启动时检测旧版 {id}.json 幂等迁移（备份原文件，已迁移目录跳过，单文件损坏跳过 WARN）
    *   **变更类型**: 修改
    *   **涉及文件**: `agent-demo-skill/.../store/SkillStore.java`、`agent-demo-skill/.../config/SkillProperties.java`（迁移开关参数）
    *   **测试文件**: `SkillStoreTest.java`、`SkillStoreMigrationTest.java`（新增）
    *   **参考**: 技术方案 Sec 4.3/3.1
    *   **对应AC**: AC-N08
    *   **预估工时**: 120m
    *   **依赖**: Task-29
    *   **验证标准**（TDD RED 阶段的测试依据）:
        - [ ] 创建技能生成 {id}/SKILL.md + scripts/ + reference/ 目录结构
        - [ ] 旧版 {id}.json 存在时启动自动迁移，元数据/资源/脚本全保留
        - [ ] 迁移幂等（已迁移目录跳过不覆盖），损坏 JSON 跳过不阻断
        - [ ] 删除技能删除整个目录

- [x] **Task-31**: SkillPresetSeeder 新目录播种 + 预置技能改造
    *   **说明**: classpath 预置技能改为 {id}/ 目录结构播种；data-query-assistant 改为自带脚本型（如 http-get.sh，声明 url 参数），周报/技术文档技能保持纯指令/指令+资源
    *   **变更类型**: 修改
    *   **涉及文件**: `agent-demo-skill/.../store/SkillPresetSeeder.java`、`agent-demo-skill/src/main/resources/skills/{id}/`
    *   **测试文件**: `SkillPresetSeederIntegrationTest.java`
    *   **参考**: 技术方案 Sec 4.3
    *   **对应AC**: AC-N06/N08
    *   **预估工时**: 45m
    *   **依赖**: Task-30
    *   **验证标准**（TDD RED 阶段的测试依据）:
        - [ ] 3 个预置技能以目录结构播种成功（幂等，已存在跳过）
        - [ ] data-query-assistant 播种后含 scripts/ 脚本与参数 schema

### 阶段二：脚本执行器与安全护栏 (Script Executor & Guardrails)

- [x] **Task-32**: SkillScriptExecutor 脚本执行器
    *   **说明**: 基于 ProcessBuilder 执行脚本（shell/python3 白名单）；声明式参数校验（类型/必填/取值范围，防 shell 注入）；执行超时（默认 10s，超时 kill）；危险命令拦截（rm -rf /、下载执行、fork 炸弹、写系统目录等模式）；输出截断（4K）
    *   **变更类型**: 新增
    *   **涉及文件**: `agent-demo-skill/.../script/SkillScriptExecutor.java`、`agent-demo-skill/.../config/SkillProperties.java`（超时上限/语言白名单配置）
    *   **测试文件**: `SkillScriptExecutorTest.java`（新增）
    *   **参考**: 技术方案 Sec 3.4/6.5/决策 9
    *   **对应AC**: AC-T03/S06
    *   **预估工时**: 120m
    *   **依赖**: Task-29
    *   **验证标准**（TDD RED 阶段的测试依据）:
        - [ ] 合法脚本执行返回 stdout（截断 4K）
        - [ ] 白名单外语言拒绝；危险命令（rm -rf / 等）执行前拦截
        - [ ] 参数类型/必填/取值范围校验失败返回明确观察值
        - [ ] 超时脚本被 kill 并返回超时观察值，不悬挂
        - [ ] 超长输出被截断（防上下文膨胀）

### 阶段三：脚本工具注入 (Script Tool Injection)

- [x] **Task-33**: SkillScriptToolRegistrar 动态注册/注销脚本工具
    *   **说明**: 激活时把 Skill 的 scripts/ 声明动态注册为 Agent 工具 skill_{id}_{script}（知识库动态工具 ByteBuddy 先例，工具描述来自脚本声明）；技能退出（排除/禁用/删除/超时清理）时注销
    *   **变更类型**: 新增
    *   **涉及文件**: `agent-demo-skill/.../script/SkillScriptToolRegistrar.java`
    *   **测试文件**: `SkillScriptToolRegistrarTest.java`（新增）
    *   **参考**: 技术方案 Sec 3.1/3.4/决策 8
    *   **对应AC**: AC-T02/T05
    *   **预估工时**: 90m
    *   **依赖**: Task-32
    *   **验证标准**（TDD RED 阶段的测试依据）:
        - [ ] 激活后 skill_{id}_{script} 出现在 ToolRegistry
        - [ ] 工具描述/参数 Schema 来自脚本声明（LLM 可路由）
        - [ ] 排除/禁用/删除/超时后脚本工具注销（不可再调用）
        - [ ] 并发激活多个技能脚本工具共存不冲突

- [x] **Task-34**: SessionToolResolver/HITLReActStream/SimpleAgent 脚本工具合并与热刷新
    *   **说明**: resolveSessionTools 合并激活技能脚本工具（经 ToolRegistry 动态注册结果）；HITLReActStream 拦截 loadSkill 后触发脚本注册 + 热刷新 toolsJson（当轮可用）；同步路径 delegate 指纹重建下一轮生效
    *   **变更类型**: 修改
    *   **涉及文件**: `agent-demo-agent/.../single/SessionToolResolver.java`、`HITLReActStream.java`、`SimpleAgent.java`
    *   **测试文件**: `SkillActivationIntegrationTest.java`（修改）
    *   **参考**: 技术方案 Sec 3.2/3.4/决策 2
    *   **对应AC**: AC-T02/T03/T05/E04
    *   **预估工时**: 90m
    *   **依赖**: Task-33
    *   **验证标准**（TDD RED 阶段的测试依据）:
        - [ ] 流式激活后当轮可调用 skill_{id}_{script}（热刷新）
        - [ ] 同步路径激活后下一轮可调用（delegate 重建）
        - [ ] 脚本护栏拦截（危险命令/参数非法）返回观察值，循环不中断
        - [ ] 技能退出后脚本工具自下一轮从会话工具集消失

### 阶段四：API 与校验扩展 (API & Validation)

- [x] **Task-35**: SkillController/SkillRequest/SkillResponse 字段变更 + 脚本参数校验
    *   **说明**: boundToolIds → scripts[]；移除绑定工具存在性校验；新增脚本语言白名单/参数 schema 校验（保存时）
    *   **变更类型**: 修改
    *   **涉及文件**: `agent-demo-web/.../controller/SkillController.java`、`dto/SkillRequest.java`、`dto/SkillResponse.java`
    *   **测试文件**: `SkillControllerTest.java`（修改）
    *   **参考**: 技术方案 Sec 1.6/3.2
    *   **对应AC**: AC-N06/S01
    *   **预估工时**: 90m
    *   **依赖**: Task-29
    *   **验证标准**（TDD RED 阶段的测试依据）:
        - [ ] POST/PUT 接收 scripts[]（含参数 schema）正常创建/更新
        - [ ] 白名单外语言/非法参数 schema 保存被拒（返回明确原因）
        - [ ] GET /list 返回 scripts[] 字段
        - [ ] 旧请求体（含 boundToolIds）反序列化兼容

- [x] **Task-36**: SkillContentValidator 扩展脚本内容校验
    *   **说明**: 在四类恶意指令校验基础上新增脚本校验——语言白名单、脚本内容危险命令模式（rm -rf /、下载执行、fork 炸弹、写系统目录）、参数 schema 合法性
    *   **变更类型**: 修改
    *   **涉及文件**: `agent-demo-skill/.../security/SkillContentValidator.java`
    *   **测试文件**: `SkillContentValidatorAdversarialTest.java`（修改，新增脚本违规样本）
    *   **参考**: 技术方案 Sec 6.7/决策 9
    *   **对应AC**: AC-S01/S06
    *   **预估工时**: 60m
    *   **依赖**: Task-29
    *   **验证标准**（TDD RED 阶段的测试依据）:
        - [ ] 脚本语言非白名单 → 阻断（返回命中类别）
        - [ ] 脚本含危险命令 → 阻断
        - [ ] 参数 schema 非法（重复名/非法类型/必填缺省）→ 阻断
        - [ ] 良性脚本不误拦截

### 阶段五：前端变更 (Frontend Delta)

- [x] **Task-37**: `/skill` 前缀指令 + 对话框可视化区块 + MessageInput 提示
    *   **说明**: ChatWindow 解析 `/skill 技能名` 前缀 → 更新会话级 skills（消息剥离前缀）；对话框内新增"当前指定技能"可视化区块（类似任务拆解面板，列出手动指定技能 + 可移除）；MessageInput 增加 `/skill` 前缀提示条（/plan 同构）
    *   **变更类型**: 新增/修改
    *   **涉及文件**: `agent-demo-frontend/src/components/ChatWindow.vue`、`MessageInput.vue`、`api/chat.ts`、`stores/session.ts`
    *   **测试文件**: `chat.test.ts`、`ChatWindow` 相关测试（修改/新增）
    *   **参考**: 技术方案 Sec 1.4/1.6
    *   **对应AC**: AC-N07
    *   **预估工时**: 60m
    *   **依赖**: 无
    *   **验证标准**（TDD RED 阶段的测试依据）:
        - [ ] 输入 `/skill 数据查询助手 帮我查 XX` → 会话 skills 含"数据查询助手"，发送消息剥离 `/skill 前缀`
        - [ ] 可视化区块展示当前指定技能，可移除（恢复自动）
        - [ ] 指定不存在的技能 → 提示并保持自动模式（不静默）
        - [ ] MessageInput 显示 `/skill` 可发现提示

- [x] **Task-38**: SkillManagementPage 表单改脚本管理 + 修复取消 bug
    *   **说明**: 表单绑定工具下拉 → 脚本编辑器（脚本名/语言/描述/参数 schema/内容）；修复取消 bug（closeForm 清 isNew 标志，`v-if="isNew || editing"` 才能关闭新建表单）
    *   **变更类型**: 修改
    *   **涉及文件**: `agent-demo-frontend/src/components/SkillManagementPage.vue`
    *   **测试文件**: `skill-management-page.test.ts`（修改，新增取消关闭用例）
    *   **参考**: 需求文档 CR-001；技术方案 Sec 1.6
    *   **对应AC**: AC-N06（bug 修复）
    *   **预估工时**: 60m
    *   **依赖**: Task-35
    *   **验证标准**（TDD RED 阶段的测试依据）:
        - [ ] 新建表单点击"取消"→ 表单关闭（回归 bug）
        - [ ] 编辑表单含脚本字段（语言/参数 schema/内容）
        - [ ] 保存带脚本技能成功，列表展示脚本工具信息

- [x] **Task-39**: 前端 api/skill.ts / types / stores 类型变更
    *   **说明**: SkillInfo 类型 boundToolIds → scripts[]；api/skill.ts 请求/响应封装脚本字段；stores 适配
    *   **变更类型**: 修改
    *   **涉及文件**: `agent-demo-frontend/src/api/skill.ts`、`types/index.ts`、`stores/skill.ts`
    *   **测试文件**: `api/skill` 相关测试（修改）
    *   **参考**: 技术方案 Sec 1.6
    *   **对应AC**: AC-N06
    *   **预估工时**: 30m
    *   **依赖**: Task-35
    *   **验证标准**（TDD RED 阶段的测试依据）:
        - [ ] 类型定义与后端 scripts[] 结构一致
        - [ ] 创建/编辑请求体含 scripts[]，响应解析正确

### 阶段六：回归验证 (Regression Verification)

- [x] **Task-40**: 回归验证（后端全量 + 前端全量）
    *   **说明**: 运行 skill/agent/web 全量测试 + 前端 vitest 全量，确认无回归；专项确认 app 模块既有 3 个失败测试（基线既有，非本变更引入）
    *   **变更类型**: 验证
    *   **涉及文件**: `tests/` 目录下所有相关测试文件
    *   **对应AC**: 所有受影响 AC
    *   **预估工时**: 60m
    *   **依赖**: Task-29~39
    *   **验证标准**:
        - [ ] skill 模块全量测试通过（含迁移/脚本护栏新增测试）
        - [ ] agent/web 模块测试通过（ScriptToolRegistrar/Resolver 改动无回归）
        - [ ] 前端 vitest 全量通过（含 /skill/取消/可视化区块新增测试）
        - [ ] 应用启动验证：旧 JSON 迁移生效、预置技能目录播种、/api/skill/list 返回脚本字段

- [x] **Task-41**: 评估集更新（脚本技能样本）+ 文档收尾
    *   **说明**: skill-eval 数据集更新 data-query-assistant 为脚本技能场景（激活/脚本调用/护栏拦截样本）；KNOWLEDGE_BASE.md 更新能力矩阵/工程结构（Skill 域含脚本工具+目录结构）；SDD BR-SKILL 规则按需同步
    *   **变更类型**: 收尾
    *   **涉及文件**: `agent-demo-skill/src/test/resources/skill-eval/`、`KNOWLEDGE_BASE.md`、`specs/modules/Skill模块-业务说明书.md`
    *   **对应AC**: AC-T02/T03/S06/N08
    *   **预估工时**: 45m
    *   **依赖**: Task-40
    *   **验证标准**:
        - [ ] 评估集含脚本技能激活/调用/护栏用例，可加载
        - [ ] KNOWLEDGE_BASE/Skill 模块说明书同步 CR-001 变更

## 5. 增量验收标准检查清单 (Incremental AC Checklist)

| 验收标准ID | 验收标准描述 | 状态 | 对应任务 | 操作 |
| :--- | :--- | :--- | :--- | :--- |
| AC-N03 | 手动指定优先（选择器 + /skill 双入口） | ✅ 满足 | Task-37 | 修改 |
| AC-N06 | 管理闭环含脚本管理 + 目录结构 | ✅ 满足 | Task-29/30/31/35/38/39 | 修改 |
| AC-N07 | /skill 前缀指令强制指定并可视化 | ✅ 满足 | Task-37 | 新增 |
| AC-N08 | 标准目录结构持久化与迁移 | ✅ 满足 | Task-30/31 | 新增 |
| AC-T02 | 自带脚本工具注入与优先选用 | ✅ 满足 | Task-33/34 | 修改 |
| AC-T03 | 脚本护栏（不进入系统权限模型） | ✅ 满足 | Task-32/34 | 修改 |
| AC-T04 | 脚本工具失败降级 | ✅ 满足 | Task-32/34 | 修改 |
| AC-T05 | 脚本工具激活即用与可回滚注销 | ✅ 满足 | Task-33/34 | 新增 |
| AC-S01 | 创建时内容校验拦截（含脚本） | ✅ 满足 | Task-36 | 修改 |
| AC-S06 | 脚本执行护栏 | ✅ 满足 | Task-32 | 新增 |
| AC-H02 | ask 级系统工具确认卡片同构复用 | ✅ 满足 | Task-34（回归确认） | 修改 |

## 6. 变更总结 (Change Summary)
*   **总新增任务数**: 13 个（Task-29 ~ Task-41）
*   **预计总工时**: 915 分钟（约 15.3 小时）
*   **风险等级**: 中
*   **风险说明**: 脚本执行安全（双层护栏 + skill.enabled=false 兜底）、存储迁移（幂等 + 备份 + 单文件降级）、API 字段变更（前端同步更新 + 反序列化兼容）
*   **测试影响**: 需修改 6 个已有测试文件，新增 4 个测试文件（迁移/脚本执行器/注册器/可视化区块）
*   **预期效果**: 技能可在对话框用 `/skill` 指令强制指定并可视化；技能自带可执行脚本工具（护栏保护）；存储符合标准目录结构；管理页取消按钮可用
