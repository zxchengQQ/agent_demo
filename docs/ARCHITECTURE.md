# AI Agent 示例项目架构设计文档

| 项目 | 说明 |
|---|---|
| 项目名称 | agent-demo |
| 文档版本 | v1.0 |
| 创建日期 | 2026-07-20 |
| 技术栈 | Java 17 + Spring Boot 3.2 + LangChain4j 1.x |
| LLM 提供商 | 火山引擎方舟 Coding Plan |

---

## 一、项目定位

### 1.1 目标

构建面向学习实践的企业级 AI Agent 示例工程，功能覆盖较全面，涵盖以下能力域：

- 单 Agent 工具调用（ReAct 循环、Function Calling）
- 多 Agent 协作（Sequential / Hierarchical 模式）
- RAG 知识库问答（文档加载、分块、向量化、检索）
- MCP 协议支持（客户端调用 + 服务端暴露）
- 有状态工作流编排（分支、重试、Human-in-the-loop）
- 可观测性（链路追踪、Token 统计、成本监控）

### 1.2 非目标

- 不追求商业产品级完整度，聚焦学习与原理验证
- 不实现完整的权限/RBAC/多租户治理体系
- 不部署到 K8s 等生产编排环境

### 1.3 约束

- LLM 提供商：火山引擎方舟 Coding Plan（国内可达、按次计费、多模型可选）
- 交付形态：完整工程脚手架（多模块 Maven 项目）
- 运行环境：Windows / Linux / macOS 本地可运行

---

## 二、技术栈选型与决策理由

### 2.1 最终技术栈

```
JDK 17 + Spring Boot 3.2.x + LangChain4j 1.16+ + Maven
```

### 2.2 候选方案对比

基于 2026 年 Q2 最新调研，对四类主流方案进行了横向对比：

| 决策维度 | Python (LangGraph) | **Java (LangChain4j)** ✅ | Java (Spring AI) | TypeScript (Mastra) |
|---|---|---|---|---|
| 企业级生产就绪 | ✅ Anthropic/Uber 背书 | ✅ 企业级、5k+ stars | ✅ Spring 生态原生 | ⚠️ 较新 |
| Agent 功能全面性 | ✅ 最全（图编排/HITL） | ✅ 全（RAG/Agent/工作流/MCP/多 Agent） | ⚠️ 基础（工作流实验性） | ✅ 较全 |
| 火山引擎接入成熟度 | ✅ 原生支持 | ✅ OpenAI 适配器+大量豆包案例 | ✅ 专用 starter+OpenAI 兼容 | ✅ OpenAI 兼容 |
| MCP 原生支持 | ✅ | ✅ 1.x 原生（`langchain4j-agentic`） | ⚠️ 实验性 | ✅ |
| 多 Agent 协作 | ✅ 最强 | ✅ 完善 | ⚠️ 基础 | ✅ |
| 声明式开发体验 | ❌ 命令式为主 | ✅ `@AiService` 接口+注解 | ⚠️ 链式 API | ✅ |
| 类型安全 | ❌ 动态类型 | ✅ 强类型 | ✅ 强类型 | ✅ |
| 框架无关性 | ❌ 绑定 Python 生态 | ✅ Spring/Quarkus/纯 Java SE | ❌ 绑定 Spring | ❌ 绑定 Node |
| 工程脚手架成熟度 | ✅ FastAPI | ✅ Spring Boot（最强） | ✅ Spring Boot | ✅ |
| Guardrails 生产安全 | ✅ | ✅ 1.x 输入/输出守护 | ⚠️ 弱 | ⚠️ 弱 |
| 与企业 Java 技能栈契合 | ❌ | ✅ | ✅ | ❌ |

### 2.3 选择 LangChain4j 的核心理由

1. **企业级首选**：Java 是金融/政企/制造业的主流语言，Spring Boot 3.x 是事实标准，便于后续迁移到生产
2. **功能全面满足学习需求**：LangChain4j 1.x 已将 Agent 能力拆分为独立模块 `langchain4j-agentic`，原生支持 MCP / A2A / 常见 agentic 模式，覆盖单 Agent、多 Agent、RAG、工作流全部场景
3. **声明式开发体验优秀**：`@AiService` + `@Tool` + `@SystemMessage` 注解式编程，Controller 层零样板，像 MyBatis Mapper 一样简洁
4. **火山引擎接入成熟**：通过 `langchain4j-open-ai` 适配器 + OpenAI 兼容 Base URL，社区有大量 LangChain4j + 豆包 + Spring Boot3 实战案例，零改造成本
5. **框架无关性**：LangChain4j 不强制绑定 Spring，核心模块可独立运行，便于理解 Agent 原理
6. **生态最丰富**：30+ LLM 提供商、20+ 向量数据库，文档最完整，学习资源多
7. **生产级安全**：1.x 新增 Guardrails API（输入/输出守护），满足"企业项目"对安全的要求
8. **与现有环境契合**：工作环境含 Java/Maven 相关 skill，技术栈延续性好

### 2.4 不选 Spring AI 的理由

- Spring AI 的 Agent 工作流编排仍是实验性，多 Agent 协作能力基础
- LangChain4j 的 `@AiService` 声明式风格对学习 Agent 原理更直观
- LangChain4j 框架无关，学习价值更高（不被 Spring idioms 束缚）
- *注：若未来在生产 Spring 项目中深度集成，可考虑 LangChain4j + Spring AI 混用*

### 2.5 不选 Python LangGraph 的理由

- 虽然 LangGraph 是生产级标准，但用户环境暗示 Java 背景
- Java 生态已成熟，无需引入 Python 运维成本
- 学习 Agent 原理用 Java 强类型语言更易理解接口契约

---

## 三、整体架构

### 3.1 分层架构

```
┌─────────────────────────────────────────────────────────┐
│                    接入层 (Web API)                       │
│  REST Controller │ SSE 流式 │ WebSocket │ Swagger 文档   │
├─────────────────────────────────────────────────────────┤
│                    应用层 (Application)                  │
│  对话编排 │ Agent 工作流 │ RAG 问答 │ MCP 调用 │ 多 Agent 协作 │
├─────────────────────────────────────────────────────────┤
│                    能力层 (Capability)                   │
│  ┌──────────┬──────────┬──────────┬──────────┬───────┐ │
│  │ LLM 网关 │ 工具注册 │ 记忆管理 │ 向量检索 │ MCP   │ │
│  │(火山引擎)│(Tool)    │(Memory)  │(RAG)     │(Protocol)│
│  └──────────┴──────────┴──────────┴──────────┴───────┘ │
├─────────────────────────────────────────────────────────┤
│                    基础设施层 (Infra)                    │
│  配置中心 │ 日志 │ 异常 │ 可观测性 │ 持久化 │ 缓存       │
└─────────────────────────────────────────────────────────┘
```

### 3.2 核心能力矩阵

| 能力域 | 实现技术 | 学习要点 |
|---|---|---|
| LLM 调用 | LangChain4j OpenAI 适配器 -> 火山引擎 | 模型抽象、流式输出、参数调优 |
| 工具调用 | `@Tool` 注解、动态工具注册 | ReAct 循环、Function Calling |
| 记忆系统 | MessageWindowChatMemory、向量长期记忆 | 短期/长期记忆、会话隔离 |
| RAG | LangChain4j + Milvus/PGVector | 文档分块、向量化、检索策略 |
| Agent 编排 | AiServices、自定义 Agent 循环 | 单 Agent、规划-执行-反思 |
| 多 Agent | 角色协作、Sequential/Hierarchical | CrewAI 式协作模式 |
| MCP 协议 | `langchain4j-mcp` 客户端/服务端 | MCP 工具集成、A2A 通信 |
| 工作流 | 状态机编排、HITL | 分支、重试、人工介入 |
| 可观测性 | LangSmith/Langfuse、日志链路追踪 | Trace、Token 统计、成本监控 |

---

## 四、项目模块划分

采用 Maven 多模块，中等粒度拆分（平衡学习清晰度与工程复杂度），共 11 个模块：

```
agent-demo/
├── pom.xml                              # 根 POM，统一依赖版本管理
├── agent-demo-bom/                      # 物料清单，版本统一
├── agent-demo-common/                   # 公共组件
│   ├── constant/                        # 常量类（模型名、状态码）
│   ├── enums/                           # 枚举（AgentType、MessageType）
│   ├── exception/                       # 业务异常体系
│   ├── result/                          # 统一返回结果
│   └── utils/                           # 工具类
├── agent-demo-llm/                      # LLM 接入层（火山引擎适配）
│   ├── config/                          # 模型配置（多模型工厂）
│   ├── model/                           # ChatModel/EmbeddingModel 封装
│   ├── factory/                         # 模型工厂（按需切换）
│   └── guardrails/                      # 输入/输出守护
├── agent-demo-tools/                    # 工具集
│   ├── builtin/                         # 内置工具（计算器/时间/HTTP）
│   ├── search/                          # 搜索工具（联网搜索）
│   ├── database/                        # 数据库查询工具
│   ├── file/                            # 文件操作工具
│   └── registry/                        # 工具注册中心
├── agent-demo-memory/                   # 记忆模块
│   ├── shortterm/                       # 短期记忆（窗口/摘要）
│   ├── longterm/                        # 长期记忆（向量存储）
│   └── store/                           # 记忆持久化
├── agent-demo-rag/                      # RAG 模块
│   ├── loader/                          # 文档加载器（PDF/Word/MD）
│   ├── splitter/                        # 文本分块
│   ├── embedding/                       # 向量化
│   ├── store/                           # 向量存储（Milvus/PGVector）
│   └── retriever/                       # 检索器（多种策略）
├── agent-demo-mcp/                      # MCP 协议模块
│   ├── client/                          # MCP 客户端
│   ├── server/                          # MCP 服务端（暴露自有工具）
│   └── transport/                       # 传输层（stdio/SSE/HTTP）
├── agent-demo-agent/                    # Agent 核心模块
│   ├── core/                            # Agent 抽象（接口/基类）
│   ├── single/                          # 单 Agent（ReAct 循环）
│   ├── multi/                           # 多 Agent 协作
│   ├── workflow/                        # 工作流编排（状态机）
│   └── hitl/                            # Human-in-the-loop
├── agent-demo-app/                      # 应用编排层
│   ├── service/                         # 业务编排服务
│   ├── scene/                           # 场景化 Agent（客服/研究/编程）
│   └── pipeline/                        # 端到端流程
├── agent-demo-web/                      # Web 接口层
│   ├── controller/                      # REST API
│   ├── sse/                             # SSE 流式接口
│   ├── ws/                              # WebSocket
│   ├── dto/                             # 数据传输对象
│   └── config/                          # Web 配置（CORS/拦截器）
└── agent-demo-bootstrap/                # 启动模块
    ├── AgentDemoApplication.java        # 主启动类
    └── resources/
        ├── application.yml              # 主配置
        ├── application-dev.yml          # 开发环境
        ├── application-prod.yml         # 生产环境
        └── prompts/                     # 提示词模板
```

### 4.1 模块职责说明

| 模块 | 职责 | 依赖方向 |
|---|---|---|
| `agent-demo-bom` | 统一管理所有依赖版本，供其他模块引入 | 无 |
| `agent-demo-common` | 公共常量、枚举、异常、工具类、统一返回结果 | 无 |
| `agent-demo-llm` | 火山引擎 LLM 接入、模型工厂、Guardrails | common |
| `agent-demo-tools` | 工具定义、注册中心、内置工具实现 | common |
| `agent-demo-memory` | 短期/长期记忆、会话管理、持久化 | common, llm |
| `agent-demo-rag` | 文档加载、分块、向量化、检索 | common, llm |
| `agent-demo-mcp` | MCP 客户端/服务端、传输层 | common, tools |
| `agent-demo-agent` | Agent 核心、单/多 Agent、工作流、HITL | common, llm, tools, memory |
| `agent-demo-app` | 场景化业务编排、端到端 pipeline | agent, rag, mcp |
| `agent-demo-web` | REST/SSE/WS 接口、DTO、Web 配置 | app |
| `agent-demo-bootstrap` | 主启动类、资源文件、多环境配置 | web（聚合全部） |

---

## 五、关键流程设计

### 5.1 单 Agent ReAct 循环流程

```
用户输入 -> 构造 Prompt -> LLM 思考 -> 是否调用工具？
                                    ├─ 是 -> 执行工具 -> 结果回填 -> 回到 LLM 思考
                                    └─ 否 -> 生成最终回答 -> 返回
```

**关键点**：
- LangChain4j 的 `AiServices` 已内置 ReAct 循环，无需手写
- 通过 `@Tool` 注解声明工具，框架自动生成 JSON Schema 给模型
- 支持配置最大迭代次数，防止无限循环

### 5.2 RAG 知识库问答流程

```
用户问题 -> 向量化 -> 向量检索 TopK -> 重排序 -> 构造上下文 -> LLM 生成 -> 引用返回
```

**关键点**：
- 使用豆包 Embedding API（`doubao-embedding-large-text-240915`）
- 向量库选 Milvus（Standalone 模式，Docker 部署）
- 支持多种检索策略：相似度检索、MMR 重排、元数据过滤

### 5.3 多 Agent 协作流程（Sequential）

```
用户任务 -> 拆解子任务 -> Agent1 执行 -> Agent2 执行 -> ... -> 汇总 Agent -> 最终结果
```

**关键点**：
- 参考 CrewAI 角色协作模式
- 每个 Agent 有独立的 role/goal/backstory
- 支持顺序执行与层级执行两种模式

### 5.4 MCP 工具调用流程

```
Agent -> MCP 客户端 -> MCP 服务端 -> 工具执行 -> 结果返回 -> Agent 继续推理
```

**关键点**：
- MCP 客户端通过 `langchain4j-mcp` 加载外部 MCP 工具服务
- MCP 服务端可将本项目工具暴露给其他 Agent 使用
- 传输层支持 stdio（本地）/ SSE / HTTP

### 5.5 火山引擎接入流程

```
应用启动 -> 读取配置 -> 创建 OpenAiChatModel(BaseUrl=火山引擎) -> 注入 AiServices -> 调用
```

**接入示例（核心代码）**：

```java
// 模型构建
ChatLanguageModel model = OpenAiChatModel.builder()
    .baseUrl("https://ark.cn-beijing.volces.com/api/coding/v3")
    .apiKey(System.getenv("ARK_API_KEY"))
    .modelName("doubao-seed-2.0-code")
    .temperature(0.7)
    .timeout(Duration.ofSeconds(60))
    .build();

// 声明式 Agent
interface CodeAssistant {
    @SystemMessage("你是一位资深 Java 工程师")
    String chat(@UserMessage String userMessage);
}

CodeAssistant agent = AiServices.builder(CodeAssistant.class)
    .chatLanguageModel(model)
    .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
    .tools(calculatorTool, weatherTool)
    .build();
```

---

## 六、配置管理设计

### 6.1 火山引擎接入配置（核心）

```yaml
# application.yml
ark:
  coding-plan:
    # Coding Plan 专用 Base URL（按次计费）
    base-url: https://ark.cn-beijing.volces.com/api/coding/v3
    api-key: ${ARK_API_KEY}
    # 默认模型（支持 ark-code-latest 自动切换，或指定具体模型）
    default-model: doubao-seed-2.0-code
    # 备选模型
    models:
      chat: doubao-seed-2.0-pro
      lite: doubao-seed-2.0-lite
      code: doubao-seed-2.0-code
      minimax: minimax-m2.7
      glm: glm-5.2
      kimi: kimi-k2.7-code
      deepseek: deepseek-v4-pro
    # 调用参数
    timeout: 60s
    max-retries: 3
    temperature: 0.7
```

### 6.2 火山引擎 Base URL 说明

| 接入方式 | Base URL | 适用场景 |
|---|---|---|
| Coding Plan（OpenAI 兼容） | `https://ark.cn-beijing.volces.com/api/coding/v3` | **本项目使用**，按次计费 |
| Coding Plan（Anthropic 兼容） | `https://ark.cn-beijing.volces.com/api/coding` | Claude Code 等工具 |
| 方舟标准 API（OpenAI 兼容） | `https://ark.cn-beijing.volces.com/api/v3` | 按 Token 计费 |
| Agent Plan（OpenAI 兼容） | `https://ark.cn-beijing.volces.com/api/plan/v3` | 个人 Agent 场景 |

### 6.3 支持的模型清单（Coding Plan）

| 模型 | Model Name | 适用场景 |
|---|---|---|
| 豆包 Seed 2.0 Code | `doubao-seed-2.0-code` | 编程任务（默认） |
| 豆包 Seed 2.0 Pro | `doubao-seed-2.0-pro` | 通用旗舰 |
| 豆包 Seed 2.0 Lite | `doubao-seed-2.0-lite` | 轻量快速 |
| MiniMax M2.7 | `minimax-m2.7` | 全栈任务 |
| GLM 5.2 | `glm-5.2` | Agent 能力强 |
| Kimi K2.7 Code | `kimi-k2.7-code` | 前端任务 |
| DeepSeek V4 Pro | `deepseek-v4-pro` | 推理任务 |
| 自动模式 | `ark-code-latest` | 效果+速度智能选择 |

### 6.4 多环境配置策略

- `application.yml`：通用配置
- `application-dev.yml`：开发环境（Lite 模型、详细日志）
- `application-prod.yml`：生产环境（Pro 模型、监控开启）
- 敏感信息（API Key）通过环境变量注入，**禁止入库代码**

---

## 七、关键技术点说明

### 7.1 LLM 接入层设计

- **模型工厂模式**：通过配置动态切换模型，支持 `ark-code-latest` 自动模式
- **统一抽象**：封装火山引擎特有能力（深度思考 `thinking` 字段）通过 `extra_body` 传递
- **流式响应**：原生支持 SSE，实现 ChatGPT 式逐字输出
- **多模型路由**：按场景路由（编程任务 -> doubao-code，推理任务 -> deepseek-pro，前端任务 -> kimi）

### 7.2 工具系统设计

- **声明式注册**：`@Tool` 注解 + Spring Bean 自动扫描
- **动态工具**：支持运行时增删工具，适配不同 Agent 场景
- **工具沙箱**：危险工具（文件/数据库写操作）需 HITL 确认
- **MCP 集成**：通过 `langchain4j-mcp` 加载外部 MCP 工具服务

### 7.3 记忆系统设计

- **三级记忆**：短期（窗口）-> 中期（摘要）-> 长期（向量）
- **会话隔离**：按 sessionId 隔离，支持多用户并发
- **持久化**：对话历史落库（H2/MySQL），向量记忆落 Milvus

### 7.4 可观测性设计

- **链路追踪**：每次 LLM 调用生成 traceId，串联工具调用
- **Token 统计**：按请求次数计费场景下，统计调用次数与延迟
- **LangSmith 集成**（可选）：通过 `LANGCHAIN_API_KEY` 接入 LangSmith 可视化
- **日志规范**：结构化 JSON 日志，含 sessionId / agentName / toolName / 耗时

### 7.5 Guardrails 安全设计

LangChain4j 1.x 提供 `langchain4j-guardrails` 模块：

- **InputGuardrail**：拦截用户输入，阻止 Prompt 注入、PII 脱敏、输入 Schema 校验
- **OutputGuardrail**：校验模型输出，防止幻觉、格式校验、内容过滤

---

## 八、依赖版本（建议）

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 17+ | LangChain4j 要求 |
| Spring Boot | 3.2.x | 最新稳定版 |
| LangChain4j | 1.16+ | 最新 GA，含 agentic 模块 |
| langchain4j-open-ai | 1.16+ | 火山引擎接入适配器 |
| langchain4j-mcp | 1.16+ | MCP 协议支持 |
| langchain4j-milvus | 1.16+ | 向量数据库 |
| langchain4j-guardrails | 1.16+ | 输入/输出守护 |
| Milvus | 2.4.x | 向量数据库（Docker 部署） |
| MyBatis-Plus | 3.5.x | 关系数据库 |
| H2 / MySQL | - | 开发/生产 |
| springdoc-openapi | 2.x | API 文档 |
| Lombok | 最新 | 简化样板代码 |

---

## 九、扩展点与后续演进

1. **A2A 协议**：接入 Agent-to-Agent 协议，支持跨服务 Agent 通信
2. **本地模型**：通过 `langchain4j-ollama` 接入本地模型，降低成本
3. **多模态**：火山引擎支持图片/视频理解，扩展视觉 Agent
4. **Agent 市场**：工具与 Agent 配置化，支持热加载
5. **治理层**：增加配额/限流/审计，满足企业生产要求
6. **Spring AI 混用**：生产场景可引入 Spring AI 的 Advisors API 增强

---

## 十、下一步任务清单

待架构文档审阅通过后，按以下顺序实施：

1. 初始化 Maven 多模块工程骨架（pom.xml + 各模块目录）
2. 搭建 `agent-demo-common` 公共组件（异常/结果/枚举）
3. 搭建 `agent-demo-llm` 火山引擎接入层（模型工厂+配置）
4. 实现 `agent-demo-tools` 内置工具集（计算器/时间/HTTP）
5. 实现 `agent-demo-agent` 单 Agent ReAct 核心循环
6. 搭建 `agent-demo-web` 基础 REST+SSE 接口
7. 搭建 `agent-demo-bootstrap` 启动模块+配置文件
8. 编写最小可运行 Demo（单 Agent+工具调用+火山引擎）
9. 后续迭代：RAG 模块、多 Agent、MCP、工作流

---

## 参考资料

- [LangChain4j 官方文档](https://docs.langchain4j.dev)
- [火山引擎方舟 Coding Plan](https://www.volcengine.com/docs/82379/2188959)
- [火山引擎 OpenAI SDK 兼容说明](https://www.volcengine.cn/docs/82379/1330626)
- [LangGraph 生产级实践](https://alicelabs.ai/en/insights/best-ai-agent-frameworks-2026)
- [Java AI Agent 框架对比 2026](https://codewiz.info/blog/java-ai-agent-frameworks-2026/)
- [LangChain4j vs Spring AI 选型](https://juejin.cn/post/7659585088226918415)
