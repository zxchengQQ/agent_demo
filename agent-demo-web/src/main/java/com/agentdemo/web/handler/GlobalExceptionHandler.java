package com.agentdemo.web.handler;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.common.result.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理
 * <p>
 * 业务含义：统一捕获所有异常，返回标准 Result 结构，避免向前端暴露内部错误细节。
 * 异常分类处理：
 * 1. BusinessException：业务异常，返回对应 ErrorCode
 * 2. MethodArgumentNotValidException：参数校验异常，返回 400
 * 3. Exception：未知异常，返回 500，记录完整堆栈
 * </p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 业务异常处理
     *
     * @param e 业务异常
     * @return 标准错误结果
     */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getErrorCode().getCode(), e.getMessage());
        return Result.error(e.getErrorCode(), e.getMessage());
    }

    /**
     * 参数校验异常处理
     *
     * @param e 校验异常
     * @return 标准错误结果
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError != null ? fieldError.getDefaultMessage() : "参数校验失败";
        log.warn("参数校验异常: {}", message);
        return Result.error(ErrorCode.PARAM_INVALID, message);
    }

    /**
     * 静态资源/接口不存在异常处理
     * <p>
     * 业务含义：客户端访问了不存在的路径（如未引入 actuator 时访问 /actuator/health），
     * 返回 404 而非 500，且不打印堆栈避免污染日志。
     * </p>
     *
     * @param e 资源未找到异常
     * @return 标准错误结果
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNoResourceFound(NoResourceFoundException e) {
        log.warn("资源不存在: {}", e.getMessage());
        return Result.error(ErrorCode.NOT_FOUND);
    }

    /**
     * 未知异常处理
     * 业务含义：捕获所有未处理的异常，返回系统错误，记录完整堆栈便于定位
     *
     * @param e 未知异常
     * @return 标准错误结果
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleUnknown(Exception e) {
        log.error("未知异常", e);
        return Result.error(ErrorCode.SYSTEM_ERROR);
    }
}
