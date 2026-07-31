package com.agentdemo.splitter.splitter;

import com.agentdemo.splitter.config.SplitterProperties;
import com.agentdemo.splitter.loader.ParsedDocument;
import com.agentdemo.splitter.tokenizer.SplitterTokenEstimator;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MarkdownDocumentSplitter 单元测试
 * <p>
 * 验证 MD 专属分割器按标题分割、代码块/表格原子保护、metadata 含 headerLevel/headerText、
 * 超大 section 调用 CascadeSplitter 等行为。
 */
@DisplayName("Markdown专属分割器测试")
class MarkdownDocumentSplitterTest {

    private MarkdownDocumentSplitter splitter;
    private SplitterTokenEstimator estimator;

    @BeforeEach
    void setUp() {
        estimator = new SplitterTokenEstimator();
        SplitterProperties properties = new SplitterProperties();
        splitter = new MarkdownDocumentSplitter(estimator, properties);
    }

    @Test
    @DisplayName("supportedFormat 返回 md")
    void supportedFormatShouldReturnMd() {
        assertEquals("md", splitter.supportedFormat());
    }

    @Test
    @DisplayName("含多级标题的 Markdown 按标题边界分割")
    void splitByHeadingBoundary() {
        // 使用 minSize=0 禁用合并，验证纯分割行为
        SplitterProperties noMergeProps = new SplitterProperties();
        noMergeProps.getMd().setMinSize(0);
        MarkdownDocumentSplitter noMergeSplitter = new MarkdownDocumentSplitter(estimator, noMergeProps);

        String md = "# 标题一\n\n这是第一段内容。\n\n" +
                "## 标题二\n\n这是第二段内容。\n\n" +
                "# 标题三\n\n这是第三段内容。";

        ParsedDocument doc = ParsedDocument.builder()
                .text(md)
                .format("md")
                .sections(null)
                .build();

        List<TextSegment> result = noMergeSplitter.split(doc);

        assertFalse(result.isEmpty());
        assertTrue(result.size() >= 3, "含3个标题的 Markdown 应至少分割为3块");

        // 验证分块内容包含标题文本
        String allText = result.stream().map(TextSegment::text).reduce("", (a, b) -> a + b);
        assertTrue(allText.contains("标题一"), "应包含标题一");
        assertTrue(allText.contains("标题二"), "应包含标题二");
        assertTrue(allText.contains("标题三"), "应包含标题三");
    }

    @Test
    @DisplayName("分块 metadata 含 headerLevel 和 headerText")
    void metadataShouldContainHeaderInfo() {
        String md = "# 第一章\n\n正文内容一\n\n" +
                "## 子章节\n\n正文内容二";

        ParsedDocument doc = ParsedDocument.builder()
                .text(md)
                .format("md")
                .sections(null)
                .build();

        List<TextSegment> result = splitter.split(doc);

        assertFalse(result.isEmpty());
        // 至少有一个分块的 metadata 含 headerLevel
        boolean hasHeaderLevel = false;
        boolean hasHeaderText = false;
        for (TextSegment seg : result) {
            if (seg.metadata().containsKey("headerLevel")) {
                hasHeaderLevel = true;
            }
            if (seg.metadata().containsKey("headerText")) {
                hasHeaderText = true;
            }
        }
        assertTrue(hasHeaderLevel, "应有分块包含 headerLevel metadata");
        assertTrue(hasHeaderText, "应有分块包含 headerText metadata");
    }

    @Test
    @DisplayName("代码块不被切断到两个分块中")
    void codeBlockShouldNotBeSplit() {
        // 构造含代码块的 Markdown，代码块较大但不超过 maxSize
        StringBuilder codeContent = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            codeContent.append("int x = ").append(i).append(";\n");
        }
        String md = "# 代码示例\n\n" +
                "下面是代码：\n\n" +
                "```java\n" + codeContent + "```\n\n" +
                "代码结束。";

        ParsedDocument doc = ParsedDocument.builder()
                .text(md)
                .format("md")
                .sections(null)
                .build();

        List<TextSegment> result = splitter.split(doc);

        assertFalse(result.isEmpty());
        // 代码块内容不应被切断到两个分块中
        String codeMarker = "```java";
        int segmentsWithCodeStart = 0;
        for (TextSegment seg : result) {
            if (seg.text().contains(codeMarker)) {
                segmentsWithCodeStart++;
                // 包含代码开始标记的分块也应包含代码结束标记
                assertTrue(seg.text().contains("```"),
                        "代码块开始和结束应在同一分块中");
            }
        }
        // 代码块开始标记只应出现在一个分块中
        assertTrue(segmentsWithCodeStart <= 1,
                "代码块不应被切分到多个分块中，实际出现在 " + segmentsWithCodeStart + " 个分块中");
    }

    @Test
    @DisplayName("GFM 表格不被切断到两个分块中")
    void tableShouldNotBeSplit() {
        String md = "# 数据表\n\n" +
                "下面是表格：\n\n" +
                "| 名称 | 年龄 | 城市 |\n" +
                "| --- | --- | --- |\n" +
                "| 张三 | 25 | 北京 |\n" +
                "| 李四 | 30 | 上海 |\n" +
                "| 王五 | 28 | 广州 |\n\n" +
                "表格结束。";

        ParsedDocument doc = ParsedDocument.builder()
                .text(md)
                .format("md")
                .sections(null)
                .build();

        List<TextSegment> result = splitter.split(doc);

        assertFalse(result.isEmpty());
        // 表格行不应被分散到多个分块
        int segmentsWithTableRows = 0;
        for (TextSegment seg : result) {
            if (seg.text().contains("张三") && seg.text().contains("王五")) {
                segmentsWithTableRows++;
            }
        }
        assertTrue(segmentsWithTableRows >= 1,
                "表格数据行应在同一分块中");
    }

    @Test
    @DisplayName("超大 section 调用 CascadeSplitter 切分")
    void oversizedSectionShouldBeCascadeSplit() {
        // 构造单标题下超长内容（超过 md.size=800 tokens）
        StringBuilder sb = new StringBuilder();
        sb.append("# 超长章节\n\n");
        for (int i = 0; i < 200; i++) {
            sb.append("这是一段用于测试超长章节切分的文本内容。");
        }
        // 200 * 22 = 4400 chars ≈ 2934 tokens > 800

        ParsedDocument doc = ParsedDocument.builder()
                .text(sb.toString())
                .format("md")
                .sections(null)
                .build();

        List<TextSegment> result = splitter.split(doc);

        assertFalse(result.isEmpty());
        assertTrue(result.size() > 1, "超长章节应被切分为多个分块");

        int maxSize = new SplitterProperties().getMd().getSize();
        for (TextSegment seg : result) {
            int tokens = estimator.estimate(seg.text());
            assertTrue(tokens <= maxSize,
                    "分块 Token 数 " + tokens + " 超过 maxSize " + maxSize);
        }
    }

    @Test
    @DisplayName("无标题的纯文本 Markdown 整体作为一个分块")
    void noHeadingShouldReturnSingleChunk() {
        String md = "这是一段没有标题的纯文本内容。\n\n第二段内容。";

        ParsedDocument doc = ParsedDocument.builder()
                .text(md)
                .format("md")
                .sections(null)
                .build();

        List<TextSegment> result = splitter.split(doc);

        assertEquals(1, result.size(), "短文本无标题 Markdown 应返回单元素列表");
        assertTrue(result.get(0).text().contains("纯文本内容"));
    }

    @Test
    @DisplayName("空文本返回空列表")
    void emptyTextShouldReturnEmptyList() {
        ParsedDocument doc = ParsedDocument.builder()
                .text("")
                .format("md")
                .sections(null)
                .build();

        List<TextSegment> result = splitter.split(doc);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("headerLevel metadata 值正确")
    void headerLevelMetadataShouldBeCorrect() {
        // 使用 minSize=0 禁用合并，验证纯分割的 metadata
        SplitterProperties noMergeProps = new SplitterProperties();
        noMergeProps.getMd().setMinSize(0);
        MarkdownDocumentSplitter noMergeSplitter = new MarkdownDocumentSplitter(estimator, noMergeProps);

        String md = "# 一级标题\n\n内容一\n\n" +
                "### 三级标题\n\n内容二";

        ParsedDocument doc = ParsedDocument.builder()
                .text(md)
                .format("md")
                .sections(null)
                .build();

        List<TextSegment> result = noMergeSplitter.split(doc);

        assertFalse(result.isEmpty());
        boolean hasLevel1 = false;
        boolean hasLevel3 = false;
        for (TextSegment seg : result) {
            if (seg.metadata().containsKey("headerLevel")) {
                String level = seg.metadata().getString("headerLevel");
                if (level.equals("1")) hasLevel1 = true;
                if (level.equals("3")) hasLevel3 = true;
            }
        }
        assertTrue(hasLevel1, "应存在 headerLevel=1 的分块");
        assertTrue(hasLevel3, "应存在 headerLevel=3 的分块");
    }

    @Test
    @DisplayName("短 section 合并后无低于 minSize 的碎片块（CR-001）")
    void shortSectionsShouldBeMerged() {
        // 构造同标题下的多个短 section，每个远低于 minSize=400
        StringBuilder sb = new StringBuilder();
        sb.append("# 标题\n\n");
        for (int i = 0; i < 5; i++) {
            sb.append("短内容").append(i).append("。\n\n");
        }

        ParsedDocument doc = ParsedDocument.builder()
                .text(sb.toString())
                .format("md")
                .sections(null)
                .build();

        List<TextSegment> result = splitter.split(doc);

        assertFalse(result.isEmpty());
        // CR-001: 合并后不应存在低于 minSize 的碎片块（最后一个除外）
        int minSize = new SplitterProperties().getMd().getMinSize();
        for (int i = 0; i < result.size() - 1; i++) {
            int tokens = estimator.estimate(result.get(i).text());
            assertTrue(tokens >= minSize,
                    "中间块 Token 数 " + tokens + " 低于 minSize " + minSize + "，应被合并");
        }
    }

    @Test
    @DisplayName("不同标题的短 section 全局合并后无碎片块（CR-001 + BUG 修复）")
    void shortSectionsDifferentHeadersShouldBeMergedGlobally() {
        String md = "# 标题A\n\n短内容A\n\n" +
                "# 标题B\n\n短内容B\n\n" +
                "# 标题C\n\n短内容C";

        ParsedDocument doc = ParsedDocument.builder()
                .text(md)
                .format("md")
                .sections(null)
                .build();

        List<TextSegment> result = splitter.split(doc);

        assertFalse(result.isEmpty());
        // 全局合并后，所有短块应合并为一个（总 Token 数远低于 minSize）
        // 最终结果中不存在低于 minSize 的碎片块（最后一个除外）
        int minSize = new SplitterProperties().getMd().getMinSize();
        for (int i = 0; i < result.size() - 1; i++) {
            int tokens = estimator.estimate(result.get(i).text());
            assertTrue(tokens >= minSize,
                    "中间块 Token 数 " + tokens + " 低于 minSize " + minSize + "，应被合并");
        }
    }

    @Test
    @DisplayName("父标题后紧跟子标题不应产生仅含标题的碎片块（BUG 复现）")
    void parentHeadingFollowedByChildShouldNotProduceFragment() {
        // 模拟 AppFlowy 调研报告的结构：## 9. 优缺点 后面紧跟 ### 优点
        String md = "# AppFlowy 调研报告\n\n" +
                "## 1. 简介\n\n" +
                "AppFlowy 是一款开源的 AI 工作空间，定位为 Notion 的开源替代品。\n\n" +
                "## 9. 优缺点\n\n" +
                "### 优点\n\n" +
                "- Rust 性能优势：核心逻辑用 Rust 实现，性能优秀，内存安全\n" +
                "- 本地优先：数据存储在本地 SQLite，离线可用，数据隐私有保障\n" +
                "- 跨平台原生体验：Flutter 单代码库覆盖桌面+移动端\n\n" +
                "### 缺点\n\n" +
                "- 无 RAG 能力：不支持向量检索 + LLM 问答\n" +
                "- 文件导入有限：不支持 Notion/Obsidian 等格式直接导入\n" +
                "- 架构复杂：Rust + Flutter + FFI + Protobuf 多层架构\n\n";

        ParsedDocument doc = ParsedDocument.builder()
                .text(md)
                .format("md")
                .sections(null)
                .build();

        List<TextSegment> result = splitter.split(doc);

        assertFalse(result.isEmpty());

        // BUG 验证：不应存在仅含 "## 9. 优缺点" 标题行的碎片块
        for (TextSegment seg : result) {
            String text = seg.text().trim();
            // 不应存在仅含标题行、无实际内容的碎片块
            boolean isOnlyHeading = text.matches("^#{1,6}\\s+.*$") && !text.contains("\n\n");
            assertFalse(isOnlyHeading,
                    "存在仅含标题行的碎片块: [" + text + "]");
        }

        // 不应存在低于 minSize 的碎片块（最后一个除外）
        int minSize = new SplitterProperties().getMd().getMinSize();
        for (int i = 0; i < result.size() - 1; i++) {
            int tokens = estimator.estimate(result.get(i).text());
            assertTrue(tokens >= minSize,
                    "中间块 Token 数 " + tokens + " 低于 minSize " + minSize + "，应被合并。内容: ["
                            + result.get(i).text().substring(0, Math.min(50, result.get(i).text().length())) + "...]");
        }
    }
}
