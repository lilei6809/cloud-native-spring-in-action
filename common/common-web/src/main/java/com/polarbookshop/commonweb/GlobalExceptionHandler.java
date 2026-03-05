package com.polarbookshop.commonweb;

import com.polarbookshop.commoncore.exception.BusinessException;
import com.polarbookshop.commoncore.exception.Result;
import com.polarbookshop.commoncore.exception.SystemException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // 🎯 拦截点 1：精准拦截我们自己抛出的 BusinessException
    @ExceptionHandler(BusinessException.class)
    public Result<?> handleBusinessException(BusinessException e) {
        // 业务异常通常是用户行为（如密码错误），用 WARN 级别记录即可
        log.warn("业务阻断: code={}, msg={}", e.getCode(), e.getMessage());

        // 转化为标准的 JSON 返回体
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<?> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        return Result.fail("A0105", e.getMessage());
    }

    // 🎯 拦截点 2：可预期的系统异常（如下游服务超时、资源不可用）
    @ExceptionHandler(SystemException.class)
    public Result<?> handleSystemException(SystemException e) {
        log.error("系统异常: code={}, msg={}", e.getCode(), e.getMessage(), e);
        return Result.fail(e.getCode(), "服务器开小差了，请稍后再试");
    }

    // 🎯 拦截点 3：兜底拦截所有未知崩溃 (如 NullPointerException)
    @ExceptionHandler(Exception.class)
    public Result<?> handleUnknownException(Exception e) {
        log.error("系统未知异常: ", e);
        return Result.fail("B0001", "服务器开小差了，请稍后再试");
    }

}
