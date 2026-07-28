package com.agentdemo.rag.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库实体
 * <p>
 * 业务含义：知识库是文档的容器，用户通过知识库组织和管理文档。
 * 每个知识库通过名称全局唯一标识，Agent 检索时按知识库名称定位。
 * </p>
 */
@Data
public class KnowledgeBase {

    /** 知识库 ID（UUID 去横线，主键） */
    private String id;

    /** 知识库名称（1-50 字符，全局唯一） */
    private String name;

    /** 知识库描述（最长 200 字符） */
    private String description;

    /** 文档数量（冗余字段，加速列表查询避免每次统计） */
    private int documentCount;

    /** 创建时间 */
    private LocalDateTime createTime;
}
