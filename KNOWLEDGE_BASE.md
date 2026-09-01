# AI Agent 示例项目 知识库 (KNOWLEDGE_BASE.md)

> **文档版本**：v3.7
> **基线日期**：2026-08-31
> **适用范围**：agent-demo（Java 后端 + Vue 3 前端工程）
> **数据来源**：项目源码 + `pom.xml` + `application.yml` + `package.json` + `specs/` 文档体系
> **维护方式**：每次功能迭代后由 `knowledge-base-generator` 技能增量更新

---

## 目录

- [一、项目概览](#一项目概览)
- [二、文档地图](#二文档地图)
- [三、技术栈速查](#三技术栈速查)
- [四、工程结构速查](#四工程结构速查)
- [五、业务域知识图谱](#五业务域知识图谱)
- [六、核心开发范式](#六核心开发范式)
- [七、数据库速查](#七数据库速查)
- [八、安全与权限体系](#八安全与权限体系)
- [九、关键业务规则清单](#九关键业务规则清单)
- [十、开发环境与构建](#十开发环境与构建)
- [十一、AI 驱动开发流程](#十一ai-驱动开发流程)
- [十二、常见问题与排障](#十二常见问题与排障)

---

## 一、项目概览

### 1.1 系统定位

**AI Agent 示例项目 (agent-demo)** 是一套面向 AI 应用开发学习者的**企业级 Agent 能力演练平台**。系统基于 Java 17 + Spring Boot 3.2.5 + LangChain4j 1.17.2 构建后端，以**单 Agent 工具调用（ReAct 循环）**为基础，扩展多 Agent 协作、RAG 知识库问答、MCP 协议互通、有状态工作流编排等能力。

LLM 提供商支持配置级切换，默认为**火山引擎方舟 Coding Plan**（按次计费，OpenAI 兼容协议），也可切换为**阿里百炼**（百炼大模型服务平台，OpenAI 兼容协议）。

前端模块基于 **Vue 3 + Vite 5 + TypeScript 5 + Pinia 2** 构建，提供美观的**暗色科技风（Refined Dark Tech）** 对话界面，通过 SSE 流式接口与后端通信，使用浏览器 localStorage 持久化会话纪录。

### 1.2 核心价值

| # | 核心价值 | 说明 |
|---|---------|------|
| 1 | 企业级 Agent 能力覆盖 | 单 Agent、多 Agent、RAG、MCP、工作流、Skill 等 10 大能力域 |
| 2 | 声明式开发体验 | `@AiService` + `@Tool` + `@SystemMessage` 注解式编程，零样板 |
| 3 | 强类型契约 | Java 强类型直观呈现 Agent 接口，编译期检查 |
| 4 | 低成本 LLM 接入 | Coding Plan 按次计费 + 模型实例缓存复用 |
| 5 | 安全沙箱机制 | SSRF 防护 + 目录白名单 + 响应截断三重工具安全 |
| 6 | 模块化清晰 | 11 个 Maven 模块中等粒度拆分，职责边界明确 |
| 7 | BOM 统一版本 | 第三方依赖集中管理，避免版本冲突 |
| 8 | 渐进式演进 | 已实现核心 5 能力，RAG/MCP/多 Agent 分阶段补充 |

### 1.3 目标用户角色

| 角色 | 职责范围 | 主要操作模块 |
|------|---------|------------|
| **学习者** | 运行 Demo、调用 API、阅读源码理解原理 | bootstrap、web、agent |
| **开发者** | 扩展工具、新增 Agent、接入新 LLM | tools、agent、llm、memory |
| **API 调用方** | 通过 REST API 集成 Agent 能力 | web |
| **运维者** | 配置 API Key、监控 Token 消耗 | bootstrap、application.yml |

### 1.4 能力矩阵

| 能力域 | 实现状态 | 学习要点 |
|--------|---------|---------|
| LLM 调用 | ✅ 已实现 | 模型抽象、流式输出、参数调优、场景路由、**动态配置 + 多厂商 + 前端管理 + 会话级模型选择（CR-003 重构：移除 Provider 模式，前端动态配置，LlmConfigStore 内存存储，按 modelId 路由）** |
| 工具调用 | ✅ 已实现 | ReAct 循环、Function Calling、声明式注册、**工具按需加载（default-tools 默认加载 + optional 按需指定，GET /api/agent/tools 查询）** |
| 记忆系统 | ✅ 已实现（短期） | 短期窗口记忆、会话隔离、超时清理 |
| Agent 编排 | ✅ 已实现（单 Agent） | AiServices 代理、ReAct 循环、懒加载 |
| Web 接口 | ✅ 已实现 | REST 同步对话、SSE 流式对话、会话管理、Swagger 文档 |
| 前端对话 | ✅ 已实现（v1） | Vue 3 对话框、SSE 流式逐字显示、localStorage 持久化、会话管理 UI |
| 前端知识库管理 | ✅ 已实现 | 知识库 CRUD、文档上传/轮询/删除、对话知识库选择器、左右分栏管理页面 |
| RAG 检索 | ✅ 已实现 | 知识库问答、文档分块、向量化（批量批处理）、向量检索、Agent 工具集成（CR-003: 动态 Tool 注册，每个知识库独立 Tool） |
| MCP 协议 | ✅ 已实现 | MCP 客户端、双传输（stdio+SSE+Streamable HTTP）、动态/静态 Server 管理、ByteBuddy 工具代理 |
| 工具产出安全清洗 | ✅ 已实现（20260826 迭代；20260831 CR-001 增强 / 20260901 CR-004 架构改造） | 4 类数据获取工具产出统一经 SanitizeStage SPI 可插拔清洗管道（变换段有序链：⓪ 隐形字符剥离/① HTML 剥离/② 可疑指令分级处置/②' 秘密脱敏 + 固定终段限长临时文件/包裹声明），工具结果作为数据而非指令进入上下文；秘密脱敏 [REDACTED]（AC-S09）、随机化分隔符防闭合标记逃逸（AC-S10）、隐形字符清洗（AC-S11）、统一限长 4000 字符 + 临时文件分段查询（AC-T01/T02）、双层降级铁律不阻断结果返回（AC-E02/E05/E06）、规则启动预编译 RULE_SKIPPED（AC-S12）、三子开关独立回退；评估侧 forbidden-keywords 拒绝断言（CR-001 审查加固） |
| 多 Agent 协作 | ✅ 已实现 | langchain4j-agentic 编排引擎：串行/并行/条件/循环/Supervisor 五种模式（2026-08-13 应用编排层 P1~P3 交付） |
| 工作流编排 | ✅ 已实现 | 模板注册、执行状态机（含 PAUSED/WAITING_USER）、自动重试、断点续执行、SSE 执行可视化、前端编排页面（工作流 HITL 已实现，拖拽编排规划中） |
| 人机交互 HITL | ✅ 已实现（单 Agent + 工作流） | 单 Agent：askUser 工具 + 显式 ReAct 暂停-恢复 + `ask_user` SSE 事件 + 前端文本/选项卡片双形态；工作流：@HumanCheckpoint 检查点 + WAITING_USER 状态 + `workflow_waiting`/`workflow_resumed` 事件 + 5 模式执行视图等待 UI（20260824 迭代） |
| Skill 能力域 | ✅ 已实现（20260826 迭代；20260827 CR-001/CR-002 演进） | 渐进式披露（目录段常驻元数据 + loadSkill 按需加载全文）、会话级激活态（自主/手动/排除 + `/skill` 指令，用户消息保留原始输入展示 CR-002）、多 Skill 并发（上限 3）、自带脚本工具（白名单 shell/python3 + 脚本护栏，动态注册 skill_{id}_{script}，CR-001）、标准目录结构存储（data/skills/{id}/SKILL.md+scripts/+reference/，启动自动迁移）、`skill_activated` SSE 事件、Skill 管理页（agent-demo-skill 模块，第 10 能力域） |
| 可观测性 | ✅ 已实现（20260829 基础 + 20260831 CR-001 五域扩展） | LangSmith 云接入（OTel 标准 + OTLP HTTP + x-api-key，agent-demo-observability 模块第 11 能力域）：**基础** LLM+工具采集（listener/装饰器/ToolExecutor 收口）、**CR-001 五域事件采集**（RAG 检索/记忆压缩/工作流编排双层/MCP 协议层/Skill 激活）、脱敏单一出口（SensitiveDataMasker）、默认关零外联（Noop/Otlp 条件装配）、工作流 executionId 聚合 + 并行线程传播、project 归属双写、endpoint 完整 URL 含 /v1/traces（2026-08-31 404 修复）、AiServices 反射路径工具调用经 afterToolExecution 统一采集（2026-08-31 CR-002 审查补链，补齐生产同步路径工具 span）、**HITL 多轮 trace 续接（2026-09-01 BUG 修复：markHITLPause/resumeRequest 远程父续接，单任务多轮人机交互一条完整链路）** |
| 评估能力域 | ✅ 已实现（20260831 CR-002 完整评估体系） | 本地评估 harness（agent-demo-evaluation 模块第 12 能力域）：**本地数据集执行**（10 用例含陷阱×2+密钥正例，Pass^runs 稳定性）、**LLM-as-judge**（三维度评分 + 幻觉 veto 代码层强制 + 多源 judge 对冲同源偏差 + 缺席降级）、**回归基线**（±30pp 噪声带宽 + 显式重建防静默覆盖）、**A/B 对比**（Pass^3 强制 + 统计显著性）、CLI 独立上下文（LlmConfigSeed 双厂商种子注入）、评估出境/落盘统一脱敏出口（AC-S07） |

> **数据来源**：`specs/SDD-工程业务背景文档.md` 第 2.3 节、`docs/ARCHITECTURE.md`

---

## 二、文档地图

### 2.1 TOGAF 架构文档（specs/ 根目录）

| 文档 | 路径 | 定位 |
|------|------|------|
| SDD-工程业务背景文档.md | `specs/SDD-工程业务背景文档.md` | 业务真理源，纯业务视角 |
| SDD-项目技术指南文档.md | `specs/SDD-项目技术指南文档.md` | 技术真理源，纯技术视角 |
| 业务架构文档.md | `specs/业务架构文档.md` | TOGAF Phase B |
| 技术架构文档-TOGAF.md | `specs/技术架构文档-TOGAF.md` | TOGAF Phase C/D |
| 数据架构文档-TOGAF.md | `specs/数据架构文档-TOGAF.md` | TOGAF Phase C-Data |

### 2.2 模块业务说明书（specs/modules/）

| 模块 | 文档路径 |
|------|---------|
| Agent 编排模块 | `specs/modules/Agent编排模块-业务说明书.md` |
| LLM 接入模块 | `specs/modules/LLM接入模块-业务说明书.md` |
| 工具调用模块 | `specs/modules/工具调用模块-业务说明书.md` |
| 记忆管理模块 | `specs/modules/记忆管理模块-业务说明书.md` |
| Web 接口模块 | `specs/modules/Web接口模块-业务说明书.md` |
| 公共组件模块 | `specs/modules/公共组件模块-业务说明书.md` |
| RAG 知识库模块 | `specs/modules/RAG模块-业务说明书.md` |
| MCP 协议模块 | `specs/modules/MCP协议模块-业务说明书.md` |
| 应用编排模块 | `specs/modules/应用编排模块-业务说明书.md`（v2.9 新增） |
| 可观测性模块 | `specs/modules/可观测性模块-业务说明书.md`（20260831 新增，第 11 能力域；覆盖 LangSmith 基础接入 + CR-001 五域事件采集） |
| 评估模块 | `specs/modules/评估模块-业务说明书.md`（20260831 新增，第 12 能力域；覆盖 CR-002 完整评估体系：LLM-as-judge + 回归基线 + A/B 对比） |

> 模块总目录：`specs/modules/README.md`（v2.9 新增，含模块依赖全景图）

### 2.3 前端模块目录

| 路径 | 说明 |
|------|------|
| `agent-demo-frontend/` | Vue 3 前端项目根目录 |
| `agent-demo-frontend/src/api/` | SSE 流式调用封装（`chat.ts`）、RAG API 封装（`rag.ts`，7 个 REST 接口） |
| `agent-demo-frontend/src/stores/` | Pinia 状态管理（`session.ts` 含知识库选择器会话级状态、`rag.ts` 知识库/文档状态管理） |
| `agent-demo-frontend/src/utils/` | localStorage 缓存工具（`storage.ts`）、Markdown 渲染封装（`markdown.ts`） |
| `agent-demo-frontend/src/types/` | TypeScript 类型定义（`index.ts`，含 KnowledgeBase/DocumentInfo/DocumentStatus 等 RAG 类型） |
| `agent-demo-frontend/src/components/` | Vue 组件（对话：MessageItem/MessageList/MessageInput/ChatWindow/SessionList/NavBar；知识库：KnowledgeBasePage/KnowledgeBaseList/CreateKnowledgeBaseDialog/DocumentList/DocumentUploader/KnowledgeBaseSelector） |
| `agent-demo-frontend/src/styles/` | 全局样式系统（`global.css`，Refined Dark Tech） |

### 2.4 工程参考文档

| 文档 | 路径 | 说明 |
|------|------|------|
| 架构设计文档 | `docs/ARCHITECTURE.md` | 项目最初架构设计，含技术选型决策 |
| 功能迭代计划 | `feature/项目初始化搭建/plan/` | 9 个阶段的搭建计划文档 |
| AI Skills | `.agents/skills/` | 8 个 AI 辅助开发技能 |

### 2.5 迭代文档规范

```
features/{yyyy-MM-dd}/{功能名}/
├── 前端对话模块.md         # 功能规格
├── 前端对话模块_任务规划.md # 任务清单
└── 前端对话模块_技术方案.md # 技术设计
```

### 2.6 开发记录

| 文档 | 路径 |
|------|------|
| 阶段一 | `docs/开发记录/前端对话模块_阶段1_完成报告.md` |
| 阶段二 | `docs/开发记录/前端对话模块_阶段2_完成报告.md` |
| 阶段三 | `docs/开发记录/前端对话模块_阶段3_完成报告.md` |
| 阶段四 | `docs/开发记录/前端对话模块_阶段4_T16联调报告.md` |
| 应用编排层 P2 阶段一 | `docs/开发记录/应用编排层_P2_阶段1_完成报告.md` |
| 应用编排层 P2 | `docs/开发记录/应用编排层_P2_完成报告.md` |
| 应用编排层 P3 | `docs/开发记录/应用编排层_P3_完成报告.md` |
| 应用编排层全阶段 | `docs/开发记录/应用编排层_全阶段_完成报告.md` |

> **数据来源**：`specs/` 目录扫描

---

## 三、技术栈速查

### 3.1 后端核心技术栈

> **数据来源**：`pom.xml` + `agent-demo-bom/pom.xml`

```properties
# 语言与框架
java.version=17
spring-boot.version=3.2.5
langchain4j.version=1.0.0

# AI 框架全家桶
langchain4j-open-ai=1.0.0          # 火山引擎接入适配器
langchain4j-milvus=1.0.0           # 向量数据库（规划中）
langchain4j-mcp=1.17.2-beta27       # MCP 协议（已实现，客户端 + 三种传输方式）
langchain4j-agentic=1.17.2-beta27   # 多 Agent 编排引擎（应用编排层：串行/并行/条件/循环/Supervisor + 断点恢复）

# 数据访问
mybatis-plus.version=3.5.7         # ORM（规划中）
milvus.version=2.4.3               # 向量数据库 SDK（规划中）

# 工具库
hutool.version=5.8.27              # 通用工具
springdoc.version=2.5.0            # OpenAPI 文档
pdfbox.version=3.0.3               # PDF 文档解析（RAG 模块）
tabula.version=1.0.5               # PDF 表格提取（RAG 模块 CR-001）
bytebuddy.version=1.14.19          # 运行时动态生成带 @Tool 注解的知识库工具类（RAG 模块 CR-003）

# 可观测性（20260829 新增，agent-demo-observability 模块）
opentelemetry.version=1.42.1       # OTel SDK（api/sdk/exporter-otlp），OTLP HTTP 上报 LangSmith 云；exporter 直接使用 endpoint 完整 URL 不追加 /v1/traces（缺路径致 404）

# 构建
maven.version=3.9+
project.version=1.0.0
```

### 3.2 LLM 提供商配置

> **配置方式变更（v2.7）**：LLM 厂商和模型配置已从静态 `application.yml` 提取到前端页面动态管理，不再通过环境变量注入 API Key。用户通过前端"LLM 配置"页面添加厂商、配置 API Key、选择模型，配置同时保存到后端内存和前端 localStorage，重启后自动恢复。

LLM 模块采用**动态配置模式**（CR-003 重构），通过 `LlmConfigStore` 内存存储管理所有厂商配置，`ModelFactory` 从 `LlmConfigStore` 读取配置动态创建 OpenAI 兼容模型实例，按 `vendorId:modelName` 缓存复用。支持多厂商同时配置，按会话维度选择模型。

项目内置 5 个预定义厂商（火山引擎方舟、阿里百炼、OpenAI、DeepSeek、Ollama），每个预定义厂商自带默认 Base URL 和常用模型清单，也支持用户自定义厂商。

### 3.3 预定义模型目录

> **配置方式变更（v2.7）**：模型配置已从静态 yml 提取到前端页面动态管理，以下为预定义厂商中内置的常用模型清单，实际可用模型由用户在前端配置时选择。

| 厂商 | 预定义模型（部分） |
|------|------------------|
| 火山引擎方舟 | doubao-seed-2.0-pro(chat)、doubao-seed-2.0-code(chat)、doubao-seed-2.0-lite(chat)、doubao-vision-pro(chat,支持视图)、doubao-embedding-large-text-240915(embedding) |
| 阿里百炼 | deepseek-v4-flash(chat)、glm-5.2(chat)、qwen3.7-plus(chat,支持视图)、text-embedding-v4(embedding) |
| OpenAI | gpt-4o(chat,支持视图)、gpt-4o-mini(chat)、text-embedding-3-small(embedding) |
| DeepSeek | deepseek-chat(chat)、deepseek-reasoner(chat) |
| Ollama (本地) | 无预定义模型，用户自定义 |

### 3.4 前端技术栈

> **数据来源**：`agent-demo-frontend/package.json`、`vite.config.ts`

```properties
# 语言与框架
node.version=18+
vue.version=3.4+
vite.version=5.4+
typescript.version=5.4+

# 核心依赖
pinia=2.1+               # Vue 状态管理
vue-router=4.3+          # 路由（预留）
marked=12+               # Markdown 解析（CR-001 新增，助手消息 Markdown 渲染）
dompurify=3+             # DOMPurify XSS 防护（CR-001 新增，与 marked 配合使用）

# 构建与开发
vitest=1.6+              # 单元测试框架
@vue/test-utils=2.4+     # Vue 组件测试工具
playwright=1.58+         # 浏览器端 E2E 测试

# 代理配置
vite.proxy=/api -> http://localhost:8080  # 开发期代理规避 CORS
```

### 3.5 前端设计系统

前端采用 **Refined Dark Tech（精致暗色科技风）** 设计美学：

| 设计维度 | 取值 |
|---------|------|
| 主色调 | 深蓝黑底（`#0a0e1a`）+ 青色 accent（`#00d4b8`） |
| 字体 | 英文 `JetBrains Mono`，中文 `Noto Sans SC` |
| 布局 | 左右分栏（280px 侧边栏 + 自适应对话框） |
| 交互 | hover 高亮、fadeIn 动画、流式光标 blink 动画 |
| 组件 | 13 个 Vue 组件：对话（MessageItem/MessageList/MessageInput/ChatWindow/SessionList/NavBar）+ 知识库（KnowledgeBasePage/KnowledgeBaseList/CreateKnowledgeBaseDialog/DocumentList/DocumentUploader/KnowledgeBaseSelector）+ App |

> **数据来源**：`agent-demo-bom/pom.xml`、`agent-demo-common/.../ModelConstants.java`

---

## 四、工程结构速查

### 4.1 后端模块拓扑

> **数据来源**：根 `pom.xml` + 各模块 `pom.xml`

```
agent-demo/
├── pom.xml                              # 根 POM，继承 spring-boot-starter-parent:3.2.5
├── agent-demo-bom/                      # BOM 物料清单（pom-only，统一版本）
├── agent-demo-common/                   # 公共组件（常量/枚举/异常/结果/工具类）
├── agent-demo-llm/                      # LLM 接入层（动态配置 + 模型工厂 + 内存配置存储）
├── agent-demo-tools/                    # 工具集（内置工具 + 注册中心）
├── agent-demo-memory/                   # 记忆模块（短期记忆 + 会话管理）
├── agent-demo-rag/                      # RAG 模块（知识库问答：向量化、检索、CR-003 动态 Tool 注册，解析/分割已迁移至 splitter 模块）
├── agent-demo-splitter/                 # 文档分割模块（文档解析、多级级联切分、过短块合并、按类型专属分割策略）
├── agent-demo-mcp/                      # MCP 协议模块（MCP 客户端：三传输方式 + 动态/静态 Server 管理 + ByteBuddy 工具代理）
├── agent-demo-skill/                    # Skill 能力域（技能定义/存储/内容校验/会话激活态/提示词组装/loadSkill 工具，第 10 能力域，20260826 新增）
├── agent-demo-observability/            # 可观测性（LangSmith 接入：TraceCollector 采集接口 + OTel span 构建 + 脱敏单一出口 + 条件装配，第 11 能力域，20260829 新增；CR-001 五域事件采集扩展 20260831）
├── agent-demo-evaluation/               # 评估能力域（本地评估 harness：数据集执行 + LLM-as-judge + 回归基线 + A/B 对比，CLI 独立上下文 + LlmConfigSeed 多源 judge 种子注入，第 12 能力域，20260831 CR-002 新增）
├── agent-demo-agent/                    # Agent 核心模块（单 Agent ReAct）
├── agent-demo-app/                      # 应用编排层（P1~P3 完整实现：adapter 基础设施桥接 + core 领域模型 + strategy 五种编排策略 + execution 重试/SSE 基础设施 + service 协调层与断点恢复 + registry 模板注册 + template 5 个预置模板；工作流 HITL 迭代新增 @HumanCheckpoint 注解 / WorkflowHITLState 快照 / WAITING_USER 状态 / hitlReply 恢复 / 并行 HITL 排队）
├── agent-demo-web/                      # Web 接口层（REST + SSE + DTO + 配置）
├── agent-demo-bootstrap/                # 启动模块（主启动类 + 配置 + 提示词）
└── agent-demo-frontend/                 # 前端模块（Vue 3 + Vite + TypeScript + Pinia）
    ├── package.json
    ├── vite.config.ts                    # Vite 配置（含 /api 代理到 :8080）
    ├── tsconfig.json
    ├── index.html
    └── src/
        ├── api/chat.ts                   # SSE 流式调用封装（fetch + ReadableStream，含 reasoning 事件处理，含 knowledgeBases 参数）
        ├── api/rag.ts                    # RAG API 封装（7 个 REST 接口 + 统一 request 函数）
        ├── api/llm.ts                    # LLM 厂商配置 API 封装（预定义/厂商 CRUD/测试连接/模型/状态/同步）
        ├── api/mcp.ts                    # MCP 服务管理 API 封装（Server 列表/添加/删除/重连/工具列表，Task-03 新增）
        ├── api/workflow.ts               # 工作流 API 封装（8 个 REST 接口 + SSE 解析 parseSseStream + 断流兜底 + streamResume 恢复流 + replyToWorkflow HITL 回复，应用编排层新增）
        ├── stores/session.ts             # Pinia 会话状态管理（含 appendReasoning + knowledgeBasesBySession 会话级知识库选择状态）
        ├── stores/rag.ts                 # Pinia RAG 状态管理（知识库列表/文档列表/CRUD/状态轮询）
        ├── stores/llm.ts                 # Pinia LLM 配置状态管理（vendors/chatModels/configStatus，配置同步至 localStorage）
        ├── stores/mcp.ts                 # Pinia MCP 服务状态管理（servers/toolsCache，写操作后自动刷新，Task-04 新增）
        ├── utils/markdown.ts             # Markdown 渲染封装（marked + DOMPurify）
        ├── utils/mcp-config.ts           # MCP JSON 配置解析器（mcpServers 格式解析 + 传输方式推断，Task-06 新增）
        ├── utils/storage.ts              # localStorage 缓存工具（50 会话 FIFO 淘汰）
        ├── types/index.ts                # TypeScript 类型定义（Message.reasoning + StreamCallbacks + KnowledgeBase/DocumentInfo/DocumentStatus 等）
        ├── components/                   # Vue 组件
        │   ├── 对话组件                   # MessageItem/MessageList/MessageInput（含 KnowledgeBaseSelector + ToolSelector 集成）/ChatWindow（含知识库选择 + 工具选择 + HITL 人机交互状态管理）/SessionList/NavBar/ConfirmCard（HITL 确认卡片，Task-09 新增）
        │   ├── 知识库组件                 # KnowledgeBasePage/KnowledgeBaseList/CreateKnowledgeBaseDialog/DocumentList（含状态轮询）/DocumentUploader/KnowledgeBaseSelector
        │   ├── 工具选择组件（CR 新增）    # ToolSelector（工具标签栏 + 下拉选择，位于输入框上方）/ ToolManagementPage（设置页工具管理）
        │   ├── LLM 配置组件               # LlmConfigPage/VendorCard/VendorEditDialog/ModelSelector
        │   ├── MCP 服务组件               # SettingsPage（标签页容器：LLM 配置 + MCP 服务 + 工具管理）/McpServicePage/McpServerCard/McpJsonConfigEditor（Task-05~08 新增）
        │   └── 编排组件（应用编排层新增）  # WorkflowPage（视图切换+恢复跳转）/WorkflowTemplateList（模板卡片）/WorkflowExecuteView（参数表单+步骤面板+暂停banner+HITL等待横幅+Supervisor子任务卡片）/SequentialExecuteView/ParallelExecuteView/LoopExecuteView/ConditionalExecuteView/SupervisorExecuteView（5 模式专属视图，均含 HITL 等待横幅，Task-12+BUG-20260825）/WorkflowHistoryList（历史+PAUSED恢复入口）/AskUserCard/ConfirmCard（HITL 交互卡片复用）
        ├── styles/global.css             # 全局样式系统（Refined Dark Tech）
        └── App.vue                       # 根组件（NavBar + 条件渲染切换对话/知识库/设置页面）
```

### 4.2 模块依赖方向

| 模块 | 依赖方向 |
|------|---------|
| `agent-demo-bom` | 无（独立存在，不继承根 pom） |
| `agent-demo-common` | 无 |
| `agent-demo-llm` | common |
| `agent-demo-tools` | common |
| `agent-demo-memory` | common, llm |
| `agent-demo-rag` | common, llm, splitter, tools（CR-003 新增） |
| `agent-demo-splitter` | common |
| `agent-demo-mcp` | common, tools |
| `agent-demo-skill` | common, tools（20260826 新增） |
| `agent-demo-observability` | common + OTel SDK（20260829 新增，叶子模块）；被 llm/tools/web + rag/memory/app/mcp/skill（CR-001）依赖 |
| `agent-demo-evaluation` | common, observability, agent, llm, tools（20260831 CR-002 新增，评估叶子模块，不参与对话主链路装配） |
| `agent-demo-agent` | common, llm, tools, memory, skill（20260826 +skill） |
| `agent-demo-app` | agent, rag, mcp（已实现：另依赖 common/llm/tools 经传递引入） |
| `agent-demo-web` | app, agent, memory, rag, mcp, skill（20260826 +skill） |
| `agent-demo-bootstrap` | web（聚合全部） |
| `agent-demo-frontend` | 独立运行，通过 HTTP 调用后端 API（无 Maven 依赖） |

### 4.3 核心模块内部分层

**agent-demo-agent**（Agent 核心）：

```
agent-demo-agent/
├── config/                # AgentConfig（配置属性绑定，含 defaultRole + 提示词默认值作为模板回退）
├── core/                  # BaseAgent（Agent 抽象接口）+ ThinkingTokenStream（思考流式接口）+ TaskBreakdownStream（任务拆解三阶段编排流）
│                          # + HitlTokenStream（HITL 流式接口，继承 ThinkingTokenStream 新增 onAskUser 回调，Task-02 新增）
│                          # + HumanInteractionManager（HITL pending 状态管理：ConcurrentHashMap 按 sessionId 存储 + @Scheduled 超时清理）
│                          # + PendingInteraction（暂停交互状态数据结构：消息列表/问题数据/追问计数/模型与工具信息）
├── prompt/                # PromptTemplateLoader（角色×场景模板加载器，从 classpath 加载 prompts/roles/ + prompts/scenarios/ 并组合系统提示词；SCENARIO_HITL="hitl"；
│                          #   CR-001 新增片段加载机制：{{include:fragment-name}} 单层展开 prompts/fragments/ 片段，缺失保留占位符降级不中断）
└── single/                # SimpleAgent（单 Agent 实现，工具按需加载 CR 新增 sessionToolIds 会话缓存 + toolsFingerprint 缓存键）
                            # + PlanAgent（任务拆解 Agent，创建 TaskBreakdownStream）
                            # + HITLReActStream（HITL 显式 ReAct 循环：工具执行前检测 askUser 拦截 -> 暂停保存状态 -> resume 恢复，Task-02 新增）
```

**agent-demo-llm**（LLM 接入，CR-003 重构为动态配置模式，移除 Provider 模式）：

```
agent-demo-llm/
├── config/                # 配置层：实体 + 内存存储 + 预定义目录
│                          # - LlmConfigStore（内存配置存储，@Component，ConcurrentHashMap 承载）
│                          # - LlmVendorConfig（厂商配置实体，含 baseUrl/apiKey/models 等）
│                          # - LlmModelConfig（模型配置实体，含 modelName/type/supportsVision 等）
│                          # - PredefinedVendorCatalog（预定义厂商目录，5 个内置厂商）
│                          # - PredefinedVendor / PredefinedModel（预定义厂商/模型类）
├── registry/              # 编排层：ModelFactory（从 LlmConfigStore 读取配置，按 vendorId:modelName 缓存）
│                          # - ModelFactory（注入 LlmConfigStore，无厂商硬编码，按 modelId 路由）
├── thinking/              # 思考流式模型层（保留 CR-002 架构不变）
│                          # - ThinkingStreamingChatModel（核心接口）
│                          # - AbstractThinkingStreamingChatModel（模板方法基类）
│                          # - ArkThinkingStreamingChatModel（thinkingTrigger=enabled）
│                          # - BailianThinkingStreamingChatModel（thinkingTrigger=none）
│                          # - ThinkingStreamHandler（回调接口）/ ToolCall（数据结构）
├── exception/             # 异常层（保留）
│                          # - UnsupportedCapabilityException（能力不存在异常）
```

**agent-demo-tools**（工具系统）：

```
agent-demo-tools/
├── builtin/               # 内置工具（Calculator/Time/Http/FileRead + AskUserTool，Task-04 新增：@Component + @Tool 占位实现，
                           #   方法体不执行，由 HITLReActStream 按工具名拦截触发暂停流程）
└── registry/              # ToolRegistry（注册中心，含动态 register/unregisterTool/getToolCount，CR-003 扩展；
                           #   工具按需加载 CR 新增 resolveTools/getAvailableTools/getDefaultTools/register(tool,serverName)；
                           #   Task-05 BUG 修复：getDefaultTools 用 LinkedHashSet 按对象去重，避免 TimeTool 多方法重复注册）
                           # + ToolSchemaConverter（Schema/描述转换，含 convertToDescriptionText 动态工具描述生成；
                           #   Task-05 BUG 修复：mapJavaTypeToJsonType 增加 String[]/List 映射为 array；
                           #   CR-001 移除 convertToDescriptionText 尾部通用引导——工具描述仅输出清单本体，调用引导由场景模板承载）
└── sanitize/              # 工具产出安全清洗（20260826 迭代，CR-004 SPI 化）：
                           #   ToolOutputSanitizer（编排器：SanitizeStage 有序链 + 终段限长/包裹 + 双层降级）+
                           #   SanitizeStage（阶段 SPI：order/name/appliesTo/process）+ 4 变换段
                           #   （InvisibleCharCleaner⓪/HtmlContentCleaner①/SuspiciousPatternDetector②/SecretRedactor②'，
                           #   规则启动预编译 + RULE_SKIPPED）+ ToolOutputTempStore/TempFileRecord（临时文件）+
                           #   SanitizeContext + ToolSanitizeProperties（agent.tool.sanitize.* 配置）
```

**agent-demo-memory**（记忆系统）：

```
agent-demo-memory/
├── longterm/              # 长期记忆（EmptyLongTermMemory 占位）
├── session/               # 会话管理（SessionManager/Metadata）
├── shortterm/             # 短期记忆（ChatMemoryManager）
└── store/                 # 记忆存储（MemoryRepository + InMemory 实现）
```

**agent-demo-rag**（RAG 知识库）：

```
agent-demo-rag/
├── config/                # RagProperties（配置属性绑定）+ RagAsyncConfig（异步线程池 @EnableAsync）
├── entity/                # DocumentStatus / KnowledgeBase / DocumentInfo / DocumentChunk（含 tokenCount + metadata 字段，CR-002 新增 metadata）
├── store/                 # KnowledgeBaseStore + InMemoryKnowledgeBaseStore（知识库元数据）
│                          # DocumentStore + InMemoryDocumentStore（文档元数据）
│                          # EmbeddingStoreFactory（向量存储工厂，可切换 InMemory/Milvus）
├── service/               # KnowledgeBaseService（创建/列表/级联删除）+ DocumentService（上传/@Async处理/状态/删除，调用 DocumentSplitterRegistry 分割，CR-002 新增 metadata 提取存入 DocumentChunk）
└── retriever/             # KnowledgeRetrieverTool（CR-003 后为核心检索逻辑，原 @Tool 入口废弃；CR-002 新增来源元数据注入检索结果）
                           # KnowledgeBaseToolFactory（CR-003 新增：ByteBuddy 动态生成带 @Tool 注解的知识库工具类）
                           # KnowledgeBaseToolRegistrar（CR-003 新增：ApplicationRunner 启动批量注册 + create/delete 生命周期联动）
```

**agent-demo-splitter**（文档分割）：

```
agent-demo-splitter/
├── config/                # SplitterProperties（按文件类型配置 size/overlap/minSize，前缀 rag.splitter）
├── loader/                # DocumentLoader（文档解析：txt/md/pdf，PDFBox 3.x，返回 ParsedDocument）
│                          # ParsedDocument / DocumentSection（解析结果数据结构，PDF 按页提取）
├── splitter/              # TypedDocumentSplitter（分割器接口）
│                          # DocumentSplitterRegistry（按格式路由 + 回退通用分割器 + 注入 fileName 元数据 CR-002）
│                          # MarkdownDocumentSplitter（MD 专属：commonmark-java AST 按标题分割 + 代码块/表格原子保护）
│                          # PdfDocumentSplitter（PDF 专属：按页分割 + 页码元数据）
│                          # TxtDocumentSplitter（TXT 专属：多级递归切分）
│                          # GenericDocumentSplitter（通用回退分割器）
│                          # util/CascadeSplitter（多级级联切分：段落->句子->行->Token滑动窗口，仅切分不合并）
│                          # util/ChunkMerger（分割后合并过短块：全局合并 + 按 metadata key 分组合并，CR-001 新增）
└── tokenizer/             # SplitterTokenEstimator（分割用 Token 估算器，委托 SimpleTokenEstimator）
```

**agent-demo-mcp**（MCP 协议）：

```
agent-demo-mcp/
├── config/                # McpProperties（@ConfigurationProperties(prefix="mcp")，含 ServerConfig 内部类）
├── entity/                # McpServer（Server 元数据+运行时状态）/ McpServerStatus（4 状态枚举）
│                          # McpTransportType（3 传输方式枚举：STDIO/SSE/HTTP）/ McpToolInfo（工具元数据）
├── client/                # McpTransportFactory（传输工厂：按 transport 创建 Stdio/Http/StreamableHttp 传输；含 Windows 命令适配 resolveWindowsCommand，npx 自动补全为 npx.cmd，BUG-20260811）
│                          # McpClientEntry（McpClient+Transport+状态聚合，AutoCloseable）
│                          # McpClientRegistry（Server 注册表，ConcurrentHashMap 按 name 索引）
├── tool/                  # McpToolFactory（ByteBuddy 生成 @Tool 代理类，含 parseParametersSchema 结构化参数描述）
│                          # McpToolInterceptor（ByteBuddy 方法拦截器，委托 McpToolExecutor）
│                          # McpContentParser（内容类型策略分发器：统一解析 MCP 协议 6 种内容类型 text/image/audio/resource/structuredContent/unknown，CR-002 新增）
│                          # McpToolExecutor（工具执行器：统一从 Wrapper 缓存通过 McpContentParser 解析，删除 extractResultText/extractFromRawResponse 双重路径，CR-002 重构）
│                          # McpToolRegistrar（接口）/ McpToolRegistrarImpl（启动加载器 ApplicationRunner + 生命周期管理）
└── service/               # McpServerManager（核心服务：CRUD + 连接 + 状态机 + serializeParameters 手动提取 JsonObjectSchema；连接失败消息含根因 rootCauseMessage，BUG-20260811）
```

**agent-demo-web**（Web 接口）：

```
agent-demo-web/
├── config/                # OpenApiConfig / TraceIdInterceptor / WebConfig
├── controller/            # AgentController（含工具按需加载 CR 新增 GET /api/agent/tools）/ McpController（MCP Server 管理 REST API）/ RagController
├── dto/                   # ChatRequest（含 tools 字段 CR 新增）/ ChatResponse / CreateMcpServerRequest / McpServerResponse / McpToolResponse / McpServerConfigValidator（MCP 配置条件校验）
└── handler/               # GlobalExceptionHandler
```

**agent-demo-common**（公共组件）：

```
agent-demo-common/
├── constant/              # ModelConstants / StatusCode
├── dto/                   # ToolInfo（工具信息 DTO，工具按需加载 CR 新增）
├── enums/                 # AgentType / MemoryType / MessageType
├── exception/             # BusinessException / ErrorCode
├── result/                # Result / PageResult
└── utils/                 # DateUtils / JsonUtils
```

**agent-demo-evaluation**（评估能力域，20260831 CR-002 新增）：

```
agent-demo-evaluation/
├── config/                # EvalProperties（eval.* 配置绑定）+ EvaluationHarnessConfig（eval.enabled 条件装配）
│                          # + LlmConfigSeed（CLI 独立上下文 LLM 配置种子：Agent 厂商 + 多源 judge 厂商注入，无模型/无 Key fail-fast）
├── cli/                   # EvaluationCli（CLI 入口：eval.enabled + run-on-startup 时执行一次评估）
├── model/                 # EvalCase/EvalDataset/ExecutionRecord（含 toolTrace 名序列 + toolTraceDetail 含结果）/CaseResult/AggregateResult
├── loader/                # EvalDatasetLoader（JSON 数据集解析，非法 JSON 明确异常）
├── runner/                # AgentInvoker（抽象）/ SimpleAgentInvoker（真实 Agent）/ EvaluationRunner（逐例执行 + Pass^runs + 失败继续）
│                          # + RecordingTraceCollector（TraceCollector 替身：录制工具轨迹名+结果）
├── eval/                  # DeterministicEvaluator（工具/关键词/脱敏/陷阱断言 + Pass^runs 聚合）
│                          # + JudgeEvaluator（judge 渲染/调用/解析 + 幻觉 veto 代码层强制 + 缺席降级）/ JudgeModelAccess/SpringJudgeModelAccess
│                          # + JudgePromptTemplate / JudgeVerdict / JudgeResult
│                          # + BaselineManager/BaselineEntry/MetricDelta/ComparisonReport（基线生成/加载/±30pp 对比）
│                          # + ABComparisonRunner/RunSpec/ABRow/ABReport（A/B 对比，Pass^3 强制）
├── harness/               # EvaluationHarness（端到端流水线编排）+ ReportWriter（Markdown 报告落盘，脱敏出口）
├── resources/prompts/     # judge.md（LLM-as-judge 评估指令制品 v2，EDD 调优）
└── resources/application.yml  # eval.* 配置段（默认全关）
```

### 4.4 包命名规范

```
com.agentdemo
├── common.{constant,enums,exception,result,utils}
├── llm.{config,capability,provider,thinking,registry,exception}    # CR-002 重构：从单一 factory 包拆为 6 个职责清晰的子包
├── tools.{builtin,registry}
├── memory.{longterm,session,shortterm,store}
├── mcp.{config,entity,client,tool,service}
├── agent.{config,core,single}
├── web.{config,controller,dto,handler}
└── AgentDemoApplication                 # 启动类位于 com.agentdemo 根包
```

> **数据来源**：`docs/ARCHITECTURE.md` 第四章、各模块源码扫描

---

## 五、业务域知识图谱

### 5.1 端到端对话流程

> **数据来源**：`specs/业务架构文档.md` 第 6.1 节、`AgentController.java`、`SimpleAgent.java`、`agent-demo-frontend/src/api/chat.ts`

```mermaid
flowchart TD
    subgraph 浏览器[浏览器]
        FE[Vue 3 前端] --> LS[(localStorage)]
        FE -->|POST /api/agent/chat/stream| SSE[SSE 流式接口]
    end
    subgraph 后端[后端]
        CTL[AgentController] --> SM{Session exists?}
        SM -->|No| CREATE[新建会话]
        SM -->|Yes| MM[ChatMemoryManager]
        CREATE --> MM
        MM --> AGT[SimpleAgent]
        AGT --> LLM[StreamingChatModel]
        LLM -->|TokenStream| AGT
        AGT -->|SSE events| CTL
        CTL -->|event:session/token/done| FE
    end
```

### 5.2 ReAct 循环机制

**核心机制**：LangChain4j AiServices 内置 ReAct 循环，无需手写。

```
用户输入 -> 构造 Prompt -> LLM 思考 -> 是否调用工具？
                                    ├─ 是 -> 执行工具 -> 结果回填 -> 回到 LLM 思考
                                    └─ 否 -> 生成最终回答 -> 返回
```

**关键约束**：
- 最大迭代次数：`agent.max-iterations=10`（防止无限循环消耗 Token）
- 工具调用决策由 LLM 自主完成（Function Calling）
- 工具结果自动回填到上下文，继续 LLM 思考

### 5.3 会话生命周期状态机

> **数据来源**：`specs/业务架构文档.md` 第 6.4 节、`SessionManager.java`

```mermaid
stateDiagram-v2
    [*] --> 活跃: createSession()
    活跃 --> 活跃: 更新活跃时间
    活跃 --> 超时: 30 分钟无活跃
    超时 --> 清理: 定时任务扫描（5min）
    清理 --> [*]
    活跃 --> 关闭: closeSession()
    关闭 --> [*]
```

**关键规则**：
- 会话 ID：UUID 去横线生成，全局唯一
- 超时时间：默认 30 分钟（`session.timeout-minutes`）
- 扫描频率：每 5 分钟（`@Scheduled(fixedRate=5*60*1000L)`）
- 无效 sessionId：自动新建会话，不抛错

 ### 5.4 记忆窗口淘汰策略

> **数据来源**：`CompressingChatMemory.java`、`ChatMemoryManager.java`、`specs/数据架构文档-TOGAF.md` 第 4.3 节（2026-08-28 更新：FIFO 硬淘汰 → 滚动摘要压缩）

```mermaid
flowchart LR
    A[新消息到达] --> B{消息数 >= 窗口20?}
    B -->|是| C{存在附件/系统消息?}
    C -->|是| D[剔除附件/系统后做 LLM 摘要]
    C -->|否| E[全量 LLM 摘要]
    D --> F[保留附件消息<br/>旧消息替换为摘要]
    E --> F
    F --> G[压缩至半窗 10<br/>滞回触发]
    B -->|否| H[追加新消息]
    G --> H
    H --> I[更新会话活跃时间]
```

**三级记忆架构**：

| 记忆类型 | 实现状态 | 存储方式 | 用途 |
|---------|---------|---------|------|
| 短期记忆 | ✅ 已实现 | 内存 CompressingChatMemory | 当前对话上下文（窗口 20，滚动摘要压缩至 10） |
| 中期记忆 | 🚧 规划中 | 内存/Redis | 历史对话摘要 |
| 长期记忆 | 🚧 规划中 | Milvus 向量 | 跨会话记忆检索 |

**滚动压缩语义（2026-08-28 agent-context-engineering 迭代）**：
- 触发：消息数 ≥ 窗口（20）时滚动触发，压缩至半窗（10），滞回避免频繁触发
- 摘要：LLM 生成滚动摘要，前置标记 `【历史对话摘要】`；实体/指代信息保留保证上下文连续（AC-M01）
- 附件保护：`【框架附件·TYPE】` 附件消息（CATALOG 目录/SKILL_INSTRUCTION 技能指令/STATUS 状态）永不参与压缩、永不淘汰
- 降级：摘要失败（LLM 异常）时降级为 FIFO 淘汰，不中断对话；`agent.memory-compression.enabled=false`（MemoryCompressionProperties）可回退纯 FIFO

### 5.5 工具调用决策流程

> **数据来源**：`specs/业务架构文档.md` 第 6.5 节、`HttpTool.java`、`FileReadTool.java`

```mermaid
flowchart TD
    A[LLM 生成思考] --> B{是否调用工具?}
    B -- 是 --> C[解析工具名与参数]
    C --> D{工具存在?}
    D -- 否 --> E[返回工具不存在错误]
    D -- 是 --> F{工具类型?}
    F -- HTTP --> G[SSRF 防护校验]
    G --> H{内网地址?}
    H -- 是 --> I[拒绝访问]
    H -- 否 --> J[执行 HTTP 请求]
    J --> K{响应 > 10KB?}
    K -- 是 --> L[截断响应]
    K -- 否 --> M[返回完整响应]
    F -- 文件 --> N[目录白名单校验]
    N --> O{在白名单?}
    O -- 否 --> P[拒绝读取]
    O -- 是 --> Q[读取文件]
    F -- 计算器/时间 --> R[直接执行]
    L --> S[结果回填给 LLM]
    M --> S
    Q --> S
    R --> S
    B -- 否 --> T[生成最终回复]
    S --> A
```

### 5.6 模型路由框架（CR-003 动态配置模式）

> **数据来源**：`ModelFactory.java`、`LlmConfigStore.java`（CR-003 动态配置模式）

#### 路由架构

```mermaid
flowchart LR
    Caller[调用方: Agent/RAG/Web] --> MF[ModelFactory]
    MF -->|modelId| Store[(LlmConfigStore)]
    Store -->|查找配置| Vendor[厂商配置]
    Vendor -->|baseUrl/apiKey| Models[OpenAI 兼容模型实例]
    MF -->|按 vendorId:modelName 缓存| Cache[ConcurrentHashMap 缓存]
    Caller -->|vendorId:modelName| Cache
```

- **路由方式（CR-003 重构）**：`ModelFactory` 注入 `LlmConfigStore`，调用方传入 `modelId`，ModelFactory 从 LlmConfigStore 查找模型配置，获取 `vendorId` 和 `modelName`，按 `vendorId:modelName` 缓存复用。无 modelId 时使用第一个可用 chat 模型。
- **扩展点**：用户通过前端页面动态添加厂商，无需修改任何后端代码
- **能力检测**：视觉模型通过 `LlmModelConfig.supportsVision` 属性标记，ModelFactory 遍历查找
- **缓存语义**：模型实例按 `vendorId:modelName` 缓存，配置变更时通过 `clearCacheForVendor/clearAllCache` 清除

#### 预定义厂商目录

| 厂商 | 类型 | 默认 Base URL | thinkingTrigger |
|------|------|------|------|
| 火山引擎方舟 | 预定义 | https://ark.cn-beijing.volces.com/api/coding/v3 | enabled |
| 阿里百炼 | 预定义 | https://dashscope.aliyuncs.com/compatible-mode/v1 | none |
| OpenAI | 预定义 | https://api.openai.com/v1 | none |
| DeepSeek | 预定义 | https://api.deepseek.com/v1 | none |
| Ollama (本地) | 预定义 | http://localhost:11434/v1 | none |

### 5.8 前端 SSE 流式对话流程

> **数据来源**：`AgentController.java`、`agent-demo-frontend/src/api/chat.ts`、`agent-demo-frontend/src/stores/session.ts`

```mermaid
sequenceDiagram
    participant U as 用户
    participant FE as 前端 Vue
    participant LS as localStorage
    participant CTL as AgentController
    participant SM as SessionManager
    participant AGT as SimpleAgent
    participant MM as ChatMemoryManager
    participant LLM as 火山引擎

    U->>FE: 输入消息，点击发送
    FE->>LS: 先存用户消息（乐观更新）
    FE->>CTL: POST /chat/stream (sessionId, message, enableThinking, knowledgeBases)
    CTL->>SM: exists(sessionId)?
    alt 会话不存在/超时
        SM->>CTL: 新建会话，返回新 sessionId
        CTL->>FE: SSE event: session(新sessionId)
        FE->>LS: 更新会话sessionId关联
    end
    CTL->>MM: addUserMessage(sessionId, message)
    alt enableThinking=true（CR-001 思考路径）
        CTL->>AGT: chatThinkingStream(sessionId, message)
        AGT->>LLM: 方舟 API（thinking.enabled）
        loop 推理+生成
            LLM-->>AGT: onPartialThinking(reasoning)
            AGT-->>CTL: thinking 回调
            CTL-->>FE: SSE event: reasoning(推理片段)
            FE->>FE: 追加到推理区块
            LLM-->>AGT: onPartialResponse(token)
            AGT-->>CTL: token 回调
            CTL-->>FE: SSE event: token(文本片段)
            FE->>FE: 追加到正式回复
        end
    else enableThinking=false/null（原路径）
        CTL->>AGT: chatStream(sessionId, message)
        AGT->>LLM: TokenStream.start()
        loop 逐字输出
            LLM-->>AGT: onPartialResponse(token)
            AGT-->>CTL: token 回调
            CTL-->>FE: SSE event: token(text)
            FE->>FE: 追加文本片段到对话框
        end
    end
    FE->>FE: 自动滚动到底部
    AGT-->>CTL: onCompleteResponse(response)
    CTL->>MM: addAssistantMessage(sessionId, fullText)
    CTL-->>FE: SSE event: done(duration)
    FE->>LS: 保存完整回复（含推理内容）
    FE->>FE: 恢复输入框，隐藏停止按钮
    opt 用户停止
        U->>FE: 点击"停止生成"
        FE->>FE: AbortController.abort()
        FE->>LS: 保存已接收的不完整回复
    end
```

**SSE 事件协议**：

| 事件名 | 数据 | 触发时机 |
|--------|------|---------|
| `session` | 新 sessionId 字符串 | 会话不存在/超时，新建后发送 |
| `reasoning` | 推理文本片段（CR-001 新增） | 每收到一段推理内容（仅 enableThinking=true 时） |
| `token` | 文本片段 | 每收到一个 LLM token |
| `ask_user` | JSON（type/question/options/retryCount，Task-07 新增） | HITL 模式下 Agent 调用 askUser 工具暂停执行时发送，前端据此渲染文本追问或确认卡片；工作流 HITL 场景复用（checkpoint 检查点 type=confirm） |
| `skill_activated` | JSON（skillId/skillName/source/boundToolIds，20260826 新增） | 技能激活成功时发送（source=manual 手动指定 / auto 自主匹配），前端记录会话级激活状态；技能展示由用户消息保留原始输入承载（CR-002，不再插入 AI 提示/页面徽标） |
| `done` | 耗时毫秒数 | 流式完整结束 |
| `error` | 错误描述 | 流式过程异常 |

**工作流 SSE 事件协议补充**（20260824 工作流 HITL 迭代）：

| 事件名 | 数据 | 触发时机 |
|--------|------|---------|
| `workflow_waiting` | JSON（executionId/agentIndex/agentName/hitlMode/resumable） | 工作流 Agent 调用 askUser 或到达 @HumanCheckpoint 检查点暂停，状态置 WAITING_USER，SSE 流随之关闭 |
| `workflow_resumed` | JSON（executionId/status=RUNNING） | 用户回复 hitl-reply 后工作流恢复执行，推送后继续新 SSE 流；存在并行排队 HITL 时先推送下一条 workflow_waiting |

**关键约束**：
- 前端使用 `fetch` + `ReadableStream` 手动解析 SSE（EventSource 不支持 POST）
- 使用 `AbortController` 实现停止生成（AC-011）
- 开启深度思考时，推理片段与正式回复片段分别通过 `reasoning` 和 `token` 事件推送（CR-001）
- localStorage 缓存上限 50 个会话，按最后活跃时间 FIFO 淘汰（AC-016）
- 会话标题取首条消息前 20 字符（AC-006）
- 知识库选择器（knowledgeBases 参数）按会话维度保持状态，空数组表示"自动"模式由 Agent 自主决策，非空时提示词注入引导 LLM 检索指定知识库

### 5.9 特殊业务机制

#### 5.9.1 懒加载机制

> **数据来源**：`SimpleAgent.java`、`ToolRegistry.java`、`ModelFactory.java`

| 对象 | 懒加载方式 | 原因 |
|------|---------|------|
| SimpleAgent.delegate | volatile + synchronized 双重检查锁 | 避免构造时调用 listTools() 触发循环依赖；CR-003 新增 lastToolCount 检测，Tool 数量变化后重建 delegate |
| ToolRegistry.scanned | volatile + synchronized 双重检查锁 | 避免 SimpleAgent 构造时触发 Tool 扫描循环依赖 |
| ModelFactory.embeddingModel | ~~volatile + synchronized 双重检查锁~~ **CR-002 已迁移**：缓存委托给 Provider 实例（ArkLlmServiceProvider / BailianLlmServiceProvider），Provider 为 Spring 单例，懒加载由各 Provider 内部实现 | 单例懒加载，首次使用时创建 |
| ChatMemoryManager.getMemory | computeIfAbsent | 会话记忆不存在时自动创建 |

#### 5.9.2 Agent 类型框架

> **数据来源**：`AgentType.java`

| 类型 | 含义 | 状态 |
|------|------|------|
| SINGLE | 单 Agent 独立完成任务 | ✅ 已实现 |
| MULTI | 多 Agent 角色协作（Sequential/Hierarchical） | 🚧 规划中 |
| WORKFLOW | 工作流编排（状态机 + HITL） | 🚧 规划中 |

---

## 六、核心开发范式

### 6.1 分层命名规范

> **数据来源**：`specs/SDD-项目技术指南文档.md` 第 3.7 节

| 类型 | 命名规范 | 示例 |
|------|---------|------|
| Controller | `{Domain}Controller` | `AgentController` |
| Service 接口 | `{Domain}Service`/`{Domain}Manager` | `SessionManager` |
| Service 实现 | `{Domain}ServiceImpl` / 委托模式 | `SimpleAgent` |
| 配置类 | `{Domain}Config` / `{Domain}Properties` | `AgentConfig` / `ArkProperties` |
| 工厂类 | `{Domain}Factory` | `ModelFactory` |
| 注册中心 | `{Domain}Registry` | `ToolRegistry` |
| 枚举 | `{Domain}{Type}Enum` | `AgentType`、`MemoryType` |
| 常量类 | `{Domain}Constants` | `ModelConstants` |
| DTO | `{Domain}{Action}Request`/`{Domain}{Action}Response` | `ChatRequest`/`ChatResponse` |
| 错误码 | 统一在 `ErrorCode` 枚举中定义 | `LLM_CALL_FAILED(5001)` |

### 6.2 Agent 开发范式

> **数据来源**：`SimpleAgent.java`、`BaseAgent.java`

**标准 Agent 实现模板**：

```java
// 1. 定义 Agent 接口（使用 LangChain4j 注解）
public interface BaseAgent {
    @MemoryId String sessionId,  // 会话隔离
    @UserMessage String message  // 用户消息
    String chat(...);
}

// 2. 实现 Agent（委托模式 + 懒加载 + Tool 变化重建）
@Service
public class SimpleAgent implements BaseAgent {
    private volatile BaseAgent delegate;  // 懒加载代理
    private volatile int lastToolCount = -1;  // CR-003: Tool 数量变化检测

    private BaseAgent getDelegate() {
        // CR-003: 若 Tool 数量变化（如知识库动态 Tool 增减），重建 delegate 绑定最新工具
        int currentToolCount = toolRegistry.getToolCount();
        if (delegate == null || currentToolCount != lastToolCount) {
            synchronized (this) {
                currentToolCount = toolRegistry.getToolCount();
                if (delegate == null || currentToolCount != lastToolCount) {
                    delegate = AiServices.builder(BaseAgent.class)
                            .chatModel(modelFactory.getDefaultChatModel())
                            .chatMemoryProvider(memoryId -> memoryManager.getMemory((String) memoryId))
                            .tools(toolRegistry.listTools().toArray())
                            .systemMessageProvider(memoryId -> agentConfig.getDefaultSystemPrompt())
                            .build();
                    lastToolCount = currentToolCount;
                }
            }
        }
        return delegate;
    }

    @Override
    public String chat(String sessionId, String message) {
        return getDelegate().chat(sessionId, message);
    }
}
```

### 6.3 工具开发范式

> **数据来源**：`HttpTool.java`、`CalculatorTool.java`、`TimeTool.java`

**标准工具模板**：

```java
@Component  // 必须：Spring Bean 自动扫描
public class XxxTool {

    @Tool("工具功能描述，Agent 通过此描述决定是否调用")  // 必须：LangChain4j 工具注解
    public String doSomething(String param) {
        // 1. 参数校验
        if (param == null || param.isEmpty()) {
            throw new BusinessException(ErrorCode.TOOL_PARAM_INVALID, "参数不能为空");
        }
        // 2. 安全防护（如 SSRF、目录白名单）
        validateSecurity(param);
        // 3. 执行业务逻辑
        try {
            String result = execute(param);
            // 4. 响应限制（如截断）
            return truncateIfNeeded(result);
        } catch (Exception e) {
            // 5. 异常封装为 BusinessException
            throw new BusinessException(ErrorCode.TOOL_EXECUTION_FAILED, "执行失败", e);
        }
    }
}
```

### 6.4 Controller 开发范式

> **数据来源**：`AgentController.java`

**标准 Controller 模板**：

```java
@Tag(name = "模块名", description = "模块描述")
@RestController
@RequestMapping("/api/{module}")
public class XxxController {

    private final XxxService xxxService;

    // 构造器注入（禁止 @Autowired 字段注入）
    public XxxController(XxxService xxxService) {
        this.xxxService = xxxService;
    }

    @Operation(summary = "接口摘要", description = "接口详细描述")
    @PostMapping("/action")
    public Result<XxxResponse> action(@Valid @RequestBody XxxRequest request) {
        XxxResponse response = xxxService.doAction(request);
        return Result.success(response);
    }
}
```

### 6.5 配置属性绑定范式

> **数据来源**：`ArkProperties.java`、`AgentConfig.java`

```java
@Data
@ConfigurationProperties(prefix = "ark.coding-plan")
public class ArkProperties {
    private String baseUrl = "https://ark.cn-beijing.volces.com/api/coding/v3";
    private String apiKey;  // 环境变量注入
    private String defaultModel = "doubao-seed-2.0-code";
    private Map<String, String> models = new HashMap<>();
    private Duration timeout = Duration.ofSeconds(60);
    // ...
}
```

### 6.6 统一返回结果范式

> **数据来源**：`Result.java`

```java
// 成功返回
return Result.success(data);
return Result.success(data, "操作成功");
return Result.success();

// 失败返回
return Result.error(ErrorCode.LLM_CALL_FAILED);
return Result.error(ErrorCode.LLM_CALL_FAILED, "补充信息");
return Result.error(5001, "自定义消息");
```

**Result 结构**：

```json
{
  "success": true,
  "code": 200,
  "message": "成功",
  "data": {...},
  "traceId": "a1b2c3d4"
}
```

### 6.7 全局异常处理范式

> **数据来源**：`GlobalExceptionHandler.java`

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        return Result.error(e.getErrorCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        return Result.error(ErrorCode.PARAM_INVALID, message);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleUnknown(Exception e) {
        return Result.error(ErrorCode.SYSTEM_ERROR);
    }
}
```

> **数据来源**：各模块源码、`specs/SDD-项目技术指南文档.md` 第 4 节

---

## 七、数据库速查

### 7.1 当前存储形态

> **⚠️ 重要**：本项目当前**无传统关系数据库**，采用纯内存存储。以下为内存数据架构。

| 数据域 | 存储方式 | 数据量级 | 业务归属模块 |
|--------|---------|---------|------------|
| 会话数据域 | 内存 ConcurrentHashMap | 小（活跃会话数） | agent-demo-memory |
| 记忆数据域 | 内存 CompressingChatMemory | 小（20 条/会话 + 滚动摘要） | agent-demo-memory |
| 模型缓存域 | 内存 ConcurrentHashMap | 极小（按 modelName） | agent-demo-llm |
| 配置数据域 | application.yml + 环境变量 | 极小 | agent-demo-bootstrap |
| 工具数据域 | 内存 CopyOnWriteArrayList | 极小（工具数） | agent-demo-tools |
| 工作流数据域 | 内存 ConcurrentHashMap（执行实例/取消标记/恢复快照） | 小（并发执行数） | agent-demo-app |
| 日志数据域 | 文件 logs/agent-demo.log | 中 | 全局 |

### 7.2 核心内存数据结构

| 数据结构 | 类型 | 所属类 | 用途 |
|---------|------|--------|------|
| sessionMap | ConcurrentHashMap<String, SessionMetadata> | SessionManager | 会话管理 |
| memoryMap | ConcurrentHashMap<String, ChatMemory> | ChatMemoryManager | 记忆管理 |
| chatModelCache | ConcurrentHashMap<String, ChatModel> | ModelFactory | 对话模型缓存（CR-003 缓存由 ModelFactory 直接管理，key 为 vendorId:modelName） |
| streamingModelCache | ConcurrentHashMap<String, StreamingChatModel> | ModelFactory | 流式模型缓存（CR-003 缓存由 ModelFactory 直接管理，key 为 vendorId:modelName） |
| embeddingModelCache | ConcurrentHashMap<String, EmbeddingModel> | ModelFactory | Embedding 模型缓存（CR-003 新增，按 vendorId:modelName 缓存） |
| thinkingModelCache | ConcurrentHashMap<String, ThinkingStreamingChatModel> | ModelFactory | 思考模型缓存（CR-003 新增，按 vendorId:modelName 缓存） |
| vendorsStore | ConcurrentHashMap<String, LlmVendorConfig> | LlmConfigStore | 厂商配置存储（CR-003 新增，按 vendorId 索引） |
| tools | CopyOnWriteArrayList<Object> | ToolRegistry | 工具列表 |
| servers | ConcurrentHashMap<String, McpClientEntry> | McpClientRegistry | MCP Server 注册表（按 name 索引） |
| delegate | volatile BaseAgent | SimpleAgent | AiServices 代理 |
| executions | ConcurrentHashMap<String, WorkflowExecution> | WorkflowExecutionService | 工作流执行实例（按 executionId 索引，应用编排层新增） |
| cancelFlags | ConcurrentHashMap<String, AtomicBoolean> | WorkflowExecutionService | 执行取消标记（终止/超时/SSE 客户端断开时置位，应用编排层新增） |
| resumableStates | ConcurrentHashMap<String, ResumableExecutionState> | WorkflowExecutionService | 暂停恢复快照（PAUSED 断点 + WAITING_USER HITL：已完成步骤输出 + AgenticScope 状态 + 可选 hitlState，应用编排层 P3/工作流 HITL 新增） |
| pendingInteractions | ConcurrentHashMap<String, PendingInteraction> | HumanInteractionManager | HITL 暂停交互状态（按 sessionId 存储消息列表/问题数据/追问计数，Task-01 新增） |

### 7.3 规划数据库（未来接入）

| 数据库 | 版本 | 用途 | 部署方式 |
|--------|------|------|---------|
| Milvus | 2.4.3 | 向量数据库（RAG + 长期记忆） | Docker |
| MySQL | 8.x | 关系数据库（会话/记忆持久化） | 独立部署 |

### 7.4 标准建表模板（未来接入 MySQL 时遵循）

> **数据来源**：`specs/SDD-项目技术指南文档.md` 第 3.6 节

```sql
CREATE TABLE `agent_{name}` (
  `id`          BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  -- 业务字段 --
  `creator`     VARCHAR(64) DEFAULT '' COMMENT '创建者',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater`     VARCHAR(64) DEFAULT '' COMMENT '更新者',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`     BIT(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='{表注释}';
```

**建表约束**：
- 所有表必须包含 `deleted BIT(1) NOT NULL DEFAULT b'0'` 逻辑删除字段
- 所有表必须包含审计四字段 `creator`/`create_time`/`updater`/`update_time`
- 字段命名使用 snake_case
- 字符集统一 `utf8mb4`，排序规则 `utf8mb4_unicode_ci`
- 主键策略：`BIGINT NOT NULL AUTO_INCREMENT`
- 存储引擎使用 `InnoDB`
- 表间不使用数据库外键，关联关系通过应用层维护

> **数据来源**：`specs/数据架构文档-TOGAF.md`、项目无 `sql/` 目录

---

## 八、安全与权限体系

### 8.1 安全措施矩阵

> **数据来源**：`HttpTool.java`、`FileReadTool.java`、`GlobalExceptionHandler.java`、`TraceIdInterceptor.java`

| 安全场景 | 机制 | 实现类 |
|----------|------|--------|
| API Key 保护 | 前端配置 + 后端内存存储 + API 响应脱敏，禁止入库/日志 | LlmConfigStore / VendorResponse.maskApiKey |
| API Key 校验 | 保存前通过测试连接端点验证有效性（POST /api/llm/config/test），ModelFactory 创建模型时直接使用配置中的 API Key | LlmConfigController / ModelFactory |
| API Key 脱敏 | API 响应中 API Key 脱敏显示（maskApiKey 方法，保留后 4 位），前端展示已脱敏 | VendorResponse.maskApiKey() |
| HTTP 工具 SSRF 防护 | 禁止访问内网地址（10./172.16-31./192.168./127./localhost） | HttpTool.validateUrl() |
| HTTP 响应截断 | 超过 10KB 截断，防止 Token 消耗过大 | HttpTool.truncateResponse() |
| 文件读取目录限制 | `agent.file-allowed-dir` 白名单（默认 `./data`） | FileReadTool |
| 会话隔离 | 按 sessionId 隔离 ChatMemory | ChatMemoryManager |
| 全局异常处理 | `@RestControllerAdvice` 统一拦截，避免堆栈泄露 | GlobalExceptionHandler |
| traceId 链路追踪 | `TraceIdInterceptor` 自动注入 MDC | TraceIdInterceptor |
| 参数校验 | `@Valid` + Bean Validation（如 `@NotBlank`） | Controller |

### 8.2 SSRF 防护规则

> **数据来源**：`HttpTool.java` 第 33-38 行

**禁止访问的内网 IP 前缀**：

```java
private static final String[] PRIVATE_IP_PREFIXES = {
    "10.", "172.16.", "172.17.", "172.18.", "172.19.", "172.20.",
    "172.21.", "172.22.", "172.23.", "172.24.", "172.25.", "172.26.",
    "172.27.", "172.28.", "172.29.", "172.30.", "172.31.", "192.168.",
    "127.", "0.0.0.0", "localhost"
};
```

### 8.3 敏感字段清单

| 实体 | 字段 | 保护方式 | 说明 |
|------|------|---------|------|
| ArkProperties | apiKey | 环境变量注入 | 禁止入库/日志 |
| BailianProperties | apiKey | 环境变量注入 | 禁止入库/日志 |
| ChatModel | apiKey | 环境变量注入 | 禁止入库/日志 |
| ChatRequest | message | 日志脱敏（截断） | 不打印完整明文 |
| SessionMetadata | userId | - | 用户标识 |

### 8.4 权限体系说明

> **⚠️ 注意**：本项目为学习示例工程，**未接入认证机制**（无 Spring Security/JWT/RBAC）。所有 API 可被任意调用。

生产环境接入时建议：
- 引入 Spring Security + JWT 认证
- 增加 RBAC 权限控制
- 接入 API 限流（如 Sentinel）

### 8.5 工具权限控制模型（v3.3 全域统一管控新增）

> **数据来源**：`specs/features/20260824_tool-permission-control/`（v3.2 基础）+ `specs/features/20260825_tool-permission-unification/`（v3.3 全域统一：双闸门 + 能力声明双方法 + 出口统一包装 + 工作流 tool_confirm 恢复，21 个 Task 全部完成）

**三级权限模型（allow / ask / deny）**，实现"加载期过滤 + 执行期管控"双重控制：

| 权限等级 | 语义 | 加载期 | 执行期 |
|---------|------|--------|--------|
| allow（放行） | 只读安全工具（计算器/时间/知识库检索） | 所有路径注入 | LLM 决策即执行，结果回填续跑 |
| ask（需确认） | 有副作用工具（HTTP/文件读取/MCP 工具） | 仅流式/工作流 HITL 路径注入（同步路径与工作流非 HITL 无暂停能力，加载期剔除） | 拦截 → 暂停保存状态 → SSE `tool_confirm` 确认卡片 → 批准执行 / 拒绝结果回填 LLM 换方案 |
| deny（禁止） | 管理员手动封禁（无默认值） | 全路径不注入（LLM 不可见） | 包装层拦截 + 执行器兜底，方法体不触发，返回权限拒绝提示 |

**全域统一模型（v3.3 新增）**：无论哪一个模块（单 Agent 同步/流式、工作流 HITL/非 HITL）使用工具模板，都必须收到工具域权限的统一管控，权限判定规则全局唯一、不因调用方模块不同而有差异：
- **能力声明双方法**：`ToolRegistry.resolveToolsForStreaming`（ask 可见，调用方具备暂停-恢复能力）与 `resolveToolsForDirect`（ask 剔除，无暂停能力），替代原 resolveTools(单参)；deny 在两方法内均全域剔除（AC-T01）
- **出口统一包装**：ToolRegistry 出口经 `ToolPermissionGuard.wrapAll` 统一包装（ByteBuddy subclass + defineMethod 覆写 @Tool 方法），包装实例按（工具对象, 权限版本）二级缓存，权限等级在包装时捕获（AC-M01 不中途突变）；包装失败降级返回原工具（加载期过滤仍生效）
- **双闸门**：
  1. **加载期闸门**（ToolRegistry 双方法）：deny 全域剔除；ask 按调用方能力（ForStreaming 可见 / ForDirect 不可见）
  2. **执行期闸门**（两条入口全覆盖）：LangChain4j 反射直调路径命中包装层拦截器（deny 方法体零触发）；手动执行路径（HITLReActStream）走 `ToolExecutor.checkPermission/execute`
- **四条解析路径全域统一**：单 Agent 同步（SessionToolResolver→ForDirect）、单 Agent 流式（askSupported=true→ForStreaming）、工作流非 HITL（AgenticAgentFactory.buildAgent→ForDirect）、工作流 HITL（AgentExecutor.resolveHitlTools→ForStreaming + ensureAskUserTool）；调用方只声明能力（ask 可见/剔除）不选权限，权限判定与配置完全归属工具域

**核心机制**：
- **默认分级**：`@DefaultPermission` 注解标注内置工具默认等级；动态注册工具按类别分级（rag→allow / mcp→ask）；`askUser` 工具固定豁免为 allow（防确认流程自身死锁，AC-S03）
- **持久化**：`data/tool-permissions.json`（`tools.permission.file-path`，`tools.permission.enabled=true` 回滚开关），服务重启不丢失；管理页变更无需重启即时生效，下一轮对话生效（本轮会话沿用已加载工具列表，避免中途突变）
- **配置防篡改**：权限配置仅经管理页（PUT /api/agent/tools/{toolId}/permission）变更，对话内容（含提示注入）不可修改
- **权限清理**：知识库删除/MCP Server 断开时对应权限配置同步清理，不残留孤儿配置（AC-E01）
- **ask 恢复链路**：单 Agent 经 HumanInteractionManager 快照（mode=tool_confirm + pendingToolCall 三字段）+ `ChatRequest.toolApproved` 静默恢复（silent 模式，不产生对话气泡）；工作流经 `WorkflowHITLState`（MODE_TOOL_CONFIRM + ToolConfirmData）+ `hitl-reply` 恢复（tool_confirm 双事件），拒绝语义=拒绝续跑（回填脱敏文案 LLM 换方案），区别于 checkpoint 拒绝即终止（决策 7）

---

## 九、关键业务规则清单

> **数据来源**：`specs/SDD-工程业务背景文档.md` 第 5 节、各模块业务说明书、核心源码校验逻辑

### 9.1 LLM 接入规则

| # | 编号 | 规则 | 范围 | 级别 |
|---|------|------|------|------|
| 1 | BR-LLM-001 | API Key 通过前端配置页面动态管理，无需环境变量，禁止硬编码入库 | LLM 接入 | 🔴 强制 |
| 2 | BR-LLM-002 | 必须使用 Coding Plan 专用地址 `/api/coding/v3`（按次计费） | LLM 接入 | 🔴 强制 |
| 3 | BR-LLM-003 | 模型名称必须通过 `ModelConstants` 常量类引用 | LLM 接入 | 🔴 强制 |
| 4 | BR-LLM-004 | 模型实例必须通过 `ModelFactory` 获取并缓存复用（CR-003 重构：缓存由 ModelFactory 直接管理，按 vendorId:modelName 缓存，配置变更时清除对应缓存） | LLM 接入 | 🔴 强制 |
| 5 | BR-LLM-005 | 调用超时时间默认 60s | LLM 接入 | ⚪ 可覆盖 |
| 6 | BR-LLM-006 | 最大重试次数默认 3 次 | LLM 接入 | ⚪ 可覆盖 |
| 7 | BR-LLM-007 | 思考模式（thinking.enabled）必须通过自定义 ArkThinkingStreamingChatModel 直连方舟 API，不走 LangChain4j openai4j（因 openai4j 不透传 reasoning_content） | LLM 接入 | 🔴 强制 |
| 8 | BR-LLM-008 | ~~LLM 提供商通过 `llm.provider` 配置项切换~~ **CR-003 已移除**：`llm.provider` 配置项已删除，LLM 模型通过前端页面动态配置，后端 LlmConfigStore 内存存储 | LLM 接入 | 🔴 强制 |
| 9 | BR-LLM-009 | API Key 通过前端配置页面动态管理，无需环境变量，禁止硬编码入库 | LLM 接入 | 🔴 强制 |
| 10 | BR-LLM-010 | 切换提供商后只校验当前提供商的 API Key，未激活的提供商不校验 | LLM 接入 | 🔴 强制 |
| 11 | BR-LLM-011 | 阿里百炼必须使用 OpenAI 兼容协议地址 `/compatible-mode/v1` | LLM 接入 | 🔴 强制 |
| 12 | BR-LLM-012 | ~~阿里百炼模式暂不支持深度思考~~ **CR-002 已修正**：阿里百炼通过 `BailianThinkingStreamingChatModel` 支持深度思考（继承 AbstractThinkingStreamingChatModel，模型名称自身触发思考能力） | LLM 接入 | 🔴 强制 |
| 13 | BR-LLM-013 | Embedding 模型跟随提供商切换：ARK 使用 `doubao-embedding-vision`，BAILIAN 使用 `text-embedding-v4` | LLM 接入 | 🔴 强制 |
| 14 | BR-LLM-016 | `ArkThinkingStreamingChatModel` 与 `BailianThinkingStreamingChatModel` 代码重复率必须 ≤ 30%，通过继承 `AbstractThinkingStreamingChatModel` 实现（CR-002 新增，对应 AC-020，jscpd 检测） | LLM 接入 | 🔴 强制 |
| 15 | BR-LLM-017 | 厂商未实现的能力接口在运行时必须抛出 `UnsupportedCapabilityException`，禁止隐式失败（CR-002 新增，对应 AC-021） | LLM 接入 | 🔴 强制 |
| 16 | BR-LLM-018 | `LlmServiceProvider` 接口必须按 ISP 原则拆分为多能力接口（`ChatModelProvider`、`StreamingChatModelProvider`、`ThinkingStreamingChatModelProvider`、`EmbeddingModelProvider`、`VisionChatModelProvider`），`VisionChatModelProvider` 为可选能力接口不强制聚合，厂商按需 `implements`（CR-002 新增） | LLM 接入 | 🔴 强制 |

### 9.2 Agent 编排规则

| # | 编号 | 规则 | 范围 | 级别 |
|---|------|------|------|------|
| 7 | BR-AGT-001 | 所有 Agent 实现必须实现 `BaseAgent` 接口 | Agent 编排 | 🔴 强制 |
| 8 | BR-AGT-002 | ReAct 循环最大迭代次数默认 10 | Agent 编排 | 🔴 强制 |
| 9 | BR-AGT-003 | Agent delegate 必须懒加载，避免构造时触发循环依赖；Tool 数量变化时必须重建 delegate 绑定最新工具列表（CR-003 新增） | Agent 编排 | 🔴 强制 |
| 10 | BR-AGT-004 | 会话记忆按 sessionId 隔离，禁止跨会话读取记忆 | Agent 编排 | 🔴 强制 |
| 11 | BR-AGT-005 | 系统提示词通过 `PromptTemplateLoader.composeSystemPrompt(role, scenario)` 动态组合（角色模板 + "\n\n" + 场景模板），`systemMessageProvider` 调用此方法 | Agent 编排 | 🔴 强制 |
| 12 | BR-AGT-006 | Agent 调用日志默认开启 | Agent 编排 | ⚪ 可覆盖 |
| 13 | BR-AGT-007 | 思考模式使用场景模板 "thinking"（声明"此模式不可调用工具"）；正常模式使用场景模板 "chat"（含工具引导语） | Agent 编排 | 🔴 强制 |
| 14 | BR-THINK-002 | ReAct 模式使用场景模板 "react"（含 ReAct 格式引导 + {{tools}} 占位符），工具描述通过 `convertToDescriptionText()` 在运行时替换占位符，不硬编码在提示词中 | Agent 编排 | 🔴 强制 |
| 15 | BR-AGT-008 | 系统提示词外部化为模板文件（`prompts/roles/*.txt` + `prompts/scenarios/*.txt`），AgentConfig 中的旧提示词默认值仅作为模板缺失时的最终回退 | Agent 编排 | 🔴 强制 |
| 16 | BR-AGT-009 | `{{tools}}` 占位符仅出现在 react 和 task-execute 场景模板中，由调用方通过 `String.replace("{{tools}}", convertToDescriptionText())` 在运行时替换 | Agent 编排 | 🔴 强制 |
| 17 | BR-AGT-011 | Agent delegate 缓存键为 modelId + toolsFingerprint，不同工具集使用独立 delegate；工具按需加载时默认工具不可排除（默认 ∪ 指定）（工具按需加载 CR 新增） | Agent 编排 | 🔴 强制 |
| 18 | BR-AGT-012 | 会话级工具绑定按 sessionId 缓存（sessionToolIds），首次指定后后续轮次沿用；空数组清除恢复默认（工具按需加载 CR 新增） | Agent 编排 | 🔴 强制 |
| 19 | BR-AGT-016 | 工具协议以 tools 参数为权威来源：`{{tools}}` 由会话基础工具集（SessionToolResolver.resolveSessionBaseTools，不含技能脚本工具）确定性生成，会话内冻结不变（20260828 新增） | Agent 编排 | 🔴 强制 |
| 20 | BR-AGT-017 | 技能激活段从系统提示词移除，改为 SKILL_INSTRUCTION 附件注入记忆流（激活点单通道、流式/同步双路径覆盖）；技能激活不改变系统提示词，保证前缀缓存稳定（20260828 新增） | Agent 编排 | 🔴 强制 |
| 21 | BR-AGT-018 | HITL 末轮强制总结前必须注入 `<agent_status>` 收尾消息（user 角色，读数+操作策略成对，显示配置上限），仅框架代码可写，模型/用户禁止伪造（20260828 新增） | Agent 编排 | 🔴 强制 |
| 22 | BR-AGT-019 | 规划判断（TaskPlanJudge.judge）必须携带会话历史 recentHistory 参与意图判别，缺失时降级为默认规划模式不抛错（20260828 新增） | Agent 编排 | 🔴 强制 |
| 23 | BR-AGT-020 | 模板公共规则单源化：hitl.txt/hitl-guidance.txt 公共规则经 `{{include:hitl-shared-rules}}` 共享片段引用（PromptTemplateLoader 片段加载，单层展开、片段缺失保留占位符降级不中断）（CR-001 新增） | Agent 编排 | 🔴 强制 |
| 24 | BR-AGT-021 | 场景模板采用 XML 语义标签段（`<guardrails>`/`<scenario_behavior>`/`<interaction_rules>` 等），护栏条文内容零变更，仅结构标签包裹（CR-001 新增） | Agent 编排 | 🔴 强制 |
| 25 | BR-AGT-022 | 工具引导语单源：convertToDescriptionText 仅输出工具清单本体（不含尾部通用引导），工具调用引导由场景模板差异化承载（CR-001 新增） | Agent 编排 | 🔴 强制 |
| 26 | BR-AGT-023 | AgentConfig 降级默认值与场景模板语义一致（模板缺失降级质量不骤降），由一致性守护测试双向锚点防漂移（CR-001 新增） | Agent 编排 | 🔴 强制 |

### 9.3 工具调用规则

| # | 编号 | 规则 | 范围 | 级别 |
|---|------|------|------|------|
| 13 | BR-TOOL-001 | 工具类必须加 `@Component`，工具方法必须加 `@Tool` 注解 | 工具调用 | 🔴 强制 |
| 14 | BR-TOOL-002 | 工具注册采用懒加载，首次调用 `listTools()` 时扫描 | 工具调用 | 🔴 强制 |
| 15 | BR-TOOL-003 | HTTP 工具必须执行 SSRF 防护，禁止访问内网地址 | 工具调用 | 🔴 强制 |
| 16 | BR-TOOL-004 | 工具产出统一限长：清洗后超过 `agent.tool.sanitize.max-chars`（默认 4000 字符）进入"前缀 + 临时文件分段查询"机制（清洗管道③统一承担，取代原 HTTP 10KB 截断；20260826 CR-001 防腐更新） | 工具调用 | 🔴 强制 |
| 17 | BR-TOOL-005 | 文件读取工具必须限定在 `agent.file-allowed-dir` 目录白名单内 | 工具调用 | 🔴 强制 |
| 18 | BR-TOOL-006 | 工具执行失败必须抛出 `BusinessException` + 对应 ErrorCode | 工具调用 | 🔴 强制 |
| 19 | BR-TOOL-007 | 动态注册/注销工具应通过 `ToolRegistry.register()` / `unregisterTool()` 操作（CR-003 新增注销方法） | 工具调用 | 🟡 尽量 |
| 20 | BR-TOOL-010 | 默认工具不可通过 API 排除，最终工具 = 默认 ∪ 指定（工具按需加载 CR 新增） | 工具调用 | 🔴 强制 |
| 21 | BR-TOOL-011 | 工具标识格式为 `category:name`，category 取值：`builtin`/`mcp`/`rag`（工具按需加载 CR 新增） | 工具调用 | 🔴 强制 |
| 22 | BR-TOOL-012 | `name` 支持通配符 `*`，表示该类别下所有工具（工具按需加载 CR 新增） | 工具调用 | 🔴 强制 |
| 23 | BR-TOOL-013 | API 指定不存在的工具时，返回 `TOOL_NOT_FOUND(5101)`，提示具体工具名（工具按需加载 CR 新增） | 工具调用 | 🔴 强制 |
| 24 | BR-TOOL-014 | API 指定格式错误的工具标识时，返回 `TOOL_PARAM_INVALID(5102)`（工具按需加载 CR 新增） | 工具调用 | 🔴 强制 |
| 25 | BR-TOOL-015 | 默认工具配置了不存在的工具时，启动日志 ERROR 并跳过，不阻塞启动（工具按需加载 CR 新增） | 工具调用 | 🔴 强制 |
| 26 | BR-TOOL-016 | MCP Server 断开后其工具从可选列表移除，API 指定已断开的工具报 `TOOL_NOT_FOUND`（工具按需加载 CR 新增） | 工具调用 | 🔴 强制 |
| 27 | BR-TOOL-017 | 知识库删除后其工具从可选列表移除，API 指定已删除的知识库工具报 `TOOL_NOT_FOUND`（工具按需加载 CR 新增） | 工具调用 | 🔴 强制 |
| 28 | BR-TOOL-018 | 前端工具选择器按会话维度保持状态，切换会话互不影响（工具按需加载 CR 新增） | 工具调用 | 🔴 强制 |
| 29 | BR-TOOL-019 | ChatRequest.tools 为空或不传时，仅加载默认工具（工具按需加载 CR 新增） | 工具调用 | 🔴 强制 |

### 9.4 记忆与会话规则

| # | 编号 | 规则 | 范围 | 级别 |
|---|------|------|------|------|
| 20 | BR-MEM-001 | 会话 ID 必须使用 UUID 去横线生成，保证全局唯一 | 记忆管理 | 🔴 强制 |
| 21 | BR-MEM-002 | 会话超时默认 30 分钟，每 5 分钟扫描清理一次 | 记忆管理 | ⚪ 可覆盖 |
| 22 | BR-MEM-003 | 短期记忆窗口默认 20 条消息，超出自动淘汰旧消息（20260828 起：超出触发 LLM 滚动摘要压缩至 10，详见 §5.4） | 记忆管理 | ⚪ 可覆盖 |
| 23 | BR-MEM-004 | `ChatMemoryManager.getMemory()` 使用 `computeIfAbsent`，回调内禁止修改同一 map | 记忆管理 | 🔴 强制 |
| 24 | BR-MEM-005 | 传入无效 sessionId 时应自动新建会话，不应抛出错误 | 记忆管理 | 🔴 强制 |
| 25 | BR-MEM-006 | 记忆附件以 `【框架附件·TYPE】` 帧标记写入（CATALOG/SKILL_INSTRUCTION/STATUS），永不参与压缩与淘汰；状态附件仅框架代码可写（20260828 新增） | 记忆管理 | 🔴 强制 |
| 26 | BR-MEM-007 | 用户输入中的框架标记（附件/摘要前缀/`<agent_status>`）必须剥离，禁止用户伪造框架消息（20260828 新增） | 记忆管理 | 🔴 强制 |
| 27 | BR-MEM-008 | 对话请求记忆写入必须唯一化（effectiveMessage 单点写入），重复标记/无效消息去重，防止记忆膨胀（20260828 新增） | 记忆管理 | 🔴 强制 |

### 9.5 数据安全与合规规则

| # | 编号 | 规则 | 范围 | 级别 |
|---|------|------|------|------|
| 25 | BR-SEC-001 | API Key 禁止明文打印到日志 | 安全合规 | 🔴 强制 |
| 26 | BR-SEC-002 | HTTP 工具禁止访问内网地址 | 安全合规 | 🔴 强制 |
| 27 | BR-SEC-003 | 文件读取工具禁止读取白名单目录外的文件 | 安全合规 | 🔴 强制 |
| 28 | BR-SEC-004 | 生产环境应关闭 Swagger UI 访问 | 安全合规 | 🟡 尽量 |
| 29 | BR-SEC-005 | 日志中不应打印用户消息完整明文（可截断或脱敏） | 安全合规 | 🟢 建议 |

### 9.6 RAG 知识库规则

| # | 编号 | 规则 | 范围 | 级别 |
|---|------|------|------|------|
| 30 | BR-RAG-001 | 知识库名称长度 1-50 个字符，仅允许中英文、数字、下划线和连字符 | RAG 知识库 | 🔴 强制 |
| 31 | BR-RAG-002 | 知识库名称不允许重复（全局唯一） | RAG 知识库 | 🔴 强制 |
| 32 | BR-RAG-003 | 知识库描述长度不超过 200 个字符 | RAG 知识库 | 🔴 强制 |
| 33 | BR-RAG-004 | 单个文档大小不超过 10MB | RAG 知识库 | 🔴 强制 |
| 34 | BR-RAG-005 | 支持的文档格式：txt、md、pdf（第一期） | RAG 知识库 | ⚪ 可覆盖 |
| 35 | BR-RAG-006 | 同一知识库下允许同名文档，以文档 ID 区分 | RAG 知识库 | 🔴 强制 |
| 36 | BR-RAG-007 | 文档处理采用异步模式，状态流转：待处理 -> 处理中 -> 已完成/失败 | RAG 知识库 | 🔴 强制 |
| 37 | BR-RAG-008 | 检索结果最多返回 5 个最相关文档片段，按相似度降序排列 | RAG 知识库 | ⚪ 可覆盖 |
| 38 | BR-RAG-009 | 删除知识库时级联删除其下所有文档记录和向量数据 | RAG 知识库 | 🔴 强制 |
| 39 | BR-RAG-010 | 删除文档时同步删除该文档对应的所有向量数据 | RAG 知识库 | 🔴 强制 |
| 40 | BR-RAG-011 | 每个知识库独立注册为 @Tool（CR-003 动态 Tool 模式），Agent 通过 Function Calling 选择具体知识库工具，LLM 无需传递知识库名称参数 | RAG 知识库 | 🔴 强制 |
| 41 | BR-RAG-012 | 向量数据库不可用时检索工具返回错误提示，不导致 Agent 对话中断 | RAG 知识库 | 🔴 强制 |
| 42 | BR-RAG-013 | 文档向量化时 Embeddings API 单次输入上限为 10 个文本片段，超出时必须分批调用（batchEmbed，每批 10 条），避免 InvalidParameter 错误 | RAG 知识库 | 🔴 强制 |
| 43 | BR-RAG-014 | 对话知识库集成采用提示词注入方案：用户指定知识库时，将知识库名称注入用户消息末尾引导 LLM 检索；不指定时 Agent 自主决策（零回归） | RAG 知识库 | 🔴 强制 |
| 44 | BR-RAG-015 | 第一轮分割后对过短分块执行合并后处理：低于 minSize 的分块与同组（同页/同节/全局）相邻分块合并，合并后不超过 maxSize，最终结果中不存在低于 minSize 的碎片块（最后一个分块除外） | RAG 知识库 | 🔴 强制 |
| 45 | BR-RAG-016 | 合并时遵守结构边界约束：PDF 不跨页合并（按 pageNumber 分组），Markdown 不跨标题节合并（按 headerText 分组），TXT/Generic 全局合并 | RAG 知识库 | 🔴 强制 |
| 46 | BR-RAG-017 | 每种文件类型可独立配置 minSize 参数（最小分块大小），未配置时默认为 size 的 50% | RAG 知识库 | ⚪ 可覆盖 |
| 47 | BR-RAG-018 | PDF 文档解析支持表格结构提取（tabula-java），表格内容转换为 Markdown 格式保留行列关系，无表格区域回退纯文本提取（CR-001） | RAG 知识库 | 🔴 强制 |
| 48 | BR-RAG-019 | 分块数据携带来源元数据（fileName、format、pageNumber/headerText），检索结果中包含来源信息，Agent 回答时可引用文档来源（CR-002） | RAG 知识库 | 🔴 强制 |
| 49 | BR-RAG-020 | 系统启动时自动批量注册所有已有知识库的动态 Tool，确保 Agent 在首次对话前所有知识库工具已就绪（CR-003） | RAG 知识库 | 🔴 强制 |

### 9.7 MCP 协议规则

| # | 编号 | 规则 | 范围 | 级别 |
|---|------|------|------|------|
| 50 | BR-MCP-001 | MCP 模块总开关为 `mcp.enabled`（默认 true），false 时模块完全禁用，所有 REST API 返回 MCP_MODULE_DISABLED(5405) | MCP 协议 | 🔴 强制 |
| 51 | BR-MCP-002 | MCP Server 名称 1-50 字符，仅允许中英文、数字、下划线和连字符 | MCP 协议 | 🔴 强制 |
| 52 | BR-MCP-003 | MCP Server 名称全局唯一，重复返回 MCP_SERVER_NAME_EXISTS(5402) | MCP 协议 | 🔴 强制 |
| 53 | BR-MCP-004 | MCP Server 必须指定 transport，取值仅限 stdio / sse / http，其他值返回 MCP_TRANSPORT_UNSUPPORTED(5404) | MCP 协议 | 🔴 强制 |
| 54 | BR-MCP-005 | stdio 传输必须配置 command 字段，args 和 env 可选 | MCP 协议 | 🔴 强制 |
| 55 | BR-MCP-006 | sse/http 传输必须配置 url 字段（合法 HTTP/HTTPS URL），headers 可选 | MCP 协议 | 🔴 强制 |
| 56 | BR-MCP-007 | MCP 工具名采用 `mcp_{serverName}_{toolName}` 前缀注册到 ToolRegistry，保证全局唯一 | MCP 协议 | 🔴 强制 |
| 57 | BR-MCP-008 | MCP 工具调用超时默认 60s，可被单 Server 的 tool-timeout 覆盖 | MCP 协议 | ⚪ 可覆盖 |
| 58 | BR-MCP-009 | 静态 Server 启动失败记录 ERROR 日志并跳过，不阻塞应用启动 | MCP 协议 | 🔴 强制 |
| 59 | BR-MCP-010 | 动态添加 Server 连接失败返回 MCP_CONNECTION_FAILED(5400)，不污染 ToolRegistry | MCP 协议 | 🔴 强制 |
| 60 | BR-MCP-011 | MCP Server 断线时自动注销其所有工具，触发 SimpleAgent delegate 重建 | MCP 协议 | 🔴 强制 |
| 61 | BR-MCP-012 | Server 状态枚举：CONNECTED / DISCONNECTED / ERROR / DISABLED | MCP 协议 | 🔴 强制 |
| 62 | BR-MCP-013 | 动态添加的 Server 仅存内存，重启后丢失（不持久化） | MCP 协议 | 🔴 强制 |
| 63 | BR-MCP-014 | Server enabled=false 时跳过连接，标记 DISABLED，可通过重连接口启用 | MCP 协议 | 🔴 强制 |
| 64 | BR-MCP-015 | 重连已 CONNECTED 状态的 Server 返回 MCP_SERVER_ALREADY_CONNECTED(5406) | MCP 协议 | 🔴 强制 |
| 65 | BR-MCP-016 | MCP 工具参数 Schema 必须手动提取 JsonObjectSchema properties 序列化，禁止直接用 Jackson 序列化 JsonSchemaElement 多态接口 | MCP 协议 | 🔴 强制 |
| 66 | BR-MCP-017 | MCP 工具描述必须包含结构化参数列表和调用示例（parseParametersSchema 生成），帮助 LLM 正确理解参数名 | MCP 协议 | 🔴 强制 |
| 67 | BR-MCP-018 | McpClient 创建时必须配置 initializationTimeout（默认 60s），确保 stdio 模式下 npx 首次下载不超时 | MCP 协议 | 🔴 强制 |
| 68 | BR-MCP-019 | ~~McpToolExecutor 必须捕获 LangChain4j ToolExecutionHelper 的 Unsupported content type 异常，返回友好提示~~ **CR-002 更新**：McpToolExecutor 必须将 Unsupported content type 异常视为预期行为（非文本内容正常返回），继续到统一解析路径，不作为错误处理 | MCP 协议 | 🔴 强制 |
| 69 | BR-MCP-023 | MCP 工具输出统一从 McpTransportWrapper 缓存的原始 JSON-RPC 响应通过 McpContentParser 解析，executeTool 返回值被丢弃（CR-002 新增，对应 AC-041） | MCP 协议 | 🔴 强制 |
| 70 | BR-MCP-024 | McpContentParser 按内容类型策略分发处理：text->提取文本，image URL->Markdown 图片语法，image/audio base64->文本描述，resource text->提取文本，resource blob->文本描述，structuredContent->JSON 序列化，unknown->WARNING 日志+静默跳过（CR-002 新增，对应 AC-042~044） | MCP 协议 | 🔴 强制 |
| 71 | BR-MCP-025 | McpContentParser 对未知内容类型必须静默跳过（记录 WARNING 日志），不得导致工具调用失败（CR-002 新增，对应 AC-045） | MCP 协议 | 🔴 强制 |
| 72 | BR-MCP-026 | Windows 下 stdio 传输的 command 若为无扩展名裸命令（如 npx），必须自动补全 PATH 中存在的 .cmd/.bat/.exe 扩展名（resolveWindowsCommand，npx→npx.cmd），否则 ProcessBuilder 无法启动子进程（BUG-20260811 修复） | MCP 协议 | 🔴 强制 |
| 73 | BR-MCP-027 | MCP Server 连接失败的错误消息必须包含底层根因（rootCauseMessage 提取异常链最深层 cause），避免仅返回笼统"连接失败"（BUG-20260811 修复） | MCP 协议 | 🔴 强制 |

### 9.8 前端 MCP 服务管理规则

| # | 编号 | 规则 | 范围 | 级别 |
|---|------|------|------|------|
| 1 | BR-MCP-FE-001 | MCP Server 名称 1-50 字符，仅允许中英文、数字、下划线和连字符（JSON 配置的 key 即名称，前端解析后校验） | 前端 MCP | 🔴 强制 |
| 2 | BR-MCP-FE-002 | MCP Server 名称全局唯一（提交后接收后端唯一性校验结果并提示） | 前端 MCP | 🔴 强制 |
| 3 | BR-MCP-FE-003 | JSON 配置必须符合 mcpServers 格式：顶层为 `{"mcpServers": {...}}`，每个 Server 为 key-value 对 | 前端 MCP | 🔴 强制 |
| 4 | BR-MCP-FE-004 | 传输方式根据配置字段自动推断：有 command->STDIO；有 url 且无 command 且无 transport->HTTP；有 url + transport:"sse"->SSE | 前端 MCP | 🔴 强制 |
| 5 | BR-MCP-FE-005 | stdio 类型必须包含 command 字段；args/env 为可选字段 | 前端 MCP | 🔴 强制 |
| 6 | BR-MCP-FE-006 | sse/http 类型必须包含 url 字段（合法 http/https URL）；headers 为可选字段 | 前端 MCP | 🔴 强制 |
| 7 | BR-MCP-FE-007 | 删除 Server 必须二次确认，确认框需明示"将断开连接并注销所有工具" | 前端 MCP | 🔴 强制 |
| 8 | BR-MCP-FE-008 | 重连按钮仅在 Server 状态为 DISCONNECTED 或 ERROR 时可用，CONNECTED 显示"已连接"且不可点击 | 前端 MCP | 🔴 强制 |
| 9 | BR-MCP-FE-009 | 添加/重连操作进行中按钮禁用并显示 loading，防止重复提交 | 前端 MCP | 🔴 强制 |
| 10 | BR-MCP-FE-010 | MCP 模块禁用时（后端 5405）页面显示提示并禁用操作按钮 | 前端 MCP | 🔴 强制 |
| 11 | BR-MCP-FE-011 | Server 列表在添加/删除/重连操作成功后自动刷新 | 前端 MCP | 🔴 强制 |
| 12 | BR-MCP-FE-012 | JSON 配置支持一次添加多个 Server，每个 Server 独立处理（部分失败不影响其他添加） | 前端 MCP | 🔴 强制 |

### 9.9 前端对话模块规则

| # | 编号 | 规则 | 范围 | 级别 |
|---|------|------|------|------|
| 34 | BR-FE-001 | 流式输出采用后端真流式（SSE），非前端模拟打字机 | 前端对话 | 🔴 强制 |
| 35 | BR-FE-002 | 会话纪录完整存储于浏览器 localStorage，独立于后端会话生命周期 | 前端对话 | 🔴 强制 |
| 36 | BR-FE-003 | 后端会话超时后前端透明续聊，本地历史连续展示 | 前端对话 | 🔴 强制 |
| 37 | BR-FE-004 | 会话标题取用户首条消息前 20 字符，超出省略号 | 前端对话 | 🔴 强制 |
| 38 | BR-FE-005 | 重命名标题长度上限 50 字符 | 前端对话 | 🔴 强制 |
| 39 | BR-FE-006 | 单条消息长度上限 4000 字符 | 前端对话 | 🔴 强制 |
| 40 | BR-FE-007 | 本地缓存保留最近 50 个会话，超出按最后活跃时间 FIFO 淘汰 | 前端对话 | 🔴 强制 |
| 41 | BR-FE-008 | 会话列表按最后活跃时间倒序排列 | 前端对话 | 🔴 强制 |
| 42 | BR-FE-009 | 流式输出过程中禁用输入框，提供"停止生成"按钮 | 前端对话 | 🔴 强制 |
| 43 | BR-FE-010 | 流式中断后已接收内容作为助手回复存入本地缓存 | 前端对话 | 🔴 强制 |
| 44 | BR-FE-011 | 深度思考开关（toggle）按消息维度控制，当前会话内保持状态，切换会话互不影响（CR-001） | 前端对话 | 🔴 强制 |
| 45 | BR-FE-012 | 推理内容通过可折叠区块展示：流式中展开（标题"思考中..."），完成后折叠（标题"已思考 X 秒"）（CR-001） | 前端对话 | 🔴 强制 |
| 46 | BR-FE-013 | 助手正式回复按 Markdown 格式渲染（marked + DOMPurify），用户消息保持纯文本（CR-001） | 前端对话 | 🔴 强制 |
| 47 | BR-FE-014 | 推理内容随消息持久化到 localStorage（Message.reasoning 字段），刷新页面后仍可展开回看（CR-001） | 前端对话 | 🔴 强制 |

### 9.10 错误码规则

| # | 编号 | 规则 | 范围 | 级别 |
|---|------|------|------|------|
| 30 | BR-ERR-001 | 错误码必须通过 `ErrorCode` 枚举统一定义 | 公共组件 | 🔴 强制 |
| 31 | BR-ERR-002 | 业务异常必须使用 `BusinessException` + `ErrorCode` | 公共组件 | 🔴 强制 |
| 32 | BR-ERR-003 | 错误码编号区间按业务域划分，不可重叠 | 公共组件 | 🔴 强制 |
| 33 | BR-ERR-004 | 新增错误码必须在 `ErrorCode` 枚举中分配编号并补充注释 | 公共组件 | 🔴 强制 |

### 9.11 错误码区间速查

| 区间 | 业务域 | 示例 |
|------|--------|------|
| 200 | 成功 | SUCCESS(200) |
| 400-404 | 客户端错误 | PARAM_INVALID(400)、UNAUTHORIZED(401)、FORBIDDEN(403)、NOT_FOUND(404) |
| 5000 | 系统异常 | SYSTEM_ERROR(5000) |
| 5001-5099 | LLM 相关 | LLM_CALL_FAILED(5001)、LLM_TIMEOUT(5002)、LLM_RATE_LIMITED(5003)、LLM_API_KEY_INVALID(5004)、**LLM_PROVIDER_NOT_FOUND(5006)（CR-002 新增）**、**LLM_CAPABILITY_NOT_SUPPORTED(5007)（CR-002 新增）** |
| 5100-5199 | 工具相关 | TOOL_EXECUTION_FAILED(5100)、TOOL_NOT_FOUND(5101)、TOOL_PARAM_INVALID(5102) |
| 5200-5299 | 记忆/会话 | MEMORY_NOT_FOUND(5200)、SESSION_NOT_FOUND(5201)、SESSION_EXPIRED(5202) |
| 5300-5399 | RAG 相关 | RAG_RETRIEVE_FAILED(5300)、RAG_EMBEDDING_FAILED(5301)、RAG_DOCUMENT_LOAD_FAILED(5302)、RAG_DOCUMENT_PARSE_FAILED(5303)、RAG_VECTOR_STORE_INIT_FAILED(5304)、RAG_KNOWLEDGE_BASE_NOT_FOUND(5305)、RAG_DOCUMENT_NOT_FOUND(5306)、RAG_KNOWLEDGE_BASE_NAME_EXISTS(5307)、RAG_DOCUMENT_SIZE_EXCEEDED(5308)、RAG_DOCUMENT_FORMAT_UNSUPPORTED(5309) |
| 5400-5499 | MCP 相关 | MCP_CONNECTION_FAILED(5400)、MCP_TOOL_CALL_FAILED(5401)、MCP_SERVER_NAME_EXISTS(5402)、MCP_SERVER_NOT_FOUND(5403)、MCP_TRANSPORT_UNSUPPORTED(5404)、MCP_MODULE_DISABLED(5405)、MCP_SERVER_ALREADY_CONNECTED(5406) |
| 5500-5599 | 工作流相关 | WORKFLOW_NOT_FOUND(5500)、WORKFLOW_PARAM_MISSING(5501)、WORKFLOW_MODEL_NOT_FOUND(5502)、WORKFLOW_EXECUTION_FAILED(5503)、WORKFLOW_TIMEOUT(5504)、WORKFLOW_ALREADY_TERMINATED(5505)、WORKFLOW_MODE_NOT_SUPPORTED(5506，P2 新增)、WORKFLOW_NOT_RESUMABLE(5507，P3 新增) |

### 9.12 约束分级标准

| 级别 | 标签 | 含义 | 违反后果 |
|------|------|------|---------|
| 🔴 强制 | `MUST` | 系统必须遵守，不可绕过 | 业务逻辑异常、数据不一致、资金风险 |
| 🟡 尽量 | `SHOULD` | 正常情况下必须遵守，极端场景可审批豁免 | 业务合规风险 |
| 🟢 建议 | `RECOMMENDED` | 推荐遵守，提升业务质量 | 体验下降、效率降低 |
| ⚪ 可覆盖 | `CONFIGURABLE` | 可由管理员配置 | 依赖管理员决策 |

### 9.13 前端知识库管理规则

| # | 编号 | 规则 | 范围 | 级别 |
|---|------|------|------|------|
| 48 | BR-RAG-FE-001 | 知识库名称长度 1-50 个字符，仅允许中英文、数字、下划线和连字符（前端实时校验，提交前拦截） | 前端知识库 | 🔴 强制 |
| 49 | BR-RAG-FE-002 | 知识库名称全局唯一（前端提交后接收后端唯一性校验结果并提示） | 前端知识库 | 🔴 强制 |
| 50 | BR-RAG-FE-003 | 知识库描述长度不超过 200 个字符（前端实时字数计数与拦截） | 前端知识库 | 🔴 强制 |
| 51 | BR-RAG-FE-004 | 单个文档大小不超过 10MB（前端上传前校验，超限直接拦截不发起请求） | 前端知识库 | 🔴 强制 |
| 52 | BR-RAG-FE-005 | 支持的文档格式：txt、md、pdf（前端按扩展名校验，不支持格式直接拦截） | 前端知识库 | 🔴 强制 |
| 53 | BR-RAG-FE-006 | 文档处理状态流转：待处理 -> 处理中 -> 已完成/失败（前端轮询展示，状态终态后停止轮询） | 前端知识库 | 🔴 强制 |
| 54 | BR-RAG-FE-007 | 删除知识库时级联删除其下所有文档和向量数据（前端确认框需明示连带删除数量） | 前端知识库 | 🔴 强制 |
| 55 | BR-RAG-FE-008 | 知识库选择器状态按会话维度保持，切换会话互不影响，同一会话内保持状态（与深度思考开关行为一致） | 前端知识库 | 🔴 强制 |
| 56 | BR-RAG-FE-009 | 知识库选择按消息维度控制（发送消息时使用当前选择器的知识库配置，不影响历史消息） | 前端知识库 | 🔴 强制 |
| 57 | BR-RAG-FE-010 | 文档状态轮询使用 setInterval 每 3 秒一次，仅对 PENDING/PROCESSING 文档发起请求，全部终态后自动停止，组件卸载时清理定时器 | 前端知识库 | 🔴 强制 |

> **数据来源**：`specs/SDD-工程业务背景文档.md` 第 5 节、`ErrorCode.java`、`specs/features/2026-07-27/RAG知识库前端/RAG知识库前端.md`（BR-RAG-FE-001~010）、`specs/features/2026-07-24/RAG知识库问答/RAG知识库问答.md`（BR-RAG-013~014）、`specs/features/2026-08-05/MCP协议模块/MCP协议模块.md`（BR-MCP-001~019）、`specs/features/2026-08-10/LLM厂商模型配置/LLM厂商模型配置.md`（BR-LLM-CONF-001~020）

### 9.14 前端 LLM 配置管理规则（CR-003 新增）

| # | 编号 | 规则 | 范围 | 级别 |
|---|------|------|------|------|
| 58 | BR-LLM-CONF-001 | 厂商名称全局唯一，不允许重复 | LLM 配置 | 🔴 强制 |
| 59 | BR-LLM-CONF-002 | 每个厂商必须配置 API Key 才能保存 | LLM 配置 | 🔴 强制 |
| 60 | BR-LLM-CONF-003 | 每个厂商必须配置 Base URL（预定义自动填充，自定义手动填写） | LLM 配置 | 🔴 强制 |
| 61 | BR-LLM-CONF-004 | 模型名称不可为空，同一厂商下同一类型的模型名称不可重复 | LLM 配置 | 🔴 强制 |
| 62 | BR-LLM-CONF-005 | 模型类型限定四种：chat/embedding/rerank/multimodal | LLM 配置 | 🔴 强制 |
| 63 | BR-LLM-CONF-006 | 仅 chat 类型模型支持"支持视图理解"标记 | LLM 配置 | 🔴 强制 |
| 64 | BR-LLM-CONF-007 | 保存厂商配置前必须通过 API Key 测试连接 | LLM 配置 | 🔴 强制 |
| 65 | BR-LLM-CONF-008 | API Key 在前端界面展示时必须脱敏 | LLM 配置 | 🔴 强制 |
| 66 | BR-LLM-CONF-009 | 配置修改保存后即时生效，无需重启服务 | LLM 配置 | 🔴 强制 |
| 67 | BR-LLM-CONF-010 | 模型选择按会话维度保持状态，切换会话互不影响 | LLM 配置 | 🔴 强制 |
| 68 | BR-LLM-CONF-011 | 模型选择按消息维度控制（发送时用当前选中模型） | LLM 配置 | 🔴 强制 |
| 69 | BR-LLM-CONF-012 | 新建会话默认选择上次使用的 chat 模型 | LLM 配置 | 🔴 强制 |
| 70 | BR-LLM-CONF-013 | 删除厂商后，正在使用该厂商模型的会话自动切换到第一个可用 chat 模型 | LLM 配置 | 🔴 强制 |
| 71 | BR-LLM-CONF-014 | 配置同时保存到后端内存和前端 localStorage | LLM 配置 | 🔴 强制 |
| 72 | BR-LLM-CONF-015 | 后端重启后前端自动从 localStorage 推送配置到后端恢复 | LLM 配置 | 🔴 强制 |
| 73 | BR-LLM-CONF-016 | 对话功能依赖至少一个已配置的 chat 模型 | LLM 配置 | 🔴 强制 |
| 74 | BR-LLM-CONF-017 | RAG 文档向量化依赖已配置的 embedding 模型 | LLM 配置 | 🔴 强制 |
| 75 | BR-LLM-CONF-018 | 预定义厂商列表包含：火山引擎方舟、阿里百炼、OpenAI、DeepSeek、Ollama | LLM 配置 | 🔴 强制 |
| 76 | BR-LLM-CONF-019 | 移除 application.yml 中 llm/ark/bailian 配置段，LLM 配置完全由前端动态管理 | LLM 配置 | 🔴 强制 |
| 77 | BR-LLM-CONF-020 | 后端模型实例缓存按厂商+模型名称维度管理，配置变更时清除对应缓存 | LLM 配置 | 🔴 强制 |

### 9.15 应用编排规则（v2.9 新增）

> **来源**：`specs/features/2026-08-13_应用编排层/应用编排层.md` 第 5.3 节（BR-APP-001~016，详见[应用编排模块-业务说明书](specs/modules/应用编排模块-业务说明书.md)第 9 节）+ 2026-08-17 BUG 修复沉淀

| # | 编号 | 规则 | 范围 | 级别 |
|---|------|------|------|------|
| 78 | BR-APP-004 | 工作流模板引用的模型必须已存在于 LLM 配置（UUID 优先、modelName 兜底解析），否则拒绝执行（WORKFLOW_MODEL_NOT_FOUND） | 应用编排 | 🔴 强制 |
| 79 | BR-APP-007 | Agent 重试耗尽后工作流暂停在失败步骤（PAUSED），不自动跳过；仅 PAUSED 态可断点恢复，恢复时已完成步骤跳过不重跑 | 应用编排 | 🔴 强制 |
| 80 | BR-APP-008 | 工作流执行状态（含 AgenticScope 共享变量）仅内存存储，应用重启丢失 | 应用编排 | 🔴 强制 |
| 81 | BR-APP-014 | 循环工作流必须配置 maxIterations 上限，防止无限循环 | 应用编排 | 🔴 强制 |
| 82 | BR-APP-SSE-001 | SSE emitter 必须配置为永不超时（`new SseEmitter(0L)`），禁止固定超时值——长任务工作流会被异步超时掐断并导致前端卡"执行中"（2026-08-17 BUG：300s 超时致长任务中断） | Web/应用编排 | 🔴 强制 |
| 83 | BR-APP-SSE-002 | 前端 SSE 解析必须做断流兜底：流结束但未收到终态事件（complete/failed/paused）时回调 onError 退出"执行中"状态 | 前端编排 | 🔴 强制 |

### 9.16 人机交互 HITL 规则（v3.1 新增）

> **来源**：`specs/features/20260820_agent-human-interaction/agent-human-interaction.md`（AC-N01~H02）+ 技术方案 Sec 6 护栏设计 + 实现记录 6.3

| # | 编号 | 规则 | 范围 | 级别 |
|---|------|------|------|------|
| 84 | BR-HITL-001 | `enableHitl=false`（默认）时走现有 chatStream 路径，行为零回归；`enableHitl=true` 时路由到 `SimpleAgent.chatHITLStream`（显式 ReAct + HITL 暂停-恢复） | Agent 编排 | 🔴 强制 |
| 85 | BR-HITL-002 | HITL 必须使用显式 ReAct 循环（`HITLReActStream`），不走 AiServices 隐式 ReAct——隐式循环无法暂停/恢复且工具无法获取 sessionId（技术决策 1/2） | Agent 编排 | 🔴 强制 |
| 86 | BR-HITL-003 | Agent 信息不足/指令有歧义时，必须调用 askUser 工具追问，不得基于猜测推进任务或伪造参数 | Agent 编排 | 🔴 强制 |
| 87 | BR-HITL-004 | Agent 即将执行有副作用操作（删除文件/修改数据/发送 HTTP 请求）前，必须调用 askUser(type=confirm) 确认，未确认不得执行 | Agent 编排 | 🔴 强制 |
| 88 | BR-HITL-005 | askUser 工具参数契约：type 仅取 "text"/"confirm"；question 非空；confirm 类型必须提供 2-4 个选项（options），text 类型传空数组 | 工具调用 | 🔴 强制 |
| 89 | BR-HITL-006 | 同一问题最多追问 3 次（retryCount 0→1→2），第 4 次达上限时返回错误 Observation，LLM 终止任务并告知用户，不得继续追问同一问题 | 工具调用 | 🔴 强制 |
| 90 | BR-HITL-007 | askUser 调用不消耗 ReAct 迭代次数（暂停状态不计入 `agent.max-iterations=10`），由 HITLReActStream 拦截处理 | Agent 编排 | 🔴 强制 |
| 91 | BR-HITL-008 | 同一 sessionId 同时只能有一个 pending HITL 交互（HumanInteractionManager 覆盖旧状态）；pending 状态按 sessionId 隔离，不同会话互不影响 | 记忆管理 | 🔴 强制 |
| 92 | BR-HITL-009 | askUser 工具采用"拦截而非执行"机制：AskUserTool 注册为 @Tool 仅提供 Function Calling Schema，HITLReActStream 检测工具名为 askUser 时拦截，不执行方法体（技术决策 2） | 工具调用 | 🔴 强制 |
| 93 | BR-HITL-010 | pending 状态随会话超时（30 分钟）清理（HumanInteractionManager @Scheduled），超时后用户消息创建新会话；Controller 检测无 pending 时降级为正常对话不报错 | 记忆管理 | 🔴 强制 |
| 94 | BR-HITL-011 | 前端渲染由 askUserData.type 决定：type=text 渲染为普通文本提示（用户经输入框自由回复），type=confirm 渲染为 ConfirmCard 选项卡片（用户点击按钮回复，AC-T01/T02） | 前端对话 | 🔴 强制 |
| 95 | BR-HITL-012 | 前端 HITL 等待态由 askUserData 是否存在决定（不依赖消息 status）：ask_user 事件后 done 使消息 status=complete，但仍视为等待用户输入；用户回复后 clearAskUser 清除 askUserData 并标记 complete（2026-08-21 BUG 修复） | 前端对话 | 🔴 强制 |

### 9.17 工具权限控制规则（v3.2 新增）

> **来源**：`specs/features/20260824_tool-permission-control/tool-permission-control.md`（15 条 AC 全部验收通过）+ 技术方案四层拦截架构 + 2026-08-24 两项 BUG 修复沉淀

| # | 编号 | 规则 | 范围 | 级别 |
|---|------|------|------|------|
| 96 | BR-PERM-001 | 工具权限分三级：allow（直执行）/ ask（人工确认后执行）/ deny（禁止），权限判定优先级：显式配置 > `@DefaultPermission` 注解 > 类别兜底（rag→allow / mcp→ask） | 工具调用 | 🔴 强制 |
| 97 | BR-PERM-002 | deny 工具加载期全路径不注入 LLM 工具列表（同步/流式/统一模式），执行期 ToolExecutor 兜底拦截——双保险防提示注入诱导调用（AC-T01/S01） | 工具调用 | 🔴 强制 |
| 98 | BR-PERM-003 | ask 工具仅在流式路径加载（同步路径无暂停能力，加载期排除）；调用时经 HITLReActStream 拦截 → SSE `tool_confirm` 确认卡片 → 批准执行 / 拒绝结果回填（AC-T02/N03/S02） | Agent 编排 | 🔴 强制 |
| 99 | BR-PERM-004 | askUser 工具权限固定豁免为 allow，不受权限配置影响——确认流程自身需确认会死锁（AC-S03） | 工具调用 | 🔴 强制 |
| 100 | BR-PERM-005 | 权限配置持久化至 `data/tool-permissions.json`，重启不丢失；变更即时生效但**下一轮对话**才重建工具列表（本轮会话沿用已加载列表，避免中途突变，AC-N04） | 工具调用 | 🔴 强制 |
| 101 | BR-PERM-006 | 动态注册工具按类别自动分配默认权限（知识库→allow / MCP→ask），注销（知识库删除/MCP 断开）时权限配置同步清理，禁止孤儿配置（AC-E01/T03） | 工具调用 | 🔴 强制 |
| 102 | BR-PERM-007 | 权限配置仅可经管理页 API 变更，对话内容（含提示注入）不可修改权限；权限拒绝提示不得暴露配置细节（配置者/时间） | 工具调用 | 🔴 强制 |
| 103 | BR-PERM-008 | 前端确认卡片决策必须发出非空 message 恢复请求：空 message 会被前端空消息拦截 + 后端 `@NotBlank` 双重拒绝，导致确认流程卡死（2026-08-24 BUG：批准后页面永久"生成中"）；决策采用 silent 模式，不产生对话气泡 | 前端对话 | 🔴 强制 |
| 104 | BR-PERM-009 | `askUserData.approved` 为三态字段（true=已批准 / false=已拒绝 / undefined=待决策），前端锁定判定必须用 `approved !== undefined`，禁止 `!!approved` 双态断言——拒绝时 `!!false===false` 会导致卡片不锁定、按钮可重复点击（2026-08-24 BUG） | 前端对话 | 🔴 强制 |
| 105 | BR-PERM-010 | 工具权限判定规则全局唯一：所有使用工具的模块（单 Agent 同步/流式、工作流 HITL/非 HITL）必须经 ToolRegistry 能力声明双方法（resolveToolsForStreaming/ForDirect）解析 + ToolPermissionGuard 出口统一包装；调用方只声明能力（ask 可见/剔除）不选权限，权限判定与配置完全归属工具域（AC-N01/T04，20260825 全域统一新增） | 工具调用 | 🔴 强制 |
| 106 | BR-PERM-011 | LangChain4j 反射直调路径命中 ToolPermissionGuard ByteBuddy 包装拦截器，deny 方法体零触发返回固定文案（纵深防御第二道防线，AC-T03/T04）；包装实例按（工具对象,权限版本）二级缓存、权限等级包装时捕获不中途突变（AC-M01）；包装失败降级返回原工具（加载期过滤仍生效） | 工具调用 | 🔴 强制 |
| 107 | BR-PERM-012 | 工作流 HITL 路径 ask 工具经 WorkflowHITLState MODE_TOOL_CONFIRM 快照 + hitlReply 恢复（tool_confirm 双事件）；拒绝语义=拒绝续跑（回填脱敏拒绝文案 LLM 换方案、不终止工作流），区别于 checkpoint 拒绝即终止（决策 7/AC-S04/N03）；tool_confirm 恢复后工具再次拦截不累计 retryCount | 应用编排 | 🔴 强制 |

### 9.18 工作流 HITL 规则（v3.3 新增）

> **来源**：`specs/features/20260824_workflow-hitl/`（需求 + 技术方案 + 任务规划，15 个 Task 全部完成，16 条 AC 全部验收通过）+ 2026-08-25 BUG 修复沉淀

| # | 编号 | 规则 | 范围 | 级别 |
|---|------|------|------|------|
| 108 | BR-WHITL-001 | 工作流执行状态机新增 WAITING_USER 状态（等待用户，非终态），与 PAUSED（失败暂停，可 resume）语义分离——WAITING_USER 仅允许 hitl-reply 和 terminate，resume 被拒绝（AC-S03） | 应用编排 | 🔴 强制 |
| 109 | BR-WHITL-002 | @HumanCheckpoint 注解仅可标注在 @Agent 接口方法上，AgentExecutor 在方法执行前反射检测，有注解则暂停构造确认型 askUser 数据（type=confirm），未确认不得执行方法体（AC-N02/T02） | 应用编排 | 🔴 强制 |
| 110 | BR-WHITL-003 | 工作流 HITL 暂停（WAITING_USER）时保存完整执行上下文到 ResumableExecutionState.hitlState（AgenticScope + 已完成步骤 + Agent 消息列表），恢复后后续步骤可访问，禁止因暂停-恢复丢失上下文（AC-M01/M02） | 应用编排 | 🔴 强制 |
| 111 | BR-WHITL-004 | 工作流 HITL 恢复专用端点 `POST /executions/{id}/hitl-reply`（askUser 传 message / checkpoint 传 approved / toolConfirm 传 approved），与失败恢复 resume 语义分离；message 和 approved 至少一个非 null，否则 WORKFLOW_PARAM_MISSING(5501)（AC-N03） | Web | 🔴 强制 |
| 112 | BR-WHITL-005 | 检查点拒绝（approved=false）后工作流状态置 TERMINATED，不执行后续步骤，清理 HITL 快照（AC-S01/H01） | 应用编排 | 🔴 强制 |
| 113 | BR-WHITL-006 | 并行模式多个 Agent 同时触发 HITL 时，第一个生效进入 WAITING_USER，其余写入 ctx 排队列表（PENDING_HITL_KEY）；hitlReply 恢复时先按序消费队列（每次推送一条 workflow_waiting），队列清空才重放策略（AC-E02） | 应用编排 | 🔴 强制 |
| 114 | BR-WHITL-007 | 工作流 WAITING_USER 状态超 30 分钟未回复，由 @Scheduled 每 5 分钟扫描置 TIMEOUT 并清理快照（复用会话超时，AC-E01） | 应用编排 | 🔴 强制 |
| 115 | BR-WHITL-008 | 工作流 Agent 使用 AiServices 隐式 ReAct（TokenStream），askUser 场景需切换 HITLReActStream 显式 ReAct（hitlEnabled=true）；@HumanCheckpoint 检测独立于 hitlEnabled，有注解即触发（技术决策 1） | 应用编排 | 🔴 强制 |
| 116 | BR-WHITL-009 | 工作流 HITL 恢复键为 `hitl:{iteration}:{agentName}`，executeOrSkip 检测恢复键而非 done 键，重放策略时跳过已完成步骤仅重执行暂停步（AC-E03） | 应用编排 | 🔴 强制 |
| 117 | BR-WHITL-010 | 工作流 HITL 暂停不受工作流 5 分钟超时影响（SSE emitter 永不超时 0L），复用会话超时 30 分钟清理（技术方案 Sec 1.4） | 应用编排 | 🔴 强制 |
| 118 | BR-WHITL-011 | 前端 5 个模式特定执行视图（Sequential/Parallel/Loop/Conditional/Supervisor）必须均包含 HITL 等待横幅（isWaitingUser 时显示 + AskUserCard/ConfirmCard 交互 + 终止按钮）；缺失任一视图横幅会导致页面卡住无法确认（2026-08-25 BUG：SequentialExecuteView 缺 HITL 等待横幅致【研究-分析-总结】模板无法操作） | 前端编排 | 🔴 强制 |
| 119 | BR-WHITL-012 | useWorkflowStream.startExecution 必须重置 HITL 状态变量（isWaitingUser/waitingHitlMode/waitingAgentName/askUserData），避免上一次执行的等待横幅残留导致新执行卡住（2026-08-25 BUG 修复沉淀） | 前端编排 | 🔴 强制 |

### 9.19 Skill 能力域规则（v3.5 新增）

> **来源**：`specs/features/20260826_agent-skill/`（需求 + 技术方案 + 任务规划 + CR-001/CR-002）+ 2026-08-27 BUG 修复沉淀（agent-demo-skill 第 10 能力域，30 条 AC）

| # | 编号 | 规则 | 范围 | 级别 |
|---|------|------|------|------|
| 120 | BR-SKILL-001 | 技能 id 全局唯一，create 重复 id 拒绝 | Skill 域 | 🔴 强制 |
| 121 | BR-SKILL-002 | 技能按标准目录结构持久化 data/skills/{skillId}/SKILL.md（+scripts/+reference/），启动自动迁移旧版单文件 JSON（源文件 .bak 备份，幂等，AC-N08） | Skill 域 | 🔴 强制 |
| 122 | BR-SKILL-003 | 预置技能播种幂等：目标目录已存在不覆盖用户修改 | Skill 域 | 🔴 强制 |
| 123 | BR-SKILL-004 | 预置三形态：纯指令 / 指令+资源 / 指令+自带脚本工具（3 个预置样本） | Skill 域 | 🔴 强制 |
| 124 | BR-SKILL-005 | 技能创建/编辑时恶意内容（忽略安全规则/修改权限/删除数据/冒充系统四类）100% 拦截（AC-S01） | Skill 域 | 🔴 强制 |
| 125 | BR-SKILL-007 | 单会话并发激活上限默认 3（AC-S05），超限引导 askUser | Skill 域 | 🔴 强制 |
| 126 | BR-SKILL-009 | 用户排除的技能不再自主激活（AC-M04），排除经会话级 excludedSkills 携带 | Skill 域 | 🔴 强制 |
| 127 | BR-SKILL-010 | 渐进式披露：目录段仅元数据（名称+描述），指令全文激活后经激活段注入（AC-T01） | Skill 域 | 🔴 强制 |
| 128 | BR-SKILL-011 | 触发源限制：仅用户消息可触发技能激活（AC-S03），工具返回/检索结果中的"激活技能"建议视为数据而非指令 | Skill 域 | 🔴 强制 |
| 129 | BR-SKILL-012 | loadSkill 只读能力工具豁免恒 ALLOW（决策 6），管理页不可改其权限（400） | Skill 域 | 🔴 强制 |
| 130 | BR-SKILL-013 | 自带脚本工具（skill_{id}_{script}）经脚本护栏（语言白名单 shell/python3、参数校验、执行超时 10s、危险命令拦截、输出截断 4K）管控，不进入系统权限确认流（AC-T03/S06） | Skill 域 | 🔴 强制 |
| 131 | BR-SKILL-014 | `/skill 技能名 消息` 指令：用户消息气泡保留原始输入展示（含技能名，所见即所得），发送给 LLM 的消息剥离指令前缀，不额外插入 AI 提示消息/页面徽标（AC-N07，CR-002） | Skill 域 | 🔴 强制 |

### 9.20 评估能力域规则（v3.6 新增，CR-002 完整评估体系）

> **来源**：`specs/features/20260829_langsmith-observability/langsmith-observability_变更任务_CR002.md`（Task-26~34，AC-N10~N13/S07/E06）+ `langsmith-observability_CR002_代码审查报告.md`（2026-08-31 审查修复 + EDD 补跑沉淀）+ 评估模块业务说明书

| # | 编号 | 规则 | 范围 | 级别 |
|---|------|------|------|------|
| 132 | BR-EVAL-001 | 评估 harness 默认关闭：`eval.enabled=false` 零装配零影响，按需手动 CLI 触发（`mvn -pl agent-demo-evaluation spring-boot:run`），不随构建/正常启动执行（AC-N10 生态） | 评估域 | 🔴 强制 |
| 133 | BR-EVAL-002 | 每用例独立会话执行 + Pass^runs 稳定性语义（runs 默认 3，A/B 强制下限 3 取均值）；单例失败标注后继续，不中断整体评估；禁止单次运行直接作为对比结论（AC-N10/N13） | 评估域 | 🔴 强制 |
| 134 | BR-EVAL-003 | judge 输出 JSON 契约解析失败 / 调用异常 / 未配置模型 → 一律缺席标注（可辨原因），不误计 0 分、不抛异常中断（AC-E06/N11） | 评估域 | 🔴 强制 |
| 135 | BR-EVAL-004 | 幻觉复核对 judge 输出执行代码层一票否决：hallucination=detected 时 overall 一律归一 fail（防止 judge 自相矛盾放过否决），报告标注"幻觉 detected（veto 判负）" | 评估域 | 🔴 强制 |
| 136 | BR-EVAL-005 | 幻觉判定要点（judge.md v2）：回复点名声称调用具体工具或引用具体结果值，但工具轨迹无对应调用或结果不一致 → 编造 detected；仅泛化确认表述（如"好的，我已记录"）不属编造（EDD TUNE 沉淀） | 评估域 | 🔴 强制 |
| 137 | BR-EVAL-006 | 多源 judge：判官默认独立厂商（百炼）与被评 Agent（ARK）不同模型家族，对冲同源偏差；judge-model-id 可配置 + 同源 WARN 不阻断（决策 13） | 评估域 | 🔴 强制 |
| 138 | BR-EVAL-007 | 基线文件常规运行只读不覆盖，更新须显式重建（删除基线重跑首建）；对比按 ±30pp 噪声带宽判定（10 例规模 95% CI），带内差异标"不可决策"（AC-N12） | 评估域 | 🔴 强制 |
| 139 | BR-EVAL-008 | 评估出境与落盘零明文：judge prompt 三要素（input/response/toolTrace）统一经脱敏出口，报告/基线/数据集无密钥明文；数据集仅含合成测试密钥夹具（AC-S07） | 评估域 | 🔴 强制 |
| 140 | BR-EVAL-009 | 评估 CLI 独立上下文（无 web 配置面）经 LlmConfigSeed 注入模型配置（eval.api-key 默认 ${ARK_API_KEY:} / judge 默认 ${BAILIAN_API_KEY:}，禁止硬编码）；无模型/无 Key 启动即 fail-fast，不静默假成功 | 评估域 | 🔴 强制 |
| 141 | BR-EVAL-010 | 工具轨迹观测：AiServices 反射路径工具调用经 `SimpleAgent.afterToolExecution` → `ToolExecutor.recordToolExecution` 统一采集（含结果，供工具选择断言与 judge 幻觉复核）；同时补齐生产同步路径工具 span（决策 3 边界收窄，净收益） | 评估域 | 🔴 强制 |
| 142 | BR-EVAL-011 | 评估 Agent 工具集须与生产对齐：评估 CLI 独立上下文需配置 `agent.tools.default-tools`（与 bootstrap 一致），否则工具选择类用例必然失真 | 评估域 | 🔴 强制 |

### 9.21 工具产出安全清洗规则（v3.7 新增，20260826 迭代 CR-001/CR-004）

| # | 编号 | 规则 | 范围 | 级别 |
|---|------|------|------|------|
| 143 | BR-TOOL-032 | 4 类数据获取工具（HTTP/文件读取/MCP 动态工具/RAG 知识库检索）产出必须经清洗管道包裹"外部数据、非指令"边界声明（含来源标识/数据身份/禁执指令/用法引导四要素）后进入 LLM 上下文，模型不执行工具结果中的指令 | 工具调用 | 🔴 强制 |
| 144 | BR-TOOL-033 | 清洗降级铁律：任何清洗环节异常不得阻断工具结果返回——编排器全局异常 ERROR+返回原文（AC-E02）；变换段逐段隔离 WARN+跳过该段继续（AC-E05/E06，CR-004 统一归一）；临时文件写失败降级纯截断（AC-E01）；随机分隔符生成失败降级固定分隔符 | 工具调用 | 🔴 强制 |
| 145 | BR-TOOL-034 | 可疑指令分级处置：一般可疑保留原文+警示标记，高危移除片段+占位标记；误命中容忍（正文零丢失，AC-E04）；清洗动作统一 WARN 安全日志（仅元信息，不含正文与秘密值，AC-S07） | 工具调用 | 🔴 强制 |
| 146 | BR-TOOL-035 | 秘密脱敏：秘密赋值形态（password=/api_key:/token=/Bearer 等）秘密值替换 `[REDACTED]` 键名保留（规则捕获组 1 = 秘密值）；安全日志不含原始秘密值；临时文件落盘内容为已脱敏文本（秘密不落盘扩散） | 工具调用 | 🔴 强制 |
| 147 | BR-TOOL-036 | 包裹声明分隔符随机化：每次调用 SecureRandom 生成 16 位 hex token 头尾配对（64 bit 熵），工具内容无法预先伪造闭合标记逃逸；可经 random-delimiter 开关独立回退 | 工具调用 | 🔴 强制 |
| 148 | BR-TOOL-037 | 清洗注入点契约：新增数据获取工具必须在成功返回路径调用 `sanitizer.sanitize()`（SanitizeContext 自描述 toolName/sourceDesc/htmlContent；异常路径不经管道，AC-E03）；新增清洗能力一律以新 `SanitizeStage` 实现（order 落位，编排器零修改接入，AC-T05），禁止在注入点或编排器内追加分支逻辑 | 工具调用 | 🔴 强制 |
| 149 | BR-TOOL-038 | 清洗规则启动期预编译校验：非法正则或无值捕获组规则记 RULE_SKIPPED WARN 并跳过（合法规则生效、规则 ID 按原始位置稳定编号），禁止运行期静默忽略或因单条非法规则阻断清洗服务 | 工具调用 | 🔴 强制 |

---

## 十、开发环境与构建

### 10.1 后端环境

> **数据来源**：`application.yml`、`application-dev.yml`、`agent-demo-bootstrap/pom.xml`

| 配置项 | 值 |
|--------|---|
| JDK | OpenJDK 17+（推荐 Eclipse Temurin / Amazon Corretto） |
| Maven | 3.9+ |
| 端口 | 8080 |
| Profile | `application-dev.yml`（默认激活） |
| 启动类 | `com.agentdemo.AgentDemoApplication`（位于 `agent-demo-bootstrap`） |
| 接口文档 | `http://localhost:8080/swagger-ui.html` |
| OpenAPI JSON | `http://localhost:8080/v3/api-docs` |
| API Base Path | `http://localhost:8080/api/agent/` |
| 日志文件 | `logs/agent-demo.log` |

### 10.2 环境变量

| 变量名 | 必填 | 说明 |
|--------|------|------|
| 无（LLM 配置由前端动态管理，API Key 通过前端页面配置，无需环境变量） | - | LLM 配置已迁移至前端动态管理，无需设置环境变量 |
| `ARK_API_KEY` / `BAILIAN_API_KEY`（仅评估 CLI 使用） | 评估触发时 | **评估 CLI 独立上下文**（无前端配置面）经 `eval.api-key`/`eval.judge-api-key` 默认引用：Agent 模型走 `ARK_API_KEY`（默认 ARK coding v3），多源 judge 默认走 `BAILIAN_API_KEY`（百炼）；未配置时启动 fail-fast |

### 10.3 多环境配置

> **数据来源**：`application.yml`、`application-dev.yml`、`application-prod.yml`

| 环境 | Profile | 默认模型 | 日志级别 | 说明 |
|------|---------|---------|---------|------|
| 开发 | `application-dev.yml` | doubao-seed-2.0-lite | DEBUG | 轻量模型节省成本 |
| 生产 | `application-prod.yml` | doubao-seed-2.0-pro | INFO | 旗舰模型保证质量 |

### 10.4 关键配置项

> **数据来源**：`application.yml`

```yaml
# Agent 配置
agent:
  max-iterations: 10                    # ReAct 循环最大迭代
  chat-memory-window-size: 20           # 短期记忆窗口大小
  default-role: general                 # 默认角色（对应 prompts/roles/ 目录下的模板文件名）
  # 系统提示词已外部化到 prompts/roles/ 和 prompts/scenarios/ 模板文件
  # AgentConfig 中的默认值作为模板缺失时的最终回退
  enable-logging: true                  # 调用日志开关
  file-allowed-dir: ./data              # 文件读取白名单目录
  tools:                                # 工具按需加载配置（CR 新增）
    default-tools:                      # 默认加载的工具（始终可用，无需 API 指定）
      - builtin:getCurrentTime
      - builtin:calculate
    optional:                           # 可选工具（需通过 API tools 参数显式指定才加载）
      - builtin:httpGet
      - builtin:httpPost
      - builtin:readFile
      - mcp:*                           # 所有 MCP 工具
      - rag:*                           # 所有知识库工具

# 会话配置
session:
  timeout-minutes: 30                   # 会话超时时间

# RAG 知识库配置
rag:
  store-type: memory                    # 向量存储类型：memory | milvus
  document:
    max-size: 10MB                      # 单个文档大小上限
    supported-formats: txt,md,pdf       # 支持的文档格式
    temp-dir: ./data/rag/temp           # 临时文件目录
  splitter:                             # 文档分割配置（按文件类型独立配置 size/overlap/minSize，单位 Token 数）
    default-config:
      size: 1000
      overlap: 200
      min-size: 500                     # 最小分块大小，低于此值触发合并（CR-001 新增）
    md:
      size: 800
      overlap: 150
      min-size: 400
    pdf:
      size: 1200
      overlap: 200
      min-size: 600
    txt:
      size: 1000
      overlap: 200
      min-size: 500
  retrieval:
    max-results: 5                      # 检索返回最大片段数
    min-score: 0.0                      # 最小相似度阈值
  milvus:                               # Milvus 配置（store-type=milvus 时生效）
    host: ${MILVUS_HOST:localhost}
    port: ${MILVUS_PORT:19530}
    collection-name: agent_demo_rag

# MCP 协议模块配置
mcp:
  enabled: true                    # 模块总开关，false 时完全不加载
  default-tool-timeout: 60s        # 全局默认工具调用超时
  servers:                         # 静态预配置 Server 列表
    - name: mermaid-mcp            # Server 唯一标识
      transport: http              # 传输方式：stdio | sse | http
      enabled: true
      url: https://mcp.mermaid.ai/mcp
      tool-timeout: 60s
    - name: fetch
      transport: stdio
      enabled: true
      command: npx.cmd             # Windows 需 .cmd 后缀
      args:
        - mcp-fetch-server
      tool-timeout: 60s

# 服务器配置
server:
  port: 8080
  tomcat:
    connection-timeout: 300000           # SSE 流式接口超时 5 分钟
```

### 10.5 构建与启动

**编译命令**：

```powershell
# 全量编译
mvn clean compile

# 单模块编译（含依赖模块）
mvn compile -pl agent-demo-agent -am

# 打包（跳过测试）
mvn clean install -DskipTests
```

**启动方式**：

```powershell
# 方式 1：IDE 启动
# 运行 agent-demo-bootstrap 模块的 AgentDemoApplication.main()

# 方式 2：JAR 包启动
$env:ARK_API_KEY="your-api-key-here"
java -jar agent-demo-bootstrap/target/agent-demo-bootstrap-1.0.0.jar

# 方式 3：项目提供的启动脚本
.\start.ps1
```

### 10.6 Maven 版本管理

| 管理项 | 机制 |
|--------|------|
| Spring Boot 版本 | 根 POM 继承 `spring-boot-starter-parent:3.2.5` |
| 第三方依赖版本 | `agent-demo-bom` BOM 统一管控 |
| 项目版本 | 统一 `1.0.0`，BOM 与子模块使用 `${project.version}` |
| 编译顺序 | bom -> common -> llm/tools/memory -> agent -> app -> web -> bootstrap |

### 10.8 前端开发环境

| 配置项 | 值 |
|--------|---|
| Node.js | 18+ |
| 包管理器 | npm |
| 开发端口 | 5173（Vite 默认，被占用时自动递增） |
| 代理配置 | `/api` -> `http://localhost:8080`（Vite proxy 规避 CORS） |
| 启动命令 | `npm run dev`（在 `agent-demo-frontend/` 目录） |
| 构建命令 | `npm run build` |
| 测试命令 | `npm run test`（Vitest） |
| 测试框架 | Vitest 1.6+ + @vue/test-utils 2.4+ |
| 浏览器测试 | Playwright 1.58+（Chromium 无头模式） |

### 10.9 接口调用示例

```bash
# 创建会话
curl -X POST http://localhost:8080/api/agent/session

# 同步对话
curl -X POST http://localhost:8080/api/agent/chat \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"","message":"现在几点？"}'

# 流式对话（SSE）
curl -X POST http://localhost:8080/api/agent/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"","message":"你好"}'

# 查询会话是否存在
curl -X GET http://localhost:8080/api/agent/session/{sessionId}

# 清空会话记忆
curl -X DELETE http://localhost:8080/api/agent/session/{sessionId}/memory
```

> **数据来源**：`application.yml`、`application-dev.yml`、`start.ps1`

---

## 十一、AI 驱动开发流程

### 11.1 标准迭代流程

> **数据来源**：用户规则中的"渐进式开发"与"结构化流程"

```mermaid
flowchart LR
    A[构思方案] --> B[提请审核]
    B --> C[分解任务]
    C --> D[执行编码]
    D --> E[编译验证]
    E --> F[代码审查]
    F --> G[迭代文档]
    G --> H[更新知识库]
```

### 11.2 AI 编码核心约束

> **数据来源**：`specs/SDD-项目技术指南文档.md` 第 4 节

| 编号 | 约束描述 | 级别 |
|------|---------|------|
| TC-AI-001 | AI 生成的代码必须通过 `mvn compile -pl {模块名} -am` 编译验证 | 🔴 强制 |
| TC-AI-002 | AI 修改代码前必须先读取目标文件，理解上下文再动手 | 🔴 强制 |
| TC-AI-003 | AI 删除或修改已有代码前必须向用户确认 | 🔴 强制 |
| TC-AI-004 | AI 必须优先使用项目已有的工具函数和组件，不重复造轮子 | 🔴 强制 |
| TC-AI-005 | AI 生成新工具必须加 `@Component` + `@Tool` 注解 | 🔴 强制 |
| TC-AI-006 | AI 生成新 Agent 实现必须实现 `BaseAgent` 接口 | 🔴 强制 |
| TC-AI-007 | AI 生成新模型名必须先在 `ModelConstants` 中定义常量 | 🔴 强制 |
| TC-AI-008 | AI 不得使用任何 Java 21+ API | 🔴 强制 |
| TC-AI-009 | AI 遇到不确定的业务规则时应主动向用户确认而非假设 | 🔴 强制 |
| TC-AI-010 | AI 修改接口签名前必须枚举所有调用方 | 🔴 强制 |
| TC-AI-012 | 核心业务逻辑必须在代码块上方写明业务含义注释 | 🔴 强制 |
| TC-AI-013 | 接口变更需在 Javadoc 中补充调用方枚举与影响评估 | 🔴 强制 |
| TC-AI-016 | 禁止以 null 作为缺省参数传递（应重载方法） | 🔴 强制 |
| TC-AI-017 | 禁止将业务状态码硬编码在调用方（统一使用 `ErrorCode` 枚举） | 🔴 强制 |
| TC-AI-018 | 未经 review 不得修改抽象类共享逻辑 | 🔴 强制 |
| TC-AI-023 | 始终使用中文回复和书写文档 | 🔴 强制 |

### 11.3 AI 应参考的现有范式

> **数据来源**：`specs/SDD-项目技术指南文档.md` 第 4.4 节

| 需要写的东西 | 参考范式文件 |
|-------------|------------|
| 新 Controller | `agent-demo-web/.../controller/AgentController.java` |
| 新 Agent 实现 | `agent-demo-agent/.../single/SimpleAgent.java` |
| 新工具 | `agent-demo-tools/.../builtin/HttpTool.java` |
| 新配置类 | `agent-demo-llm/.../config/ArkProperties.java` |
| 新工厂类 / 注册表路由 | `agent-demo-llm/.../registry/ModelFactory.java`（CR-002 重构后路径） |
| 新 LLM 厂商策略实现 | `agent-demo-llm/.../provider/ArkLlmServiceProvider.java`（CR-002 新增，参考接入新厂商） |
| 新能力接口（ISP 拆分） | `agent-demo-llm/.../capability/ChatModelProvider.java`（CR-002 新增） |
| 新枚举 | `agent-demo-common/.../enums/AgentType.java` |
| 新错误码 | `agent-demo-common/.../exception/ErrorCode.java` |
| 新常量类 | `agent-demo-common/.../constant/ModelConstants.java` |
| 新 Manager | `agent-demo-memory/.../shortterm/ChatMemoryManager.java` |
| 新 DTO | `agent-demo-web/.../dto/ChatRequest.java` |
| 新全局异常处理 | `agent-demo-web/.../handler/GlobalExceptionHandler.java` |

### 11.4 AI Skills 列表

> **数据来源**：`.agents/skills/` 目录扫描

| Skill 名称 | 用途 |
|-----------|------|
| `project-specs-creator` | 创建项目规格文档（specs/ 体系） |
| `knowledge-base-generator` | 生成/更新 KNOWLEDGE_BASE.md |
| `feature-requirements-clarification` | 功能需求澄清 |
| `feature-tech-design` | 功能技术设计 |
| `feature-task-planning` | 功能任务规划 |
| `feature-implementation` | 功能编码实现（TDD 驱动） |
| `feature-evolution` | 功能迭代变更管理 |
| `bugfix-workflow` | BUG 修复流程 |

### 11.5 迭代文档存放规范

```
specs/features/{yyyy-MM-dd}/{功能名}/
├── spec.md              # 功能规格（AC 验收标准）
├── tasks.md             # 任务清单（TDD 适配）
└── design.md            # 技术设计（API/数据库/核心逻辑）
```

> **数据来源**：`specs/SDD-项目技术指南文档.md` 第 4 节、`.agents/skills/` 目录

---

## 十二、常见问题与排障

### 12.1 编译与启动错误

| 错误现象 | 原因 | 解决方案 |
|---------|------|---------|
| `java: error: release version 17 not supported` | JDK 版本低于 17 | 安装 OpenJDK 17+，IDE 配置 JDK 17 |
| `cannot find symbol class Tool` | 未引入 langchain4j 依赖 | 检查 `agent-demo-tools/pom.xml` 是否引入 `langchain4j` |
| `ARK_API_KEY 未配置` | 环境变量未设置 | LLM 配置已迁移至前端动态管理，通过前端"LLM 配置"页面添加厂商和配置 API Key |
| `BAILIAN_API_KEY 未配置` | 环境变量未设置或 `llm.provider` 切换为 bailian 时未配置 | LLM 配置已迁移至前端动态管理，通过前端"LLM 配置"页面添加厂商和配置 API Key |
| `LLM API Key 无效` (5004) | API Key 错误或过期 | 在前端"LLM 配置"页面检查对应厂商的 API Key 是否正确，或重新配置 |
| `端口 8080 被占用` | 端口冲突 | 修改 `application.yml` 的 `server.port` |
| `循环依赖` 错误 | 构造函数中调用了懒加载方法 | 确认 SimpleAgent/ToolRegistry 使用懒加载模式 |

### 12.2 运行时异常

| 错误码 | 异常现象 | 排查方法 |
|--------|---------|---------|
| 5001 | LLM 调用失败 | 检查网络、API Key、模型名是否正确 |
| 5002 | LLM 调用超时 | 调整厂商配置中的 timeout 参数，或切换更快的模型 |
| 5003 | LLM 调用被限流 | 降低调用频率，检查套餐额度 |
| 5004 | LLM API Key 无效 | 在前端"LLM 配置"页面检查对应厂商的 API Key 是否正确 |
| 5006 | LLM 模型不存在（CR-003 新增） | 检查 `modelId` 是否正确，对应厂商和模型是否已在前端配置 |
| 5007 | LLM 能力不支持（CR-003 新增） | 检查模型类型是否正确（如需要 chat 模型但配置了 embedding 模型） |
| 5100 | 工具执行失败 | 查看日志堆栈，检查工具参数 |
| 5101 | 工具不存在 | 检查工具类是否加 `@Component` + `@Tool` 注解 |
| 5102 | 工具参数无效 | 检查工具方法参数校验逻辑 |
| 5201 | 会话不存在 | 传入无效 sessionId 会自动新建，无需处理 |
| 5202 | 会话已过期 | 会话超时自动清理，传入无效 sessionId 会自动新建 |
| 5400 | MCP 连接失败 | 检查 MCP Server URL/命令是否正确、网络是否可达 |
| 5401 | MCP 工具调用失败 | 查看日志堆栈，检查 MCP Server 是否在线、参数是否正确、是否超时 |
| 5402 | MCP Server 名称已存在 | 检查是否已有同名 Server，先删除再添加 |
| 5404 | 不支持的传输方式 | transport 字段仅支持 stdio / sse / http |
| 5405 | MCP 模块已禁用 | 检查 application.yml 中 mcp.enabled 是否为 true |
| 5000 | 系统异常 | 查看日志完整堆栈定位问题 |

### 12.3 必须遵守的技术规范

> **数据来源**：`specs/SDD-项目技术指南文档.md` 第 3 节

#### 12.3.1 Java 语言级约束（JDK 17 限定）

| 约束 | 说明 |
|------|------|
| 🔴 禁止使用虚拟线程 | Java 21+ 特性 |
| 🔴 禁止使用模式匹配 for switch | Java 21+ 正式版 |
| 🔴 禁止使用 `SequencedCollection` 接口 | Java 21+ |
| 🟢 允许使用 record/sealed/文本块 | Java 17 特性 |

#### 12.3.2 Spring Boot 框架约束

| 约束 | 说明 |
|------|------|
| 🔴 Spring Boot 版本锁定 3.2.5 | 禁止升级至 3.3+ |
| 🔴 Controller 返回值必须用 `Result<T>` 包装 | 前端统一解析 `code/data/msg` |
| 🔴 业务异常使用 `BusinessException` + `ErrorCode` | 禁止 `throw new RuntimeException` |
| 🔴 API 路径前缀统一 `/api/{module}/` | - |
| 🔴 全局异常通过 `@RestControllerAdvice` 统一拦截 | - |
| 🔴 定时任务使用 `@Scheduled`，主启动类需 `@EnableScheduling` | - |

#### 12.3.3 LangChain4j 约束

| 约束 | 说明 |
|------|------|
| 🔴 LangChain4j 版本锁定 1.0.0，所有子模块版本必须一致 | - |
| 🔴 Agent 接口必须通过 `AiServices.builder()` 构建代理 | 禁止手写 ReAct 循环 |
| 🔴 工具方法必须使用 `@Tool` 注解并填写功能描述 | - |
| 🔴 会话记忆使用 `CompressingChatMemory`（滚动摘要压缩，按 sessionId 隔离） | 附件消息不参与压缩淘汰 |
| 🔴 LLM 模型实例必须缓存复用 | 禁止每次调用重新构建 |

#### 12.3.4 Maven 构建约束

| 约束 | 说明 |
|------|------|
| 🔴 根 POM 继承 `spring-boot-starter-parent:3.2.5` | - |
| 🔴 第三方依赖版本通过 `agent-demo-bom` BOM 管理 | 子模块禁止声明版本号 |
| 🔴 编译顺序：bom -> common -> llm/tools/memory -> agent -> app -> web -> bootstrap | - |
| 🔴 项目统一版本号 `1.0.0` | - |
| 🔴 全局属性：`java.version=17`、`sourceEncoding=UTF-8` | - |

### 12.4 调试技巧

| 场景 | 技巧 |
|------|------|
| Agent 不调用工具 | 检查工具类是否有 `@Component` + `@Tool` 注解，ToolRegistry 是否扫描到 |
| Agent 回复不准确 | 调整 `prompts/roles/` 或 `prompts/scenarios/` 模板文件内容，或通过 `agent.default-role` 切换角色 |
| Token 消耗过大 | 降低 `agent.chat-memory-window-size`，或切换 Lite 模型 |
| 会话记忆丢失 | 检查 sessionId 是否正确传递，记忆窗口是否过小 |
| HTTP 工具被拦截 | 检查 SSRF 防护规则，确认 URL 不含内网地址 |
| 文件读取失败 | 检查文件是否在 `agent.file-allowed-dir` 白名单目录内 |
| 日志无 traceId | 检查 `TraceIdInterceptor` 是否注册到 `WebConfig` |
| MCP Server 连接失败 | 检查 URL/命令是否正确；stdio 模式 Windows 下已自动适配（resolveWindowsCommand 将 npx 补全为 npx.cmd）；连接失败消息现会包含根因（如 CreateProcess error=2）；npx 首次下载依赖可能需要更长的 initializationTimeout |
| MCP 工具返回 image 类型报错 | CR-002 后：McpToolExecutor 将 Unsupported content type 异常视为预期行为，统一从 Wrapper 缓存通过 McpContentParser 解析所有内容类型（text/image/audio/resource/structuredContent/unknown）。确认 McpContentParser 已注入 McpToolExecutor 构造器 |
| LLM 调用 MCP 工具时参数名错误 | 检查 McpServerManager.serializeParameters 是否正确序列化 JsonObjectSchema；确认 McpToolFactory.parseParametersSchema 生成了结构化参数描述 |
| MCP Server 启动超时 | 确认 DefaultMcpClient.Builder 配置了 initializationTimeout(60s)；npx 首次运行需下载依赖，可能需要更长超时 |

### 12.5 Git 仓库

| 仓库类型 | 地址 |
|---------|------|
| 项目根目录 | `d:\project_demo\agent_demo` |
| Git 仓库 | 本地仓库（未配置远程） |

### 12.6 提交规范

> **数据来源**：用户规则中的"提交规范"

遵循 Conventional Commits 规范：

```
<type>[scope]: <description>

<body 用中文描述业务背景和变更原因>
```

**type 类型**：
- `feat`：新功能
- `fix`：BUG 修复
- `docs`：文档变更
- `style`：代码格式（不影响功能）
- `refactor`：重构
- `test`：测试
- `chore`：构建/工具变更

**示例**：

```
docs: update KNOWLEDGE_BASE.md to version 1.0

初始化项目知识库文档，覆盖 12 章标准结构，
基于 specs/ 文档体系与项目源码深度调研生成。
```

> **数据来源**：`specs/SDD-项目技术指南文档.md`、`specs/业务架构文档.md`、项目源码

---

## 附录：变更日志

| 版本 | 日期 | 变更内容 |
|------|------|---------|
| v1.0 | 2026-07-20 | 初始版本，基于 specs/ 文档体系与项目源码生成 12 章完整知识库 |
| v1.1 | 2026-07-21 | 新增前端对话模块（Vue 3 + Vite + TypeScript + Pinia），SSE 流式接口，10 条前端业务规则（BR-FE-001~010），前端设计系统（Refined Dark Tech），localStorage 持久化，更新项目结构（agent-demo-frontend 模块），更新技术栈与开发环境章节 |
| v1.2 | 2026-07-22 | CR-001 深度思考与 Markdown 渲染：新增 4 条前端规则（BR-FE-011~014）、1 条 LLM 规则（BR-LLM-007）、1 条 Agent 规则（BR-AGT-007）、1 条 Web 规则（BR-WEB-010）；更新 5.8 节 SSE 流式对话流程（含 enableThinking 分流 + reasoning 事件）；更新 4.1/4.3 节工程结构（新增 ArkThinkingStreamingChatModel/ThinkingTokenStream 等）；更新 3.4 节前端技术栈（marked + DOMPurify） |
| v1.3 | 2026-07-23 | 深度思考模式优化 CR-001（动态工具声明）：ToolSchemaConverter 新增 convertToDescriptionText() 方法动态生成工具描述；AgentConfig.thinkingReactSystemPrompt 移除硬编码工具描述；SimpleAgent.buildReActMessagesWithMemory() 动态拼接工具描述到系统提示词；新增 BR-THINK-002 业务规则（工具描述动态生成，不硬编码）；更新 4.3 节工程结构（tools/registry 新增 ToolSchemaConverter，agent/config 新增 thinkingReactSystemPrompt） |
| v1.4 | 2026-07-24 | RAG 知识库问答模块完整实现：agent-demo-rag 从空模块变为完整实现（20 个源文件）；新增知识库管理（创建/列表/级联删除）、文档管理（上传/异步处理/状态查询/删除）、文档解析（txt/md/pdf，PDFBox 3.x）、向量语义检索（InMemoryEmbeddingStore 可切换 MilvusEmbeddingStore）、Agent 工具集成（KnowledgeRetrieverTool @Tool）；新增 12 条 RAG 业务规则（BR-RAG-001~012）；新增 7 个错误码（5303-5309）；新增 7 个 REST API（/api/rag/*）；新增 pdfbox 3.0.3 依赖；新增 rag.* 配置段；更新能力矩阵 RAG 状态为已实现；更新工程结构/模块依赖/模块分层；web 模块新增 rag 依赖 |
| v1.5 | 2026-07-28 | RAG 知识库前端管理界面完整实现：新增 9 个前端组件（NavBar/KnowledgeBasePage/KnowledgeBaseList/CreateKnowledgeBaseDialog/DocumentList/DocumentUploader/KnowledgeBaseSelector）+ 1 个 API 封装（rag.ts）+ 1 个 Pinia Store（rag.ts）；修改 App.vue（条件渲染切换对话/知识库页面）、ChatWindow/MessageInput（知识库选择器集成）、session.ts（会话级知识库选择状态）、chat.ts（streamChat 新增 knowledgeBases 参数）；后端 ChatRequest 新增 knowledgeBases 字段、AgentController 提示词注入（3 处调用点）；新增 10 条前端知识库管理规则（BR-RAG-FE-001~010）；新增 2 条 RAG 规则（BR-RAG-013 批量向量化约束、BR-RAG-014 提示词注入方案）；修复向量化 API input limit 问题（batchEmbed 每批 10 条）；更新能力矩阵新增前端知识库管理能力；更新工程结构/前端模块目录 |
| v1.6 | 2026-07-29 | 文档分割模块化与Token展示 + CR-001 分割后合并过短块：新增 agent-demo-splitter 模块（从 RAG 模块抽取文档解析/分割为独立模块）；新增 DocumentLoader/ParsedDocument（文档解析）、TypedDocumentSplitter/DocumentSplitterRegistry（分割器路由）、MarkdownDocumentSplitter/PdfDocumentSplitter/TxtDocumentSplitter/GenericDocumentSplitter（专属分割器）、CascadeSplitter（多级级联切分）、ChunkMerger（分割后合并过短块，CR-001 新增）、SplitterTokenEstimator（Token 估算）；CascadeSplitter 职责分离（仅切分，合并迁移至 ChunkMerger）；ChunkMerger 支持全局合并 + 按 metadata key 分组合并（PDF 不跨页、MD 不跨节）；SplitterProperties 按文件类型配置 size/overlap/minSize；新增 3 条 RAG 业务规则（BR-RAG-015~017）；更新工程结构/模块依赖/模块分层；更新 rag.splitter 配置段 |
| v1.7 | 2026-07-30 | CR-001 PDF 表格解析优化：新增 tabula-java 1.0.5 依赖；DocumentLoader.parsePdf() 重构为混合提取策略（tabula-java 表格 + PDFBox 纯文本）；新增 extractTablesAsMarkdown() 方法；新增 3 条 AC（AC-028/029/030）；新增 BR-RAG-018 业务规则 |
| v1.8 | 2026-07-30 | CR-002 分块数据携带文件元数据：DocumentChunk 新增 metadata 字段（Map<String, String>）；DocumentSplitterRegistry.split() 新增 fileName 参数 + enrichMetadata() 注入 fileName；DocumentService 保存 DocumentChunk 时从 TextSegment.metadata 提取来源元数据；KnowledgeRetrieverTool 新增 buildSourcePrefix() 方法，检索结果注入"来源: 文件名 (格式) 页码/章节"前缀；新增 2 条 AC（AC-031/032）；新增 BR-RAG-019 业务规则 |
| v1.9 | 2026-07-30 | 多 LLM 提供商支持（阿里百炼）：新增 LlmProvider 枚举（ARK/BAILIAN）、LlmProperties（llm.provider 配置绑定）、BailianProperties（bailian.* 配置绑定）；ModelFactory 新增提供商路由逻辑（if-else 根据 provider 切换配置源）；新增支持阿里百炼同步对话、流式对话、Embedding 模型；API Key 隔离校验（各验各的）；Embedding 模型跟随提供商切换；新增 6 条 LLM 业务规则（BR-LLM-008~013）；更新 3.2 节 LLM 提供商配置表、3.3 节模型清单、5.6 节场景路由框架、10.2 节环境变量、10.4 节关键配置项 |
| v2.0 | 2026-07-31 | CR-003 知识库动态 Tool 注册：新增 KnowledgeBaseToolFactory（CGLIB 动态代理生成 @Tool Bean）、KnowledgeBaseToolRegistrar（启动批量注册 + 生命周期管理）；KnowledgeRetrieverTool 从 @Tool 改为 @Component，新增 searchByKbId(kbId, query) 方法；ToolRegistry 新增 register/unregisterTool 方法支持动态 Tool；KnowledgeBaseService.create()/delete() 联动 Tool 注册/注销；新增 3 条 AC（AC-033/034/035）；更新 BR-RAG-011（动态 Tool 模式）、新增 BR-RAG-020（启动批量注册）；技术决策 1/9/10 更新；更新 RAG 模块描述为"动态 Tool 注册，每个知识库独立 Tool" |
| v2.1 | 2026-07-31 | CR-003 实现细节修正：KnowledgeBaseToolFactory 从 CGLIB 改为 ByteBuddy 1.14.19，在生成方法上直接写入 @Tool 注解以被 LangChain4j ToolSpecifications 识别；SimpleAgent 增加 lastToolCount 检测，Tool 数量变化后重建 delegate 绑定最新工具；agent-demo-rag 新增对 agent-demo-tools 依赖；更新 KNOWLEDGE_BASE.md 技术栈与模块描述 |
| v2.2 | 2026-08-03 | CR-003 文档同步：更新 6.2 节 Agent 开发范式代码示例（含 lastToolCount 检测逻辑）；更新 5.9.1 节懒加载机制表（SimpleAgent.delegate 增加 Tool 变化重建说明）；更新 BR-AGT-003（Tool 数量变化重建 delegate）、BR-TOOL-007（新增注销方法）；更新 4.2 节模块依赖方向（rag 新增 tools 依赖）；更新 4.3 节 agent-demo-tools 内部分层描述；同步更新 RAG/工具调用/Agent 编排模块业务说明书与技术架构文档 |
| v2.3 | 2026-08-05 | CR-002 agent-demo-llm 模块重构（能力矩阵 + 提供商策略 + 注册表）：4.3 节 agent-demo-llm 内部分层从单一 factory 包重构为 6 个职责子包（config/capability/provider/thinking/registry/exception）；4.4 节包命名规范同步更新；5.6 节模型场景路由框架新增注册表路由架构图与扩展点说明；5.9.1 节懒加载机制表更新 ModelFactory.embeddingModel 持有者迁移；7.2 节核心内存数据结构补充 providerRegistry 新增项与缓存持有者变更；8.1 节安全措施矩阵更新 API Key 校验实现类位置；9.1 节新增 5 条 LLM 业务规则（BR-LLM-014~018，对应 AC-018~022），修正 BR-LLM-012（阿里百炼已支持深度思考）；9.9 节错误码区间新增 LLM_PROVIDER_NOT_FOUND(5006)/LLM_CAPABILITY_NOT_SUPPORTED(5007)；11.3 节 AI 参考范式新增厂商策略实现、能力接口、新工厂类路径；12.2 节运行时异常新增 5006/5007 排查方法；1.4 节能力矩阵 LLM 调用能力描述补充 |
| v2.4 | 2026-08-07 | MCP 协议模块完整实现 + 运行时调试修复：1.4 节能力矩阵 MCP 状态从 🚧 规划中 更新为 ✅ 已实现；2.2 节文档地图新增 MCP 协议模块业务说明书；3.1 节技术栈 langchain4j-mcp 从规划中更新为 1.17.2-beta27 已实现；4.1 节工程结构 MCP 模块从空模块更新为完整实现；4.2 节模块依赖更新（mcp 不再标记规划中，web 新增 mcp 依赖）；4.3 节新增 agent-demo-mcp 内部分层（config/entity/client/tool/service），更新 agent-demo-web 分层（新增 McpController/McpDTO）；4.4 节包命名新增 mcp 子包；7.2 节新增 MCP Server 注册表数据结构；9.7 节新增 19 条 MCP 业务规则（BR-MCP-001~019，含运行时调试新增的 BR-MCP-016~019：参数 Schema 手动序列化、结构化参数描述、初始化超时、Unsupported content type 异常捕获）；9.10 节错误码区间更新 MCP 完整 7 个错误码（5400-5406）；9.8~9.12 节编号顺延；10.4 节新增 mcp.* 配置段（含 mermaid-mcp HTTP 传输 + fetch stdio 传输示例）；12.2 节运行时异常新增 5400/5401/5402/5404/5405 排查方法；12.4 节调试技巧新增 MCP 连接失败/图片类型报错/参数名错误/启动超时 4 条排障 |
| v2.5 | 2026-08-07 | CR-002 MCP 输出解析结构化重构：4.3 节 agent-demo-mcp tool 包新增 McpContentParser（内容类型策略分发器），McpToolExecutor 描述更新为统一从 Wrapper 缓存通过 McpContentParser 解析；9.7 节 BR-MCP-019 更新（从异常捕获改为统一解析预期行为），新增 BR-MCP-023（统一解析路径）、BR-MCP-024（内容类型策略分发矩阵）、BR-MCP-025（未知类型静默跳过）；12.4 节调试技巧更新 MCP 图片类型排障条目（从异常捕获改为 McpContentParser 统一解析） |
| v2.6 | 2026-08-07 | Prompt 优化（角色×场景模板矩阵）：4.3 节 agent-demo-agent 新增 prompt 包（PromptTemplateLoader）；9.2 节 BR-AGT-005 更新为 PromptTemplateLoader 组合机制，BR-AGT-007 更新为场景模板引用，新增 BR-AGT-008（提示词外部化到模板文件）和 BR-AGT-009（{{tools}} 占位符运行时替换）；10.4 节移除 default-system-prompt/thinking-system-prompt/thinking-react-system-prompt 配置项，新增 default-role: general；数据架构文档 5.3 节提示词模板从 3 个旧文件更新为 4 角色 + 6 场景模板矩阵 |
| v2.7 | 2026-08-10 | LLM 厂商模型配置（CR-003 重构）：移除 Provider/Properties/capability 模式，ModelFactory 改为从 LlmConfigStore 动态创建模型；新增 LlmConfigStore/LlmVendorConfig/LlmModelConfig/PredefinedVendorCatalog；新增 LlmConfigController（9 个 API 接口）；SimpleAgent delegate 改为按 modelId 隔离的 ConcurrentHashMap 缓存；ChatRequest.model 字段启用为 modelId；前端新增 LLM 配置页面（LlmConfigPage/VendorCard/VendorEditDialog）和 ModelSelector 模型选择器；配置同步至 localStorage，后端重启后自动恢复；移除 application.yml 中 llm/ark/bailian 配置段；新增 8 个错误码（5008-5015）；新增 20 条 BR-LLM-CONF 业务规则 |
| v2.8 | 2026-08-07 | MCP 服务管理页面 + stdio 命令适配 BUG 修复：1.4/2.2/4.1/4.3 节新增设置页面与 MCP 服务管理（SettingsPage/McpServicePage/McpServerCard/McpJsonConfigEditor + api/mcp.ts + stores/mcp.ts + utils/mcp-config.ts JSON 配置解析器）；NavBar 导航从"LLM 配置"迁移为"设置"；后端 McpServerResponse 新增 url/command/args 字段；9.7 节新增 BR-MCP-026（Windows stdio 命令适配 resolveWindowsCommand，npx→npx.cmd）、BR-MCP-027（连接失败消息含根因 rootCauseMessage），新增 9.8 节前端 MCP 服务管理规则 12 条（BR-MCP-FE-001~012），原 9.8~9.12 顺延为 9.9~9.13；12.4 节 MCP 图片排障已更新 |
| v2.9 | 2026-08-12 | Agent 工具按需加载（feature 2026-08-12）：1.4 能力矩阵工具调用新增按需加载；4.1/4.3 节工程结构更新（ToolRegistry 新增 resolveTools/getAvailableTools/getDefaultTools/register(tool,serverName)、SimpleAgent 新增 sessionToolIds 会话缓存 + toolsFingerprint 缓存键、AgentController 新增 GET /api/agent/tools、common 新增 dto/ToolInfo、前端新增 ToolSelector/ToolManagementPage）；9.2 节新增 BR-AGT-011/012，9.3 节新增 BR-TOOL-010~019（共 10 条，工具标识 category:name、通配符、默认工具不可排除、会话级绑定）；10.4 节新增 agent.tools 配置段（default-tools 默认加载 + optional 按需指定） |
| v3.0 | 2026-08-13 | 应用编排层 P2 多模式编排（feature 2026-08-13）：1.4 能力矩阵多 Agent 协作/工作流编排更新为 ✅ 已实现；4.1 节 agent-demo-app 更新为 P2 完整实现（core 模型 + strategy 策略层 + execution 基础设施 + service 协调层 + template 预置模板）；9.10 节错误码区间新增 5500-5599 工作流段（含 5506 WORKFLOW_MODE_NOT_SUPPORTED P2 新增）；策略模式三层分离架构（协调层 WorkflowExecutionService + 策略层 WorkflowExecutionStrategy 4 实现 + 基础设施 AgentExecutor/WorkflowEventPublisher/WorkflowContext） |
| v3.1 | 2026-08-21 | Agent-Human 交互（HITL，feature 20260820_agent-human-interaction）：1.4 能力矩阵新增"人机交互 HITL"行；4.1/4.3 节工程结构更新（agent-demo-agent 新增 HitlTokenStream/HITLReActStream/HumanInteractionManager/PendingInteraction，agent-demo-tools 新增 AskUserTool，前端新增 ConfirmCard.vue，ToolRegistry/ToolSchemaConverter 工具去重与数组类型 BUG 修复）；5.8 节 SSE 事件协议新增 ask_user 事件；7.2 节内存数据结构新增 pendingInteractions；9.16 节新增 12 条 HITL 业务规则（BR-HITL-001~012）；前端 HITL 渲染链路（chat.ts onAskUser 回调 + enableHitl 参数 / session.ts askUserData + isWaitingForUserInput / MessageItem.vue 双形态渲染 / ChatWindow.vue 开关与回复闭环） |
| v3.3 | 2026-08-25 | 工作流 HITL（feature 20260824_workflow-hitl）：1.4 能力矩阵工作流编排行新增 WAITING_USER 状态，人机交互 HITL 行更新为"单 Agent + 工作流"双覆盖；4.1/4.3 节工程结构更新（agent-demo-app 新增 @HumanCheckpoint 注解 / WorkflowHITLState 快照 / WAITING_USER 状态 / hitlReply 恢复 / 并行 HITL 排队 PENDING_HITL_KEY，编排组件新增 SequentialExecuteView/ParallelExecuteView/LoopExecuteView/ConditionalExecuteView/SupervisorExecuteView 5 个模式专属视图并复用 AskUserCard/ConfirmCard，api/workflow.ts 从 7 个扩展为 8 个 REST 接口新增 replyToWorkflow HITL 回复）；5.8 节 SSE 事件协议新增工作流 SSE 事件表（workflow_waiting/workflow_resumed，复用 ask_user 事件）；7.2 节内存数据结构更新 resumableStates 为"暂停恢复快照（PAUSED 断点 + WAITING_USER HITL）"；9.18 节新增 12 条工作流 HITL 业务规则（BR-WHITL-001~012，含 WAITING_USER 状态语义分离、@HumanCheckpoint 反射检测、并行 HITL 排队、30 分钟超时清理、5 个模式视图 HITL 等待横幅 BUG 沉淀）；修复【研究-分析-总结】模板模式特定执行视图缺少 HITL 等待横幅导致页面卡住的 BUG（SequentialExecuteView 等 5 个模式视图全部补齐，useWorkflowStream.startExecution 重置 HITL 状态） |
| v3.4 | 2026-08-28 | Agent 上下文工程优化（feature 20260828_agent-context-engineering）：5.4 节记忆窗口淘汰策略从 FIFO 更新为滚动摘要压缩（CompressingChatMemory 窗口 20/压缩至 10/附件保护/降级 FIFO）；9.2 节新增 BR-AGT-016（工具协议以 tools 参数为权威、基础工具集冻结）、BR-AGT-017（技能段附件化保证缓存稳定）、BR-AGT-018（末轮 `<agent_status>` 状态栏收尾）、BR-AGT-019（规划判断携带会话历史）；9.4 节更新 BR-MEM-003（滚动压缩），新增 BR-MEM-006（附件帧标记三类型，不参与压缩淘汰）、BR-MEM-007（输入框架标记剥离防伪造）、BR-MEM-008（记忆写入唯一化）；工程结构新增 CompressingChatMemory/MemoryCompressionProperties；修复 Spring bean 多构造器装配启动异常（AgentController/ChatMemoryManager/SkillLoadTool 补 @Autowired） |
| v3.5 | 2026-08-31 | CR-001 Out of Scope 质量债清偿（feature 20260828_agent-context-engineering）：9.2 节新增 BR-AGT-020（模板公共规则单源化，{{include:hitl-shared-rules}} 共享片段 + PromptTemplateLoader 片段加载机制）、BR-AGT-021（场景模板 XML 语义标签段，护栏条文零变更）、BR-AGT-022（工具引导语单源，convertToDescriptionText 移除尾部通用引导）、BR-AGT-023（AgentConfig 降级默认值同步 + 一致性守护测试防漂移）；4.3 节 prompt/ 补充片段加载机制、ToolSchemaConverter 补充引导移除；数据架构文档 5.3 节提示词模板矩阵更新（移除已删除的 thinking/react，补齐 hitl 与 fragments/ 片段）；AgentConfig.defaultSystemPrompt 补"读取文件"语义与 chat 模板对齐
| v3.6 | 2026-08-31 | 完整评估体系（CR-002，feature 20260829_langsmith-observability Task-26~34）：新增第 12 能力域 agent-demo-evaluation 评估模块（本地评估 harness）；1.4 能力矩阵新增"评估能力域"行并更新可观测性行（AiServices 工具采集补链）；2.2 文档地图新增评估模块业务说明书；4.1/4.2/4.3 节工程结构/依赖方向/内部分层新增评估模块；9.20 节新增 11 条评估能力域规则（BR-EVAL-001~011，含多源 judge、幻觉 veto 代码层强制、±30pp 噪声带宽、CLI 种子注入、工具轨迹观测）；10.2 节补充评估 CLI 环境变量（ARK/BAILIAN Key）；specs/modules 新增评估模块-业务说明书.md 并编目 README（第 12 能力域）；同步业务架构/技术架构文档；代码审查（ai-agent-code-review）+ EDD 补跑（judge-v1→v2 TUNE，解析率 100%，首建基线 Pass^runs=70%/工具选择率 100%/脱敏拦截率 100%） |

---

**文档维护说明**：

1. 本文档由 `knowledge-base-generator` 技能自动生成，每次功能迭代后应增量更新
2. 第五章（业务域知识图谱）与第九章（关键业务规则清单）需结合最新代码验证
3. 技术栈升级时，同步更新第三章与第十二章
4. 新增模块时，更新第四章工程结构与第二章文档地图
5. 所有数据来源已标注，便于追溯验证
| v3.7 | 2026-09-01 | 工具产出安全清洗（feature 20260826_tool-output-sanitization，CR-001 + CR-004 双批交付并经代码审查通过）：1.4 能力矩阵新增"工具产出安全清洗"行；4.3 节 agent-demo-tools 内部分层新增 sanitize 包（ToolOutputSanitizer/SanitizeStage SPI/4 变换段/临时文件/配置类）；9.3 节 BR-TOOL-004 防腐更新（HTTP 10KB 截断 -> 统一 maxChars 4000 前缀+临时文件机制）；9.21 节新增 7 条工具产出安全清洗规则（BR-TOOL-032~038：包裹声明/降级铁律/分级处置/秘密脱敏/随机分隔符/注入点契约/SPI 扩展与规则预编译）；10.4 节关键配置项新增 agent.tool.sanitize.* 段；specs/modules/工具调用模块-业务说明书.md 同步（§1 概述/§3.4 防腐/新增 §3.12/§5/§7/§9 BR-TOOL-004 防腐+032~038/§10 降级矩阵/§11/§12 安全矩阵）；技术架构文档-TOGAF.md 更新安全沙箱约束与 tools 内部分层（sanitize 包）；MCP 协议模块业务说明书工具执行链补清洗环节 |