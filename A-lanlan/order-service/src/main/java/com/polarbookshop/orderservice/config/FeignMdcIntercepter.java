package com.polarbookshop.orderservice.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 *  远程调用会导致 MDC 信息丢失, 解决方式：在发请求时把 MDC 值塞进请求头，接收方 Filter 再读出来放回 MDC。
 *  response 不用管, 阻塞等待/ 虚拟线程恢复 load
 */
@Component
public class FeignMdcIntercepter implements RequestInterceptor {
    @Override
    public void apply(RequestTemplate template) {
        String tenantId = MDC.get("tenantId");
        String userId = MDC.get("userId");

        if (tenantId != null) template.header("tenantId", tenantId);
        if (userId != null) template.header("userId", userId);
    }
}
