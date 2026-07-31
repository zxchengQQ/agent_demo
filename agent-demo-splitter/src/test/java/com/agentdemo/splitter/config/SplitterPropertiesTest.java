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
}
