# 功能变更记录: MCP 协议模块 - CR-001

## 0. 变更概览 (Change Overview)

*   **变更标题**: MCP 工具非文本内容处理能力扩展（Transport 包装器 + 前端图片渲染）
*   **变更类型**: 扩展 (Extension)
*   **变更原因**: MCP 工具（如 mermaid-mcp）返回图片等非文本内容时，LangChain4j `DefaultMcpClient.executeTool()` 内部抛出 `Unsupported content type: "image"` 异常，导致 `extractResultText()` 方法无法执行，Agent 仅收到通用提示信息丢失实际图片数据。需通过 Transport 包装器拦截原始 MCP 响应，提取图片 URL/base64 信息以 Markdown 格式返回给 LLM，同时前端支持 Markdown 图片渲染。
*   **发起日期**: 2026-08-07
*   **开发方法**: TDD（测试驱动开发）- 每个任务按 Red-Green-Refactor 循环执行
*   **关联功能**: MCP 协议模块（agent-demo-mcp）
*   **关联文档**:
    -   需求文档: `specs/features/2026-08-05/MCP协议模块/MCP协议模块.md`（v1.1 新增 AC-036~040 + BR-MCP-020~022）
    -   技术方案: `specs/features/2026-08-05/MCP协议模块/MCP协议模块_技术方案.md`（v1.1 新增 Section 4.6）
    -   任务规划: `specs/features/2026-08-05/MCP协议模块/MCP协议模块_任务规划.md`（原 Task-01~17）
    -   BUG 修复: `docs/BUG修复文档/20260806-1914-MCP工具返回image类型内容异常.md`（初始修复，本 CR 为根本性扩展）

## 1. 影响分析 (Impact Analysis)

### 1.1 需求影响

| 影响项 | 变更类型 | 详情 |
| :--- | :--- | :--- |
| AC-036 | 新增 | MCP 工具返回图片 URL 时，系统从原始响应提取 URL 以 Markdown 格式返回给 LLM |
| AC-037 | 新增 | MCP 工具返回 base64 图片时，系统提取图片信息返回给 LLM |
| AC-038 | 新增 | MCP 工具返回混合内容（text + image）时，系统正确提取所有内容 |
| AC-039 | 新增 | 前端对话界面支持 Markdown 图片渲染 |
| AC-040 | 新增 | Transport 包装器对现有三种传输方式无影响（回归验证） |
| BR-MCP-020 | 新增 | MCP 工具返回非文本内容时必须从原始响应提取内容信息，禁止仅返回通用提示 |
| BR-MCP-021 | 新增 | McpTransportWrapper 必须透明代理所有方法，仅拦截工具调用响应进行缓存 |
| BR-MCP-022 | 新增 | 前端 Markdown 白名单必须包含 img 标签，DOMPurify 必须过滤危险属性 |

### 1.2 技术影响

| 影响层 | 影响范围 | 详情 |
| :--- | :--- | :--- |
| 数据层 | 无影响 | 纯内存存储，无数据库变更 |
| API 层 | 无影响 | 不新增/修改 REST API |
| 业务逻辑 | 新增逻辑 | Transport 包装器拦截原始响应 + 原始 JSON-RPC 响应解析提取图片信息 |
| 表现层 | 修改组件 | 前端 `markdown.ts` 白名单新增 img 标签 + `MessageItem.vue` 图片样式 |

### 1.3 代码影响

| 文件路径 | 操作 | 影响说明 |
| :--- | :--- | :--- |
| `agent-demo-mcp/src/main/java/com/agentdemo/mcp/client/McpTransportWrapper.java` | 新增 | Transport 包装器，实现 McpTransport 接口，拦截缓存原始 JSON-RPC 响应 |
| `agent-demo-mcp/src/main/java/com/agentdemo/mcp/client/McpTransportFactory.java` | 修改 | createTransport() 方法返回前用 McpTransportWrapper 包装原始 Transport |
| `agent-demo-mcp/src/main/java/com/agentdemo/mcp/client/McpClientEntry.java` | 修改 | 新增 getTransportWrapper() 方法，便于 Executor 获取缓存响应 |
| `agent-demo-mcp/src/main/java/com/agentdemo/mcp/tool/McpToolExecutor.java` | 修改 | catch 块中从 Wrapper 缓存提取图片信息，以 Markdown 格式返回 |
| `agent-demo-frontend/src/utils/markdown.ts` | 修改 | ALLOWED_TAGS 新增 'img' |
| `agent-demo-frontend/src/components/MessageItem.vue` | 修改 | .markdown-body 新增 img CSS 样式约束 |
| `agent-demo-frontend/src/utils/markdown.test.ts` | 修改 | 更新 XSS 测试 + 新增 Markdown 图片语法测试 |

### 1.4 测试影响

| 测试文件 | 影响类型 | 说明 |
| :--- | :--- | :--- |
| `McpTransportWrapperTest.java` | 需新增 | 包装器透明代理验证 + 响应缓存验证 |
| `McpTransportFactoryTest.java` | 需修改 | 验证返回的 Transport 被 McpTransportWrapper 包装 |
| `McpToolExecutorTest.java` | 需修改 | 新增从原始响应提取图片 URL/base64/混合内容的测试用例 |
| `markdown.test.ts` | 需修改 | 更新 XSS 测试（img 标签保留但 onerror 移除）+ 新增图片渲染测试 |

### 1.5 回归风险评估

*   **高风险区域**: McpTransport 包装器可能影响所有三种传输方式（stdio/SSE/HTTP）的正常调用
*   **已有测试覆盖**: McpToolExecutorTest（11 个用例）、McpTransportFactoryTest 覆盖核心路径
*   **需要补充的测试**: 包装器透明代理验证、三种传输方式回归、前端 XSS 防护验证（img 标签场景）

## 2. 需求变更详情 (Requirements Delta)

### 2.1 新增/修改的用户故事

- **US-005**（CR-001 新增）: 作为 **对话用户**，我想要 **Agent 调用 MCP 工具返回图片时能看到图片内容**，以便 **直观获取工具执行结果（如 Mermaid 图表、截图等）而非仅看到文字提示**。
    - 关联验收标准：AC-036, AC-037, AC-038, AC-039

### 2.2 新增/修改的验收标准

#### 正常流程 (Happy Path)

- **AC-036**: MCP 工具返回图片 URL 时系统从原始响应提取并返回给 LLM
    - Given: mermaid-mcp Server 已连接，Agent 调用 `validate_and_render_mermaid_diagram` 工具
    - When: MCP Server 返回包含 `ImageContent`（带 URL）的响应，`mcpClient.executeTool()` 内部抛出 `Unsupported content type: "image"` 异常
    - Then: 系统从 `McpTransportWrapper` 缓存的原始 JSON-RPC 响应中提取图片 URL，以 Markdown 格式 `![图片](url)` 返回给 LLM

- **AC-037**: MCP 工具返回 base64 图片时系统提取图片信息返回给 LLM
    - Given: MCP Server 返回包含 `ImageContent`（带 base64Data，无 URL）的响应
    - When: 系统处理非文本内容异常
    - Then: 系统从原始响应中提取 base64 图片信息（MIME 类型 + 数据摘要），返回文本描述给 LLM

- **AC-038**: MCP 工具返回混合内容时系统正确提取所有内容
    - Given: MCP Server 返回包含 `TextContent` + `ImageContent` 的混合响应
    - When: 系统处理非文本内容异常
    - Then: 系统从原始响应中同时提取文本和图片信息，按响应顺序拼接返回给 LLM

#### 边界与异常 (Edge & Error Cases)

- **AC-039**: 前端对话界面支持 Markdown 图片渲染
    - Given: LLM 回复中包含 `![alt](url)` 格式的 Markdown 图片语法
    - When: 前端渲染助手消息（`renderMarkdown` 函数处理）
    - Then: 图片以 `<img>` 标签正确渲染显示，图片宽度不超出消息气泡边界，`onerror` 等危险属性被 DOMPurify 过滤

- **AC-040**: Transport 包装器对现有三种传输方式无影响
    - Given: 系统使用 `McpTransportWrapper` 包装 stdio/SSE/HTTP 三种传输方式
    - When: 正常 MCP 工具调用（返回纯文本内容）
    - Then: 包装器透明代理所有方法，工具调用结果与未包装时完全一致，无异常、无延迟、无数据丢失

## 3. 技术变更详情 (Technical Delta)

### 3.1 数据库变更

无数据库变更（纯内存存储）。

### 3.2 API 变更

无 API 变更（不新增/修改 REST API）。

### 3.3 组件变更

| 操作 | 组件 | 说明 |
| :--- | :--- | :--- |
| 新增 | `McpTransportWrapper.java` | 实现 `McpTransport` 接口的包装器，透明代理所有方法 + 拦截缓存工具调用响应 |
| 修改 | `McpTransportFactory.java` | `createTransport()` 返回前用 `McpTransportWrapper` 包装原始 Transport |
| 修改 | `McpClientEntry.java` | 新增 `getTransportWrapper()` 方法，将 `transport` 字段强转为 Wrapper |
| 修改 | `McpToolExecutor.java` | catch 块中从 Wrapper 缓存提取图片信息，以 Markdown 格式返回 |
| 修改 | `markdown.ts` | `ALLOWED_TAGS` 新增 `'img'` |
| 修改 | `MessageItem.vue` | `.markdown-body` 新增 `img` CSS 样式（`max-width: 100%` 等） |
| 修改 | `markdown.test.ts` | 更新 XSS 测试 + 新增图片渲染测试 |

### 3.4 兼容性说明

*   **向前兼容**: 完全兼容。Transport 包装器透明代理所有方法，不修改任何现有行为。前端新增 img 标签不影响已有渲染逻辑。
*   **迁移方案**: 无需迁移。包装器在 `McpTransportFactory` 内部自动应用，对上层透明。

## 4. 增量开发任务 (Incremental Tasks)

> 任务编号从原任务规划最后一个编号（Task-17）之后继续
> 每个任务按 TDD 循环执行：RED（写测试）-> GREEN（写实现）-> REFACTOR（重构）

### 阶段一：后端核心 - Transport 包装器 (Backend Core - Transport Wrapper)

- [x] **Task-18**: 创建 McpTransportWrapper（Transport 包装器）
    *   **通俗解释**: 做完这步后，系统就有了一层"透明薄膜"包裹在 MCP 传输通道外面，当 MCP Server 返回图片等非文本内容时，这层薄膜会悄悄把原始响应数据缓存下来，等后续需要时再取出来解析。
    *   **说明**: 创建 `McpTransportWrapper` 类，实现 `dev.langchain4j.mcp.client.transport.McpTransport` 接口。持有原始 `delegate` Transport 对象，所有接口方法直接委托给 delegate 执行。对工具调用相关的方法，在委托后缓存原始 JSON-RPC 响应字符串。提供 `getLastRawResponse()` 和 `clearCachedResponse()` 方法。
    *   **变更类型**: 新增
    *   **涉及文件**: `agent-demo-mcp/src/main/java/com/agentdemo/mcp/client/McpTransportWrapper.java`
    *   **测试文件**: `agent-demo-mcp/src/test/java/com/agentdemo/mcp/client/McpTransportWrapperTest.java`
    *   **参考**: 技术方案 Sec 4.6.1
    *   **对应AC**: AC-040
    *   **预估工时**: 90m
    *   **依赖**: 无
    *   **风险标注**: ⚠️ 需先通过 IDE 或反编译确认 `McpTransport` 接口的方法签名（`langchain4j-mcp:1.17.2-beta27` 无 source jar），确保所有方法都被正确代理
    *   **验证标准**（TDD RED 阶段的测试依据）:
        - [ ] `new McpTransportWrapper(delegateTransport)` 构造成功
        - [ ] Wrapper 调用 `initialize()` 时委托给 delegate 执行
        - [ ] Wrapper 调用 `close()` 时委托给 delegate 执行
        - [ ] Wrapper 调用工具执行相关方法后，`getLastRawResponse()` 返回缓存的原始 JSON-RPC 响应字符串
        - [ ] `clearCachedResponse()` 后 `getLastRawResponse()` 返回 null
        - [ ] 多线程场景下缓存不串（ThreadLocal 或请求作用域隔离）
        - [ ] delegate 抛异常时，异常原样传播，不被 Wrapper 吞掉

### 阶段二：后端集成 - 工厂与执行器修改 (Backend Integration)

- [x] **Task-19**: 修改 McpTransportFactory + McpClientEntry（集成包装器）
    *   **通俗解释**: 做完这步后，系统创建的每条 MCP 传输通道都会自动套上"透明薄膜"（Wrapper），并且每个 Server 的档案袋里都能方便地找到这层薄膜，供后续工具执行器使用。
    *   **说明**: 修改 `McpTransportFactory.createTransport()`，在返回原始 Transport 前用 `McpTransportWrapper` 包装。修改 `McpClientEntry`，新增 `getTransportWrapper()` 方法（将 `transport` 字段强转为 `McpTransportWrapper`），便于 Executor 获取缓存响应。
    *   **变更类型**: 修改
    *   **涉及文件**: `agent-demo-mcp/src/main/java/com/agentdemo/mcp/client/McpTransportFactory.java`、`agent-demo-mcp/src/main/java/com/agentdemo/mcp/client/McpClientEntry.java`
    *   **测试文件**: `agent-demo-mcp/src/test/java/com/agentdemo/mcp/client/McpTransportFactoryTest.java`（更新已有测试）
    *   **参考**: 技术方案 Sec 4.6.2
    *   **对应AC**: AC-040
    *   **预估工时**: 45m
    *   **依赖**: Task-18
    *   **验证标准**（TDD RED 阶段的测试依据）:
        - [ ] `factory.createTransport(stdioConfig)` 返回 `McpTransportWrapper` 实例（`instanceof McpTransportWrapper`）
        - [ ] `factory.createTransport(sseConfig)` 返回 `McpTransportWrapper` 实例
        - [ ] `factory.createTransport(httpConfig)` 返回 `McpTransportWrapper` 实例
        - [ ] `McpClientEntry.getTransportWrapper()` 返回 `McpTransportWrapper` 实例
        - [ ] Wrapper 内部的 delegate 是原始 Transport（Stdio/Http/StreamableHttp）
        - [ ] 已有 `McpTransportFactoryTest` 测试全部通过（回归）

- [x] **Task-20**: 修改 McpToolExecutor（从原始响应提取图片信息）
    *   **通俗解释**: 做完这步后，当 MCP 工具返回图片导致异常时，系统不再返回"无法展示"的通用提示，而是从缓存的原始响应中提取出图片链接，以 Markdown 格式返回给 LLM，LLM 可以把图片链接展示给用户。
    *   **说明**: 修改 `McpToolExecutor.execute()` 的 catch 块。当捕获到 `Unsupported content type` 异常时：(1) 从 `McpClientEntry` 获取 `McpTransportWrapper`；(2) 调用 `getLastRawResponse()` 获取原始 JSON-RPC 响应；(3) 解析 JSON 提取 `result.content` 数组；(4) 按 type 处理：text 提取文本、image+url 转为 `![图片](url)`、image+base64 转为文本描述；(5) 拼接返回。同时更新 `extractResultText()` 方法中 IMAGE 类型的处理，统一使用 Markdown 格式。
    *   **变更类型**: 修改
    *   **涉及文件**: `agent-demo-mcp/src/main/java/com/agentdemo/mcp/tool/McpToolExecutor.java`
    *   **测试文件**: `agent-demo-mcp/src/test/java/com/agentdemo/mcp/tool/McpToolExecutorTest.java`（更新已有测试 + 新增测试）
    *   **参考**: 技术方案 Sec 4.6.3
    *   **对应AC**: AC-036, AC-037, AC-038
    *   **预估工时**: 75m
    *   **依赖**: Task-18, Task-19
    *   **验证标准**（TDD RED 阶段的测试依据）:
        - [ ] `execute()` 当 mcpClient.executeTool 抛 `Unsupported content type: "image"` 且 Wrapper 缓存中含图片 URL 时，返回 `![图片](url)` 格式的 Markdown 文本
        - [ ] `execute()` 当 Wrapper 缓存中含 base64 图片时，返回包含 MIME 类型和数据长度摘要的文本描述
        - [ ] `execute()` 当 Wrapper 缓存中含混合内容（text + image）时，返回文本 + 图片 Markdown 拼接结果
        - [ ] `execute()` 当 Wrapper 缓存为 null 或空时（异常情况），回退到原有通用提示信息
        - [ ] `execute()` 当 mcpClient.executeTool 正常返回（纯文本内容）时，extractResultText 正常处理，不触发 Wrapper 缓存逻辑
        - [ ] 已有 11 个 McpToolExecutorTest 测试全部通过（回归）

### 阶段三：前端 - Markdown 图片渲染 (Frontend - Image Rendering)

- [x] **Task-21**: 前端 Markdown 图片渲染支持
    *   **通俗解释**: 做完这步后，当 LLM 在回复中使用 `![图片](链接)` 的 Markdown 语法时，前端对话界面能直接显示图片，而不是把图片标签过滤掉。
    *   **说明**: 修改 `markdown.ts` 的 `ALLOWED_TAGS` 数组新增 `'img'`（`ALLOWED_ATTR` 已包含 `src` 和 `alt`，无需修改）。修改 `MessageItem.vue` 的 `.markdown-body` 样式区新增 `img` 元素的 CSS 约束（`max-width: 100%`、`border-radius`、`margin`）。更新 `markdown.test.ts` 的 XSS 测试用例（原 `<img src=x onerror=alert(1)>` 测试需调整为验证 `onerror` 被移除但 `img` 标签保留），新增 Markdown 图片语法 `![alt](url)` 渲染为 `<img>` 标签的测试。
    *   **变更类型**: 修改
    *   **涉及文件**: `agent-demo-frontend/src/utils/markdown.ts`、`agent-demo-frontend/src/components/MessageItem.vue`、`agent-demo-frontend/src/utils/markdown.test.ts`
    *   **测试文件**: `agent-demo-frontend/src/utils/markdown.test.ts`
    *   **参考**: 前端调查报告
    *   **对应AC**: AC-039
    *   **预估工时**: 45m
    *   **依赖**: 无（可与后端任务并行）
    *   **验证标准**（TDD RED 阶段的测试依据）:
        - [ ] `renderMarkdown('![图片](https://example.com/a.png)')` 返回包含 `<img src="https://example.com/a.png" alt="图片">` 的 HTML
        - [ ] `renderMarkdown('<img src=x onerror=alert(1)>')` 返回的 HTML 不包含 `onerror`，但包含 `<img>` 标签（XSS 防护：属性过滤而非标签移除）
        - [ ] `renderMarkdown('![图片](javascript:alert(1))')` 返回的 HTML 不包含 `javascript:` 协议（DOMPurify 自动拦截）
        - [ ] 已有 markdown.test.ts 测试全部通过（回归）

### 阶段四：回归验证 (Regression Verification)

- [x] **Task-22**: 回归验证
    *   **说明**: 运行全量已有测试套件，确保变更未破坏原有功能。注意：每个增量任务内部已通过 TDD 循环完成了自身的测试编写，此处重点是验证跨模块的回归安全性。
    *   **变更类型**: 验证
    *   **涉及文件**: 后端 `agent-demo-mcp` + 前端 `agent-demo-frontend` 全量测试
    *   **对应AC**: AC-040（所有受影响的 AC）
    *   **预估工时**: 30m
    *   **依赖**: Task-18, Task-19, Task-20, Task-21
    *   **验证标准**:
        - [ ] 后端 `mvn test -pl agent-demo-mcp -am` 全部通过（原有测试无回归）
        - [ ] 前端 `npm test` 全部通过（原有测试无回归）
        - [ ] 本次变更的所有新增测试通过
        - [ ] 测试覆盖率未下降
        - [ ] 手动验证：启动应用 -> Agent 对话"帮我画一个流程图" -> mermaid-mcp 工具返回图片 -> 前端正确渲染图片

## 5. 增量验收标准检查清单 (Incremental AC Checklist)

| 验收标准ID | 验收标准描述 | 状态 | 对应任务 | 操作 |
| :--- | :--- | :--- | :--- | :--- |
| AC-036 | MCP 工具返回图片 URL 时系统从原始响应提取并返回给 LLM | ✅ 已完成 | Task-18, Task-19, Task-20 | 新增 |
| AC-037 | MCP 工具返回 base64 图片时系统提取图片信息返回给 LLM | ✅ 已完成 | Task-18, Task-19, Task-20 | 新增 |
| AC-038 | MCP 工具返回混合内容时系统正确提取所有内容 | ✅ 已完成 | Task-18, Task-19, Task-20 | 新增 |
| AC-039 | 前端对话界面支持 Markdown 图片渲染 | ✅ 已完成 | Task-21 | 新增 |
| AC-040 | Transport 包装器对现有三种传输方式无影响 | ✅ 已完成 | Task-18, Task-19, Task-22 | 新增 |

## 6. 变更总结 (Change Summary)

*   **总新增任务数**: 5 个（Task-18 ~ Task-22）
*   **预计总工时**: 285 分钟（约 4.75 小时）
*   **风险等级**: 中
*   **风险说明**:
    - ⚠️ `McpTransport` 接口方法签名需在编码阶段通过 IDE 确认（langchain4j-mcp:1.17.2-beta27 无 source jar）
    - ⚠️ Transport 包装器影响所有三种传输方式，需充分回归测试
    - ⚠️ 原始 JSON-RPC 响应格式可能因 MCP Server 实现不同而有差异，需灵活解析
    - 前端新增 img 标签的 XSS 风险由 DOMPurify 自动防护，但需验证测试覆盖
*   **测试影响**: 需修改 3 个已有测试文件（McpTransportFactoryTest、McpToolExecutorTest、markdown.test.ts），新增 1 个测试文件（McpTransportWrapperTest）
*   **预期效果**: MCP 工具（如 mermaid-mcp）返回图片内容时，Agent 能提取图片 URL 以 Markdown 格式返回给 LLM，LLM 可将图片链接转述给用户，前端对话界面正确渲染图片显示
