package com.polarbookshop.commonweb;

import com.polarbookshop.commoncore.exception.BusinessException;
import com.polarbookshop.commoncore.exception.ResultBox;
import com.polarbookshop.commoncore.exception.SystemException;
import feign.RetryableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    private static final String DEFAULT_BUSINESS_CODE = "A0001";
    private static final String VALIDATION_ERROR_CODE = "A0105";
    private static final String DEFAULT_SYSTEM_CODE = "B0001";
    private static final String DOWNSTREAM_UNAVAILABLE_CODE = "B1002";
    private static final String GENERIC_SERVER_MESSAGE = "服务器开小差了，请稍后再试";
    private static final String DOWNSTREAM_UNAVAILABLE_MESSAGE = "依赖服务暂不可用，请稍后再试";

    // 🎯 拦截点 1：精准拦截我们自己抛出的 BusinessException
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ResultBox<?>> handleBusinessException(BusinessException e) {
        // 业务异常通常是用户行为（如密码错误），用 WARN 级别记录即可
        log.warn("业务阻断: code={}, msg={}", e.getCode(), e.getMessage());

        // 转化为标准的 JSON 返回体
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ResultBox.fail(resolveCode(e.getCode(), DEFAULT_BUSINESS_CODE), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ResultBox<?>> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("请求参数校验失败");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ResultBox.fail(VALIDATION_ERROR_CODE, message));
    }

    // 🎯 拦截点 2：可预期的系统异常（如下游服务超时、资源不可用）
    @ExceptionHandler(SystemException.class)
    public ResponseEntity<ResultBox<?>> handleSystemException(SystemException e) {
        log.error("系统异常: code={}, msg={}", e.getCode(), e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ResultBox.fail(resolveCode(e.getCode(), DEFAULT_SYSTEM_CODE), e.getMessage()));
    }

    @ExceptionHandler(RetryableException.class)
    public ResponseEntity<ResultBox<?>> handleRetryableException(RetryableException e) {
        log.error("Downstream service unavailable: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ResultBox.fail(DOWNSTREAM_UNAVAILABLE_CODE, DOWNSTREAM_UNAVAILABLE_MESSAGE));
    }

    // 🎯 拦截点 3：兜底拦截所有未知崩溃 (如 NullPointerException)
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ResultBox<?>> handleUnknownException(Exception e) {
        log.error("系统未知异常: ", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ResultBox.fail(DEFAULT_SYSTEM_CODE, GENERIC_SERVER_MESSAGE));
    }

    private String resolveCode(String code, String defaultCode) {
        return code == null || code.isBlank() ? defaultCode : code;
    }

}
