package com.agentdemo.splitter.splitter;

import com.agentdemo.splitter.config.SplitterProperties;
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
 * TXT 专属分割器
 * <p>
 * 业务含义：调用 CascadeSplitter 对全文进行多级递归切分（段落->句子->行->Token滑动窗口），
 * 尽量保持语义完整性。使用 txt 配置的 size 和 overlap。
 */
@Slf4j
@Component
public class TxtDocumentSplitter implements TypedDocumentSplitter {

    private final CascadeSplitter cascadeSplitter;
    private final ChunkMerger chunkMerger;
    private final SplitterProperties properties;

    public TxtDocumentSplitter(SplitterTokenEstimator estimator,
                               SplitterProperties properties) {
        this.cascadeSplitter = new CascadeSplitter(estimator);
        this.chunkMerger = new ChunkMerger(estimator);
        this.properties = properties;
    }

    @Override
    public String supportedFormat() {
        return "txt";
    }

    @Override
    public List<TextSegment> split(ParsedDocument parsedDocument) {
        String text = parsedDocument.getText();
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        SplitterProperties.ChunkConfig config = properties.getTxt();
        List<String> chunks = cascadeSplitter.split(text, config.getSize(), config.getOverlap());

        List<TextSegment> segments = new ArrayList<>();
        for (String chunk : chunks) {
            segments.add(TextSegment.from(chunk, new Metadata()));
        }

        // CR-001: 全局合并过短块
        return chunkMerger.merge(segments, config.getMinSize(), config.getSize());
    }
}
