package com.agentdemo.splitter.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SplitterProperties 单元测试
 * <p>
 * 验证按文件类型配置 size/overlap/minSize 的能力（CR-001 新增 minSize）。
 */
class SplitterPropertiesTest {

    @Test
    void ChunkConfig包含minSize字段() {
        SplitterProperties.ChunkConfig config = new SplitterProperties.ChunkConfig(800, 150, 400);
        assertEquals(800, config.getSize());
        assertEquals(150, config.getOverlap());
        assertEquals(400, config.getMinSize());
    }

    @Test
    void ChunkConfig无参构造minSize默认为0() {
        SplitterProperties.ChunkConfig config = new SplitterProperties.ChunkConfig();
        assertEquals(0, config.getSize());
        assertEquals(0, config.getOverlap());
        assertEquals(0, config.getMinSize());
    }

    @Test
    void md配置的minSize为size的一半() {
        SplitterProperties properties = new SplitterProperties();
        SplitterProperties.ChunkConfig mdConfig = properties.getConfig("md");
        assertEquals(800, mdConfig.getSize());
        assertEquals(400, mdConfig.getMinSize());
    }

    @Test
    void pdf配置的minSize为size的一半() {
        SplitterProperties properties = new SplitterProperties();
        SplitterProperties.ChunkConfig pdfConfig = properties.getConfig("pdf");
        assertEquals(1200, pdfConfig.getSize());
        assertEquals(600, pdfConfig.getMinSize());
    }

    @Test
    void txt配置的minSize为size的一半() {
        SplitterProperties properties = new SplitterProperties();
        SplitterProperties.ChunkConfig txtConfig = properties.getConfig("txt");
        assertEquals(1000, txtConfig.getSize());
        assertEquals(500, txtConfig.getMinSize());
    }

    @Test
    void defaultConfig的minSize为size的一半() {
        SplitterProperties properties = new SplitterProperties();
        SplitterProperties.ChunkConfig defaultConfig = properties.getConfig("unknown");
        assertEquals(1000, defaultConfig.getSize());
        assertEquals(500, defaultConfig.getMinSize());
    }

    @Test
    void null格式返回defaultConfig() {
        SplitterProperties properties = new SplitterProperties();
        SplitterProperties.ChunkConfig config = properties.getConfig(null);
        assertEquals(1000, config.getSize());
        assertEquals(500, config.getMinSize());
    }

    // ===== CR-002: PDF 图片提取配置 =====

    @Test
    void pdf配置默认开启图片提取() {
        SplitterProperties properties = new SplitterProperties();
        SplitterProperties.PdfChunkConfig pdfConfig = properties.getPdf();
        assertTrue(pdfConfig.isExtractImages(), "extractImages 默认应为 true");
    }

    @Test
    void pdf配置默认DPI为144() {
        SplitterProperties properties = new SplitterProperties();
        SplitterProperties.PdfChunkConfig pdfConfig = properties.getPdf();
        assertEquals(144, pdfConfig.getImageDpi(), "imageDpi 默认应为 144");
    }

    @Test
    void pdf配置继承ChunkConfig字段() {
        SplitterProperties properties = new SplitterProperties();
        SplitterProperties.PdfChunkConfig pdfConfig = properties.getPdf();
        // 继承自 ChunkConfig 的字段
        assertEquals(1200, pdfConfig.getSize());
        assertEquals(200, pdfConfig.getOverlap());
        assertEquals(600, pdfConfig.getMinSize());
    }

    @Test
    void pdf配置可设置图片提取参数() {
        SplitterProperties.PdfChunkConfig pdfConfig = new SplitterProperties.PdfChunkConfig();
        pdfConfig.setExtractImages(false);
        pdfConfig.setImageDpi(300);
        assertFalse(pdfConfig.isExtractImages());
        assertEquals(300, pdfConfig.getImageDpi());
    }

    @Test
    void getConfig返回的pdf配置仍为ChunkConfig兼容() {
        SplitterProperties properties = new SplitterProperties();
        // getConfig 返回 ChunkConfig，PdfChunkConfig 向上转型兼容
        SplitterProperties.ChunkConfig config = properties.getConfig("pdf");
        assertEquals(1200, config.getSize());
    }
}
