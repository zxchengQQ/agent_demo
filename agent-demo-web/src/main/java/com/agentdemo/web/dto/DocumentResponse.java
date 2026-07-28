package com.agentdemo.web.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档响应 DTO
 * <p>
 * 业务含义：文档相关接口的统一返回结构，包含文档元数据和处理状态。
 * status 以字符串形式返回（PENDING/PROCESSING/COMPLETED/FAILED），便于前端直接展示。
 * </p>
 */
@Data
public class DocumentResponse {

    /** 文档 ID */
    private String documentId;

    /** 文件名 */
    private String fileName;

    /** 文件大小（字节） */
    private long fileSize;

    /** 文档格式（txt/md/pdf） */
    private String format;

    /** 处理状态（PENDING/PROCESSING/COMPLETED/FAILED） */
    private String status;

    /** 分块数量 */
    private int chunkCount;

    /** 失败原因（FAILED 时填充） */
    private String failReason;

    /** 上传时间 */
    private LocalDateTime uploadTime;
}
