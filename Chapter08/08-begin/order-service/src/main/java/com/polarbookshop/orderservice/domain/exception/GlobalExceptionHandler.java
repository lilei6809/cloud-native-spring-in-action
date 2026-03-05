package com.polarbookshop.orderservice.domain.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

@ControllerAdvice
@Slf4j
public class GlobalExceptionHandler {


    @ExceptionHandler(BookNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    String handleBookNotFoundException(BookNotFoundException ex, HttpServletRequest request) {
        return ex.getMessage();
    }

    @ExceptionHandler(BusinessException.class)
    String handleBusinessException(BusinessException ex, HttpServletRequest request) {
        return ex.getMessage();
    }
}

