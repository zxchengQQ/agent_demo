package com.agentdemo.splitter.loader;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 文档分节
 * <p>
 * 表示文档的一个结构化分块，如 PDF 的一页或 Markdown 的一个章节。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentSection {
    /** 分节文本 */
    private String text;
    /** 元数据（如 page_number、header_level 等） */
    private Map<String, String> metadata;
}
