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
 * 通用回退分割器
 * <p>
 * 业务含义：当专属分割器（MD/PDF/TXT）不存在或执行失败时，使用此通用分割器
 * 对全文进行多级级联切分。不绑定特定文件格式，supportedFormat() 返回 null。
 * 使用 SplitterProperties.defaultConfig 的 size 和 overlap 配置。
 */
@Slf4j
@Component
public class GenericDocumentSplitter implements TypedDocumentSplitter {

    private final CascadeSplitter cascadeSplitter;
    private final ChunkMerger chunkMerger;
    private final SplitterProperties properties;

    public GenericDocumentSplitter(SplitterTokenEstimator tokenEstimator,
                                   SplitterProperties properties) {
        this.cascadeSplitter = new CascadeSplitter(tokenEstimator);
        this.chunkMerger = new ChunkMerger(tokenEstimator);
        this.properties = properties;
    }

    @Override
    public String supportedFormat() {
        return null;
    }

    @Override
    public List<TextSegment> split(ParsedDocument parsedDocument) {
        String text = parsedDocument.getText();
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        SplitterProperties.ChunkConfig config = properties.getDefaultConfig();
        List<String> chunks = cascadeSplitter.split(text, config.getSize(), config.getOverlap());

        List<TextSegment> segments = new ArrayList<>();
        for (String chunk : chunks) {
            segments.add(TextSegment.from(chunk, new Metadata()));
        }

        // CR-001: 全局合并过短块
        return chunkMerger.merge(segments, config.getMinSize(), config.getSize());
    }
}
