package com.agentdemo.rag.loader;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文档加载器测试
 * <p>
 * 验证 DocumentLoader 对 txt/md/pdf 格式的解析能力，以及格式校验、大小校验、异常处理。
 * PDF 正常解析测试使用硬编码的最小合法 PDF 字节数组，避免依赖 PDFBox 字体 API。
 * </p>
 */
@DisplayName("文档加载器测试")
class DocumentLoaderTest {

    private DocumentLoader documentLoader;

    @BeforeEach
    void setUp() {
        documentLoader = new DocumentLoader();
    }

    @Test
    @DisplayName("解析 txt 文件返回文本内容")
    void loadTxtShouldReturnTextContent() {
        String content = "这是一段测试文本";
        byte[] txtBytes = content.getBytes(StandardCharsets.UTF_8);

        String result = documentLoader.load(txtBytes, "txt");

        assertEquals(content, result, "txt 解析结果应与原文一致");
    }

    @Test
    @DisplayName("解析 md 文件返回 Markdown 原文")
    void loadMdShouldReturnOriginalMarkdown() {
        String content = "# 标题\n\n正文内容";
        byte[] mdBytes = content.getBytes(StandardCharsets.UTF_8);

        String result = documentLoader.load(mdBytes, "md");

        assertEquals(content, result, "md 解析结果应与原文一致");
    }

    @Test
    @DisplayName("解析 pdf 文件返回提取的文本")
    void loadPdfShouldReturnExtractedText() {
        byte[] pdfBytes = createMinimalPdf();
        assertTrue(pdfBytes.length > 0, "测试 PDF 字节数组不应为空");

        String result = documentLoader.load(pdfBytes, "pdf");

        assertTrue(result.contains("Hello PDF Test"), "pdf 解析结果应包含原始文本");
    }

    @Test
    @DisplayName("不支持的格式抛 RAG_DOCUMENT_FORMAT_UNSUPPORTED")
    void loadUnsupportedFormatShouldThrow() {
        byte[] bytes = "test".getBytes(StandardCharsets.UTF_8);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> documentLoader.load(bytes, "docx"));

        assertEquals(ErrorCode.RAG_DOCUMENT_FORMAT_UNSUPPORTED, ex.getErrorCode(),
                "不支持的格式应抛 RAG_DOCUMENT_FORMAT_UNSUPPORTED");
    }

    @Test
    @DisplayName("超过 10MB 的文件抛 RAG_DOCUMENT_SIZE_EXCEEDED")
    void loadOversizedFileShouldThrow() {
        // 11MB 字节数组，超过 10MB 上限
        byte[] oversizedBytes = new byte[11 * 1024 * 1024];

        BusinessException ex = assertThrows(BusinessException.class,
                () -> documentLoader.load(oversizedBytes, "txt"));

        assertEquals(ErrorCode.RAG_DOCUMENT_SIZE_EXCEEDED, ex.getErrorCode(),
                "超过 10MB 的文件应抛 RAG_DOCUMENT_SIZE_EXCEEDED");
    }

    @Test
    @DisplayName("恰好 10MB 的文件正常解析（边界值）")
    void loadBoundarySizeShouldSucceed() {
        // 恰好 10MB，不超过上限，应正常解析
        byte[] boundaryBytes = new byte[10 * 1024 * 1024];
        Arrays.fill(boundaryBytes, (byte) 'a');

        String result = assertDoesNotThrow(() -> documentLoader.load(boundaryBytes, "txt"));

        assertEquals(boundaryBytes.length, result.length(), "10MB 边界值应正常解析");
    }

    @Test
    @DisplayName("损坏的 PDF 抛 RAG_DOCUMENT_PARSE_FAILED")
    void loadCorruptedPdfShouldThrow() {
        // 非 PDF 格式的字节数组，PDFBox 解析时会抛异常
        byte[] corruptedBytes = "not a pdf file".getBytes(StandardCharsets.UTF_8);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> documentLoader.load(corruptedBytes, "pdf"));

        assertEquals(ErrorCode.RAG_DOCUMENT_PARSE_FAILED, ex.getErrorCode(),
                "损坏的 PDF 应抛 RAG_DOCUMENT_PARSE_FAILED");
    }

    @Test
    @DisplayName("null 格式抛 RAG_DOCUMENT_FORMAT_UNSUPPORTED")
    void loadNullFormatShouldThrow() {
        byte[] bytes = "test".getBytes(StandardCharsets.UTF_8);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> documentLoader.load(bytes, null));

        assertEquals(ErrorCode.RAG_DOCUMENT_FORMAT_UNSUPPORTED, ex.getErrorCode(),
                "null 格式应抛 RAG_DOCUMENT_FORMAT_UNSUPPORTED");
    }

    @Test
    @DisplayName("大写格式名也能正常解析")
    void loadUpperCaseFormatShouldSucceed() {
        String content = "大写格式测试";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        String result = documentLoader.load(bytes, "TXT");

        assertEquals(content, result, "大写格式名应能正常解析");
    }

    // ===== CR-001: PDF 表格解析测试（AC-028, AC-029, AC-030）=====

    @Test
    @DisplayName("AC-028: 含表格的 PDF 解析结果包含 Markdown 表格语法")
    void loadPdfWithTableShouldReturnMarkdownTable() throws IOException {
        byte[] pdfBytes = createPdfWithTable();
        assertTrue(pdfBytes.length > 0, "测试 PDF 字节数组不应为空");

        String result = documentLoader.load(pdfBytes, "pdf");

        // AC-028: 解析结果中包含 Markdown 表格语法
        assertTrue(result.contains("--- 表格内容 ---"),
                "含表格的 PDF 解析结果应包含表格内容分隔标记");
        assertTrue(result.contains("|"),
                "解析结果应包含 Markdown 表格分隔符 |");
        // 验证包含表头分隔行（含 --- 的行）
        assertTrue(result.lines().anyMatch(line -> line.trim().startsWith("|") && line.contains("---")),
                "解析结果应包含 Markdown 表头分隔行 ---");
    }

    @Test
    @DisplayName("AC-029: 无表格的 PDF 解析结果不包含 Markdown 表格语法")
    void loadPdfWithoutTableShouldNotContainMarkdownTable() {
        byte[] pdfBytes = createMinimalPdf();

        String result = documentLoader.load(pdfBytes, "pdf");

        // AC-029: 无表格 PDF 不应包含 Markdown 表格语法
        assertFalse(result.contains("--- 表格内容 ---"),
                "无表格的 PDF 解析结果不应包含表格内容分隔标记");
    }

    @Test
    @DisplayName("AC-030: PDF 表格结构完整性 - 行列对应关系正确")
    void loadPdfWithTableShouldPreserveRowColumnStructure() throws IOException {
        byte[] pdfBytes = createPdfWithTable();

        String result = documentLoader.load(pdfBytes, "pdf");

        // 找到表格内容部分
        int tableIndex = result.indexOf("--- 表格内容 ---");
        assertTrue(tableIndex >= 0, "应包含表格内容分隔标记");

        String tableSection = result.substring(tableIndex);
        String[] lines = tableSection.split("\n");

        // 收集所有 Markdown 表格行（以 | 开头）
        List<String> tableLines = new ArrayList<>();
        for (String line : lines) {
            if (line.trim().startsWith("|")) {
                tableLines.add(line.trim());
            }
        }

        // 应至少有 4 行：1 表头 + 1 分隔行 + 2 数据行
        assertTrue(tableLines.size() >= 4,
                "应至少有 4 行（表头+分隔+2数据行），实际: " + tableLines.size());

        // 验证每行有相同数量的单元格（通过 | 的数量判断）
        // 一行 "| a | b | c |" 有 4 个 |，表示 3 个单元格
        long firstRowPipes = tableLines.get(0).chars().filter(c -> c == '|').count();
        assertTrue(firstRowPipes >= 4, "表头行应至少有 3 个单元格");

        for (int i = 1; i < tableLines.size(); i++) {
            long rowPipes = tableLines.get(i).chars().filter(c -> c == '|').count();
            assertEquals(firstRowPipes, rowPipes,
                    "第 " + (i + 1) + " 行的单元格数量应与表头一致");
        }

        // 验证表头内容包含预期值
        assertTrue(tableLines.get(0).contains("Name"), "表头应包含 Name");
        assertTrue(tableLines.get(0).contains("Age"), "表头应包含 Age");
        assertTrue(tableLines.get(0).contains("City"), "表头应包含 City");

        // 验证数据行内容
        assertTrue(result.contains("Alice"), "应包含数据 Alice");
        assertTrue(result.contains("Bob"), "应包含数据 Bob");
        assertTrue(result.contains("Beijing"), "应包含数据 Beijing");
        assertTrue(result.contains("Shanghai"), "应包含数据 Shanghai");
    }

    /**
     * 构造一个包含 "Hello PDF Test" 文本的最小合法 PDF 字节数组
     * <p>
     * 业务含义：避免使用 PDFBox 3.x 变更后的字体 API，直接硬编码 PDF 结构。
     * PDFBox 的修复模式可解析缺少 xref 表的 PDF。
     * </p>
     *
     * @return 最小合法 PDF 字节数组
     */
    private byte[] createMinimalPdf() {
        // 流内容长度：BT /F1 12 Tf 100 700 Td (Hello PDF Test) Tj ET = 44 字节
        String pdf = "%PDF-1.4\n"
                + "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n"
                + "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n"
                + "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792]"
                + " /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>\nendobj\n"
                + "4 0 obj\n<< /Length 44 >>\nstream\n"
                + "BT /F1 12 Tf 100 700 Td (Hello PDF Test) Tj ET\n"
                + "endstream\nendobj\n"
                + "5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n"
                + "trailer\n<< /Root 1 0 R >>\n%%EOF\n";
        return pdf.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 构造一个包含表格的 PDF 字节数组（3列 x 3行）
     * <p>
     * 业务含义：使用 PDFBox 创建包含表格文本的 PDF，文本按行列网格定位，
     * 供 tabula-java 的 SpreadsheetExtractionAlgorithm 检测和提取。
     * 表格内容：
     * | Name  | Age | City     |
     * | Alice | 30  | Beijing  |
     * | Bob   | 25  | Shanghai |
     * </p>
     *
     * @return 包含表格的 PDF 字节数组
     * @throws IOException PDF 创建过程中的 IO 异常
     */
    private byte[] createPdfWithTable() throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);

            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            float fontSize = 12;

            // 表格数据：3 列（Name, Age, City），3 行（1 表头 + 2 数据）
            String[][] tableData = {
                    {"Name", "Age", "City"},
                    {"Alice", "30", "Beijing"},
                    {"Bob", "25", "Shanghai"}
            };

            float startX = 50;
            float startY = 700;
            float colWidth = 100;
            float rowHeight = 30;
            int numRows = tableData.length;
            int numCols = tableData[0].length;

            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.setFont(font, fontSize);

                // 绘制表格网格线（供 tabula-java 的 SpreadsheetExtractionAlgorithm 检测）
                // 水平线
                for (int i = 0; i <= numRows; i++) {
                    float y = startY + fontSize - i * rowHeight;
                    content.moveTo(startX, y);
                    content.lineTo(startX + numCols * colWidth, y);
                    content.stroke();
                }
                // 垂直线
                for (int j = 0; j <= numCols; j++) {
                    float x = startX + j * colWidth;
                    content.moveTo(x, startY + fontSize);
                    content.lineTo(x, startY + fontSize - numRows * rowHeight);
                    content.stroke();
                }

                // 在网格中填写文本
                for (int row = 0; row < tableData.length; row++) {
                    for (int col = 0; col < tableData[row].length; col++) {
                        content.beginText();
                        content.newLineAtOffset(
                                startX + col * colWidth + 5,
                                startY - row * rowHeight);
                        content.showText(tableData[row][col]);
                        content.endText();
                    }
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            document.save(baos);
            return baos.toByteArray();
        }
    }
}
