package com.polarbookshop.orderservice.config;

import com.polarbookshop.commoncore.exception.ResultBox;
import com.polarbookshop.orderservice.model.Book;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class CatalogClientFallbackFactory  implements FallbackFactory<CatalogClient> {
    @Override
    public CatalogClient create(Throwable cause) {
        return new CatalogClient() {
            @Override
            public ResultBox<Book> getBookByIsbn(String isbn) {
                // // 1. 记下致命日志，发送钉钉/微信报警
                log.error("Catalog 服务调用失败! 原因: {}", cause.getMessage());
                return ResultBox.fail("3010", cause.getMessage());
            }
        };
    }
}
