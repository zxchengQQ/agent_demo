package com.agentdemo.rag.entity;

import lombok.Data;

/**
 * 文档分块实体
 * <p>
 * 业务含义：文档处理完成后的分块信息，包含分块索引、文本内容和字符数。
 * 在文档异步处理（processDocument）阶段 3 分块完成后保存。
 * 删除文档时级联删除对应分块记录。
 * </p>
 */
@Data
public class DocumentChunk {

    /** 分块 ID（UUID 去横线，主键） */
    private String id;

    /** 所属文档 ID（外键，关联 DocumentInfo.id） */
    private String documentId;

    /** 分块索引（从 0 开始，按原文档顺序） */
    private int chunkIndex;

    /** 分块文本内容 */
    private String content;

    /** 分块字符数 */
    private int charCount;
}
