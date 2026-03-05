package com.polarbookshop.commoncore.exception;

public class BusinessException extends RuntimeException{
    private static final long serialVersionUID = 1L;

    private String code;

    public BusinessException(String message, String code){
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
