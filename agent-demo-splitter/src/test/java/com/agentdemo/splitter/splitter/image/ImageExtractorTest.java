package com.agentdemo.splitter.splitter.image;

import com.agentdemo.splitter.config.SplitterProperties;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.cos.COSName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ImageExtractor 单元测试（CR-002 新增，BUG 修复后更新）
 * <p>
 * BUG 修复要点 1：原实现使用 PDFRenderer 将整页（含文字）渲染为 PNG，文字页面被误判为图片。
 * 修复后改为遍历 PDF 嵌入的图片对象（PDImageXObject），仅提取真正的嵌入图片，无图 PDF 返回空列表。
 * </p>
 * <p>
 * BUG 修复要点 2：流程图软件（如 process.on）导出 PDF 会嵌入大量碎片图片（背景块、节点框、边框），
 * 单张 OCR/摘要无意义。修复后通过 maxImagesPerPage 阈值过滤碎片化页面，整页跳过并清理图片文件。
 * </p>
 */
class ImageExtractorTest {

    private SplitterProperties splitterProperties;
    private ImageExtractor extractor;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        splitterProperties = new SplitterProperties();
        extractor = new ImageExtractor(splitterProperties);
    }

    // ========== 核心修复场景：BUG 验证 ==========

    /**
     * BUG 核心验证：空白 PDF（无嵌入图片）应返回空列表，而不是将每页渲染为图片。
     * 修复前：3 页空白 PDF 错误返回 3 张"图片"（其实是整页渲染图）。
     */
    @Test
    void 空白PDF无嵌入图片应返回空列表不渲染整页() throws Exception {
        byte[] pdfBytes = createBlankPdf(3);

        List<ImageInfo> images = extractor.extractImages(pdfBytes, "blankDoc", tempDir, 144);

        assertTrue(images.isEmpty(), "空白 PDF 无嵌入图片时不应提取任何图片，但实际: " + images.size());
    }

    /**
     * 正常场景：PDF 含 1 张嵌入图片应返回 1 个 ImageInfo
     */
    @Test
    void pdf含单张嵌入图片返回一个ImageInfo() throws Exception {
        byte[] pdfBytes = createPdfWithImages(1, 1);

        List<ImageInfo> images = extractor.extractImages(pdfBytes, "singleImgDoc", tempDir, 144);

        assertEquals(1, images.size(), "PDF 含 1 张图片应提取 1 个 ImageInfo");
    }

    /**
     * 同页多图场景：单页含多张图片应全部提取，imageIndex 递增区分
     */
    @Test
    void 同页含多张图片应全部提取且索引递增() throws Exception {
        byte[] pdfBytes = createPdfWithImages(1, 3);

        List<ImageInfo> images = extractor.extractImages(pdfBytes, "multiImgDoc", tempDir, 144);

        assertEquals(3, images.size(), "单页含 3 张图片应提取 3 个 ImageInfo");
        // 验证 imageIndex 从 0 开始递增
        for (int i = 0; i < images.size(); i++) {
            assertEquals(i, images.get(i).getImageIndex(),
                    "imageIndex 应从 0 递增，实际第 " + i + " 个为: " + images.get(i).getImageIndex());
            assertEquals(1, images.get(i).getPageNumber(), "同页图片 pageNumber 应一致");
        }
    }

    /**
     * 多页含图场景：每页含 1 张图片，pageNumber 应正确递增
     */
    @Test
    void 多页PDF每页含一张图片应按页码递增() throws Exception {
        byte[] pdfBytes = createPdfWithImages(3, 1);

        List<ImageInfo> images = extractor.extractImages(pdfBytes, "multiPageDoc", tempDir, 144);

        assertEquals(3, images.size(), "3 页每页 1 图应提取 3 个 ImageInfo");
        for (int i = 0; i < images.size(); i++) {
            assertEquals(i + 1, images.get(i).getPageNumber(),
                    "pageNumber 应从 1 递增，实际第 " + i + " 个为: " + images.get(i).getPageNumber());
        }
    }

    /**
     * 提取的图片文件实际存在于磁盘
     */
    @Test
    void 提取的图片文件实际存在() throws Exception {
        byte[] pdfBytes = createPdfWithImages(1, 2);

        List<ImageInfo> images = extractor.extractImages(pdfBytes, "filesExistDoc", tempDir, 144);

        assertEquals(2, images.size());
        for (ImageInfo info : images) {
            assertTrue(Path.of(info.getImagePath()).toFile().exists(),
                    "图片文件应存在: " + info.getImagePath());
        }
    }

    /**
     * 图片保存到 documentId 子目录
     */
    @Test
    void 图片保存到documentId子目录() throws Exception {
        byte[] pdfBytes = createPdfWithImages(1, 1);

        List<ImageInfo> images = extractor.extractImages(pdfBytes, "myDoc", tempDir, 144);

        assertEquals(1, images.size());
        assertTrue(images.get(0).getImagePath().contains("myDoc"),
                "图片路径应包含 documentId 子目录");
    }

    // ========== 边界与异常场景 ==========

    /**
     * 边界：空字节数组返回空列表不抛异常
     */
    @Test
    void 空字节数组返回空列表不抛异常() {
        List<ImageInfo> images = extractor.extractImages(new byte[0], "doc", tempDir, 144);
        assertTrue(images.isEmpty(), "空字节数组应返回空列表");
    }

    /**
     * 边界：null 字节数组返回空列表不抛异常
     */
    @Test
    void null字节数组返回空列表不抛异常() {
        List<ImageInfo> images = extractor.extractImages(null, "doc", tempDir, 144);
        assertTrue(images.isEmpty(), "null 字节数组应返回空列表");
    }

    /**
     * 边界：非 PDF 格式返回空列表不抛异常
     */
    @Test
    void 非PDF格式返回空列表不抛异常() {
        List<ImageInfo> images = extractor.extractImages("not a pdf".getBytes(), "doc", tempDir, 144);
        assertTrue(images.isEmpty(), "非 PDF 格式应返回空列表");
    }

    /**
     * 边界：含图但图片提取异常时跳过该图片不影响其他图片
     */
    @Test
    void 单张图片提取失败不影响其他图片() throws Exception {
        // 构造一个含合法图片的 PDF
        byte[] pdfBytes = createPdfWithImages(1, 1);
        List<ImageInfo> images = extractor.extractImages(pdfBytes, "robustDoc", tempDir, 144);
        assertEquals(1, images.size(), "合法 PDF 应正常提取图片");
    }

    // ========== 碎片化过滤场景（BUG 修复新增） ==========

    /**
     * 核心场景：单页含 20 张图片（超过默认阈值 15）应整页跳过，返回空列表。
     * 模拟 process.on 流程图导出 PDF 的碎片化场景。
     */
    @Test
    void 单页图片数超过阈值应整页跳过返回空列表() throws Exception {
        // 单页 20 张图片，超过默认阈值 15
        byte[] pdfBytes = createPdfWithImages(1, 20);

        List<ImageInfo> images = extractor.extractImages(pdfBytes, "fragmentedDoc", tempDir, 144);

        assertTrue(images.isEmpty(), "单页 20 张图片超过阈值应整页跳过，返回空列表");
    }

    /**
     * 跳过的碎片化页面，已保存的图片文件应被清理，避免残留占用磁盘
     */
    @Test
    void 碎片化页面跳过后应清理已保存的图片文件() throws Exception {
        byte[] pdfBytes = createPdfWithImages(1, 20);

        List<ImageInfo> images = extractor.extractImages(pdfBytes, "cleanupDoc", tempDir, 144);

        assertTrue(images.isEmpty(), "碎片化页面应返回空列表");
        // 验证 documentId 子目录下不应残留任何图片文件
        Path docDir = tempDir.resolve("cleanupDoc");
        if (java.nio.file.Files.exists(docDir)) {
            long remainingFiles = java.nio.file.Files.list(docDir)
                    .filter(p -> p.toString().endsWith(".png"))
                    .count();
            assertEquals(0, remainingFiles, "碎片化页面跳过后不应残留任何图片文件");
        }
    }

    /**
     * 正常场景：单页含 15 张图片（等于默认阈值）应保留全部，不超过阈值不触发过滤
     */
    @Test
    void 单页图片数等于阈值应保留全部() throws Exception {
        byte[] pdfBytes = createPdfWithImages(1, 15);

        List<ImageInfo> images = extractor.extractImages(pdfBytes, "atThresholdDoc", tempDir, 144);

        assertEquals(15, images.size(), "单页图片数等于阈值 15 应保留全部，不过滤");
    }

    /**
     * 多页混合场景：page1 含 20 张图片（跳过），page2 含 3 张图片（保留）
     * 验证：仅返回 page2 的 3 张图片，page1 的图片被跳过
     */
    @Test
    void 多页混合仅跳过碎片化页面保留正常页面() throws Exception {
        // page1 含 20 张图片（超过阈值），page2 含 3 张图片（低于阈值）
        byte[] pdfBytes = createMixedPdf(20, 3);

        List<ImageInfo> images = extractor.extractImages(pdfBytes, "mixedDoc", tempDir, 144);

        assertEquals(3, images.size(), "应仅返回 page2 的 3 张图片，page1 被跳过");
        // 验证返回的图片都是 page2 的
        for (ImageInfo info : images) {
            assertEquals(2, info.getPageNumber(), "保留的图片应全部来自 page2");
        }
    }

    /**
     * 配置项验证：将 maxImagesPerPage 调整为 30 时，单页 20 张图片不被过滤
     */
    @Test
    void 调整maxImagesPerPage阈值后不再过滤() throws Exception {
        // 调整阈值为 30
        splitterProperties.getPdf().setMaxImagesPerPage(30);
        byte[] pdfBytes = createPdfWithImages(1, 20);

        List<ImageInfo> images = extractor.extractImages(pdfBytes, "adjustedThresholdDoc", tempDir, 144);

        assertEquals(20, images.size(), "阈值调整为 30 后，20 张图片应全部保留");
    }

    /**
     * 配置项验证：将 maxImagesPerPage 设为 0（无效配置）时，回退到默认值 15
     */
    @Test
    void maxImagesPerPage为零时回退默认值15() throws Exception {
        splitterProperties.getPdf().setMaxImagesPerPage(0);
        byte[] pdfBytes = createPdfWithImages(1, 20);

        List<ImageInfo> images = extractor.extractImages(pdfBytes, "fallbackDefaultDoc", tempDir, 144);

        assertTrue(images.isEmpty(), "maxImagesPerPage=0 时应回退到默认值 15，20 张图片仍被过滤");
    }

    /**
     * 配置项验证：SplitterProperties.PdfChunkConfig.maxImagesPerPage 默认值为 15
     */
    @Test
    void maxImagesPerPage默认值为15() {
        SplitterProperties.PdfChunkConfig pdfConfig = new SplitterProperties.PdfChunkConfig(1200, 200, 600);
        assertEquals(15, pdfConfig.getMaxImagesPerPage(), "maxImagesPerPage 默认值应为 15");
    }

    // ========== 测试辅助方法 ==========

    /**
     * 创建指定页数的空白 PDF（无嵌入图片）
     */
    private byte[] createBlankPdf(int pageCount) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pageCount; i++) {
                document.addPage(new PDPage(PDRectangle.A4));
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    /**
     * 创建含嵌入图片的 PDF
     *
     * @param pageCount    页数
     * @param imagesPerPage 每页嵌入的图片数量
     */
    private byte[] createPdfWithImages(int pageCount, int imagesPerPage) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int p = 0; p < pageCount; p++) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);

                PDResources resources = new PDResources();
                for (int i = 0; i < imagesPerPage; i++) {
                    // 创建 10x10 红色 PNG 图片字节
                    byte[] imageBytes = createRedPngBytes(10, 10);
                    String fileName = String.format("img_p%d_%d.png", p + 1, i);
                    PDImageXObject imageXObject = PDImageXObject.createFromByteArray(document, imageBytes, fileName);
                    COSName name = COSName.getPDFName("Im" + p + "_" + i);
                    resources.put(name, imageXObject);
                }
                page.setResources(resources);
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    /**
     * 创建两页混合 PDF：page1 含 imagesPerPage1 张图片，page2 含 imagesPerPage2 张图片。
     * 用于测试多页场景下不同页面的差异化过滤。
     */
    private byte[] createMixedPdf(int imagesPerPage1, int imagesPerPage2) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            // page1
            PDPage page1 = new PDPage(PDRectangle.A4);
            document.addPage(page1);
            PDResources resources1 = new PDResources();
            for (int i = 0; i < imagesPerPage1; i++) {
                byte[] imageBytes = createRedPngBytes(10, 10);
                PDImageXObject imageXObject = PDImageXObject.createFromByteArray(document, imageBytes, "img_p1_" + i + ".png");
                resources1.put(COSName.getPDFName("Im1_" + i), imageXObject);
            }
            page1.setResources(resources1);

            // page2
            PDPage page2 = new PDPage(PDRectangle.A4);
            document.addPage(page2);
            PDResources resources2 = new PDResources();
            for (int i = 0; i < imagesPerPage2; i++) {
                byte[] imageBytes = createRedPngBytes(10, 10);
                PDImageXObject imageXObject = PDImageXObject.createFromByteArray(document, imageBytes, "img_p2_" + i + ".png");
                resources2.put(COSName.getPDFName("Im2_" + i), imageXObject);
            }
            page2.setResources(resources2);

            document.save(out);
            return out.toByteArray();
        }
    }

    /**
     * 生成指定尺寸的红色 PNG 图片字节数组
     */
    private byte[] createRedPngBytes(int width, int height) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
