package com.agentdemo.rag.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档信息实体
 * <p>
 * 业务含义：记录用户上传文档的元数据和处理状态，
 * 与向量存储中的 TextSegment 通过 documentId 关联。
 * 异步处理线程根据 status 字段跟踪文档处理进度。
 * </p>
 */
@Data
public class DocumentInfo {

    /** 文档 ID（UUID 去横线，主键） */
    private String id;

    /** 所属知识库 ID（外键，关联 KnowledgeBase.id） */
    private String knowledgeBaseId;

    /** 文件名 */
    private String fileName;

    /** 文件大小（字节） */
    private long fileSize;

    /** 文档格式（txt/md/pdf） */
    private String format;

    /** 处理状态 */
    private DocumentStatus status;

    /** 分块数量（处理完成后填充） */
    private int chunkCount;

    /** 失败原因（FAILED 时填充） */
    private String failReason;

    /** 上传时间 */
    private LocalDateTime uploadTime;
}
