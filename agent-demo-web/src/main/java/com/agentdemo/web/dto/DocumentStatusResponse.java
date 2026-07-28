package com.agentdemo.web.dto;

import lombok.Data;

/**
 * 文档状态响应 DTO
 * <p>
 * 业务含义：文档状态查询接口的返回结构，仅包含状态相关信息，
 * 供前端轮询展示文档处理进度。
 * </p>
 */
@Data
public class DocumentStatusResponse {

    /** 文档 ID */
    private String documentId;

    /** 处理状态（PENDING/PROCESSING/COMPLETED/FAILED） */
    private String status;

    /** 分块数量 */
    private int chunkCount;

    /** 失败原因（FAILED 时填充） */
    private String failReason;
}
