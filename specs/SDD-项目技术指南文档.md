# AI Agent 示例项目 - 项目技术指南文档（技术真理源）

> **版本**：v1.0 | **基线日期**：2026-07-20 | **适用范围**：agent-demo
> **定位**：纯技术视角。限定"代码怎么写"、"框架怎么用"、"AI Agent 怎么开发"。不涉及业务含义。
> **约束分级**：🔴 强制 -> 🟡 尽量 -> 🟢 建议 -> ⚪ 可覆盖（详见第 3.1 节）

---

## 1. 技术栈锁定

### 1.1 后端核心技术栈

| 层级 | 技术 | 版本 | 备注 |
|------|------|------|------|
| 语言 | Java | **17** | 🔴 严格锁定，禁止使用 21+ 语法 |
| 框架 | Spring Boot | **3.2.5** | 父 POM 继承 spring-boot-starter-parent |
| AI 框架 | LangChain4j | **1.17.2** (GA) | 核心模块（langchain4j / langchain4j-open-ai） |
| LLM 适配 | langchain4j-open-ai | **1.17.2** (GA) | 火山引擎接入适配器 |
| MCP 协议 | langchain4j-mcp | **1.17.2-beta27** | MCP 客户端/服务端（集成模块，beta 版本线独立） |
| 向量数据库 | langchain4j-milvus + milvus-sdk-java | **1.17.2-beta27 / 2.4.3** | RAG 检索（规划中，集成模块 beta 版） |
| ORM | MyBatis-Plus | **3.5.7** | mybatis-plus-spring-boot3-starter |
| 接口文档 | springdoc-openapi | **2.5.0** | springdoc-openapi-starter-webmvc-ui |
| 工具库 | Hutool | **5.8.27** | hutool-all |
| JSON | Jackson | Spring Boot 内置 | 默认配置 non_null + Asia/Shanghai |
| 日志 | Logback | Spring Boot 内置 | logback-spring.xml |
| 代码简化 | Lombok | Spring Boot 内置 | @Data/@Slf4j/@Getter |
| 构建 | Maven | 3.9+ | 多模块 + BOM 统一版本 |
| 测试 | JUnit 5 | Spring Boot 内置 | 待补充测试用例 |

### 1.2 LLM 提供商配置

| 配置项 | 值 |
|--------|---|
| 提供商 | 火山引擎方舟（Volcengine Ark） |
| 接入方式 | Coding Plan（按次计费） |
| Base URL | `https://ark.cn-beijing.volces.com/api/coding/v3` |
| 协议 | OpenAI 兼容 |
| 默认模型 | `doubao-seed-2.0-code` |
| API Key | 环境变量 `ARK_API_KEY` 注入 |

### 1.3 前端技术栈

本项目为纯后端示例工程，暂无前端模块。通过 Swagger UI / curl / Postman 调用 API。

---

## 2. 工程结构

### 2.1 后端模块拓扑

```
agent-demo/
├── pom.xml                              # 根 POM，继承 spring-boot-starter-parent
├── agent-demo-bom/                      # BOM 物料清单（pom-only，统一版本）
├── agent-demo-common/                   # 公共组件（常量/枚举/异常/结果/工具类）
├── agent-demo-llm/                      # LLM 接入层（火山引擎适配 + 模型工厂）
├── agent-demo-tools/                    # 工具集（内置工具 + 注册中心）
├── agent-demo-memory/                   # 记忆模块（短期记忆 + 会话管理）
├── agent-demo-rag/                      # RAG 模块（规划中，空模块）
├── agent-demo-mcp/                      # MCP 协议模块（规划中，空模块）
├── agent-demo-agent/                    # Agent 核心模块（单 Agent ReAct）
├── agent-demo-app/                      # 应用编排层（规划中，空模块）
├── agent-demo-web/                      # Web 接口层（REST + DTO + 配置）
└── agent-demo-bootstrap/                # 启动模块（主启动类 + 配置 + 提示词）
```

### 2.2 模块依赖方向

| 模块 | 依赖方向 |
|------|---------|
| `agent-demo-bom` | 无（独立存在，不继承根 pom） |
| `agent-demo-common` | 无 |
| `agent-demo-llm` | common |
| `agent-demo-tools` | common |
| `agent-demo-memory` | common, llm |
| `agent-demo-rag` | common, llm（规划中） |
| `agent-demo-mcp` | common, tools（规划中） |
| `agent-demo-agent` | common, llm, tools, memory |
| `agent-demo-app` | agent, rag, mcp（规划中） |
| `agent-demo-web` | app, agent, memory |
| `agent-demo-bootstrap` | web（聚合全部） |

### 2.3 核心模块内部分层

```
agent-demo-agent/
├── config/                              # AgentConfig（配置属性绑定）
├── core/                                # BaseAgent（Agent 抽象接口）
└── single/                              # SimpleAgent（单 Agent 实现）

agent-demo-llm/
├── config/                              # ArkProperties / LlmConfig
└── factory/                             # ModelFactory（模型工厂）

agent-demo-tools/
├── builtin/                             # 内置工具（Calculator/Time/Http/FileRead）
└── registry/                            # ToolRegistry（注册中心）

agent-demo-memory/
├── longterm/                            # 长期记忆（EmptyLongTermMemory 占位）
├── session/                             # 会话管理（SessionManager/Metadata）
├── shortterm/                           # 短期记忆（ChatMemoryManager）
└── store/                               # 记忆存储（MemoryRepository + InMemory 实现）

agent-demo-web/
├── config/                              # OpenApiConfig / TraceIdInterceptor / WebConfig
├── controller/                          # AgentController
├── dto/                                 # ChatRequest / ChatResponse
└── handler/                             # GlobalExceptionHandler
```

### 2.4 包命名规范

```
com.agentdemo
├── common.{constant,enums,exception,result,utils}
├── llm.{config,factory}
├── tools.{builtin,registry}
├── memory.{longterm,session,shortterm,store}
├── agent.{config,core,single}
├── web.{config,controller,dto,handler}
└── AgentDemoApplication                 # 启动类位于 com.agentdemo 根包
```

---

## 3. 技术约束清单

### 3.1 约束分级标准

| 级别 | 标签 | 含义 | 违反后果 |
|------|------|------|---------|
| 🔴 强制 | `MUST` | 违反将直接导致编译失败或运行时崩溃 | 系统无法启动/功能异常 |
| 🟡 尽量 | `SHOULD` | 不遵守会引发隐蔽 bug | 难以排查的运行时问题 |
| 🟢 建议 | `RECOMMENDED` | 推荐遵守 | 代码可读性下降 |
| ⚪ 可覆盖 | `CONFIGURABLE` | 可通过配置调整 | 影响范围取决于配置项 |

---

### 3.2 Java 语言级约束（JDK 17 限定）

> 由于项目锁定 Java 17，以下语法约束**对人类开发者和 AI Agent 同等生效**。

| 编号 | 约束描述 | 级别 |
|------|---------|------|
| TC-LANG-001 | **禁止**使用虚拟线程（Java 21+） | 🔴 强制 |
| TC-LANG-002 | **禁止**使用模式匹配 for switch（Java 21+ 正式版） | 🔴 强制 |
| TC-LANG-003 | **禁止**使用 `SequencedCollection` 接口（Java 21+） | 🔴 强制 |
| TC-LANG-004 | 允许使用 record（Java 16+）、sealed（Java 17）、文本块（Java 15+） | 🟢 建议 |
| TC-LANG-005 | 集合判空优先使用 `CollectionUtils.isEmpty()`（Hutool/Spring），而非手动 `== null \|\| .isEmpty()` | 🟢 建议 |
| TC-LANG-006 | 使用 `var` 局部变量类型推断时仅限局部变量，不可用于字段/方法签名 | 🟡 尽量 |

---

### 3.3 Spring Boot 框架使用约束

| 编号 | 约束描述 | 级别 |
|------|---------|------|
| TC-FRAMEWORK-001 | Spring Boot 版本锁定 3.2.5，禁止升级至 3.3+ | 🔴 强制 |
| TC-FRAMEWORK-002 | Controller 返回值必须用 `Result<T>` 包装，前端统一解析 `code/data/msg` | 🔴 强制 |
| TC-FRAMEWORK-003 | Controller 方法必须添加 `@Operation` OpenAPI 注解描述功能 | 🟡 尽量 |
| TC-FRAMEWORK-004 | 业务异常使用 `BusinessException` + `ErrorCode`，禁止直接 `throw new RuntimeException` | 🔴 强制 |
| TC-FRAMEWORK-005 | 跨模块调用必须通过接口，禁止直接引用其他模块的 Service 实现类 | 🔴 强制 |
| TC-FRAMEWORK-006 | 配置属性必须使用 `@ConfigurationProperties` 绑定，禁止 `@Value` 散落使用 | 🟡 尽量 |
| TC-FRAMEWORK-007 | API 路径前缀统一 `/api/{module}/` | 🔴 强制 |
| TC-FRAMEWORK-008 | RESTful 风格：POST(create)/GET(get)/PUT(update)/DELETE(delete) | 🟡 尽量 |
| TC-FRAMEWORK-009 | 全局异常处理通过 `@RestControllerAdvice` + `GlobalExceptionHandler` 统一拦截 | 🔴 强制 |
| TC-FRAMEWORK-010 | 定时任务使用 `@Scheduled`，主启动类需 `@EnableScheduling` | 🔴 强制 |
| TC-FRAMEWORK-011 | `NoResourceFoundException`（访问不存在的静态资源/接口路径）必须专门处理返回 404，禁止落入兜底 `Exception` 处理器导致 500 + 堆栈污染 | 🔴 强制 |

---

### 3.4 LangChain4j 使用约束

| 编号 | 约束描述 | 级别 |
|------|---------|------|
| TC-LC4J-001 | LangChain4j 核心模块（langchain4j / langchain4j-open-ai）版本锁定 1.17.2 GA，集成模块（langchain4j-milvus / langchain4j-mcp）锁定 1.17.2-beta27，两条版本线独立管理 | 🔴 强制 |
| TC-LC4J-002 | Agent 接口必须通过 `AiServices.builder()` 构建代理，禁止手写 ReAct 循环 | 🔴 强制 |
| TC-LC4J-003 | 工具方法必须使用 `@Tool` 注解并填写功能描述，框架自动生成 JSON Schema | 🔴 强制 |
| TC-LC4J-004 | 会话记忆使用 `MessageWindowChatMemory`，通过 `@MemoryId` 标识会话 | 🔴 强制 |
| TC-LC4J-005 | 系统提示词通过 `systemMessageProvider` 动态提供，支持场景定制 | 🟡 尽量 |
| TC-LC4J-006 | LLM 模型实例必须缓存复用，禁止每次调用重新构建 | 🔴 强制 |
| TC-LC4J-007 | 流式输出使用 `StreamingChatModel`，非流式使用 `ChatModel`，分别构建 | 🟡 尽量 |

---

### 3.5 数据访问层约束

> 本项目当前为内存存储（会话/记忆），未来 RAG 接入 Milvus，业务数据可选 MySQL。以下约束适用于未来数据库接入场景。

| 编号 | 约束描述 | 级别 |
|------|---------|------|
| TC-ORM-001 | DO 实体必须继承统一基类（含审计字段 createTime/updateTime/creator/updater/deleted） | 🔴 强制 |
| TC-ORM-002 | Mapper 接口继承 MyBatis-Plus `BaseMapper` | 🟡 尽量 |
| TC-ORM-003 | 分页查询返回 `PageResult<T>` | 🟡 尽量 |
| TC-ORM-004 | DO -> VO 转换使用 `BeanUtils.toBean()` 或 MapStruct，禁止手动 setter 逐个赋值 | 🟢 建议 |
| TC-ORM-005 | 表间不使用数据库外键，关联关系通过应用层维护 | 🔴 强制 |

---

### 3.6 数据库建表约束（未来接入）

| 编号 | 约束描述 | 级别 |
|------|---------|------|
| TC-DB-001 | 所有表必须包含 `deleted BIT(1) NOT NULL DEFAULT b'0'` 逻辑删除字段 | 🔴 强制 |
| TC-DB-002 | 所有表必须包含审计四字段 `creator`/`create_time`/`updater`/`update_time` | 🔴 强制 |
| TC-DB-003 | 字段命名使用 snake_case（MyBatis-Plus 自动映射为 camelCase） | 🔴 强制 |
| TC-DB-004 | 字符集统一 `utf8mb4`，排序规则 `utf8mb4_unicode_ci` | 🔴 强制 |
| TC-DB-005 | 主键策略：`BIGINT NOT NULL AUTO_INCREMENT` | 🔴 强制 |
| TC-DB-006 | 存储引擎使用 `InnoDB` | 🔴 强制 |
| TC-DB-007 | 每个字段必须有 `COMMENT` 注释 | 🟡 尽量 |

**标准建表模板**：

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

---

### 3.7 分层命名规范约束

| 编号 | 约束描述 | 示例 | 级别 |
|------|---------|------|------|
| TC-NAME-001 | Controller: `{Domain}Controller` | `AgentController` | 🟡 尽量 |
| TC-NAME-002 | Service 接口: `{Domain}Service`/`{Domain}Manager` | `SessionManager` | 🟡 尽量 |
| TC-NAME-003 | Service 实现: `{Domain}ServiceImpl` | `SimpleAgent`（接口委托模式） | 🟡 尽量 |
| TC-NAME-004 | 配置类: `{Domain}Config` / `{Domain}Properties` | `AgentConfig` / `ArkProperties` | 🔴 强制 |
| TC-NAME-005 | 工厂类: `{Domain}Factory` | `ModelFactory` | 🔴 强制 |
| TC-NAME-006 | 注册中心: `{Domain}Registry` | `ToolRegistry` | 🔴 强制 |
| TC-NAME-007 | 枚举: `{Domain}{Type}Enum` | `AgentType`、`MemoryType` | 🟡 尽量 |
| TC-NAME-008 | 错误码统一在 `ErrorCode` 枚举中定义，编号区间不重叠 | 见 3.10 节 | 🔴 强制 |
| TC-NAME-009 | 常量类: `{Domain}Constants` | `ModelConstants` | 🔴 强制 |
| TC-NAME-010 | DTO: `{Domain}{Action}Request`/`{Domain}{Action}Response` | `ChatRequest`/`ChatResponse` | 🟡 尽量 |

---

### 3.8 权限与安全约束

| 编号 | 约束描述 | 级别 |
|------|---------|------|
| TC-SEC-001 | API Key 必须通过环境变量 `ARK_API_KEY` 注入，禁止入库 | 🔴 强制 |
| TC-SEC-002 | HTTP 工具必须执行 SSRF 防护，禁止访问内网地址 | 🔴 强制 |
| TC-SEC-003 | 文件读取工具必须限定目录白名单（`agent.file-allowed-dir`） | 🔴 强制 |
| TC-SEC-004 | 日志中禁止打印 API Key 明文 | 🔴 强制 |
| TC-SEC-005 | 生产环境关闭 Swagger UI 访问 | 🟡 尽量 |
| TC-SEC-006 | 日志中不应打印用户消息完整明文（可截断或脱敏） | 🟢 建议 |
| TC-SEC-007 | HTTP 工具响应超 10KB 必须截断 | 🔴 强制 |

---

### 3.9 Maven 构建约束

| 编号 | 约束描述 | 级别 |
|------|---------|------|
| TC-MVN-001 | 根 POM 继承 `spring-boot-starter-parent:3.2.5`，统一 Spring 依赖版本 | 🔴 强制 |
| TC-MVN-002 | 第三方依赖版本通过 `agent-demo-bom` BOM 管理，子模块禁止声明版本号 | 🔴 强制 |
| TC-MVN-003 | 编译顺序：bom -> common -> llm/tools/memory -> agent -> app -> web -> bootstrap | 🔴 强制 |
| TC-MVN-004 | 项目统一版本号 `1.0.0`，BOM 与子模块使用 `${project.version}`；LangChain4j 核心模块版本由 `langchain4j.version` 管理，集成模块版本由 `langchain4j.beta.version` 独立管理 | 🔴 强制 |
| TC-MVN-005 | 单模块编译：`mvn compile -pl {模块名} -am` | 🟢 建议 |
| TC-MVN-006 | 打包使用 `spring-boot-maven-plugin`，排除 devtools | 🔴 强制 |
| TC-MVN-007 | 全局属性：`java.version=17`、`sourceEncoding=UTF-8` | 🔴 强制 |

---

### 3.10 错误码约束

| 编号 | 约束描述 | 级别 |
|------|---------|------|
| TC-ERR-001 | 错误码必须通过 `ErrorCode` 枚举统一定义 | 🔴 强制 |
| TC-ERR-002 | 业务异常必须使用 `BusinessException` + `ErrorCode` | 🔴 强制 |
| TC-ERR-003 | 错误码编号区间按业务域划分，不可重叠 | 🔴 强制 |
| TC-ERR-004 | 新增错误码必须在 `ErrorCode` 枚举中分配编号并补充注释 | 🔴 强制 |

**错误码区间分配**：

| 区间 | 业务域 | 已用错误码 |
|------|--------|---------|
| 200 | 成功 | SUCCESS(200) |
| 400-404 | 客户端错误 | PARAM_INVALID/UNAUTHORIZED/FORBIDDEN/NOT_FOUND |
| 5000 | 系统异常 | SYSTEM_ERROR |
| 5001-5099 | LLM 相关 | LLM_CALL_FAILED/TIMEOUT/RATE_LIMITED/API_KEY_INVALID |
| 5100-5199 | 工具相关 | TOOL_EXECUTION_FAILED/TOOL_NOT_FOUND/TOOL_PARAM_INVALID |
| 5200-5299 | 记忆/会话 | MEMORY_NOT_FOUND/SESSION_NOT_FOUND/SESSION_EXPIRED |
| 5300-5399 | RAG 相关 | RAG_RETRIEVE_FAILED/EMBEDDING_FAILED/DOCUMENT_LOAD_FAILED |
| 5400-5499 | MCP 相关 | MCP_CONNECTION_FAILED/MCP_TOOL_CALL_FAILED |

---

## 4. AI Agent 开发规范

> 以下约束面向 AI Agent（Cursor / Trae / Claude 等）在本项目中的自动化编码行为。

### 4.1 AI 编码行为约束

| 编号 | 约束描述 | 级别 |
|------|---------|------|
| TC-AI-001 | AI 生成的代码必须通过 `mvn compile -pl {模块名} -am` 编译验证 | 🔴 强制 |
| TC-AI-002 | AI 修改代码前必须先读取目标文件，理解上下文再动手 | 🔴 强制 |
| TC-AI-003 | AI 删除或修改已有代码前必须向用户确认 | 🔴 强制 |
| TC-AI-004 | AI 必须优先使用项目已有的工具函数和组件，不重复造轮子 | 🔴 强制 |
| TC-AI-005 | AI 生成新工具必须加 `@Component` + `@Tool` 注解，并通过 `ToolRegistry` 自动注册 | 🔴 强制 |
| TC-AI-006 | AI 生成新 Agent 实现必须实现 `BaseAgent` 接口 | 🔴 强制 |
| TC-AI-007 | AI 生成新模型名必须先在 `ModelConstants` 中定义常量 | 🔴 强制 |
| TC-AI-008 | AI 不得使用任何 Java 21+ API（见 3.2 节） | 🔴 强制 |
| TC-AI-009 | AI 遇到不确定的业务规则时应主动向用户确认而非假设 | 🔴 强制 |
| TC-AI-010 | AI 修改接口签名前必须枚举所有调用方，并在任务清单中逐一列为子任务 | 🔴 强制 |

### 4.2 AI 代码风格约束

| 编号 | 约束描述 | 级别 |
|------|---------|------|
| TC-AI-011 | 遵循项目分层命名规范（Controller/Service/Mapper/DO/VO），见 3.7 节 | 🟡 尽量 |
| TC-AI-012 | 核心业务逻辑（ReAct 循环、资金计算、状态机流转）必须在代码块上方写明业务含义注释 | 🔴 强制 |
| TC-AI-013 | 接口变更需在 Javadoc 中补充调用方枚举与影响评估 | 🔴 强制 |
| TC-AI-014 | 新增功能的 DTO 入参应添加 `@Valid` + Bean Validation 校验注解 | 🟡 尽量 |
| TC-AI-015 | 代码注释使用中文，方法级 Javadoc 可选但鼓励 | 🟢 建议 |
| TC-AI-016 | 禁止以 null 作为缺省参数传递（应重载方法） | 🔴 强制 |
| TC-AI-017 | 禁止将业务状态码硬编码在调用方（统一使用 `ErrorCode` 枚举） | 🔴 强制 |
| TC-AI-018 | 未经 review 不得修改抽象类共享逻辑（改动影响所有子类） | 🔴 强制 |

### 4.3 AI 迭代流程约束

| 编号 | 约束描述 | 级别 |
|------|---------|------|
| TC-AI-019 | 功能迭代应遵循"构思方案 -> 提请审核 -> 分解任务 -> 执行"的作业顺序 | 🟡 尽量 |
| TC-AI-020 | 编码完成后必须执行 `mvn compile` 编译验证 | 🔴 强制 |
| TC-AI-021 | 安全审查发现的 P0 级问题必须在当次迭代内修复 | 🔴 强制 |
| TC-AI-022 | 所有迭代文档存放于 `specs/features/{yyyy-MM-dd}/` 目录 | 🟡 尽量 |
| TC-AI-023 | 始终使用中文回复和书写文档 | 🔴 强制 |

### 4.4 AI 应参考的现有范式

> AI 在生成新代码前，应优先参考以下已有实现，确保风格统一。

| 需要写的东西 | 参考范式文件 |
|-------------|------------|
| 新 Controller | `agent-demo-web/src/main/java/com/agentdemo/web/controller/AgentController.java` |
| 新 Agent 实现 | `agent-demo-agent/src/main/java/com/agentdemo/agent/single/SimpleAgent.java` |
| 新工具 | `agent-demo-tools/src/main/java/com/agentdemo/tools/builtin/HttpTool.java` |
| 新配置类 | `agent-demo-llm/src/main/java/com/agentdemo/llm/config/ArkProperties.java` |
| 新工厂类 | `agent-demo-llm/src/main/java/com/agentdemo/llm/factory/ModelFactory.java` |
| 新枚举 | `agent-demo-common/src/main/java/com/agentdemo/common/enums/AgentType.java` |
| 新错误码 | `agent-demo-common/src/main/java/com/agentdemo/common/exception/ErrorCode.java` |
| 新常量类 | `agent-demo-common/src/main/java/com/agentdemo/common/constant/ModelConstants.java` |
| 新 Manager | `agent-demo-memory/src/main/java/com/agentdemo/memory/shortterm/ChatMemoryManager.java` |
| 新 DTO | `agent-demo-web/src/main/java/com/agentdemo/web/dto/ChatRequest.java` |
| 新全局异常处理 | `agent-demo-web/src/main/java/com/agentdemo/web/handler/GlobalExceptionHandler.java` |

---

## 5. 开发环境

### 5.1 后端

| 配置项 | 值 |
|--------|---|
| JDK | OpenJDK 17+（推荐 Eclipse Temurin / Amazon Corretto） |
| Maven | 3.9+ |
| 端口 | 8080 |
| Profile | `application-dev.yml`（默认） |
| 启动 | 运行 `agent-demo-bootstrap` 模块的 `AgentDemoApplication.main()` |
| 接口文档 | `http://localhost:8080/swagger-ui.html` |
| API Base Path | `http://localhost:8080/api/agent/` |
| 环境变量 | `ARK_API_KEY`（火山引擎 API Key，必填） |

### 5.2 多环境配置

| 环境 | Profile | 说明 |
|------|---------|------|
| 开发 | `application-dev.yml` | 默认激活，Lite 模型，详细日志 |
| 生产 | `application-prod.yml` | Pro 模型，监控开启 |

### 5.3 快速启动

**方式一：手动启动**

```powershell
# Windows PowerShell
$env:ARK_API_KEY="your-api-key-here"
mvn clean install -DskipTests
java -jar agent-demo-bootstrap/target/agent-demo-bootstrap-1.0.0.jar
```

**方式二：启动脚本（推荐）**

项目提供 `start.ps1` 启动脚本，封装环境变量设置、Maven 打包、JVM 启动、编码修复全流程：

```powershell
# 默认启动（dev 环境，读取 $env:ARK_API_KEY）
.\start.ps1

# 指定 API Key 启动
.\start.ps1 -ApiKey "ark-xxxx"

# 指定 prod 环境启动
.\start.ps1 -Profile prod

# 跳过打包，直接启动已有 jar
.\start.ps1 -SkipBuild

# 测试对话接口（需应用已启动）
.\start.ps1 -Test

# 自定义测试消息
.\start.ps1 -Test -TestMessage "2+2="

# 查看帮助
.\start.ps1 -Help
```

> 启动脚本内置三层中文编码修复（详见 5.5 节），必须保存为 **UTF-8 with BOM** 编码，否则 PowerShell 5 无法正确解析中文。

### 5.4 接口调用示例

```bash
# 创建会话
curl -X POST http://localhost:8080/api/agent/session

# 同步对话
curl -X POST http://localhost:8080/api/agent/chat \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"","message":"现在几点？"}'

# 清空会话记忆
curl -X DELETE http://localhost:8080/api/agent/session/{sessionId}/memory
```

### 5.5 中文编码方案（Windows PowerShell）

> **适用场景**：Windows PowerShell 5 环境下运行应用或调用接口时出现中文乱码。Linux/macOS 环境默认 UTF-8 无需此方案。

**三层编码修复**（已内置到 `start.ps1`）：

| 层次 | 问题 | 解决方案 |
|------|------|---------|
| 控制台编码 | PowerShell 5 默认 GBK（代码页 936）解码 UTF-8 字节流 | `chcp 65001` + `[Console]::OutputEncoding/InputEncoding = UTF8` |
| JVM 编码 | JVM 默认编码受操作系统影响，Windows 下为 GBK | JVM 启动参数 `-Dfile.encoding=UTF-8`（须用数组 `@()` 传递，避免 PowerShell 5 拆分） |
| HTTP 响应解码 | `Invoke-RestMethod` 用系统编码解析响应体导致中文乱码 | 改用 `Invoke-WebRequest` + `RawContentStream` 获取原始字节流，`[System.Text.Encoding]::UTF8.GetString()` 手动解码 |

**常见陷阱**：

| 陷阱 | 现象 | 规避方式 |
|------|------|---------|
| `java -version` 触发异常 | `ErrorActionPreference=Stop` 时 stderr 被当作异常 | 用 `cmd /c "java -version 2>&1"` 包装 |
| `-Dfile.encoding=UTF-8` 被拆分 | PowerShell 5 拆分为 `-Dfile` 和 `.encoding=UTF-8`，导致 `ClassNotFoundException` | 用数组 `$javaArgs = @("-Dfile.encoding=UTF-8", ...); & java @javaArgs` |
| `.ps1` 文件中文乱码 | PowerShell 5 用 GBK 读取无 BOM 文件，中文字符串未正确闭合引发语法错误 | 保存为 UTF-8 with BOM（文件开头添加 `0xEF 0xBB 0xBF`） |

---

## 6. 约束速查索引

> 按严重程度降序排列，便于 AI Agent 和开发者快速查阅。

### 6.1 🔴 强制约束速查（违反 = 编译失败 / 运行崩溃）

| 领域 | 条数 | 关键约束 |
|------|------|---------|
| Java 语言 | 3 | 禁用 21+ 语法（虚拟线程/模式匹配/SequencedCollection） |
| Spring Boot | 7 | 版本锁定、Result 包装、BusinessException、跨模块接口、API 前缀、全局异常、NoResourceFoundException 404 处理 |
| LangChain4j | 4 | 版本一致、AiServices 代理、@Tool 注解、模型缓存 |
| 数据访问 | 1 | 无外键约束 |
| 数据库 | 5 | deleted/审计字段/snake_case/utf8mb4/InnoDB |
| 命名规范 | 4 | Config/Properties/Factory/Registry/Constants |
| 权限安全 | 5 | API Key 注入、SSRF 防护、目录白名单、日志脱敏、响应截断 |
| Maven | 5 | 父 POM 继承、BOM 管理、编译顺序、版本统一、UTF-8 |
| 错误码 | 4 | 枚举统一、BusinessException、区间不重叠、新增注释 |
| AI 开发 | 9 | 编译验证、先读后改、确认删除、复用工具、@Tool/BaseAgent/ModelConstants、禁用 21+、确认不确定、接口签名评估 |

### 6.2 🟡 尽量约束速查（不遵守 = 隐蔽 bug / 不一致）

| 领域 | 关键约束 |
|------|---------|
| Java 语言 | var 仅限局部变量 |
| Spring Boot | @Operation 注解、@ConfigurationProperties、RESTful 风格 |
| LangChain4j | systemMessageProvider、流式与非流式分别构建 |
| 数据访问 | BaseMapper 继承、PageResult 返回 |
| 命名规范 | Controller/Service/Enum/DTO 命名 |
| 权限安全 | 生产关闭 Swagger |
| AI 流程 | 迭代流程、文档目录 |

---

**文档维护**：
- 技术栈升级时，同步更新版本号和约束
- 新增约束时，按领域添加并分配编号
- 定期审查过时约束，标记为"已废弃"
