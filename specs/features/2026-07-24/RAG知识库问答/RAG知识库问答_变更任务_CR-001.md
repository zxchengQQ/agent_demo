# 功能变更记录: RAG 知识库问答 - CR-001

## 0. 变更概览 (Change Overview)

*   **变更标题**: PDF 表格解析优化 - 引入 tabula-java 混合提取
*   **变更类型**: 扩展 (Extension)
*   **变更原因**: 当前 PDF 解析使用 PDFBox 的 PDFTextStripper 仅提取线性文本，无法识别表格结构，导致 PDF 中的表格信息丢失或混乱，Agent 无法回答表格相关问题。
*   **发起日期**: 2026-07-27
*   **开发方法**: TDD（测试驱动开发）- 每个任务按 Red-Green-Refactor 循环执行
*   **关联功能**: RAG 知识库问答
*   **关联文档**:
    -   需求文档: `specs/features/2026-07-24/RAG知识库问答/RAG知识库问答.md`
    -   技术方案: `specs/features/2026-07-24/RAG知识库问答/RAG知识库问答_技术方案.md`
    -   任务规划: `specs/features/2026-07-24/RAG知识库问答/RAG知识库问答_任务规划.md`

## 1. 影响分析 (Impact Analysis)

### 1.1 需求影响

| 影响项 | 变更类型 | 详情 |
| :--- | :--- | :--- |
| BR-RAG-013 | 新增 | PDF 文档解析支持表格结构提取，表格内容转换为 Markdown 格式保留行列关系，无表格区域回退纯文本提取 |
| AC-028 | 新增 | PDF 文件包含表格时解析为 Markdown 表格格式，保留行列结构 |
| AC-029 | 新增 | PDF 文件不包含表格时回退纯文本解析，行为与变更前一致 |
| AC-030 | 新增 | PDF 表格结构完整性 - 多行多列表格的单元格按行列顺序正确排列 |

### 1.2 技术影响

| 影响层 | 影响范围 | 详情 |
| :--- | :--- | :--- |
| 依赖层 | 新增依赖 | `technology.tabula:tabula:1.0.5`（基于 PDFBox，兼容 3.0.x） |
| API 层 | 无影响 | 无新增/修改 REST 接口 |
| 数据层 | 无影响 | 无新增字段/表 |
| 业务逻辑 | 修改逻辑 | `DocumentLoader.parsePdf()` 方法重构为混合提取（tabula-java 表格 + PDFBox 纯文本） |

### 1.3 代码影响

| 文件路径 | 操作 | 影响说明 |
| :--- | :--- | :--- |
| `agent-demo-bom/pom.xml` | 修改 | 新增 `tabula.version` 属性 + dependency 声明 |
| `agent-demo-rag/pom.xml` | 修改 | 新增 tabula 依赖引用 |
| `agent-demo-rag/src/main/java/com/agentdemo/rag/loader/DocumentLoader.java` | 修改 | `parsePdf()` 方法重构为混合提取；新增 `extractTablesAsMarkdown()` 方法 |

### 1.4 测试影响

| 测试文件 | 影响类型 | 说明 |
| :--- | :--- | :--- |
| `agent-demo-rag/src/test/java/com/agentdemo/rag/loader/DocumentLoaderTest.java` | 需修改 | 新增 PDF 表格提取测试用例；验证无表格 PDF 解析行为不变 |
| 其他测试文件 | 无影响 | 不涉及变更范围 |

### 1.5 回归风险评估

*   **高风险区域**: 无 - 仅影响 PDF 解析路径，txt/md 解析不变
*   **已有测试覆盖**: `DocumentLoaderTest` 已覆盖 PDF 正常解析（`loadPdfShouldReturnExtractedText`）和损坏 PDF 异常（`loadCorruptedPdfShouldThrow`），这些测试验证回归安全性
*   **需要补充的测试**: PDF 表格提取（验证 Markdown 表格输出）、无表格 PDF 回退纯文本、表格结构完整性验证

## 2. 需求变更详情 (Requirements Delta)

> 仅记录本次变更涉及的需求变化，已有需求不重复列出

### 2.2 新增/修改的验收标准

#### 正常流程 (Happy Path)

- **AC-028**: PDF 文件包含表格时解析为 Markdown 表格格式
    - Given: 用户上传一个包含表格的 PDF 文件到知识库
    - When: 后台异步处理解析该 PDF 文件
    - Then: 解析结果中包含 Markdown 格式的表格内容，表格的行列结构被保留，Agent 检索时可正确回答表格相关问题

#### 边界与异常 (Edge & Error Cases)

- **AC-029**: PDF 文件不包含表格时回退纯文本解析
    - Given: 用户上传一个不包含任何表格的纯文本 PDF 文件
    - When: 后台异步处理解析该 PDF 文件
    - Then: 解析结果为纯文本内容，不包含 Markdown 表格语法，行为与变更前一致

#### 业务规则 (Business Rules)

- **AC-030**: PDF 表格结构完整性
    - Given: 用户上传一个包含多行多列表格的 PDF 文件
    - When: 后台异步处理解析该 PDF 文件
    - Then: 解析结果中表格的每行单元格数量与原表格一致，单元格内容按行列顺序正确排列，表头与数据行的对应关系被保留

## 3. 技术变更详情 (Technical Delta)

> 仅记录本次变更涉及的技术变化

### 3.2 API 变更

无 API 变更。本次变更仅影响 `DocumentLoader` 内部实现，不改变任何公开接口签名。

### 3.3 组件变更

| 操作 | 组件 | 说明 |
| :--- | :--- | :--- |
| 修改 | `DocumentLoader.java` | `parsePdf()` 方法重构为混合提取策略；新增 `extractTablesAsMarkdown()` 私有方法 |
| 修改 | `agent-demo-bom/pom.xml` | 新增 `tabula.version` 属性和 dependency 声明 |
| 修改 | `agent-demo-rag/pom.xml` | 新增 tabula 依赖引用 |

### 3.4 兼容性说明

*   **向前兼容**: 完全兼容。无表格的 PDF 解析行为不变（回退纯文本提取）；所有现有 REST API 接口不变；数据模型不变。
*   **迁移方案**: 无需迁移。引入新依赖后重新编译即可。

### 3.5 核心算法说明

**PDF 混合提取流程（CR-001 新增）**:

```
输入: PDF 文件字节数组
  │
  ├── 1. tabula-java 表格提取
  │     ├── ObjectExtractor 遍历每页
  │     ├── SpreadsheetExtractionAlgorithm 检测表格区域
  │     ├── 提取表格为 List<Table>（行列结构）
  │     └── 转换为 Markdown 表格格式字符串
  │
  ├── 2. PDFBox 纯文本提取
  │     └── PDFTextStripper 提取全文文本（含表格区域的线性文本）
  │
  └── 3. 结果合并
        ├── tableText 为空（无表格）-> 返回 plainText
        └── tableText 非空（有表格）-> 返回 plainText + Markdown 表格内容
```

## 4. 增量开发任务 (Incremental Tasks)

> 任务编号从原任务规划最后一个编号（Task-16）之后继续
> 每个任务按 TDD 循环执行：RED（写测试）-> GREEN（写实现）-> REFACTOR（重构）

### 阶段一：依赖层变更 (Dependency Layer Delta)

- [x] **Task-17**: 依赖配置（BOM + RAG pom.xml 新增 tabula-java）
    *   **通俗解释**: 做完这步后，项目就引入了 tabula-java 这个专门用来提取 PDF 表格的工具包，后续代码可以直接使用它来识别和提取表格。
    *   **说明**: BOM 新增 tabula 版本管理；agent-demo-rag/pom.xml 新增 tabula 依赖引用
    *   **变更类型**: 修改
    *   **涉及文件**: `agent-demo-bom/pom.xml`, `agent-demo-rag/pom.xml`
    *   **测试文件**: 无（配置验证通过编译即可）
    *   **参考**: 技术方案 Sec 10.1, 10.2
    *   **对应AC**: 无（基础设施）
    *   **预估工时**: 20m
    *   **依赖**: 无
    *   **验证标准**:
        - [ ] BOM 中 `tabula.version` 属性已声明，值为 `1.0.5`
        - [ ] BOM 中 tabula dependency 声明已添加
        - [ ] agent-demo-rag/pom.xml 包含 tabula 依赖声明（不指定版本，由 BOM 管理）
        - [ ] `mvn compile -pl agent-demo-rag -am` 编译通过
        - [ ] tabula-java 的 `technology.tabula.*` 类可被 RAG 模块代码引用

### 阶段二：业务逻辑变更 (Business Logic Delta)

- [x] **Task-18**: DocumentLoader PDF 表格解析重构 ⚠️
    *   **通俗解释**: 做完这步后，系统能识别 PDF 中的表格并保留行列结构，表格内容会被转成 Markdown 格式，而非表格区域仍用原来的方式提取纯文本。
    *   **说明**: 修改 `DocumentLoader.parsePdf()` 方法为混合提取策略；新增 `extractTablesAsMarkdown()` 私有方法使用 tabula-java 提取表格并转为 Markdown 格式；合并表格 Markdown 和纯文本结果
    *   **变更类型**: 修改
    *   **涉及文件**: `agent-demo-rag/src/main/java/com/agentdemo/rag/loader/DocumentLoader.java`
    *   **测试文件**: `agent-demo-rag/src/test/java/com/agentdemo/rag/loader/DocumentLoaderTest.java`
    *   **参考**: 技术方案 Sec 4.2（CR-001 更新后的 parsePdf 设计）, Sec 3.5（核心算法说明）
    *   **对应AC**: AC-028, AC-029, AC-030
    *   **预估工时**: 90m
    *   **依赖**: Task-17
    *   **风险标注**: ⚠️ tabula-java API 使用（ObjectExtractor / SpreadsheetExtractionAlgorithm / Table / RectangularTextContainer），需准备含表格的测试 PDF
    *   **验证标准**（TDD RED 阶段的测试依据）:
        - [ ] **AC-028**: `load(含表格的PDF, "pdf")` 返回的文本中包含 Markdown 表格语法（`|` 分隔符和 `---` 表头分隔行）
        - [ ] **AC-028**: 表格区域的单元格内容在解析结果中按行列正确排列
        - [ ] **AC-029**: `load(无表格的PDF, "pdf")` 返回纯文本，不包含 Markdown 表格语法，与变更前行为一致
        - [ ] **AC-029**: 现有测试 `loadPdfShouldReturnExtractedText` 仍然通过（无表格 PDF 回归验证）
        - [ ] **AC-030**: 含多行多列表格的 PDF 解析后，每行单元格数量一致，表头与数据行对应关系正确
        - [ ] 现有测试 `loadCorruptedPdfShouldThrow` 仍然通过（损坏 PDF 异常处理回归验证）
        - [ ] tabula-java 未检测到表格时，`extractTablesAsMarkdown()` 返回空字符串
        - [ ] `mvn compile -pl agent-demo-rag -am` 编译通过

### 阶段三：回归验证 (Regression Verification)

- [x] **Task-19**: 回归验证
    *   **通俗解释**: 做完这步后，确认 PDF 表格解析优化没有破坏任何已有功能，所有原有测试仍然通过。
    *   **说明**: 运行 RAG 模块全量测试套件，确保变更未破坏原有功能。重点验证 txt/md 解析不受影响、无表格 PDF 解析行为不变、损坏 PDF 异常处理正常。
    *   **变更类型**: 验证
    *   **涉及文件**: `agent-demo-rag/src/test/` 目录下所有测试文件
    *   **对应AC**: AC-028, AC-029, AC-030 + 所有已有 AC 的回归验证
    *   **预估工时**: 30m
    *   **依赖**: Task-18
    *   **验证标准**:
        - [ ] `mvn test -pl agent-demo-rag -am` 全量测试通过（无回归）
        - [ ] `mvn compile -pl agent-demo-bootstrap -am` 全量编译通过
        - [ ] 本次变更的所有新增测试通过（AC-028/029/030 对应测试）
        - [ ] 现有 PDF 解析测试通过（`loadPdfShouldReturnExtractedText`、`loadCorruptedPdfShouldThrow`）
        - [ ] 现有 txt/md 解析测试通过（`loadTxtShouldReturnTextContent`、`loadMdShouldReturnOriginalMarkdown`）
        - [ ] 现有格式/大小校验测试通过（`loadUnsupportedFormatShouldThrow`、`loadOversizedFileShouldThrow`、`loadBoundarySizeShouldSucceed`、`loadNullFormatShouldThrow`、`loadUpperCaseFormatShouldSucceed`）
        - [ ] 测试覆盖率未下降

## 5. 增量验收标准检查清单 (Incremental AC Checklist)

> 仅包含本次变更涉及的验收标准

| 验收标准ID | 验收标准描述 | 状态 | 对应任务 | 操作 |
| :--- | :--- | :--- | :--- | :--- |
| AC-028 | PDF 文件包含表格时解析为 Markdown 表格格式 | ✅ 已完成 | Task-18 | 新增 |
| AC-029 | PDF 文件不包含表格时回退纯文本解析 | ✅ 已完成 | Task-18 | 新增 |
| AC-030 | PDF 表格结构完整性 | ✅ 已完成 | Task-18 | 新增 |

## 6. 变更总结 (Change Summary)

*   **总新增任务数**: 3 个（Task-17, Task-18, Task-19）
*   **预计总工时**: 140 分钟（约 2.3 小时）
*   **风险等级**: 低
*   **风险说明**: 仅影响 PDF 解析路径，txt/md 解析完全不变；tabula-java 基于 PDFBox 构建，依赖兼容性有保障；现有测试覆盖 PDF 正常解析和异常场景，回归安全性高。
*   **测试影响**: 需修改 1 个已有测试文件（`DocumentLoaderTest.java`），新增约 3-4 个测试用例
*   **预期效果**: PDF 文档中的表格内容将被正确提取为 Markdown 格式，保留行列结构，Agent 可基于检索到的表格内容准确回答用户的表格相关问题。无表格的 PDF 文档解析行为不受影响。
