package com.polarbookshop.edgeserver.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

@Configuration
public class RateLimiterConfig {

    @Bean
    public KeyResolver keyResolver() {
        // 接收一个 ServerWebExchange, 输出一个 redis 使用的 key
        // 我们目的是根据 userId 生成 key, 但是需要使用 spring security 后实现
        // 现在固定返回 "anonymous"，所以所有请求都会共用同一个限流桶
        return exchange -> Mono.just("anonymous");
    }
}
