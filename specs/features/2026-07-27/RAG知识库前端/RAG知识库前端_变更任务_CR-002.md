# 功能变更记录: RAG 知识库前端 - CR-002

## 0. 变更概览 (Change Overview)
*   **变更标题**: 对话知识库来源展示功能
*   **变更类型**: 扩展 (Extension)
*   **变更原因**: 用户希望在对话使用知识库后，能看到回答基于哪些知识库和文档片段生成，以便验证回答的准确性和可信度
*   **发起日期**: 2026-07-30
*   **开发方法**: TDD（测试驱动开发）- 每个任务按 Red-Green-Refactor 循环执行
*   **关联功能**: RAG 知识库前端
*   **关联文档**:
    -   需求文档: `specs/features/2026-07-27/RAG知识库前端/RAG知识库前端.md`
    -   技术方案: `specs/features/2026-07-27/RAG知识库前端/RAG知识库前端_技术方案.md`
    -   任务规划: `specs/features/2026-07-27/RAG知识库前端/RAG知识库前端_任务规划.md`
    -   前序变更: `specs/features/2026-07-27/RAG知识库前端/RAG知识库前端_变更任务_CR-001.md`

## 1. 影响分析 (Impact Analysis)

### 1.1 需求影响
| 影响项 | 变更类型 | 详情 |
| :--- | :--- | :--- |
| US-005 | 新增 | 对话用户查看知识库来源信息 |
| AC-043 | 新增 | 助手消息底部显示折叠引用条 |
| AC-044 | 新增 | 展开引用来源详情 |
| AC-045 | 新增 | 未使用知识库时不显示引用条 |
| BR-RAG-FE-011 | 新增 | 引用条可折叠/展开，未使用时不显示 |

### 1.2 技术影响
| 影响层 | 影响范围 | 详情 |
| :--- | :--- | :--- |
| 后端 | 修改 | KnowledgeRetrieverTool.buildSourcePrefix 新增 knowledgeBaseName 参数，来源格式变为 `{知识库名}/{文件名}` |
| 前端类型 | 新增 | KnowledgeSource 接口、Message.knowledgeSources 字段、StreamCallbacks.onSources 回调 |
| 前端 API | 修改 | chat.ts observation 事件分支新增正则解析来源信息 |
| 表现层 | 新增组件+修改组件 | KnowledgeSourceBar.vue 引用条组件（新增）；MessageItem.vue 集成引用条（修改） |

### 1.3 代码影响
| 文件路径 | 操作 | 影响说明 |
| :--- | :--- | :--- |
| `agent-demo-rag/.../retriever/KnowledgeRetrieverTool.java` | 修改 | buildSourcePrefix 新增 knowledgeBaseName 参数，调用点透传 |
| `src/types/index.ts` | 修改 | 新增 KnowledgeSource 接口；Message 新增 knowledgeSources 字段；StreamCallbacks 新增 onSources 回调 |
| `src/api/chat.ts` | 修改 | observation 事件分支新增来源解析逻辑 |
| `src/components/KnowledgeSourceBar.vue` | 新增 | 折叠/展开引用来源条组件 |
| `src/components/MessageItem.vue` | 修改 | 气泡底部集成 KnowledgeSourceBar 组件 |
| `src/components/ChatWindow.vue` | 修改 | 注册 onSources 回调，将来源信息写入消息对象 |

### 1.4 测试影响
| 测试文件 | 影响类型 | 说明 |
| :--- | :--- | :--- |
| `src/components/knowledge-source-bar.test.ts` | 需新增 | 引用条组件测试（折叠/展开/空状态） |
| `src/api/chat.test.ts` | 需修改 | 新增 observation 事件来源解析测试 |
| `src/components/components.test.ts` | 需修改 | 新增 MessageItem 集成 KnowledgeSourceBar 测试 |

### 1.5 回归风险评估
*   **低风险区域**: KnowledgeRetrieverTool.buildSourcePrefix 修改仅影响来源前缀格式，不影响检索逻辑
*   **低风险区域**: chat.ts observation 事件解析为追加逻辑，不影响原有 onObservation 回调
*   **中风险区域**: MessageItem.vue 修改需确保引用条不影响现有气泡布局
*   **已有测试覆盖**: MessageItem 已有测试覆盖消息渲染，chat.ts 已有 SSE 解析测试
*   **需要补充的测试**: KnowledgeSourceBar 组件渲染/交互测试，observation 事件来源解析测试

## 2. 需求变更详情 (Requirements Delta)

### 2.1 新增的用户故事
- **US-005**: 作为 **对话用户**，我想要 **在收到助手回答后看到使用的知识库来源信息**，以便 **了解回答基于哪些文档片段生成，验证回答的准确性和可信度**
    - 关联验收标准：AC-043, AC-044, AC-045

### 2.2 新增的验收标准

#### 正常流程 (Happy Path)
- **AC-043**: 助手消息底部显示折叠引用条
    - Given: Agent 在对话中调用了知识库检索工具并返回了检索结果
    - When: 流式回答完成
    - Then: 助手消息底部显示折叠的"引用来源"条，标题显示引用数量（如"引用来源 (3)"），列出本次回答使用的知识库名和文档文件名

- **AC-044**: 展开引用来源详情
    - Given: 引用来源条已显示在助手消息底部
    - When: 用户点击引用条展开
    - Then: 展开显示具体来源列表，每条来源显示 `{知识库名} / {文件名}`；再次点击可收起

#### 边界与异常 (Edge & Error Cases)
- **AC-045**: 未使用知识库时不显示引用条
    - Given: Agent 在对话中未调用知识库检索工具，或调用了但检索无结果
    - When: 流式回答完成
    - Then: 助手消息底部不显示"引用来源"条

## 3. 技术变更详情 (Technical Delta)

### 3.1 后端变更
**KnowledgeRetrieverTool.buildSourcePrefix 方法修改**：
- 方法签名从 `buildSourcePrefix(TextSegment segment)` 变为 `buildSourcePrefix(String knowledgeBaseName, TextSegment segment)`
- 来源前缀格式从 `来源: {fileName}` 变为 `来源: {knowledgeBaseName}/{fileName}`
- searchKnowledge 方法内的调用点透传 knowledgeBaseName 参数

### 3.2 前端类型变更
**新增接口**：
```typescript
export interface KnowledgeSource {
    knowledgeBaseName: string;
    fileName: string;
}
```

**Message 接口扩展**：
```typescript
knowledgeSources?: KnowledgeSource[];
```

**StreamCallbacks 接口扩展**：
```typescript
onSources?: (sources: KnowledgeSource[]) => void;
```

### 3.3 SSE 解析变更
在 observation 事件分支追加来源解析逻辑：
- 正则 `/来源: ([^\/\n]+)\/([^\s（(]+)/g` 提取来源信息
- 解析成功后通过 `callbacks.onSources?.(sources)` 通知调用方

### 3.4 组件变更
| 操作 | 组件 | 说明 |
| :--- | :--- | :--- |
| 新增 | `KnowledgeSourceBar.vue` | 折叠/展开引用来源条，接收 sources 数组 |
| 修改 | `MessageItem.vue` | 气泡底部集成 KnowledgeSourceBar，v-if 条件渲染 |
| 修改 | `ChatWindow.vue` | 注册 onSources 回调，将来源信息累积到当前消息 |

### 3.5 兼容性说明
*   **后端向前兼容**: buildSourcePrefix 仅修改来源前缀格式（新增知识库名前缀），不影响检索逻辑，LLM 自然兼容。
*   **前端向前兼容**: Message.knowledgeSources 和 StreamCallbacks.onSources 均为可选字段，旧消息/旧调用方不受影响。
*   **observation 事件向前兼容**: 来源解析为追加逻辑，不影响原有 onObservation 回调的执行。

## 4. 增量开发任务 (Incremental Tasks)
> 任务编号从 CR-001 最后一个任务（Task-22）之后继续
> 每个任务按 TDD 循环执行：RED（写测试）-> GREEN（写实现）-> REFACTOR（重构）

### 阶段一：后端修改 (Backend Layer)

- [ ] **Task-23**: KnowledgeRetrieverTool 修改 buildSourcePrefix
    *   **通俗解释**: 做完这步后，知识库检索结果中每个片段的来源信息会包含知识库名称，格式变为 `来源: {知识库名}/{文件名}`。
    *   **说明**: 修改 buildSourcePrefix 方法签名，新增 knowledgeBaseName 参数；来源前缀从 `来源: {fileName}` 变为 `来源: {knowledgeBaseName}/{fileName}`；修改 searchKnowledge 方法内调用点，透传 knowledgeBaseName
    *   **变更类型**: 修改
    *   **涉及文件**: `agent-demo-rag/.../retriever/KnowledgeRetrieverTool.java`
    *   **测试文件**: 无（后端编译验证为主，逻辑简单）
    *   **参考**: 技术方案 Sec 2.5
    *   **对应AC**: AC-043（来源信息包含知识库名）
    *   **预估工时**: 30m
    *   **依赖**: 无
    *   **验证标准**:
        - [ ] `mvn compile -pl agent-demo-rag -am` 编译通过
        - [ ] buildSourcePrefix 方法签名为 `(String knowledgeBaseName, TextSegment segment)`
        - [ ] 来源前缀格式为 `来源: {knowledgeBaseName}/{fileName}`
        - [ ] searchKnowledge 内调用 buildSourcePrefix 时透传 knowledgeBaseName
        - [ ] 无 fileName 元数据时仍返回 null（零回归）

### 阶段二：前端数据层 (Frontend Data Layer)

- [ ] **Task-24**: 前端类型定义 + SSE observation 来源解析
    *   **通俗解释**: 做完这步后，前端能从 SSE observation 事件中解析出知识库来源信息（知识库名 + 文件名），并通过回调通知调用方。
    *   **说明**: 在 types/index.ts 新增 KnowledgeSource 接口；Message 新增 knowledgeSources 可选字段；StreamCallbacks 新增 onSources 可选回调；在 chat.ts 的 observation 事件分支追加正则解析逻辑
    *   **变更类型**: 新增 + 修改
    *   **涉及文件**: `src/types/index.ts`、`src/api/chat.ts`
    *   **测试文件**: `src/api/chat.test.ts`
    *   **参考**: 技术方案 Sec 2.6、Sec 2.7
    *   **对应AC**: AC-043（来源解析基础）、AC-045（无来源时不触发回调）
    *   **预估工时**: 60m
    *   **依赖**: Task-23（后端来源格式就绪才能联调，前端可先 Mock）
    *   **验证标准**:
        - [ ] KnowledgeSource 接口包含 knowledgeBaseName 和 fileName 两个 string 字段
        - [ ] Message 接口新增 knowledgeSources?: KnowledgeSource[] 可选字段
        - [ ] StreamCallbacks 新增 onSources?: (sources: KnowledgeSource[]) => void 可选回调
        - [ ] observation 事件解析正则 `/来源: ([^\/\n]+)\/([^\s（(]+)/g` 正确提取来源
        - [ ] 解析到来源时调用 callbacks.onSources?.(sources)
        - [ ] 未解析到来源时不调用 onSources（AC-045）
        - [ ] JSON 解析失败时静默跳过（容错，零回归）
        - [ ] vue-tsc --noEmit 类型检查通过

### 阶段三：前端表现层 (Frontend Presentation Layer)

- [ ] **Task-25**: 新增 KnowledgeSourceBar 组件
    *   **通俗解释**: 做完这步后，有了可折叠/展开的"引用来源"条组件，能展示来源列表。
    *   **说明**: 新建 KnowledgeSourceBar.vue，包含：折叠标题（"引用来源 (N)"）、展开/收起切换、来源列表（每条显示 `{知识库名} / {文件名}`）；样式与 thinking-block / react-block 折叠区块一致
    *   **变更类型**: 新增
    *   **涉及文件**: `src/components/KnowledgeSourceBar.vue`
    *   **测试文件**: `src/components/knowledge-source-bar.test.ts`
    *   **参考**: 技术方案 Sec 4.9
    *   **对应AC**: AC-043, AC-044
    *   **预估工时**: 60m
    *   **依赖**: Task-24（需要 KnowledgeSource 类型）
    *   **验证标准**:
        - [ ] 接收 sources: KnowledgeSource[] prop
        - [ ] 默认折叠，显示"引用来源 (N)"标题
        - [ ] 点击标题展开，显示来源列表（每条 `{知识库名} / {文件名}`）（AC-044）
        - [ ] 再次点击收起
        - [ ] sources 为空数组时不渲染（AC-045 辅助）
        - [ ] 样式与现有折叠区块（thinking-block）风格一致

- [ ] **Task-26**: MessageItem 集成 KnowledgeSourceBar + ChatWindow 注册 onSources
    *   **通俗解释**: 做完这步后，对话中 Agent 使用知识库回答时，助手消息底部会显示折叠的引用来源条。
    *   **说明**: 在 MessageItem.vue 气泡底部（stream-cursor 之后）集成 KnowledgeSourceBar，v-if 条件渲染（role === 'assistant' && knowledgeSources 非空）；在 ChatWindow.vue 注册 onSources 回调，将来源信息累积写入当前助手消息的 knowledgeSources 字段
    *   **变更类型**: 修改
    *   **涉及文件**: `src/components/MessageItem.vue`、`src/components/ChatWindow.vue`
    *   **测试文件**: `src/components/components.test.ts`
    *   **参考**: 技术方案 Sec 4.9 MessageItem 集成
    *   **对应AC**: AC-043, AC-045
    *   **预估工时**: 50m
    *   **依赖**: Task-24, Task-25
    *   **验证标准**:
        - [ ] MessageItem 气泡底部渲染 KnowledgeSourceBar（AC-043）
        - [ ] knowledgeSources 为空/undefined 时不渲染引用条（AC-045）
        - [ ] ChatWindow 注册 onSources 回调，来源信息写入 message.knowledgeSources
        - [ ] 多次 observation 事件来源信息累积（不覆盖）
        - [ ] 现有消息渲染功能不受影响（零回归）

### 阶段四：回归验证 (Regression Verification)

- [ ] **Task-27**: 回归验证
    *   **说明**: 运行全量已有测试套件，确保变更未破坏原有功能。注意：每个增量任务内部已通过 TDD 循环完成了自身的测试编写，此处重点是验证跨模块的回归安全性。
    *   **变更类型**: 验证
    *   **涉及文件**: 所有测试文件
    *   **对应AC**: 所有受影响的 AC（AC-043~045 + 原有 AC-001~042）
    *   **预估工时**: 40m
    *   **依赖**: 上述所有增量任务（Task-23 ~ Task-26）
    *   **验证标准**:
        - [ ] 前端 `npm run test` 全量通过（原有测试 + 新增测试）
        - [ ] `vue-tsc --noEmit` 类型检查无错误
        - [ ] 后端 `mvn compile -pl agent-demo-rag -am` 编译通过
        - [ ] 端到端验证：选择知识库 -> 发送消息 -> Agent 检索 -> 助手消息底部显示引用来源条
        - [ ] 端到端验证：引用条展开/收起功能正常
        - [ ] 端到端验证：不选择知识库（自动模式）且 Agent 未检索时无引用条
        - [ ] 现有功能无回归（对话流式、ReAct 推理、任务拆解、知识库管理）

## 5. 增量验收标准检查清单 (Incremental AC Checklist)

| 验收标准ID | 验收标准描述 | 状态 | 对应任务 | 操作 |
| :--- | :--- | :--- | :--- | :--- |
| AC-043 | 助手消息底部显示折叠引用条 | 待实现 | Task-23, 24, 25, 26 | 新增 |
| AC-044 | 展开引用来源详情 | 待实现 | Task-25 | 新增 |
| AC-045 | 未使用知识库时不显示引用条 | 待实现 | Task-24, 26 | 新增 |

## 6. 变更总结 (Change Summary)
*   **总新增任务数**: 5 个（Task-23 ~ Task-27）
*   **预计总工时**: 240 分钟（约 4 小时）
*   **风险等级**: 低
*   **风险说明**: KnowledgeRetrieverTool.buildSourcePrefix 修改仅影响来源前缀格式，不影响检索逻辑。前端 observation 解析为追加逻辑，不影响原有回调。MessageItem 集成需注意不影响气泡布局，但引用条在气泡外部（status-hint 同级），布局风险低。
*   **测试影响**: 需新增 1 个测试文件（knowledge-source-bar.test.ts），修改 2 个已有测试文件（chat.test.ts、components.test.ts）
*   **预期效果**: 用户在对话中使用知识库后，助手消息底部显示折叠的"引用来源"条，点击可展开查看具体的知识库名和文件名列表，验证回答的准确性和可信度。未使用知识库时不显示引用条。
