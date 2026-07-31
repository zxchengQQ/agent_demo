package com.agentdemo.splitter.loader;

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
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DocumentLoader 单元测试（迁移改造版）
 * <p>
 * 验证 DocumentLoader 返回 ParsedDocument，PDF 按页提取 sections，
 * 空文件检测，以及格式校验和大小校验。
 */
@DisplayName("文档加载器测试（迁移版）")
class DocumentLoaderTest {

    private DocumentLoader documentLoader;

    @BeforeEach
    void setUp() {
        documentLoader = new DocumentLoader();
    }

    @Test
    @DisplayName("解析 txt 文件返回 ParsedDocument，text 非空，format=txt，sections=null")
    void loadTxtShouldReturnParsedDocument() {
        String content = "这是一段测试文本";
        byte[] txtBytes = content.getBytes(StandardCharsets.UTF_8);

        ParsedDocument result = documentLoader.load(txtBytes, "txt");

        assertNotNull(result);
        assertEquals(content, result.getText(), "txt 解析文本应与原文一致");
        assertEquals("txt", result.getFormat(), "format 应为 txt");
        assertNull(result.getSections(), "txt 的 sections 应为 null");
    }

    @Test
    @DisplayName("解析 md 文件返回 ParsedDocument，text 非空，format=md，sections=null")
    void loadMdShouldReturnParsedDocument() {
        String content = "# 标题\n\n正文内容";
        byte[] mdBytes = content.getBytes(StandardCharsets.UTF_8);

        ParsedDocument result = documentLoader.load(mdBytes, "md");

        assertNotNull(result);
        assertEquals(content, result.getText(), "md 解析文本应与原文一致");
        assertEquals("md", result.getFormat(), "format 应为 md");
        assertNull(result.getSections(), "md 的 sections 应为 null");
    }

    @Test
    @DisplayName("解析 pdf 文件返回 ParsedDocument，sections 非空且含 pageNumber")
    void loadPdfShouldReturnParsedDocumentWithSections() {
        byte[] pdfBytes = createMinimalPdf();
        assertTrue(pdfBytes.length > 0, "测试 PDF 字节数组不应为空");

        ParsedDocument result = documentLoader.load(pdfBytes, "pdf");

        assertNotNull(result);
        assertEquals("pdf", result.getFormat(), "format 应为 pdf");
        assertNotNull(result.getText(), "全文 text 不应为 null");
        assertFalse(result.getText().isBlank(), "全文 text 不应为空白");
        assertNotNull(result.getSections(), "pdf 的 sections 不应为 null");
        assertFalse(result.getSections().isEmpty(), "sections 不应为空");
        // 每页 section 的 metadata 含 pageNumber
        for (DocumentSection section : result.getSections()) {
            assertNotNull(section.getMetadata(), "section metadata 不应为 null");
            assertNotNull(section.getMetadata().get("pageNumber"), "pageNumber 不应为 null");
        }
    }

    @Test
    @DisplayName("解析多页 PDF，sections 数量等于页数，pageNumber 递增")
    void loadMultiPagePdfShouldHaveCorrectSections() {
        byte[] pdfBytes = createMultiPagePdf(3);
        assertTrue(pdfBytes.length > 0, "多页 PDF 字节数组不应为空");

        ParsedDocument result = documentLoader.load(pdfBytes, "pdf");

        assertNotNull(result.getSections());
        assertEquals(3, result.getSections().size(), "3 页 PDF 应有 3 个 sections");
        assertEquals("1", result.getSections().get(0).getMetadata().get("pageNumber"), "第 1 页 pageNumber=1");
        assertEquals("2", result.getSections().get(1).getMetadata().get("pageNumber"), "第 2 页 pageNumber=2");
        assertEquals("3", result.getSections().get(2).getMetadata().get("pageNumber"), "第 3 页 pageNumber=3");
    }

    @Test
    @DisplayName("解析空内容文件抛 RAG_DOCUMENT_PARSE_FAILED，消息含'文件内容为空'")
    void loadEmptyFileShouldThrow() {
        byte[] emptyBytes = new byte[0];

        BusinessException ex = assertThrows(BusinessException.class,
                () -> documentLoader.load(emptyBytes, "txt"));

        assertEquals(ErrorCode.RAG_DOCUMENT_PARSE_FAILED, ex.getErrorCode(),
                "空文件应抛 RAG_DOCUMENT_PARSE_FAILED");
        assertNotNull(ex.getDetail());
        assertTrue(ex.getDetail().contains("文件内容为空"),
                "异常详情应包含'文件内容为空'，实际: " + ex.getDetail());
    }

    @Test
    @DisplayName("解析纯空白字符文件抛 RAG_DOCUMENT_PARSE_FAILED")
    void loadBlankContentFileShouldThrow() {
        byte[] blankBytes = "   \n\t  ".getBytes(StandardCharsets.UTF_8);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> documentLoader.load(blankBytes, "txt"));

        assertEquals(ErrorCode.RAG_DOCUMENT_PARSE_FAILED, ex.getErrorCode(),
                "空白文件应抛 RAG_DOCUMENT_PARSE_FAILED");
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
    @DisplayName("null 格式抛 RAG_DOCUMENT_FORMAT_UNSUPPORTED")
    void loadNullFormatShouldThrow() {
        byte[] bytes = "test".getBytes(StandardCharsets.UTF_8);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> documentLoader.load(bytes, null));

        assertEquals(ErrorCode.RAG_DOCUMENT_FORMAT_UNSUPPORTED, ex.getErrorCode(),
                "null 格式应抛 RAG_DOCUMENT_FORMAT_UNSUPPORTED");
    }

    @Test
    @DisplayName("超过 10MB 的文件抛 RAG_DOCUMENT_SIZE_EXCEEDED")
    void loadOversizedFileShouldThrow() {
        byte[] oversizedBytes = new byte[11 * 1024 * 1024];

        BusinessException ex = assertThrows(BusinessException.class,
                () -> documentLoader.load(oversizedBytes, "txt"));

        assertEquals(ErrorCode.RAG_DOCUMENT_SIZE_EXCEEDED, ex.getErrorCode(),
                "超过 10MB 的文件应抛 RAG_DOCUMENT_SIZE_EXCEEDED");
    }

    @Test
    @DisplayName("大写格式名也能正常解析")
    void loadUpperCaseFormatShouldSucceed() {
        String content = "大写格式测试";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        ParsedDocument result = documentLoader.load(bytes, "TXT");

        assertEquals(content, result.getText(), "大写格式名应能正常解析");
        assertEquals("txt", result.getFormat(), "format 应为小写 txt");
    }

    @Test
    @DisplayName("损坏的 PDF 抛 RAG_DOCUMENT_PARSE_FAILED")
    void loadCorruptedPdfShouldThrow() {
        byte[] corruptedBytes = "not a pdf file".getBytes(StandardCharsets.UTF_8);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> documentLoader.load(corruptedBytes, "pdf"));

        assertEquals(ErrorCode.RAG_DOCUMENT_PARSE_FAILED, ex.getErrorCode(),
                "损坏的 PDF 应抛 RAG_DOCUMENT_PARSE_FAILED");
    }

    // ===== PDF 表格提取测试 =====

    @Test
    @DisplayName("含表格的 PDF 解析结果包含 Markdown 表格语法")
    void loadPdfWithTableShouldContainMarkdownTable() throws IOException {
        byte[] pdfBytes = createPdfWithTable();
        assertTrue(pdfBytes.length > 0);

        ParsedDocument result = documentLoader.load(pdfBytes, "pdf");

        // 表格内容应合并到全文中
        assertTrue(result.getText().contains("|"), "全文应包含 Markdown 表格分隔符 |");
        assertTrue(result.getText().lines().anyMatch(line -> line.trim().startsWith("|") && line.contains("---")),
                "全文应包含 Markdown 表头分隔行 ---");
    }

    @Test
    @DisplayName("含表格的 PDF 表格内容合并到对应页的 section")
    void loadPdfWithTableShouldMergeToPageSection() throws IOException {
        byte[] pdfBytes = createPdfWithTable();

        ParsedDocument result = documentLoader.load(pdfBytes, "pdf");

        assertNotNull(result.getSections());
        assertFalse(result.getSections().isEmpty());
        // 第 1 页的 section 应包含表格内容
        String page1Text = result.getSections().get(0).getText();
        assertTrue(page1Text.contains("|") || page1Text.contains("Alice") || page1Text.contains("Name"),
                "第 1 页 section 应包含表格相关内容");
    }

    // ===== 辅助方法 =====

    /**
     * 构造包含 "Hello PDF Test" 文本的最小合法 PDF（单页）
     */
    private byte[] createMinimalPdf() {
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
     * 构造多页 PDF，每页包含 "Page N" 文本
     */
    private byte[] createMultiPagePdf(int pageCount) {
        try (PDDocument document = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int i = 1; i <= pageCount; i++) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.setFont(font, 12);
                    content.beginText();
                    content.newLineAtOffset(100, 700);
                    content.showText("Page " + i);
                    content.endText();
                }
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            document.save(baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("创建测试 PDF 失败", e);
        }
    }

    /**
     * 构造包含表格的 PDF（3列 x 3行）
     */
    private byte[] createPdfWithTable() throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);

            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            float fontSize = 12;

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

                for (int i = 0; i <= numRows; i++) {
                    float y = startY + fontSize - i * rowHeight;
                    content.moveTo(startX, y);
                    content.lineTo(startX + numCols * colWidth, y);
                    content.stroke();
                }
                for (int j = 0; j <= numCols; j++) {
                    float x = startX + j * colWidth;
                    content.moveTo(x, startY + fontSize);
                    content.lineTo(x, startY + fontSize - numRows * rowHeight);
                    content.stroke();
                }

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
