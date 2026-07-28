# 功能变更记录: RAG 知识库前端 - CR-001

## 0. 变更概览 (Change Overview)
*   **变更标题**: 文档分块详情查看功能
*   **变更类型**: 扩展 (Extension)
*   **变更原因**: 用户需要在知识库页面查看文档切分后的分块信息，以便了解文档如何被分块和向量化，便于调试检索效果和优化分块参数
*   **发起日期**: 2026-07-28
*   **开发方法**: TDD（测试驱动开发）- 每个任务按 Red-Green-Refactor 循环执行
*   **关联功能**: RAG 知识库前端
*   **关联文档**:
    -   需求文档: `specs/features/2026-07-27/RAG知识库前端/RAG知识库前端.md`
    -   技术方案: `specs/features/2026-07-27/RAG知识库前端/RAG知识库前端_技术方案.md`
    -   任务规划: `specs/features/2026-07-27/RAG知识库前端/RAG知识库前端_任务规划.md`

## 1. 影响分析 (Impact Analysis)

### 1.1 需求影响
| 影响项 | 变更类型 | 详情 |
| :--- | :--- | :--- |
| AC-038 | 新增 | 查看文档分块列表（抽屉面板展示分块索引/内容/字符数） |
| AC-039 | 新增 | 分块内容展开/收起查看 |
| AC-040 | 新增 | 非已完成文档不可查看分块（按钮不显示） |
| AC-041 | 新增 | 无分块数据的空状态提示 |
| AC-042 | 新增 | 分块查询接口异常 Toast 提示 |

### 1.2 技术影响
| 影响层 | 影响范围 | 详情 |
| :--- | :--- | :--- |
| 数据层 | 新增实体+Store | DocumentChunk 实体 + DocumentChunkStore 接口 + InMemoryDocumentChunkStore 实现 |
| API 层 | 新增接口 | GET /api/rag/documents/{docId}/chunks 返回分块列表 |
| 业务逻辑 | 修改 | DocumentService.processDocument() 新增阶段 5.5 保存分块；deleteDocument() 级联删除分块 |
| 表现层 | 新增组件+修改组件 | DocumentChunkDrawer.vue 抽屉面板（新增）；DocumentList.vue 添加查看按钮（修改） |

### 1.3 代码影响
| 文件路径 | 操作 | 影响说明 |
| :--- | :--- | :--- |
| `agent-demo-rag/.../entity/DocumentChunk.java` | 新增 | 分块实体（id/documentId/chunkIndex/content/charCount） |
| `agent-demo-rag/.../store/DocumentChunkStore.java` | 新增 | 分块存储接口（saveChunks/getChunks/deleteChunks） |
| `agent-demo-rag/.../store/InMemoryDocumentChunkStore.java` | 新增 | 内存实现（ConcurrentHashMap） |
| `agent-demo-web/.../dto/DocumentChunkResponse.java` | 新增 | 分块响应 DTO（chunkIndex/content/charCount） |
| `src/components/DocumentChunkDrawer.vue` | 新增 | 抽屉面板组件（分块列表+展开收起） |
| `agent-demo-rag/.../service/DocumentService.java` | 修改 | processDocument 新增保存分块；deleteDocument 级联删除分块 |
| `agent-demo-web/.../controller/RagController.java` | 修改 | 新增 GET /documents/{id}/chunks 端点 |
| `src/types/index.ts` | 修改 | 新增 DocumentChunk 类型 |
| `src/api/rag.ts` | 修改 | 新增 getDocumentChunks 函数 |
| `src/stores/rag.ts` | 修改 | 新增 currentChunks state + loadDocumentChunks action |
| `src/components/DocumentList.vue` | 修改 | 新增"查看分块"按钮 + 集成 DocumentChunkDrawer |

### 1.4 测试影响
| 测试文件 | 影响类型 | 说明 |
| :--- | :--- | :--- |
| `src/components/document-list.test.ts` | 需修改 | 新增"查看分块"按钮测试、状态判断测试 |
| `src/components/document-chunk-drawer.test.ts` | 需新增 | 抽屉组件测试（加载/展开/收起/空状态/错误处理） |
| `src/api/rag.test.ts` | 需修改 | 新增 getDocumentChunks 测试 |
| `src/stores/rag.test.ts` | 需修改 | 新增 loadDocumentChunks 测试 |

### 1.5 回归风险评估
*   **高风险区域**: DocumentService.processDocument() 修改可能影响文档处理流程
*   **中风险区域**: DocumentList.vue 修改可能影响文档列表展示
*   **已有测试覆盖**: DocumentList 已有 10 个测试覆盖列表展示/轮询/删除等功能
*   **需要补充的测试**: 分块保存/查询/删除的单元测试，抽屉组件的渲染/交互测试

## 2. 需求变更详情 (Requirements Delta)

### 2.1 新增的用户故事
- **US-004**: 作为 **学习者**，我想要 **查看文档切分后的分块详情**，以便 **了解文档是如何被分块和向量化的，便于调试检索效果和优化分块参数**
    - 关联验收标准：AC-038, AC-039, AC-040, AC-041, AC-042

### 2.2 新增的验收标准

#### 正常流程 (Happy Path)
- **AC-038**: 查看文档分块列表
    - Given: 文档列表中存在一个状态为"已完成"的文档（分块数为 10）
    - When: 用户点击该文档的"查看分块"按钮
    - Then: 右侧滑出抽屉面板，展示该文档的 10 个分块列表，每个分块显示索引编号（如"分块 1/10"）、文本内容（截断显示）、字符数

- **AC-039**: 分块内容展开查看
    - Given: 抽屉面板已打开，某分块的文本内容被截断显示
    - When: 用户点击该分块的"展开"按钮
    - Then: 展开显示完整分块文本内容；再次点击"收起"恢复截断显示

#### 边界与异常 (Edge & Error Cases)
- **AC-040**: 非已完成文档不可查看分块
    - Given: 文档列表中存在状态为"待处理"、"处理中"或"失败"的文档
    - When: 页面渲染文档列表
    - Then: 这些文档不显示"查看分块"按钮（或按钮禁用），仅"已完成"状态的文档可点击查看分块

- **AC-041**: 无分块数据的空状态
    - Given: 文档状态为"已完成"但分块数为 0
    - When: 用户点击"查看分块"
    - Then: 抽屉面板打开，展示空状态提示"该文档无分块数据"

- **AC-042**: 分块查询接口异常
    - Given: 用户点击"查看分块"按钮
    - When: 后端接口返回错误或网络异常
    - Then: Toast 提示错误信息（展示后端错误消息或"网络异常，请稍后重试"），不打开抽屉面板

## 3. 技术变更详情 (Technical Delta)

### 3.1 数据库变更
> 本项目无传统关系数据库，采用纯内存存储。本次新增内存数据结构。

**新增数据结构**：`ConcurrentHashMap<String, List<DocumentChunk>>` — 按文档 ID 映射分块列表。

### 3.2 API 变更
| 操作 | 方法 | 路径 | 说明 |
| :--- | :--- | :--- | :--- |
| 新增 | GET | `/api/rag/documents/{documentId}/chunks` | 返回文档的分块详情列表（DocumentChunkResponse 数组） |

### 3.3 组件变更
| 操作 | 组件 | 说明 |
| :--- | :--- | :--- |
| 新增 | `DocumentChunkDrawer.vue` | 右侧滑出抽屉面板，展示分块列表，支持展开/收起 |
| 修改 | `DocumentList.vue` | 每个已完成文档行新增"查看分块"按钮，集成抽屉组件 |

### 3.4 兼容性说明
*   **向前兼容**: 新增的 API 和组件不影响已有功能。DocumentService.processDocument() 仅在原有阶段 5 和 6 之间追加保存逻辑，不改变原有处理流程。deleteDocument() 仅追加一行级联删除调用。
*   **迁移方案**: 无需迁移，已有文档的分块数据在重新上传处理后才可查看（历史已处理文档无分块数据）。

## 4. 增量开发任务 (Incremental Tasks)
> 任务编号从原任务规划最后一个编号（Task-15）之后继续
> 每个任务按 TDD 循环执行：RED（写测试）-> GREEN（写实现）-> REFACTOR（重构）

### 阶段一：后端数据层 (Backend Data Layer)

- [x] **Task-16**: 新增 DocumentChunk 实体 + DocumentChunkStore
    *   **通俗解释**: 做完这步后，后端有了存储文档分块信息的"仓库"，能按文档 ID 存取分块数据。
    *   **说明**: 新建 DocumentChunk 实体（id/documentId/chunkIndex/content/charCount）；新建 DocumentChunkStore 接口（saveChunks/getChunks/deleteChunks）；新建 InMemoryDocumentChunkStore 实现（ConcurrentHashMap）
    *   **变更类型**: 新增
    *   **涉及文件**: `agent-demo-rag/.../entity/DocumentChunk.java`、`agent-demo-rag/.../store/DocumentChunkStore.java`、`agent-demo-rag/.../store/InMemoryDocumentChunkStore.java`
    *   **测试文件**: 无（后端编译验证为主，Store 逻辑简单）
    *   **参考**: 技术方案 Sec 2.3、现有 InMemoryDocumentStore 实现
    *   **对应AC**: AC-038（数据结构基础）
    *   **预估工时**: 40m
    *   **依赖**: 无
    *   **验证标准**:
        - [ ] `mvn compile -pl agent-demo-rag -am` 编译通过
        - [ ] DocumentChunk 包含 id/documentId/chunkIndex/content/charCount 五个字段
        - [ ] DocumentChunkStore 接口包含 saveChunks/getChunks/deleteChunks 三个方法
        - [ ] InMemoryDocumentChunkStore 使用 ConcurrentHashMap 实现
        - [ ] saveChunks 后 getChunks 返回相同的分块列表
        - [ ] deleteChunks 后 getChunks 返回空列表

### 阶段二：后端业务逻辑 (Backend Service Layer)

- [x] **Task-17**: DocumentService 修改 - 保存分块 + 级联删除
    *   **通俗解释**: 做完这步后，文档处理完成时会自动保存分块信息，删除文档时也会自动清理分块数据。
    *   **说明**: 在 DocumentService.processDocument() 阶段 5 和 6 之间新增阶段 5.5（将 segments 转为 DocumentChunk 列表并保存到 DocumentChunkStore）；在 deleteDocument() 中新增 documentChunkStore.deleteChunks() 调用
    *   **变更类型**: 修改
    *   **涉及文件**: `agent-demo-rag/.../service/DocumentService.java`
    *   **测试文件**: 无（后端编译 + 集成验证为主）
    *   **参考**: 技术方案 Sec 2.3 DocumentService 修改
    *   **对应AC**: AC-038（分块数据持久化）
    *   **预估工时**: 50m
    *   **依赖**: Task-16
    *   **验证标准**:
        - [ ] `mvn compile -pl agent-demo-rag -am` 编译通过
        - [ ] processDocument 在向量入库后、标记完成前保存分块信息
        - [ ] 保存的 chunkIndex 从 0 开始按原文档顺序递增
        - [ ] 保存的 content 为 TextSegment 的文本内容
        - [ ] 保存的 charCount 为文本内容长度
        - [ ] deleteDocument 调用 documentChunkStore.deleteChunks()
        - [ ] 原有文档处理流程不受影响（零回归）

### 阶段三：后端 API 层 (Backend API Layer)

- [x] **Task-18**: RagController 新增分块查询端点 + DocumentChunkResponse DTO
    *   **通俗解释**: 做完这步后，前端可以通过 API 获取文档的分块详情列表了。
    *   **说明**: 新建 DocumentChunkResponse DTO（chunkIndex/content/charCount）；在 RagController 新增 GET /documents/{documentId}/chunks 端点，从 DocumentChunkStore 查询并转为 DTO 返回
    *   **变更类型**: 新增
    *   **涉及文件**: `agent-demo-web/.../dto/DocumentChunkResponse.java`、`agent-demo-web/.../controller/RagController.java`
    *   **测试文件**: 无（后端编译 + Swagger 验证为主）
    *   **参考**: 技术方案 Sec 2.3 RagController 新增端点
    *   **对应AC**: AC-038, AC-042
    *   **预估工时**: 40m
    *   **依赖**: Task-16
    *   **验证标准**:
        - [ ] `mvn compile -pl agent-demo-web -am` 编译通过
        - [ ] GET /api/rag/documents/{docId}/chunks 返回 Result<List<DocumentChunkResponse>>
        - [ ] 文档不存在时返回 5306 错误码
        - [ ] 文档无分块数据时返回空数组
        - [ ] Swagger UI 中可见新端点

### 阶段四：前端数据层 (Frontend Data Layer)

- [x] **Task-19**: 前端类型 + API 封装 + Store action
    *   **通俗解释**: 做完这步后，前端有了分块数据的类型定义、API 调用能力和状态管理。
    *   **说明**: 在 types/index.ts 新增 DocumentChunk 类型；在 api/rag.ts 新增 getDocumentChunks 函数；在 stores/rag.ts 新增 currentChunks state + loadDocumentChunks action
    *   **变更类型**: 新增
    *   **涉及文件**: `src/types/index.ts`、`src/api/rag.ts`、`src/stores/rag.ts`
    *   **测试文件**: `src/api/rag.test.ts`、`src/stores/rag.test.ts`
    *   **参考**: 技术方案 Sec 2.2 getDocumentChunks、现有 rag store 模式
    *   **对应AC**: AC-038（数据基础）
    *   **预估工时**: 50m
    *   **依赖**: Task-18（API 需后端就绪才能联调，但前端可先 Mock）
    *   **验证标准**:
        - [ ] DocumentChunk 类型包含 chunkIndex/content/charCount 三个字段
        - [ ] getDocumentChunks('doc-123') 发送 GET /api/rag/documents/doc-123/chunks
        - [ ] loadDocumentChunks('doc-123') 调用后 currentChunks state 填充
        - [ ] API 返回错误时抛出 Error（AC-042）
        - [ ] vue-tsc --noEmit 类型检查通过

### 阶段五：前端表现层 (Frontend Presentation Layer)

- [x] **Task-20**: 新增 DocumentChunkDrawer 组件
    *   **通俗解释**: 做完这步后，有了右侧滑出的抽屉面板组件，能展示分块列表并支持展开/收起。
    *   **说明**: 新建 DocumentChunkDrawer.vue，包含：抽屉容器（右侧滑出，点击外部关闭）、分块列表（索引/截断内容/字符数）、展开/收起按钮、加载状态、空状态、错误处理
    *   **变更类型**: 新增
    *   **涉及文件**: `src/components/DocumentChunkDrawer.vue`
    *   **测试文件**: `src/components/document-chunk-drawer.test.ts`
    *   **参考**: 技术方案 Sec 4.8
    *   **对应AC**: AC-038, AC-039, AC-041, AC-042
    *   **预估工时**: 90m
    *   **依赖**: Task-19
    *   **验证标准**:
        - [ ] visible 为 true 时抽屉滑出展示
        - [ ] 分块列表每项显示"分块 N/M"、截断文本（超 200 字符截断）、字符数
        - [ ] 点击"展开"显示完整文本，点击"收起"恢复截断（AC-039）
        - [ ] 分块列表为空时显示"该文档无分块数据"（AC-041）
        - [ ] 加载中显示 loading 动画
        - [ ] 加载失败 Toast 提示并关闭抽屉（AC-042）
        - [ ] 点击关闭按钮或抽屉外部区域关闭抽屉

- [x] **Task-21**: DocumentList 集成"查看分块"按钮
    *   **通俗解释**: 做完这步后，文档列表中已完成的文档旁边多了一个"查看分块"按钮，点击弹出抽屉面板。
    *   **说明**: 修改 DocumentList.vue，为状态为 COMPLETED 的文档行添加"查看分块"按钮；集成 DocumentChunkDrawer 组件；点击按钮时设置 documentId 并打开抽屉
    *   **变更类型**: 修改
    *   **涉及文件**: `src/components/DocumentList.vue`
    *   **测试文件**: `src/components/document-list.test.ts`
    *   **参考**: 技术方案 Sec 4.8 DocumentList 集成
    *   **对应AC**: AC-038, AC-040
    *   **预估工时**: 50m
    *   **依赖**: Task-20
    *   **验证标准**:
        - [ ] 状态为 COMPLETED 的文档行显示"查看分块"按钮
        - [ ] 状态为 PENDING/PROCESSING/FAILED 的文档行不显示按钮（AC-040）
        - [ ] 点击"查看分块"按钮后 DocumentChunkDrawer 的 visible 变为 true
        - [ ] 点击按钮时正确传递 documentId 和 chunkCount
        - [ ] 原有文档列表功能不受影响（零回归）

### 阶段六：回归验证 (Regression Verification)

- [x] **Task-22**: 回归验证
    *   **说明**: 运行全量已有测试套件，确保变更未破坏原有功能。注意：每个增量任务内部已通过 TDD 循环完成了自身的测试编写，此处重点是验证跨模块的回归安全性。
    *   **变更类型**: 验证
    *   **涉及文件**: 所有测试文件
    *   **对应AC**: 所有受影响的 AC（AC-038~042 + 原有 AC-001~037）
    *   **预估工时**: 60m
    *   **依赖**: 上述所有增量任务（Task-16 ~ Task-21）
    *   **验证标准**:
        - [ ] 前端 `npm run test` 全量通过（原有 235 个 + 新增测试）
        - [ ] `vue-tsc --noEmit` 类型检查无错误
        - [ ] 后端 `mvn compile -pl agent-demo-web -am` 编译通过
        - [ ] 端到端验证：上传文档 -> 等待处理完成 -> 点击"查看分块" -> 抽屉展示分块列表
        - [ ] 端到端验证：分块展开/收起功能正常
        - [ ] 端到端验证：非已完成文档不显示查看按钮
        - [ ] 现有功能无回归（知识库 CRUD、文档上传/轮询/删除、对话知识库选择器）

## 5. 增量验收标准检查清单 (Incremental AC Checklist)

| 验收标准ID | 验收标准描述 | 状态 | 对应任务 | 操作 |
| :--- | :--- | :--- | :--- | :--- |
| AC-038 | 查看文档分块列表 | ✅ 已完成 | Task-16, 17, 18, 19, 20, 21 | 新增 |
| AC-039 | 分块内容展开查看 | ✅ 已完成 | Task-20 | 新增 |
| AC-040 | 非已完成文档不可查看分块 | ✅ 已完成 | Task-21 | 新增 |
| AC-041 | 无分块数据的空状态 | ✅ 已完成 | Task-20 | 新增 |
| AC-042 | 分块查询接口异常 | ✅ 已完成 | Task-19, 20 | 新增 |

## 6. 变更总结 (Change Summary)
*   **总新增任务数**: 7 个（Task-16 ~ Task-22）
*   **预计总工时**: 380 分钟（约 6.3 小时）
*   **风险等级**: 中
*   **风险说明**: DocumentService.processDocument() 是文档处理核心流程，修改时需确保仅在阶段 5 和 6 之间追加，不影响原有向量化/入库/状态更新逻辑。已处理的历史文档无分块数据（需重新上传才能查看）。
*   **测试影响**: 需修改 3 个已有测试文件（document-list.test.ts、rag.test.ts、rag store.test.ts），新增 1 个测试文件（document-chunk-drawer.test.ts）
*   **预期效果**: 用户可在知识库页面点击"查看分块"按钮，通过抽屉面板查看文档切分后的每个分块的索引、文本内容和字符数，支持展开查看完整内容，便于调试检索效果和优化分块参数
