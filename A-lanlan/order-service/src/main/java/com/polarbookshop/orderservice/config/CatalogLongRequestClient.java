package com.polarbookshop.orderservice.config;

import com.polarbookshop.commoncore.exception.ResultBox;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

@FeignClient(name = "catalog-service",
        url = "${polar.catalog-service-uri}/books"
        ,contextId = "catalogLongRequestClient")
public interface CatalogLongRequestClient {

    @GetMapping("/longReadTimeOut")
    ResultBox<String> longReadTimeOut();
}
