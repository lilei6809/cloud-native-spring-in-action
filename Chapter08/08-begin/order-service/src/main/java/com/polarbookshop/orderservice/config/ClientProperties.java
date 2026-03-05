package com.polarbookshop.orderservice.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

@ConfigurationProperties(prefix = "polar")
public record ClientProperties(

        @NotNull
        URI catalogServiceUri // 对应 polar.catalog-service-uri

) {
}
