package com.agentdemo.common.constant;

/**
 * HTTP 状态码常量
 * <p>
 * 业务含义：与 ErrorCode 区分，此处定义标准 HTTP 状态码，用于 Web 层响应。
 * ErrorCode 是业务状态码（如 5001 LLM 调用失败），StatusCode 是 HTTP 协议状态码。
 * </p>
 */
public final class StatusCode {

    private StatusCode() {
        // 工具类禁止实例化
    }

    public static final int OK = 200;
    public static final int CREATED = 201;
    public static final int NO_CONTENT = 204;
    public static final int BAD_REQUEST = 400;
    public static final int UNAUTHORIZED = 401;
    public static final int FORBIDDEN = 403;
    public static final int NOT_FOUND = 404;
    public static final int METHOD_NOT_ALLOWED = 405;
    public static final int CONFLICT = 409;
    public static final int UNPROCESSABLE_ENTITY = 422;
    public static final int TOO_MANY_REQUESTS = 429;
    public static final int INTERNAL_SERVER_ERROR = 500;
    public static final int SERVICE_UNAVAILABLE = 503;
    public static final int GATEWAY_TIMEOUT = 504;
}
