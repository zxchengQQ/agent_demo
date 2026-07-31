package com.agentdemo.splitter.splitter;

import com.agentdemo.splitter.config.SplitterProperties;
import com.agentdemo.splitter.loader.ParsedDocument;
import com.agentdemo.splitter.tokenizer.SplitterTokenEstimator;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DocumentSplitterRegistry 单元测试
 * <p>
 * 验证路由与回退逻辑：按 format 路由到对应专属分割器、异常回退到 GenericDocumentSplitter、
 * metadata 注入 knowledgeBaseId/documentId/format。
 */
@DisplayName("分割器路由与回退测试")
class DocumentSplitterRegistryTest {

    private DocumentSplitterRegistry registry;
    private SplitterTokenEstimator estimator;
    private SplitterProperties properties;

    @BeforeEach
    void setUp() {
        estimator = new SplitterTokenEstimator();
        properties = new SplitterProperties();

        GenericDocumentSplitter genericSplitter = new GenericDocumentSplitter(estimator, properties);
        MarkdownDocumentSplitter mdSplitter = new MarkdownDocumentSplitter(estimator, properties);
        PdfDocumentSplitter pdfSplitter = new PdfDocumentSplitter(estimator, properties);
        TxtDocumentSplitter txtSplitter = new TxtDocumentSplitter(estimator, properties);

        registry = new DocumentSplitterRegistry(
                List.of(mdSplitter, pdfSplitter, txtSplitter, genericSplitter),
                genericSplitter,
                properties
        );
    }

    @Test
    @DisplayName("format=md 路由到 MarkdownDocumentSplitter")
    void splitMdShouldRouteToMarkdownSplitter() {
        String md = "# 标题\n\n正文内容";
        ParsedDocument doc = ParsedDocument.builder()
                .text(md)
                .format("md")
                .sections(null)
                .build();

        List<TextSegment> result = registry.split(doc, "kb1", "doc1", "测试文件.md");

        assertFalse(result.isEmpty());
        // 验证 metadata
        for (TextSegment seg : result) {
            assertEquals("kb1", seg.metadata().getString("knowledgeBaseId"));
            assertEquals("doc1", seg.metadata().getString("documentId"));
            assertEquals("md", seg.metadata().getString("format"));
            assertEquals("测试文件.md", seg.metadata().getString("fileName"));
        }
    }

    @Test
    @DisplayName("format=pdf 路由到 PdfDocumentSplitter")
    void splitPdfShouldRouteToPdfSplitter() {
        ParsedDocument doc = ParsedDocument.builder()
                .text("PDF全文内容")
                .format("pdf")
                .sections(null)
                .build();

        List<TextSegment> result = registry.split(doc, "kb1", "doc1", "手册.pdf");

        assertFalse(result.isEmpty());
        for (TextSegment seg : result) {
            assertEquals("kb1", seg.metadata().getString("knowledgeBaseId"));
            assertEquals("doc1", seg.metadata().getString("documentId"));
            assertEquals("pdf", seg.metadata().getString("format"));
            assertEquals("手册.pdf", seg.metadata().getString("fileName"));
        }
    }

    @Test
    @DisplayName("format=txt 路由到 TxtDocumentSplitter")
    void splitTxtShouldRouteToTxtSplitter() {
        ParsedDocument doc = ParsedDocument.builder()
                .text("TXT文本内容")
                .format("txt")
                .sections(null)
                .build();

        List<TextSegment> result = registry.split(doc, "kb1", "doc1", "笔记.txt");

        assertFalse(result.isEmpty());
        for (TextSegment seg : result) {
            assertEquals("kb1", seg.metadata().getString("knowledgeBaseId"));
            assertEquals("doc1", seg.metadata().getString("documentId"));
            assertEquals("txt", seg.metadata().getString("format"));
            assertEquals("笔记.txt", seg.metadata().getString("fileName"));
        }
    }

    @Test
    @DisplayName("format=unknown 使用 GenericDocumentSplitter")
    void splitUnknownFormatShouldUseGenericSplitter() {
        ParsedDocument doc = ParsedDocument.builder()
                .text("未知格式文本内容")
                .format("unknown")
                .sections(null)
                .build();

        List<TextSegment> result = registry.split(doc, "kb1", "doc1", "文件.unknown");

        assertFalse(result.isEmpty());
        for (TextSegment seg : result) {
            assertEquals("kb1", seg.metadata().getString("knowledgeBaseId"));
            assertEquals("doc1", seg.metadata().getString("documentId"));
            assertEquals("unknown", seg.metadata().getString("format"));
            assertEquals("文件.unknown", seg.metadata().getString("fileName"));
        }
    }

    @Test
    @DisplayName("专属分割器异常时回退到 GenericDocumentSplitter，结果非空")
    void splitterExceptionShouldFallbackToGeneric() {
        // 构造含 FailingSplitter 的 Registry
        FailingSplitter failingSplitter = new FailingSplitter();
        GenericDocumentSplitter genericSplitter = new GenericDocumentSplitter(estimator, properties);

        DocumentSplitterRegistry registryWithFailing = new DocumentSplitterRegistry(
                List.of(failingSplitter, genericSplitter),
                genericSplitter,
                properties
        );

        ParsedDocument doc = ParsedDocument.builder()
                .text("回退测试文本内容")
                .format("failing")
                .sections(null)
                .build();

        List<TextSegment> result = registryWithFailing.split(doc, "kb1", "doc1", "fallback.failing");

        assertFalse(result.isEmpty(), "专属分割器异常时应回退到通用分割器，结果非空");
        for (TextSegment seg : result) {
            assertEquals("kb1", seg.metadata().getString("knowledgeBaseId"));
            assertEquals("doc1", seg.metadata().getString("documentId"));
            assertEquals("fallback.failing", seg.metadata().getString("fileName"));
        }
    }

    @Test
    @DisplayName("返回的 TextSegment metadata 含 knowledgeBaseId、documentId、format、fileName")
    void metadataShouldContainAllFields() {
        ParsedDocument doc = ParsedDocument.builder()
                .text("metadata测试")
                .format("txt")
                .sections(null)
                .build();

        List<TextSegment> result = registry.split(doc, "myKb", "myDoc", "元数据测试.txt");

        assertFalse(result.isEmpty());
        TextSegment seg = result.get(0);
        assertEquals("myKb", seg.metadata().getString("knowledgeBaseId"));
        assertEquals("myDoc", seg.metadata().getString("documentId"));
        assertEquals("txt", seg.metadata().getString("format"));
        assertEquals("元数据测试.txt", seg.metadata().getString("fileName"));
    }

    @Test
    @DisplayName("MD 分割保留原有 headerLevel metadata")
    void mdSplitShouldPreserveHeaderMetadata() {
        String md = "# 标题\n\n正文";
        ParsedDocument doc = ParsedDocument.builder()
                .text(md)
                .format("md")
                .sections(null)
                .build();

        List<TextSegment> result = registry.split(doc, "kb1", "doc1", "header.md");

        assertFalse(result.isEmpty());
        TextSegment seg = result.get(0);
        // 既有原有 headerLevel，又有新增的 knowledgeBaseId 等
        assertTrue(seg.metadata().containsKey("headerLevel"), "应保留 headerLevel");
        assertEquals("kb1", seg.metadata().getString("knowledgeBaseId"));
        assertEquals("doc1", seg.metadata().getString("documentId"));
        assertEquals("md", seg.metadata().getString("format"));
        assertEquals("header.md", seg.metadata().getString("fileName"));
    }

    @Test
    @DisplayName("空文本返回空列表")
    void emptyTextShouldReturnEmptyList() {
        ParsedDocument doc = ParsedDocument.builder()
                .text("")
                .format("txt")
                .sections(null)
                .build();

        List<TextSegment> result = registry.split(doc, "kb1", "doc1", "empty.txt");

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("CR-002: fileName 为 null 时不注入 fileName metadata")
    void splitWithNullFileNameShouldNotInjectFileNameMetadata() {
        ParsedDocument doc = ParsedDocument.builder()
                .text("null fileName测试")
                .format("txt")
                .sections(null)
                .build();

        List<TextSegment> result = registry.split(doc, "kb1", "doc1", null);

        assertFalse(result.isEmpty());
        TextSegment seg = result.get(0);
        assertEquals("kb1", seg.metadata().getString("knowledgeBaseId"));
        assertEquals("doc1", seg.metadata().getString("documentId"));
        assertEquals("txt", seg.metadata().getString("format"));
        assertFalse(seg.metadata().containsKey("fileName"), "fileName 为 null 时不应注入 metadata");
    }

    /**
     * 测试用：总是抛异常的分割器
     */
    private static class FailingSplitter implements TypedDocumentSplitter {
        @Override
        public String supportedFormat() {
            return "failing";
        }

        @Override
        public List<TextSegment> split(ParsedDocument parsedDocument) {
            throw new RuntimeException("Intentional failure for testing");
        }

        @Override
        public List<TextSegment> split(Document document) {
            throw new RuntimeException("Intentional failure for testing");
        }
    }
}
