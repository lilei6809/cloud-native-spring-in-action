package com.polarbookshop.orderservice.config;

import com.polarbookshop.commoncore.exception.ResultBox;
import com.polarbookshop.commoncore.exception.SystemException;
import com.polarbookshop.orderservice.model.Book;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class CatalogClientFallbackFactory implements FallbackFactory<CatalogClient> {

    @Override
    public CatalogClient create(Throwable cause) {
        return new CatalogClient() {
            @Override
            public ResponseEntity<ResultBox<Book>> getBookByIsbn(String isbn) {
                if (cause instanceof CallNotPermittedException) {
                    // Circuit Breaker 已打开：快速失败，不发起真实请求
                    //TODO: 发送告警事件 (钉钉/Telegram/PagerDuty)

                    log.warn("Circuit breaker OPEN, catalog-service 降级中, isbn={}", isbn);
                    throw new SystemException("书目服务降级中，请稍后重试", "B3001");
                }
                // 网络故障 / 超时 / 重试耗尽
                log.error("Catalog 服务调用失败 isbn={}, cause={}", isbn, cause.getMessage(), cause);
                throw new SystemException("catalog-service 调用失败: " + cause.getMessage(), "B1001");
            }
        };
    }
}