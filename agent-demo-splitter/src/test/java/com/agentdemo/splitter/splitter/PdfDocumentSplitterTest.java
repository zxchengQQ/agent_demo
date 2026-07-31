package com.agentdemo.splitter.splitter;

import com.agentdemo.splitter.config.SplitterProperties;
import com.agentdemo.splitter.loader.DocumentSection;
import com.agentdemo.splitter.loader.ParsedDocument;
import com.agentdemo.splitter.tokenizer.SplitterTokenEstimator;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PdfDocumentSplitter 单元测试
 * <p>
 * 验证 PDF 专属分割器按页分割、页码 metadata、单页超限 CascadeSplitter 切分、
 * sections null 回退全文、sections 空列表返回空列表等行为。
 */
@DisplayName("PDF专属分割器测试")
class PdfDocumentSplitterTest {

    private PdfDocumentSplitter splitter;
    private SplitterTokenEstimator estimator;

    @BeforeEach
    void setUp() {
        estimator = new SplitterTokenEstimator();
        SplitterProperties properties = new SplitterProperties();
        splitter = new PdfDocumentSplitter(estimator, properties);
    }

    @Test
    @DisplayName("supportedFormat 返回 pdf")
    void supportedFormatShouldReturnPdf() {
        assertEquals("pdf", splitter.supportedFormat());
    }

    @Test
    @DisplayName("3 页 PDF 的 sections 产生分块，每个分块 metadata 含 pageNumber")
    void splitMultiPagePdfShouldHavePageNumber() {
        List<DocumentSection> sections = new ArrayList<>();
        sections.add(createSection("第一页内容", "1"));
        sections.add(createSection("第二页内容", "2"));
        sections.add(createSection("第三页内容", "3"));

        ParsedDocument doc = ParsedDocument.builder()
                .text("第一页内容第二页内容第三页内容")
                .format("pdf")
                .sections(sections)
                .build();

        List<TextSegment> result = splitter.split(doc);

        assertFalse(result.isEmpty());
        for (TextSegment seg : result) {
            assertTrue(seg.metadata().containsKey("pageNumber"),
                    "每个分块应包含 pageNumber metadata");
        }
    }

    @Test
    @DisplayName("不跨页拼接内容：每页独立分块")
    void shouldNotCrossPageContent() {
        List<DocumentSection> sections = new ArrayList<>();
        sections.add(createSection("页面一独立内容", "1"));
        sections.add(createSection("页面二独立内容", "2"));

        ParsedDocument doc = ParsedDocument.builder()
                .text("页面一独立内容页面二独立内容")
                .format("pdf")
                .sections(sections)
                .build();

        List<TextSegment> result = splitter.split(doc);

        assertFalse(result.isEmpty());
        // 验证每页内容不被混合到同一分块
        for (TextSegment seg : result) {
            String text = seg.text();
            // 分块不应同时包含两页的独有内容
            boolean hasPage1 = text.contains("页面一");
            boolean hasPage2 = text.contains("页面二");
            assertFalse(hasPage1 && hasPage2, "分块不应同时包含两页内容");
        }
    }

    @Test
    @DisplayName("单页内容超过 maxSize 时调用 CascadeSplitter 切分")
    void oversizedPageShouldBeCascadeSplit() {
        // 构造超长单页内容
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("这是一页超长的PDF内容文本。");
        }
        // 200 * 16 = 3200 chars ≈ 2134 tokens > pdf.size=1200

        List<DocumentSection> sections = new ArrayList<>();
        sections.add(createSection(sb.toString(), "1"));

        ParsedDocument doc = ParsedDocument.builder()
                .text(sb.toString())
                .format("pdf")
                .sections(sections)
                .build();

        List<TextSegment> result = splitter.split(doc);

        assertFalse(result.isEmpty());
        assertTrue(result.size() > 1, "超长单页应被切分为多个分块");

        int maxSize = new SplitterProperties().getPdf().getSize();
        for (TextSegment seg : result) {
            int tokens = estimator.estimate(seg.text());
            assertTrue(tokens <= maxSize,
                    "分块 Token 数 " + tokens + " 超过 maxSize " + maxSize);
            // 超长页切分后的子分块 pageNumber 应相同
            assertEquals("1", seg.metadata().getString("pageNumber"),
                    "超长页切分后子分块的 pageNumber 应相同");
        }
    }

    @Test
    @DisplayName("sections 为空列表返回空列表")
    void emptySectionsShouldReturnEmptyList() {
        ParsedDocument doc = ParsedDocument.builder()
                .text("some text")
                .format("pdf")
                .sections(List.of())
                .build();

        List<TextSegment> result = splitter.split(doc);

        assertTrue(result.isEmpty(), "空 sections 应返回空列表");
    }

    @Test
    @DisplayName("sections 为 null 时回退使用全文 text 切分")
    void nullSectionsShouldFallbackToFullText() {
        String text = "这是全文文本内容，当 sections 为 null 时使用。";
        ParsedDocument doc = ParsedDocument.builder()
                .text(text)
                .format("pdf")
                .sections(null)
                .build();

        List<TextSegment> result = splitter.split(doc);

        assertFalse(result.isEmpty(), "sections 为 null 时应回退使用全文");
        assertEquals(text, result.get(0).text(), "回退全文切分应保留原文");
    }

    @Test
    @DisplayName("空文本返回空列表")
    void emptyTextShouldReturnEmptyList() {
        ParsedDocument doc = ParsedDocument.builder()
                .text("")
                .format("pdf")
                .sections(null)
                .build();

        List<TextSegment> result = splitter.split(doc);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("多页 PDF 中每页都有对应的分块")
    void multiPagePdfAllPagesShouldHaveChunks() {
        List<DocumentSection> sections = new ArrayList<>();
        sections.add(createSection("第一页", "1"));
        sections.add(createSection("第二页", "2"));
        sections.add(createSection("第三页", "3"));

        ParsedDocument doc = ParsedDocument.builder()
                .text("第一页第二页第三页")
                .format("pdf")
                .sections(sections)
                .build();

        List<TextSegment> result = splitter.split(doc);

        assertFalse(result.isEmpty());
        // 验证存在 pageNumber=1、2、3 的分块
        boolean hasPage1 = false, hasPage2 = false, hasPage3 = false;
        for (TextSegment seg : result) {
            String pageNum = seg.metadata().getString("pageNumber");
            if ("1".equals(pageNum)) hasPage1 = true;
            if ("2".equals(pageNum)) hasPage2 = true;
            if ("3".equals(pageNum)) hasPage3 = true;
        }
        assertTrue(hasPage1, "应存在 pageNumber=1 的分块");
        assertTrue(hasPage2, "应存在 pageNumber=2 的分块");
        assertTrue(hasPage3, "应存在 pageNumber=3 的分块");
    }

    // ===== 辅助方法 =====

    private DocumentSection createSection(String text, String pageNumber) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("pageNumber", pageNumber);
        return DocumentSection.builder()
                .text(text)
                .metadata(metadata)
                .build();
    }

    // ===== CR-001 测试 =====

    @Test
    @DisplayName("不同页的短块不合并（CR-001）")
    void shortBlocksDifferentPagesShouldNotMerge() {
        // 每页内容很短（< minSize=600），但不同页不合并
        List<DocumentSection> sections = new ArrayList<>();
        sections.add(createSection("短内容一", "1"));
        sections.add(createSection("短内容二", "2"));
        sections.add(createSection("短内容三", "3"));

        ParsedDocument doc = ParsedDocument.builder()
                .text("短内容一短内容二短内容三")
                .format("pdf")
                .sections(sections)
                .build();

        List<TextSegment> result = splitter.split(doc);

        assertFalse(result.isEmpty());
        // 不同页的短块不合并，每页保留独立分块
        long distinctPages = result.stream()
                .map(seg -> seg.metadata().getString("pageNumber"))
                .distinct()
                .count();
        assertTrue(distinctPages >= 3, "不同页的短块不应合并，应保留3个不同 pageNumber");
    }

    @Test
    @DisplayName("同页内短块合并后无低于 minSize 的碎片块（CR-001）")
    void samePageShortBlocksShouldBeMerged() {
        // 构造单页超长内容（> maxSize=1200），含句号便于 CascadeSplitter 切分
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("这是PDF单页的一段较长文本内容。");
        }
        // 200 * 18 = 3600 chars ≈ 2400 tokens > maxSize=1200

        List<DocumentSection> sections = new ArrayList<>();
        sections.add(createSection(sb.toString(), "1"));

        ParsedDocument doc = ParsedDocument.builder()
                .text(sb.toString())
                .format("pdf")
                .sections(sections)
                .build();

        List<TextSegment> result = splitter.split(doc);

        assertFalse(result.isEmpty());
        // CR-001: 同页内短块合并后，不应有低于 minSize 的碎片块（最后一个除外）
        int minSize = new SplitterProperties().getPdf().getMinSize();
        for (int i = 0; i < result.size() - 1; i++) {
            int tokens = estimator.estimate(result.get(i).text());
            assertTrue(tokens >= minSize,
                    "同页中间块 Token 数 " + tokens + " 低于 minSize " + minSize + "，应被合并");
        }
        // 所有块 pageNumber 相同
        for (TextSegment seg : result) {
            assertEquals("1", seg.metadata().getString("pageNumber"));
        }
    }
}
