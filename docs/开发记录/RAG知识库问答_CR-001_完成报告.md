# RAG 知识库问答 - CR-001 完成报告

## 功能信息
- **功能名称**: RAG 知识库问答
- **变更编号**: CR-001（PDF 表格解析优化）
- **执行日期**: 2026-07-27
- **执行方式**: TDD（Red-Green-Refactor）

## 变更概述

当前 PDF 解析使用 PDFBox 的 `PDFTextStripper` 仅提取线性文本，无法识别表格结构，导致 PDF 中的表格信息丢失或混乱。本次变更引入 tabula-java 库，实现混合提取策略：tabula-java 提取表格结构（转为 Markdown 格式）+ PDFBox 提取纯文本，两者结果合并。

## 已完成任务清单

| 任务 | 标题 | 状态 | 测试数 |
| :--- | :--- | :--- | :--- |
| Task-17 | 依赖配置（BOM + RAG pom.xml 新增 tabula-java） | ✅ | 编译验证 |
| Task-18 | DocumentLoader PDF 表格解析重构 | ✅ | 3 新增 + 9 回归 |
| Task-19 | 回归验证 | ✅ | 54 全量 |

## TDD 循环记录

### Task-17: 依赖配置（无 TDD 循环，配置任务）

**验证方式**: Maven 编译验证

**修改文件**:
- `agent-demo-bom/pom.xml`: 新增 `<tabula.version>1.0.5</tabula.version>` 属性 + dependency 声明
- `agent-demo-rag/pom.xml`: 新增 tabula 依赖引用（版本由 BOM 管理）

**验证结果**: `mvn compile -pl agent-demo-rag -am` 编译通过

### Task-18: DocumentLoader PDF 表格解析重构

#### RED 阶段（编写失败的测试）

新增 3 个测试用例到 `DocumentLoaderTest.java`:

| 测试方法 | 对应 AC | 验证内容 |
| :--- | :--- | :--- |
| `loadPdfWithTableShouldReturnMarkdownTable` | AC-028 | 含表格 PDF 解析结果包含 Markdown 表格语法（\| 分隔符 + --- 表头分隔行） |
| `loadPdfWithoutTableShouldNotContainMarkdownTable` | AC-029 | 无表格 PDF 解析结果不包含 Markdown 表格语法 |
| `loadPdfWithTableShouldPreserveRowColumnStructure` | AC-030 | PDF 表格行列结构完整性（每行单元格数量一致，表头数据对应正确） |

同时新增 `createPdfWithTable()` 辅助方法，使用 PDFBox 创建包含网格线表格的测试 PDF。

**RED 结果**: 12 个测试中 2 个失败（AC-028 和 AC-030），10 个通过。失败原因是当前实现不输出 Markdown 表格语法。

#### GREEN 阶段（编写实现代码）

修改 `DocumentLoader.java`:

1. **`parsePdf()` 方法重构**: 从纯 `PDFTextStripper` 提取改为混合提取策略
   - 步骤 1: 调用 `extractTablesAsMarkdown()` 使用 tabula-java 检测并提取表格
   - 步骤 2: 使用 `PDFTextStripper` 提取纯文本
   - 步骤 3: 合并结果（有表格时追加 Markdown 表格内容，无表格时返回纯文本）

2. **新增 `extractTablesAsMarkdown()` 方法**: 
   - 使用 `ObjectExtractor` 遍历 PDF 每页
   - 使用 `SpreadsheetExtractionAlgorithm` 检测表格区域
   - 将表格转换为 Markdown 格式（`|` 分隔 + `---` 表头分隔行）
   - 单元格中的 `|` 字符转义为 `\|`

**GREEN 结果**: 全部 12 个测试通过

#### REFACTOR 阶段（重构优化）

重构内容:
1. 添加 `ObjectExtractor` 异常容错（catch Exception，log.warn，回退空字符串）
2. 添加表格检测 debug 日志（`log.debug("PDF 表格检测: 第 {} 页发现 {} 个表格")`）

**遇到的问题**: 初次重构使用 try-with-resources 管理 `ObjectExtractor`，但 `ObjectExtractor.close()` 会关闭底层 `PDDocument`，导致后续 `PDFTextStripper.getText()` 失败。现有测试 `loadPdfShouldReturnExtractedText` 回归失败。

**修复方案**: 移除 `ObjectExtractor` 的 try-with-resources，改用普通 try-catch 块。`PDDocument` 生命周期由 `parsePdf()` 的外层 try-with-resources 管理。

**REFACTOR 结果**: 全部 12 个测试通过

### Task-19: 回归验证

**验证命令与结果**:

| 验证项 | 命令 | 结果 |
| :--- | :--- | :--- |
| RAG 模块全量测试 | `mvn test -pl agent-demo-rag -am` | 54 tests, 0 failures, BUILD SUCCESS |
| Bootstrap 全量编译 | `mvn compile -pl agent-demo-bootstrap -am` | BUILD SUCCESS |

**回归测试明细**:

| 测试类 | 测试数 | 状态 |
| :--- | :--- | :--- |
| EntityTest | 3 | ✅ |
| DocumentLoaderTest | 12 | ✅ (含 3 个新增) |
| KnowledgeRetrieverToolTest | 7 | ✅ |
| DocumentServiceTest | 12 | ✅ |
| KnowledgeBaseServiceTest | 6 | ✅ |
| EmbeddingStoreFactoryTest | 2 | ✅ |
| InMemoryDocumentStoreTest | 6 | ✅ |
| InMemoryKnowledgeBaseStoreTest | 6 | ✅ |
| **合计** | **54** | **全部通过** |

## 文件变更清单

| 文件 | 操作 | 变更说明 |
| :--- | :--- | :--- |
| `agent-demo-bom/pom.xml` | 修改 | 新增 `tabula.version` 属性 + dependency 声明 |
| `agent-demo-rag/pom.xml` | 修改 | 新增 tabula 依赖引用 |
| `agent-demo-rag/src/main/java/.../loader/DocumentLoader.java` | 修改 | `parsePdf()` 重构为混合提取；新增 `extractTablesAsMarkdown()` 方法 |
| `agent-demo-rag/src/test/java/.../loader/DocumentLoaderTest.java` | 修改 | 新增 3 个测试方法 + `createPdfWithTable()` 辅助方法 |
| `specs/features/.../RAG知识库问答.md` | 修改 | AC-028/029/030 标记为已完成 |
| `specs/features/.../RAG知识库问答_变更任务_CR-001.md` | 修改 | Task-17/18/19 标记为已完成，AC 清单更新 |

## 验收标准检查结果

| AC ID | 描述 | 状态 | 对应测试 |
| :--- | :--- | :--- | :--- |
| AC-028 | PDF 文件包含表格时解析为 Markdown 表格格式 | ✅ 已通过 | `loadPdfWithTableShouldReturnMarkdownTable` |
| AC-029 | PDF 文件不包含表格时回退纯文本解析 | ✅ 已通过 | `loadPdfWithoutTableShouldNotContainMarkdownTable` + `loadPdfShouldReturnExtractedText` |
| AC-030 | PDF 表格结构完整性 | ✅ 已通过 | `loadPdfWithTableShouldPreserveRowColumnStructure` |

## 遇到的问题和解决方案

### 问题 1: tabula-java 未检测到表格
- **现象**: 初次 GREEN 阶段，`SpreadsheetExtractionAlgorithm` 未检测到测试 PDF 中的表格
- **原因**: `SpreadsheetExtractionAlgorithm` 依赖表格边框线（ruling lines）来检测表格，而原始测试 PDF 只有文本没有网格线
- **解决**: 在 `createPdfWithTable()` 中使用 `PDPageContentStream.moveTo()/lineTo()/stroke()` 绘制表格网格线

### 问题 2: ObjectExtractor 关闭 PDDocument
- **现象**: REFACTOR 阶段使用 try-with-resources 管理 `ObjectExtractor` 后，`loadPdfShouldReturnExtractedText` 测试失败
- **原因**: `ObjectExtractor.close()` 会关闭其包装的 `PDDocument`，导致 `parsePdf()` 中后续的 `PDFTextStripper.getText()` 无法使用已关闭的文档
- **解决**: 移除 `ObjectExtractor` 的 try-with-resources，改用普通 try-catch 块。`PDDocument` 生命周期由 `parsePdf()` 的外层 try-with-resources 统一管理

## 总结

CR-001 变更已全部完成。PDF 表格解析优化实现了混合提取策略（tabula-java 表格 + PDFBox 纯文本），表格内容转为 Markdown 格式保留行列结构，无表格 PDF 自动回退纯文本提取。所有 3 个新增 AC 通过验证，54 个全量测试无回归。
