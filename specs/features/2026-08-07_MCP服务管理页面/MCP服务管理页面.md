# 功能需求说明书：MCP 服务管理页面

## 1. 背景与价值 (Context & Value)

*   **背景**: 当前 MCP Server 只能通过 `application.yml` 静态配置或 REST API（curl）动态管理，用户无法在 Web 页面直接配置 MCP 服务。现有前端已有完整的 LLM 配置页面（厂商 CRUD + 模型管理 + 测试连接），需要一个统一的设置入口将 LLM 配置和 MCP 服务管理整合在一起。
*   **目标**: 创建统一「设置页面」，将现有 LLM 配置迁移为子标签页，新增 MCP 服务管理子标签页，支持用户通过业界通用的 JSON 配置格式（与 Claude Desktop / Cursor 一致）在页面上添加/删除/重连/查看 MCP Server 及其工具列表。
*   **关联**: MCP 协议模块 CR-003（前端管理扩展），参考 Claude Desktop / Cursor MCP 配置 UX 模式

## 2. 功能范围 (Scope)

### 2.1 本次范围（In Scope）

*   创建统一「设置页面」，设置入口在 NavBar 导航栏（替换原 "LLM 配置" 入口为 "设置"）
*   设置页面采用顶部标签页布局，包含 "LLM 配置" 和 "MCP 服务" 两个标签页
*   LLM 配置标签页：迁移现有 `LlmConfigPage.vue` 内容，功能行为不变
*   MCP 服务标签页（新增）：
    *   查看 Server 列表（名称、传输方式、地址/命令、状态、工具数）
    *   通过 JSON 配置编辑器添加 Server（支持 stdio/sse/http 全部传输方式）
    *   删除 Server（二次确认 + 断开连接 + 注销工具）
    *   重连 Server（对 DISCONNECTED/ERROR 状态的 Server 发起重连）
    *   查看工具列表（展开详情显示工具名称、注册名、描述、参数 Schema）
*   新增前端 API 封装 `api/mcp.ts`（封装已有后端 REST API）
*   新增 Pinia Store `stores/mcp.ts`（MCP 服务状态管理）

### 2.2 不在本次范围（Out of Scope）

*   **Server 编辑功能** - 后端暂无 PUT/PATCH 接口，如需修改请先删除再添加
*   **MCP 工具调用测试** - 工具调用通过 Agent ReAct 自主决策，不需要手动测试入口
*   **Server 排序/搜索/过滤** - Server 数量预期较小，暂不需要
*   **静态配置（application.yml）的页面化编辑** - 静态配置仍通过 yml 文件管理
*   **全量配置覆盖** - JSON 编辑器仅用于添加新 Server，不覆盖/同步已有 Server 配置

## 3. 用户角色 (Actors)

*   **对话用户**: 通过 Agent 对话间接受益于 MCP 工具能力，不直接操作管理页面
*   **配置用户**: 在设置页面通过 JSON 配置添加/删除/重连 MCP Server，查看工具列表
*   **开发者**: 通过 application.yml 预配置静态 Server，通过页面动态添加临时 Server

## 4. 用户故事 (User Stories)

*   **US-001**: 作为 **配置用户**，我想要 **在页面上查看所有 MCP Server 的列表和状态**，以便 **了解当前有哪些服务可用**。
    *   关联验收标准：AC-004, AC-005, AC-006

*   **US-002**: 作为 **配置用户**，我想要 **通过粘贴 JSON 配置添加 MCP Server（与 Claude Desktop 配置格式一致）**，以便 **快速接入各种 MCP 服务，包括 stdio 类型的本地服务**。
    *   关联验收标准：AC-007, AC-008, AC-009, AC-010, AC-011

*   **US-003**: 作为 **配置用户**，我想要 **删除不需要的 MCP Server**，以便 **清理无效的服务和工具注册**。
    *   关联验收标准：AC-012, AC-013

*   **US-004**: 作为 **配置用户**，我想要 **重连断线的 MCP Server**，以便 **在服务恢复后快速重新接入**。
    *   关联验收标准：AC-014, AC-015, AC-016

*   **US-005**: 作为 **配置用户**，我想要 **查看每个 MCP Server 暴露的工具列表**，以便 **了解服务提供了哪些能力**。
    *   关联验收标准：AC-017, AC-018

*   **US-006**: 作为 **配置用户**，我想要 **在设置页面同时管理 LLM 配置和 MCP 服务**，以便 **集中管理所有 Agent 配置**。
    *   关联验收标准：AC-001, AC-002, AC-003

## 5. 详细需求与流程 (Detailed Requirements)

### 5.1 核心流程

#### 5.1.1 设置页面导航流程

1. 用户在 NavBar 导航栏看到 "对话" | "知识库" | "设置" 三个导航项
2. 点击 "设置" -> 进入设置页面，默认显示 "LLM 配置" 标签页
3. 点击 "MCP 服务" 标签 -> 切换到 MCP 服务管理页面
4. 点击 "LLM 配置" 标签 -> 切换回 LLM 配置页面（保持之前的配置状态）

#### 5.1.2 通过 JSON 配置添加 MCP Server 流程

1. 在 MCP 服务标签页点击 "添加服务" 按钮
2. 弹出 JSON 配置编辑器弹窗，包含：
   - JSON 文本编辑区域（带语法高亮）
   - 配置格式说明（折叠/展开的示例）
   - "保存配置" 按钮
3. 用户输入 JSON 配置，格式与 Claude Desktop / Cursor 一致：
   ```json
   {
     "mcpServers": {
       "fetch": {
         "command": "npx",
         "args": ["mcp-fetch-server"]
       },
       "mermaid": {
         "url": "https://mcp.mermaid.ai/mcp"
       }
     }
   }
   ```
4. 点击 "保存配置" -> 前端校验 JSON 格式 -> 解析 mcpServers 对象
5. 对每个 Server 条目：
   - 根据字段推断传输方式：有 `command` -> stdio；有 `url` -> http（默认）；有 `url` + `"transport": "sse"` -> sse
   - 映射为后端 `CreateMcpServerRequest` 格式，调用 `POST /api/mcp/servers`
   - 后端自动连接 Server、拉取工具、注册到 ToolRegistry
6. 全部成功：关闭弹窗 + 刷新列表 + 显示成功提示
7. 部分失败：弹窗内显示每个 Server 的添加结果（成功/失败 + 错误原因），已成功的 Server 出现在列表中
8. 全部失败：弹窗不关闭，显示错误信息

#### 5.1.3 删除 MCP Server 流程

1. 在 Server 列表中找到目标 Server
2. 点击 "删除" 按钮
3. 弹出二次确认框："确认删除 [Server 名称]？删除后将断开连接并注销所有工具。"
4. 点击 "确认" -> 调用 `DELETE /api/mcp/servers/{name}`
5. 成功：从列表中移除 + 显示成功提示
6. 失败：显示错误信息

#### 5.1.4 重连 MCP Server 流程

1. 在 Server 列表中找到状态为 DISCONNECTED 或 ERROR 的 Server
2. 点击 "重连" 按钮（CONNECTED 状态的 Server 重连按钮禁用）
3. 调用 `POST /api/mcp/servers/{name}/reconnect`
4. 成功：更新状态为 CONNECTED + 刷新工具列表 + 显示成功提示
5. 失败：显示错误信息，状态保持不变

#### 5.1.5 查看工具列表流程

1. 在 Server 列表中，每个 Server 卡片显示工具数量（如 "3 个工具"）
2. 点击工具数量 -> 展开/折叠工具详情列表
3. 工具详情显示：工具原始名、注册名（`mcp_{serverName}_{toolName}`）、描述、参数 Schema
4. 再次点击 -> 折叠工具详情

### 5.2 交互/界面规则 (UI/UX Rules)

*   **设置页面标签页布局**：顶部水平标签页（"LLM 配置" | "MCP 服务"），切换时保持各自页面状态
*   **MCP Server 列表布局**：卡片网格布局（与 LLM 配置的 VendorCard 布局一致）
*   **状态徽章颜色**：CONNECTED -> 绿色、DISCONNECTED -> 灰色、ERROR -> 红色、DISABLED -> 橙色
*   **JSON 配置编辑器**：居中模态弹窗，包含 textarea 文本编辑区域（等宽字体，支持语法高亮），下方显示配置格式说明（可折叠）
*   **配置格式说明**：默认折叠，展开后显示 JSON 格式示例和字段说明（command/args -> stdio，url -> http/sse，transport 可选字段）
*   **删除二次确认**：使用 `window.confirm` 或自定义确认弹窗（与 VendorCard 删除行为一致）
*   **操作反馈**：所有操作成功/失败均显示 Toast 提示
*   **加载状态**：添加/重连操作进行中时按钮显示 loading 状态，禁止重复点击
*   **空状态**：无 Server 时显示 "暂无 MCP 服务，点击添加" 引导
*   **工具列表展开动画**：使用 CSS transition 实现展开/折叠过渡
*   **Server 卡片地址显示**：stdio 类型显示 command + args（如 "npx mcp-fetch-server"），sse/http 类型显示 URL

### 5.3 业务规则 (Business Rules)

*   **BR-MCP-FE-001**: MCP Server 名称 1-50 字符，仅允许中英文、数字、下划线和连字符（JSON 配置中的 key 即为名称，前端解析后校验）
*   **BR-MCP-FE-002**: MCP Server 名称全局唯一（JSON 配置中已有的 Server 名称，提交后接收后端唯一性校验结果并提示）
*   **BR-MCP-FE-003**: JSON 配置必须符合 `mcpServers` 格式：顶层为 `{"mcpServers": {...}}`，每个 Server 为 key-value 对，key 为名称，value 为配置对象
*   **BR-MCP-FE-004**: 传输方式根据配置字段自动推断：有 `command` 字段 -> stdio；有 `url` 字段且无 `command` -> HTTP（默认）；有 `url` + `"transport": "sse"` -> SSE
*   **BR-MCP-FE-005**: stdio 类型必须包含 `command` 字段；`args` 和 `env` 为可选字段
*   **BR-MCP-FE-006**: sse/http 类型必须包含 `url` 字段（合法 http/https URL）；`headers` 为可选字段
*   **BR-MCP-FE-007**: 删除 Server 必须二次确认，确认框需明示 "将断开连接并注销所有工具"
*   **BR-MCP-FE-008**: 重连按钮仅在 Server 状态为 DISCONNECTED 或 ERROR 时可用，CONNECTED 状态显示 "已连接" 且不可点击
*   **BR-MCP-FE-009**: 添加/重连操作进行中时按钮禁用并显示 loading 状态，防止重复提交
*   **BR-MCP-FE-010**: MCP 模块禁用时（后端返回 MCP_MODULE_DISABLED），页面显示 "MCP 模块已禁用，请在配置中启用" 提示，操作按钮禁用
*   **BR-MCP-FE-011**: Server 列表在添加/删除/重连操作成功后自动刷新，无需手动刷新
*   **BR-MCP-FE-012**: JSON 配置支持一次添加多个 Server，每个 Server 独立处理（部分失败不影响其他 Server 添加）

## 6. 验收标准 (Acceptance Criteria)

> **重要**：以下验收标准是后续技术方案、任务规划和 TDD 测试用例的直接依据。每条 AC 必须使用 Given-When-Then 格式，必须可被测试验证。

### 6.1 正常流程 (Happy Path)

- [ ] **AC-001**: 设置页面导航
    - Given: 用户在前端页面
    - When: 点击 NavBar 中的 "设置" 导航项
    - Then: 进入设置页面，默认显示 "LLM 配置" 标签页内容，顶部显示 "LLM 配置" 和 "MCP 服务" 两个标签页切换按钮

- [ ] **AC-002**: 切换到 MCP 服务标签页
    - Given: 用户在设置页面
    - When: 点击 "MCP 服务" 标签
    - Then: 显示 MCP 服务管理页面，包含 Server 列表区域和 "添加服务" 按钮

- [ ] **AC-003**: 切换回 LLM 配置标签页
    - Given: 用户在 MCP 服务标签页
    - When: 点击 "LLM 配置" 标签
    - Then: 显示 LLM 配置页面，之前配置的厂商列表和状态保持不变

- [ ] **AC-004**: 查看 Server 列表
    - Given: 用户进入 MCP 服务标签页，后端已有配置的 Server
    - When: 页面加载完成
    - Then: 自动调用 `GET /api/mcp/servers` 获取列表，以卡片网格展示每个 Server 的名称、传输方式、地址/命令、状态徽章、工具数量

- [ ] **AC-005**: Server 状态徽章正确显示
    - Given: Server 列表已加载
    - When: 页面渲染 Server 卡片
    - Then: CONNECTED 显示绿色徽章、DISCONNECTED 显示灰色徽章、ERROR 显示红色徽章、DISABLED 显示橙色徽章

- [ ] **AC-006**: 空列表引导
    - Given: 后端无已配置的 MCP Server
    - When: 用户进入 MCP 服务标签页
    - Then: 显示 "暂无 MCP 服务" 空状态提示 + "添加服务" 引导按钮

- [ ] **AC-007**: 打开 JSON 配置编辑器
    - Given: 用户在 MCP 服务标签页
    - When: 点击 "添加服务" 按钮
    - Then: 弹出居中模态弹窗，包含 JSON 文本编辑区域（等宽字体）和配置格式说明（可折叠），编辑区域有占位提示显示 JSON 格式示例

- [ ] **AC-008**: 通过 JSON 配置添加 stdio 类型 Server 成功
    - Given: 用户在 JSON 编辑器中输入了有效配置 `{"mcpServers": {"fetch": {"command": "npx", "args": ["mcp-fetch-server"]}}}`
    - When: 点击 "保存配置" 按钮
    - Then: 前端解析 JSON，推断传输方式为 stdio，调用 `POST /api/mcp/servers`（transport=stdio, command=npx, args=["mcp-fetch-server"]），成功后关闭弹窗、刷新列表、新 Server "fetch" 显示在列表中且状态为 CONNECTED、显示 "添加成功" Toast 提示

- [ ] **AC-009**: 通过 JSON 配置添加 http 类型 Server 成功
    - Given: 用户在 JSON 编辑器中输入了有效配置 `{"mcpServers": {"mermaid": {"url": "https://mcp.mermaid.ai/mcp"}}}`
    - When: 点击 "保存配置" 按钮
    - Then: 前端解析 JSON，推断传输方式为 HTTP（有 url 无 command），调用 `POST /api/mcp/servers`（transport=http, url=https://mcp.mermaid.ai/mcp），成功后关闭弹窗、刷新列表

- [ ] **AC-010**: 通过 JSON 配置添加 SSE 类型 Server 成功
    - Given: 用户在 JSON 编辑器中输入了有效配置 `{"mcpServers": {"myserver": {"url": "https://example.com/sse", "transport": "sse"}}}`
    - When: 点击 "保存配置" 按钮
    - Then: 前端解析 JSON，识别 `transport: "sse"` 字段，调用 `POST /api/mcp/servers`（transport=sse），成功后关闭弹窗、刷新列表

- [ ] **AC-011**: 通过 JSON 配置批量添加多个 Server
    - Given: 用户在 JSON 编辑器中输入了包含 2 个 Server 的配置（fetch + mermaid）
    - When: 点击 "保存配置" 按钮
    - Then: 前端解析 JSON，对每个 Server 分别调用 `POST /api/mcp/servers`，全部成功后关闭弹窗、刷新列表、2 个新 Server 均显示在列表中

- [ ] **AC-012**: 删除 Server 成功
    - Given: Server 列表中有一个 Server
    - When: 点击该 Server 卡片的 "删除" 按钮 -> 确认二次确认框
    - Then: 调用 `DELETE /api/mcp/servers/{name}`，成功后从列表中移除该 Server、显示 "删除成功" Toast 提示

- [ ] **AC-013**: 删除二次确认
    - Given: 用户点击 Server 卡片的 "删除" 按钮
    - When: 弹出确认框
    - Then: 确认框文本包含 "将断开连接并注销所有工具" 明示信息，点击 "取消" 不执行删除

- [ ] **AC-014**: 重连 Server 成功
    - Given: Server 列表中有一个 DISCONNECTED 状态的 Server
    - When: 点击该 Server 卡片的 "重连" 按钮
    - Then: 按钮显示 loading 状态，调用 `POST /api/mcp/servers/{name}/reconnect`，成功后状态更新为 CONNECTED、工具列表刷新、显示 "重连成功" Toast 提示

- [ ] **AC-015**: 重连按钮可用性
    - Given: Server 列表中有一个 CONNECTED 状态的 Server
    - When: 页面渲染该 Server 卡片
    - Then: "重连" 按钮禁用或显示 "已连接"，不可点击

- [ ] **AC-016**: 重连已连接的 Server 报错
    - Given: Server 列表中有一个 CONNECTED 状态的 Server
    - When: 用户尝试重连该 Server（如按钮未禁用）
    - Then: 显示错误提示 "MCP Server 已连接，无需重连"

- [ ] **AC-017**: 工具列表展示
    - Given: Server 列表中某个 CONNECTED 状态的 Server 有 3 个工具
    - When: 页面渲染该 Server 卡片
    - Then: 卡片上显示 "3 个工具" 文本

- [ ] **AC-018**: 展开工具详情
    - Given: Server 卡片显示 "3 个工具"
    - When: 点击工具数量文本
    - Then: 展开工具详情列表，显示每个工具的原始名、注册名（`mcp_{serverName}_{toolName}`）、描述、参数 Schema；再次点击折叠

### 6.2 边界与异常 (Edge & Error Cases)

- [ ] **AC-019**: JSON 格式校验失败
    - Given: 用户在 JSON 编辑器中输入了非法 JSON（如 `{invalid json!!!`）
    - When: 点击 "保存配置" 按钮
    - Then: 显示错误提示 "JSON 格式错误：[解析错误详情]"，不发起 API 请求，弹窗不关闭

- [ ] **AC-020**: JSON 缺少 mcpServers 字段
    - Given: 用户输入了 `{"servers": {}}` （缺少 mcpServers 顶层字段）
    - When: 点击 "保存配置" 按钮
    - Then: 显示错误提示 "配置必须包含 mcpServers 字段"，不发起 API 请求

- [ ] **AC-021**: Server 配置缺少必要字段
    - Given: 用户输入了 `{"mcpServers": {"bad": {}}}` （既无 command 也无 url）
    - When: 点击 "保存配置" 按钮
    - Then: 显示错误提示 "Server 'bad' 配置无效：必须包含 command（stdio）或 url（sse/http）字段"

- [ ] **AC-022**: 名称重复
    - Given: 后端已存在名为 "fetch" 的 Server
    - When: 用户通过 JSON 配置添加名为 "fetch" 的 Server
    - Then: 该 Server 添加失败，显示错误提示 "fetch: MCP Server 名称已存在"；如配置中有其他新 Server，继续尝试添加

- [ ] **AC-023**: 连接失败
    - Given: 用户通过 JSON 配置添加了一个 URL 不可达的 Server
    - When: 点击 "保存配置"
    - Then: 该 Server 添加失败，显示错误提示 "[Server名]: MCP 连接失败：[错误详情]"；如配置中有其他 Server，继续尝试添加

- [ ] **AC-024**: 部分成功部分失败
    - Given: 用户通过 JSON 配置添加 2 个 Server（A 和 B），其中 A 成功、B 名称重复
    - When: 点击 "保存配置"
    - Then: 弹窗内显示结果：A -> 成功、B -> 失败（名称已存在）；列表刷新后 A 出现在列表中；弹窗保持打开（因为有失败项），用户可关闭弹窗

- [ ] **AC-025**: 删除不存在的 Server
    - Given: Server 列表中有一个 Server，但在用户点击删除前该 Server 已被其他方式删除
    - When: 用户确认删除
    - Then: 显示错误提示 "MCP Server 不存在"，列表刷新后该 Server 消失

- [ ] **AC-026**: MCP 模块禁用
    - Given: 后端 `mcp.enabled=false`
    - When: 用户进入 MCP 服务标签页
    - Then: 页面显示 "MCP 模块已禁用，请在配置中启用" 提示，"添加服务" 按钮禁用

- [ ] **AC-027**: 网络异常
    - Given: 后端服务不可达
    - When: 用户进入 MCP 服务标签页或执行任何操作
    - Then: 显示 "网络异常，请检查后端服务是否运行" 错误提示，页面不崩溃

- [ ] **AC-028**: 重复提交防护
    - Given: 用户点击 "保存配置" 后请求正在进行中
    - When: 用户再次点击 "保存配置" 按钮
    - Then: 按钮处于禁用状态，不会发起第二次请求

### 6.3 业务规则验证 (Business Rules)

- [ ] **AC-029**: 传输方式自动推断 - stdio
    - Given: JSON 配置中某 Server 包含 `command` 字段
    - When: 前端解析配置
    - Then: 传输方式推断为 stdio，映射到后端请求的 transport=stdio

- [ ] **AC-030**: 传输方式自动推断 - HTTP（默认）
    - Given: JSON 配置中某 Server 包含 `url` 字段但无 `command` 字段，且无显式 `transport` 字段
    - When: 前端解析配置
    - Then: 传输方式推断为 HTTP，映射到后端请求的 transport=http

- [ ] **AC-031**: 传输方式显式指定 - SSE
    - Given: JSON 配置中某 Server 包含 `url` 字段和 `"transport": "sse"` 字段
    - When: 前端解析配置
    - Then: 传输方式使用显式指定的 SSE，映射到后端请求的 transport=sse

- [ ] **AC-032**: Headers 传递
    - Given: JSON 配置中某 Server 包含 `headers: {"Authorization": "Bearer xxx"}`
    - When: 前端解析配置并调用后端 API
    - Then: headers 字段正确传递到后端请求中

- [ ] **AC-033**: env 传递
    - Given: JSON 配置中某 stdio Server 包含 `env: {"NODE_ENV": "production"}`
    - When: 前端解析配置并调用后端 API
    - Then: env 字段正确传递到后端请求中

- [ ] **AC-034**: 操作后自动刷新
    - Given: 用户成功添加/删除/重连一个 Server
    - When: 操作完成的成功回调执行
    - Then: Server 列表自动刷新（无需手动点击刷新按钮），反映最新状态

- [ ] **AC-035**: LLM 配置迁移无回归
    - Given: 用户在设置页面切换到 "LLM 配置" 标签页
    - When: 进行厂商 CRUD 操作（添加/编辑/删除/测试连接）
    - Then: 所有功能行为与迁移前完全一致，无功能退化

- [ ] **AC-036**: Server 卡片地址显示
    - Given: Server 列表已加载
    - When: 页面渲染 Server 卡片
    - Then: stdio 类型 Server 显示 command + args（如 "npx mcp-fetch-server"），sse/http 类型 Server 显示 URL（如 "https://mcp.mermaid.ai/mcp"）

---

### AC 覆盖度自检
- [x] 正常流程的每个关键步骤都有对应 AC（导航、列表、JSON 添加 stdio/http/sse、批量添加、删除、重连、工具详情）
- [x] 第 5.3 节的每条业务规则都有对应 AC（BR-MCP-FE-001~012 分别对应 AC-019/020/021/022/029/030/031/013/015/026/034/024）
- [x] 所有已识别的边界/异常情况都有对应 AC（JSON 校验、缺少字段、重复、连接失败、部分成功、模块禁用、网络异常、重复提交）
- [x] 每条 AC 描述的是可观测行为，而非内部实现
