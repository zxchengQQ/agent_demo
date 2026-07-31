# 功能变更记录: RAG 知识库问答 - CR-002

## 0. 变更概览 (Change Overview)

* **变更标题**: 分块数据携带文件元数据

* **变更类型**: 扩展 (Extension)

* **变更原因**: 当前检索结果仅返回文本内容，不含来源元数据，Agent 无法引用文档来源；DocumentChunk 实体不存储分块级元数据，前端无法展示分块来源信息。

* **发起日期**: 2026-07-30

* **开发方法**: TDD（测试驱动开发）- 每个任务按 Red-Green-Refactor 循环执行

* **关联功能**: RAG 知识库问答

* **关联文档**:

  * 需求文档: `specs/features/2026-07-24/RAG知识库问答/RAG知识库问答.md`

  * 技术方案: `specs/features/2026-07-24/RAG知识库问答/RAG知识库问答_技术方案.md`

  * 任务规划: `specs/features/2026-07-24/RAG知识库问答/RAG知识库问答_任务规划.md`

  * CR-001 变更: `specs/features/2026-07-24/RAG知识库问答/RAG知识库问答_变更任务_CR-001.md`

## 1. 影响分析 (Impact Analysis)

### 1.1 需求影响

| 影响项        | 变更类型 | 详情                                                             |
| :--------- | :--- | :------------------------------------------------------------- |
| BR-RAG-018 | 新增   | 分块数据携带来源元数据（fileName、format、pageNumber/headerText），检索结果中包含来源信息 |
| AC-031     | 新增   | 检索结果包含来源元数据（文件名、格式、页码/章节标题等），Agent 回答时可引用文档来源                  |
| AC-032     | 新增   | DocumentChunk 存储分块元数据，前端可展示每个分块的来源信息                           |

### 1.2 技术影响

| 影响层   | 影响范围     | 详情                                                                            |
| :---- | :------- | :---------------------------------------------------------------------------- |
| API 层 | 无影响      | 无新增/修改 REST 接口（检索工具是 @Tool，非 REST）                                            |
| 数据层   | 实体扩展     | DocumentChunk 新增 `metadata` 字段（Map\<String, String>）                          |
| 业务逻辑  | 修改 4 个组件 | DocumentSplitterRegistry、DocumentService、KnowledgeRetrieverTool、DocumentChunk |
| 依赖层   | 无影响      | 不引入新依赖                                                                        |

### 1.3 代码影响

| 文件路径                                                                           | 操作 | 影响说明                                                                     |
| :----------------------------------------------------------------------------- | :- | :----------------------------------------------------------------------- |
| `agent-demo-rag/src/main/java/.../entity/DocumentChunk.java`                   | 修改 | 新增 `metadata` 字段（Map\<String, String>）                                   |
| `agent-demo-splitter/src/main/java/.../splitter/DocumentSplitterRegistry.java` | 修改 | `split()` 方法新增 `fileName` 参数；`enrichMetadata()` 注入 `fileName`            |
| `agent-demo-rag/src/main/java/.../service/DocumentService.java`                | 修改 | 传入 `fileName` 到 `splitterRegistry.split()`；保存 DocumentChunk 时提取 metadata |
| `agent-demo-rag/src/main/java/.../retriever/KnowledgeRetrieverTool.java`       | 修改 | 检索结果中注入来源元数据文本（文件名、页码/章节）                                                |

### 1.4 测试影响

| 测试文件                                | 影响类型 | 说明                                    |
| :---------------------------------- | :--- | :------------------------------------ |
| `DocumentSplitterRegistryTest.java` | 需修改  | `split()` 方法签名变更，测试需适配新 `fileName` 参数 |
| `DocumentServiceTest.java`          | 需修改  | 验证 DocumentChunk 中 metadata 正确保存      |
| `KnowledgeRetrieverToolTest.java`   | 需修改  | 验证检索结果包含来源元数据                         |
| 其他测试文件                              | 无影响  | 不涉及变更范围                               |

### 1.5 回归风险评估

* **高风险区域**: DocumentSplitterRegistry.split() 方法签名变更，需更新所有调用方（目前仅 DocumentService）

* **中风险区域**: KnowledgeRetrieverTool 检索结果格式变化，可能影响 Agent 对检索结果的解析（但格式增强不破坏现有行为）

* **低风险区域**: DocumentChunk 新增字段，不影响现有数据（内存存储，重启清空）

* **已有测试覆盖**: DocumentServiceTest（12 个测试）、KnowledgeRetrieverToolTest（7 个测试）、DocumentSplitterRegistryTest 需回归验证

## 2. 需求变更详情 (Requirements Delta)

### 2.1 新增验收标准

#### 正常流程 (Happy Path)

* **AC-031**: 检索结果包含来源元数据

  * Given: 知识库中已上传多个文档（含 PDF 和 Markdown），文档处理已完成

  * When: Agent 调用知识库检索工具，检索到相关文档片段

  * Then: 每个检索结果片段包含来源信息（文件名、格式、页码或章节标题），Agent 回答时可引用文档来源

#### 业务规则 (Business Rules)

* **AC-032**: DocumentChunk 存储分块元数据

  * Given: 用户上传文档到知识库，文档异步处理完成

  * When: 系统保存分块信息到 DocumentChunkStore

  * Then: 每个 DocumentChunk 的 metadata 字段包含来源信息（fileName、format、pageNumber 或 headerText），前端查询分块列表时可展示来源

## 3. 技术变更详情 (Technical Delta)

### 3.1 组件变更

| 操作 | 组件                              | 说明                                                                                                                                                     |
| :- | :------------------------------ | :----------------------------------------------------------------------------------------------------------------------------------------------------- |
| 修改 | `DocumentChunk.java`            | 新增 `private Map<String, String> metadata` 字段                                                                                                           |
| 修改 | `DocumentSplitterRegistry.java` | `split()` 方法签名从 `(ParsedDocument, String, String)` 变为 `(ParsedDocument, String, String, String)`（新增 fileName）；`enrichMetadata()` 注入 `fileName`         |
| 修改 | `DocumentService.java`          | `processDocument()` 中调用 `splitterRegistry.split()` 时传入 `docInfo.getFileName()`；保存 DocumentChunk 时从 `segment.metadata()` 提取来源信息存入 `chunk.setMetadata()` |
| 修改 | `KnowledgeRetrieverTool.java`   | `searchKnowledge()` 中从 `match.embedded().metadata()` 提取 fileName/pageNumber/headerText，组装到结果文本前缀                                                       |

### 3.2 检索结果格式变化

**变更前**:

```
【片段1】
文档内容文本...

【片段2】
文档内容文本...
```

**变更后**:

```
【片段1】来源: 产品手册.pdf (pdf) 第3页
文档内容文本...

【片段2】来源: 架构设计.md (md) 章节"系统部署"
文档内容文本...
```

### 3.3 兼容性说明

* **向前兼容**: 检索结果文本格式增强（追加来源前缀），不破坏现有 Agent ReAct 循环对结果的解析

* **数据兼容**: DocumentChunk 新增 metadata 字段，现有数据不受影响（内存存储）

* **接口兼容**: split() 方法签名变更，但调用方仅 DocumentService 一处，同步修改即可

## 4. 增量开发任务 (Incremental Tasks)

> 任务编号从 CR-001 最后一个任务（Task-19）之后继续
> 每个任务按 TDD 循环执行：RED（写测试）-> GREEN（写实现）-> REFACTOR（重构）

### 阶段一：数据层与分割器变更 (Data & Splitter Layer Delta)

* [x] **Task-20**: DocumentChunk 新增 metadata 字段 + DocumentSplitterRegistry 注入 fileName

  * **通俗解释**: 做完这步后，每个分块在创建时就携带文件名等来源信息，分块实体也能存储这些元数据。

  * **说明**: DocumentChunk 新增 `Map<String, String> metadata` 字段；DocumentSplitterRegistry.split() 方法新增 `fileName` 参数，enrichMetadata() 将 fileName 注入 TextSegment metadata

  * **变更类型**: 修改

  * **涉及文件**:

    * `agent-demo-rag/src/main/java/com/agentdemo/rag/entity/DocumentChunk.java`

    * `agent-demo-splitter/src/main/java/com/agentdemo/splitter/splitter/DocumentSplitterRegistry.java`

  * **测试文件**:

    * `agent-demo-rag/src/test/java/com/agentdemo/rag/entity/EntityTest.java`（或新建 DocumentChunkTest）

    * `agent-demo-splitter/src/test/java/com/agentdemo/splitter/splitter/DocumentSplitterRegistryTest.java`

  * **对应AC**: AC-032（部分）

  * **预估工时**: 30m

  * **依赖**: 无

  * **验证标准**:

    * [ ] DocumentChunk 类包含 `Map<String, String> metadata` 字段

    * [ ] DocumentSplitterRegistry.split() 方法签名包含 `fileName` 参数

    * [ ] enrichMetadata() 将 `fileName` 注入 TextSegment metadata

    * [ ] `mvn compile -pl agent-demo-rag,agent-demo-splitter -am` 编译通过

    * [ ] 现有 DocumentSplitterRegistryTest 测试适配新签名后通过

### 阶段二：业务逻辑变更 (Business Logic Delta)

* [x] **Task-21**: DocumentService 传入 fileName + 保存 DocumentChunk 时提取 metadata

  * **通俗解释**: 做完这步后，文档处理流水线会将文件名传递给分割器，并在保存分块时把来源元数据（文件名、页码、章节等）存入 DocumentChunk。

  * **说明**: DocumentService.processDocument() 中调用 splitterRegistry.split() 时传入 docInfo.getFileName()；保存 DocumentChunk 时从 segment.metadata() 提取来源信息（fileName、format、pageNumber、headerText）存入 chunk.setMetadata()

  * **变更类型**: 修改

  * **涉及文件**: `agent-demo-rag/src/main/java/com/agentdemo/rag/service/DocumentService.java`

  * **测试文件**: `agent-demo-rag/src/test/java/com/agentdemo/rag/service/DocumentServiceTest.java`

  * **对应AC**: AC-032

  * **预估工时**: 40m

  * **依赖**: Task-20

  * **验证标准**:

    * [ ] processDocument() 调用 splitterRegistry.split() 时传入 fileName

    * [ ] 保存 DocumentChunk 时从 segment.metadata() 提取来源信息

    * [ ] DocumentChunk.metadata 包含 fileName、format 字段

    * [ ] PDF 文档的 DocumentChunk.metadata 包含 pageNumber

    * [ ] MD 文档的 DocumentChunk.metadata 包含 headerText

    * [ ] 现有 DocumentServiceTest 全部测试通过（无回归）

### 阶段三：检索层变更 (Retrieval Layer Delta)

* [x] **Task-22**: KnowledgeRetrieverTool 检索结果注入来源元数据 ⚠️

  * **通俗解释**: 做完这步后，Agent 检索知识库时，每个返回的文档片段都会标注来源（文件名、页码/章节），Agent 可以在回答中引用来源。

  * **说明**: KnowledgeRetrieverTool.searchKnowledge() 中从 match.embedded().metadata() 提取 fileName、format、pageNumber、headerText，组装"来源: {fileName} ({format}) {位置信息}"前缀注入结果文本

  * **变更类型**: 修改

  * **涉及文件**: `agent-demo-rag/src/main/java/com/agentdemo/rag/retriever/KnowledgeRetrieverTool.java`

  * **测试文件**: `agent-demo-rag/src/test/java/com/agentdemo/rag/retriever/KnowledgeRetrieverToolTest.java`

  * **对应AC**: AC-031

  * **预估工时**: 30m

  * **依赖**: Task-20（metadata 中需有 fileName）

  * **验证标准**:

    * [ ] 检索结果文本包含"来源:"前缀

    * [ ] 来源信息包含文件名

    * [ ] PDF 文档的来源信息包含页码

    * [ ] MD 文档的来源信息包含章节标题

    * [ ] 无 pageNumber/headerText 时不显示位置信息（仅显示文件名和格式）

    * [ ] 现有 KnowledgeRetrieverToolTest 全部测试通过（结果格式变化需适配断言）

### 阶段四：回归验证 (Regression Verification)

* [x] **Task-23**: 回归验证

  * **通俗解释**: 确认元数据增强没有破坏任何已有功能，所有原有测试仍然通过。

  * **说明**: 运行 RAG 模块和 splitter 模块全量测试套件，确保变更未破坏原有功能

  * **变更类型**: 验证

  * **涉及文件**: `agent-demo-rag/src/test/` + `agent-demo-splitter/src/test/` 目录下所有测试文件

  * **对应AC**: AC-031, AC-032 + 所有已有 AC 的回归验证

  * **预估工时**: 20m

  * **依赖**: Task-22

  * **验证标准**:

    * [ ] `mvn test -pl agent-demo-rag -am` 全量测试通过（无回归）

    * [ ] `mvn test -pl agent-demo-splitter -am` 全量测试通过（无回归）

    * [ ] `mvn compile -pl agent-demo-bootstrap -am` 全量编译通过

    * [ ] 本次变更的所有新增测试通过（AC-031/032 对应测试）

    * [ ] 现有 DocumentServiceTest 全部通过

    * [ ] 现有 KnowledgeRetrieverToolTest 全部通过（适配后）

    * [ ] 现有 DocumentSplitterRegistryTest 全部通过（适配后）

## 5. 增量验收标准检查清单 (Incremental AC Checklist)

| 验收标准ID | 验收标准描述                | 状态  | 对应任务             | 操作 |
| :----- | :-------------------- | :-- | :--------------- | :- |
| AC-031 | 检索结果包含来源元数据           | ✅ 已完成 | Task-22          | 新增 |
| AC-032 | DocumentChunk 存储分块元数据 | ✅ 已完成 | Task-20, Task-21 | 新增 |

## 6. 变更总结 (Change Summary)

* **总新增任务数**: 4 个（Task-20, Task-21, Task-22, Task-23）

* **预计总工时**: 120 分钟（约 2 小时）

* **风险等级**: 低

* **风险说明**: DocumentSplitterRegistry.split() 方法签名变更但调用方仅 DocumentService 一处；检索结果格式增强不破坏现有行为；DocumentChunk 新增字段不影响现有数据。

* **测试影响**: 需修改 3 个已有测试文件（DocumentSplitterRegistryTest、DocumentServiceTest、KnowledgeRetrieverToolTest），新增约 4-6 个测试用例

* **预期效果**: Agent 检索知识库时，每个返回的文档片段都标注来源（文件名、格式、页码/章节），Agent 可在回答中引用文档来源，提升回答可信度。前端查询分块列表时可展示每个分块的来源信息。

