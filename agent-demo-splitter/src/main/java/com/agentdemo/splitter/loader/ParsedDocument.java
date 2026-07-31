package com.agentdemo.splitter.loader;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 文件解析结果
 * <p>
 * DocumentLoader 解析文件后构建此对象，包含全文文本和可选的结构化分节信息。
 * - MD/TXT：sections 为 null，分割器自行解析结构
 * - PDF：sections 包含按页提取的文本，每个 section 的 metadata 含 pageNumber
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParsedDocument {
    /** 全文文本（所有分割器可用） */
    private String text;
    /** 文件格式（txt/md/pdf） */
    private String format;
    /** 结构化分节（PDF 为按页文本，MD/TXT 为 null） */
    private List<DocumentSection> sections;
}
