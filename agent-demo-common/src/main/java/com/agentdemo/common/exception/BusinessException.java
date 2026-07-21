package com.agentdemo.common.exception;

import lombok.Getter;

/**
 * 业务异常
 * <p>
 * 业务含义：所有业务层抛出的异常统一使用 BusinessException，携带 ErrorCode 标识错误类型。
 * 设计原则：
 * 1. 禁止以 null 作为 errorCode 参数传递，通过方法重载处理不同场景
 * 2. 全局异常处理器 GlobalExceptionHandler 统一捕获并转换为 Result 返回
 * 3. 异常不吞噬，必要信息通过 detail 或 cause 传递
 * </p>
 */
@Getter
public class BusinessException extends RuntimeException {

    /**
     * 业务错误码
     */
    private final ErrorCode errorCode;

    /**
     * 补充详情（可选，用于补充 ErrorCode 之外的具体信息）
     */
    private final String detail;

    /**
     * 仅指定错误码
     *
     * @param errorCode 业务错误码（禁止为 null）
     */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.detail = null;
    }

    /**
     * 指定错误码与补充详情
     *
     * @param errorCode 业务错误码（禁止为 null）
     * @param detail    补充详情
     */
    public BusinessException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
        this.detail = detail;
    }

    /**
     * 指定错误码与根因异常
     *
     * @param errorCode 业务错误码（禁止为 null）
     * @param cause     根因异常
     */
    public BusinessException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
        this.detail = cause != null ? cause.getMessage() : null;
    }

    /**
     * 指定错误码、补充详情与根因异常
     *
     * @param errorCode 业务错误码（禁止为 null）
     * @param detail    补充详情
     * @param cause     根因异常
     */
    public BusinessException(ErrorCode errorCode, String detail, Throwable cause) {
        super(detail, cause);
        this.errorCode = errorCode;
        this.detail = detail;
    }
}
