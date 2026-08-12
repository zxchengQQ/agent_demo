package com.agentdemo.web.dto;

import lombok.Data;

/**
 * 测试连接响应 DTO
 * <p>
 * 业务含义：返回连接测试结果，包含是否成功、描述信息和请求耗时，
 * 前端据此展示测试状态和延迟。
 * </p>
 */
@Data
public class TestConnectionResponse {

    /** 是否连接成功 */
    private boolean success;

    /** 结果描述信息 */
    private String message;

    /** 请求耗时（毫秒） */
    private long latency;
}
