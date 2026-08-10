# 功能变更记录: MCP 协议模块 - CR-002

## 0. 变更概览 (Change Overview)

*   **变更标题**: MCP 输出解析结构性重构（内容类型策略分发器 + 统一解析路径）
*   **变更类型**: 重构 (Refactor)
*   **变更原因**: CR-001 的非文本内容处理采用"异常驱动双重解析"架构，存在 4 个结构性缺陷：(1) 异常驱动控制流--非文本内容是协议正常返回但通过 catch 块触发；(2) 双重解析路径违反 DRY--extractResultText 和 extractFromRawResponse 有重复逻辑；(3) 耦合 LangChain4j 内部异常行为；(4) 协议覆盖不完整--仅处理 text/image，缺少 audio/resource/structuredContent/unknown。本次重构引入 McpContentParser 内容类型策略分发器，统一从原始 JSON-RPC 响应解析，覆盖 MCP 协议全部内容类型。
*   **发起日期**: 2026-08-07
*   **开发方法**: TDD（测试驱动开发）- 每个任务按 Red-Green-Refactor 循环执行
*   **关联功能**: MCP 协议模块（agent-demo-mcp）
*   **关联文档**:
    -   需求文档: `specs/features/2026-08-05/MCP协议模块/MCP协议模块.md`（v1.2 新增 AC-041~045 + BR-MCP-023~025，修改 AC-036~038）
    -   技术方案: `specs/features/2026-08-05/MCP协议模块/MCP协议模块_技术方案.md`（v1.2 新增 Section 4.7）
    -   任务规划: `specs/features/2026-08-05/MCP协议模块/MCP协议模块_任务规划.md`（原 Task-01~17）
    -   CR-001 变更: `specs/features/2026-08-05/MCP协议模块/MCP协议模块_变更任务_CR-001.md`（Task-18~22，已完成）
    -   参考方案: `C:\Users\cy\Desktop\temp\agent_app\file\dify\06_MCP\18-MCP返回内容按类型分流详解.md`

## 1. 影响分析 (Impact Analysis)

### 1.1 需求影响

| 影响项 | 变更类型 | 详情 |
| :--- | :--- | :--- |
| AC-041 | 新增 | 系统统一从原始 JSON-RPC 响应解析所有 MCP 内容类型，不依赖 ToolExecutionResult |
| AC-042 | 新增 | 系统支持 AudioContent 类型内容处理 |
| AC-043 | 新增 | 系统支持 EmbeddedResource 内容类型处理（TextResourceContents + BlobResourceContents） |
| AC-044 | 新增 | 系统支持 structuredContent 结构化输出处理 |
| AC-045 | 新增 | 系统对未知内容类型静默跳过不崩溃 |
| AC-036 | 修改 | 从"异常路径提取"改为"统一解析路径提取"图片 URL |
| AC-037 | 修改 | 从"异常路径提取"改为"统一解析路径提取"base64 图片 |
| AC-038 | 修改 | 从"异常路径提取"改为"统一解析路径提取"混合内容 |
| BR-MCP-023 | 新增 | 统一从原始 JSON-RPC 响应解析，禁止依赖 ToolExecutionResult |
| BR-MCP-024 | 新增 | 内容解析按类型策略分发，新增类型仅需修改 Parser |
| BR-MCP-025 | 新增 | 未知类型静默跳过 + isError 添加 [工具错误] 前缀 |

### 1.2 技术影响

| 影响层 | 影响范围 | 详情 |
| :--- | :--- | :--- |
| 数据层 | 无影响 | 纯内存存储，无数据库变更 |
| API 层 | 无影响 | 不新增/修改 REST API |
| 业务逻辑 | 核心重构 | 新增 `McpContentParser` 内容类型策略分发器；重构 `McpToolExecutor` 删除 extractResultText/extractFromRawResponse，统一委托 McpContentParser |
| 表现层 | 无影响 | 前端无变更（Markdown 图片渲染已在 CR-001 完成） |

### 1.3 代码影响

| 文件路径 | 操作 | 影响说明 |
| :--- | :--- | :--- |
| `agent-demo-mcp/.../tool/McpContentParser.java` | 新增 | 内容类型策略分发器：输入原始 JSON-RPC 响应，按 type 分发到各处理器，输出 LLM 可用文本 |
| `agent-demo-mcp/.../tool/McpContentParserTest.java` | 新增 | 内容解析器单元测试（~12 个用例覆盖全类型） |
| `agent-demo-mcp/.../tool/McpToolExecutor.java` | 修改（核心） | 删除 extractResultText + extractFromRawResponse；新增 parseFromWrapper + isUnsupportedContentTypeException；注入 McpContentParser |
| `agent-demo-mcp/.../tool/McpToolExecutorTest.java` | 修改 | 8 个内容解析相关用例（#9-#16）需调整验证点 |
| `agent-demo-mcp/.../tool/McpImageContentE2ETest.java` | 修改 | 7 个 E2E 用例需调整（解析路径从异常驱动变为统一解析） |
| `agent-demo-mcp/.../client/McpTransportWrapper.java` | 无变更 | 缓存机制不变（AtomicReference 已验证） |
| `agent-demo-mcp/.../client/McpTransportFactory.java` | 无变更 | 包装逻辑不变 |
| `agent-demo-mcp/.../client/McpClientEntry.java` | 无变更 | getTransportWrapper() 已存在 |

### 1.4 测试影响

| 测试文件 | @Test 数 | 影响类型 | 说明 |
| :--- | :--- | :--- | :--- |
| `McpToolExecutorTest.java` | 16 | 需修改 | 8 个内容解析用例（#9-#16）需调整验证点；8 个基础执行用例（#1-#8）无影响 |
| `McpImageContentE2ETest.java` | 7 | 需修改 | 全部 7 个 E2E 用例需调整（解析路径从异常驱动变为统一解析） |
| `McpTransportWrapperTest.java` | 12 | 无影响 | 缓存机制不变 |
| `McpTransportFactoryTest.java` | 9 | 无影响 | 工厂逻辑不变 |
| `McpClientEntryTest.java` | 10 | 无影响 | 聚合对象不变 |
| `McpContentParserTest.java` | 0 -> ~12 | 需新增 | 覆盖 text/image/audio/resource/structured/unknown 全类型 + isError + 边界场景 |

### 1.5 回归风险评估

*   **高风险区域**: `McpToolExecutor.execute()` 核心执行逻辑变更，影响所有 MCP 工具调用路径
*   **已有测试覆盖**: 54 个测试中 22 个直接覆盖内容解析，需全部通过重构后验证
*   **需要补充的测试**: `McpContentParser` 独立单元测试（所有内容类型覆盖）+ 重构后 E2E 回归

## 2. 需求变更详情 (Requirements Delta)

### 2.1 新增/修改的用户故事

- **US-006**（CR-002 新增）: 作为 **对话用户**，我想要 **Agent 调用 MCP 工具返回音频、资源、结构化数据等多样内容时能正确处理**，以便 **不因内容类型不支持而导致工具调用失败，且能获取有意义的信息描述**。
    - 关联验收标准：AC-041, AC-042, AC-043, AC-044, AC-045

### 2.2 新增/修改的验收标准

#### 正常流程 (Happy Path)

- **AC-041**: 系统统一从原始 JSON-RPC 响应解析所有 MCP 内容类型（CR-002）
    - Given: MCP Server 已连接，Agent 调用任意 MCP 工具
    - When: MCP Server 返回任意类型内容（text/image/audio/resource/structuredContent），`mcpClient.executeTool()` 成功或抛出 `Unsupported content type` 异常
    - Then: 系统不依赖 `ToolExecutionResult` 的内容提取，统一从 `McpTransportWrapper` 缓存的原始 JSON-RPC 响应通过 `McpContentParser` 解析，返回 LLM 可消费的文本

- **AC-036**（CR-002 修改）: MCP 工具返回图片 URL 时系统从原始响应提取并返回给 LLM
    - Given: mermaid-mcp Server 已连接，Agent 调用 `validate_and_render_mermaid_diagram` 工具
    - When: MCP Server 返回包含 `ImageContent`（带 URL）的响应，系统通过 `McpContentParser` 统一解析原始 JSON-RPC 响应
    - Then: 系统从 `McpTransportWrapper` 缓存的原始 JSON-RPC 响应中提取图片 URL，以 Markdown 格式 `![图片](url)` 返回给 LLM

- **AC-037**（CR-002 修改）: MCP 工具返回 base64 图片时系统提取图片信息返回给 LLM
    - Given: MCP Server 返回包含 `ImageContent`（带 base64Data，无 URL）的响应
    - When: 系统通过 `McpContentParser` 统一解析原始 JSON-RPC 响应
    - Then: 系统从原始响应中提取 base64 图片信息（MIME 类型 + 数据摘要），返回文本描述给 LLM

- **AC-038**（CR-002 修改）: MCP 工具返回混合内容时系统正确提取所有内容
    - Given: MCP Server 返回包含 `TextContent` + `ImageContent` 的混合响应
    - When: 系统通过 `McpContentParser` 统一解析原始 JSON-RPC 响应
    - Then: 系统从原始响应中同时提取文本和图片信息，按响应顺序拼接返回给 LLM

#### 边界与异常 (Edge & Error Cases)

- **AC-042**: 系统支持 AudioContent 类型内容处理（CR-002）
    - Given: MCP Server 返回包含 `AudioContent`（带 base64Data + mimeType）的响应
    - When: 系统通过 `McpContentParser` 解析原始响应
    - Then: 系统返回文本描述（如 `[音频] 已生成 audio/wav 格式音频，base64 数据长度: 67890 字符`），不导致工具调用失败

- **AC-043**: 系统支持 EmbeddedResource 内容类型处理（CR-002）
    - Given: MCP Server 返回包含 `EmbeddedResource` 的响应，resource 子类型为 `TextResourceContents`（含 text 字段）或 `BlobResourceContents`（含 blob base64 字段）
    - When: 系统通过 `McpContentParser` 解析原始响应
    - Then: TextResourceContents 提取文本内容返回；BlobResourceContents 返回文本描述

- **AC-044**: 系统支持 structuredContent 结构化输出处理（CR-002）
    - Given: MCP Server 返回包含 `structuredContent` 字段的响应（JSON 对象）
    - When: 系统通过 `McpContentParser` 解析原始响应
    - Then: 系统将 structuredContent 序列化为 JSON 文本返回给 LLM

- **AC-045**: 系统对未知内容类型静默跳过不崩溃（CR-002）
    - Given: MCP Server 返回包含未知 `type` 字段的 content 项
    - When: 系统通过 `McpContentParser` 解析原始响应
    - Then: 未知类型 content 项被静默跳过并记录 WARNING 日志，其他已知类型 content 项正常解析，工具调用不失败

## 3. 技术变更详情 (Technical Delta)

### 3.1 数据库变更

无数据库变更（纯内存存储）。

### 3.2 API 变更

无 API 变更（不新增/修改 REST API）。

### 3.3 组件变更

| 操作 | 组件 | 说明 |
| :--- | :--- | :--- |
| 新增 | `McpContentParser.java` | 内容类型策略分发器：parse(rawResponse) -> text；内部按 type 分发到 processText/processImage/processAudio/processResource/processStructuredContent/processUnknown |
| 修改 | `McpToolExecutor.java` | 删除 extractResultText + extractFromRawResponse；新增 parseFromWrapper（委托 McpContentParser）+ isUnsupportedContentTypeException；构造器新增 McpContentParser 依赖注入 |
| 无变更 | `McpTransportWrapper.java` | AtomicReference 缓存机制不变 |
| 无变更 | `McpTransportFactory.java` | 包装逻辑不变 |
| 无变更 | `McpClientEntry.java` | getTransportWrapper() 已存在 |

### 3.4 兼容性说明

*   **向前兼容**: 完全兼容。对外接口（execute 方法签名、返回值格式）不变。已有功能（纯文本工具调用、图片 URL/base64 处理、前端 Markdown 渲染）行为一致。
*   **迁移方案**: 无需迁移。重构是内部实现变更，对上层透明。

## 4. 增量开发任务 (Incremental Tasks)

> 任务编号从 CR-001 最后一个任务（Task-22）之后继续
> 每个任务按 TDD 循环执行：RED（写测试）-> GREEN（写实现）-> REFACTOR（重构）

### 阶段一：内容解析器创建 (Content Parser Creation)

- [x] **Task-23**: 创建 McpContentParser（内容类型策略分发器）
    *   **通俗解释**: 做完这步后，系统就有了一个专业的"翻译官"，能把 MCP Server 返回的各种内容类型（文本、图片、音频、资源、结构化数据）统一翻译成 LLM 能理解的文本，不再需要依赖异常来触发解析。
    *   **说明**: 创建 `McpContentParser` 类（`@Component`），提供 `parse(String rawResponse)` 方法。内部解析 JSON-RPC 响应，提取 `result.content[]` 数组 + `result.isError` 标记 + `result.structuredContent`，按 content type 分发到 private 处理方法。处理方法包括：processText（提取文本）、processImage（URL->Markdown/base64->描述）、processAudio（描述）、processResource（Text->提取/Blob->描述）、processStructuredContent（JSON 序列化）、processUnknown（WARNING+跳过）。isError=true 时添加 `[工具错误]` 前缀。
    *   **变更类型**: 新增
    *   **涉及文件**: `agent-demo-mcp/src/main/java/com/agentdemo/mcp/tool/McpContentParser.java`
    *   **测试文件**: `agent-demo-mcp/src/test/java/com/agentdemo/mcp/tool/McpContentParserTest.java`
    *   **参考**: 技术方案 Sec 4.7.2 ~ 4.7.3、Dify MCP 内容分流参考文档
    *   **对应AC**: AC-041, AC-042, AC-043, AC-044, AC-045
    *   **预估工时**: 90m
    *   **依赖**: 无
    *   **验证标准**（TDD RED 阶段的测试依据）:
        - [ ] `parse(含 text content 的响应)` 返回文本内容
        - [ ] `parse(含 image+url 的响应)` 返回 `![图片](url)` Markdown 语法
        - [ ] `parse(含 image+base64 的响应)` 返回 `[图片] 已生成 {mimeType} 格式图片，base64 数据长度: {N} 字符`
        - [ ] `parse(含 audio 的响应)` 返回 `[音频] 已生成 {mimeType} 格式音频，base64 数据长度: {N} 字符`（AC-042）
        - [ ] `parse(含 resource+text 的响应)` 返回 resource.text 文本内容（AC-043）
        - [ ] `parse(含 resource+blob 的响应)` 返回 `[资源] {mimeType} 格式二进制资源，base64 数据长度: {N} 字符`（AC-043）
        - [ ] `parse(含 structuredContent 的响应)` 返回 JSON 文本（AC-044）
        - [ ] `parse(含未知 type 的响应)` 跳过未知项，已知项正常返回，记录 WARNING（AC-045）
        - [ ] `parse(含混合 content 的响应)` 按顺序拼接所有内容
        - [ ] `parse(isError=true 的响应)` 返回内容前缀 `[工具错误]`
        - [ ] `parse(null 或空字符串)` 返回 null
        - [ ] `parse(非法 JSON)` 返回 null，不抛异常
        - [ ] `parse(缺少 result.content 的响应)` 返回 null

### 阶段二：执行器重构 (Executor Refactoring)

- [x] **Task-24**: 重构 McpToolExecutor（统一解析路径）
    *   **通俗解释**: 做完这步后，工具执行器不再有"成功走路径A、失败走路径B"的双重解析逻辑，而是所有情况都走同一条路径--从 Wrapper 缓存的原始响应统一解析，代码更简洁、更可维护。
    *   **说明**: 修改 `McpToolExecutor`：(1) 构造器新增 `McpContentParser` 依赖注入；(2) 删除 `extractResultText(ToolExecutionResult)` 方法；(3) 删除 `extractFromRawResponse(McpClientEntry)` 方法；(4) 新增 `parseFromWrapper(McpClientEntry)` 方法（获取 Wrapper -> 读取缓存 -> 清理缓存 -> 委托 contentParser.parse -> 降级提示）；5) 新增 `isUnsupportedContentTypeException(RuntimeException)` 辅助方法；(6) 修改 `execute()` 方法：try 块中 `executeTool` 返回值被丢弃，catch 块中 Unsupported content type 视为预期行为继续到统一解析，其他异常照常抛 BusinessException。
    *   **变更类型**: 修改（核心重构）
    *   **涉及文件**: `agent-demo-mcp/src/main/java/com/agentdemo/mcp/tool/McpToolExecutor.java`
    *   **测试文件**: `agent-demo-mcp/src/test/java/com/agentdemo/mcp/tool/McpToolExecutorTest.java`（更新已有测试）
    *   **参考**: 技术方案 Sec 4.7.4
    *   **对应AC**: AC-041, AC-036, AC-037, AC-038
    *   **预估工时**: 75m
    *   **依赖**: Task-23
    *   **验证标准**（TDD RED 阶段的测试依据）:
        - [ ] `execute()` 当 mcpClient.executeTool 成功（纯文本）时，从 Wrapper 缓存解析返回文本（不使用 ToolExecutionResult）
        - [ ] `execute()` 当 mcpClient.executeTool 抛 Unsupported content type 时，从 Wrapper 缓存解析返回内容（不视为错误）
        - [ ] `execute()` 当 mcpClient.executeTool 抛 IOException 时，标记断线 + 抛 BusinessException
        - [ ] `execute()` 当 mcpClient.executeTool 抛其他 RuntimeException 时，抛 BusinessException
        - [ ] `execute()` 当 Wrapper 缓存为 null 时，返回降级提示
        - [ ] `execute()` 当 entry 无 Wrapper 时，返回降级提示
        - [ ] 已有 8 个基础执行用例（#1-#8）全部通过（回归）
        - [ ] 删除的 extractResultText / extractFromRawResponse 不再被引用

### 阶段三：测试适配 (Test Adaptation)

- [x] **Task-25**: 更新 McpToolExecutorTest（适配统一解析）
    *   **通俗解释**: 做完这步后，工具执行器的测试用例都适配了新的统一解析路径，验证点从"异常路径提取"变为"统一解析输出"，确保重构后所有场景都被正确覆盖。
    *   **说明**: 更新 `McpToolExecutorTest` 中 8 个内容解析相关用例（#9-#16）：(1) Mock McpContentParser 返回预期文本（替代直接验证 extractResultText/extractFromRawResponse 的内部逻辑）；(2) 验证 executeTool 成功时也走统一解析路径（不再仅验证异常路径）；(3) 验证 Unsupported content type 异常被正确识别为预期行为；(4) 保留降级提示验证（Wrapper 缓存为空/无 Wrapper 场景）。8 个基础执行用例（#1-#8）仅需适配构造器参数变更（新增 McpContentParser mock）。
    *   **变更类型**: 修改
    *   **涉及文件**: `agent-demo-mcp/src/test/java/com/agentdemo/mcp/tool/McpToolExecutorTest.java`
    *   **对应AC**: AC-036, AC-037, AC-038, AC-041
    *   **预估工时**: 60m
    *   **依赖**: Task-24
    *   **验证标准**:
        - [ ] 8 个基础执行用例（#1-#8）适配构造器后全部通过
        - [ ] 图片 URL 用例验证统一解析返回 `![图片](url)`
        - [ ] 图片 base64 用例验证统一解析返回描述文本
        - [ ] 混合内容用例验证统一解析返回拼接文本
        - [ ] Unsupported content type 用例验证异常被识别为预期行为 + 统一解析
        - [ ] 缓存为空用例验证降级提示
        - [ ] 无 Wrapper 用例验证降级提示

- [x] **Task-26**: 更新 McpImageContentE2ETest（适配统一解析）
    *   **通俗解释**: 做完这步后，端到端集成测试适配了新的统一解析路径，从模拟真实 mermaid-mcp 工具调用到最终输出，完整验证重构后的链路正确性。
    *   **说明**: 更新 `McpImageContentE2ETest` 全部 7 个 E2E 用例：(1) 验证点从"异常路径触发 Wrapper 缓存提取"改为"统一解析路径从缓存提取"；(2) 正常文本调用场景验证统一解析路径也生效（不再仅验证异常路径）；(3) 缓存清除验证保持不变；(4) 边界场景（缓存为空/JSON 异常）验证降级提示。使用真实 `McpTransportWrapper`（非 Mock）+ Mock `McpClient.executeTool` 模拟真实链路。
    *   **变更类型**: 修改
    *   **涉及文件**: `agent-demo-mcp/src/test/java/com/agentdemo/mcp/tool/McpImageContentE2ETest.java`
    *   **对应AC**: AC-036, AC-037, AC-038, AC-040, AC-041
    *   **预估工时**: 45m
    *   **依赖**: Task-24
    *   **验证标准**:
        - [ ] 图片 URL E2E 用例验证统一解析返回 Markdown 图片语法
        - [ ] base64 图片 E2E 用例验证统一解析返回描述文本
        - [ ] 混合内容 E2E 用例验证统一解析返回拼接文本
        - [ ] 正常文本调用 E2E 用例验证统一解析路径（非异常路径）
        - [ ] 缓存清除 E2E 用例验证 getLastRawResponse 返回 null
        - [ ] 缓存为空 E2E 用例验证降级提示
        - [ ] JSON 异常 E2E 用例验证降级提示

### 阶段四：回归验证 (Regression Verification)

- [x] **Task-27**: 回归验证
    *   **说明**: 运行全量已有测试套件，确保重构未破坏原有功能。注意：每个增量任务内部已通过 TDD 循环完成了自身的测试编写，此处重点是验证跨模块的回归安全性。
    *   **变更类型**: 验证
    *   **涉及文件**: 后端 `agent-demo-mcp` 全量测试
    *   **对应AC**: AC-040, AC-041（所有受影响的 AC）
    *   **预估工时**: 30m
    *   **依赖**: Task-23, Task-24, Task-25, Task-26
    *   **验证标准**:
        - [ ] 后端 `mvn test -pl agent-demo-mcp -am` 全部通过（原有测试无回归）
        - [ ] McpTransportWrapperTest 12 个用例全部通过（缓存机制无回归）
        - [ ] McpTransportFactoryTest 9 个用例全部通过（工厂逻辑无回归）
        - [ ] McpClientEntryTest 10 个用例全部通过（聚合对象无回归）
        - [ ] McpContentParserTest 新增用例全部通过
        - [ ] McpToolExecutorTest 16 个用例全部通过
        - [ ] McpImageContentE2ETest 7 个用例全部通过
        - [ ] 测试覆盖率未下降
        - [ ] 手动验证：启动应用 -> Agent 对话"帮我画一个流程图" -> mermaid-mcp 工具返回图片 -> 统一解析路径提取图片 URL -> 前端正确渲染

## 5. 增量验收标准检查清单 (Incremental AC Checklist)

| 验收标准ID | 验收标准描述 | 状态 | 对应任务 | 操作 |
| :--- | :--- | :--- | :--- | :--- |
| AC-041 | 系统统一从原始 JSON-RPC 响应解析所有内容类型 | 已完成 | Task-23, Task-24 | 新增 |
| AC-042 | 系统支持 AudioContent 类型内容处理 | 已完成 | Task-23 | 新增 |
| AC-043 | 系统支持 EmbeddedResource 内容类型处理 | 已完成 | Task-23 | 新增 |
| AC-044 | 系统支持 structuredContent 结构化输出处理 | 已完成 | Task-23 | 新增 |
| AC-045 | 系统对未知内容类型静默跳过不崩溃 | 已完成 | Task-23 | 新增 |
| AC-036 | MCP 工具返回图片 URL 时系统从原始响应提取 | 已完成 | Task-24, Task-25, Task-26 | 修改 |
| AC-037 | MCP 工具返回 base64 图片时系统提取图片信息 | 已完成 | Task-24, Task-25, Task-26 | 修改 |
| AC-038 | MCP 工具返回混合内容时系统正确提取所有内容 | 已完成 | Task-24, Task-25, Task-26 | 修改 |
| AC-040 | Transport 包装器对现有三种传输方式无影响 | 已完成 | Task-27 | 回归验证 |

## 6. 变更总结 (Change Summary)

*   **总新增任务数**: 5 个（Task-23 ~ Task-27）
*   **预计总工时**: 300 分钟（约 5 小时）
*   **风险等级**: 中
*   **风险说明**:
    - ⚠️ `McpToolExecutor.execute()` 核心执行逻辑变更，影响所有 MCP 工具调用路径
    - ⚠️ 15 个已有测试用例需调整验证点（8 个单元测试 + 7 个 E2E 测试）
    - ⚠️ 原始 JSON-RPC 响应格式可能因 MCP Server 实现不同而有差异，Parser 需对每个字段做 null 安全处理
    - ✅ McpTransportWrapper / McpTransportFactory / McpClientEntry 无变更，降低回归风险
    - ✅ McpContentParser 可独立单元测试（纯字符串输入输出），不依赖 McpClient/Transport
*   **测试影响**: 需修改 2 个已有测试文件（McpToolExecutorTest 8 用例 + McpImageContentE2ETest 7 用例），新增 1 个测试文件（McpContentParserTest ~12 用例）
*   **预期效果**: MCP 工具返回内容解析从"异常驱动双重路径"重构为"原始响应统一解析"架构，覆盖 MCP 协议全部内容类型（text/image/audio/resource/structuredContent/unknown），解耦 LangChain4j 内部异常行为依赖，代码可维护性和协议覆盖完整性显著提升
