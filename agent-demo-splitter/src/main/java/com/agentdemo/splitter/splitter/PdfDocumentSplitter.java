package com.agentdemo.splitter.splitter;

import com.agentdemo.splitter.config.SplitterProperties;
import com.agentdemo.splitter.loader.DocumentSection;
import com.agentdemo.splitter.loader.ParsedDocument;
import com.agentdemo.splitter.splitter.util.CascadeSplitter;
import com.agentdemo.splitter.splitter.util.ChunkMerger;
import com.agentdemo.splitter.tokenizer.SplitterTokenEstimator;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * PDF 专属分割器
 * <p>
 * 业务含义：利用 ParsedDocument.sections（DocumentLoader 已按页提取），
 * 每页文本独立分块，不跨页拼接。页码写入 metadata 便于检索定位。
 * 单页内容超过 maxSize 时调用 CascadeSplitter 按段落递归切分，
 * 切分后的子分块均携带相同 pageNumber。
 */
@Slf4j
@Component
public class PdfDocumentSplitter implements TypedDocumentSplitter {

    private final CascadeSplitter cascadeSplitter;
    private final ChunkMerger chunkMerger;
    private final SplitterProperties properties;
    private final SplitterTokenEstimator estimator;

    public PdfDocumentSplitter(SplitterTokenEstimator estimator,
                               SplitterProperties properties) {
        this.cascadeSplitter = new CascadeSplitter(estimator);
        this.chunkMerger = new ChunkMerger(estimator);
        this.properties = properties;
        this.estimator = estimator;
    }

    @Override
    public String supportedFormat() {
        return "pdf";
    }

    @Override
    public List<TextSegment> split(ParsedDocument parsedDocument) {
        String text = parsedDocument.getText();
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        List<DocumentSection> sections = parsedDocument.getSections();

        // sections 为空列表时返回空列表
        if (sections != null && sections.isEmpty()) {
            return List.of();
        }

        // sections 为 null 时回退使用全文 text
        if (sections == null) {
            return splitFullText(text);
        }

        // 按页独立分块
        SplitterProperties.ChunkConfig config = properties.getPdf();
        int maxSize = config.getSize();
        int overlap = config.getOverlap();

        List<TextSegment> result = new ArrayList<>();
        for (DocumentSection section : sections) {
            String pageText = section.getText();
            if (pageText == null || pageText.isBlank()) {
                continue;
            }

            String pageNumber = section.getMetadata() != null
                    ? section.getMetadata().getOrDefault("pageNumber", "")
                    : "";

            int pageTokens = estimator.estimate(pageText);
            if (pageTokens <= maxSize) {
                result.add(createSegment(pageText, pageNumber));
            } else {
                // 单页超限：调用 CascadeSplitter 切分
                List<String> chunks = cascadeSplitter.split(pageText, maxSize, overlap);
                for (String chunk : chunks) {
                    result.add(createSegment(chunk, pageNumber));
                }
            }
        }

        // CR-001: 按 pageNumber 分组合并过短块
        int minSize = config.getMinSize();
        return chunkMerger.merge(result, minSize, maxSize, "pageNumber");
    }

    /**
     * 回退方案：sections 为 null 时对全文进行 CascadeSplitter 切分
     */
    private List<TextSegment> splitFullText(String text) {
        SplitterProperties.ChunkConfig config = properties.getPdf();
        List<String> chunks = cascadeSplitter.split(text, config.getSize(), config.getOverlap());

        List<TextSegment> result = new ArrayList<>();
        for (String chunk : chunks) {
            result.add(TextSegment.from(chunk, new Metadata()));
        }
        return result;
    }

    private TextSegment createSegment(String text, String pageNumber) {
        Metadata metadata = new Metadata();
        if (pageNumber != null && !pageNumber.isEmpty()) {
            metadata = metadata.put("pageNumber", pageNumber);
        }
        return TextSegment.from(text, metadata);
    }
}
