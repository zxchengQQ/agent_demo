package com.agentdemo.common.result;

import com.agentdemo.common.exception.ErrorCode;
import lombok.Data;
import org.slf4j.MDC;

import java.io.Serializable;

/**
 * 统一返回结果
 * <p>
 * 业务含义：所有 API 接口的统一返回结构，前端根据 code 判断业务是否成功，
 * 通过 traceId 串联整条调用链路便于问题定位。
 * </p>
 * <p>
 * 设计原则：
 * 1. 所有 Controller 返回 Result&lt;T&gt;，禁止返回裸数据
 * 2. success 字段便于前端快速判断，code 字段用于精细处理
 * 3. traceId 从 MDC 获取，贯穿整个请求链路
 * </p>
 *
 * @param <T> 业务数据类型
 */
@Data
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 业务状态码（参考 ErrorCode）
     */
    private int code;

    /**
     * 提示信息
     */
    private String message;

    /**
     * 业务数据
     */
    private T data;

    /**
     * 链路追踪 ID（从 MDC 获取，用于日志串联）
     */
    private String traceId;

    public Result() {
        this.traceId = MDC.get("traceId");
    }

    /**
     * 成功返回（带数据）
     *
     * @param data 业务数据
     * @param <T>  数据类型
     * @return 成功结果
     */
    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.setSuccess(true);
        result.setCode(ErrorCode.SUCCESS.getCode());
        result.setMessage(ErrorCode.SUCCESS.getMessage());
        result.setData(data);
        return result;
    }

    /**
     * 成功返回（带数据与自定义提示）
     *
     * @param data    业务数据
     * @param message 提示信息
     * @param <T>     数据类型
     * @return 成功结果
     */
    public static <T> Result<T> success(T data, String message) {
        Result<T> result = new Result<>();
        result.setSuccess(true);
        result.setCode(ErrorCode.SUCCESS.getCode());
        result.setMessage(message);
        result.setData(data);
        return result;
    }

    /**
     * 成功返回（无数据）
     *
     * @param <T> 数据类型
     * @return 成功结果
     */
    public static <T> Result<T> success() {
        return success(null);
    }

    /**
     * 失败返回（基于 ErrorCode）
     *
     * @param errorCode 业务错误码
     * @param <T>       数据类型
     * @return 失败结果
     */
    public static <T> Result<T> error(ErrorCode errorCode) {
        Result<T> result = new Result<>();
        result.setSuccess(false);
        result.setCode(errorCode.getCode());
        result.setMessage(errorCode.getMessage());
        return result;
    }

    /**
     * 失败返回（基于 ErrorCode 与补充信息）
     *
     * @param errorCode 业务错误码
     * @param message   补充信息
     * @param <T>       数据类型
     * @return 失败结果
     */
    public static <T> Result<T> error(ErrorCode errorCode, String message) {
        Result<T> result = new Result<>();
        result.setSuccess(false);
        result.setCode(errorCode.getCode());
        result.setMessage(message);
        return result;
    }

    /**
     * 失败返回（自定义 code 与 message）
     *
     * @param code    状态码
     * @param message 提示信息
     * @param <T>     数据类型
     * @return 失败结果
     */
    public static <T> Result<T> error(int code, String message) {
        Result<T> result = new Result<>();
        result.setSuccess(false);
        result.setCode(code);
        result.setMessage(message);
        return result;
    }
}
