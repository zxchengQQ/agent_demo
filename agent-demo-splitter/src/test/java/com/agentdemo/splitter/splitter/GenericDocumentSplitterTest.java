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
 * GenericDocumentSplitter 单元测试
 * <p>
 * 验证通用回退分割器使用 CascadeSplitter 对全文进行切分，
 * 以及 supportedFormat() 返回 null、空文本返回空列表等行为。
 */
@DisplayName("通用回退分割器测试")
class GenericDocumentSplitterTest {

    private GenericDocumentSplitter splitter;
    private SplitterTokenEstimator estimator;

    @BeforeEach
    void setUp() {
        estimator = new SplitterTokenEstimator();
        SplitterProperties properties = new SplitterProperties();
        splitter = new GenericDocumentSplitter(estimator, properties);
    }

    @Test
    @DisplayName("supportedFormat 返回 null")
    void supportedFormatShouldReturnNull() {
        assertNull(splitter.supportedFormat(), "通用分割器的 supportedFormat 应为 null");
    }

    @Test
    @DisplayName("非空文本返回分块列表，每个分块 Token 数不超过 maxSize")
    void splitNonEmptyTextShouldReturnSegments() {
        // 构造超长文本（约 500 tokens）
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 70; i++) {
            sb.append("这是一段测试文本内容用于验证通用分割器的切分能力。");
        }
        ParsedDocument doc = ParsedDocument.builder()
                .text(sb.toString())
                .format(null)
                .sections(null)
                .build();

        List<TextSegment> result = splitter.split(doc);

        assertFalse(result.isEmpty(), "非空文本应返回非空分块列表");
        assertTrue(result.size() > 1, "长文本应被切分为多个分块");

        int maxSize = new SplitterProperties().getDefaultConfig().getSize();
        for (TextSegment segment : result) {
            assertNotNull(segment.text(), "每个分块的 text 不应为 null");
            assertFalse(segment.text().isEmpty(), "每个分块的 text 不应为空");
            int tokens = estimator.estimate(segment.text());
            assertTrue(tokens <= maxSize,
                    "分块 Token 数 " + tokens + " 超过 maxSize " + maxSize);
        }
    }

    @Test
    @DisplayName("空文本返回空列表")
    void splitEmptyTextShouldReturnEmptyList() {
        ParsedDocument doc = ParsedDocument.builder()
                .text("")
                .format(null)
                .sections(null)
                .build();

        List<TextSegment> result = splitter.split(doc);

        assertTrue(result.isEmpty(), "空文本应返回空列表");
    }

    @Test
    @DisplayName("短文本返回单元素列表")
    void splitShortTextShouldReturnSingleSegment() {
        ParsedDocument doc = ParsedDocument.builder()
                .text("短文本")
                .format(null)
                .sections(null)
                .build();

        List<TextSegment> result = splitter.split(doc);

        assertEquals(1, result.size(), "短文本应返回单元素列表");
        assertEquals("短文本", result.get(0).text());
    }

    @Test
    @DisplayName("null 文本返回空列表")
    void splitNullTextShouldReturnEmptyList() {
        ParsedDocument doc = ParsedDocument.builder()
                .text(null)
                .format(null)
                .sections(null)
                .build();

        List<TextSegment> result = splitter.split(doc);

        assertTrue(result.isEmpty(), "null 文本应返回空列表");
    }
}
