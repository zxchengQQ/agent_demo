# AI Agent 示例项目 (agent-demo)

基于 **Java 17 + Spring Boot 3.2.5 + LangChain4j 1.17.2** 构建的企业级 AI Agent 演练平台，集成 **Vue 3 + Vite 5** 前端对话界面，LLM 提供商为**火山引擎方舟 Coding Plan**。

## 技术栈

| 层级 | 技术 | 版本 |
|------|------|------|
| 后端框架 | Spring Boot | 3.2.5 |
| JDK | Java | 17 |
| AI 框架 | LangChain4j | 1.17.2 |
| LLM 提供商 | 火山引擎方舟 (Coding Plan) | OpenAI 兼容协议 |
| 前端框架 | Vue 3 + TypeScript 5 | ^3.4.0 |
| 构建工具 | Vite 5 | ^5.0.0 |
| 状态管理 | Pinia 2 | ^2.1.7 |
| 项目构建 | Maven | 多模块聚合 |

## 项目结构

```
agent-demo/
├── agent-demo-bom/          # BOM 依赖版本管理
├── agent-demo-common/       # 公共模块：常量、枚举、异常、工具类
├── agent-demo-llm/          # LLM 接入层：模型工厂、Ark 配置
├── agent-demo-tools/        # 工具调用系统：计算器、HTTP、时间、文件
├── agent-demo-memory/       # 记忆管理：会话管理、短时记忆、持久化
├── agent-demo-rag/          # RAG 知识库问答（规划中）
├── agent-demo-agent/        # Agent 核心：BaseAgent 接口、SimpleAgent 实现
├── agent-demo-mcp/          # MCP 协议互通（规划中）
├── agent-demo-web/          # Web 接口层：SSE 流式对话、REST API
├── agent-demo-app/          # 应用编排层（规划中）
├── agent-demo-bootstrap/    # 启动模块：配置、日志、启动入口
├── agent-demo-frontend/     # 前端：Vue 3 对话界面（暗色科技风）
└── start.ps1                # 一键启动脚本
```

## 快速启动

### 前置条件

- JDK 17+
- Maven 3.8+
- Node.js 18+（前端）
- 火山引擎方舟 API Key

### 后端启动

```powershell
# 方式一：使用启动脚本（推荐）
.\start.ps1 -ApiKey "ark-你的-api-key"

# 方式二：手动启动
$env:ARK_API_KEY = "ark-你的-api-key"
$env:JAVA_HOME = "D:\java\jdk-17.0.7"
mvn clean package -DskipTests
java -Dfile.encoding=UTF-8 -jar agent-demo-bootstrap\target\agent-demo-bootstrap-1.0.0.jar
```

启动后访问：
- Swagger UI: http://localhost:8080/swagger-ui.html
- 健康检查: http://localhost:8080/api/agent/session/list

### 前端启动

```powershell
cd agent-demo-frontend
npm install
npm run dev
```

访问 http://localhost:5173（开发服务器已配置 `/api` 代理到后端 8080）

### 前端测试

```powershell
cd agent-demo-frontend
npm test          # 运行全部测试
npm run test:watch  # 监视模式
```

## 核心 API

### 普通对话

```
POST /api/agent/chat
Content-Type: application/json

{
  "message": "你好",
  "sessionId": "可选，不传则自动创建新会话"
}
```

### 流式对话 (SSE)

```
POST /api/agent/chat/stream
Content-Type: application/json
Accept: text/event-stream

{
  "message": "你好",
  "sessionId": "可选，不传则自动创建新会话"
}
```

SSE 事件类型：
- `session` — 会话 ID（新建会话时触发）
- `token` — 流式文本块
- `done` — 对话完成

### 会话管理

```
GET  /api/agent/session/list      # 获取活跃会话列表
GET  /api/agent/session/{id}      # 获取会话详情
```

## 模块说明

| 模块 | 职责 | 状态 |
|------|------|------|
| agent-demo-llm | 模型工厂（OpenAI 兼容协议）、Ark 配置、模型实例缓存 | ✅ 已完成 |
| agent-demo-tools | 工具注册中心、内置工具（计算器/HTTP/时间/文件） | ✅ 已完成 |
| agent-demo-memory | SessionManager（30 分钟超时清理）、ChatMemoryManager（20 条窗口） | ✅ 已完成 |
| agent-demo-agent | BaseAgent 接口、SimpleAgent 实现（ReAct 循环 + 流式输出） | ✅ 已完成 |
| agent-demo-web | SSE 流式接口、REST API、参数校验、全局异常处理 | ✅ 已完成 |
| agent-demo-frontend | 暗色科技风对话界面、localStorage 会话缓存、SSE 流式解析 | ✅ 已完成 |
| agent-demo-rag | RAG 知识库问答 | 📋 规划中 |
| agent-demo-mcp | MCP 协议互通 | 📋 规划中 |
| agent-demo-app | 多 Agent 工作流编排 | 📋 规划中 |

## 文档体系

- [项目知识库](KNOWLEDGE_BASE.md) — 项目核心知识库
- [技术架构文档](specs/技术架构文档-TOGAF.md) — 技术架构设计
- [业务架构文档](specs/业务架构文档.md) — 业务架构设计
- [数据架构文档](specs/数据架构文档-TOGAF.md) — 数据架构设计
- [开发记录](docs/开发记录/) — 各阶段开发报告
- [Feature 文档](features/) — 功能需求与技术方案

## 相关链接

- [LangChain4j 文档](https://docs.langchain4j.dev/)
- [火山引擎方舟文档](https://www.volcengine.com/docs/82379)
- [Spring Boot 3.x 文档](https://docs.spring.io/spring-boot/docs/3.2.5/reference/html/)