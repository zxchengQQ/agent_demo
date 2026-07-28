package com.agentdemo.web.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库响应 DTO
 * <p>
 * 业务含义：知识库相关接口的统一返回结构，屏蔽内部实体细节。
 * </p>
 */
@Data
public class KnowledgeBaseResponse {

    /** 知识库 ID */
    private String id;

    /** 知识库名称 */
    private String name;

    /** 知识库描述 */
    private String description;

    /** 文档数量 */
    private int documentCount;

    /** 创建时间 */
    private LocalDateTime createTime;
}
