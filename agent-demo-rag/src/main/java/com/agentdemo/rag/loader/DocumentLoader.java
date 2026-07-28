package com.agentdemo.rag.loader;

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
import java.util.List;
import java.util.Set;

/**
 * 文档加载器
 * <p>
 * 业务含义：将用户上传的原始文件（txt/md/pdf）解析为纯文本，
 * 作为后续分块、向量化的输入源。不同格式采用不同解析策略，
 * 解析失败统一抛出 RAG_DOCUMENT_PARSE_FAILED，由上层捕获并标记文档为 FAILED。
 * </p>
 * <p>
 * CR-001 优化：PDF 格式采用混合提取策略 —— tabula-java 提取表格结构（转为 Markdown 格式）
 * + PDFBox 提取纯文本，两者结果合并。无表格的 PDF 自动回退纯文本提取，行为不变。
 * </p>
 */
@Slf4j
@Component
public class DocumentLoader {

    /** 支持的文档格式集合（小写），不在集合内的格式拒绝加载 */
    private static final Set<String> SUPPORTED_FORMATS = Set.of("txt", "md", "pdf");

    /** 单个文档大小上限：10MB，防止超大文件耗尽内存 */
    private static final long MAX_SIZE = 10 * 1024 * 1024; // 10MB

    /**
     * 加载并解析文档为纯文本
     * <p>
     * 业务含义：按文件格式路由到对应的解析器，提取纯文本内容。
     * 校验顺序：先格式后大小，格式错误属于用户输入问题优先拦截。
     * </p>
     *
     * @param fileBytes 文件字节数组
     * @param format    文件格式（txt/md/pdf，小写）
     * @return 解析后的纯文本
     * @throws BusinessException 格式不支持（RAG_DOCUMENT_FORMAT_UNSUPPORTED）
     *                            或大小超限（RAG_DOCUMENT_SIZE_EXCEEDED）
     *                            或解析失败（RAG_DOCUMENT_PARSE_FAILED）
     */
    public String load(byte[] fileBytes, String format) {
        // 校验格式：不在支持列表内直接拒绝，避免无效格式进入解析流程
        if (format == null || !SUPPORTED_FORMATS.contains(format.toLowerCase())) {
            throw new BusinessException(ErrorCode.RAG_DOCUMENT_FORMAT_UNSUPPORTED,
                    "不支持的文档格式: " + format);
        }

        // 校验大小：超过 10MB 拒绝加载，防止内存溢出
        if (fileBytes == null || fileBytes.length > MAX_SIZE) {
            throw new BusinessException(ErrorCode.RAG_DOCUMENT_SIZE_EXCEEDED,
                    "文档大小超过限制: " + (fileBytes == null ? 0 : fileBytes.length) + " bytes");
        }

        String lowerFormat = format.toLowerCase();
        try {
            return switch (lowerFormat) {
                case "txt", "md" -> parseText(fileBytes);
                case "pdf" -> parsePdf(fileBytes);
                default -> throw new BusinessException(ErrorCode.RAG_DOCUMENT_FORMAT_UNSUPPORTED,
                        "不支持的文档格式: " + format);
            };
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
     * 业务含义：txt 和 md 本质为 UTF-8 文本，直接转为字符串即可，无需额外解析。
     * </p>
     *
     * @param fileBytes 文件字节数组
     * @return UTF-8 解码后的文本
     */
    private String parseText(byte[] fileBytes) {
        return new String(fileBytes, StandardCharsets.UTF_8);
    }

    /**
     * 解析 PDF 文件（CR-001 混合提取策略）
     * <p>
     * 业务含义：使用 tabula-java 检测并提取 PDF 中的表格结构，转为 Markdown 格式；
     * 同时使用 PDFBox 的 PDFTextStripper 提取纯文本。两者结果合并，
     * 表格内容以 Markdown 格式追加到纯文本末尾，便于 LLM 理解表格结构。
     * 无表格时仅返回纯文本，行为与变更前一致。
     * </p>
     *
     * @param fileBytes PDF 文件字节数组
     * @return 提取的文本（含 Markdown 表格，如有）
     * @throws Exception PDF 解析过程中的任何异常
     */
    private String parsePdf(byte[] fileBytes) throws Exception {
        try (PDDocument document = Loader.loadPDF(fileBytes)) {
            // 1. 使用 tabula-java 检测并提取表格，转为 Markdown 格式
            String tableText = extractTablesAsMarkdown(document);

            // 2. 使用 PDFBox 提取纯文本（含表格区域的线性文本）
            PDFTextStripper stripper = new PDFTextStripper();
            String plainText = stripper.getText(document);

            // 3. 合并结果：如果检测到表格，将 Markdown 表格追加到纯文本末尾
            if (tableText.isEmpty()) {
                return plainText;
            }
            return plainText + "\n\n--- 表格内容 ---\n\n" + tableText;
        }
    }

    /**
     * 使用 tabula-java 提取 PDF 中的表格为 Markdown 格式
     * <p>
     * 业务含义：遍历 PDF 每一页，使用 SpreadsheetExtractionAlgorithm 自动检测表格区域，
     * 将检测到的表格按行列结构转换为 Markdown 表格语法（| 分隔 + --- 表头分隔行）。
     * 单元格中的 | 字符转义为 \\| 避免破坏 Markdown 语法。
     * </p>
     *
     * @param document PDFBox 文档对象
     * @return Markdown 格式的表格文本，无表格时返回空字符串
     */
    private String extractTablesAsMarkdown(PDDocument document) {
        SpreadsheetExtractionAlgorithm extractor = new SpreadsheetExtractionAlgorithm();
        StringBuilder markdown = new StringBuilder();

        try {
            // ObjectExtractor 包装 PDDocument，其 close() 会关闭底层文档，
            // 因此不使用 try-with-resources，由 parsePdf() 的外层管理 PDDocument 生命周期
            ObjectExtractor objectExtractor = new ObjectExtractor(document);
            PageIterator pages = objectExtractor.extract();

            while (pages.hasNext()) {
                Page page = pages.next();
                List<Table> tables = extractor.extract(page);
                if (!tables.isEmpty()) {
                    log.debug("PDF 表格检测: 第 {} 页发现 {} 个表格", page.getPageNumber(), tables.size());
                }
                for (Table table : tables) {
                    List<List<RectangularTextContainer>> rows = table.getRows();
                    for (int i = 0; i < rows.size(); i++) {
                        List<RectangularTextContainer> row = rows.get(i);
                        markdown.append("| ");
                        for (RectangularTextContainer cell : row) {
                            // 转义单元格中的 | 字符，避免破坏 Markdown 表格语法
                            String cellText = cell.getText().replace("|", "\\|").trim();
                            markdown.append(cellText).append(" | ");
                        }
                        markdown.append("\n");
                        // 第一行后添加 Markdown 表头分隔符
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
            }
        } catch (Exception e) {
            log.warn("PDF 表格提取异常，跳过表格解析: {}", e.getMessage());
        }
        return markdown.toString();
    }
}
