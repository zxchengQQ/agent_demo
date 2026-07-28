package com.agentdemo.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RAG 知识库配置属性
 * <p>
 * 业务含义：集中管理 RAG 模块的所有可配置参数，包括向量存储类型、文档限制、分块参数、
 * 检索参数和 Milvus 连接配置。通过 application.yml 中 rag.* 前缀注入。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    /** 向量存储类型：memory（内存，默认）或 milvus（需部署 Milvus） */
    private StoreType storeType = StoreType.MEMORY;

    /** 文档相关配置 */
    private Document document = new Document();

    /** 分块相关配置 */
    private Chunk chunk = new Chunk();

    /** 检索相关配置 */
    private Retrieval retrieval = new Retrieval();

    /** Milvus 连接配置（store-type=milvus 时生效） */
    private Milvus milvus = new Milvus();

    public enum StoreType {
        MEMORY,
        MILVUS
    }

    @Data
    public static class Document {
        /** 单个文档大小上限 */
        private String maxSize = "10MB";
        /** 支持的文档格式 */
        private List<String> supportedFormats = List.of("txt", "md", "pdf");
        /** 临时文件目录 */
        private String tempDir = "./data/rag/temp";
    }

    @Data
    public static class Chunk {
        /** 分块大小（token 数） */
        private int size = 1000;
        /** 分块重叠（token 数） */
        private int overlap = 200;
    }

    @Data
    public static class Retrieval {
        /** 检索返回最大片段数 */
        private int maxResults = 5;
        /** 最小相似度阈值（0-1，0 表示不过滤） */
        private double minScore = 0.0;
    }

    @Data
    public static class Milvus {
        /** Milvus 服务地址 */
        private String host = "localhost";
        /** Milvus 服务端口 */
        private int port = 19530;
        /** Collection 名称 */
        private String collectionName = "agent_demo_rag";
    }
}
