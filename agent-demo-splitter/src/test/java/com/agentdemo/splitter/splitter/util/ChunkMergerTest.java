package com.agentdemo.splitter.splitter.util;

import com.agentdemo.splitter.tokenizer.SplitterTokenEstimator;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ChunkMerger 单元测试（CR-001 新增）
 * <p>
 * 验证分割后合并过短块逻辑：全局合并和分组合并（按 metadata key 分组）。
 */
class ChunkMergerTest {

    private ChunkMerger chunkMerger;
    private SplitterTokenEstimator estimator;

    @BeforeEach
    void setUp() {
        estimator = new SplitterTokenEstimator();
        chunkMerger = new ChunkMerger(estimator);
    }

    // ===== 全局合并 =====

    @Test
    void 空列表返回空列表() {
        List<TextSegment> result = chunkMerger.merge(List.of(), 50, 100);
        assertTrue(result.isEmpty());
    }

    @Test
    void 单元素列表不合并() {
        TextSegment seg = TextSegment.from("短文本", new Metadata());
        List<TextSegment> result = chunkMerger.merge(List.of(seg), 50, 100);
        assertEquals(1, result.size());
        assertEquals("短文本", result.get(0).text());
    }

    @Test
    void 两个短块合并后不超过maxSize则合并() {
        // 每块约 3 tokens，minSize=50，合并后约 6 tokens < 100
        TextSegment seg1 = TextSegment.from("第一段短文本", new Metadata());
        TextSegment seg2 = TextSegment.from("第二段短文本", new Metadata());
        List<TextSegment> result = chunkMerger.merge(List.of(seg1, seg2), 50, 100);
        assertEquals(1, result.size(), "两个短块应合并为一个");
        assertTrue(result.get(0).text().contains("第一段短文本"));
        assertTrue(result.get(0).text().contains("第二段短文本"));
    }

    @Test
    void 两个短块合并后超过maxSize则不合并() {
        // 构造两个块，各自 < minSize 但合并后 > maxSize
        StringBuilder sb1 = new StringBuilder();
        StringBuilder sb2 = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb1.append("这是第一段较长的文本内容");
            sb2.append("这是第二段较长的文本内容");
        }
        // 每块约 267 tokens，minSize=200，maxSize=300
        // 各自 < 200? 不，267 > 200。需要调整。
        // 让每块约 150 tokens < minSize=200，合并后约 300 = maxSize
        // 再大一点让合并后 > maxSize
        // 用 minSize=200, maxSize=250，每块约 150 tokens < 200，合并后 300 > 250
        TextSegment seg1 = TextSegment.from(sb1.toString(), new Metadata());
        TextSegment seg2 = TextSegment.from(sb2.toString(), new Metadata());
        int tokens1 = estimator.estimate(sb1.toString());
        int tokens2 = estimator.estimate(sb2.toString());
        // 确保每块 < minSize 且合并后 > maxSize
        int minSize = Math.max(tokens1, tokens2) + 1;  // minSize 比两块都大
        int maxSize = tokens1 + tokens2 - 1;  // maxSize 比合并后小

        List<TextSegment> result = chunkMerger.merge(List.of(seg1, seg2), minSize, maxSize);
        assertEquals(2, result.size(), "合并后超过 maxSize 则不合并");
    }

    @Test
    void 长块加短块合并后不超过maxSize则合并() {
        // 长块 >= minSize，短块 < minSize，合并后 <= maxSize
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            longText.append("这是一段正常长度的文本内容");
        }
        // longText 约 87 tokens
        String shortText = "短";
        // shortText 约 1 token

        int longTokens = estimator.estimate(longText.toString());
        int shortTokens = estimator.estimate(shortText);
        int minSize = 50;  // longText >= 50, shortText < 50
        int maxSize = longTokens + shortTokens + 10;  // 合并后 <= maxSize

        TextSegment seg1 = TextSegment.from(longText.toString(), new Metadata());
        TextSegment seg2 = TextSegment.from(shortText, new Metadata());
        List<TextSegment> result = chunkMerger.merge(List.of(seg1, seg2), minSize, maxSize);
        assertEquals(1, result.size(), "长块+短块合并后不超限应合并");
    }

    @Test
    void 长块加短块合并后超过maxSize则不合并() {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            longText.append("这是一段正常长度的文本内容");
        }
        // longText 约 173 tokens
        String shortText = "短";

        int longTokens = estimator.estimate(longText.toString());
        int minSize = 50;
        int maxSize = longTokens;  // maxSize = longTokens，加任何内容都会超限

        TextSegment seg1 = TextSegment.from(longText.toString(), new Metadata());
        TextSegment seg2 = TextSegment.from(shortText, new Metadata());
        List<TextSegment> result = chunkMerger.merge(List.of(seg1, seg2), minSize, maxSize);
        assertEquals(2, result.size(), "合并后超过 maxSize 则不合并");
    }

    @Test
    void 中间短块与前一块合并() {
        // [长, 短, 长]，中间短块与前一块合并
        StringBuilder longText1 = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            longText1.append("这是第一段长文本内容");
        }
        String shortText = "短";
        StringBuilder longText2 = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            longText2.append("这是第三段长文本内容");
        }

        int longTokens = estimator.estimate(longText1.toString());
        int minSize = 50;
        int maxSize = longTokens + 10;  // 长块+短块不超限

        TextSegment seg1 = TextSegment.from(longText1.toString(), new Metadata());
        TextSegment seg2 = TextSegment.from(shortText, new Metadata());
        TextSegment seg3 = TextSegment.from(longText2.toString(), new Metadata());
        List<TextSegment> result = chunkMerger.merge(List.of(seg1, seg2, seg3), minSize, maxSize);
        assertEquals(2, result.size(), "中间短块应与前一块合并，结果为 2 块");
    }

    @Test
    void 合并后文本用换行符连接() {
        TextSegment seg1 = TextSegment.from("第一段", new Metadata());
        TextSegment seg2 = TextSegment.from("第二段", new Metadata());
        List<TextSegment> result = chunkMerger.merge(List.of(seg1, seg2), 50, 100);
        assertEquals(1, result.size());
        assertEquals("第一段\n第二段", result.get(0).text());
    }

    @Test
    void 合并后metadata保留前一块的值() {
        Metadata meta1 = new Metadata().put("pageNumber", "1");
        Metadata meta2 = new Metadata().put("pageNumber", "2");
        TextSegment seg1 = TextSegment.from("第一段短文本", meta1);
        TextSegment seg2 = TextSegment.from("第二段短文本", meta2);
        List<TextSegment> result = chunkMerger.merge(List.of(seg1, seg2), 50, 100);
        assertEquals(1, result.size());
        assertEquals("1", result.get(0).metadata().getString("pageNumber"));
    }

    // ===== 分组合并 =====

    @Test
    void 分组合并不同组不合并() {
        // 两个块 groupByKey 不同，均过短，不合并
        Metadata meta1 = new Metadata().put("pageNumber", "1");
        Metadata meta2 = new Metadata().put("pageNumber", "2");
        TextSegment seg1 = TextSegment.from("短文本一", meta1);
        TextSegment seg2 = TextSegment.from("短文本二", meta2);
        List<TextSegment> result = chunkMerger.merge(List.of(seg1, seg2), 50, 100, "pageNumber");
        assertEquals(2, result.size(), "不同组的块不应合并");
    }

    @Test
    void 分组合并同组合并() {
        Metadata meta1 = new Metadata().put("pageNumber", "1");
        Metadata meta2 = new Metadata().put("pageNumber", "1");
        TextSegment seg1 = TextSegment.from("短文本一", meta1);
        TextSegment seg2 = TextSegment.from("短文本二", meta2);
        List<TextSegment> result = chunkMerger.merge(List.of(seg1, seg2), 50, 100, "pageNumber");
        assertEquals(1, result.size(), "同组的短块应合并");
        assertEquals("1", result.get(0).metadata().getString("pageNumber"));
    }

    @Test
    void 分组合并跨组边界不合并() {
        // [组1短, 组1短, 组2短, 组2短] -> 组1合并, 组2合并, 结果2块
        Metadata meta1 = new Metadata().put("headerText", "标题A");
        Metadata meta2 = new Metadata().put("headerText", "标题A");
        Metadata meta3 = new Metadata().put("headerText", "标题B");
        Metadata meta4 = new Metadata().put("headerText", "标题B");
        TextSegment seg1 = TextSegment.from("短文本一", meta1);
        TextSegment seg2 = TextSegment.from("短文本二", meta2);
        TextSegment seg3 = TextSegment.from("短文本三", meta3);
        TextSegment seg4 = TextSegment.from("短文本四", meta4);
        List<TextSegment> result = chunkMerger.merge(List.of(seg1, seg2, seg3, seg4), 50, 100, "headerText");
        assertEquals(2, result.size(), "跨组不合并，每组各合并为1块");
    }

    @Test
    void 最后一个分块允许低于minSize() {
        // [长, 短] -> 短块无法再合并，允许低于 minSize
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            longText.append("这是第一段长文本内容");
        }
        int longTokens = estimator.estimate(longText.toString());
        int minSize = 50;
        int maxSize = longTokens;  // maxSize = longTokens，加任何内容都会超限

        TextSegment seg1 = TextSegment.from(longText.toString(), new Metadata());
        TextSegment seg2 = TextSegment.from("短", new Metadata());
        List<TextSegment> result = chunkMerger.merge(List.of(seg1, seg2), minSize, maxSize);
        assertEquals(2, result.size(), "合并后超限不合并，最后一块允许低于 minSize");
        int lastTokens = estimator.estimate(result.get(1).text());
        assertTrue(lastTokens < minSize, "最后一块应低于 minSize");
    }

    @Test
    void 分组合并groupByKey为null时按全局合并() {
        // groupByKey 传 null 时等同于全局合并
        Metadata meta1 = new Metadata().put("pageNumber", "1");
        Metadata meta2 = new Metadata().put("pageNumber", "2");
        TextSegment seg1 = TextSegment.from("短文本一", meta1);
        TextSegment seg2 = TextSegment.from("短文本二", meta2);
        List<TextSegment> result = chunkMerger.merge(List.of(seg1, seg2), 50, 100, null);
        assertEquals(1, result.size(), "groupByKey 为 null 时应全局合并");
    }
}
