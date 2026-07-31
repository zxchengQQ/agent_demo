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
    /** PDF 分割配置 */
    private ChunkConfig pdf = new ChunkConfig(1200, 200, 600);
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
}
