package com.agentdemo.splitter.splitter;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.segment.TextSegment;

import com.agentdemo.splitter.loader.ParsedDocument;

import java.util.List;

/**
 * 类型化文档分割器接口
 * <p>
 * 继承 LangChain4j 的 DocumentSplitter，新增 supportedFormat() 方法
 * 用于 DocumentSplitterRegistry 按文件格式路由到对应的专属分割器。
 */
public interface TypedDocumentSplitter extends DocumentSplitter {

    /**
     * 返回此分割器支持的文件格式
     *
     * @return 文件格式字符串（如 "md"、"pdf"、"txt"），通用分割器返回 null
     */
    String supportedFormat();

    /**
     * 分割解析后的文档
     *
     * @param parsedDocument 文件解析结果
     * @return 分割后的文本段列表
     */
    List<TextSegment> split(ParsedDocument parsedDocument);

    /**
     * 默认实现：将 Document 转为 ParsedDocument 后调用 split(ParsedDocument)
     * 这是 LangChain4j DocumentSplitter 接口的要求
     */
    @Override
    default List<TextSegment> split(Document document) {
        ParsedDocument parsed = ParsedDocument.builder()
                .text(document.text())
                .format(null)
                .sections(null)
                .build();
        return split(parsed);
    }
}
