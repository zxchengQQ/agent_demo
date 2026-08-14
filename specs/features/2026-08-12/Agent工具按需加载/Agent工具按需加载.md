# 功能需求说明书：Agent 工具按需加载

## 1. 背景与价值 (Context & Value)

- **背景**：当前 Agent 启动时全量加载所有工具（内置工具 + MCP 动态工具 + 知识库动态工具），导致 Agent 上下文过长、Token 消耗大，且 LLM 可能因工具过多而选择错误工具。同时，部分工具（如 HTTP 请求、文件读取）存在安全风险，不应默认暴露给所有对话场景。
- **目标**：Agent 默认仅加载通用安全工具，其他工具需通过 API 或前端显式指定后才加载，实现工具按需加载、降低 Token 消耗、提升安全性。
- **关联**：Roadmap Phase 工具调用优化

## 2. 功能范围 (Scope)

### 2.1 本次范围（In Scope）

- application.yml 工具分组配置（default / optional）
- 工具标识规范：`category:name` 格式，支持通配符 `*`
- ToolRegistry 按标识过滤加载工具
- SimpleAgent 按需绑定工具（默认工具 + API 指定工具）
- ChatRequest 新增 `tools` 参数，支持运行时追加工具
- 工具不存在时返回明确错误码，阻止对话
- 前端输入框 `@` 语法工具选择器
- 前端设置页面工具清单管理

### 2.2 不在本次范围（Out of Scope）

- 工具级别超时/描述覆盖 — 后续迭代
- 工具版本管理 — 暂不需要
- 工具权限控制（RBAC） — 后续迭代
- 工具使用统计/计费 — 后续迭代
- 前端工具搜索/高级过滤 — 后续迭代

## 3. 用户角色 (Actors)

- **开发者**：通过 application.yml 配置工具分组，决定哪些工具默认加载、哪些可选
- **对话用户**：通过前端 `@` 语法或 API 参数指定本次对话需要加载的额外工具

## 4. 用户故事 (User Stories)

- **US-001**：作为开发者，我想要在配置文件中指定默认工具和可选工具，以便控制 Agent 的基础能力范围。
  - 关联验收标准：AC-001, AC-008, AC-012
- **US-002**：作为对话用户，我想要在对话时动态指定需要加载的工具，以便按需使用 MCP 或知识库工具。
  - 关联验收标准：AC-002, AC-003, AC-013
- **US-003**：作为对话用户，我想要在输入框中通过 `@` 快速选择工具，以便便捷地指定工具而无需手动输入工具名。
  - 关联验收标准：AC-004, AC-005, AC-011
- **US-004**：作为对话用户，我想要在设置页面查看所有可用工具，以便了解当前系统有哪些工具、哪些是默认加载的。
  - 关联验收标准：AC-006, AC-009, AC-010

## 5. 详细需求与流程 (Detailed Requirements)

### 5.1 核心流程

**流程一：系统启动 — 工具分组加载**

1. 系统启动，读取 `application.yml` 中 `agent.tools.default` 和 `agent.tools.optional` 配置
2. ToolRegistry 扫描所有工具，按 `category:name` 标识归类
3. 校验 default 列表中的工具是否存在，不存在则 ERROR 日志并跳过
4. default 列表中的工具标记为"默认加载"
5. optional 列表中的工具标记为"可选"，需显式指定才加载

**流程二：对话时 — 工具按需绑定**

1. 用户发起对话，ChatRequest 携带 `tools` 参数（可选）
2. SimpleAgent 计算本次对话应加载的工具 = 默认工具 + API 指定工具
3. 校验 API 指定的工具是否在 optional 列表中存在，不存在则返回错误码
4. 将最终工具列表绑定到 AiServices delegate
5. 后续对话中 LLM 仅可见这些工具

**流程三：前端 `@` 语法 — 工具选择**

1. 用户在输入框中输入 `@`
2. 弹出工具选择下拉面板，按类别分组展示可选工具
3. 默认工具已勾选且不可取消（灰色 + 锁定图标）
4. 用户可勾选/取消可选工具
5. 用户选择完成后，选中的工具以标签形式展示在输入框上方
6. 发送消息时，工具标签转换为 `tools` 参数随请求发送

### 5.2 交互/界面规则 (UI/UX Rules)

- `@` 触发下拉面板，失去焦点或按 Esc 关闭
- 下拉面板按类别分组：内置工具、MCP 工具、知识库工具
- 默认工具项显示锁定图标，不可取消勾选
- 选中的可选工具在输入框上方以标签形式展示，点击 × 可取消
- 标签颜色按类别区分（内置-蓝色、MCP-绿色、知识库-橙色）
- 设置页面工具清单展示：工具标识、所属类别、描述、默认/可选状态
- 工具选择状态按会话维度保持（与知识库选择器、深度思考开关行为一致）

### 5.3 业务规则 (Business Rules)

- **BR-TOOL-010**：默认工具不可通过 API 排除，最终工具 = 默认 ∪ 指定
- **BR-TOOL-011**：工具标识格式为 `category:name`，category 取值：`builtin` / `mcp` / `rag`
- **BR-TOOL-012**：`name` 支持通配符 `*`，表示该类别下所有工具
- **BR-TOOL-013**：API 指定不存在的工具时，返回 TOOL_NOT_FOUND(5101)，提示具体工具名
- **BR-TOOL-014**：API 指定格式错误的工具标识时，返回 TOOL_PARAM_INVALID(5102)
- **BR-TOOL-015**：默认工具配置了不存在的工具时，启动日志 ERROR 并跳过，不阻塞启动
- **BR-TOOL-016**：MCP Server 断开后其工具从可选列表移除，API 指定已断开的工具报 TOOL_NOT_FOUND
- **BR-TOOL-017**：知识库删除后其工具从可选列表移除，API 指定已删除的知识库工具报 TOOL_NOT_FOUND
- **BR-TOOL-018**：前端工具选择器按会话维度保持状态，切换会话互不影响
- **BR-TOOL-019**：ChatRequest.tools 为空或不传时，仅加载默认工具

## 6. 验收标准 (Acceptance Criteria)

### 6.1 正常流程 (Happy Path)

- [ ] **AC-001**：Agent 启动后仅加载默认工具
  - Given: application.yml 配置 `agent.tools.default: [builtin:getCurrentTime, builtin:calculate]`
  - When: 系统启动完成，用户发起对话
  - Then: Agent 仅可使用 getCurrentTime 和 calculate，尝试调用 httpGet 或 readFile 时 LLM 不可见这些工具

- [ ] **AC-002**：API 追加可选工具
  - Given: 默认工具为 `[builtin:getCurrentTime]`，optional 包含 `[builtin:httpGet]`
  - When: 用户通过 ChatRequest 传入 `tools: ["builtin:httpGet"]`
  - Then: Agent 可使用 getCurrentTime 和 httpGet 两个工具

- [ ] **AC-003**：通配符加载整类工具
  - Given: optional 包含 `[mcp:*]`，已连接 mermaid-mcp 和 fetch 两个 MCP Server
  - When: 用户通过 ChatRequest 传入 `tools: ["mcp:*"]`
  - Then: Agent 可调用所有 MCP 工具（mermaid-mcp 的 generate 工具和 fetch 的 fetch 工具）

- [ ] **AC-004**：前端 `@` 语法弹出工具选择器
  - Given: 用户在对话输入框中，系统已连接 MCP Server 和知识库
  - When: 用户输入 `@`
  - Then: 弹出工具选择下拉面板，按"内置工具""MCP 工具""知识库工具"分组展示，默认工具已勾选且锁定

- [ ] **AC-005**：前端 `@` 选择工具后发送消息
  - Given: 用户通过 `@` 选择了 `mcp:mermaid-mcp`
  - When: 用户输入消息并发送
  - Then: API 请求中 `tools` 字段包含 `["mcp:mermaid-mcp"]`，Agent 可调用 mermaid 工具

- [ ] **AC-006**：设置页面查看工具清单
  - Given: 系统已启动，已连接 MCP Server 和知识库
  - When: 用户打开设置页面的"工具管理"标签
  - Then: 展示所有工具清单，按类别分组，每项显示工具标识、描述、默认/可选状态

### 6.2 边界与异常 (Edge & Error Cases)

- [ ] **AC-007**：API 指定不存在的工具报错
  - Given: 系统中不存在 `builtin:nonExistent` 工具
  - When: 用户通过 ChatRequest 传入 `tools: ["builtin:nonExistent"]`
  - Then: 返回错误码 5101，消息包含"工具不存在: builtin:nonExistent"，对话不执行

- [ ] **AC-008**：默认工具配置了不存在的工具时启动不阻塞
  - Given: application.yml 的 default 列表包含 `["builtin:nonExistent", "builtin:getCurrentTime"]`
  - When: 系统启动
  - Then: 启动日志输出 ERROR "默认工具不存在: builtin:nonExistent"，getCurrentTime 正常加载，系统正常启动

- [ ] **AC-009**：MCP Server 断开后其工具不可用
  - Given: mermaid-mcp 已连接并注册工具，用户在前端选择了 `mcp:mermaid-mcp`
  - When: mermaid-mcp 断开连接
  - Then: 该工具从可选列表中移除，前端 `@` 面板中不再显示；若用户仍尝试发送指定该工具的请求，返回 5101

- [ ] **AC-010**：知识库删除后其工具自动注销
  - Given: 知识库 kb-abc123 存在，用户在前端选择了 `rag:kb-abc123`
  - When: 用户删除知识库 kb-abc123
  - Then: 该工具从可选列表中移除，前端 `@` 面板中不再显示；若用户仍尝试发送指定该工具的请求，返回 5101

- [ ] **AC-011**：前端 `@` 后输入不存在的工具名
  - Given: 用户在输入框中输入 `@`
  - When: 用户继续输入 `不存在的工具名`
  - Then: 下拉面板过滤结果为空，不创建工具标签，输入内容保持为普通文本

- [ ] **AC-012**：空 tools 参数等同于仅加载默认工具
  - Given: 默认工具为 `[builtin:getCurrentTime]`
  - When: ChatRequest.tools 为空数组或不传该字段
  - Then: Agent 仅加载 getCurrentTime 一个工具

### 6.3 业务规则验证 (Business Rules)

- [ ] **AC-013**：默认工具不可通过 API 排除
  - Given: 默认工具为 `[builtin:getCurrentTime, builtin:calculate]`
  - When: 用户通过 ChatRequest 传入 `tools: ["builtin:httpGet"]`（未包含默认工具）
  - Then: Agent 最终加载的工具 = [getCurrentTime, calculate, httpGet]（默认 + 指定）

- [ ] **AC-014**：工具标识格式校验
  - Given: 用户在 ChatRequest 中传入 `tools: ["invalidFormat"]`
  - When: 系统解析 tools 参数
  - Then: 返回错误码 5102，消息提示正确格式为 "category:name"（如 builtin:getCurrentTime）

- [ ] **AC-015**：前端工具选择器按会话维度保持状态
  - Given: 用户在会话 A 中通过 `@` 选择了 `mcp:mermaid-mcp`
  - When: 用户切换到会话 B
  - Then: 会话 B 的工具选择恢复为默认（仅默认工具）；切换回会话 A，仍保持 `mcp:mermaid-mcp` 被选中

---

### AC 覆盖度自检

- [x] 正常流程的每个关键步骤都有对应 AC（AC-001 ~ AC-006）
- [x] 第 5.3 节的每条业务规则都有对应 AC（AC-007 ~ AC-015）
- [x] 所有已识别的边界/异常情况都有对应 AC（AC-007 ~ AC-012）
- [x] 每条 AC 描述的是可观测行为，而非内部实现

## 7. 配置设计参考

### 7.1 application.yml 配置示例

```yaml
agent:
  tools:
    # 默认加载的工具（Agent 始终可用，无需指定）
    default:
      - builtin:getCurrentTime    # 时间查询
      - builtin:calculate         # 计算器
    # 可选工具（需通过 API 显式指定才加载）
    optional:
      - builtin:httpGet           # HTTP GET 请求
      - builtin:httpPost          # HTTP POST 请求
      - builtin:readFile          # 文件读取
      - mcp:*                     # 所有 MCP 工具
      - rag:*                     # 所有知识库工具
```

### 7.2 工具标识规范

| 类别 | 格式 | 示例 | 通配符 |
|------|------|------|:---:|
| 内置工具 | `builtin:{方法名}` | `builtin:getCurrentTime` | — |
| MCP 工具 | `mcp:{serverName}` | `mcp:mermaid-mcp` | `mcp:*` |
| 知识库工具 | `rag:{kbId}` | `rag:kb-abc123` | `rag:*` |

### 7.3 ChatRequest 新增字段

```json
{
  "sessionId": "abc123",
  "message": "帮我画一个流程图",
  "model": "ark:doubao-seed-2.0-code",
  "tools": ["mcp:mermaid-mcp"]
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|:---:|------|
| tools | String[] | 否 | 本次对话额外加载的工具标识列表。为空或不传时仅加载默认工具 |

---

## 8. 变更日志

| 日期 | 版本 | 变更内容 |
|------|------|---------|
| 2026-08-12 | v1.0 | 初始版本：需求澄清完成，含 15 条 AC、10 条业务规则 |