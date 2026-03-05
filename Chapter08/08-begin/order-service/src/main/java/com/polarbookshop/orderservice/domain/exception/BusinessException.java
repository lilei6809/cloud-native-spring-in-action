package com.polarbookshop.orderservice.domain.exception;

public class BusinessException extends RuntimeException {
    private String code; // 业务错误码，如 "A0400"

    public BusinessException(String code, String message) {
        super(message);
        this.code = code;
    }
}
