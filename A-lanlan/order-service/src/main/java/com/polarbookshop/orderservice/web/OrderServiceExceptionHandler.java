package com.polarbookshop.orderservice.web;

import com.polarbookshop.commoncore.exception.ResultBox;
import feign.RetryableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class OrderServiceExceptionHandler {

    @ExceptionHandler(RetryableException.class)
    public ResultBox<?> handleRetryableException(RetryableException e) {
        log.error("Downstream service unavailable: {}", e.getMessage(), e);
        return ResultBox.fail("B1002", "依赖服务暂不可用，请稍后再试");
    }
}
