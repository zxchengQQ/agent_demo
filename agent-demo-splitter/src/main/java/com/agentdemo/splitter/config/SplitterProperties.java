package com.agentdemo.splitter.config;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文档分割配置
 * <p>
 * 支持按文件类型独立配置分块大小（size）和重叠大小（overlap），单位为 Token 数。
 * 配置前缀：rag.splitter
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.splitter")
public class SplitterProperties {

    /** 默认分割配置（未知文件类型使用） */
    private ChunkConfig defaultConfig = new ChunkConfig(1000, 200, 500);
    /** Markdown 分割配置 */
    private ChunkConfig md = new ChunkConfig(800, 150, 400);
    /** PDF 分割配置（CR-002 扩展：含图片提取参数） */
    private PdfChunkConfig pdf = new PdfChunkConfig(1200, 200, 600);
    /** TXT 分割配置 */
    private ChunkConfig txt = new ChunkConfig(1000, 200, 500);

    /**
     * 根据文件格式获取对应的分割配置
     *
     * @param format 文件格式（txt/md/pdf）
     * @return 对应的 ChunkConfig，未知格式返回 defaultConfig
     */
    public ChunkConfig getConfig(String format) {
        if (format == null) {
            return defaultConfig;
        }
        return switch (format.toLowerCase()) {
            case "md" -> md;
            case "pdf" -> pdf;
            case "txt" -> txt;
            default -> defaultConfig;
        };
    }

    /**
     * 分块配置
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChunkConfig {
        /** 分块大小（Token 数） */
        private int size;
        /** 分块重叠（Token 数） */
        private int overlap;
        /** 最小分块大小（Token 数），低于此值的分块触发合并，默认为 size 的 50%（CR-001 新增） */
        private int minSize;
    }

    /**
     * PDF 分块配置（CR-002 新增）
     * <p>
     * 继承 ChunkConfig 的 size/overlap/minSize，新增图片提取参数。
     * 配置路径：rag.splitter.pdf.*
     * </p>
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PdfChunkConfig extends ChunkConfig {
        /** 是否提取 PDF 嵌入图片对象（默认 true）。BUG 修复后仅提取真正的嵌入图片，不再渲染整页 */
        private boolean extractImages = true;
        /**
         * （已废弃）原整页渲染 DPI
         * <p>
         * BUG 修复说明：原实现使用 PDFRenderer 将整页（含文字）渲染为图片，导致文字页面被误判为图片。
         * 修复后改为遍历 PDResources 提取嵌入的 PDImageXObject，按原始分辨率保存，无需 DPI 参数。
         * 保留字段仅为向后兼容，配置值不再生效。
         * </p>
         */
        @Deprecated
        private int imageDpi = 144;
        /**
         * 单页图片数量上限（BUG 修复新增）
         * <p>
         * 业务含义：当 PDF 单页提取出的嵌入图片数量超过此阈值时，认为是流程图软件（如 process.on）
         * 导出的碎片化 PDF（流程图被切碎为大量小图标、边框、背景块），整页跳过图片处理，
         * 避免对无意义的碎片图片调用视觉模型浪费 Token。
         * </p>
         * <p>
         * 默认 15：正常 PDF 单页图片数量通常 ≤ 10，流程图导出 PDF 常达 30-100+。
         * 可通过 rag.splitter.pdf.max-images-per-page 调整。
         * </p>
         */
        private int maxImagesPerPage = 15;

        public PdfChunkConfig(int size, int overlap, int minSize) {
            super(size, overlap, minSize);
            this.extractImages = true;
            this.imageDpi = 144;
            this.maxImagesPerPage = 15;
        }
    }
}
