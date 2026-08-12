package com.agentdemo.common.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 业务错误码枚举
 * <p>
 * 业务含义：统一管理所有业务错误码，禁止在调用方硬编码状态码。
 * 编码规范：
 * - 2xx：成功
 * - 4xx：客户端错误
 * - 5xxx：业务错误（LLM/工具/记忆/RAG/MCP 等）
 * - 5000：系统未知异常
 * </p>
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    // 成功
    SUCCESS(200, "成功"),

    // 客户端错误
    PARAM_INVALID(400, "参数无效"),
    UNAUTHORIZED(401, "未授权"),
    FORBIDDEN(403, "禁止访问"),
    NOT_FOUND(404, "资源不存在"),

    // LLM 相关错误（5001-5099）
    LLM_CALL_FAILED(5001, "LLM 调用失败"),
    LLM_TIMEOUT(5002, "LLM 调用超时"),
    LLM_RATE_LIMITED(5003, "LLM 调用被限流"),
    LLM_API_KEY_INVALID(5004, "LLM API Key 无效"),
    /** 模型名称未配置（CR-002 新增） */
    LLM_MODEL_NOT_CONFIGURED(5005, "LLM 模型未配置"),
    /** LLM 提供商未注册（CR-002 Task-24 新增）：编排层在注册表中未找到对应 providerCode 的厂商策略实现 */
    LLM_PROVIDER_NOT_FOUND(5006, "LLM 提供商未注册"),
    /** LLM 能力不支持（CR-002 Task-24 新增）：厂商未实现某能力接口（如视觉对话），调用该能力方法时抛出 */
    LLM_CAPABILITY_NOT_SUPPORTED(5007, "LLM 能力不支持"),
    /** LLM 配置不存在（动态配置功能新增） */
    LLM_CONFIG_NOT_FOUND(5008, "LLM 配置不存在"),
    /** 厂商不存在（按 ID 查找未命中时抛出） */
    LLM_VENDOR_NOT_FOUND(5009, "厂商不存在"),
    /** 模型不存在（按 ID 查找未命中时抛出） */
    LLM_MODEL_NOT_FOUND(5010, "模型不存在"),
    /** 厂商名称已存在（重复添加同名厂商时抛出） */
    LLM_VENDOR_NAME_EXISTS(5011, "厂商名称已存在"),
    /** 同类型同名模型已存在（同一厂商下同一类型重复模型名时抛出） */
    LLM_MODEL_NAME_EXISTS(5012, "同类型同名模型已存在"),
    /** 连接测试失败（API Key 验证不通过时返回） */
    LLM_CONNECTION_TEST_FAILED(5013, "连接测试失败"),
    /** 未配置 chat 模型（无可用对话模型时抛出） */
    LLM_NO_CHAT_MODEL(5014, "未配置 chat 模型"),
    /** 未配置 embedding 模型（RAG 向量化无可用模型时抛出） */
    LLM_NO_EMBEDDING_MODEL(5015, "未配置 embedding 模型"),

    // 工具相关错误（5100-5199）
    TOOL_EXECUTION_FAILED(5100, "工具执行失败"),
    TOOL_NOT_FOUND(5101, "工具不存在"),
    TOOL_PARAM_INVALID(5102, "工具参数无效"),

    // 记忆相关错误（5200-5299）
    MEMORY_NOT_FOUND(5200, "会话记忆不存在"),
    SESSION_NOT_FOUND(5201, "会话不存在"),
    SESSION_EXPIRED(5202, "会话已过期"),

    // RAG 相关错误（5300-5399）
    RAG_RETRIEVE_FAILED(5300, "知识检索失败"),
    RAG_EMBEDDING_FAILED(5301, "文本向量化失败"),
    RAG_DOCUMENT_LOAD_FAILED(5302, "文档加载失败"),
    RAG_DOCUMENT_PARSE_FAILED(5303, "文档解析失败"),
    RAG_VECTOR_STORE_INIT_FAILED(5304, "向量存储初始化失败"),
    RAG_KNOWLEDGE_BASE_NOT_FOUND(5305, "知识库不存在"),
    RAG_DOCUMENT_NOT_FOUND(5306, "文档不存在"),
    RAG_KNOWLEDGE_BASE_NAME_EXISTS(5307, "知识库名称已存在"),
    RAG_DOCUMENT_SIZE_EXCEEDED(5308, "文档大小超过限制"),
    RAG_DOCUMENT_FORMAT_UNSUPPORTED(5309, "不支持的文档格式"),

    // MCP 相关错误（5400-5499）
    MCP_CONNECTION_FAILED(5400, "MCP 连接失败"),
    MCP_TOOL_CALL_FAILED(5401, "MCP 工具调用失败"),
    /** MCP Server 名称已存在（重复添加同名 Server 时抛出） */
    MCP_SERVER_NAME_EXISTS(5402, "MCP Server 名称已存在"),
    /** MCP Server 不存在（操作不存在的 Server 时抛出） */
    MCP_SERVER_NOT_FOUND(5403, "MCP Server 不存在"),
    /** MCP 传输方式不支持（transport 非 stdio/sse 时抛出） */
    MCP_TRANSPORT_UNSUPPORTED(5404, "MCP 传输方式不支持"),
    /** MCP 模块已禁用（mcp.enabled=false 时所有 MCP API 调用抛出） */
    MCP_MODULE_DISABLED(5405, "MCP 模块已禁用"),
    /** MCP Server 已连接（重连已 CONNECTED 状态的 Server 时抛出） */
    MCP_SERVER_ALREADY_CONNECTED(5406, "MCP Server 已连接"),

    // 系统错误
    SYSTEM_ERROR(5000, "系统异常");

    /**
     * 业务错误码
     */
    private final int code;

    /**
     * 错误信息
     */
    private final String message;
}
