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
 * TxtDocumentSplitter 单元测试
 * <p>
 * 验证 TXT 专属分割器调用 CascadeSplitter 对全文进行多级递归切分。
 */
@DisplayName("TXT专属分割器测试")
class TxtDocumentSplitterTest {

    private TxtDocumentSplitter splitter;
    private SplitterTokenEstimator estimator;

    @BeforeEach
    void setUp() {
        estimator = new SplitterTokenEstimator();
        SplitterProperties properties = new SplitterProperties();
        splitter = new TxtDocumentSplitter(estimator, properties);
    }

    @Test
    @DisplayName("supportedFormat 返回 txt")
    void supportedFormatShouldReturnTxt() {
        assertEquals("txt", splitter.supportedFormat());
    }

    @Test
    @DisplayName("含多个段落的长文本按段落优先切分")
    void splitLongTextWithParagraphs() {
        StringBuilder para1 = new StringBuilder();
        StringBuilder para2 = new StringBuilder();
        for (int i = 0; i < 150; i++) {
            para1.append("第一段文本内容");
            para2.append("第二段文本内容");
        }
        String text = para1 + "\n\n" + para2;

        ParsedDocument doc = ParsedDocument.builder()
                .text(text)
                .format("txt")
                .sections(null)
                .build();

        List<TextSegment> result = splitter.split(doc);

        assertFalse(result.isEmpty());
        assertTrue(result.size() > 1, "长文本应被切分为多个分块");

        int maxSize = new SplitterProperties().getTxt().getSize();
        for (TextSegment seg : result) {
            int tokens = estimator.estimate(seg.text());
            assertTrue(tokens <= maxSize,
                    "分块 Token 数 " + tokens + " 超过 maxSize " + maxSize);
        }
    }

    @Test
    @DisplayName("含句子的长文本按句子切分")
    void splitLongTextWithSentences() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("这是一个用于测试句子切分的长句子。");
        }

        ParsedDocument doc = ParsedDocument.builder()
                .text(sb.toString())
                .format("txt")
                .sections(null)
                .build();

        List<TextSegment> result = splitter.split(doc);

        assertFalse(result.isEmpty());
        assertTrue(result.size() > 1, "长文本应被切分为多个分块");

        int maxSize = new SplitterProperties().getTxt().getSize();
        for (TextSegment seg : result) {
            int tokens = estimator.estimate(seg.text());
            assertTrue(tokens <= maxSize,
                    "分块 Token 数 " + tokens + " 超过 maxSize " + maxSize);
        }
    }

    @Test
    @DisplayName("短文本返回单元素列表")
    void splitShortTextShouldReturnSingleSegment() {
        ParsedDocument doc = ParsedDocument.builder()
                .text("短文本")
                .format("txt")
                .sections(null)
                .build();

        List<TextSegment> result = splitter.split(doc);

        assertEquals(1, result.size());
        assertEquals("短文本", result.get(0).text());
    }

    @Test
    @DisplayName("空文本返回空列表")
    void emptyTextShouldReturnEmptyList() {
        ParsedDocument doc = ParsedDocument.builder()
                .text("")
                .format("txt")
                .sections(null)
                .build();

        List<TextSegment> result = splitter.split(doc);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("null 文本返回空列表")
    void nullTextShouldReturnEmptyList() {
        ParsedDocument doc = ParsedDocument.builder()
                .text(null)
                .format("txt")
                .sections(null)
                .build();

        List<TextSegment> result = splitter.split(doc);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("切分合并后无低于 minSize 的碎片块（CR-001）")
    void mergedResultShouldNotHaveShortBlocks() {
        // 构造含句号的长文本，切分后产生短块，合并后无碎片
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("这是用于测试合并的句子。");
        }
        // 200 * 12 = 2400 chars ≈ 1600 tokens > maxSize=1000

        ParsedDocument doc = ParsedDocument.builder()
                .text(sb.toString())
                .format("txt")
                .sections(null)
                .build();

        List<TextSegment> result = splitter.split(doc);

        assertFalse(result.isEmpty());
        // CR-001: 合并后不应存在低于 minSize 的碎片块（最后一个除外）
        int minSize = new SplitterProperties().getTxt().getMinSize();
        for (int i = 0; i < result.size() - 1; i++) {
            int tokens = estimator.estimate(result.get(i).text());
            assertTrue(tokens >= minSize,
                    "中间块 Token 数 " + tokens + " 低于 minSize " + minSize + "，应被合并");
        }
    }
}
