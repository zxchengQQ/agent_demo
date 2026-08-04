# 技术设计文档: 文档分割模块化与Token展示

## 0. 设计概要 (Design Summary)

*   **功能描述**：将文件解析和分割逻辑抽取为独立 Maven 模块并为 txt/md/pdf 实现专属结构感知分割策略，同时在对话页面添加会话级 Token 消耗实时展示。
*   **影响范围**：新建 `agent-demo-splitter` 模块；改造 `agent-demo-rag`（移除解析/分割代码，改为依赖新模块）；改造 `agent-demo-llm`（ArkThinkingStreamingChatModel 添加 Token usage 解析）；改造 `agent-demo-web`（AgentController 添加 usage SSE 事件）；改造 `agent-demo-frontend`（Token 展示组件）；改造 `agent-demo-common`（新增 SimpleTokenEstimator）；改造 `agent-demo-bom`（新增 commonmark-java 依赖管理）；改造根 `pom.xml`（新增模块声明）。
*   **技术难点**：
    1. Markdown AST 解析与结构感知分割（标题层级 + 代码块/表格原子保护）
    2. PDF 按页提取与页码元数据保留
    3. 超大语义单元多级优先级级联切分算法
    4. 火山引擎 API Token usage 提取（需确认 API 是否返回 usage，可能需要 `stream_options.include_usage`）
    5. SSE 协议扩展（新增 usage 事件）与前端解析适配
    6. PDF 图片提取（PDFBox PDFRender 渲染页面为图片）与视觉模型描述生成（CR-002 新增）
*   **依赖关系**：新模块依赖 `agent-demo-common` + langchain4j + pdfbox + tabula + commonmark-java；RAG 模块改为依赖新模块。

## 1. 架构概览 (Architecture Overview)

### 1.1 模块变更总览

```mermaid
graph LR
    subgraph 变更前
        RAG1[agent-demo-rag<br/>DocumentLoader<br/>DocumentService<br/>DocumentSplitters]
    end
    subgraph 变更后
        SPLITTER[agent-demo-splitter<br/>DocumentLoader<br/>DocumentSplitterRegistry<br/>MarkdownSplitter/PdfSplitter/TxtSplitter<br/>CascadeSplitter<br/>SplitterProperties]
        RAG2[agent-demo-rag<br/>DocumentService<br/>--- 调用新模块 ---]
        RAG2 -->|依赖| SPLITTER
    end
    COMMON[agent-demo-common<br/>SimpleTokenEstimator]
    SPLITTER -->|依赖| COMMON
```

### 1.2 新模块内部结构

```
agent-demo-splitter/
├── pom.xml
└── src/main/java/com/agentdemo/splitter/
    ├── config/
    │   └── SplitterProperties.java          # 按文件类型配置 size + overlap
    ├── loader/
    │   ├── DocumentLoader.java              # 从 RAG 模块迁移（解析文件为 ParsedDocument）
    │   └── ParsedDocument.java              # 解析结果（含全文 + 分页/分节信息）
    ├── splitter/
    │   ├── TypedDocumentSplitter.java        # 分割器接口（extends DocumentSplitter）
    │   ├── DocumentSplitterRegistry.java     # 分割器注册中心（按文件类型路由 + 回退）
    │   ├── MarkdownDocumentSplitter.java     # MD 专属分割器（commonmark-java AST）
    │   ├── PdfDocumentSplitter.java          # PDF 专属分割器（PDFBox 按页）
    │   ├── TxtDocumentSplitter.java          # TXT 专属分割器（多级递归）
    │   ├── GenericDocumentSplitter.java      # 通用递归分割器（回退用）
    │   ├── image/                            # 图片处理（CR-002 新增）
    │   │   ├── ImageExtractor.java          # PDF 图片提取器（PDFBox PDFRender）
    │   │   ├── ImageDescriptor.java          # 图片描述生成器（调用视觉 ChatModel）
    │   │   └── ImageInfo.java                # 图片信息数据结构（path/pageNumber/index）
    │   └── util/
    │       ├── CascadeSplitter.java          # 多级优先级级联切分工具（仅切分，不合并）
    │       └── ChunkMerger.java              # 分割后合并过短块工具（CR-001 新增）
    └── tokenizer/
        └── SplitterTokenEstimator.java       # 分割用 Token 估算器
```

### 1.3 文档处理数据流

```mermaid
sequenceDiagram
    participant DS as DocumentService (RAG)
    participant Reg as DocumentSplitterRegistry
    participant Splitter as TypedDocumentSplitter
    participant Loader as DocumentLoader
    participant Cascade as CascadeSplitter

    DS->>Loader: load(fileBytes, format)
    Loader->>Loader: 解析文件为 ParsedDocument
    Loader-->>DS: ParsedDocument (text + sections)

    DS->>Reg: split(parsedDocument, knowledgeBaseId, documentId)
    Reg->>Reg: 按 format 选择专属 Splitter
    alt 专属 Splitter 存在
        Reg->>Splitter: split(parsedDocument)
        Splitter->>Splitter: 结构感知分割（标题/页面/段落）
        alt 语义单元超限
            Splitter->>Cascade: cascadeSplit(oversizedText, maxTokens, overlap)
            Cascade-->>Splitter: List<TextSegment>
        end
        Splitter->>Splitter: ChunkMerger 合并过短块（CR-001 新增）
        Splitter-->>Reg: List<TextSegment>
    else 专属 Splitter 失败/不存在
        Reg->>Reg: 回退到 GenericDocumentSplitter
        Reg-->>DS: List<TextSegment>
    end
    Reg-->>DS: List<TextSegment> (含 metadata)

    alt 文件为 PDF（CR-002 新增）
        DS->>Loader: extractImages(fileBytes, documentId)
        Loader->>Loader: PDFRender 渲染页面为 PNG，保存到 images/{documentId}/
        Loader-->>DS: List<ImageInfo> (path + pageNumber + index)
        DS->>DS: ImageDescriptor 调用视觉模型生成描述
        DS->>DS: 图片描述构建为 TextSegment (chunkType=image, metadata 含 imagePath)
        DS-->>DS: 合并文本分块 + 图片描述分块
    end

    DS->>DS: 向量化 + 存储（文本分块 + 图片描述分块统一向量化）
```

### 1.4 Token 消耗展示数据流

```mermaid
sequenceDiagram
    participant FE as 前端 Vue
    participant CTL as AgentController
    participant LLM as 火山引擎 API

    FE->>CTL: POST /chat/stream (SSE)
    CTL->>LLM: 调用 LLM API

    alt 正常流式路径 (enableThinking=false)
        LLM-->>CTL: Response<AiMessage> (含 tokenUsage)
        CTL->>CTL: response.tokenUsage() 提取
    else 思考流式路径 (enableThinking=true)
        LLM-->>CTL: SSE chunks (含 usage chunk)
        CTL->>CTL: ArkThinkingStreamingChatModel 解析 usage
    end

    alt API 返回 Token 用量
        CTL->>FE: SSE event: usage (真实 Token 数据)
    else API 不返回 Token 用量
        CTL->>CTL: SimpleTokenEstimator 估算
        CTL->>FE: SSE event: usage (估算 Token 数据, estimated=true)
    end

    CTL->>FE: SSE event: done (duration)
    FE->>FE: 累加会话 Token 总量
    FE->>FE: 更新输入框区域展示
    FE->>FE: 持久化到 localStorage
```

### 1.5 UI/逻辑映射

| 前端组件 | 消费方式 | 说明 |
|---------|---------|------|
| ChatWindow.vue | 接收 SSE `usage` 事件，调用 store 更新 Token 累计 | 新增 `onUsage` 回调 |
| MessageInput.vue | 展示会话累计 Token 消耗量 | 新增 Token 展示区域 |
| session.ts (store) | 管理会话级 Token 累计状态，持久化到 localStorage | 新增 `tokenUsage` 字段和 `addTokenUsage` 方法 |
| chat.ts (API) | 解析 SSE `usage` 事件，触发回调 | 新增 `onUsage` 回调到 StreamCallbacks |

## 2. API 设计 (API Design)

### 2.1 SSE 事件协议变更

本次不新增 REST API，仅扩展现有 SSE 流式接口的事件类型。

| 事件名 | 数据格式 | 触发时机 | 对应验收标准 |
| :--- | :--- | :--- | :--- |
| `usage` (新增) | JSON 对象 | 每轮对话 LLM 响应完成后，`done` 事件之前 | AC-010, AC-014 |
| `done` (不变) | 数字（耗时毫秒） | 流式完整结束 | 无变更 |

#### `usage` 事件数据格式

```json
{
  "inputTokens": 150,
  "outputTokens": 320,
  "totalTokens": 470,
  "estimated": false
}
```

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| inputTokens | int | 输入 Token 数（提示词消耗） |
| outputTokens | int | 输出 Token 数（生成内容消耗） |
| totalTokens | int | 总 Token 数（inputTokens + outputTokens） |
| estimated | boolean | true=本地估算值，false=API 真实返回值 |

### 2.2 SSE 事件时序

```
event: session
data: abc123def456

event: token
data: 你

event: token
data: 好

event: usage
data:{"inputTokens":150,"outputTokens":320,"totalTokens":470,"estimated":false}

event: done
data: 1234
```

## 3. 数据库设计 (Database Schema)

> 本项目当前无传统关系数据库，采用纯内存存储。本次不涉及数据库设计变更。

### 3.1 内存数据结构变更

#### DocumentChunk 实体新增字段

**变更说明**：为支持 Token 级别统计，DocumentChunk 新增 `tokenCount` 字段。

```java
// agent-demo-rag: DocumentChunk.java 变更
@Data
public class DocumentChunk {
    private String id;
    private String documentId;
    private int chunkIndex;
    private String content;
    private int charCount;
    private int tokenCount;  // 新增：分块 Token 数（通过 SplitterTokenEstimator 估算）
}
```

#### 前端 Session 接口新增字段

```typescript
// agent-demo-frontend: types/index.ts 变更
export interface TokenUsage {
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  estimated: boolean;
}

export interface Session {
  sessionId: string;
  title: string;
  messages: Message[];
  createdAt: number;
  lastActiveAt: number;
  tokenUsage?: TokenUsage;  // 新增：会话级累计 Token 消耗
}
```

## 4. 核心逻辑与算法 (Core Logic)

### 4.1 ParsedDocument 数据结构

**触发条件**：DocumentLoader 解析文件时构建。

```java
@Data
public class ParsedDocument {
    /** 全文文本（所有分割器可用） */
    private String text;
    /** 文件格式（txt/md/pdf） */
    private String format;
    /** 结构化分节（PDF 为按页文本，MD/TXT 为 null） */
    private List<DocumentSection> sections;
}

@Data
public class DocumentSection {
    /** 分节文本 */
    private String text;
    /** 元数据（如 page_number、header_level 等） */
    private Map<String, String> metadata;
}
```

**PDF 解析行为**：DocumentLoader 使用 PDFBox 的 `PDFTextStripper` 按页提取文本，每页构建一个 `DocumentSection`，metadata 中写入 `pageNumber`。表格提取（tabula）结果合并到对应页面的文本中。

**MD/TXT 解析行为**：DocumentLoader 直接返回全文文本，`sections` 为 null。结构解析由各自的专属分割器负责。

#### 4.1.1 ImageInfo 数据结构（CR-002 新增）

**触发条件**：DocumentLoader 解析 PDF 时提取图片后构建。

```java
@Data
@Builder
public class ImageInfo {
    /** 图片文件存储路径（绝对路径） */
    private String imagePath;
    /** 图片所属页码 */
    private int pageNumber;
    /** 图片在页面中的索引（同页多图时区分） */
    private int imageIndex;
}
```

**图片提取行为**：DocumentLoader 使用 PDFBox 的 `PDFRenderer` 将 PDF 每页渲染为 PNG 图片，保存到 `${rag.document.temp-dir}/images/{documentId}/page{N}_img{M}.png`。图片提取可通过配置开关 `rag.splitter.pdf.extract-images` 控制（默认 true）。

#### 4.1.2 ImageExtractor 图片提取器（CR-002 新增）

**职责**：从 PDF 文档中提取图片并保存到文件系统。

```java
@Component
public class ImageExtractor {
    /**
     * 从 PDF 提取所有页面图片并保存为 PNG
     * @param fileBytes PDF 文件字节数组
     * @param documentId 文档 ID（用于构建存储路径）
     * @param imageDir 图片存储根目录
     * @return 提取的图片信息列表
     */
    public List<ImageInfo> extractImages(byte[] fileBytes, String documentId, Path imageDir);
}
```

**提取策略**：使用 `PDFRenderer.renderImageWithDPI(pageIndex, 144)` 将每页渲染为 144 DPI 的 BufferedImage，通过 `ImageIO.write(image, "png", file)` 保存。144 DPI 兼顾清晰度和文件大小。

#### 4.1.3 ImageDescriptor 图片描述生成器（CR-002 新增）

**职责**：调用视觉模型为图片生成文本描述。

```java
@Component
public class ImageDescriptor {
    private final ModelFactory modelFactory;

    /**
     * 为图片生成文本描述
     * @param imagePath 图片路径
     * @return 图片文本描述（涵盖图中文字、图表内容、示意图含义）
     */
    public String describe(String imagePath);
}
```

**实现方案**：通过 `ModelFactory.getVisionChatModel()` 获取视觉 ChatModel，将图片转为 Base64 后作为 UserMessage 的 image content 发送，提示词引导模型描述图片中的可见信息。调用失败时返回 null 并记录 WARN 日志（AC-024）。

### 4.2 DocumentSplitterRegistry 路由与回退

**触发条件**：DocumentService 调用分割时。

**处理步骤**：
1. 根据文件扩展名（format）查找对应的 `TypedDocumentSplitter`
2. 调用专属分割器的 `split()` 方法
3. 若专属分割器抛出异常，捕获后记录 WARN 日志，回退到 `GenericDocumentSplitter`
4. 返回分割结果

```java
public List<TextSegment> split(ParsedDocument doc, String knowledgeBaseId, String documentId) {
    TypedDocumentSplitter splitter = splitters.get(doc.getFormat());
    List<TextSegment> segments;
    try {
        if (splitter != null) {
            segments = splitter.split(doc);
        } else {
            segments = genericSplitter.split(doc);
        }
    } catch (Exception e) {
        log.warn("专属分割器 [{}] 执行失败，回退到通用分割器: {}", doc.getFormat(), e.getMessage());
        segments = genericSplitter.split(doc);
    }
    // 为每个分块添加来源 metadata
    return enrichMetadata(segments, knowledgeBaseId, documentId, doc.getFormat());
}
```

### 4.3 MarkdownDocumentSplitter 算法

**触发条件**：文件格式为 `md` 时由 Registry 路由调用。

**技术方案**：使用 commonmark-java（0.22.0）+ GFM Tables 扩展解析 Markdown AST，通过 Visitor 模式遍历节点。

**处理步骤**：
1. 使用 `Parser.builder().extensions([TablesExtension.create()]).build()` 构建 Parser
2. 解析全文为 AST `Node document = parser.parse(text)`
3. 遍历 AST，按 `Heading` 节点分割为多个 Section
4. 遇到 `FencedCodeBlock`、`IndentedCodeBlock`、`TableBlock` 时标记为原子单元
5. 每个 Section 构建为一个 `TextSegment`，metadata 写入 `headerLevel` 和 `headerText`
6. 若 Section 文本 Token 数超过配置的 `maxSize`，调用 `CascadeSplitter` 二次切分
7. 原子单元（代码块/表格）即使超限也不在首次分割中切断，而是作为独立分块输出；若原子单元本身超过 `maxSize`，才调用 `CascadeSplitter` 强制切分
8. 调用 `ChunkMerger.merge(segments, minSize, maxSize, "headerText")` 按 headerText 分组合并过短块（CR-001 新增）

**伪代码**：
```
function splitMarkdown(text, maxSize, overlap):
    ast = commonmarkParser.parse(text)
    sections = []
    currentSection = new StringBuilder()
    currentHeader = null

    ast.accept(new AbstractVisitor() {
        visitHeading(heading):
            if currentSection.notEmpty():
                sections.add(buildSection(currentSection, currentHeader))
            currentSection = new StringBuilder()
            currentHeader = {level: heading.getLevel(), text: heading.getText()}
            currentSection.append(heading.getText()).append("\n")

        visitFencedCodeBlock(code):
            // 代码块作为原子单元
            codeText = code.getContent()
            if currentSection.notEmpty() and estimateTokens(currentSection) + estimateTokens(codeText) > maxSize:
                sections.add(buildSection(currentSection, currentHeader))
                currentSection = new StringBuilder()
            currentSection.append("```").append(code.getInfo()).append("\n")
            currentSection.append(codeText)
            currentSection.append("```\n")

        visitTableBlock(table):
            // 表格作为原子单元，类似代码块处理
            tableText = renderTableToMarkdown(table)
            // 同代码块逻辑...

        visitText(text):
            currentSection.append(text.getLiteral())
    })

    if currentSection.notEmpty():
        sections.add(buildSection(currentSection, currentHeader))

    // 检查每个 section 是否超限
    result = []
    for section in sections:
        tokens = estimateTokens(section.text)
        if tokens > maxSize:
            result.addAll(cascadeSplitter.split(section.text, maxSize, overlap))
        else:
            result.add(section)
    return result
```

### 4.4 PdfDocumentSplitter 算法

**触发条件**：文件格式为 `pdf` 时由 Registry 路由调用。

**技术方案**：利用 `ParsedDocument.sections`（DocumentLoader 已按页提取），每页文本独立分块。

**处理步骤**：
1. 从 `ParsedDocument.sections` 获取每页文本（每个 section metadata 含 `pageNumber`）
2. 对每页文本：
   - 若 Token 数 ≤ `maxSize`，整页作为一个 `TextSegment`，metadata 写入 `pageNumber`
   - 若 Token 数 > `maxSize`，调用 `CascadeSplitter` 按段落递归切分，每个子分块 metadata 均写入相同 `pageNumber`
3. 不跨页拼接内容（AC-004 要求）
4. 调用 `ChunkMerger.merge(segments, minSize, maxSize, "pageNumber")` 按 pageNumber 分组合并过短块（CR-001 新增）

**伪代码**：
```
function splitPdf(parsedDocument, maxSize, overlap):
    result = []
    for section in parsedDocument.sections:
        pageNumber = section.metadata["pageNumber"]
        tokens = estimateTokens(section.text)
        if tokens <= maxSize:
            result.add(TextSegment(section.text, metadata={pageNumber: pageNumber}))
        else:
            subSegments = cascadeSplitter.split(section.text, maxSize, overlap)
            for seg in subSegments:
                seg.metadata.put("pageNumber", pageNumber)
                result.add(seg)
    return result
```

### 4.5 TxtDocumentSplitter 算法

**触发条件**：文件格式为 `txt` 时由 Registry 路由调用。

**技术方案**：多级分隔符优先级递归切分，与 `CascadeSplitter` 策略一致。

**处理步骤**：
1. 获取全文文本
2. 调用 `CascadeSplitter.split(text, maxSize, overlap)` 执行多级递归切分
3. 调用 `ChunkMerger.merge(segments, minSize, maxSize)` 全局合并过短块（CR-001 修改：合并职责从 CascadeSplitter 迁移到 ChunkMerger）

### 4.6 CascadeSplitter 多级优先级级联切分算法

**触发条件**：任何语义单元（段落、章节、页面）超过 `maxSize` 时调用。

**处理步骤**：
1. **Level 1**：按段落分隔符 `\n\n` 切分
2. 对每个子块检查 Token 数：
   - 若 ≤ `maxSize`，保留
   - 若 > `maxSize`，进入 Level 2
3. **Level 2**：按句子分隔符 `。`、`！`、`？`、`. `、`! `、`? ` 切分
4. 对每个子块检查 Token 数：
   - 若 ≤ `maxSize`，保留
   - 若 > `maxSize`，进入 Level 3
5. **Level 3**：按行分隔符 `\n` 切分
6. 对每个子块检查 Token 数：
   - 若 ≤ `maxSize`，保留
   - 若 > `maxSize`，进入 Level 4
7. **Level 4**：按 Token 数强制滑动窗口切分（step = maxSize - overlap）

> **CR-001 变更**：原步骤 8"合并过短块"已移除，合并职责迁移至独立的 `ChunkMerger` 工具类（见 Sec 4.10）。CascadeSplitter 仅负责纯切分，合并由各分割器在最终输出前统一调用 ChunkMerger 完成。

**伪代码**：
```
function cascadeSplit(text, maxSize, overlap):
    # Level 1: 段落
    blocks = splitBy(text, ["\n\n"])
    result = []
    for block in blocks:
        if estimateTokens(block) <= maxSize:
            result.add(block)
        else:
            result.addAll(level2Split(block, maxSize, overlap))
    # CR-001: 移除 mergeShortBlocks 调用，合并由 ChunkMerger 统一处理
    return result

function level2Split(text, maxSize, overlap):
    # Level 2: 句子
    blocks = splitBy(text, ["。", "！", "？", ". ", "! ", "? "])
    result = []
    for block in blocks:
        if estimateTokens(block) <= maxSize:
            result.add(block)
        else:
            result.addAll(level3Split(block, maxSize, overlap))
    return result

function level3Split(text, maxSize, overlap):
    # Level 3: 行
    blocks = splitBy(text, ["\n"])
    result = []
    for block in blocks:
        if estimateTokens(block) <= maxSize:
            result.add(block)
        else:
            result.addAll(level4Split(block, maxSize, overlap))
    return result

function level4Split(text, maxSize, overlap):
    # Level 4: Token 滑动窗口
    tokens = estimateTokens(text)
    if tokens <= maxSize:
        return [text]
    # 按字符比例估算切分位置
    charsPerToken = text.length() / tokens
    segmentCharSize = (int)(maxSize * charsPerToken)
    overlapCharSize = (int)(overlap * charsPerToken)
    step = segmentCharSize - overlapCharSize
    result = []
    for i in range(0, text.length(), step):
        end = min(i + segmentCharSize, text.length())
        result.add(text.substring(i, end))
    return result
```

### 4.7 Token Usage 提取逻辑

#### 4.7.1 正常流式路径（enableThinking=false/null）

**现有代码**：`AgentController` 第 258 行 `onCompleteResponse(Response<AiMessage> response)` 回调已包含 `response.tokenUsage()`。

**改造方案**：在 `onCompleteResponse` 回调中提取 `response.tokenUsage()`，若非空则发送 `usage` SSE 事件；若为空则使用 `SimpleTokenEstimator` 估算。

```java
.onCompleteResponse(response -> {
    memoryManager.addAssistantMessage(effectiveSessionId, fullResponse.toString());

    // 新增：提取 Token usage
    TokenUsage tokenUsage = response.tokenUsage();
    if (tokenUsage != null) {
        sendEvent(emitter, "usage", buildUsageJson(
            tokenUsage.inputTokenCount(),
            tokenUsage.outputTokenCount(),
            tokenUsage.totalTokenCount(),
            false));
    } else {
        // API 未返回 usage，本地估算
        int inputTokens = SimpleTokenEstimator.estimate(userMessage + systemPrompt);
        int outputTokens = SimpleTokenEstimator.estimate(fullResponse.toString());
        sendEvent(emitter, "usage", buildUsageJson(
            inputTokens, outputTokens, inputTokens + outputTokens, true));
    }

    long duration = System.currentTimeMillis() - start;
    sendEvent(emitter, "done", duration);
    emitter.complete();
})
```

#### 4.7.2 思考流式路径（enableThinking=true）

**现有代码**：`ArkThinkingStreamingChatModel.parseSseLine()` 未解析 `usage` 字段，请求体未设置 `stream_options.include_usage`。

**改造方案**：

1. **请求体**（`buildRequestBody` 方法）：添加 `stream_options: {include_usage: true}`
2. **解析逻辑**（`parseSseLine` 方法）：新增 `usage` 字段解析
3. **回调接口**（`ThinkingStreamHandler`）：`onComplete` 方法签名扩展，新增 `TokenUsage` 参数

```java
// ArkThinkingStreamingChatModel.buildRequestBody() 新增
requestBody.put("stream_options", Map.of("include_usage", true));

// parseSseLine() 新增 usage 解析
JsonNode usageNode = root.path("usage");
if (!usageNode.isMissingNode() && !usageNode.isNull()) {
    int promptTokens = usageNode.path("prompt_tokens").asInt(0);
    int completionTokens = usageNode.path("completion_tokens").asInt(0);
    int totalTokens = usageNode.path("total_tokens").asInt(promptTokens + completionTokens);
    this.capturedUsage = new TokenUsage(promptTokens, completionTokens, totalTokens);
}

// onComplete 回调时传递 capturedUsage
handler.onComplete(fullResponse.toString(), finishReason, capturedUsage);
```

4. **AgentController** 中思考路径的 `onComplete` 回调提取 `TokenUsage`，发送 `usage` SSE 事件。

#### 4.7.3 任务拆解路径（enableTaskBreakdown=true）

与正常流式路径类似，在 `onComplete` 回调中提取 Token usage。若 LangChain4j `AiServices` 代理的 `Response` 包含 `tokenUsage()` 则直接使用，否则本地估算。

### 4.8 图片处理流程（CR-002 新增）

**触发条件**：DocumentService 处理 PDF 文档时，文本分割完成后执行。

**处理步骤**：
1. DocumentService 检查文件格式为 `pdf` 且 `rag.splitter.pdf.extract-images=true`
2. 调用 `ImageExtractor.extractImages(fileBytes, documentId, imageDir)` 提取图片并保存
3. 对每张图片调用 `ImageDescriptor.describe(imagePath)` 生成文本描述
4. 将图片描述构建为独立 `TextSegment`，metadata 写入：
   - `chunkType`: "image"（标识为图片描述分块）
   - `imagePath`: 图片文件路径（供前端展示原图）
   - `imageDescription`: 视觉模型生成的描述
   - `pageNumber`: 图片所属页码
5. 图片描述分块追加到文本分块列表后，统一执行向量化+入库
6. 视觉模型调用失败的图片跳过（不构建 TextSegment），记录 WARN 日志

**伪代码**：
```
function processPdfImages(fileBytes, documentId, textSegments):
    if format != "pdf" or not config.extractImages:
        return textSegments

    images = imageExtractor.extractImages(fileBytes, documentId, imageDir)
    imageSegments = []
    for image in images:
        description = imageDescriptor.describe(image.imagePath)
        if description == null:
            log.warn("图片描述生成失败, skip: {}", image.imagePath)
            continue
        segment = TextSegment(
            text="[图片描述] " + description,
            metadata={
                chunkType: "image",
                imagePath: image.imagePath,
                imageDescription: description,
                pageNumber: image.pageNumber
            }
        )
        imageSegments.add(segment)

    return textSegments + imageSegments
```

**ModelFactory 新增方法**：

```java
/**
 * 获取视觉对话模型（CR-002 新增）
 * 业务含义：用于 PDF 图片描述生成，支持图片输入的 ChatModel
 */
public ChatModel getVisionChatModel() {
    String modelName = arkProperties.getVisionModel();  // 如 doubao-vision-pro
    return chatModelCache.computeIfAbsent(modelName, this::createVisionChatModel);
}
```

**配置新增**（application.yml）：

```yaml
rag:
  splitter:
    pdf:
      extract-images: true          # 是否提取 PDF 图片（CR-002 新增）
      image-dpi: 144                # 图片渲染 DPI（CR-002 新增）
  document:
    image-dir: ${rag.document.temp-dir}/images  # 图片存储目录（CR-002 新增）

ark:
  vision-model: doubao-vision-pro  # 视觉模型名称（CR-002 新增）
```

### 4.9 SimpleTokenEstimator 估算算法

**位置**：`agent-demo-common/src/main/java/com/agentdemo/common/utils/SimpleTokenEstimator.java`

**算法**：
- 中文字符（Unicode CJK 区间）：约 1.5 字符 / Token
- 其他字符（英文、数字、符号）：约 4 字符 / Token
- 混合文本：分别统计后加权求和

```java
public class SimpleTokenEstimator {
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        int cjkChars = 0;
        int otherChars = 0;
        for (char c : text.toCharArray()) {
            if (isCJK(c)) {
                cjkChars++;
            } else {
                otherChars++;
            }
        }
        return (int) Math.ceil(cjkChars / 1.5 + otherChars / 4.0);
    }

    private static boolean isCJK(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA;
    }
}
```

### 4.9 前端 Token 展示逻辑

#### 4.9.1 SSE 事件解析（chat.ts）

新增 `usage` 事件处理分支：

```typescript
// chat.ts handleSseEvent() 新增
case 'usage':
  const usageData = JSON.parse(data) as TokenUsage;
  callbacks.onUsage?.(usageData);
  break;
```

#### 4.9.2 StreamCallbacks 接口扩展（types/index.ts）

```typescript
export interface StreamCallbacks {
  // ... 现有回调 ...
  onUsage?: (usage: TokenUsage) => void;  // 新增
}
```

#### 4.9.3 会话 Token 累计（session.ts）

```typescript
// Session 接口新增 tokenUsage 字段
// 新增 addTokenUsage 方法
addTokenUsage(sessionId: string, usage: TokenUsage): void {
  const session = this.sessions.find(s => s.sessionId === sessionId);
  if (!session) return;
  if (!session.tokenUsage) {
    session.tokenUsage = { inputTokens: 0, outputTokens: 0, totalTokens: 0, estimated: false };
  }
  session.tokenUsage.inputTokens += usage.inputTokens;
  session.tokenUsage.outputTokens += usage.outputTokens;
  session.tokenUsage.totalTokens += usage.totalTokens;
  // 任意一次为估算值，整体标记为估算
  if (usage.estimated) {
    session.tokenUsage.estimated = true;
  }
  this.persist();
}
```

#### 4.9.4 Token 展示 UI（MessageInput.vue）

在输入框上方或右侧添加 Token 消耗展示区域：

```vue
<template>
  <div class="input-container">
    <!-- Token 消耗展示 -->
    <div class="token-usage" v-if="sessionTokenUsage">
      <span class="token-count">{{ formatTokens(sessionTokenUsage.totalTokens) }} Tokens</span>
      <span class="token-badge" v-if="sessionTokenUsage.estimated">估算</span>
    </div>
    <!-- 现有输入框 -->
    <div class="input-box">...</div>
  </div>
</template>
```

### 4.10 ChunkMerger 分割后合并过短块算法（CR-001 新增）

**触发条件**：各分割器完成第一轮分割后，在最终输出前调用。

**职责**：对分割结果中低于 `minSize` 的过短分块执行合并，避免 chunk 太小太碎。

**核心 API**：

```java
public class ChunkMerger {
    private final SplitterTokenEstimator tokenEstimator;

    /**
     * 全局合并（TXT/Generic 使用）：所有分块参与合并，无分组约束
     */
    public List<TextSegment> merge(List<TextSegment> segments, int minSize, int maxSize);

    /**
     * 分组合并（MD/PDF 使用）：按指定 metadata key 分组，仅同组内短块合并
     * @param groupByKey metadata 中的键名（如 "pageNumber"、"headerText"）
     */
    public List<TextSegment> merge(List<TextSegment> segments, int minSize, int maxSize, String groupByKey);
}
```

**合并规则**：
1. 遍历分块列表，计算每个分块的 Token 数
2. 当当前块 **或** 前一块 Token 数 < `minSize` 时尝试合并
3. 合并约束：合并后总 Token 数 ≤ `maxSize` 时才执行合并
4. 分组合并时：仅当两块的 `groupByKey` metadata 值相同时才合并（保证 PDF 不跨页、MD 不跨节）
5. 合并方向：当前块内容追加到前一块尾部，metadata 保留前一块的值
6. 最后一个分块允许低于 minSize（无法再合并）

**伪代码**：

```
function merge(segments, minSize, maxSize, groupByKey):
    if segments is empty:
        return segments

    result = [segments[0]]
    for i in 1..segments.length:
        current = segments[i]
        prev = result.last()
        currentTokens = estimateTokens(current)
        prevTokens = estimateTokens(prev)

        # 分组检查：groupByKey 不为空时，仅同组才合并
        if groupByKey != null:
            if prev.metadata[groupByKey] != current.metadata[groupByKey]:
                result.add(current)
                continue

        # 合并条件：当前块或前一块过短，且合并后不超限
        if (currentTokens < minSize or prevTokens < minSize)
           and (prevTokens + currentTokens <= maxSize):
            prev.text = prev.text + "\n" + current.text
            # metadata 保留 prev 的值
        else:
            result.add(current)

    return result
```

**与 CascadeSplitter 的关系**：
- CR-001 前：`CascadeSplitter` 内部 `mergeShortBlocks` 在切分后立即合并，但仅覆盖走 CascadeSplitter 的路径
- CR-001 后：`CascadeSplitter` 仅负责切分（移除 mergeShortBlocks），`ChunkMerger` 统一负责所有分割器的合并后处理，覆盖 MD/PDF 的快捷路径盲区

## 5. 异常处理 (Error Handling)

| 异常场景 | 对应验收标准 | 处理方案 | 用户提示 |
| :--- | :--- | :--- | :--- |
| 空文件（解析后无文本） | AC-011 | DocumentLoader 检测到 text 为空白后抛出 BusinessException(RAG_DOCUMENT_PARSE_FAILED, "文件内容为空") | "文件内容为空，请上传有内容的文件" |
| 专属分割器执行失败 | AC-012 | DocumentSplitterRegistry 捕获异常，记录 WARN 日志，回退到 GenericDocumentSplitter | 无前端提示（用户无感知，文档正常处理） |
| PDF 加密无法解析 | AC-013 | PdfDocumentSplitter 捕获 PDFBox 加密异常，尝试仅提取可提取文本；若完全无法提取则抛 BusinessException | "PDF 文件已加密，无法解析内容" |
| LLM API 不返回 Token 用量 | AC-014 | AgentController 检测 response.tokenUsage() 为 null，使用 SimpleTokenEstimator 估算 | 前端展示"估算"标记 |
| Markdown 格式异常 | AC-012 | commonmark-java Parser 具有容错性，格式异常不会抛异常（按原文处理）。若极端情况抛异常，回退到通用分割器 | 无前端提示 |
| 页面刷新 | AC-015 | Token 累计值随 Session 对象持久化到 localStorage，页面加载时从 localStorage 恢复 | 无提示，自动恢复 |
| SSE usage 事件解析失败 | - | chat.ts 中 JSON.parse 异常捕获，记录 console.warn，不影响 done 事件处理 | 无提示（降级为不展示 Token） |
| PDF 图片提取失败 | AC-021 | ImageExtractor 捕获 PDFRender 异常，记录 WARN 日志，跳过该页图片，继续处理其他页 | 无前端提示（文本分块正常） |
| 视觉模型调用失败/超时 | AC-024 | ImageDescriptor 捕获异常返回 null，跳过该图片描述，不影响文档整体处理 | 无前端提示（文档状态为已完成） |
| 图片描述向量化失败 | - | 复用现有向量化失败处理逻辑（DocumentService 阶段 4 catch） | 文档状态标记为 FAILED |

## 6. 安全与性能 (Security & Performance)

*   **鉴权机制**：无变更（项目无认证机制）
*   **数据校验**：DocumentLoader 现有的格式校验和大小校验不变
*   **性能影响**：
    - commonmark-java AST 解析性能高（比 pegdown 快 10-20 倍），对 10MB 以内文件无感知延迟
    - PDFBox 按页提取需逐页调用 `PDFTextStripper`，对大 PDF 文件（100+页）可能有数秒延迟，但现有 10MB 限制可控制
    - SimpleTokenEstimator 为纯字符遍历，性能可忽略
    - SSE `usage` 事件仅增加一个轻量 JSON 推送，对流式性能无影响
    - PDF 图片提取（PDFRender 144 DPI 渲染）对每页约 100-300ms，10MB PDF 通常 < 50 页，总耗时 < 15s，在异步线程池中执行不阻塞用户（CR-002 新增）
    - 视觉模型调用为同步 API 调用，每张图片约 2-5s，多图时累积耗时较长；通过 `@Async` 异步处理不影响用户体验，失败时跳过不阻断（CR-002 新增）
*   **缓存策略**：无变更（分割器无状态，无需缓存）
*   **安全考虑**：commonmark-java 解析 Markdown 不会执行嵌入脚本；PDFBox 解析 PDF 不会执行嵌入 JavaScript

## 7. 验收标准映射 (AC Mapping)

| 验收标准ID | 验收标准描述 | 对应技术实现 |
| :--- | :--- | :--- |
| AC-001 | 新建独立 Maven 模块抽取解析与分割逻辑 | 新建 `agent-demo-splitter` 模块，迁移 DocumentLoader，新建分割器体系 |
| AC-002 | Markdown 文件按标题层级分割 | `MarkdownDocumentSplitter` + commonmark-java Heading 节点遍历 |
| AC-003 | Markdown 代码块和表格作为原子单元不被切断 | `MarkdownDocumentSplitter` 中 FencedCodeBlock/TableBlock 原子标记 |
| AC-004 | PDF 文件按页面边界分割 | `PdfDocumentSplitter` + DocumentLoader 按页提取（PDFTextStripper） |
| AC-005 | PDF 单页内容超限时按段落递归切分 | `PdfDocumentSplitter` 调用 `CascadeSplitter` 二次切分 |
| AC-006 | TXT 文件按多级优先级递归切分 | `TxtDocumentSplitter` 调用 `CascadeSplitter` |
| AC-007 | 超大语义单元通过多级优先级级联切分 | `CascadeSplitter` 四级降级算法（段落->句子->行->Token滑动窗口） |
| AC-008 | 每种文件类型独立配置分割参数 | `SplitterProperties` 配置类 + application.yml `rag.splitter.{format}.size/overlap` |
| AC-009 | 对话页面展示会话累计 Token 消耗量 | `MessageInput.vue` Token 展示区域 + `session.ts` addTokenUsage 方法 |
| AC-010 | 后端从 LLM API 响应中提取真实 Token 用量并推送 | AgentController `onCompleteResponse` 提取 `response.tokenUsage()` + SSE `usage` 事件 |
| AC-011 | 空文件报错拒绝处理 | DocumentLoader 检测空文本抛 BusinessException |
| AC-012 | 专属分割器失败时自动回退通用分割 | `DocumentSplitterRegistry.split()` try-catch 回退逻辑 |
| AC-013 | PDF 加密文件处理失败的回退 | `PdfDocumentSplitter` 捕获加密异常，尝试降级提取或报错 |
| AC-014 | LLM API 不返回 Token 用量时本地估算 | `SimpleTokenEstimator` + `estimated=true` 标记 |
| AC-015 | Token 消耗量页面刷新后保持 | `session.ts` tokenUsage 字段持久化到 localStorage |
| AC-016 | 分块大小按 Token 数计算 | `SplitterTokenEstimator` + `SimpleTokenEstimator` 替代字符数切分 |
| AC-017 | 分块元数据保留来源信息 | `DocumentSplitterRegistry.enrichMetadata()` 添加 knowledgeBaseId/documentId/format/pageNumber/headerLevel |
| AC-018 | 新模块的模块依赖关系正确 | 根 pom.xml 新增模块声明 + agent-demo-rag pom.xml 新增 splitter 依赖 + BOM 版本管理 |
| AC-019 | 分割后合并过短分块 | `ChunkMerger` 合并工具类 + 各分割器最终输出前调用（MD 按 headerText 分组、PDF 按 pageNumber 分组、TXT/Generic 全局合并）（CR-001 新增） |
| AC-020 | 每种文件类型独立配置 minSize 参数 | `SplitterProperties.ChunkConfig` 新增 minSize 字段 + application.yml `rag.splitter.{format}.min-size`（CR-001 新增） |
| AC-021 | PDF 文档中的图片被提取并保存 | `ImageExtractor.extractImages()` + PDFBox PDFRender 渲染保存 PNG 到 `${rag.document.temp-dir}/images/{documentId}/`（CR-002 新增） |
| AC-022 | 图片通过视觉模型生成文本描述 | `ImageDescriptor.describe()` + `ModelFactory.getVisionChatModel()` 调用视觉 ChatModel（CR-002 新增） |
| AC-023 | 图片描述向量化并作为独立分块支持检索 | DocumentService 图片描述构建 TextSegment（chunkType=image）追加到分块列表，统一 batchEmbed 向量化入库（CR-002 新增） |
| AC-024 | 视觉模型调用失败时跳过图片不影响文档处理 | ImageDescriptor 捕获异常返回 null，DocumentService 跳过该图片，文档状态保持已完成（CR-002 新增） |

## 8. 技术决策说明 (Technical Decisions)

### 8.1 新建独立 Maven 模块 vs RAG 模块内新建包

*   **决策**：新建独立 Maven 模块 `agent-demo-splitter`
*   **理由**：用户明确要求独立模块；职责边界更清晰；未来可独立扩展新文件类型而不影响 RAG 模块；符合项目现有的中等粒度模块拆分风格（11 个模块）

### 8.2 commonmark-java vs 正则表达式 vs flexmark-java

*   **决策**：使用 commonmark-java（0.22.0）+ GFM Tables 扩展
*   **理由**：
    - commonmark-java：轻量（~200KB，零核心依赖）、快速（比 pegdown 快 10-20 倍）、AST 解析准确、活跃维护（2026.06 最新提交）、LangChain4j PR #4276 使用同一库
    - 正则表达式：无法处理嵌套结构、YAML front matter、列表中的标题等边界情况
    - flexmark-java：功能过剩（数十种扩展）、体积大（~1MB+）、API 复杂

### 8.3 新增 SSE `usage` 事件 vs 改造 `done` 事件为 JSON

*   **决策**：新增独立 `usage` SSE 事件
*   **理由**：非破坏性新增，`done` 事件保持原有格式（纯数字 duration），前后端向后兼容性更好；职责分离（duration 和 tokenUsage 独立传输）

### 8.4 简易字符估算 vs OpenAiTokenizer

*   **决策**：在 `agent-demo-common` 中新建 `SimpleTokenEstimator`，基于字符数估算
*   **理由**：项目为学习/演示性质，简易估算已满足需求；无需引入 langchain4j-open-ai 重依赖到 common 模块；估算逻辑透明可理解

### 8.5 ParsedDocument 结构化解析 vs 纯文本传递

*   **决策**：DocumentLoader 返回 `ParsedDocument`（含全文 + 可选 sections）
*   **理由**：PDF 按页分割需要页码信息，纯文本无法承载；MD/TXT 不需要 sections（splitter 自行解析结构），设为 null 保持简洁；向后兼容（全文字段始终存在）

### 8.6 DocumentSplitters.recursive 字符切分 -> Token 切分

*   **决策**：废弃 `DocumentSplitters.recursive(size, overlap)` 默认字符切分，改用自定义 Token 估算
*   **理由**：调研发现现有代码注释标注"token 数"但实际按字符切分（未传 Tokenizer 参数），存在设计偏差；新模块统一使用 `SimpleTokenEstimator` 估算 Token 数进行切分，修正此偏差

### 8.7 图片向量化方案：视觉模型描述+文本向量化 vs 多模态 Embedding（CR-002 新增）

*   **决策**：采用视觉模型生成图片文本描述，再通过现有文本 Embedding 模型向量化
*   **理由**：
    - 复用现有文本向量化流程（batchEmbed + EmbeddingStore），改动最小
    - 无需引入多模态 Embedding 模型，避免新增模型配置和向量化分支
    - 检索时返回图片描述文本 + 原图路径引用，用户可理解图片内容
    - 多模态 Embedding 方案需新增模型和图片向量化流程，维护成本高

### 8.8 图片存储方案：文件系统 vs Base64 metadata（CR-002 新增）

*   **决策**：图片保存到文件系统 `${rag.document.temp-dir}/images/{documentId}/`，metadata 存储 imagePath
*   **理由**：
    - Base64 编码存入 metadata 会显著膨胀向量存储，影响检索性能
    - 文件系统存储支持前端直接通过 URL 访问原图展示
    - 删除文档时可按 documentId 目录清理关联图片

## 9. 风险与注意事项 (Risks & Notes)

### 9.1 技术风险

*   **火山引擎 API Token usage 返回不确定性**：Coding Plan（按次计费）模式的 API 响应中是否包含 `usage` 字段需实际验证。若不包含，`stream_options.include_usage` 可能也无效。**缓解措施**：AC-014 已设计本地估算回退方案，确保功能可用。
*   **commonmark-java 版本兼容性**：0.22.0 需 Java 8+，与项目 Java 17 兼容。需在 BOM 中新增版本管理。
*   **PDF 按页提取性能**：大 PDF 文件（100+页）逐页提取可能有数秒延迟。**缓解措施**：现有 10MB 文件大小限制可控制页数；异步处理机制（`@Async`）已有。
*   **视觉模型 API 可用性**：视觉模型（如 doubao-vision-pro）可能不可用或超时。**缓解措施**：AC-024 设计了失败跳过机制，不影响文档整体处理（CR-002 新增）。
*   **图片提取精度**：PDFRender 整页渲染会将整页转为图片（含文字+图片），而非仅提取嵌入图片对象。**缓解措施**：视觉模型描述会区分文字和图片内容；如需仅提取嵌入图片对象，可后续迭代引入 PDFBox 的 XObject 提取（CR-002 新增）。

### 9.2 兼容性

*   **DocumentLoader 返回值类型变更**：从 `String` 变为 `ParsedDocument`，需同步修改 `DocumentService` 中的调用方代码。
*   **DocumentSplitter 接口变更**：从直接使用 `DocumentSplitters.recursive()` 变为通过 `DocumentSplitterRegistry` 路由，`DocumentService` 中的分割调用需重写。
*   **SSE 协议扩展**：新增 `usage` 事件对现有前端无破坏（前端按事件名分发，未注册的事件自动忽略）。
*   **DocumentChunk 新增 tokenCount 字段**：InMemory 存储无需数据迁移，但需更新 `DocumentChunkStore` 实现中的 `saveChunks` 逻辑。

### 9.3 回滚方案

*   **模块抽取回滚**：若新模块出现问题，可将 DocumentLoader 和分割逻辑回退到 RAG 模块内（Git 回退即可）
*   **Token 展示回滚**：若 Token 统计出现问题，前端可忽略 `usage` 事件（不影响对话功能），后端可注释掉 `usage` 事件发送代码
*   **SSE 协议回滚**：`usage` 事件为新增，移除后不影响现有 `done` 事件

### 9.4 改动文件清单

| 模块 | 文件 | 改动类型 | 说明 |
| :--- | :--- | :--- | :--- |
| 根 pom.xml | `pom.xml` | 修改 | 新增 `agent-demo-splitter` 模块声明 |
| agent-demo-bom | `pom.xml` | 修改 | 新增 commonmark-java、commonmark-ext-gfm-tables 版本管理 |
| **agent-demo-splitter** | `pom.xml` | 新建 | 模块 POM |
| | `config/SplitterProperties.java` | 新建 | 按类型配置 |
| | `loader/DocumentLoader.java` | 迁移+修改 | 从 RAG 迁移，返回值改为 ParsedDocument |
| | `loader/ParsedDocument.java` | 新建 | 解析结果数据结构 |
| | `splitter/TypedDocumentSplitter.java` | 新建 | 分割器接口 |
| | `splitter/DocumentSplitterRegistry.java` | 新建 | 路由与回退 |
| | `splitter/MarkdownDocumentSplitter.java` | 新建 | MD 专属分割器 |
| | `splitter/PdfDocumentSplitter.java` | 新建 | PDF 专属分割器 |
| | `splitter/TxtDocumentSplitter.java` | 新建 | TXT 专属分割器 |
| | `splitter/GenericDocumentSplitter.java` | 新建 | 通用回退分割器 |
| | `splitter/util/CascadeSplitter.java` | 新建 | 多级级联切分 |
| | `splitter/util/ChunkMerger.java` | 新建（CR-001） | 分割后合并过短块工具 |
| | `tokenizer/SplitterTokenEstimator.java` | 新建 | 分割用 Token 估算 |
| agent-demo-common | `utils/SimpleTokenEstimator.java` | 新建 | Token 估算工具类 |
| agent-demo-rag | `pom.xml` | 修改 | 新增 splitter 依赖，移除 pdfbox/tabula 依赖 |
| | `service/DocumentService.java` | 修改 | 改为调用 DocumentSplitterRegistry |
| | `loader/DocumentLoader.java` | 删除 | 迁移到 splitter 模块 |
| | `entity/DocumentChunk.java` | 修改 | 新增 tokenCount 字段 |
| | `config/RagProperties.java` | 修改 | 移除 Chunk 内部类（迁移到 SplitterProperties） |
| agent-demo-llm | `factory/ArkThinkingStreamingChatModel.java` | 修改 | 添加 stream_options.include_usage + usage 解析 |
| | `factory/ThinkingStreamHandler.java` | 修改 | onComplete 签名新增 TokenUsage 参数 |
| agent-demo-web | `controller/AgentController.java` | 修改 | 三条流式路径新增 usage 事件发送 |
| | `dto/ChatResponse.java` | 修改 | 新增 tokenUsage 字段（同步对话用） |
| agent-demo-bootstrap | `application.yml` | 修改 | 新增 rag.splitter 配置段 |
| agent-demo-frontend | `types/index.ts` | 修改 | 新增 TokenUsage 接口、Session.tokenUsage 字段、onUsage 回调 |
| | `api/chat.ts` | 修改 | 新增 usage 事件解析 |
| | `stores/session.ts` | 修改 | 新增 addTokenUsage 方法、tokenUsage 持久化 |
| | `components/ChatWindow.vue` | 修改 | 新增 onUsage 回调处理 |
| | `components/MessageInput.vue` | 修改 | 新增 Token 展示区域 |
| **agent-demo-splitter**（CR-002） | `splitter/image/ImageExtractor.java` | 新建 | PDF 图片提取器（PDFBox PDFRender） |
| | `splitter/image/ImageDescriptor.java` | 新建 | 图片描述生成器（视觉 ChatModel） |
| | `splitter/image/ImageInfo.java` | 新建 | 图片信息数据结构 |
| | `loader/DocumentLoader.java` | 修改 | 新增 extractImages 方法（PDF 图片提取入口） |
| agent-demo-rag（CR-002） | `service/DocumentService.java` | 修改 | processDocument 新增图片处理分支（提取→描述→向量化） |
| | `store/DocumentChunkStore.java` | 修改 | chunkType=image 分块的存储支持 |
| agent-demo-llm（CR-002） | `factory/ModelFactory.java` | 修改 | 新增 getVisionChatModel 方法 |
| | `config/ArkProperties.java` | 修改 | 新增 visionModel 配置字段 |
| agent-demo-bootstrap（CR-002） | `application.yml` | 修改 | 新增 rag.splitter.pdf.extract-images/image-dpi、ark.vision-model 配置 |

---
## 变更日志 (Change Log)

### CR-001: 分割后合并过短分块 (2026-07-29)
**影响范围**: 业务逻辑层（分割器后处理逻辑）、配置层（SplitterProperties）
**变更内容摘要**:
- [新增] `ChunkMerger.java`：独立合并工具类，支持全局合并 + 按 metadata key 分组合并
- [新增] `SplitterProperties.ChunkConfig.minSize` 字段：最小分块大小，默认为 size * 0.5
- [新增] 技术方案 Sec 4.10 ChunkMerger 算法描述
- [修改] `CascadeSplitter.java`：移除 mergeShortBlocks 调用，仅保留纯切分职责
- [修改] `MarkdownDocumentSplitter.java`：最终输出前调用 ChunkMerger（按 headerText 分组合并）
- [修改] `PdfDocumentSplitter.java`：最终输出前调用 ChunkMerger（按 pageNumber 分组合并）
- [修改] `TxtDocumentSplitter.java`：用 ChunkMerger 替代 CascadeSplitter 内部合并
- [修改] `GenericDocumentSplitter.java`：同 TxtDocumentSplitter
- [修改] `application.yml`：rag.splitter 各类型新增 min-size 配置项
- [修改] AC-007：级联切分后补充合并后处理步骤
- [新增] AC-019、AC-020 验收标准映射

### CR-002: PDF 图片提取与向量化检索 (2026-08-03)
**影响范围**: 数据结构层（ImageInfo）、业务逻辑层（图片提取/描述生成/向量化流程）、配置层（视觉模型/图片提取配置）、模型工厂（视觉模型获取）
**变更内容摘要**:
- [新增] `ImageExtractor.java`：PDF 图片提取器，基于 PDFBox PDFRender 渲染页面为 PNG
- [新增] `ImageDescriptor.java`：图片描述生成器，调用视觉 ChatModel 生成文本描述
- [新增] `ImageInfo.java`：图片信息数据结构（path/pageNumber/index）
- [新增] 技术方案 Sec 4.1.1 ImageInfo 数据结构
- [新增] 技术方案 Sec 4.1.2 ImageExtractor 图片提取器
- [新增] 技术方案 Sec 4.1.3 ImageDescriptor 图片描述生成器
- [新增] 技术方案 Sec 4.8 图片处理流程（含伪代码、ModelFactory 新增方法、配置新增）
- [新增] 技术方案 Sec 8.7 图片向量化方案决策说明
- [新增] 技术方案 Sec 8.8 图片存储方案决策说明
- [修改] 数据流图：新增 PDF 图片处理分支
- [修改] 模块内部结构：新增 splitter/image/ 子包
- [修改] 异常处理表：新增图片提取失败、视觉模型失败、图片描述向量化失败
- [修改] 性能影响：新增图片提取和视觉模型调用耗时说明
- [修改] 风险与注意事项：新增视觉模型可用性、图片提取精度风险
- [修改] 改动文件清单：新增 CR-002 相关文件
- [新增] AC-021、AC-022、AC-023、AC-024 验收标准映射
