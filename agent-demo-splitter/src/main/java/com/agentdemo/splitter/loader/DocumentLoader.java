package com.agentdemo.splitter.loader;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import technology.tabula.ObjectExtractor;
import technology.tabula.Page;
import technology.tabula.PageIterator;
import technology.tabula.RectangularTextContainer;
import technology.tabula.Table;
import technology.tabula.extractors.SpreadsheetExtractionAlgorithm;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文档加载器（迁移自 agent-demo-rag 模块）
 * <p>
 * 业务含义：将用户上传的原始文件（txt/md/pdf）解析为 ParsedDocument，
 * 作为后续结构感知分割的输入源。不同格式采用不同解析策略：
 * - txt/md：直接 UTF-8 解码，sections 为 null
 * - pdf：PDFBox 逐页提取纯文本 + tabula-java 逐页提取表格，每页构建 DocumentSection
 * <p>
 * 解析后 text 为空白则抛出 RAG_DOCUMENT_PARSE_FAILED("文件内容为空")，
 * 由上层捕获并标记文档为 FAILED。
 */
@Slf4j
@Component
public class DocumentLoader {

    /** 支持的文档格式集合（小写），不在集合内的格式拒绝加载 */
    private static final Set<String> SUPPORTED_FORMATS = Set.of("txt", "md", "pdf");

    /** 单个文档大小上限：10MB，防止超大文件耗尽内存 */
    private static final long MAX_SIZE = 10 * 1024 * 1024; // 10MB

    /**
     * 加载并解析文档为 ParsedDocument
     * <p>
     * 业务含义：按文件格式路由到对应的解析器，提取文本内容。
     * 校验顺序：先格式后大小，格式错误属于用户输入问题优先拦截。
     * </p>
     *
     * @param fileBytes 文件字节数组
     * @param format    文件格式（txt/md/pdf，大小写不敏感）
     * @return 解析结果，包含全文文本、格式和可选的结构化分节
     * @throws BusinessException 格式不支持/大小超限/解析失败/文件内容为空
     */
    public ParsedDocument load(byte[] fileBytes, String format) {
        // 校验格式：不在支持列表内直接拒绝
        if (format == null || !SUPPORTED_FORMATS.contains(format.toLowerCase())) {
            throw new BusinessException(ErrorCode.RAG_DOCUMENT_FORMAT_UNSUPPORTED,
                    "不支持的文档格式: " + format);
        }

        // 校验大小：超过 10MB 拒绝加载
        if (fileBytes == null || fileBytes.length > MAX_SIZE) {
            throw new BusinessException(ErrorCode.RAG_DOCUMENT_SIZE_EXCEEDED,
                    "文档大小超过限制: " + (fileBytes == null ? 0 : fileBytes.length) + " bytes");
        }

        String lowerFormat = format.toLowerCase();
        try {
            ParsedDocument result = switch (lowerFormat) {
                case "txt", "md" -> parseText(fileBytes, lowerFormat);
                case "pdf" -> parsePdf(fileBytes);
                default -> throw new BusinessException(ErrorCode.RAG_DOCUMENT_FORMAT_UNSUPPORTED,
                        "不支持的文档格式: " + format);
            };

            // 空文件检测：解析后 text 为空白则拒绝
            if (result.getText() == null || result.getText().isBlank()) {
                throw new BusinessException(ErrorCode.RAG_DOCUMENT_PARSE_FAILED, "文件内容为空");
            }

            return result;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("文档解析失败, format={}", format, e);
            throw new BusinessException(ErrorCode.RAG_DOCUMENT_PARSE_FAILED, e);
        }
    }

    /**
     * 解析纯文本文件（txt/md）
     * <p>
     * txt 和 md 本质为 UTF-8 文本，直接转为字符串，sections 为 null
     * （结构解析由各自的专属分割器负责）。
     */
    private ParsedDocument parseText(byte[] fileBytes, String format) {
        String text = new String(fileBytes, StandardCharsets.UTF_8);
        return ParsedDocument.builder()
                .text(text)
                .format(format)
                .sections(null)
                .build();
    }

    /**
     * 解析 PDF 文件（按页提取 + 表格提取）
     * <p>
     * 业务含义：使用 PDFTextStripper 逐页提取纯文本，每页构建一个 DocumentSection，
     * metadata 含 pageNumber。同时使用 tabula-java 逐页检测表格，表格 Markdown 文本
     * 合并到对应页面的 section 文本中。全文 text 为所有页面文本的拼接。
     */
    private ParsedDocument parsePdf(byte[] fileBytes) throws Exception {
        try (PDDocument document = Loader.loadPDF(fileBytes)) {
            int pageCount = document.getNumberOfPages();

            // 逐页提取表格文本（pageNumber -> markdown 表格文本）
            Map<Integer, String> tableTextByPage = extractTablesPerPage(document);

            // 逐页提取纯文本并构建 sections
            List<DocumentSection> sections = new ArrayList<>();
            StringBuilder fullText = new StringBuilder();

            for (int i = 1; i <= pageCount; i++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String pageText = stripper.getText(document);

                // 合并该页的表格文本
                String tableText = tableTextByPage.get(i);
                if (tableText != null && !tableText.isEmpty()) {
                    pageText = pageText + "\n\n" + tableText;
                }

                Map<String, String> metadata = new HashMap<>();
                metadata.put("pageNumber", String.valueOf(i));

                sections.add(DocumentSection.builder()
                        .text(pageText)
                        .metadata(metadata)
                        .build());

                fullText.append(pageText);
            }

            return ParsedDocument.builder()
                    .text(fullText.toString())
                    .format("pdf")
                    .sections(sections)
                    .build();
        }
    }

    /**
     * 使用 tabula-java 逐页提取 PDF 表格为 Markdown 格式
     * <p>
     * 遍历 PDF 每一页，使用 SpreadsheetExtractionAlgorithm 自动检测表格区域，
     * 将检测到的表格转为 Markdown 语法。返回 pageNumber -> markdown 表格文本的映射。
     */
    private Map<Integer, String> extractTablesPerPage(PDDocument document) {
        Map<Integer, String> result = new HashMap<>();
        SpreadsheetExtractionAlgorithm extractor = new SpreadsheetExtractionAlgorithm();

        try {
            // ObjectExtractor 的 close() 会关闭底层 PDDocument，由外层管理生命周期
            ObjectExtractor objectExtractor = new ObjectExtractor(document);
            PageIterator pages = objectExtractor.extract();

            while (pages.hasNext()) {
                Page page = pages.next();
                int pageNumber = page.getPageNumber();
                List<Table> tables = extractor.extract(page);
                if (tables.isEmpty()) {
                    continue;
                }

                log.debug("PDF 表格检测: 第 {} 页发现 {} 个表格", pageNumber, tables.size());
                StringBuilder markdown = new StringBuilder();

                for (Table table : tables) {
                    List<List<RectangularTextContainer>> rows = table.getRows();
                    for (int i = 0; i < rows.size(); i++) {
                        List<RectangularTextContainer> row = rows.get(i);
                        markdown.append("| ");
                        for (RectangularTextContainer cell : row) {
                            String cellText = cell.getText().replace("|", "\\|").trim();
                            markdown.append(cellText).append(" | ");
                        }
                        markdown.append("\n");
                        if (i == 0) {
                            markdown.append("|");
                            for (int j = 0; j < row.size(); j++) {
                                markdown.append(" --- |");
                            }
                            markdown.append("\n");
                        }
                    }
                    markdown.append("\n");
                }

                if (markdown.length() > 0) {
                    result.put(pageNumber, markdown.toString().trim());
                }
            }
        } catch (Exception e) {
            log.warn("PDF 表格提取异常，跳过表格解析: {}", e.getMessage());
        }

        return result;
    }
}
