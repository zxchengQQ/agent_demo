# 功能变更记录: 文档分割模块化与Token展示 - CR-002

## 0. 变更概览 (Change Overview)
*   **变更标题**: PDF 图片提取与向量化检索
*   **变更类型**: 扩展 (Extension)
*   **变更原因**: PDF 文档进行切割时需要支持文档中存在图片，且需要将图片的信息进行向量化支持检索
*   **发起日期**: 2026-08-03
*   **开发方法**: TDD（测试驱动开发）— 每个任务按 Red-Green-Refactor 循环执行
*   **关联功能**: 文档分割模块化与Token展示
*   **关联文档**:
    -   需求文档: `specs/features/2026-07-29_文档分割模块化与Token展示/文档分割模块化与Token展示.md`
    -   技术方案: `specs/features/2026-07-29_文档分割模块化与Token展示/文档分割模块化与Token展示_技术方案.md`
    -   任务规划: `specs/features/2026-07-29_文档分割模块化与Token展示/文档分割模块化与Token展示_任务规划.md`

## 1. 影响分析 (Impact Analysis)

### 1.1 需求影响
| 影响项 | 变更类型 | 详情 |
| :--- | :--- | :--- |
| AC-021 | 新增 | PDF 文档中的图片被提取并保存到文件系统 |
| AC-022 | 新增 | 图片通过视觉模型生成文本描述 |
| AC-023 | 新增 | 图片描述向量化并作为独立分块支持检索 |
| AC-024 | 新增 | 视觉模型调用失败时跳过图片不影响文档处理 |
| US-007 | 新增 | PDF 中的图片信息可被检索 |

### 1.2 技术影响
| 影响层 | 影响范围 | 详情 |
| :--- | :--- | :--- |
| 数据层 | 新增数据结构 | ImageInfo（path/pageNumber/index）；ParsedDocument 可能新增 images 字段 |
| API 层 | 无影响 | 不新增 REST API，图片处理在异步流程内完成 |
| 表现层 | 无影响 | 当前不新增前端图片展示组件（检索结果含图片描述文本即可） |
| 业务逻辑 | 新增逻辑 | 图片提取（ImageExtractor）→ 描述生成（ImageDescriptor）→ 向量化（复用 batchEmbed） |
| 配置层 | 新增配置 | rag.splitter.pdf.extract-images/image-dpi、ark.vision-model、rag.document.image-dir |
| 模型工厂 | 新增方法 | ModelFactory.getVisionChatModel() |

### 1.3 代码影响
| 文件路径 | 操作 | 影响说明 |
| :--- | :--- | :--- |
| `agent-demo-splitter/.../splitter/image/ImageInfo.java` | 新增 | 图片信息数据结构 |
| `agent-demo-splitter/.../splitter/image/ImageExtractor.java` | 新增 | PDF 图片提取器（PDFBox PDFRender） |
| `agent-demo-splitter/.../splitter/image/ImageDescriptor.java` | 新增 | 图片描述生成器（视觉 ChatModel） |
| `agent-demo-splitter/.../loader/DocumentLoader.java` | 修改 | 新增 extractImages 入口方法 |
| `agent-demo-rag/.../service/DocumentService.java` | 修改 | processDocument 新增图片处理分支 |
| `agent-demo-llm/.../factory/ModelFactory.java` | 修改 | 新增 getVisionChatModel 方法 |
| `agent-demo-llm/.../config/ArkProperties.java` | 修改 | 新增 visionModel 配置字段 |
| `agent-demo-bootstrap/.../application.yml` | 修改 | 新增图片提取和视觉模型配置 |

### 1.4 测试影响
| 测试文件 | 影响类型 | 说明 |
| :--- | :--- | :--- |
| `ImageExtractorTest.java` | 需新增 | 图片提取测试（含 PDF 渲染、文件保存、异常处理） |
| `ImageDescriptorTest.java` | 需新增 | 图片描述生成测试（含 Mock 视觉模型、失败跳过） |
| `DocumentLoaderTest.java` | 需修改 | 新增 extractImages 方法的测试 |
| `DocumentServiceTest.java` | 需修改 | 新增图片处理流程的集成测试 |
| `PdfDocumentSplitterTest.java` | 无影响 | 图片处理在 DocumentService 层，不影响分割器 |
| `ModelFactoryTest.java` | 需修改 | 新增 getVisionChatModel 测试 |

### 1.5 回归风险评估
*   **高风险区域**: DocumentService.processDocument（异步处理流水线主流程）
*   **已有测试覆盖**: DocumentServiceTest 已覆盖文本处理流程，需扩展图片分支
*   **需要补充的测试**: 图片提取、描述生成、视觉模型失败跳过、图片描述向量化
*   **回归安全性**: 图片处理为新增分支，仅 PDF 且 extract-images=true 时触发，不影响 TXT/MD 和无图片 PDF

## 2. 需求变更详情 (Requirements Delta)

### 2.1 新增的用户故事
- **US-007**: 作为 学习者，我希望 PDF 文档中的图片信息也能被检索到，以便 通过文字描述找到包含相关图片的文档内容，而不只检索到纯文本。
    - 关联验收标准：AC-021, AC-022, AC-023

### 2.2 新增的验收标准

#### 正常流程 (Happy Path)
- **AC-021**: PDF 文档中的图片被提取并保存
    - Given: 用户上传一个包含图片的多页 PDF 文件
    - When: 系统解析该 PDF 文件
    - Then: PDF 中的图片被提取并保存为 PNG 文件到 `${rag.document.temp-dir}/images/{documentId}/` 目录，每张图片的元数据记录所属页码和图片索引

- **AC-022**: 图片通过视觉模型生成文本描述
    - Given: 系统已提取 PDF 中的图片
    - When: 系统对每张图片调用视觉模型生成描述
    - Then: 视觉模型返回图片的文本描述，描述内容涵盖图片中的可见信息；视觉模型调用失败时跳过该图片

- **AC-023**: 图片描述向量化并作为独立分块支持检索
    - Given: 系统已获取图片的文本描述
    - When: 系统执行向量化处理
    - Then: 图片描述作为独立 TextSegment 向量化并入向量存储，metadata 标记 chunkType=image 并含 imagePath/imageDescription/pageNumber；检索时可通过描述命中并返回图片路径引用

#### 边界与异常 (Edge & Error Cases)
- **AC-024**: 视觉模型调用失败时跳过图片不影响文档处理
    - Given: PDF 文档包含图片，但视觉模型 API 不可用或调用超时
    - When: 系统处理该 PDF 文档
    - Then: 图片提取和文本分块正常完成，视觉模型失败的图片被跳过并记录 WARN 日志，文档状态为已完成

## 3. 技术变更详情 (Technical Delta)

### 3.1 数据结构变更
```java
// 新增 ImageInfo 数据结构
@Data @Builder
public class ImageInfo {
    private String imagePath;    // 图片文件存储路径
    private int pageNumber;      // 所属页码
    private int imageIndex;      // 页面内索引
}
```

### 3.2 API 变更
无新增 REST API。图片处理在 DocumentService.processDocument 异步流程内完成。

### 3.3 组件变更
| 操作 | 组件 | 说明 |
| :--- | :--- | :--- |
| 新增 | `ImageExtractor.java` | PDF 图片提取器，PDFBox PDFRender 渲染页面为 PNG |
| 新增 | `ImageDescriptor.java` | 图片描述生成器，调用视觉 ChatModel |
| 新增 | `ImageInfo.java` | 图片信息数据结构 |
| 修改 | `DocumentLoader.java` | 新增 extractImages 入口方法 |
| 修改 | `DocumentService.java` | processDocument 新增图片处理分支 |
| 修改 | `ModelFactory.java` | 新增 getVisionChatModel 方法 |
| 修改 | `ArkProperties.java` | 新增 visionModel 配置字段 |
| 修改 | `application.yml` | 新增图片提取和视觉模型配置 |

### 3.4 配置变更
```yaml
rag:
  splitter:
    pdf:
      extract-images: true       # 是否提取 PDF 图片
      image-dpi: 144              # 图片渲染 DPI
  document:
    image-dir: ${rag.document.temp-dir}/images  # 图片存储目录
ark:
  vision-model: doubao-vision-pro  # 视觉模型名称
```

### 3.5 兼容性说明
*   **向前兼容**: 图片处理为新增分支，extract-images=true 时才触发；现有无图片 PDF 和 TXT/MD 文档行为不变
*   **迁移方案**: 无需迁移，新上传的 PDF 自动支持图片提取

## 4. 增量开发任务 (Incremental Tasks)
> 任务编号从 Task-27 开始（CR-001 到 Task-26）
> 每个任务按 TDD 循环执行：RED（写测试）→ GREEN（写实现）→ REFACTOR（重构）

### 阶段一：数据结构与配置 (Data & Config Layer)

- [ ] **Task-27**: ImageInfo 数据结构 + 配置项
    *   **说明**: 新增 ImageInfo 数据结构；SplitterProperties 新增 PDF 图片提取配置（extract-images/image-dpi）；ArkProperties 新增 visionModel；application.yml 新增配置
    *   **变更类型**: 新增
    *   **涉及文件**: `ImageInfo.java`、`SplitterProperties.java`、`ArkProperties.java`、`application.yml`
    *   **测试文件**: `SplitterPropertiesTest.java`（扩展）、`ArkPropertiesTest.java`（如有）
    *   **参考**: 技术方案 Sec 4.1.1、4.8 配置部分
    *   **对应AC**: AC-021（配置支撑）
    *   **预估工时**: 40m
    *   **依赖**: 无
    *   **验证标准**（TDD RED 阶段的测试依据）:
        - [ ] SplitterProperties.getPdf().isExtractImages() 返回 true（默认值）
        - [ ] SplitterProperties.getPdf().getImageDpi() 返回 144（默认值）
        - [ ] ArkProperties.getVisionModel() 返回配置的视觉模型名称
        - [ ] ImageInfo 包含 imagePath/pageNumber/imageIndex 三个字段

### 阶段二：图片提取 (Image Extraction)

- [ ] **Task-28**: ImageExtractor PDF 图片提取器（TDD）
    *   **说明**: 使用 PDFBox PDFRenderer 将 PDF 每页渲染为 PNG 图片并保存到文件系统，返回 ImageInfo 列表
    *   **变更类型**: 新增
    *   **涉及文件**: `ImageExtractor.java`、`DocumentLoader.java`（新增 extractImages 入口）
    *   **测试文件**: `ImageExtractorTest.java`（新增）
    *   **参考**: 技术方案 Sec 4.1.2
    *   **对应AC**: AC-021
    *   **预估工时**: 90m
    *   **依赖**: Task-27
    *   **验证标准**（TDD RED 阶段的测试依据）:
        - [ ] 给定含图片的 PDF 字节数组，extractImages 返回非空 ImageInfo 列表
        - [ ] 返回的 ImageInfo 的 imagePath 指向实际存在的 PNG 文件
        - [ ] 返回的 ImageInfo 的 pageNumber 与 PDF 页码对应
        - [ ] extract-images=false 时不提取图片（返回空列表）
        - [ ] PDF 渲染异常时捕获并返回空列表（不抛异常）
        - [ ] 空字节数组或非 PDF 格式返回空列表

### 阶段三：图片描述生成 (Image Description)

- [x] **Task-29**: ModelFactory 新增 getVisionChatModel 方法（TDD）
    *   **说明**: ModelFactory 新增 getVisionChatModel() 方法，返回支持图片输入的视觉 ChatModel 实例
    *   **变更类型**: 修改
    *   **涉及文件**: `ModelFactory.java`
    *   **测试文件**: `ModelFactoryTest.java`（扩展）
    *   **参考**: 技术方案 Sec 4.8 ModelFactory 新增方法
    *   **对应AC**: AC-022
    *   **预估工时**: 40m
    *   **依赖**: Task-27
    *   **验证标准**（TDD RED 阶段的测试依据）:
        - [ ] getVisionChatModel() 返回非 null 的 ChatModel 实例
        - [ ] 多次调用返回同一缓存实例（缓存复用）
        - [ ] visionModel 未配置时抛出 BusinessException

- [x] **Task-30**: ImageDescriptor 图片描述生成器（TDD）
    *   **说明**: 调用视觉 ChatModel 为图片生成文本描述；视觉模型调用失败时返回 null 并记录 WARN 日志
    *   **变更类型**: 新增
    *   **涉及文件**: `ImageDescriptor.java`
    *   **测试文件**: `ImageDescriptorTest.java`（新增）
    *   **参考**: 技术方案 Sec 4.1.3
    *   **对应AC**: AC-022, AC-024
    *   **预估工时**: 80m
    *   **依赖**: Task-29
    *   **验证标准**（TDD RED 阶段的测试依据）:
        - [ ] 给定有效图片路径，describe 返回非空文本描述
        - [ ] 视觉模型返回的描述涵盖图片可见信息
        - [ ] 视觉模型抛异常时 describe 返回 null（不抛异常）
        - [ ] 视觉模型失败时记录 WARN 日志
        - [ ] 图片文件不存在时返回 null

### 阶段四：向量化流程集成 (Vectorization Integration)

- [x] **Task-31**: DocumentService 图片处理分支集成（TDD）
    *   **说明**: processDocument 新增图片处理分支：PDF 且 extract-images=true 时提取图片→生成描述→构建 TextSegment（chunkType=image）→追加到分块列表→统一向量化入库
    *   **变更类型**: 修改
    *   **涉及文件**: `DocumentService.java`
    *   **测试文件**: `DocumentServiceTest.java`（扩展）
    *   **参考**: 技术方案 Sec 4.8 图片处理流程
    *   **对应AC**: AC-023, AC-024
    *   **预估工时**: 100m
    *   **依赖**: Task-28, Task-30
    *   **验证标准**（TDD RED 阶段的测试依据）:
        - [ ] PDF 文档处理后，分块列表包含 chunkType=image 的图片描述分块
        - [ ] 图片描述分块的 metadata 包含 imagePath/imageDescription/pageNumber
        - [ ] 图片描述分块与文本分块一同向量化入库
        - [ ] 视觉模型失败时图片被跳过，文档状态为 COMPLETED
        - [ ] extract-images=false 时不执行图片处理（分块列表仅含文本）
        - [ ] TXT/MD 文档不触发图片处理

### 阶段五：回归验证 (Regression Verification)

- [x] **Task-32**: 回归验证
    *   **说明**: 运行全量已有测试套件，确保变更未破坏原有功能。重点验证文本分割/向量化流程无回归。
    *   **变更类型**: 验证
    *   **涉及文件**: 所有测试文件
    *   **对应AC**: 所有受影响的 AC
    *   **预估工时**: 50m
    *   **依赖**: Task-31
    *   **验证标准**:
        - [ ] splitter 模块全量测试通过（无回归）
        - [ ] RAG 模块全量测试通过（无回归）
        - [ ] LLM 模块全量测试通过（无回归）
        - [ ] 全量编译通过
        - [ ] 本次新增测试全部通过

## 5. 增量验收标准检查清单 (Incremental AC Checklist)

| 验收标准ID | 验收标准描述 | 状态 | 对应任务 | 操作 |
| :--- | :--- | :--- | :--- | :--- |
| AC-021 | PDF 文档中的图片被提取并保存 | 已完成 | Task-27, Task-28 | 新增 |
| AC-022 | 图片通过视觉模型生成文本描述 | 已完成 | Task-29, Task-30 | 新增 |
| AC-023 | 图片描述向量化并作为独立分块支持检索 | 已完成 | Task-31 | 新增 |
| AC-024 | 视觉模型调用失败时跳过图片不影响文档处理 | 已完成 | Task-30, Task-31 | 新增 |

## 6. 变更总结 (Change Summary)
*   **总新增任务数**: 6 个（Task-27 ~ Task-32）
*   **预计总工时**: 400 分钟（约 6.7 小时）
*   **风险等级**: 中
*   **风险说明**: 视觉模型 API 可用性是主要风险（可能未配置或超时），通过 AC-024 失败跳过机制缓解；PDFRender 整页渲染可能将文字也转为图片，视觉模型描述需区分文字和图片内容
*   **测试影响**: 需修改 3 个已有测试（SplitterPropertiesTest/DocumentLoaderTest/DocumentServiceTest），新增 2 个测试文件（ImageExtractorTest/ImageDescriptorTest）
*   **预期效果**: PDF 文档中的图片信息可通过文字描述被检索到，检索结果包含图片路径引用，用户可获取包含相关图片的文档内容
