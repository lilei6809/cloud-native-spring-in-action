package com.polarbookshop.orderservice.config;

import com.polarbookshop.orderservice.model.Book;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "catalog-service",
        url = "${polar.catalog-service-uri}"
        ,contextId = "catalogClient")
public interface CatalogClient {

    @GetMapping("/books/{isbn}")
    Book getBookByIsbn(@PathVariable("isbn") String isbn);
}
