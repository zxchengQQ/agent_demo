package com.agentdemo.splitter.splitter.util;

import com.agentdemo.splitter.tokenizer.SplitterTokenEstimator;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 多级优先级级联切分工具
 * <p>
 * 业务含义：当语义单元（段落、章节、页面）超过 maxSize 时，按优先级递进切分，
 * 尽量保持语义完整性。切分优先级：段落(\n\n) -> 句子(。！？. ! ?) -> 行(\n) -> Token滑动窗口。
 * <p>
 * CR-001 变更：移除 mergeShortBlocks 合并逻辑，CascadeSplitter 仅负责纯切分。
 * 合并过短块的职责由独立的 ChunkMerger 工具类承担，在各分割器最终输出前统一调用。
 */
@Slf4j
public class CascadeSplitter {

    private final SplitterTokenEstimator tokenEstimator;

    public CascadeSplitter(SplitterTokenEstimator tokenEstimator) {
        this.tokenEstimator = tokenEstimator;
    }

    /**
     * 多级级联切分
     *
     * @param text     待切分文本
     * @param maxSize  单块最大 Token 数
     * @param overlap  重叠 Token 数
     * @return 切分后的文本块列表，空文本返回空列表
     */
    public List<String> split(String text, int maxSize, int overlap) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        int totalTokens = tokenEstimator.estimate(text);
        // 短文本直接返回单元素列表
        if (totalTokens <= maxSize) {
            return List.of(text);
        }

        // Level 1~4 级联切分
        List<String> rawBlocks = level1Split(text, maxSize, overlap);

        // CR-001: 移除 mergeShortBlocks 调用，合并由 ChunkMerger 统一处理
        return rawBlocks;
    }

    /**
     * Level 1：按段落分隔符 \n\n 切分
     */
    private List<String> level1Split(String text, int maxSize, int overlap) {
        String[] paragraphs = text.split("(?<=\n\n)");
        List<String> result = new ArrayList<>();
        for (String para : paragraphs) {
            if (para.isBlank()) continue;
            if (tokenEstimator.estimate(para) <= maxSize) {
                result.add(para);
            } else {
                result.addAll(level2Split(para, maxSize, overlap));
            }
        }
        return result;
    }

    /**
     * Level 2：按句子分隔符切分（中文。！？ + 英文 . ! ? 后接空格或行尾）
     */
    private List<String> level2Split(String text, int maxSize, int overlap) {
        // 匹配中文句号/感叹号/问号，或英文句号/感叹号/问号后跟空格/换行
        Pattern sentencePattern = Pattern.compile("(?<=[。！？])|(?<=[.!?](?=\\s|$))");
        String[] sentences = sentencePattern.split(text);
        List<String> result = new ArrayList<>();
        for (String sentence : sentences) {
            if (sentence.isBlank()) continue;
            if (tokenEstimator.estimate(sentence) <= maxSize) {
                result.add(sentence);
            } else {
                result.addAll(level3Split(sentence, maxSize, overlap));
            }
        }
        return result;
    }

    /**
     * Level 3：按行分隔符 \n 切分
     */
    private List<String> level3Split(String text, int maxSize, int overlap) {
        String[] lines = text.split("(?<=\n)");
        List<String> result = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank()) continue;
            if (tokenEstimator.estimate(line) <= maxSize) {
                result.add(line);
            } else {
                result.addAll(level4Split(line, maxSize, overlap));
            }
        }
        return result;
    }

    /**
     * Level 4：按 Token 数滑动窗口强制切分
     * <p>
     * 通过字符比例估算切分位置：charsPerToken = text.length() / tokens，
     * 以 step = maxSize - overlap 为步长滑动窗口。
     */
    private List<String> level4Split(String text, int maxSize, int overlap) {
        int tokens = tokenEstimator.estimate(text);
        if (tokens <= maxSize) {
            return List.of(text);
        }

        // 按字符比例估算切分位置
        double charsPerToken = (double) text.length() / tokens;
        int segmentCharSize = (int) Math.ceil(maxSize * charsPerToken);
        int overlapCharSize = (int) Math.ceil(overlap * charsPerToken);
        int step = Math.max(1, segmentCharSize - overlapCharSize);

        List<String> result = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + segmentCharSize, text.length());
            result.add(text.substring(start, end));
            if (end >= text.length()) {
                break;
            }
            start += step;
        }
        return result;
    }
}
