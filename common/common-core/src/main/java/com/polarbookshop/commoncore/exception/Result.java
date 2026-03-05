package com.polarbookshop.commoncore.exception;

import lombok.Data;

@Data
public class Result<T> {
    private boolean success; // 标识请求是否成功 (非必须，但前端很喜欢)
    private String code; // 业务错误码
    private String message; // 提示信息
    private T data;  // 核心数据！用泛型 <T> 保证各种类型都能装
    private String traceId; // 日志追踪 ID

    private Result() {}

    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.setSuccess(true);
        result.setCode("200");
        result.setData(data);
        result.setMessage("success");
        // result.setTraceId(MDC.get("traceId")); // 实际企业中会从日志上下文中取
        return result;
    }

    public static <T> Result<T> fail(String code, String message) {
        Result<T> result = new Result<>();
        result.setSuccess(false);
        result.setCode(code);
        result.setMessage(message);
        result.setData(null);
        return result;
    }

}
