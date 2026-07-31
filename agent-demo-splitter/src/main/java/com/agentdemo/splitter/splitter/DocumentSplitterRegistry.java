package com.agentdemo.splitter.splitter;

import com.agentdemo.splitter.config.SplitterProperties;
import com.agentdemo.splitter.loader.ParsedDocument;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档分割器注册中心
 * <p>
 * 业务含义：按文件格式路由到对应的 TypedDocumentSplitter（MD/PDF/TXT），
 * 专属分割器异常时回退到 GenericDocumentSplitter，确保文档处理不中断。
 * 分割后为每个 TextSegment 注入来源 metadata（knowledgeBaseId、documentId、format），
 * 保留专属分割器写入的结构化 metadata（如 pageNumber、headerLevel）。
 */
@Slf4j
@Component
public class DocumentSplitterRegistry {

    private final Map<String, TypedDocumentSplitter> splitterMap;
    private final GenericDocumentSplitter genericSplitter;
    private final SplitterProperties properties;

    public DocumentSplitterRegistry(List<TypedDocumentSplitter> splitters,
                                    GenericDocumentSplitter genericSplitter,
                                    SplitterProperties properties) {
        this.genericSplitter = genericSplitter;
        this.properties = properties;
        this.splitterMap = new HashMap<>();
        for (TypedDocumentSplitter splitter : splitters) {
            String format = splitter.supportedFormat();
            if (format != null) {
                splitterMap.put(format.toLowerCase(), splitter);
            }
        }
    }

    /**
     * 分割文档：按格式路由 -> 异常回退 -> 注入来源 metadata
     *
     * @param doc             解析后的文档
     * @param knowledgeBaseId 知识库 ID
     * @param documentId      文档 ID
     * @param fileName        文件名（CR-002 新增，注入到 metadata 供检索结果引用来源）
     * @return 分割后的文本段列表，每个段含来源 metadata
     */
    public List<TextSegment> split(ParsedDocument doc, String knowledgeBaseId, String documentId, String fileName) {
        String format = doc.getFormat();
        TypedDocumentSplitter splitter = format != null
                ? splitterMap.get(format.toLowerCase())
                : null;

        List<TextSegment> segments;
        try {
            if (splitter != null) {
                segments = splitter.split(doc);
            } else {
                segments = genericSplitter.split(doc);
            }
        } catch (Exception e) {
            log.warn("专属分割器 [{}] 执行失败，回退到通用分割器: {}", format, e.getMessage());
            segments = genericSplitter.split(doc);
        }

        return enrichMetadata(segments, knowledgeBaseId, documentId, format, fileName);
    }

    /**
     * 为每个 TextSegment 注入来源 metadata
     * <p>
     * 保留分割器已写入的结构化 metadata（如 pageNumber、headerLevel），
     * 追加 knowledgeBaseId、documentId、format 用于检索过滤和来源定位。
     */
    private List<TextSegment> enrichMetadata(List<TextSegment> segments,
                                             String knowledgeBaseId,
                                             String documentId,
                                             String format,
                                             String fileName) {
        List<TextSegment> result = new ArrayList<>();
        for (TextSegment seg : segments) {
            Metadata metadata = seg.metadata();
            if (knowledgeBaseId != null) {
                metadata = metadata.put("knowledgeBaseId", knowledgeBaseId);
            }
            if (documentId != null) {
                metadata = metadata.put("documentId", documentId);
            }
            if (format != null) {
                metadata = metadata.put("format", format);
            }
            if (fileName != null) {
                metadata = metadata.put("fileName", fileName);
            }
            result.add(TextSegment.from(seg.text(), metadata));
        }
        return result;
    }
}
