package com.polarbookshop.orderservice.config;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

@FeignClient(name = "catalog-service",
        url = "${polar.catalog-service-uri}/books"
        ,contextId = "catalogLongRequestClient")
public interface CatalogLongRequestClient {

    @GetMapping("/longReadTimeOut")
    String longReadTimeOut();
}
