package com.agentdemo.web.dto;

import lombok.Data;

/**
 * 文档分块响应 DTO
 * <p>
 * 业务含义：文档分块查询接口的返回结构，包含分块索引、文本内容和字符数。
 * 供前端分块详情抽屉面板展示使用。
 * </p>
 */
@Data
public class DocumentChunkResponse {

    /** 分块索引（从 0 开始，按原文档顺序） */
    private int chunkIndex;

    /** 分块文本内容 */
    private String content;

    /** 分块字符数 */
    private int charCount;
}
