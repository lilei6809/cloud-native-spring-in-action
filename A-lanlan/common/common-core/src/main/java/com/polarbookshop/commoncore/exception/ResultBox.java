package com.polarbookshop.commoncore.exception;

import lombok.Data;

@Data
public class ResultBox<T> {
    private boolean success; // 标识请求是否成功 (非必须，但前端很喜欢)
    private String code; // 业务错误码
    private String message; // 提示信息
    private T data;  // 核心数据！用泛型 <T> 保证各种类型都能装
    private String traceId; // 日志追踪 ID

    private ResultBox() {}

    public static <T> ResultBox<T> success(T data) {
        ResultBox<T> resultBox = new ResultBox<>();
        resultBox.setSuccess(true);
        resultBox.setCode("200");
        resultBox.setData(data);
        resultBox.setMessage("success");
        // result.setTraceId(MDC.get("traceId")); // 实际企业中会从日志上下文中取
        return resultBox;
    }

    public static <T> ResultBox<T> fail(String code, String message) {
        ResultBox<T> resultBox = new ResultBox<>();
        resultBox.setSuccess(false);
        resultBox.setCode(code);
        resultBox.setMessage(message);
        resultBox.setData(null);
        return resultBox;
    }

}
