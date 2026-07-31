# RAG 知识库问答 - CR-002 完成报告

## 功能信息
- **功能名称**: RAG 知识库问答
- **变更编号**: CR-002（分块数据携带文件元数据）
- **执行日期**: 2026-07-30
- **执行方式**: TDD（Red-Green-Refactor）

## 变更概述

当前检索结果仅返回文本内容，不含来源元数据，Agent 无法引用文档来源。本次变更为分块数据添加来源元数据（fileName、format、pageNumber/headerText），检索结果中包含来源信息，Agent 回答时可引用文档来源。同时 DocumentChunk 实体新增 metadata 字段，存储分块级来源信息。

## 已完成任务清单

| 任务 | 标题 | 状态 | 测试数 |
| :--- | :--- | :--- | :--- |
| Task-20 | DocumentChunk 新增 metadata 字段 + DocumentSplitterRegistry 注入 fileName | ✅ | 9 (splitter) |
| Task-21 | DocumentService 传入 fileName + 保存 DocumentChunk 时提取 metadata | ✅ | 15 (含 1 新增) |
| Task-22 | KnowledgeRetrieverTool 检索结果注入来源元数据 | ✅ | 10 (含 3 新增) |
| Task-23 | 回归验证 | ✅ | 141 全量 |

## TDD 循环记录

### Task-20: DocumentChunk 新增 metadata + DocumentSplitterRegistry 注入 fileName

#### RED 阶段
- 更新 `DocumentSplitterRegistryTest` 全部 8 个现有测试适配 4 参数 `split()` 签名
- 新增测试 `splitWithNullFileNameShouldNotInjectFileNameMetadata`：验证 fileName 为 null 时不注入 metadata
- 所有测试因 `split()` 方法签名不匹配而编译失败（RED 确认）

#### GREEN 阶段
- `DocumentChunk.java`：新增 `private Map<String, String> metadata` 字段
- `DocumentSplitterRegistry.java`：`split()` 方法签名新增 `String fileName` 参数；`enrichMetadata()` 注入 `fileName` 到 TextSegment metadata
- `DocumentService.java`：从 `documentStore.findById()` 获取 fileName，传入 `splitterRegistry.split()`
- `DocumentServiceTest.java`：Mock 的 `split()` 调用适配新签名

#### 结果
- Splitter 模块 9 个测试通过
- RAG 模块 DocumentServiceTest 14 个测试通过

### Task-21: DocumentService 保存 DocumentChunk 时提取 metadata

#### RED 阶段
- 新增测试 `processDocumentShouldSaveChunksWithMetadata`：验证 DocumentChunk.metadata 包含 fileName、format、pageNumber
- 测试失败：`chunk.getMetadata()` 为 null（RED 确认）

#### GREEN 阶段
- 修改 `DocumentService.processDocument()` 中保存 DocumentChunk 的逻辑
- 从 `segment.metadata()` 提取已知来源字段（fileName、format、pageNumber、headerText、headerLevel）
- 使用 `containsKey()` + `getString()` 逐个提取（LangChain4j Metadata API 无 `asMap()` 方法）

#### 遇到的问题
- **问题**: 初次实现使用 `metadata.asMap()` 遍历所有 key，编译失败
- **原因**: LangChain4j 1.x 的 `Metadata` 类没有 `asMap()` 和 `get(String)` 方法
- **解决**: 改为使用 `containsKey()` + `getString()` 逐个提取已知来源字段

#### 结果
- DocumentServiceTest 15 个测试通过（14 现有 + 1 新增）

### Task-22: KnowledgeRetrieverTool 检索结果注入来源元数据

#### RED 阶段
- 更新 `searchKnowledgeNormalShouldReturnFragments` 测试：TextSegment 添加 metadata，验证结果包含"来源:"前缀
- 新增测试 `searchKnowledgeWithPdfMetadataShouldIncludePageNumber`：PDF 检索结果包含页码
- 新增测试 `searchKnowledgeWithMdMetadataShouldIncludeHeader`：MD 检索结果包含章节标题
- 新增测试 `searchKnowledgeWithoutMetadataShouldNotIncludeSource`：无元数据时不包含来源前缀
- 3 个测试失败（结果不含"来源:"前缀）（RED 确认）

#### GREEN 阶段
- 修改 `KnowledgeRetrieverTool.searchKnowledge()` 结果组装逻辑
- 新增 `buildSourcePrefix()` 私有方法：从 TextSegment.metadata 提取 fileName、format、pageNumber、headerText
- 格式：`来源: {fileName} ({format}) 第{pageNumber}页` 或 `来源: {fileName} ({format}) 章节"{headerText}"`
- 无 fileName 时不注入来源前缀（向后兼容）

#### 结果
- KnowledgeRetrieverToolTest 10 个测试通过（7 现有 + 3 新增）

### Task-23: 回归验证

| 验证项 | 命令 | 结果 |
| :--- | :--- | :--- |
| RAG + Splitter 全量测试 | `mvn test -pl agent-demo-rag,agent-demo-splitter -am` | 141 tests, 0 failures |
| Bootstrap 全量编译 | `mvn compile -pl agent-demo-bootstrap -am` | BUILD SUCCESS |

## 文件变更清单

| 文件 | 操作 | 变更说明 |
| :--- | :--- | :--- |
| `agent-demo-rag/.../entity/DocumentChunk.java` | 修改 | 新增 `Map<String, String> metadata` 字段 |
| `agent-demo-splitter/.../splitter/DocumentSplitterRegistry.java` | 修改 | `split()` 新增 fileName 参数；`enrichMetadata()` 注入 fileName |
| `agent-demo-rag/.../service/DocumentService.java` | 修改 | 传入 fileName 到 split()；保存 DocumentChunk 时提取 metadata |
| `agent-demo-rag/.../retriever/KnowledgeRetrieverTool.java` | 修改 | 新增 `buildSourcePrefix()` 方法，检索结果注入来源元数据 |
| `agent-demo-splitter/.../DocumentSplitterRegistryTest.java` | 修改 | 适配 4 参数签名 + 新增 null fileName 边界测试 |
| `agent-demo-rag/.../DocumentServiceTest.java` | 修改 | 新增 metadata 提取验证测试 |
| `agent-demo-rag/.../KnowledgeRetrieverToolTest.java` | 修改 | 新增 3 个来源元数据验证测试 |
| `specs/features/.../RAG知识库问答.md` | 修改 | AC-031/032 标记为已完成 |
| `specs/features/.../RAG知识库问答_变更任务_CR-002.md` | 修改 | Task-20/21/22/23 标记为已完成，AC 清单更新 |

## 验收标准检查结果

| AC ID | 描述 | 状态 | 对应测试 |
| :--- | :--- | :--- | :--- |
| AC-031 | 检索结果包含来源元数据 | ✅ 已通过 | `searchKnowledgeWithPdfMetadataShouldIncludePageNumber`, `searchKnowledgeWithMdMetadataShouldIncludeHeader`, `searchKnowledgeWithoutMetadataShouldNotIncludeSource` |
| AC-032 | DocumentChunk 存储分块元数据 | ✅ 已通过 | `processDocumentShouldSaveChunksWithMetadata`, `splitWithNullFileNameShouldNotInjectFileNameMetadata` |

## 遇到的问题和解决方案

### 问题 1: LangChain4j Metadata API 差异
- **现象**: 使用 `metadata.asMap()` 遍历所有 key 时编译失败
- **原因**: LangChain4j 1.x 的 `Metadata` 类没有 `asMap()` 和 `get(String)` 方法，只有 `containsKey()` 和 `getString()` 等类型安全方法
- **解决**: 改为使用已知来源字段数组 `{"fileName", "format", "pageNumber", "headerText", "headerLevel"}` 逐个 `containsKey()` + `getString()` 提取

## 总结

CR-002 变更已全部完成。分块数据现在携带来源元数据（fileName、format、pageNumber/headerText），检索结果中每个片段标注来源信息（如"来源: 产品手册.pdf (pdf) 第3页"），Agent 可在回答中引用文档来源。DocumentChunk 实体新增 metadata 字段存储分块级来源信息。所有 2 个新增 AC 通过验证，141 个全量测试无回归。
