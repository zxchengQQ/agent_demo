package com.agentdemo.splitter.splitter.util;

import com.agentdemo.splitter.tokenizer.SplitterTokenEstimator;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayList;
import java.util.List;

/**
 * 分割后合并过短块工具（CR-001 新增）
 * <p>
 * 业务含义：各分割器完成第一轮分割后，对低于 minSize 的过短分块执行合并，
 * 避免 chunk 太小太碎导致检索精度下降和向量存储碎片化。
 * <p>
 * 支持两种合并模式：
 * <ul>
 *   <li>全局合并（TXT/Generic 使用）：所有分块参与合并，无分组约束</li>
 *   <li>分组合并（MD/PDF 使用）：按指定 metadata key 分组，仅同组内短块合并</li>
 * </ul>
 */
public class ChunkMerger {

    private final SplitterTokenEstimator tokenEstimator;

    public ChunkMerger(SplitterTokenEstimator tokenEstimator) {
        this.tokenEstimator = tokenEstimator;
    }

    /**
     * 全局合并：所有分块参与合并，无分组约束
     *
     * @param segments 分割后的分块列表
     * @param minSize  最小分块大小（Token 数），低于此值触发合并
     * @param maxSize  最大分块大小（Token 数），合并后不超过此值
     * @return 合并后的分块列表
     */
    public List<TextSegment> merge(List<TextSegment> segments, int minSize, int maxSize) {
        return merge(segments, minSize, maxSize, null);
    }

    /**
     * 分组合并：按指定 metadata key 分组，仅同组内短块合并
     *
     * @param segments   分割后的分块列表
     * @param minSize    最小分块大小（Token 数），低于此值触发合并
     * @param maxSize    最大分块大小（Token 数），合并后不超过此值
     * @param groupByKey metadata 中的键名（如 "pageNumber"、"headerText"），null 表示全局合并
     * @return 合并后的分块列表
     */
    public List<TextSegment> merge(List<TextSegment> segments, int minSize, int maxSize, String groupByKey) {
        if (segments == null || segments.isEmpty()) {
            return List.of();
        }

        List<TextSegment> merged = new ArrayList<>();
        merged.add(segments.get(0));

        for (int i = 1; i < segments.size(); i++) {
            TextSegment current = segments.get(i);
            TextSegment prev = merged.get(merged.size() - 1);

            int currentTokens = tokenEstimator.estimate(current.text());
            int prevTokens = tokenEstimator.estimate(prev.text());

            // 分组检查：groupByKey 不为 null 时，仅同组才合并
            if (groupByKey != null && !sameGroup(prev.metadata(), current.metadata(), groupByKey)) {
                merged.add(current);
                continue;
            }

            // 合并条件：当前块或前一块过短，且合并后不超限
            if ((currentTokens < minSize || prevTokens < minSize)
                    && (prevTokens + currentTokens <= maxSize)) {
                // 合并：当前块内容追加到前一块尾部，metadata 保留前一块的值
                String combinedText = prev.text() + "\n" + current.text();
                TextSegment combined = TextSegment.from(combinedText, prev.metadata());
                merged.set(merged.size() - 1, combined);
            } else {
                merged.add(current);
            }
        }

        return merged;
    }

    /**
     * 检查两个 metadata 在指定 key 上的值是否相同
     */
    private boolean sameGroup(Metadata meta1, Metadata meta2, String key) {
        String val1 = meta1.getString(key);
        String val2 = meta2.getString(key);
        if (val1 == null && val2 == null) {
            return true;
        }
        return val1 != null && val1.equals(val2);
    }
}
