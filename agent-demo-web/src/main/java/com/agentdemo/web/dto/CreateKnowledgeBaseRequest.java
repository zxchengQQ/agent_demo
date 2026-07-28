package com.agentdemo.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建知识库请求 DTO
 * <p>
 * 业务含义：前端调用 POST /api/rag/knowledges 接口的请求参数。
 * 通过 Bean Validation 校验知识库名称格式与描述长度，保证数据合法性。
 * </p>
 */
@Data
public class CreateKnowledgeBaseRequest {

    /**
     * 知识库名称（必填，1-50 字符，仅允许中文、字母、数字、下划线、横线）
     */
    @NotBlank(message = "知识库名称不能为空")
    @Pattern(regexp = "^[\\u4e00-\\u9fa5a-zA-Z0-9_-]+$", message = "知识库名称仅允许中文、字母、数字、下划线和横线")
    @Size(min = 1, max = 50, message = "知识库名称长度需在 1-50 字符之间")
    private String name;

    /**
     * 知识库描述（可选，最长 200 字符）
     */
    @Size(max = 200, message = "知识库描述长度不能超过 200 字符")
    private String description;
}
